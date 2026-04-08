package com.redstoneai.recording;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceSignalController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Captures block, block-entity, and entity state across a single virtual step.
 * All methods run on the server thread only.
 */
public final class StateRecorder {
    private static final Map<UUID, Map<BlockPos, PendingBlockChange>> pendingChanges = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, CompoundTag>> preStepBlockEntities = new HashMap<>();
    private static final Map<UUID, List<TickSnapshot.EntitySnapshot>> preStepEntityStates = new HashMap<>();
    private static Workspace currentStepWorkspace;

    private StateRecorder() {}

    public static void beginStep(ServerLevel level, Workspace ws) {
        currentStepWorkspace = ws;
        pendingChanges.put(ws.getId(), new LinkedHashMap<>());
        preStepBlockEntities.put(ws.getId(), captureBlockEntities(level, ws));
        preStepEntityStates.put(ws.getId(), captureEntityStates(level, ws));
    }

    public static void abortStep(Workspace ws) {
        pendingChanges.remove(ws.getId());
        preStepBlockEntities.remove(ws.getId());
        preStepEntityStates.remove(ws.getId());
        if (currentStepWorkspace != null && currentStepWorkspace.getId().equals(ws.getId())) {
            currentStepWorkspace = null;
        }
    }

    /**
     * Called by LevelSetBlockMixin before a block change is applied.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (currentStepWorkspace == null || !currentStepWorkspace.contains(pos)) {
            return;
        }

        Map<BlockPos, PendingBlockChange> changes = pendingChanges.get(currentStepWorkspace.getId());
        if (changes == null) {
            return;
        }

        BlockPos immutable = pos.immutable();
        PendingBlockChange existing = changes.get(immutable);
        CompoundTag oldTileEntity = null;
        if (existing == null) {
            Map<BlockPos, CompoundTag> beforeBlockEntities = preStepBlockEntities.get(currentStepWorkspace.getId());
            if (beforeBlockEntities != null) {
                oldTileEntity = beforeBlockEntities.get(immutable);
            }
        }

        BlockState initialOldState = existing != null ? existing.oldState : oldState;
        CompoundTag initialOldTileEntity = existing != null ? existing.oldTileEntity : copyTag(oldTileEntity);
        changes.put(immutable, new PendingBlockChange(initialOldState, initialOldTileEntity));
    }

    public static void endStep(ServerLevel level, Workspace ws, int tickIndex,
                               FrozenTickQueue.QueueState queueBefore,
                               FrozenTickQueue.QueueState queueAfter) {
        Map<BlockPos, PendingBlockChange> changes = pendingChanges.remove(ws.getId());
        Map<BlockPos, CompoundTag> beforeBlockEntities = preStepBlockEntities.remove(ws.getId());
        List<TickSnapshot.EntitySnapshot> beforeEntities = preStepEntityStates.remove(ws.getId());

        if (changes == null) {
            changes = new LinkedHashMap<>();
        }
        if (beforeBlockEntities == null) {
            beforeBlockEntities = Map.of();
        }
        if (beforeEntities == null) {
            beforeEntities = List.of();
        }

        Map<BlockPos, CompoundTag> afterBlockEntities = captureBlockEntities(level, ws);
        addPureBlockEntityChanges(level, ws, changes, beforeBlockEntities, afterBlockEntities);

        Map<BlockPos, TickSnapshot.BlockStateChange> finalizedChanges = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, PendingBlockChange> entry : changes.entrySet()) {
            BlockPos pos = entry.getKey();
            PendingBlockChange change = entry.getValue();
            CompoundTag newTileEntity = copyTag(afterBlockEntities.get(pos));
            finalizedChanges.put(pos, new TickSnapshot.BlockStateChange(
                    change.oldState,
                    level.getBlockState(pos),
                    copyTag(change.oldTileEntity),
                    newTileEntity
            ));
        }

        Map<BlockPos, Integer> ioPowerLevels = new LinkedHashMap<>();
        for (IOMarker marker : ws.getIOMarkers()) {
            ioPowerLevels.put(marker.pos(), WorkspaceSignalController.readMarkerSignalLevel(level, marker));
        }

        List<TickSnapshot.EntitySnapshot> afterEntities = captureEntityStates(level, ws);
        TickSnapshot snapshot = new TickSnapshot(
                tickIndex,
                finalizedChanges.isEmpty() ? Map.of() : Collections.unmodifiableMap(finalizedChanges),
                ioPowerLevels.isEmpty() ? Map.of() : Collections.unmodifiableMap(ioPowerLevels),
                List.copyOf(beforeEntities),
                List.copyOf(afterEntities),
                queueBefore,
                queueAfter
        );

        RecordingTimeline timeline = ws.getTimeline();
        if (timeline != null) {
            timeline.addDelta(snapshot);
        }

        currentStepWorkspace = null;
    }

    public static boolean isRecording() {
        return currentStepWorkspace != null;
    }

    private static void addPureBlockEntityChanges(ServerLevel level, Workspace ws,
                                                  Map<BlockPos, PendingBlockChange> changes,
                                                  Map<BlockPos, CompoundTag> beforeBlockEntities,
                                                  Map<BlockPos, CompoundTag> afterBlockEntities) {
        Set<BlockPos> allPositions = new HashSet<>();
        allPositions.addAll(beforeBlockEntities.keySet());
        allPositions.addAll(afterBlockEntities.keySet());

        for (BlockPos pos : allPositions) {
            if (!ws.contains(pos) || changes.containsKey(pos)) {
                continue;
            }

            CompoundTag before = beforeBlockEntities.get(pos);
            CompoundTag after = afterBlockEntities.get(pos);
            if (!Objects.equals(before, after)) {
                BlockState state = level.getBlockState(pos);
                changes.put(pos, new PendingBlockChange(state, copyTag(before)));
            }
        }
    }

    private static Map<BlockPos, CompoundTag> captureBlockEntities(ServerLevel level, Workspace ws) {
        Map<BlockPos, CompoundTag> result = new LinkedHashMap<>();
        BlockPos min = new BlockPos(ws.getBounds().minX(), ws.getBounds().minY(), ws.getBounds().minZ());
        BlockPos max = new BlockPos(ws.getBounds().maxX(), ws.getBounds().maxY(), ws.getBounds().maxZ());
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                result.put(pos.immutable(), blockEntity.saveWithoutMetadata());
            }
        }
        return result;
    }

    private static List<TickSnapshot.EntitySnapshot> captureEntityStates(ServerLevel level, Workspace ws) {
        AABB aabb = ws.getAABB();
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, aabb,
                entity -> !(entity instanceof Player)
                        && ws.containsEntityFully(entity)
                        && ws.getEntityFilterMode().shouldAffect(entity));

        List<TickSnapshot.EntitySnapshot> snapshots = new ArrayList<>();
        for (Entity entity : entities) {
            CompoundTag nbt = new CompoundTag();
            entity.saveWithoutId(nbt);
            nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            snapshots.add(new TickSnapshot.EntitySnapshot(
                    entity.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    nbt
            ));
        }
        return snapshots;
    }

    private static CompoundTag copyTag(CompoundTag tag) {
        return tag == null ? null : tag.copy();
    }

    private static final class PendingBlockChange {
        private final BlockState oldState;
        private final CompoundTag oldTileEntity;

        private PendingBlockChange(BlockState oldState, CompoundTag oldTileEntity) {
            this.oldState = oldState;
            this.oldTileEntity = oldTileEntity;
        }
    }
}
