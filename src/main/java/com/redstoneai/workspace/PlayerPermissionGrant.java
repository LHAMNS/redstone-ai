package com.redstoneai.workspace;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.EnumSet;

/**
 * Persistent permission grant for one player name within a workspace.
 */
public final class PlayerPermissionGrant {
    private final String playerName;
    private final EnumSet<WorkspacePermission> permissions;

    public PlayerPermissionGrant(String playerName, EnumSet<WorkspacePermission> permissions) {
        this.playerName = WorkspaceAccessControl.normalizePlayerName(playerName);
        this.permissions = permissions.isEmpty()
                ? EnumSet.noneOf(WorkspacePermission.class)
                : EnumSet.copyOf(permissions);
    }

    public String playerName() {
        return playerName;
    }

    public EnumSet<WorkspacePermission> permissions() {
        return permissions.isEmpty()
                ? EnumSet.noneOf(WorkspacePermission.class)
                : EnumSet.copyOf(permissions);
    }

    public boolean has(WorkspacePermission permission) {
        return permissions.contains(permission);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", playerName);
        ListTag list = new ListTag();
        for (WorkspacePermission permission : permissions) {
            CompoundTag permissionTag = new CompoundTag();
            permissionTag.putString("id", permission.getSerializedName());
            list.add(permissionTag);
        }
        tag.put("permissions", list);
        return tag;
    }

    public static PlayerPermissionGrant load(CompoundTag tag) {
        EnumSet<WorkspacePermission> permissions = EnumSet.noneOf(WorkspacePermission.class);
        ListTag list = tag.getList("permissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            WorkspacePermission permission = WorkspacePermission.tryParse(list.getCompound(i).getString("id"));
            if (permission != null) {
                permissions.add(permission);
            }
        }
        return new PlayerPermissionGrant(tag.getString("name"), permissions);
    }
}
