package com.redstoneai.client;

import com.redstoneai.RedstoneAI;
import com.redstoneai.registry.RAIMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-side entry point. Registers renderers, screens, and client events.
 */
@Mod.EventBusSubscriber(modid = RedstoneAI.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RedstoneAIClient {

    private RedstoneAIClient() {}

    public static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(RedstoneAIClient::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(WorkspaceBoundaryRenderer::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(RedstoneAIClient::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(RedstoneAIClient::onClientLogout);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                MenuScreens.register(RAIMenus.WORKSPACE_CONTROLLER.get(), WorkspaceScreen::new));
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWorkspaceCache.clear();
    }

    private static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientWorkspaceCache.clear();
    }
}
