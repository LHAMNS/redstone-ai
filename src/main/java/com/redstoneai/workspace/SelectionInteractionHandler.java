package com.redstoneai.workspace;

import com.redstoneai.RedstoneAI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Intercepts right-click-block events during selection mode to capture
 * the two ground corner positions for workspace range definition.
 * Only active when the player is in selection mode (tracked by SelectionManager).
 */
@Mod.EventBusSubscriber(modid = RedstoneAI.ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SelectionInteractionHandler {

    private SelectionInteractionHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!SelectionManager.isSelecting(player.getUUID())) return;

        boolean consumed = SelectionManager.onGroundClick(player, event.getPos());
        if (consumed) {
            event.setCanceled(true);
        }
    }
}
