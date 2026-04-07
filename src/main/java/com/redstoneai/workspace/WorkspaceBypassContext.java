package com.redstoneai.workspace;

/**
 * Narrow thread-local bypasses for trusted internal maintenance operations.
 */
public final class WorkspaceBypassContext {
    private static final ThreadLocal<Integer> ENTITY_REMOVAL_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private WorkspaceBypassContext() {}

    public static void runWithEntityRemovalBypass(Runnable runnable) {
        ENTITY_REMOVAL_BYPASS_DEPTH.set(ENTITY_REMOVAL_BYPASS_DEPTH.get() + 1);
        try {
            runnable.run();
        } finally {
            ENTITY_REMOVAL_BYPASS_DEPTH.set(Math.max(0, ENTITY_REMOVAL_BYPASS_DEPTH.get() - 1));
        }
    }

    public static boolean isEntityRemovalBypassed() {
        return ENTITY_REMOVAL_BYPASS_DEPTH.get() > 0;
    }
}
