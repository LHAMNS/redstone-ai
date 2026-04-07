package com.redstoneai.workspace;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Captures and restores the complete initial state of a workspace region.
 * Taken when the workspace is first created (block placed + /rai create),
 * this snapshot allows one-click revert to the original state of all blocks,
 * including block states and tile entity data.
 * <p>
 * Stored as NBT inside the controller block entity. For a 32x32x32 workspace
 * with mostly air, only non-air blocks are stored (~50KB typical).
 */
public class InitialSnapshot {

    private final Map<BlockPos, BlockState> blockStates;
    private final Map<BlockPos, CompoundTag> tileEntities;
    private final BoundingBox bounds;
    @Nullable
    private final BlockPos controllerPos;

    private InitialSnapshot(BoundingBox bounds, @Nullable BlockPos controllerPos,
                            Map<BlockPos, BlockState> blockStates,
                            Map<BlockPos, CompoundTag> tileEntities) {
        this.bounds = bounds;
        this.controllerPos = controllerPos;
        this.blockStates = Map.copyOf(blockStates);
        this.tileEntities = Map.copyOf(tileEntities);
    }

    /**
     * Capture the current state of all blocks within the given bounds.
     * Only stores non-air blocks to save memory and NBT space.
     */
    public static InitialSnapshot capture(ServerLevel level, BoundingBox bounds) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> tiles = new HashMap<>();
        BlockPos controllerPos = null;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(pos.immutable(), state);
                    }
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        BlockPos immutablePos = pos.immutable();
                        tiles.put(immutablePos, captureBlockEntity(be));
                        if (be instanceof WorkspaceControllerBlockEntity) {
                            controllerPos = immutablePos;
                        }
                    }
                }
            }
        }

        return new InitialSnapshot(bounds, controllerPos, blocks, tiles);
    }

    private static CompoundTag captureBlockEntity(BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithoutMetadata();
        if (blockEntity instanceof WorkspaceControllerBlockEntity) {
            tag.remove("initialSnapshot");
        }
        return tag.copy();
    }

    /**
     * Restore the workspace to this snapshot's state. Every position in bounds
     * is set to either the stored block state or air, including the controller
     * block entity so its metadata rolls back with the region. Tile entities are
     * restored from saved NBT.
     *
     * @return number of blocks changed
     */
    public int restore(ServerLevel level) {
        int changed = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    BlockState targetState = blockStates.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    BlockState currentState = level.getBlockState(pos);

                    if (!currentState.equals(targetState)) {
                        level.setBlock(pos, targetState, 2 | 16);
                        changed++;
                    }
                }
            }
        }

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    CompoundTag savedTile = tileEntities.get(pos);
                    if (savedTile == null) {
                        continue;
                    }

                    BlockState targetState = blockStates.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null && targetState.hasBlockEntity() && !targetState.isAir()) {
                        level.setBlock(pos, targetState, 2 | 16);
                        be = level.getBlockEntity(pos);
                        changed++;
                    }

                    if (be != null) {
                        be.load(savedTile.copy());
                        be.setChanged();
                    }
                }
            }
        }

        return changed;
    }

    public int getBlockCount() {
        return blockStates.size();
    }

    public BoundingBox getBounds() {
        return bounds;
    }

    public BlockState getBlockState(BlockPos pos) {
        return blockStates.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Nullable
    public CompoundTag getTileEntityData(BlockPos pos) {
        CompoundTag tag = tileEntities.get(pos);
        return tag != null ? tag.copy() : null;
    }

    public Set<BlockPos> getStoredPositions() {
        return Collections.unmodifiableSet(blockStates.keySet());
    }

    @Nullable
    public BlockPos getControllerPos() {
        return controllerPos;
    }

    // NBT persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("bounds", new int[]{
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        });
        if (controllerPos != null) {
            tag.putIntArray("controllerPos", new int[]{
                    controllerPos.getX(), controllerPos.getY(), controllerPos.getZ()
            });
        }

        ListTag blockList = new ListTag();
        for (var entry : blockStates.entrySet()) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.putIntArray("pos", new int[]{
                    entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()
            });
            blockTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
            CompoundTag tile = tileEntities.get(entry.getKey());
            if (tile != null) {
                blockTag.put("tile", tile.copy());
            }
            blockList.add(blockTag);
        }
        tag.put("blocks", blockList);

        return tag;
    }

    public static InitialSnapshot load(CompoundTag tag) {
        int[] b = tag.getIntArray("bounds");
        BoundingBox bounds = new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
        BlockPos controllerPos = null;
        if (tag.contains("controllerPos", Tag.TAG_INT_ARRAY)) {
            int[] c = tag.getIntArray("controllerPos");
            if (c.length == 3) {
                controllerPos = new BlockPos(c[0], c[1], c[2]);
            }
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> tiles = new HashMap<>();

        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            int[] p = blockTag.getIntArray("pos");
            BlockPos pos = new BlockPos(p[0], p[1], p[2]);
            BlockState state = NbtUtils.readBlockState(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                    blockTag.getCompound("state")
            );
            blocks.put(pos, state);
            if (blockTag.contains("tile", Tag.TAG_COMPOUND)) {
                CompoundTag tile = blockTag.getCompound("tile").copy();
                tiles.put(pos, tile);
                if (controllerPos == null && isControllerTile(tile)) {
                    controllerPos = pos;
                }
            }
        }

        return new InitialSnapshot(bounds, controllerPos, blocks, tiles);
    }

    private boolean isControllerPosition(BlockPos pos, @Nullable BlockEntity blockEntity) {
        if (controllerPos != null) {
            return controllerPos.equals(pos);
        }
        return blockEntity instanceof WorkspaceControllerBlockEntity;
    }

    private static boolean isControllerTile(CompoundTag tile) {
        return tile.contains("workspaceName", Tag.TAG_STRING)
                && tile.contains("operationLog", Tag.TAG_COMPOUND)
                && tile.contains("chatHistory", Tag.TAG_LIST);
    }
}
