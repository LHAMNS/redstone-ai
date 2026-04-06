package com.redstoneai.recording;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import com.redstoneai.tick.FrozenTickQueue;

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
    ) {}

    public boolean hasChanges() {
        return !blockChanges.isEmpty() || !entityStatesBefore.isEmpty() || !entityStatesAfter.isEmpty();
    }
}
