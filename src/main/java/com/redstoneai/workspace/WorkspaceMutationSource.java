package com.redstoneai.workspace;

import javax.annotation.Nullable;

/**
 * Tracks the most recent mutation source that materially changed workspace state.
 */
public enum WorkspaceMutationSource {
    NONE("none"),
    FREEZE("freeze"),
    STEP("step"),
    REWIND("rewind"),
    FAST_FORWARD("fast_forward"),
    SETTLE("settle"),
    REVERT("revert"),
    BUILD("build"),
    IO_DRIVE("io_drive"),
    ENTITY_MUTATION("entity_mutation"),
    BLOCK_ENTITY_MUTATION("block_entity_mutation"),
    CONFIG("config"),
    SYSTEM("system");

    private final String serializedName;

    WorkspaceMutationSource(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return serializedName;
    }

    @Nullable
    public static WorkspaceMutationSource tryParse(String name) {
        for (WorkspaceMutationSource source : values()) {
            if (source.serializedName.equals(name)) {
                return source;
            }
        }
        return null;
    }
}
