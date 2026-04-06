package com.redstoneai.recording;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * A labeled monitoring point within a workspace (input or output).
 * Used by the AI to define what to monitor during simulation and testing.
 */
public record IOMarker(BlockPos pos, IORole role, String label) {

    public enum IORole {
        INPUT("input"),
        OUTPUT("output"),
        MONITOR("monitor");

        private final String serializedName;

        IORole(String serializedName) {
            this.serializedName = serializedName;
        }

        public String getSerializedName() {
            return serializedName;
        }

        @Nullable
        public static IORole tryParse(String name) {
            for (IORole role : values()) {
                if (role.serializedName.equals(name)) {
                    return role;
                }
            }
            return null;
        }

        public static IORole fromString(String name) {
            IORole role = tryParse(name);
            if (role != null) {
                return role;
            }
            return MONITOR;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("role", role.getSerializedName());
        tag.putString("label", label);
        return tag;
    }

    public static IOMarker load(CompoundTag tag) {
        int[] p = tag.getIntArray("pos");
        return new IOMarker(
                new BlockPos(p[0], p[1], p[2]),
                IORole.fromString(tag.getString("role")),
                tag.getString("label")
        );
    }
}
