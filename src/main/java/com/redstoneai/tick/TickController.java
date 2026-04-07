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
        // Dev environment uses Mojang-mapped names; production uses SRG names.
        // Try Mojang name first (dev), fall back to SRG name (production).
        LEVEL_TICKS_ALL_CONTAINERS_FIELD = findFieldSafe(LevelTicks.class, "allContainers", "f_193800_");
        LEVEL_TICKS_TO_RUN_FIELD = findFieldSafe(LevelTicks.class, "toRunThisTick", "f_193802_");
        LEVEL_TICKS_ALREADY_RUN_FIELD = findFieldSafe(LevelTicks.class, "alreadyRunThisTick", "f_193803_");
        SERVER_LEVEL_BLOCK_EVENTS_FIELD = findFieldSafe(ServerLevel.class, "blockEvents", "f_8549_");
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
        ws.setFrozen(false);
        frozenQueues.remove(ws.getId());
        WorkspaceChunkLoader.unloadWorkspace(level, ws);
        WorkspaceManager.get(level).setDirty();
        WorkspaceBoundarySyncPacket.sync(level);
    }

    public static int step(ServerLevel level, Workspace ws, int count) {
        if (!ws.isFrozen()) {
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
        WorkspaceManager.get(level).setDirty();
        return toReplay.size();
    }

    public static int replayThenStep(ServerLevel level, Workspace ws, int count) {
        RecordingTimeline timeline = ws.getTimeline();
        int replayed = 0;
        if (timeline != null) {
            int availableFuture = Math.max(0, timeline.getLength() - (timeline.getCurrentIndex() + 1));
            int toReplay = Math.min(count, availableFuture);
            if (toReplay > 0) {
                replayed = fastForward(level, ws, toReplay);
            }
        }
        int remaining = count - replayed;
        if (remaining > 0) {
            return replayed + step(level, ws, remaining);
        }
        return replayed;
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
    }

    /**
     * External mutations while frozen/non-stepping invalidate the recording baseline.
     */
    public static void invalidateRecording(ServerLevel level, Workspace ws) {
        if (ws.isFrozen()) {
            FrozenTickQueue queue = frozenQueues.computeIfAbsent(ws.getId(), ignored -> new FrozenTickQueue());
            migratePendingScheduledTicks(level, ws, queue);
            migratePendingBlockEvents(level, ws, queue);
            // Skip the expensive full-workspace snapshot if the timeline is
            // already fresh (no deltas recorded yet) — avoids double-capture
            // when freeze() was just called before an input change.
            RecordingTimeline timeline = ws.getTimeline();
            if (timeline == null || timeline.getLength() > 0) {
                resetRecording(level, ws);
            }
        } else {
            ws.setTimeline(null);
            ws.setVirtualTick(0);
        }
        WorkspaceManager.get(level).setDirty();
    }

    private static void stepOnce(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        int nextTick = ws.getVirtualTick() + 1;
        FrozenTickQueue.QueueState queueBefore = queue.snapshot();
        StateRecorder.beginStep(level, ws);
        TickInterceptor.beginStep(ws);

        try {
            for (FrozenTickQueue.QueuedBlockTick tick : queue.pollDueBlockTicks()) {
                BlockState state = level.getBlockState(tick.pos());
                if (state.is(tick.block())) {
                    state.tick(level, tick.pos(), level.random);
                }
            }

            for (FrozenTickQueue.QueuedFluidTick tick : queue.pollDueFluidTicks()) {
                FluidState fluidState = level.getFluidState(tick.pos());
                if (fluidState.is(tick.fluid())) {
                    fluidState.tick(level, tick.pos());
                }
            }
            tickEntitiesInWorkspace(level, ws);
            tickBlockEntitiesInWorkspace(level, ws);

            for (FrozenTickQueue.QueuedBlockEvent queuedEvent : queue.pollDueBlockEvents()) {
                var event = queuedEvent.event();
                BlockState state = level.getBlockState(event.pos());
                if (state.is(event.block())) {
                    state.triggerEvent(level, event.pos(), event.paramA(), event.paramB());
                }
            }

            for (FrozenTickQueue.QueuedNeighborUpdate update : queue.drainNeighborUpdates()) {
                update.state().neighborChanged(level, update.pos(), update.sourceBlock(), update.sourcePos(), update.movedByPiston());
            }
        } finally {
            TickInterceptor.endStep();
            StateRecorder.endStep(level, ws, nextTick, queueBefore, queue.snapshot());
            ws.setVirtualTick(nextTick);
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

    private static void migratePendingScheduledTicks(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        BoundingBox bounds = ws.getBounds();
        ServerLevelTickAccessAccessor accessor = (ServerLevelTickAccessAccessor) level;
        migrateLevelTicks(level, accessor.redstoneai$getBlockTicks(), bounds, queue, true);
        migrateLevelTicks(level, accessor.redstoneai$getFluidTicks(), bounds, queue, false);
    }

    @SuppressWarnings("unchecked")
    private static void migratePendingBlockEvents(ServerLevel level, Workspace ws, FrozenTickQueue queue) {
        try {
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
            ((List<ScheduledTick<T>>) LEVEL_TICKS_ALREADY_RUN_FIELD.get(levelTicks)).stream()
                    .filter(tick -> bounds.isInside(tick.pos()))
                    .forEach(ticks::add);
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
}
