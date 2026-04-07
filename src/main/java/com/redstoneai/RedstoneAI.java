package com.redstoneai;

import com.redstoneai.client.RedstoneAIClient;
import com.redstoneai.command.RAICommands;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.mixin.ServerLevelTickAccessAccessor;
import com.redstoneai.network.WorkspaceBoundarySyncPacket;
import com.redstoneai.network.websocket.WebSocketServer;
import com.redstoneai.registry.RAIBlockEntities;
import com.redstoneai.registry.RAIBlocks;
import com.redstoneai.registry.RAICreativeTabs;
import com.redstoneai.registry.RAIItems;
import com.redstoneai.network.RAINetwork;
import com.redstoneai.registry.RAIMenus;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.LevelTickSourceRegistry;
import com.redstoneai.workspace.WorkspaceChunkLoader;
import com.redstoneai.workspace.WorkspaceManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RedstoneAI.ID)
public class RedstoneAI {
    public static final String ID = "redstone_ai";
    public static final String NAME = "RedstoneAI";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

    public RedstoneAI() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        RAIConfig.register();
        RAIBlocks.register(modBus);
        RAIItems.register(modBus);
        RAIBlockEntities.register(modBus);
        RAICreativeTabs.register(modBus);
        RAIMenus.register(modBus);
        RAINetwork.register();

        forgeBus.addListener(this::onServerStarted);
        forgeBus.addListener(this::onServerStopping);
        forgeBus.addListener(this::onLevelLoad);
        forgeBus.addListener(this::onLevelUnload);
        forgeBus.addListener(this::onRegisterCommands);
        forgeBus.addListener(this::onPlayerLogin);
        forgeBus.addListener(this::onPlayerChangedDimension);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> RedstoneAIClient::init);

        WorkspaceChunkLoader.registerValidationCallback();
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (ForgeGameTestHooks.isGametestServer()) {
            LOGGER.info("[RedstoneAI] Skipping WebSocket server startup in GameTest mode");
            return;
        }
        int port = RAIConfig.SERVER.webSocketPort.get();
        String bindHost = RAIConfig.SERVER.webSocketBindAddress.get();
        WebSocketServer.start(event.getServer(), port, bindHost);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        WebSocketServer.stop();
        TickController.clearAllQueues();
        LOGGER.info("[RedstoneAI] Server stopping. Cleaning up workspaces.");
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            WorkspaceManager manager = WorkspaceManager.get(serverLevel);
            ServerLevelTickAccessAccessor accessor = (ServerLevelTickAccessAccessor) serverLevel;
            LevelTickSourceRegistry.register(serverLevel, accessor.redstoneai$getBlockTicks(), accessor.redstoneai$getFluidTicks());
            for (var workspace : manager.getAllWorkspacesSnapshot()) {
                if (workspace.isFrozen()) {
                    TickController.initializeLoadedFrozenWorkspace(serverLevel, workspace);
                }
            }
            LOGGER.debug("[RedstoneAI] WorkspaceManager loaded for {}", serverLevel.dimension().location());
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            for (var workspace : WorkspaceManager.get(serverLevel).getAllWorkspacesSnapshot()) {
                TickController.removeQueue(workspace.getId());
            }
            ServerLevelTickAccessAccessor accessor = (ServerLevelTickAccessAccessor) serverLevel;
            LevelTickSourceRegistry.unregister(accessor.redstoneai$getBlockTicks(), accessor.redstoneai$getFluidTicks());
            WorkspaceManager.onLevelUnload(serverLevel);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RAICommands.register(event.getDispatcher());
        LOGGER.info("[RedstoneAI] Commands registered");
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            WorkspaceBoundarySyncPacket.syncTo(player);
        }
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            WorkspaceBoundarySyncPacket.syncTo(player);
        }
    }

    public static ResourceLocation asResource(String path) {
        return new ResourceLocation(ID, path);
    }
}
