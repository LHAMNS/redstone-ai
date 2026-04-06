package com.redstoneai.mcr;

import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A single parsed MCR block with its position offset and properties.
 */
public record MCRBlock(
        String blockType,
        int x, int y, int z,
        @Nullable Direction facing,
        int delay,
        @Nullable String mode,
        Map<String, String> extraProperties
) {
    public MCRBlock(String blockType, int x, int y, int z) {
        this(blockType, x, y, z, null, 0, null, Map.of());
    }
}
