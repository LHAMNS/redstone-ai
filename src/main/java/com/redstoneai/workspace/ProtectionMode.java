package com.redstoneai.workspace;

import javax.annotation.Nullable;

public enum ProtectionMode {
    LOCKED("locked", false, false),
    AI_ONLY("ai_only", true, false),
    PLAYER_ONLY("player_only", false, true),
    COLLABORATIVE("collaborative", true, true);

    private final String serializedName;
    private final boolean aiCanModify;
    private final boolean playerCanModify;

    ProtectionMode(String serializedName, boolean aiCanModify, boolean playerCanModify) {
        this.serializedName = serializedName;
        this.aiCanModify = aiCanModify;
        this.playerCanModify = playerCanModify;
    }

    public String getSerializedName() {
        return serializedName;
    }

    public boolean canAIModify() {
        return aiCanModify;
    }

    public boolean canPlayerModify() {
        return playerCanModify;
    }

    @Nullable
    public static ProtectionMode tryParse(String name) {
        for (ProtectionMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return null;
    }

    public static ProtectionMode fromString(String name) {
        ProtectionMode mode = tryParse(name);
        if (mode != null) {
            return mode;
        }
        return AI_ONLY;
    }
}
