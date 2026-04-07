package com.redstoneai.workspace;

import com.redstoneai.network.RAINetwork;
import com.redstoneai.network.SelectionPreviewSyncPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import com.redstoneai.tick.TickController;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-player range selection state for workspace configuration.
 * <p>
 * Flow: player enters selection mode (from GUI button or sneak+right-click the controller),
 * then clicks two ground positions to define the XZ bounds. The height (Y) is taken from
 * the controller block entity's configured sizeY.
 * <p>
 * The active selection is bound to the controller name observed when selection began so
 * stale controller moves or workspace relinks fail closed instead of mutating the wrong
 * workspace.
 */
public final class SelectionManager {

    private record SelectionState(BlockPos controllerPos, String workspaceName, @Nullable BlockPos corner1) {}

    private static final Map<UUID, SelectionState> activeSelections = new HashMap<>();

    private SelectionManager() {}

    /**
     * Enter selection mode for a player.
     */
    public static void beginSelection(ServerPlayer player, BlockPos controllerPos, String workspaceName) {
        activeSelections.put(player.getUUID(), new SelectionState(controllerPos, workspaceName, null));
        syncPreview(player);
        player.sendSystemMessage(Component.translatable("message.redstone_ai.select_mode_enter")
                .withStyle(ChatFormatting.AQUA));
    }

    /**
     * Cancel selection mode.
     */
    public static void cancelSelection(ServerPlayer player) {
        if (activeSelections.remove(player.getUUID()) != null) {
            syncPreview(player);
            player.sendSystemMessage(Component.translatable("message.redstone_ai.select_mode_cancel")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * Returns true if the player is currently in selection mode.
     */
    public static boolean isSelecting(UUID playerId) {
        return activeSelections.containsKey(playerId);
    }

    /**
     * Process a ground click during selection mode.
     * First click sets corner1, second click completes the selection.
     *
     * @return true if the click was consumed
     */
    public static boolean onGroundClick(ServerPlayer player, BlockPos clickedPos) {
        SelectionState state = activeSelections.get(player.getUUID());
        if (state == null) {
            return false;
        }

        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(state.controllerPos) instanceof WorkspaceControllerBlockEntity controller)) {
            return failSelection(player, "Selection expired: controller no longer exists.");
        }
        if (!controller.getWorkspaceName().equals(state.workspaceName)) {
            return failSelection(player, "Selection expired: controller binding changed.");
        }

        Workspace workspace = null;
        if (!state.workspaceName.isEmpty()) {
            workspace = WorkspaceManager.get(level).getByName(state.workspaceName);
            if (workspace == null || !workspace.isControllerPos(state.controllerPos)) {
                return failSelection(player, "Selection expired: workspace no longer matches this controller.");
            }
            if (!WorkspaceRules.canPlayerManage(player, workspace)) {
                return failSelection(player, "You no longer have permission to manage that workspace.");
            }
            if (workspace.isFrozen()) {
                return failSelection(player, "That workspace is frozen. Unfreeze it before changing the range.");
            }
        }

        if (state.corner1 == null) {
            activeSelections.put(player.getUUID(), new SelectionState(state.controllerPos, state.workspaceName, clickedPos));
            syncPreview(player);
            player.sendSystemMessage(Component.translatable("message.redstone_ai.select_mode_first",
                    clickedPos.toShortString()).withStyle(ChatFormatting.GREEN));
            return true;
        }

        BlockPos c1 = state.corner1;
        BlockPos c2 = clickedPos;
        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = Math.max(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = Math.max(c1.getZ(), c2.getZ());

        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int sizeY = controller.getSizeY();
        String dimensionError = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (dimensionError != null) {
            return failSelection(player, dimensionError);
        }

        activeSelections.remove(player.getUUID());
        syncPreview(player);

        BoundingBox bounds = new BoundingBox(
                minX,
                state.controllerPos.getY() - sizeY,
                minZ,
                maxX,
                state.controllerPos.getY() - 1,
                maxZ
        );
        if (workspace != null) {
            WorkspaceManager manager = WorkspaceManager.get(level);
            String overlapping = manager.checkOverlap(bounds, workspace.getId());
            if (overlapping != null) {
                return failSelection(player, "Selection overlaps workspace '" + overlapping + "'.");
            }
        }
        BlockPos finalControllerPos = state.controllerPos;
        WorkspaceControllerBlockEntity targetController = controller;

        if (bounds.isInside(state.controllerPos)) {
            BlockPos newPos = WorkspaceRules.findRelocationSpot(level, state.controllerPos, bounds);
            if (newPos == null) {
                return failSelection(player, "Unable to relocate the controller outside the selected bounds.");
            }

            WorkspaceControllerBlockEntity relocated = WorkspaceRules.relocateController(level, workspace, state.controllerPos, newPos);
            if (relocated == null) {
                return failSelection(player, "Unable to relocate the controller.");
            }

            targetController = relocated;
            finalControllerPos = newPos;
            player.sendSystemMessage(Component.translatable("message.redstone_ai.controller_in_bounds",
                    newPos.toShortString()).withStyle(ChatFormatting.GOLD));
        }

        if (workspace != null) {
            WorkspaceManager manager = WorkspaceManager.get(level);
            manager.updateWorkspaceGeometry(workspace, bounds, finalControllerPos, WorkspaceRules.originFromBounds(bounds));
        }

        targetController.setSize(sizeX, sizeY, sizeZ);
        targetController.setInitialSnapshot(InitialSnapshot.capture(level, bounds));
        if (workspace != null) {
            Workspace selectedWorkspace = workspace;
            selectedWorkspace.retainIOMarkers(marker -> selectedWorkspace.contains(marker.pos()));
            TickController.invalidateRecording(level, selectedWorkspace);
        }

        player.sendSystemMessage(Component.translatable("message.redstone_ai.select_mode_done",
                        new BlockPos(minX, bounds.minY(), minZ).toShortString(),
                        new BlockPos(maxX, bounds.maxY(), maxZ).toShortString())
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    @Nullable
    public static BlockPos getFirstCorner(UUID playerId) {
        SelectionState state = activeSelections.get(playerId);
        return state != null ? state.corner1 : null;
    }

    @Nullable
    public static BlockPos getControllerPos(UUID playerId) {
        SelectionState state = activeSelections.get(playerId);
        return state != null ? state.controllerPos : null;
    }

    private static boolean failSelection(ServerPlayer player, String message) {
        activeSelections.remove(player.getUUID());
        syncPreview(player);
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        return true;
    }

    private static void syncPreview(ServerPlayer player) {
        SelectionState state = activeSelections.get(player.getUUID());
        if (state == null) {
            RAINetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new SelectionPreviewSyncPacket(player.serverLevel().dimension().location(), BlockPos.ZERO, null, 0, false));
            return;
        }

        int sizeY = 8;
        if (player.serverLevel().getBlockEntity(state.controllerPos) instanceof WorkspaceControllerBlockEntity controller) {
            sizeY = controller.getSizeY();
        }
        RAINetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SelectionPreviewSyncPacket(
                        player.serverLevel().dimension().location(),
                        state.controllerPos,
                        state.corner1,
                        sizeY,
                        true
                ));
    }
}
