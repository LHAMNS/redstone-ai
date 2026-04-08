package com.redstoneai.workspace;

import com.redstoneai.tick.TickController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Shared revert implementation used by GUI, command, and RPC paths.
 * Restores the initial snapshot, tears down old frozen runtime state using the
 * previous geometry, and re-applies controller-backed workspace settings.
 */
public final class WorkspaceRevertService {
    private WorkspaceRevertService() {}

    public static Result revert(ServerLevel level,
                                Workspace workspace,
                                WorkspaceControllerBlockEntity controller,
                                InitialSnapshot snapshot) {
        boolean wasFrozen = workspace.isFrozen();
        BoundingBox previousBounds = workspace.getBounds();
        BlockPos previousOrigin = workspace.getOriginPos();

        if (wasFrozen) {
            TickController.discardFrozenState(level, workspace, previousBounds, previousOrigin);
        } else {
            TickController.removeQueue(workspace.getId());
        }

        int changed = snapshot.restore(level);
        BlockPos restoredControllerPos = snapshot.getControllerPos() != null
                ? snapshot.getControllerPos()
                : workspace.getControllerPos();
        WorkspaceControllerBlockEntity restoredController = level.getBlockEntity(restoredControllerPos) instanceof WorkspaceControllerBlockEntity be
                ? be
                : controller;

        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.updateWorkspaceGeometry(
                workspace,
                snapshot.getBounds(),
                restoredControllerPos,
                WorkspaceRules.originFromBounds(snapshot.getBounds())
        );

        clearWorkspaceEntities(level, snapshot.getBounds());
        workspace.setTimeline(null);
        workspace.setVirtualTick(0);
        workspace.retainIOMarkers(marker -> workspace.contains(marker.pos()));
        applyControllerSettings(workspace, restoredController);
        restoredController.setInitialSnapshot(snapshot);

        if (wasFrozen) {
            TickController.freeze(level, workspace);
        } else {
            workspace.setTemporalState(WorkspaceTemporalState.LIVE);
        }
        workspace.setLastMutationSource(WorkspaceMutationSource.REVERT);

        return new Result(changed, wasFrozen, restoredControllerPos, restoredController);
    }

    public static void applyControllerSettings(Workspace workspace, WorkspaceControllerBlockEntity controller) {
        workspace.setProtectionMode(controller.getProtectionMode());
        workspace.setEntityFilterMode(controller.getEntityFilterMode());
        workspace.replaceAuthorizedPlayers(controller.getAuthorizedPlayers());
        workspace.replacePlayerPermissionGrants(controller.getPlayerPermissionGrants());
        workspace.setAllowVanillaCommands(controller.isAllowVanillaCommands());
        workspace.setAllowFrozenEntityTeleport(controller.isAllowFrozenEntityTeleport());
        workspace.setAllowFrozenEntityDamage(controller.isAllowFrozenEntityDamage());
        workspace.setAllowFrozenEntityCollision(controller.isAllowFrozenEntityCollision());
    }

    private static void clearWorkspaceEntities(ServerLevel level, BoundingBox bounds) {
        AABB aabb = new AABB(
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX() + 1.0,
                bounds.maxY() + 1.0,
                bounds.maxZ() + 1.0
        );
        for (Entity entity : level.getEntitiesOfClass(Entity.class, aabb, entity -> !(entity instanceof Player))) {
            WorkspaceBypassContext.runWithEntityRemovalBypass(entity::discard);
        }
    }

    public record Result(int changedBlocks,
                         boolean wasFrozen,
                         BlockPos restoredControllerPos,
                         WorkspaceControllerBlockEntity restoredController) {
    }
}
