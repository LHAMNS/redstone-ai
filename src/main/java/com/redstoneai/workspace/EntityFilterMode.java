package com.redstoneai.workspace;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;

/**
 * Controls which non-player entities are subject to workspace freeze/step/rewind.
 * <p>
 * Default is {@code ALL_NON_PLAYER}: everything except players is affected, which
 * ensures that redstone contraptions using minecarts, items, falling blocks, etc.
 * behave correctly under tick control.
 */
public enum EntityFilterMode {
    /** All entities except players — the safe default for redstone testing. */
    ALL_NON_PLAYER("all_non_player") {
        @Override
        public boolean shouldAffect(Entity entity) {
            return !(entity instanceof Player);
        }
    },

    /** Only mechanical/redstone-relevant entities: minecarts, items, falling blocks. */
    MECHANICAL_ONLY("mechanical_only") {
        @Override
        public boolean shouldAffect(Entity entity) {
            return entity instanceof AbstractMinecart
                    || entity instanceof ItemEntity
                    || entity instanceof FallingBlockEntity;
        }
    },

    /** No entities are affected — only blocks are frozen/stepped. */
    NONE("none") {
        @Override
        public boolean shouldAffect(Entity entity) {
            return false;
        }
    };

    private final String serializedName;

    EntityFilterMode(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return serializedName;
    }

    /** Returns true if this entity should be frozen/stepped/recorded by the workspace. */
    public abstract boolean shouldAffect(Entity entity);

    public static EntityFilterMode fromString(String name) {
        for (EntityFilterMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return ALL_NON_PLAYER;
    }
}
