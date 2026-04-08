package com.redstoneai.workspace;

import javax.annotation.Nullable;

/**
 * Fine-grained player permissions inside a workspace.
 */
public enum WorkspacePermission {
    BUILD("build"),
    TIME_CONTROL("time"),
    VIEW_HISTORY("history"),
    CHAT("chat"),
    MANAGE_SETTINGS("settings"),
    REVERT("revert");

    private final String serializedName;

    WorkspacePermission(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return serializedName;
    }

    @Nullable
    public static WorkspacePermission tryParse(String name) {
        for (WorkspacePermission permission : values()) {
            if (permission.serializedName.equals(name)) {
                return permission;
            }
        }
        return null;
    }
}
