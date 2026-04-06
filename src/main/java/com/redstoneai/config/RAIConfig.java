package com.redstoneai.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

public class RAIConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final RAIServerConfig SERVER;

    static {
        Pair<RAIServerConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .configure(RAIServerConfig::new);
        SERVER = pair.getLeft();
        SERVER_SPEC = pair.getRight();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    public static class RAIServerConfig {
        public final ForgeConfigSpec.IntValue webSocketPort;
        public final ForgeConfigSpec.IntValue maxWorkspaceSize;
        public final ForgeConfigSpec.IntValue maxWorkspacesPerPlayer;
        public final ForgeConfigSpec.IntValue maxRecordingTicks;
        public final ForgeConfigSpec.IntValue maxStepsPerCall;

        public final ForgeConfigSpec.ConfigValue<String> webSocketBindAddress;

        RAIServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("websocket");
            webSocketPort = builder
                    .comment("Port for the WebSocket server that Python MCP clients connect to")
                    .defineInRange("port", 4711, 1024, 65535);
            webSocketBindAddress = builder
                    .comment("Bind address for the WebSocket server. Default 127.0.0.1 (local-only). WSL users may need 0.0.0.0 but this exposes the port to the network — use with caution.")
                    .define("bindAddress", "127.0.0.1");
            builder.pop();

            builder.push("workspace");
            maxWorkspaceSize = builder
                    .comment("Maximum dimension size for workspace (applies to X, Y, and Z)")
                    .defineInRange("maxSize", 32, 4, 64);
            maxWorkspacesPerPlayer = builder
                    .comment("Maximum number of workspaces a single player can create")
                    .defineInRange("maxPerPlayer", 5, 1, 20);
            builder.pop();

            builder.push("recording");
            maxRecordingTicks = builder
                    .comment("Maximum number of ticks a recording can store")
                    .defineInRange("maxTicks", 10000, 100, 100000);
            maxStepsPerCall = builder
                    .comment("Maximum tick steps allowed per single step/fast_forward call")
                    .defineInRange("maxStepsPerCall", 200, 1, 10000);
            builder.pop();
        }
    }
}
