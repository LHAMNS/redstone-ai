package com.redstoneai.tick;

import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * Static query utility for mixin callbacks. Determines whether a block position
 * falls inside a frozen workspace, accounting for the "stepping" override.
 * <p>
 * All methods run on the server thread only — no synchronization required.
 */
public final class TickInterceptor {
    /** The workspace currently being stepped, or null. */
    private static Workspace steppingWorkspace;

    private TickInterceptor() {}

    /**
     * Returns true if {@code pos} is inside a frozen workspace.
     * During stepping, the stepped workspace is treated as unfrozen.
     */
    public static boolean isFrozen(ServerLevel level, BlockPos pos) {
        Workspace stepping = steppingWorkspace;
        if (stepping != null && stepping.contains(pos)) {
            return false;
        }
        Workspace ws = WorkspaceManager.get(level).getWorkspaceAt(pos);
        return ws != null && ws.isFrozen();
    }

    /**
     * Returns the frozen workspace containing {@code pos}, or null if the position
     * is not in any frozen workspace (or is in the currently stepping workspace).
     */
    @Nullable
    public static Workspace getWorkspaceIfFrozen(ServerLevel level, BlockPos pos) {
        Workspace stepping = steppingWorkspace;
        if (stepping != null && stepping.contains(pos)) {
            return null;
        }
        Workspace ws = WorkspaceManager.get(level).getWorkspaceAt(pos);
        return (ws != null && ws.isFrozen()) ? ws : null;
    }

    /**
     * Returns the workspace that should capture deferred scheduling. Unlike
     * {@link #getWorkspaceIfFrozen(ServerLevel, BlockPos)}, the workspace
     * currently being stepped is still returned so newly scheduled ticks/events
     * stay in virtual time instead of leaking back into real server time.
     */
    @Nullable
    public static Workspace getWorkspaceForDeferredScheduling(ServerLevel level, BlockPos pos) {
        Workspace stepping = steppingWorkspace;
        if (stepping != null && stepping.contains(pos)) {
            return stepping;
        }
        Workspace ws = WorkspaceManager.get(level).getWorkspaceAt(pos);
        return (ws != null && ws.isFrozen()) ? ws : null;
    }

    /**
     * Check if a non-player entity should be frozen.
     * Returns true only if the entity is NOT a player AND its entire bounding box
     * is fully contained within a frozen workspace.
     */
    public static boolean isEntityFrozen(ServerLevel level, Entity entity) {
        if (entity instanceof Player) return false;

        Workspace stepping = steppingWorkspace;
        if (stepping != null && stepping.containsEntityFully(entity)) {
            return false;
        }

        Workspace candidate = WorkspaceManager.get(level).getWorkspaceAt(entity.blockPosition());
        return candidate != null
                && candidate.isFrozen()
                && candidate.containsEntityFully(entity)
                && candidate.getEntityFilterMode().shouldAffect(entity);
    }

    public static boolean isStepping(Workspace ws) {
        return ws == steppingWorkspace;
    }

    static void beginStep(Workspace ws) {
        steppingWorkspace = ws;
    }

    static void endStep() {
        steppingWorkspace = null;
    }
}
