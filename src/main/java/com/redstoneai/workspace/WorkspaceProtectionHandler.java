package com.redstoneai.workspace;

import com.redstoneai.RedstoneAI;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Enforces workspace protection mode by cancelling block placement, breaking,
 * and explosion damage within workspace bounds when the protection mode
 * does not permit the action.
 * <p>
 * Protection rules:
 * <ul>
 *   <li>LOCKED - no modifications by either player or AI</li>
 *   <li>AI_ONLY - only WebSocket/command-driven changes; player block events cancelled</li>
 *   <li>PLAYER_ONLY - player can modify; AI changes (via commands) are checked elsewhere</li>
 *   <li>COLLABORATIVE - both can modify</li>
 * </ul>
 * <p>
 * "AI changes" made via commands/WebSocket bypass these Forge events entirely
 * (they use {@code level.setBlock} directly), so this handler only needs to
 * guard against player-initiated events.
 */
@Mod.EventBusSubscriber(modid = RedstoneAI.ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorkspaceProtectionHandler {
    private WorkspaceProtectionHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = event.getPos();
        WorkspaceManager manager = WorkspaceManager.get(level);
        Workspace ws = manager.getWorkspaceAt(pos);
        if (ws == null) return;

        if (!WorkspaceAccessControl.canPlayerModify(player, ws)) {
            event.setCanceled(true);
            player.sendSystemMessage(
                    Component.literal("[RedstoneAI] Cannot place blocks - workspace '" + ws.getName() +
                            "' mode: " + ws.getProtectionMode().getSerializedName())
                            .withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getPlayer();
        BlockPos pos = event.getPos();
        WorkspaceManager manager = WorkspaceManager.get(level);
        Workspace controllerWorkspace = manager.getByControllerPos(pos);
        if (controllerWorkspace != null) {
            if (!(player instanceof ServerPlayer sp) || !WorkspaceRules.canPlayerManage(sp, controllerWorkspace)) {
                event.setCanceled(true);
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(
                            Component.literal("[RedstoneAI] Only the owner or an operator can break a workspace controller.")
                                    .withStyle(ChatFormatting.RED));
                }
                return;
            }
            return;
        }

        Workspace ws = manager.getWorkspaceAt(pos);
        if (ws == null) return;

        if (pos.equals(ws.getControllerPos())) {
            if (!(player instanceof ServerPlayer sp) || !WorkspaceRules.canPlayerManage(sp, ws)) {
                event.setCanceled(true);
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(
                            Component.literal("[RedstoneAI] Only the owner or an operator can break a workspace controller.")
                                    .withStyle(ChatFormatting.RED));
                }
                return;
            }
        }

        if (!(player instanceof ServerPlayer sp) || !WorkspaceAccessControl.canPlayerModify(sp, ws)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(
                        Component.literal("[RedstoneAI] Cannot break blocks - workspace '" + ws.getName() +
                                "' mode: " + ws.getProtectionMode().getSerializedName())
                                .withStyle(ChatFormatting.RED));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        WorkspaceManager manager = WorkspaceManager.get(level);

        // Collect all controller positions — these may be outside workspace bounds
        // (relocated during range selection) so getWorkspaceAt() won't find them.
        java.util.Set<net.minecraft.core.BlockPos> controllerPositions = new java.util.HashSet<>();
        for (Workspace ws : manager.getAllWorkspacesSnapshot()) {
            controllerPositions.add(ws.getControllerPos());
        }

        event.getAffectedBlocks().removeIf(pos -> {
            // Always protect controller blocks from explosions
            if (controllerPositions.contains(pos)) {
                return true;
            }
            // Protect non-modifiable workspace interiors
            Workspace ws = manager.getWorkspaceAt(pos);
            return ws != null && !ws.getProtectionMode().canPlayerModify();
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Workspace ws = WorkspaceManager.get(level).getWorkspaceAt(event.getPos());
        if (ws == null) return;
        if (!WorkspaceAccessControl.canPlayerInteract(player, ws)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("[RedstoneAI] Interaction blocked inside protected workspace '" + ws.getName() + "'.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Workspace ws = WorkspaceManager.get(level).getWorkspaceAt(event.getPos());
        if (ws == null) return;
        if (!WorkspaceAccessControl.canPlayerInteract(player, ws)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        Workspace ws = WorkspaceAccessControl.findFrozenWorkspaceForEntity(event.getTarget());
        if (ws == null) {
            ws = WorkspaceManager.get(level).getWorkspaceAt(event.getTarget().blockPosition());
        }
        if (ws == null) return;
        if (!WorkspaceAccessControl.canPlayerInteract(player, ws)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        Workspace ws = WorkspaceAccessControl.findFrozenWorkspaceForEntity(event.getTarget());
        if (ws == null) {
            ws = WorkspaceManager.get(level).getWorkspaceAt(event.getTarget().blockPosition());
        }
        if (ws == null) return;
        if (!WorkspaceAccessControl.canPlayerInteract(player, ws)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        Workspace ws = WorkspaceAccessControl.findFrozenWorkspaceForEntity(event.getTarget());
        if (ws == null) {
            ws = WorkspaceManager.get(level).getWorkspaceAt(event.getTarget().blockPosition());
        }
        if (ws == null) return;
        if (!WorkspaceAccessControl.canPlayerInteract(player, ws)) {
            event.setCanceled(true);
        }
    }
}
