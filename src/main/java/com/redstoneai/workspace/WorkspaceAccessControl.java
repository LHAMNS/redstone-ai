package com.redstoneai.workspace;

import com.redstoneai.tick.TickInterceptor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Centralized permission and protection checks for workspace mutations.
 */
public final class WorkspaceAccessControl {
    private WorkspaceAccessControl() {}

    public static List<String> parseAuthorizedPlayers(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }

        for (String token : raw.split(",")) {
            String normalized = normalizePlayerName(token);
            if (!normalized.isBlank() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    public static String formatAuthorizedPlayers(List<String> players) {
        return String.join(", ", players);
    }

    public static List<String> serializePermissionGrants(List<PlayerPermissionGrant> grants) {
        List<String> serialized = new ArrayList<>();
        for (PlayerPermissionGrant grant : grants) {
            List<String> permissions = new ArrayList<>();
            for (WorkspacePermission permission : grant.permissions()) {
                permissions.add(permission.getSerializedName());
            }
            serialized.add(grant.playerName() + "|" + String.join(",", permissions));
        }
        return serialized;
    }

    public static List<PlayerPermissionGrant> deserializePermissionGrants(List<String> serialized) {
        List<PlayerPermissionGrant> grants = new ArrayList<>();
        for (String entry : serialized) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] split = entry.split("\\|", 2);
            String playerName = normalizePlayerName(split[0]);
            if (playerName.isBlank()) {
                continue;
            }
            java.util.EnumSet<WorkspacePermission> permissions = java.util.EnumSet.noneOf(WorkspacePermission.class);
            if (split.length == 2 && !split[1].isBlank()) {
                for (String token : split[1].split(",")) {
                    WorkspacePermission permission = WorkspacePermission.tryParse(token.trim());
                    if (permission != null) {
                        permissions.add(permission);
                    }
                }
            }
            if (!permissions.isEmpty()) {
                grants.add(new PlayerPermissionGrant(playerName, permissions));
            }
        }
        return grants;
    }

    public static String formatPermissionGrantLine(PlayerPermissionGrant grant) {
        List<String> tokens = new ArrayList<>();
        for (WorkspacePermission permission : grant.permissions()) {
            tokens.add(permission.getSerializedName());
        }
        return grant.playerName() + ": " + String.join("/", tokens);
    }

    public static String normalizePlayerName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isAuthorizedListedPlayer(Player player, Workspace workspace) {
        String normalized = normalizePlayerName(player.getGameProfile().getName());
        return workspace.getAuthorizedPlayers().contains(normalized);
    }

    public static boolean hasPermission(ServerPlayer player, Workspace workspace, WorkspacePermission permission) {
        if (WorkspaceRules.canPlayerManage(player, workspace)) {
            return true;
        }
        return workspace.hasPermission(player.getGameProfile().getName(), permission);
    }

    public static boolean canPlayerModify(ServerPlayer player, Workspace workspace) {
        return workspace.getProtectionMode().canPlayerModify()
                && hasPermission(player, workspace, WorkspacePermission.BUILD);
    }

    public static boolean canPlayerInteract(ServerPlayer player, Workspace workspace) {
        return canPlayerModify(player, workspace);
    }

    public static boolean canPlayerViewHistory(ServerPlayer player, Workspace workspace) {
        return hasPermission(player, workspace, WorkspacePermission.VIEW_HISTORY);
    }

    public static boolean canPlayerChat(ServerPlayer player, Workspace workspace) {
        return hasPermission(player, workspace, WorkspacePermission.CHAT);
    }

    public static boolean canPlayerUseTimeControls(ServerPlayer player, Workspace workspace) {
        return hasPermission(player, workspace, WorkspacePermission.TIME_CONTROL);
    }

    public static boolean canPlayerManageSettings(ServerPlayer player, Workspace workspace) {
        return hasPermission(player, workspace, WorkspacePermission.MANAGE_SETTINGS);
    }

    public static boolean canPlayerOpenMenu(ServerPlayer player, Workspace workspace) {
        return canPlayerModify(player, workspace)
                || canPlayerUseTimeControls(player, workspace)
                || canPlayerViewHistory(player, workspace)
                || canPlayerChat(player, workspace)
                || canPlayerManageSettings(player, workspace);
    }

    @Nullable
    public static Workspace findWorkspaceForCommandMutation(ServerLevel level, BlockPos pos) {
        Workspace workspace = WorkspaceManager.get(level).getWorkspaceAt(pos);
        if (workspace == null && WorkspaceManager.get(level).getByControllerPos(pos) != null) {
            workspace = WorkspaceManager.get(level).getByControllerPos(pos);
        }
        if (workspace != null && !workspace.isAllowVanillaCommands()) {
            return workspace;
        }
        return null;
    }

    @Nullable
    public static Workspace findWorkspaceForCommandMutation(ServerLevel level, BoundingBox area) {
        for (Workspace workspace : WorkspaceManager.get(level).getAllWorkspacesSnapshot()) {
            BoundingBox bounds = workspace.getBounds();
            if (!workspace.isAllowVanillaCommands() && bounds.intersects(area)) {
                return workspace;
            }
            if (!workspace.isAllowVanillaCommands() && area.isInside(workspace.getControllerPos())) {
                return workspace;
            }
        }
        return null;
    }

    @Nullable
    public static Workspace findFrozenWorkspaceForEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        Workspace workspace = WorkspaceManager.get(level).getWorkspaceContainingAABB(entity.getBoundingBox());
        if (workspace != null && workspace.isFrozen() && !TickInterceptor.isStepping(workspace)) {
            return workspace;
        }
        return null;
    }

    @Nullable
    public static Workspace findWorkspaceForEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return WorkspaceManager.get(level).getWorkspaceContainingAABB(entity.getBoundingBox());
    }

    @Nullable
    public static Workspace findWorkspaceForAabb(ServerLevel level, AABBLike aabb) {
        return WorkspaceManager.get(level).getWorkspaceContainingAABB(
                new net.minecraft.world.phys.AABB(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ())
        );
    }

    public static boolean isFrozenEntityTeleportBlocked(Entity entity) {
        Workspace workspace = findFrozenWorkspaceForEntity(entity);
        return workspace != null && !workspace.isAllowFrozenEntityTeleport();
    }

    public static boolean isFrozenEntityDamageBlocked(Entity entity) {
        Workspace workspace = findFrozenWorkspaceForEntity(entity);
        return workspace != null && !workspace.isAllowFrozenEntityDamage();
    }

    public static boolean isFrozenEntityCollisionProtected(Entity entity) {
        Workspace workspace = findFrozenWorkspaceForEntity(entity);
        return workspace != null && !workspace.isAllowFrozenEntityCollision();
    }

    public static boolean shouldBlockExternalEntityInteraction(Entity protectedEntity, @Nullable Entity other) {
        Workspace workspace = findWorkspaceForEntity(protectedEntity);
        if (workspace == null || workspace.isAllowFrozenEntityCollision()) {
            return false;
        }
        if (other == null) {
            return true;
        }
        if (other instanceof Player) {
            return true;
        }

        Workspace otherWorkspace = findWorkspaceForEntity(other);
        return otherWorkspace == null || !otherWorkspace.getId().equals(workspace.getId());
    }

    public static boolean shouldBlockEntityDamage(Entity protectedEntity, @Nullable Entity sourceEntity) {
        Workspace workspace = findFrozenWorkspaceForEntity(protectedEntity);
        if (workspace == null || workspace.isAllowFrozenEntityDamage()) {
            return false;
        }
        if (sourceEntity == null) {
            return true;
        }
        if (sourceEntity instanceof Player) {
            return true;
        }

        Workspace otherWorkspace = findFrozenWorkspaceForEntity(sourceEntity);
        return otherWorkspace == null || !otherWorkspace.getId().equals(workspace.getId());
    }

    public static boolean wouldCrossWorkspaceBoundary(Entity entity, net.minecraft.world.phys.AABB targetBox) {
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        Workspace current = findWorkspaceForEntity(entity);
        Workspace target = findWorkspaceForAabb(level, new AabbWrapper(targetBox));

        if (current == null && target == null) {
            return false;
        }
        if (current == null || target == null) {
            return true;
        }
        return !current.getId().equals(target.getId());
    }

    private static boolean containsFully(Workspace workspace, AABBLike aabb) {
        return aabb.minX() >= workspace.getBounds().minX()
                && aabb.minY() >= workspace.getBounds().minY()
                && aabb.minZ() >= workspace.getBounds().minZ()
                && aabb.maxX() <= workspace.getBounds().maxX() + 1.0
                && aabb.maxY() <= workspace.getBounds().maxY() + 1.0
                && aabb.maxZ() <= workspace.getBounds().maxZ() + 1.0;
    }

    public interface AABBLike {
        double minX();
        double minY();
        double minZ();
        double maxX();
        double maxY();
        double maxZ();
    }

    private record AabbWrapper(net.minecraft.world.phys.AABB box) implements AABBLike {
        @Override public double minX() { return box.minX; }
        @Override public double minY() { return box.minY; }
        @Override public double minZ() { return box.minZ; }
        @Override public double maxX() { return box.maxX; }
        @Override public double maxY() { return box.maxY; }
        @Override public double maxZ() { return box.maxZ; }
    }
}
