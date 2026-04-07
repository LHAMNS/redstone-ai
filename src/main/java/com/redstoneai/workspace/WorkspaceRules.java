package com.redstoneai.workspace;

import com.redstoneai.config.RAIConfig;
import com.redstoneai.registry.RAIBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared validation and permission rules for workspace operations.
 * Keeps GUI, commands, and RPC paths aligned.
 */
public final class WorkspaceRules {
    public static final UUID API_OWNER = new UUID(0L, 0L);
    private static final Pattern WORKSPACE_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,32}");

    private WorkspaceRules() {}

    @Nullable
    public static String validateWorkspaceName(String name) {
        if (name == null || name.isBlank()) {
            return "Workspace name cannot be empty";
        }
        if (!WORKSPACE_NAME_PATTERN.matcher(name).matches()) {
            return "Workspace name must match [a-zA-Z0-9_-]{1,32}";
        }
        return null;
    }

    @Nullable
    public static String validateDimensions(int sizeX, int sizeY, int sizeZ) {
        int maxSize = RAIConfig.SERVER.maxWorkspaceSize.get();
        if (sizeX < 4 || sizeX > maxSize || sizeY < 4 || sizeY > maxSize || sizeZ < 4 || sizeZ > maxSize) {
            return "Workspace size must be between 4 and " + maxSize + " on every axis";
        }
        return null;
    }

    public static boolean canPlayerManage(ServerPlayer player, Workspace ws) {
        return player.hasPermissions(2) || ws.getOwnerUUID().equals(player.getUUID());
    }

    public static boolean canAiModify(Workspace ws) {
        return ws.getProtectionMode().canAIModify();
    }

    public static boolean isEditableBlock(Workspace ws, BlockPos pos) {
        return ws.contains(pos) && !ws.getControllerPos().equals(pos);
    }

    public static BoundingBox createBoundsFromController(BlockPos controllerPos, int sizeX, int sizeY, int sizeZ) {
        return new BoundingBox(
                controllerPos.getX(),
                controllerPos.getY() - sizeY,
                controllerPos.getZ(),
                controllerPos.getX() + sizeX - 1,
                controllerPos.getY() - 1,
                controllerPos.getZ() + sizeZ - 1
        );
    }

    public static BoundingBox resizeBounds(BoundingBox currentBounds, int controllerY, int sizeX, int sizeY, int sizeZ) {
        return new BoundingBox(
                currentBounds.minX(),
                controllerY - sizeY,
                currentBounds.minZ(),
                currentBounds.minX() + sizeX - 1,
                controllerY - 1,
                currentBounds.minZ() + sizeZ - 1
        );
    }

    public static BlockPos originFromBounds(BoundingBox bounds) {
        return new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
    }

    public static boolean matchesDimensions(BoundingBox bounds, int sizeX, int sizeY, int sizeZ) {
        return bounds.getXSpan() == sizeX
                && bounds.getYSpan() == sizeY
                && bounds.getZSpan() == sizeZ;
    }

    public static boolean isValidTickCount(int count) {
        return count >= 1 && count <= RAIConfig.SERVER.maxStepsPerCall.get();
    }

    /**
     * Check whether a proposed bounds region contains the controller block position.
     */
    public static boolean boundsContainController(BlockPos controllerPos, BoundingBox bounds) {
        return bounds.isInside(controllerPos);
    }

    /**
     * Attempt to relocate the controller block to a position adjacent to the workspace
     * bounds (within 5 blocks), at the same Y level. The new position must be air and
     * have no entities.
     *
     * @return the new controller position, or null if no suitable position found
     */
    @Nullable
    public static BlockPos findRelocationSpot(ServerLevel level, BlockPos original, BoundingBox proposedBounds) {
        int y = original.getY();
        // Search in expanding rings around the bounds
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= proposedBounds.getXSpan() + radius; dx++) {
                for (int dz = -radius; dz <= proposedBounds.getZSpan() + radius; dz++) {
                    // Only check border ring at this radius
                    boolean isBorder = dx == -radius || dx == proposedBounds.getXSpan() + radius
                            || dz == -radius || dz == proposedBounds.getZSpan() + radius;
                    if (!isBorder) continue;

                    BlockPos candidate = new BlockPos(proposedBounds.minX() + dx, y, proposedBounds.minZ() + dz);
                    if (proposedBounds.isInside(candidate)) continue;
                    if (!level.getBlockState(candidate).isAir()) continue;
                    if (!level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                            new net.minecraft.world.phys.AABB(candidate), e -> true).isEmpty()) continue;
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Physically relocate the controller block in the world.
     * Places the controller block at newPos, removes from oldPos, updates block entity.
     */
    public static WorkspaceControllerBlockEntity relocateController(ServerLevel level,
                                                                   @Nullable Workspace workspace,
                                                                   BlockPos oldPos,
                                                                   BlockPos newPos) {
        level.setBlock(newPos, RAIBlocks.WORKSPACE_CONTROLLER.get().defaultBlockState(), 3);

        if (!(level.getBlockEntity(oldPos) instanceof WorkspaceControllerBlockEntity oldBe)) {
            level.setBlock(newPos, Blocks.AIR.defaultBlockState(), 3);
            return null;
        }
        if (!(level.getBlockEntity(newPos) instanceof WorkspaceControllerBlockEntity newBe)) {
            level.setBlock(newPos, Blocks.AIR.defaultBlockState(), 3);
            return null;
        }

        newBe.copyStateFrom(oldBe);
        if (workspace != null) {
            workspace.setControllerPos(newPos);
            WorkspaceManager.get(level).setDirty();
        }

        level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), 3);
        return newBe;
    }
}
