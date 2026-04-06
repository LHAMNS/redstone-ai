package com.redstoneai.recording;

import com.redstoneai.tick.FrozenTickQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores the complete history of a workspace's block state changes.
 * Consists of a base snapshot (full state at recording start) and a list of per-tick deltas.
 * Supports rewind by replaying deltas backward and fast-forward by replaying forward.
 */
public class RecordingTimeline {
    private final Map<BlockPos, BlockState> baseSnapshot;
    private final Map<BlockPos, CompoundTag> baseTileEntities;
    private final Map<UUID, TickSnapshot.EntitySnapshot> baseEntityStates;
    private FrozenTickQueue.QueueState baseQueueState;
    private final List<TickSnapshot> deltas;
    private int currentIndex;
    private final int maxTicks;

    public RecordingTimeline(int maxTicks) {
        this.baseSnapshot = new HashMap<>();
        this.baseTileEntities = new HashMap<>();
        this.baseEntityStates = new HashMap<>();
        this.baseQueueState = new FrozenTickQueue.QueueState(List.of(), List.of(), List.of(), List.of());
        this.deltas = new ArrayList<>();
        this.currentIndex = -1;
        this.maxTicks = maxTicks;
    }

    public void setBaseSnapshot(Map<BlockPos, BlockState> blocks,
                                Map<BlockPos, CompoundTag> tileEntities,
                                List<TickSnapshot.EntitySnapshot> entityStates,
                                FrozenTickQueue.QueueState queueState) {
        this.baseSnapshot.clear();
        this.baseSnapshot.putAll(blocks);
        this.baseTileEntities.clear();
        this.baseTileEntities.putAll(tileEntities);
        this.baseEntityStates.clear();
        for (TickSnapshot.EntitySnapshot entityState : entityStates) {
            this.baseEntityStates.put(entityState.entityUUID(), entityState);
        }
        this.baseQueueState = queueState;
        this.currentIndex = -1;
    }

    public void addDelta(TickSnapshot delta) {
        // If we rewound and are adding new deltas, truncate future
        if (currentIndex < deltas.size() - 1) {
            deltas.subList(currentIndex + 1, deltas.size()).clear();
        }
        if (deltas.size() >= maxTicks) {
            TickSnapshot evicted = deltas.remove(0);
            rebaseFromEvictedDelta(evicted);
            if (currentIndex >= 0) {
                currentIndex--;
            }
        }
        deltas.add(delta);
        currentIndex = deltas.size() - 1;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getLength() {
        return deltas.size();
    }

    public boolean canRewind(int ticks) {
        return currentIndex - ticks >= -1;
    }

    public boolean canFastForward(int ticks) {
        return currentIndex + ticks < deltas.size();
    }

    /**
     * Get the delta at the specified index for replay purposes.
     */
    @Nullable
    public TickSnapshot getDelta(int index) {
        if (index < 0 || index >= deltas.size()) return null;
        return deltas.get(index);
    }

    /**
     * Rewind by N ticks. Returns list of deltas that need to be undone (in reverse order).
     */
    public List<TickSnapshot> rewind(int ticks) {
        List<TickSnapshot> toUndo = new ArrayList<>();
        for (int i = 0; i < ticks && currentIndex >= 0; i++) {
            toUndo.add(deltas.get(currentIndex));
            currentIndex--;
        }
        return toUndo;
    }

    /**
     * Fast-forward by N ticks. Returns list of deltas to replay.
     */
    public List<TickSnapshot> fastForward(int ticks) {
        List<TickSnapshot> toReplay = new ArrayList<>();
        for (int i = 0; i < ticks && currentIndex + 1 < deltas.size(); i++) {
            currentIndex++;
            toReplay.add(deltas.get(currentIndex));
        }
        return toReplay;
    }

    public Map<BlockPos, BlockState> getBaseSnapshot() {
        return baseSnapshot;
    }

    public Map<BlockPos, CompoundTag> getBaseTileEntities() {
        return baseTileEntities;
    }

    public Map<UUID, TickSnapshot.EntitySnapshot> getBaseEntityStates() {
        return baseEntityStates;
    }

    public FrozenTickQueue.QueueState getBaseQueueState() {
        return baseQueueState;
    }

    public List<TickSnapshot> getDeltas() {
        return deltas;
    }

    private void rebaseFromEvictedDelta(TickSnapshot delta) {
        for (Map.Entry<BlockPos, TickSnapshot.BlockStateChange> entry : delta.blockChanges().entrySet()) {
            BlockPos pos = entry.getKey();
            TickSnapshot.BlockStateChange change = entry.getValue();
            if (change.newState().isAir()) {
                baseSnapshot.remove(pos);
            } else {
                baseSnapshot.put(pos, change.newState());
            }
            if (change.newTileEntity() == null) {
                baseTileEntities.remove(pos);
            } else {
                baseTileEntities.put(pos, change.newTileEntity().copy());
            }
        }

        baseEntityStates.clear();
        for (TickSnapshot.EntitySnapshot entityState : delta.entityStatesAfter()) {
            baseEntityStates.put(entityState.entityUUID(), entityState);
        }
        baseQueueState = delta.queueAfter();
    }
}
