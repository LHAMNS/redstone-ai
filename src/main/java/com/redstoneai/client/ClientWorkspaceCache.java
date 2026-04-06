package com.redstoneai.client;

import com.redstoneai.network.WorkspaceBoundarySyncPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Client-side cache of workspace boundary data synchronized from the server.
 * The renderer only needs the most recently received dimension snapshot, so the
 * cache keeps a single immutable view list instead of accumulating per-dimension
 * history over a long play session.
 */
public final class ClientWorkspaceCache {
    private static final Object LOCK = new Object();
    private static ResourceLocation syncedDimension;
    private static List<WorkspaceBoundarySyncPacket.WorkspaceBoundaryView> syncedViews = List.of();

    private ClientWorkspaceCache() {}

    public static void update(ResourceLocation dimensionId, List<WorkspaceBoundarySyncPacket.WorkspaceBoundaryView> views) {
        synchronized (LOCK) {
            syncedDimension = dimensionId;
            syncedViews = List.copyOf(views);
        }
    }

    public static List<WorkspaceBoundarySyncPacket.WorkspaceBoundaryView> get(ResourceLocation dimensionId) {
        synchronized (LOCK) {
            if (syncedDimension != null && syncedDimension.equals(dimensionId)) {
                return syncedViews;
            }
            return List.of();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            syncedDimension = null;
            syncedViews = List.of();
        }
    }
}
