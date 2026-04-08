package com.redstoneai.tick;

import com.redstoneai.RedstoneAI;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.mixin.LevelAccessor;
import com.redstoneai.mixin.ServerLevelTickAccessAccessor;
import com.redstoneai.recording.RecordingTimeline;
import com.redstoneai.recording.StateRecorder;
import com.redstoneai.recording.TickSnapshot;
import com.redstoneai.network.WorkspaceBoundarySyncPacket;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceBypassContext;
import com.redstoneai.workspace.WorkspaceChunkLoader;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceMutationSource;
import com.redstoneai.workspace.WorkspaceTemporalState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Orchestrates workspace tick control: freeze, unfreeze, step, rewind, and replay.
 * All methods must be called on the server thread.
 */
public final class TickController {
    private static final Map<UUID, FrozenTickQueue> frozenQueues = new HashMap<>();
    private static final Field LEVEL_TICKS_ALL_CONTAINERS_FIELD;
    private static final Field LEVEL_TICKS_TO_RUN_FIELD;
    private static final Field LEVEL_TICKS_ALREADY_RUN_FIELD;
    private static final Field SERVER_LEVEL_BLOCK_EVENTS_FIELD;

    static {
        LEVEL_TICKS_ALL_CONTAINERS_FIELD = findFieldSafe(LevelTicks.class, "allContainers", "f_193202_");
        LEVEL_TICKS_TO_RUN_FIELD = findFieldSafe(LevelTicks.class, "toRunThisTick", "f_193205_");
        LEVEL_TICKS_ALREADY_RUN_FIELD = findFieldSafe(LevelTicks.class, "alreadyRunThisTick", "f_193206_");
        SERVER_LEVEL_BLOCK_EVENTS_FIELD = findFieldSafe(ServerLevel.class, "blockEvents", "f_8556_");
    }

    private static Field findFieldSafe(Class<?> clazz, String mojangName, String srgName) {
        try {
            Field f = clazz.getDeclaredField(mojangName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {}
        try {
            Field f = clazz.getDeclaredField(srgName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Cannot find field " + mojangName + "/" + srgName + " in " + clazz.getName(), e);
        }
    }

    private TickController() {}

    public static void freeze(ServerLevel level, Workspace ws) {
        boolean wasFrozen = ws.isFrozen();
        if (!wasFrozen) {
            ws.setFrozen(true);
        }
        FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
        migratePendingScheduledTicks(level, ws, queue);
        migratePendingBlockEvents(level, ws, queue);

        if (!wasFrozen || ws.getTimeline() == null) {
            resetRecording(level, ws);
        }
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_AT_HEAD);
        ws.setLastMutationSource(WorkspaceMutationSource.FREEZE);

        WorkspaceChunkLoader.forceLoadWorkspace(level, ws);
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
        RedstoneAI.LOGGER.info("[RedstoneAI] Workspace '{}' frozen", ws.getName());
    }

    /**
     * Rebuild runtime state for a workspace that was loaded from disk while frozen.
     * Recording history is reset because queue/timeline deltas are not persisted.
     */
    public static void initializeLoadedFrozenWorkspace(ServerLevel level, Workspace ws) {
        if (!ws.isFrozen()) {
            return;
        }

        FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
        migratePendingScheduledTicks(level, ws, queue);
        migratePendingBlockEvents(level, ws, queue);
        resetRecording(level, ws);
        ws.setVirtualTick(0);
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_AT_HEAD);
        ws.setLastMutationSource(WorkspaceMutationSource.FREEZE);
        WorkspaceChunkLoader.forceLoadWorkspace(level, ws);
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
        RedstoneAI.LOGGER.warn("[RedstoneAI] Workspace '{}' reloaded frozen; recording history was reset", ws.getName());
    }

    public static void unfreeze(ServerLevel level, Workspace ws) {
        if (!ws.isFrozen()) {
            return;
        }

        ws.setFrozen(false);
        ws.setPersistedFrozenQueueState(null);
        ws.setTemporalState(WorkspaceTemporalState.LIVE);
        FrozenTickQueue queue = frozenQueues.remove(ws.getId());
        if (queue != null && !queue.isEmpty()) {
            replayQueueIntoWorld(level, queue);
        }

        WorkspaceChunkLoader.unloadWorkspace(level, ws);
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
        RedstoneAI.LOGGER.info("[RedstoneAI] Workspace '{}' unfrozen", ws.getName());
    }

    public static void discardFrozenState(ServerLevel level, Workspace ws) {
        if (!ws.isFrozen()) {
            return;
        }
        discardFrozenState(level, ws, ws.getBounds(), ws.getOriginPos());
    }

    public static void discardFrozenState(ServerLevel level, Workspace ws, BoundingBox unloadBounds, BlockPos unloadOrigin) {
        if (!ws.isFrozen()) {
            return;
        }
        ws.setFrozen(false);
        ws.setPersistedFrozenQueueState(null);
        ws.setTemporalState(WorkspaceTemporalState.LIVE);
        frozenQueues.remove(ws.getId());
        WorkspaceChunkLoader.unloadWorkspace(level, unloadBounds, unloadOrigin, ws.getName());
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
    }

    public static void restoreFrozenRuntimeState(ServerLevel level,
                                                 Workspace ws,
                                                 @Nullable RecordingTimeline timeline,
                                                 int virtualTick,
                                                 FrozenTickQueue.QueueState queueState) {
        ws.setFrozen(true);
        ws.setTimeline(timeline);
        ws.setPersistedFrozenQueueState(queueState);
        ws.setVirtualTick(virtualTick);
        ws.setTemporalState(timeline != null && timeline.getCurrentIndex() < timeline.getLength() - 1
                ? WorkspaceTemporalState.FROZEN_REWOUND
                : WorkspaceTemporalState.FROZEN_AT_HEAD);
        FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
        queue.restore(queueState);
        WorkspaceChunkLoader.forceLoadWorkspace(level, ws);
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
    }

    public static int step(ServerLevel level, Workspace ws, int count) {
        if (!ws.isFrozen()) {
            return 0;
        }
        if (!ws.getTemporalState().canStep()) {
            return 0;
        }

        FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
        int maxSteps = Math.min(count, RAIConfig.SERVER.maxStepsPerCall.get());
        int stepped = 0;

        for (int i = 0; i < maxSteps; i++) {
            stepOnce(level, ws, queue);
            stepped++;
        }

        WorkspaceManager.get(level).setDirty();
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_AT_HEAD);
        ws.setLastMutationSource(WorkspaceMutationSource.STEP);
        return stepped;
    }

    public static int rewind(ServerLevel level, Workspace ws, int count) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) {
            return 0;
        }

        List<TickSnapshot> toUndo = timeline.rewind(count);
        for (TickSnapshot snapshot : toUndo) {
            applySnapshotBackward(level, ws, snapshot);
        }
        ws.setVirtualTick(Math.max(0, ws.getVirtualTick() - toUndo.size()));
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_REWOUND);
        ws.setLastMutationSource(WorkspaceMutationSource.REWIND);
        WorkspaceManager.get(level).setDirty();
        return toUndo.size();
    }

    public static int fastForward(ServerLevel level, Workspace ws, int count) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) {
            return 0;
        }

        List<TickSnapshot> toReplay = timeline.fastForward(count);
        for (TickSnapshot snapshot : toReplay) {
            applySnapshotForward(level, ws, snapshot);
        }
        ws.setVirtualTick(ws.getVirtualTick() + toReplay.size());
        ws.setTemporalState(timeline.getCurrentIndex() < timeline.getLength() - 1
                ? WorkspaceTemporalState.FROZEN_REWOUND
                : WorkspaceTemporalState.FROZEN_AT_HEAD);
        ws.setLastMutationSource(WorkspaceMutationSource.FAST_FORWARD);
        WorkspaceManager.get(level).setDirty();
        return toReplay.size();
    }

    /**
     * Discard future deltas beyond the current rewind position.
     * Transitions FROZEN_REWOUND → FROZEN_DIRTY so step() becomes available.
     */
    public static void discardFuture(ServerLevel level, Workspace ws) {
        if (ws.getTemporalState() != WorkspaceTemporalState.FROZEN_REWOUND) {
            throw new IllegalStateException("discardFuture requires FROZEN_REWOUND state, got " + ws.getTemporalState());
        }
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline != null && timeline.getCurrentIndex() < timeline.getLength() - 1) {
            // Truncate future by adding a no-op — addDelta's truncation logic handles this
            // Actually just directly truncate:
            timeline.getDeltas().subList(timeline.getCurrentIndex() + 1, timeline.getLength()).clear();
        }
        resetRecording(level, ws);
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_DIRTY);
        ws.setLastMutationSource(WorkspaceMutationSource.SYSTEM);
        WorkspaceManager.get(level).setDirty();
    }

    public static void restoreBaseState(ServerLevel level, Workspace ws) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) {
            return;
        }

        BoundingBox bounds = ws.getBounds();
        Map<BlockPos, BlockState> blocks = timeline.getBaseSnapshot();
        Map<BlockPos, CompoundTag> tileEntities = timeline.getBaseTileEntities();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState target = blocks.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    level.setBlock(pos, target, 2 | 16);
                    restoreBlockEntity(level, pos, target, tileEntities.get(pos));
                }
            }
        }

        restoreEntityStates(level, ws, timeline.getBaseEntityStates().values());
        FrozenTickQueue queue = frozenQueues.get(ws.getId());
        if (queue == null && ws.isFrozen()) {
            queue = new FrozenTickQueue();
            frozenQueues.put(ws.getId(), queue);
        }
        if (queue != null) {
            queue.restore(timeline.getBaseQueueState());
        }
        ws.setVirtualTick(0);
        ws.setTemporalState(WorkspaceTemporalState.FROZEN_AT_HEAD);
    }

    /**
     * External mutations while frozen/non-stepping invalidate the recording baseline.
     */
    public static void invalidateRecording(ServerLevel level, Workspace ws) {
        invalidateRecording(level, ws, WorkspaceMutationSource.SYSTEM);
    }

    public static void invalidateRecording(ServerLevel level, Workspace ws, WorkspaceMutationSource mutationSource) {
        if (ws.isFrozen()) {
            FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
            migratePendingScheduledTicks(level, ws, queue);
            migratePendingBlockEvents(level, ws, queue);
            // Skip the expensive full-workspace snapshot if the timeline is
            // already fresh (no deltas recorded yet) — avoids double-capture
            // when freeze() was just called before an input change.
            resetRecording(level, ws);
            ws.setTemporalState(WorkspaceTemporalState.FROZEN_DIRTY);
        } else {
            ws.setTimeline(null);
            ws.setVirtualTick(0);
            ws.setTemporalState(WorkspaceTemporalState.LIVE);
        }
        ws.setLastMutationSource(mutationSource);
        WorkspaceManager.get(level).setDirty();
    }

    private static void stepOnce(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        int nextTick = ws.getVirtualTick() + 1;
        FrozenTickQueue.QueueState queueBefore = queue.snapshot();
        StepRollbackState rollbackState = captureRollbackState(level, ws, queueBefore, ws.getVirtualTick());
        StateRecorder.beginStep(level, ws);
        TickInterceptor.beginStep(ws);
        boolean contextClosed = false;

        try {
            // Order matches vanilla ServerLevel.tick():
            // 1. Block events (pistons, noteblocks) — vanilla runBlockEvents()
            // 1. Scheduled block ticks
            for (FrozenTickQueue.QueuedBlockTick tick : queue.pollDueBlockTicks()) {
                BlockState state = level.getBlockState(tick.pos());
                if (state.is(tick.block())) {
                    state.tick(level, tick.pos(), level.random);
                }
            }

            // 2. Scheduled fluid ticks
            for (FrozenTickQueue.QueuedFluidTick tick : queue.pollDueFluidTicks()) {
                FluidState fluidState = level.getFluidState(tick.pos());
                if (fluidState.is(tick.fluid())) {
                    fluidState.tick(level, tick.pos());
                }
            }

            // 3. Block events
            for (FrozenTickQueue.QueuedBlockEvent queuedEvent : queue.pollDueBlockEvents()) {
                var event = queuedEvent.event();
                BlockState state = level.getBlockState(event.pos());
                if (state.is(event.block())) {
                    state.triggerEvent(level, event.pos(), event.paramA(), event.paramB());
                }
            }

            // 4. Entities
            tickEntitiesInWorkspace(level, ws);
            // 5. Block entities
            tickBlockEntitiesInWorkspace(level, ws);

            for (FrozenTickQueue.QueuedNeighborUpdate update : queue.drainNeighborUpdates()) {
                update.state().neighborChanged(level, update.pos(), update.sourceBlock(), update.sourcePos(), update.movedByPiston());
            }
            TickInterceptor.endStep();
            contextClosed = true;
            StateRecorder.endStep(level, ws, nextTick, queueBefore, queue.snapshot());
            ws.setVirtualTick(nextTick);
        } catch (RuntimeException | Error e) {
            if (!contextClosed) {
                TickInterceptor.endStep();
                contextClosed = true;
            }
            StateRecorder.abortStep(ws);
            restoreRollbackState(level, ws, queue, rollbackState);
            ws.setTemporalState(WorkspaceTemporalState.FROZEN_CORRUPTED);
            ws.setLastMutationSource(WorkspaceMutationSource.SYSTEM);
            WorkspaceManager.get(level).setDirty();
            throw new IllegalStateException("Virtual step failed for workspace '" + ws.getName() + "'", e);
        } finally {
            if (!contextClosed) {
                TickInterceptor.endStep();
            }
        }
    }

    private static void tickBlockEntitiesInWorkspace(ServerLevel level, Workspace ws) {
        List<TickingBlockEntity> tickers = ((LevelAccessor) level).redstoneai$getBlockEntityTickers();
        for (TickingBlockEntity ticker : tickers) {
            if (!ticker.isRemoved() && ws.contains(ticker.getPos())) {
                ticker.tick();
            }
        }
    }

    private static void tickEntitiesInWorkspace(ServerLevel level, Workspace ws) {
        AABB aabb = ws.getAABB();
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, aabb,
                entity -> !(entity instanceof Player)
                        && !entity.isPassenger()
                        && ws.containsEntityFully(entity)
                        && ws.getEntityFilterMode().shouldAffect(entity));
        for (Entity entity : entities) {
            level.tickNonPassenger(entity);
        }
    }

    private static void captureBaseSnapshot(ServerLevel level, Workspace ws) {
        BoundingBox bounds = ws.getBounds();
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> tileEntities = new HashMap<>();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(pos, state);
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        tileEntities.put(pos, blockEntity.saveWithoutMetadata());
                    }
                }
            }
        }

        List<TickSnapshot.EntitySnapshot> baseEntities = captureWorkspaceEntities(level, ws);
        FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
        ws.getTimeline().setBaseSnapshot(blocks, tileEntities, baseEntities, queue.snapshot());
    }

    private static StepRollbackState captureRollbackState(ServerLevel level,
                                                          Workspace ws,
                                                          FrozenTickQueue.QueueState queueBefore,
                                                          int virtualTickBefore) {
        BoundingBox bounds = ws.getBounds();
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> tileEntities = new HashMap<>();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(pos.immutable(), state);
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        tileEntities.put(pos.immutable(), blockEntity.saveWithoutMetadata());
                    }
                }
            }
        }

        return new StepRollbackState(
                bounds,
                blocks,
                tileEntities,
                captureWorkspaceEntities(level, ws),
                queueBefore,
                virtualTickBefore
        );
    }

    private static void restoreRollbackState(ServerLevel level,
                                             Workspace ws,
                                             FrozenTickQueue queue,
                                             StepRollbackState rollbackState) {
        BoundingBox bounds = rollbackState.bounds();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState targetState = rollbackState.blocks().getOrDefault(
                            pos,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    );
                    level.setBlock(pos, targetState, 2 | 16);
                    restoreBlockEntity(level, pos, targetState, rollbackState.tileEntities().get(pos));
                }
            }
        }
        restoreEntityStates(level, ws, rollbackState.entityStates());
        queue.restore(rollbackState.queueBefore());
        ws.setVirtualTick(rollbackState.virtualTickBefore());
    }

    private static List<TickSnapshot.EntitySnapshot> captureWorkspaceEntities(ServerLevel level, Workspace ws) {
        List<TickSnapshot.EntitySnapshot> snapshots = new ArrayList<>();
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, ws.getAABB(),
                entity -> !(entity instanceof Player)
                        && ws.containsEntityFully(entity)
                        && ws.getEntityFilterMode().shouldAffect(entity));

        for (Entity entity : entities) {
            CompoundTag nbt = new CompoundTag();
            entity.saveWithoutId(nbt);
            nbt.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            snapshots.add(new TickSnapshot.EntitySnapshot(
                    entity.getUUID(),
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    nbt
            ));
        }
        return snapshots;
    }

    private static void replayQueueIntoWorld(ServerLevel level, FrozenTickQueue queue) {
        for (FrozenTickQueue.DelayedTickView<net.minecraft.world.level.block.Block> tick : queue.snapshotBlockTicks()) {
            level.scheduleTick(tick.pos(), tick.type(), Math.max(0, tick.remainingDelay()), tick.priority());
        }
        for (FrozenTickQueue.DelayedTickView<net.minecraft.world.level.material.Fluid> tick : queue.snapshotFluidTicks()) {
            level.scheduleTick(tick.pos(), tick.type(), Math.max(0, tick.remainingDelay()), tick.priority());
        }
        for (FrozenTickQueue.DelayedBlockEventView event : queue.snapshotBlockEvents()) {
            level.blockEvent(event.event().pos(), event.event().block(), event.event().paramA(), event.event().paramB());
        }
        for (FrozenTickQueue.QueuedNeighborUpdate update : queue.drainNeighborUpdates()) {
            update.state().neighborChanged(level, update.pos(), update.sourceBlock(), update.sourcePos(), update.movedByPiston());
        }
    }

    private static void applySnapshotBackward(ServerLevel level, Workspace ws, TickSnapshot snapshot) {
        applyBlockChanges(level, snapshot.blockChanges(), false);
        restoreEntityStates(level, ws, snapshot.entityStatesBefore());
        FrozenTickQueue queue = frozenQueues.get(ws.getId());
        if (queue != null) {
            queue.restore(snapshot.queueBefore());
        }
    }

    private static void applySnapshotForward(ServerLevel level, Workspace ws, TickSnapshot snapshot) {
        applyBlockChanges(level, snapshot.blockChanges(), true);
        restoreEntityStates(level, ws, snapshot.entityStatesAfter());
        FrozenTickQueue queue = frozenQueues.get(ws.getId());
        if (queue != null) {
            queue.restore(snapshot.queueAfter());
        }
    }

    private static void applyBlockChanges(ServerLevel level,
                                          Map<BlockPos, TickSnapshot.BlockStateChange> blockChanges,
                                          boolean forward) {
        for (Map.Entry<BlockPos, TickSnapshot.BlockStateChange> entry : blockChanges.entrySet()) {
            BlockPos pos = entry.getKey();
            TickSnapshot.BlockStateChange change = entry.getValue();
            BlockState targetState = forward ? change.newState() : change.oldState();
            CompoundTag targetTileEntity = forward ? change.newTileEntity() : change.oldTileEntity();
            level.setBlock(pos, targetState, 2 | 16);
            restoreBlockEntity(level, pos, targetState, targetTileEntity);
        }
    }

    private static void restoreBlockEntity(ServerLevel level, BlockPos pos, BlockState targetState, @Nullable CompoundTag targetTileEntity) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (targetTileEntity == null) {
            if (blockEntity != null && !targetState.hasBlockEntity()) {
                level.removeBlockEntity(pos);
            }
            return;
        }

        if (blockEntity != null) {
            blockEntity.load(targetTileEntity.copy());
            blockEntity.setChanged();
        }
    }

    public static void restoreEntityStates(ServerLevel level, Workspace ws,
                                           Collection<TickSnapshot.EntitySnapshot> entityStates) {
        List<Entity> currentEntities = level.getEntitiesOfClass(Entity.class, ws.getAABB(),
                entity -> !(entity instanceof Player) && ws.getEntityFilterMode().shouldAffect(entity));
        Set<UUID> targetEntityIds = new HashSet<>();
        for (TickSnapshot.EntitySnapshot snapshot : entityStates) {
            targetEntityIds.add(snapshot.entityUUID());
        }
        for (Entity entity : currentEntities) {
            if (targetEntityIds.contains(entity.getUUID()) || ws.containsEntityFully(entity)) {
                WorkspaceBypassContext.runWithEntityRemovalBypass(entity::discard);
            }
        }

        Set<UUID> passengerIds = new HashSet<>();
        for (TickSnapshot.EntitySnapshot snapshot : entityStates) {
            collectPassengerIds(snapshot.fullNbt(), passengerIds);
        }

        for (TickSnapshot.EntitySnapshot snapshot : entityStates) {
            if (!passengerIds.contains(snapshot.entityUUID())) {
                spawnEntityFromSnapshot(level, snapshot);
            }
        }
    }

    private static void spawnEntityFromSnapshot(ServerLevel level, TickSnapshot.EntitySnapshot snapshot) {
        CompoundTag nbt = snapshot.fullNbt().copy();
        if (!nbt.contains("id")) {
            nbt.putString("id", snapshot.entityTypeId());
        }
        EntityType.loadEntityRecursive(nbt, level, entity -> {
            level.addFreshEntity(entity);
            return entity;
        });
    }

    private static void resetRecording(ServerLevel level, Workspace ws) {
        RecordingTimeline timeline = new RecordingTimeline(RAIConfig.SERVER.maxRecordingTicks.get());
        ws.setTimeline(timeline);
        ws.setVirtualTick(0);
        captureBaseSnapshot(level, ws);
    }

    public static void clearAllQueues() {
        frozenQueues.clear();
    }

    @Nullable
    public static FrozenTickQueue getQueue(Workspace ws) {
        return frozenQueues.get(ws.getId());
    }

    @Nullable
    public static FrozenTickQueue getQueue(UUID workspaceId) {
        return frozenQueues.get(workspaceId);
    }

    public static void removeQueue(UUID workspaceId) {
        frozenQueues.remove(workspaceId);
    }

    @Nullable
    public static FrozenTickQueue.QueueState snapshotQueueState(Workspace ws) {
        FrozenTickQueue queue = getQueue(ws);
        return queue != null ? queue.snapshot() : ws.getPersistedFrozenQueueState();
    }

    private static void migratePendingScheduledTicks(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        BoundingBox bounds = ws.getBounds();
        ServerLevelTickAccessAccessor accessor = (ServerLevelTickAccessAccessor) level;
        migrateLevelTicks(level, accessor.redstoneai$getBlockTicks(), bounds, queue, true);
        migrateLevelTicks(level, accessor.redstoneai$getFluidTicks(), bounds, queue, false);
    }

    private static void migratePendingBlockEvents(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        try {
            @SuppressWarnings("unchecked")
            Collection<BlockEventData> blockEvents = (Collection<BlockEventData>) SERVER_LEVEL_BLOCK_EVENTS_FIELD.get(level);
            Iterator<BlockEventData> iterator = blockEvents.iterator();
            while (iterator.hasNext()) {
                BlockEventData event = iterator.next();
                if (ws.contains(event.pos())) {
                    queue.addBlockEvent(event, 0);
                    iterator.remove();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect pending block events", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void migrateLevelTicks(ServerLevel level, LevelTicks<T> levelTicks, BoundingBox bounds,
                                              FrozenTickQueue queue, boolean blockTicks) {
        List<ScheduledTick<T>> scheduledTicks = collectScheduledTicks(levelTicks, bounds);
        levelTicks.clearArea(bounds);

        for (ScheduledTick<T> scheduledTick : scheduledTicks) {
            int delay = (int) Math.max(0L, scheduledTick.triggerTick() - level.getGameTime());
            if (blockTicks) {
                queue.addBlockTick(scheduledTick.pos(), (net.minecraft.world.level.block.Block) scheduledTick.type(),
                        delay, scheduledTick.priority(), scheduledTick.subTickOrder());
            } else {
                queue.addFluidTick(scheduledTick.pos(), (net.minecraft.world.level.material.Fluid) scheduledTick.type(),
                        delay, scheduledTick.priority(), scheduledTick.subTickOrder());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<ScheduledTick<T>> collectScheduledTicks(LevelTicks<T> levelTicks, BoundingBox bounds) {
        try {
            List<ScheduledTick<T>> ticks = new ArrayList<>();
            ((Queue<ScheduledTick<T>>) LEVEL_TICKS_TO_RUN_FIELD.get(levelTicks)).stream()
                    .filter(tick -> bounds.isInside(tick.pos()))
                    .forEach(ticks::add);
            Map<?, ?> containers = (Map<?, ?>) LEVEL_TICKS_ALL_CONTAINERS_FIELD.get(levelTicks);
            for (Object value : containers.values()) {
                ((net.minecraft.world.ticks.LevelChunkTicks<T>) value).getAll()
                        .filter(tick -> bounds.isInside(tick.pos()))
                        .forEach(ticks::add);
            }
            ticks.sort(ScheduledTick.DRAIN_ORDER);
            return ticks;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect scheduled ticks", e);
        }
    }

    private static void collectPassengerIds(CompoundTag entityTag, Set<UUID> passengerIds) {
        if (!entityTag.contains("Passengers", net.minecraft.nbt.Tag.TAG_LIST)) {
            return;
        }
        var passengers = entityTag.getList("Passengers", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            CompoundTag passenger = passengers.getCompound(i);
            if (passenger.hasUUID("UUID")) {
                passengerIds.add(passenger.getUUID("UUID"));
            }
            collectPassengerIds(passenger, passengerIds);
        }
    }

    private record StepRollbackState(
            BoundingBox bounds,
            Map<BlockPos, BlockState> blocks,
            Map<BlockPos, CompoundTag> tileEntities,
            List<TickSnapshot.EntitySnapshot> entityStates,
            FrozenTickQueue.QueueState queueBefore,
            int virtualTickBefore
    ) {
    }
}
