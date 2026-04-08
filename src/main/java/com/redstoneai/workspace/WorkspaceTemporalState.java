package com.redstoneai.workspace;

import javax.annotation.Nullable;

/**
 * Explicit temporal lifecycle state for a workspace.
 */
public enum WorkspaceTemporalState {
    LIVE("live"),
    FROZEN_AT_HEAD("frozen_at_head"),
    FROZEN_REWOUND("frozen_rewound"),
    FROZEN_DIRTY("frozen_dirty"),
    FROZEN_CORRUPTED("frozen_corrupted");

    private final String serializedName;

    WorkspaceTemporalState(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return serializedName;
    }

    public boolean isFrozen() {
        return this != LIVE;
    }

    public boolean canStep() {
        return this == FROZEN_AT_HEAD || this == FROZEN_DIRTY;
    }

    public boolean canRewind() {
        return isFrozen() && this != FROZEN_CORRUPTED;
    }

    public boolean canFastForward() {
        return this == FROZEN_REWOUND;
    }

    public boolean canSettle() {
        return this == FROZEN_AT_HEAD || this == FROZEN_DIRTY;
    }

    public boolean canBuild() {
        return this != FROZEN_CORRUPTED;
    }

    public boolean canUnfreeze() {
        return isFrozen() && this != FROZEN_CORRUPTED;
    }

    public static WorkspaceTemporalState fromLegacyFrozen(boolean frozen) {
        return frozen ? FROZEN_AT_HEAD : LIVE;
    }

    @Nullable
    public static WorkspaceTemporalState tryParse(String name) {
        for (WorkspaceTemporalState state : values()) {
            if (state.serializedName.equals(name)) {
                return state;
            }
        }
        return null;
    }
}
