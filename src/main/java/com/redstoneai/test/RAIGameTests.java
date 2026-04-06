package com.redstoneai.test;

import com.redstoneai.RedstoneAI;
import com.redstoneai.mcr.MCRBlock;
import com.redstoneai.mcr.MCRParser;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.network.rpc.handlers.IOHandler;
import com.redstoneai.network.rpc.handlers.TestHandler;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.TickPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * GameTests verifying core RedstoneAI functionality:
 * MCR parsing, workspace management, tick freeze/step, recording, boundary checks.
 * <p>
 * All tests use {@code template = "forge:empty3x3x3"} for a minimal empty structure.
 */
@GameTestHolder(RedstoneAI.ID)
@PrefixGameTestTemplate(false)
public class RAIGameTests {

    private static final String T = "forge:empty3x3x3";

    // ── MCR Parser ──────────────────────────────────────────────────

    @GameTest(template = T, batch = "mcr")
    public static void mcr_simple_parse(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("# D R");
            helper.assertTrue(blocks.size() == 3, "Expected 3 blocks");
            helper.assertTrue("stone".equals(blocks.get(0).blockType()), "First=stone");
            helper.assertTrue("redstone_wire".equals(blocks.get(1).blockType()), "Second=wire");
            helper.assertTrue("repeater".equals(blocks.get(2).blockType()), "Third=repeater");
        } catch (MCRParser.MCRParseException e) {
            helper.fail("Parse failed: " + e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_modifiers(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("Rn2 Cex");
            MCRBlock r = blocks.get(0);
            helper.assertTrue(r.delay() == 2, "delay=2");
            helper.assertTrue(r.facing().getName().equals("north"), "facing=north");
            MCRBlock c = blocks.get(1);
            helper.assertTrue("subtract".equals(c.mode()), "mode=subtract");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_directives(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("@origin 1,2,3 # @row # @layer #");
            helper.assertTrue(blocks.get(0).x() == 1 && blocks.get(0).y() == 2 && blocks.get(0).z() == 3, "origin");
            helper.assertTrue(blocks.get(1).x() == 1 && blocks.get(1).z() == 4, "@row z+1");
            helper.assertTrue(blocks.get(2).y() == 3 && blocks.get(2).z() == 3, "@layer y+1 z-reset");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_invalid_code_throws(GameTestHelper helper) {
        try {
            MCRParser.parse("Z");
            helper.fail("Should throw");
        } catch (MCRParser.MCRParseException e) {
            helper.assertTrue(e.getMessage().contains("Unknown block code"), "msg");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_fill_directive(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("@fill #");
            helper.assertTrue(blocks.size() == 1, "1 block");
            helper.assertTrue("fill".equals(blocks.get(0).extraProperties().get("_directive")), "fill directive");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_all_codes_parse(GameTestHelper helper) {
        try {
            MCRParser.parse("D R C T W P K O H L B # G S N _ A X I J Q");
            helper.succeed();
        } catch (MCRParser.MCRParseException e) {
            helper.fail("All codes should parse: " + e.getMessage());
        }
    }

    // ── Workspace + Tick Control ────────────────────────────────────

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_freeze_unfreeze(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_freeze", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            helper.assertTrue(ws.isFrozen(), "frozen");
            helper.assertTrue(ws.getTimeline() != null, "timeline created");

            TickController.unfreeze(level, ws);
            helper.assertTrue(!ws.isFrozen(), "unfrozen");
        } finally {
            if (ws.isFrozen()) TickController.unfreeze(level, ws);
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_freeze");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_step_increments_vtick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_step", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            int stepped = TickController.step(level, ws, 10);
            helper.assertTrue(stepped == 10, "stepped 10");
            helper.assertTrue(ws.getVirtualTick() == 10, "vtick=10");
        } finally {
            if (ws.isFrozen()) TickController.unfreeze(level, ws);
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_step");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_overlap_detection(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox b1 = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 7, abs.getY() + 3, abs.getZ() + 7);
        Workspace ws1 = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_ovlp1", abs, b1);
        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.addWorkspace(ws1);

        try {
            // Overlapping
            BoundingBox b2 = new BoundingBox(abs.getX() + 4, abs.getY(), abs.getZ(),
                    abs.getX() + 11, abs.getY() + 3, abs.getZ() + 7);
            helper.assertTrue("gt_ovlp1".equals(manager.checkOverlap(b2)), "overlap detected");

            // Non-overlapping
            BoundingBox b3 = new BoundingBox(abs.getX() + 100, abs.getY(), abs.getZ(),
                    abs.getX() + 107, abs.getY() + 3, abs.getZ() + 7);
            helper.assertTrue(manager.checkOverlap(b3) == null, "no overlap");
        } finally {
            manager.removeWorkspace("gt_ovlp1");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_restores_frozen_queue_and_virtual_tick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_frozen", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            FrozenTickQueue queue = Objects.requireNonNull(TickController.getQueue(ws), "queue");
            queue.addBlockTick(abs.offset(1, 0, 0), Blocks.STONE, 2, TickPriority.NORMAL);
            ws.setVirtualTick(7);

            FrozenTickQueue.QueueState beforeQueue = queue.snapshot();
            List<TestCase> cases = List.of(
                    new TestCase("case1", Map.of(), Map.of(), 1),
                    new TestCase("case2", Map.of(), Map.of(), 1)
            );

            List<TestResult> results = TestRunner.runSuite(level, ws, new TestSuite("suite_frozen", cases));
            helper.assertTrue(results.size() == 2, "two results");
            helper.assertTrue(results.stream().allMatch(TestResult::passed), "suite passed");
            helper.assertTrue(ws.isFrozen(), "workspace restored frozen");
            helper.assertTrue(ws.getVirtualTick() == 7, "virtual tick restored");
            helper.assertTrue(beforeQueue.equals(Objects.requireNonNull(TickController.getQueue(ws)).snapshot()),
                    "queue restored");
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_frozen");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_restores_unfrozen_workspace_state(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_unfrozen", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            List<TestResult> results = TestRunner.runSuite(level, ws, new TestSuite("suite_unfrozen",
                    List.of(new TestCase(Map.of(), Map.of(), 1))));

            helper.assertTrue(results.size() == 1, "single result");
            helper.assertTrue(results.get(0).passed(), "suite passed");
            helper.assertTrue(!ws.isFrozen(), "workspace restored unfrozen");
            helper.assertTrue(TickController.getQueue(ws) == null, "queue removed");
            helper.assertTrue(ws.getTimeline() == null, "timeline removed");
            helper.assertTrue(ws.getVirtualTick() == 0, "virtual tick restored");
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_unfrozen");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_total_tick_budget_is_enforced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_budget", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            List<TestCase> cases = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                cases.add(new TestCase("case_" + i, Map.of(), Map.of(), 200));
            }

            try {
                TestRunner.runSuite(level, ws, new TestSuite("suite_budget", cases));
                helper.fail("Expected suite total tick budget to be rejected");
            } catch (IllegalArgumentException e) {
                helper.assertTrue(e.getMessage().contains("Suite total tick budget"), "budget message");
            }
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_budget");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void test_handler_rejects_controller_marker_label(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_test_handler", abs, bounds);
        ws.addIOMarker(new IOMarker(ws.getControllerPos(), IOMarker.IORole.INPUT, "reserved"));
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            JsonObject inputs = new JsonObject();
            inputs.addProperty("reserved", 15);
            params.add("inputs", inputs);
            params.add("expected", new JsonObject());
            params.addProperty("ticks", 1);

            try {
                new TestHandler().run(new JsonRpcRequest("1", "run", params), level.getServer());
                helper.fail("Expected controller-position marker label to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_test_handler");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void io_handler_rejects_controller_position_mark(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_io_handler", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("x", 0);
            params.addProperty("y", 0);
            params.addProperty("z", 0);
            params.addProperty("role", "input");
            params.addProperty("label", "reserved");

            try {
                new IOHandler().mark(new JsonRpcRequest("1", "mark", params), level.getServer());
                helper.fail("Expected controller block position to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_io_handler");
        }
        helper.succeed();
    }
}
