package com.redstoneai.recording;

import com.redstoneai.tick.FrozenTickQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of changes that occurred during one tick step.
 * Stores block changes (old+new state for bidirectional replay),
 * entity states (for rewind), and IO power levels.
 */
public record TickSnapshot(
        int tickIndex,
        Map<BlockPos, BlockStateChange> blockChanges,
        Map<BlockPos, Integer> ioPowerLevels,
        List<EntitySnapshot> entityStatesBefore,
        List<EntitySnapshot> entityStatesAfter,
        FrozenTickQueue.QueueState queueBefore,
        FrozenTickQueue.QueueState queueAfter
) {
    /**
     * Records a single block state change with both old and new state for bidirectional replay.
     */
    public record BlockStateChange(
            BlockState oldState,
            BlockState newState,
            CompoundTag oldTileEntity,
            CompoundTag newTileEntity
    ) {}

    /**
     * Records the state of a single entity at a point in time.
     * Stores full NBT for exact restoration (position, motion, inventory, health, etc.).
     */
    public record EntitySnapshot(
            UUID entityUUID,
            String entityTypeId,
            CompoundTag fullNbt
    ) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("entityUUID", entityUUID);
            tag.putString("entityTypeId", entityTypeId);
            tag.put("fullNbt", fullNbt.copy());
            return tag;
        }

        public static EntitySnapshot load(CompoundTag tag) {
            String entityTypeId = tag.getString("entityTypeId");
            ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
            if (key == null) {
                throw new IllegalStateException("Corrupted entity snapshot type: " + entityTypeId);
            }
            return new EntitySnapshot(
                    tag.getUUID("entityUUID"),
                    entityTypeId,
                    tag.getCompound("fullNbt").copy()
            );
        }
    }

    public boolean hasChanges() {
        return !blockChanges.isEmpty() || !entityStatesBefore.isEmpty() || !entityStatesAfter.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("tickIndex", tickIndex);
        tag.put("blockChanges", saveBlockChanges(blockChanges));
        tag.put("ioPowerLevels", saveIoPowerLevels(ioPowerLevels));
        tag.put("entityStatesBefore", saveEntityStates(entityStatesBefore));
        tag.put("entityStatesAfter", saveEntityStates(entityStatesAfter));
        tag.put("queueBefore", FrozenTickQueue.saveQueueState(queueBefore != null ? queueBefore : emptyQueueState()));
        tag.put("queueAfter", FrozenTickQueue.saveQueueState(queueAfter != null ? queueAfter : emptyQueueState()));
        return tag;
    }

    public static TickSnapshot load(CompoundTag tag) {
        return new TickSnapshot(
                tag.getInt("tickIndex"),
                loadBlockChanges(tag.getList("blockChanges", Tag.TAG_COMPOUND)),
                loadIoPowerLevels(tag.getList("ioPowerLevels", Tag.TAG_COMPOUND)),
                loadEntityStates(tag.getList("entityStatesBefore", Tag.TAG_COMPOUND)),
                loadEntityStates(tag.getList("entityStatesAfter", Tag.TAG_COMPOUND)),
                tag.contains("queueBefore", Tag.TAG_COMPOUND)
                        ? FrozenTickQueue.loadQueueState(tag.getCompound("queueBefore"))
                        : emptyQueueState(),
                tag.contains("queueAfter", Tag.TAG_COMPOUND)
                        ? FrozenTickQueue.loadQueueState(tag.getCompound("queueAfter"))
                        : emptyQueueState()
        );
    }

    private static ListTag saveBlockChanges(Map<BlockPos, BlockStateChange> blockChanges) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockStateChange> entry : blockChanges.entrySet()) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.putIntArray("pos", posArray(entry.getKey()));
            blockTag.put("oldState", NbtUtils.writeBlockState(entry.getValue().oldState()));
            blockTag.put("newState", NbtUtils.writeBlockState(entry.getValue().newState()));
            if (entry.getValue().oldTileEntity() != null) {
                blockTag.put("oldTileEntity", entry.getValue().oldTileEntity().copy());
            }
            if (entry.getValue().newTileEntity() != null) {
                blockTag.put("newTileEntity", entry.getValue().newTileEntity().copy());
            }
            list.add(blockTag);
        }
        return list;
    }

    private static Map<BlockPos, BlockStateChange> loadBlockChanges(ListTag list) {
        Map<BlockPos, BlockStateChange> changes = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag blockTag = list.getCompound(i);
            BlockPos pos = readPos(blockTag, "pos");
            BlockState oldState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), blockTag.getCompound("oldState"));
            BlockState newState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), blockTag.getCompound("newState"));
            CompoundTag oldTile = blockTag.contains("oldTileEntity", Tag.TAG_COMPOUND)
                    ? blockTag.getCompound("oldTileEntity").copy()
                    : null;
            CompoundTag newTile = blockTag.contains("newTileEntity", Tag.TAG_COMPOUND)
                    ? blockTag.getCompound("newTileEntity").copy()
                    : null;
            changes.put(pos, new BlockStateChange(oldState, newState, oldTile, newTile));
        }
        return changes;
    }

    private static ListTag saveIoPowerLevels(Map<BlockPos, Integer> ioPowerLevels) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Integer> entry : ioPowerLevels.entrySet()) {
            CompoundTag ioTag = new CompoundTag();
            ioTag.putIntArray("pos", posArray(entry.getKey()));
            ioTag.putInt("power", entry.getValue());
            list.add(ioTag);
        }
        return list;
    }

    private static Map<BlockPos, Integer> loadIoPowerLevels(ListTag list) {
        Map<BlockPos, Integer> ioLevels = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag ioTag = list.getCompound(i);
            ioLevels.put(readPos(ioTag, "pos"), ioTag.getInt("power"));
        }
        return ioLevels;
    }

    private static ListTag saveEntityStates(List<EntitySnapshot> states) {
        ListTag list = new ListTag();
        for (EntitySnapshot state : states) {
            list.add(state.save());
        }
        return list;
    }

    private static List<EntitySnapshot> loadEntityStates(ListTag list) {
        List<EntitySnapshot> states = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            states.add(EntitySnapshot.load(list.getCompound(i)));
        }
        return List.copyOf(states);
    }

    private static int[] posArray(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        int[] pos = tag.getIntArray(key);
        if (pos.length < 3) {
            throw new IllegalStateException("Corrupted tick snapshot: " + key + " is missing coordinates");
        }
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    private static FrozenTickQueue.QueueState emptyQueueState() {
        return new FrozenTickQueue.QueueState(List.of(), List.of(), List.of(), List.of());
    }
}
