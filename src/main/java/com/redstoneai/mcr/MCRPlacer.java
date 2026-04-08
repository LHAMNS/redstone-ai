package com.redstoneai.mcr;

import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Places parsed MCR blocks into a workspace region.
 * Coordinates in MCRBlock are relative to the workspace origin (controller position).
 */
public final class MCRPlacer {

    public record PlaceResult(int placed, int skipped) {}

    private MCRPlacer() {}

    /**
     * Place a list of MCR blocks into the workspace.
     * Positions are offset relative to workspace origin.
     */
    public static PlaceResult place(ServerLevel level, Workspace ws, List<MCRBlock> blocks) {
        BlockPos origin = ws.getOriginPos();
        BoundingBox bounds = ws.getBounds();
        int placed = 0;
        int skipped = 0;

        for (MCRBlock mcr : blocks) {
            // Handle @fill directive: fill entire workspace with this block
            if ("fill".equals(mcr.extraProperties().get("_directive"))) {
                BlockState fillState = resolveBlockState(mcr);
                if (fillState == null) { skipped++; continue; }
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                            BlockPos fillPos = new BlockPos(x, y, z);
                            if (!WorkspaceRules.isEditableBlock(ws, fillPos)) {
                                skipped++;
                                continue;
                            }
                            level.setBlock(fillPos, fillState, 3);
                            if (placementSatisfied(level.getBlockState(fillPos), fillState, mcr)) {
                                placed++;
                            } else {
                                skipped++;
                            }
                        }
                    }
                }
                continue;
            }

            BlockPos worldPos = origin.offset(mcr.x(), mcr.y(), mcr.z());

            if (!WorkspaceRules.isEditableBlock(ws, worldPos)) {
                skipped++;
                continue;
            }

            BlockState state = resolveBlockState(mcr);
            if (state == null) {
                skipped++;
                continue;
            }

            level.setBlock(worldPos, state, 3);
            if (placementSatisfied(level.getBlockState(worldPos), state, mcr)) {
                placed++;
            } else {
                skipped++;
            }
        }

        return new PlaceResult(placed, skipped);
    }

    private static BlockState resolveBlockState(MCRBlock mcr) {
        ResourceLocation blockId = new ResourceLocation("minecraft", mcr.blockType());
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        if (block == Blocks.AIR && !"air".equals(mcr.blockType())) {
            return null; // Unknown block
        }

        BlockState state = block.defaultBlockState();

        // Apply facing
        if (mcr.facing() != null) {
            state = applyFacing(state, mcr.facing());
        }

        // Apply delay (repeater/comparator: 1-4)
        if (mcr.delay() > 0 && state.hasProperty(BlockStateProperties.DELAY)) {
            state = state.setValue(BlockStateProperties.DELAY, mcr.delay());
        }

        // Apply comparator mode
        if (mcr.mode() != null && state.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
            ComparatorMode cmode = "subtract".equals(mcr.mode()) ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE;
            state = state.setValue(BlockStateProperties.MODE_COMPARATOR, cmode);
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyFacing(BlockState state, Direction facing) {
        // Try FACING (6-way: pistons, observers, dispensers, droppers)
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.setValue(BlockStateProperties.FACING, facing);
        }
        // Try HORIZONTAL_FACING (4-way: repeater, comparator)
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        // Try ATTACH_FACE + HORIZONTAL_FACING (levers, buttons)
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            if (facing == Direction.UP) {
                return state.setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.CEILING);
            } else if (facing == Direction.DOWN) {
                return state.setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
            } else {
                return state.setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        return state;
    }

    private static boolean placementSatisfied(BlockState actual, BlockState requestedState, MCRBlock mcr) {
        if (!actual.is(requestedState.getBlock())) {
            return false;
        }
        if (mcr.facing() != null) {
            if (requestedState.hasProperty(BlockStateProperties.FACING)
                    && !actual.getValue(BlockStateProperties.FACING).equals(requestedState.getValue(BlockStateProperties.FACING))) {
                return false;
            }
            if (requestedState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    && !actual.getValue(BlockStateProperties.HORIZONTAL_FACING).equals(requestedState.getValue(BlockStateProperties.HORIZONTAL_FACING))) {
                return false;
            }
            if (requestedState.hasProperty(BlockStateProperties.ATTACH_FACE)
                    && !actual.getValue(BlockStateProperties.ATTACH_FACE).equals(requestedState.getValue(BlockStateProperties.ATTACH_FACE))) {
                return false;
            }
        }
        if (mcr.delay() > 0 && requestedState.hasProperty(BlockStateProperties.DELAY)
                && !actual.getValue(BlockStateProperties.DELAY).equals(requestedState.getValue(BlockStateProperties.DELAY))) {
            return false;
        }
        if (mcr.mode() != null && requestedState.hasProperty(BlockStateProperties.MODE_COMPARATOR)
                && !actual.getValue(BlockStateProperties.MODE_COMPARATOR).equals(requestedState.getValue(BlockStateProperties.MODE_COMPARATOR))) {
            return false;
        }
        return true;
    }
}
