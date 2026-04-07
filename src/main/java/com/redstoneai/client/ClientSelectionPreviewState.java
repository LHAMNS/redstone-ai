package com.redstoneai.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Client-side preview state for manual workspace range selection.
 */
public final class ClientSelectionPreviewState {
    private static final Object LOCK = new Object();
    @Nullable
    private static ResourceLocation dimensionId;
    @Nullable
    private static BlockPos controllerPos;
    @Nullable
    private static BlockPos firstCorner;
    private static int sizeY;
    private static boolean active;

    private ClientSelectionPreviewState() {}

    public static void update(ResourceLocation dimensionId, BlockPos controllerPos, @Nullable BlockPos firstCorner, int sizeY, boolean active) {
        synchronized (LOCK) {
            ClientSelectionPreviewState.dimensionId = dimensionId;
            ClientSelectionPreviewState.controllerPos = controllerPos.immutable();
            ClientSelectionPreviewState.firstCorner = firstCorner != null ? firstCorner.immutable() : null;
            ClientSelectionPreviewState.sizeY = sizeY;
            ClientSelectionPreviewState.active = active;
        }
    }

    public static Snapshot get() {
        synchronized (LOCK) {
            return new Snapshot(dimensionId, controllerPos, firstCorner, sizeY, active);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            dimensionId = null;
            controllerPos = null;
            firstCorner = null;
            sizeY = 0;
            active = false;
        }
    }

    public record Snapshot(
            @Nullable ResourceLocation dimensionId,
            @Nullable BlockPos controllerPos,
            @Nullable BlockPos firstCorner,
            int sizeY,
            boolean active
    ) {}
}
