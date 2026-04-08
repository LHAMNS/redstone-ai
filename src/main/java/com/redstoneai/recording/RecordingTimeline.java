package com.redstoneai.recording;

import com.redstoneai.tick.FrozenTickQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
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

    public FrozenTickQueue.QueueState getCurrentQueueState() {
        if (currentIndex < 0) {
            return baseQueueState;
        }
        TickSnapshot current = getDelta(currentIndex);
        return current != null ? current.queueAfter() : baseQueueState;
    }

    public List<TickSnapshot> getDeltas() {
        return deltas;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("maxTicks", maxTicks);
        tag.putInt("currentIndex", currentIndex);
        tag.put("baseBlocks", saveBaseBlocks());
        tag.put("baseEntityStates", saveBaseEntityStates());
        tag.put("baseQueueState", FrozenTickQueue.saveQueueState(baseQueueState));

        ListTag deltaList = new ListTag();
        for (TickSnapshot delta : deltas) {
            deltaList.add(delta.save());
        }
        tag.put("deltas", deltaList);
        return tag;
    }

    public static RecordingTimeline load(CompoundTag tag) {
        RecordingTimeline timeline = new RecordingTimeline(tag.getInt("maxTicks"));
        timeline.currentIndex = tag.getInt("currentIndex");

        ListTag baseBlockList = tag.getList("baseBlocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < baseBlockList.size(); i++) {
            CompoundTag blockTag = baseBlockList.getCompound(i);
            BlockPos pos = readPos(blockTag, "pos");
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), blockTag.getCompound("state"));
            timeline.baseSnapshot.put(pos, state);
            if (blockTag.contains("tile", Tag.TAG_COMPOUND)) {
                timeline.baseTileEntities.put(pos, blockTag.getCompound("tile").copy());
            }
        }

        ListTag baseEntityList = tag.getList("baseEntityStates", Tag.TAG_COMPOUND);
        for (int i = 0; i < baseEntityList.size(); i++) {
            TickSnapshot.EntitySnapshot snapshot = TickSnapshot.EntitySnapshot.load(baseEntityList.getCompound(i));
            timeline.baseEntityStates.put(snapshot.entityUUID(), snapshot);
        }

        if (tag.contains("baseQueueState", Tag.TAG_COMPOUND)) {
            timeline.baseQueueState = FrozenTickQueue.loadQueueState(tag.getCompound("baseQueueState"));
        }

        ListTag deltaList = tag.getList("deltas", Tag.TAG_COMPOUND);
        for (int i = 0; i < deltaList.size(); i++) {
            timeline.deltas.add(TickSnapshot.load(deltaList.getCompound(i)));
        }

        if (timeline.currentIndex >= timeline.deltas.size()) {
            timeline.currentIndex = timeline.deltas.size() - 1;
        }
        return timeline;
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

    private ListTag saveBaseBlocks() {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : baseSnapshot.entrySet()) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.putIntArray("pos", new int[]{entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()});
            blockTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
            CompoundTag tile = baseTileEntities.get(entry.getKey());
            if (tile != null) {
                blockTag.put("tile", tile.copy());
            }
            list.add(blockTag);
        }
        return list;
    }

    private ListTag saveBaseEntityStates() {
        ListTag list = new ListTag();
        for (TickSnapshot.EntitySnapshot snapshot : baseEntityStates.values()) {
            list.add(snapshot.save());
        }
        return list;
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        int[] pos = tag.getIntArray(key);
        if (pos.length < 3) {
            throw new IllegalStateException("Corrupted recording timeline: " + key + " is missing coordinates");
        }
        return new BlockPos(pos[0], pos[1], pos[2]);
    }
}
