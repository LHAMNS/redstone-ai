package com.redstoneai.tick;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Associates LevelTicks instances with their owning ServerLevel so schedule-time
 * mixins can recover world context.
 */
public final class LevelTickSourceRegistry {
    private static final Map<Object, TickSource> SOURCES = new IdentityHashMap<>();

    private LevelTickSourceRegistry() {}

    public static void register(ServerLevel level, Object blockTicks, Object fluidTicks) {
        SOURCES.put(blockTicks, new TickSource(level, true));
        SOURCES.put(fluidTicks, new TickSource(level, false));
    }

    public static void unregister(Object blockTicks, Object fluidTicks) {
        SOURCES.remove(blockTicks);
        SOURCES.remove(fluidTicks);
    }

    @Nullable
    public static TickSource get(Object source) {
        return SOURCES.get(source);
    }

    public record TickSource(ServerLevel level, boolean blockTicks) {}
}
