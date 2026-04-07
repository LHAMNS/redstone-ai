package com.redstoneai.test;

import com.redstoneai.config.RAIConfig;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.recording.RecordingTimeline;
import com.redstoneai.recording.TickSnapshot;
import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.InitialSnapshot;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceSignalController;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates test execution: for each test case, sets input signals,
 * steps the workspace by the required number of ticks, then reads output
 * power levels and compares against expected values.
 * <p>
 * Between test cases, the workspace is rewound to its initial state
 * to ensure isolation. All methods must be called on the server thread.
 */
public final class TestRunner {
    private static final FrozenTickQueue.QueueState EMPTY_QUEUE_STATE =
            new FrozenTickQueue.QueueState(List.of(), List.of(), List.of(), List.of());

    private TestRunner() {}

    /**
     * Run a single test case against a workspace.
     * The workspace should be frozen before calling this.
     */
    public static TestResult runSingle(ServerLevel level, Workspace ws, TestCase testCase) {
        validateTickCount(testCase.ticks());
        ensureFrozen(level, ws);
        applyInputs(level, ws, testCase.inputs());
        TickController.step(level, ws, testCase.ticks());

        Map<String, Integer> actual = readOutputs(level, ws);
        boolean passed = checkExpected(testCase.expected(), actual);

        return new TestResult(testCase, passed, actual, testCase.ticks());
    }

    /**
     * Run an entire test suite. Rewinds the workspace between each case.
     * Returns a list of results in the same order as the suite's cases.
     */
    public static List<TestResult> runSuite(ServerLevel level, Workspace ws, TestSuite suite) {
        validateSuiteWorkBudget(suite);
        SuiteRestoreState restoreState = captureRestoreState(ws);
        ensureFrozen(level, ws);
        SuiteBaseline suiteBaseline = captureSuiteBaseline(level, ws);

        try {
            List<TestResult> results = new ArrayList<>(suite.size());
            for (TestCase testCase : suite.getCases()) {
                validateTickCount(testCase.ticks());
                prepareIsolatedRun(level, ws, restoreState, suiteBaseline);
                results.add(runSingle(level, ws, testCase));
            }
            return results;
        } finally {
            restoreSuiteState(level, ws, restoreState, suiteBaseline);
        }
    }

    /**
     * Summarize test results as a compact string.
     */
    public static String summarize(List<TestResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(TestResult::passed).count();
        int failed = total - passed;

        StringBuilder sb = new StringBuilder();
        sb.append("Tests: ").append(passed).append("/").append(total).append(" passed");
        if (failed > 0) {
            sb.append(" (").append(failed).append(" FAILED)");
            for (TestResult r : results) {
                if (!r.passed()) {
                    sb.append("\n  FAIL");
                    if (!r.testCase().name().isEmpty()) {
                        sb.append(" [").append(r.testCase().name()).append("]");
                    }
                    sb.append(": in=").append(r.testCase().inputs());
                    sb.append(" expected=").append(r.testCase().expected());
                    sb.append(" actual=").append(r.actual());
                }
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────────

    private static void ensureFrozen(ServerLevel level, Workspace ws) {
        if (!ws.isFrozen()) {
            TickController.freeze(level, ws);
        }
    }

    private static void applyInputs(ServerLevel level, Workspace ws, Map<String, Integer> inputs) {
        if (inputs.isEmpty()) {
            return;
        }
        WorkspaceSignalController.setInputs(level, ws, inputs);
    }

    private static Map<String, Integer> readOutputs(ServerLevel level, Workspace ws) {
        return new LinkedHashMap<>(WorkspaceSignalController.readSignals(level, ws, IOMarker.IORole.OUTPUT));
    }

    private static boolean checkExpected(Map<String, Integer> expected, Map<String, Integer> actual) {
        for (var entry : expected.entrySet()) {
            Integer actualVal = actual.get(entry.getKey());
            if (actualVal == null || !actualVal.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void prepareIsolatedRun(ServerLevel level,
                                           Workspace ws,
                                           SuiteRestoreState restoreState,
                                           SuiteBaseline suiteBaseline) {
        clearRuntimeState(level, ws);
        suiteBaseline.blockSnapshot().restore(level);
        restoreEntities(level, ws, suiteBaseline.entitySnapshots());
        ws.setTimeline(null);
        ws.setVirtualTick(0);
        TickController.freeze(level, ws);
        FrozenTickQueue queue = TickController.getQueue(ws);
        if (queue != null) {
            queue.restore(suiteBaseline.queueState());
        }
        ws.setVirtualTick(restoreState.originalVirtualTick());
    }

    private static void restoreSuiteState(ServerLevel level,
                                          Workspace ws,
                                          SuiteRestoreState restoreState,
                                          SuiteBaseline suiteBaseline) {
        clearRuntimeState(level, ws);
        suiteBaseline.blockSnapshot().restore(level);
        restoreEntities(level, ws, suiteBaseline.entitySnapshots());
        ws.setTimeline(restoreState.originalTimeline());
        ws.setVirtualTick(restoreState.originalVirtualTick());

        if (!restoreState.wasFrozen()) {
            TickController.removeQueue(ws.getId());
            return;
        }

        TickController.freeze(level, ws);
        // freeze() rebuilt the timeline — restore the original one AFTER freeze
        ws.setTimeline(restoreState.originalTimeline());
        ws.setVirtualTick(restoreState.originalVirtualTick());
        FrozenTickQueue queue = TickController.getQueue(ws);
        if (queue != null) {
            queue.restore(restoreState.originalQueueState());
        }
    }

    private static void clearRuntimeState(ServerLevel level, Workspace ws) {
        if (ws.isFrozen()) {
            TickController.discardFrozenState(level, ws);
        } else {
            TickController.removeQueue(ws.getId());
        }
    }

    private static FrozenTickQueue.QueueState snapshotQueueState(Workspace ws) {
        FrozenTickQueue queue = TickController.getQueue(ws);
        return queue != null ? queue.snapshot() : EMPTY_QUEUE_STATE;
    }

    private static void validateTickCount(int ticks) {
        if (!WorkspaceRules.isValidTickCount(ticks)) {
            throw new IllegalArgumentException("Tick count exceeds configured maximum");
        }
    }

    private static void validateSuiteWorkBudget(TestSuite suite) {
        long totalTicks = 0L;
        for (TestCase testCase : suite.getCases()) {
            totalTicks += testCase.ticks();
            if (totalTicks > RAIConfig.SERVER.maxRecordingTicks.get()) {
                throw new IllegalArgumentException("Suite total tick budget exceeds configured maximum");
            }
        }
    }

    private static SuiteRestoreState captureRestoreState(Workspace ws) {
        return new SuiteRestoreState(ws.isFrozen(), ws.getTimeline(), ws.getVirtualTick(), snapshotQueueState(ws));
    }

    private static SuiteBaseline captureSuiteBaseline(ServerLevel level, Workspace ws) {
        return new SuiteBaseline(
                InitialSnapshot.capture(level, ws.getBounds()),
                captureEntities(level, ws),
                snapshotQueueState(ws)
        );
    }

    private static List<TickSnapshot.EntitySnapshot> captureEntities(ServerLevel level, Workspace ws) {
        AABB aabb = ws.getAABB();
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, aabb,
                entity -> !(entity instanceof Player)
                        && ws.containsEntityFully(entity)
                        && ws.getEntityFilterMode().shouldAffect(entity));

        List<TickSnapshot.EntitySnapshot> snapshots = new ArrayList<>();
        for (Entity entity : entities) {
            CompoundTag nbt = new CompoundTag();
            entity.saveWithoutId(nbt);
            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            nbt.putString("id", entityId);
            snapshots.add(new TickSnapshot.EntitySnapshot(entity.getUUID(), entityId, nbt.copy()));
        }
        return List.copyOf(snapshots);
    }

    private static void restoreEntities(ServerLevel level, Workspace ws, List<TickSnapshot.EntitySnapshot> entitySnapshots) {
        TickController.restoreEntityStates(level, ws, entitySnapshots);
    }

    private record SuiteRestoreState(
            boolean wasFrozen,
            @Nullable RecordingTimeline originalTimeline,
            int originalVirtualTick,
            FrozenTickQueue.QueueState originalQueueState
    ) {}

    private record SuiteBaseline(
            InitialSnapshot blockSnapshot,
            List<TickSnapshot.EntitySnapshot> entitySnapshots,
            FrozenTickQueue.QueueState queueState
    ) {}
}
