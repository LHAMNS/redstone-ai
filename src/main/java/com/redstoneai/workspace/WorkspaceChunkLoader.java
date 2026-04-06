package com.redstoneai.workspace;

import com.redstoneai.RedstoneAI;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Forces workspace chunks to remain loaded so that tick control (freeze/step/rewind)
 * works even when no player is nearby. Uses Forge's chunk ticket system with the
 * workspace UUID as the ticket owner.
 * <p>
 * Tickets are added when a workspace is created or loaded from save data,
 * and removed when the workspace is deleted.
 * <p>
 * This class also handles the {@link ForgeChunkManager.LoadingValidationCallback}
 * to revalidate tickets after server restart.
 */
public final class WorkspaceChunkLoader {

    private WorkspaceChunkLoader() {}

    /**
     * Register the validation callback during mod construction.
     * Called once from {@link RedstoneAI}.
     */
    public static void registerValidationCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(RedstoneAI.ID,
                (level, ticketHelper) -> {
                    // Re-validate: only keep tickets for workspaces that still exist
                    WorkspaceManager manager = WorkspaceManager.get(level);
                    ticketHelper.getBlockTickets().forEach((pos, tickets) -> {
                        Workspace ws = manager.getWorkspaceAt(pos);
                        if (ws == null) {
                            // Workspace no longer exists — remove stale tickets
                            for (var ticket : tickets.getSecond()) {
                                ticketHelper.removeTicket(pos, ticket, false);
                            }
                            for (var ticket : tickets.getFirst()) {
                                ticketHelper.removeTicket(pos, ticket, true);
                            }
                        }
                    });
                });
    }

    /**
     * Force-load all chunks covered by a workspace's bounding box.
     */
    public static void forceLoadWorkspace(ServerLevel level, Workspace ws) {
        Set<ChunkPos> chunks = getWorkspaceChunks(ws);
        BlockPos controllerPos = ws.getControllerPos();
        for (ChunkPos chunk : chunks) {
            ForgeChunkManager.forceChunk(level, RedstoneAI.ID, controllerPos, chunk.x, chunk.z, true, true);
        }
        RedstoneAI.LOGGER.debug("[RedstoneAI] Force-loaded {} chunk(s) for workspace '{}'",
                chunks.size(), ws.getName());
    }

    /**
     * Release force-loaded chunks for a workspace.
     */
    public static void unloadWorkspace(ServerLevel level, Workspace ws) {
        Set<ChunkPos> chunks = getWorkspaceChunks(ws);
        BlockPos controllerPos = ws.getControllerPos();
        for (ChunkPos chunk : chunks) {
            ForgeChunkManager.forceChunk(level, RedstoneAI.ID, controllerPos, chunk.x, chunk.z, false, true);
        }
        RedstoneAI.LOGGER.debug("[RedstoneAI] Released {} chunk(s) for workspace '{}'",
                chunks.size(), ws.getName());
    }

    /**
     * Compute the set of chunk positions covered by a workspace's bounding box.
     */
    private static Set<ChunkPos> getWorkspaceChunks(Workspace ws) {
        BoundingBox bounds = ws.getBounds();
        Set<ChunkPos> chunks = new HashSet<>();
        int minCX = bounds.minX() >> 4;
        int maxCX = bounds.maxX() >> 4;
        int minCZ = bounds.minZ() >> 4;
        int maxCZ = bounds.maxZ() >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }
}
