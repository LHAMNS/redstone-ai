package com.redstoneai.workspace;

import com.redstoneai.RedstoneAI;
import com.redstoneai.tick.TickController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * The physical block that players place to create an AI workspace.
 * Right-click opens the workspace configuration GUI.
 */
public class WorkspaceControllerBlock extends Block implements EntityBlock {

    public WorkspaceControllerBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorkspaceControllerBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WorkspaceControllerBlockEntity controller)) {
            return InteractionResult.PASS;
        }

        // Check access: ops or workspace owner can open the GUI
        if (!serverPlayer.hasPermissions(2)) {
            ServerLevel serverLevel = serverPlayer.serverLevel();
            String wsName = controller.getWorkspaceName();
            if (!wsName.isEmpty()) {
                Workspace ws = WorkspaceManager.get(serverLevel).getByName(wsName);
                if (ws == null || !ws.isControllerPos(pos)) {
                    serverPlayer.sendSystemMessage(Component.literal(
                                    "This controller is linked to a stale or missing workspace.")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    return InteractionResult.CONSUME;
                }
                if (!WorkspaceAccessControl.canPlayerOpenMenu(serverPlayer, ws)) {
                    serverPlayer.sendSystemMessage(Component.translatable(
                                    "message.redstone_ai.protection_blocked", wsName,
                                    ws.getProtectionMode().getSerializedName())
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    return InteractionResult.CONSUME;
                }
            }
        }

        NetworkHooks.openScreen(serverPlayer, new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal("Redstone Nexus");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player menuPlayer) {
                return new WorkspaceControllerMenu(containerId, playerInv, pos, controller);
            }
        }, (FriendlyByteBuf buf) -> {
            // Write all data for client-side menu construction
            new WorkspaceControllerMenu(0, serverPlayer.getInventory(), pos, controller).writeToBuffer(buf);
        });

        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level instanceof ServerLevel serverLevel) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof WorkspaceControllerBlockEntity controller) {
                    String wsName = controller.getWorkspaceName();
                    if (wsName != null && !wsName.isEmpty()) {
                        WorkspaceManager manager = WorkspaceManager.get(serverLevel);
                        Workspace ws = manager.getByName(wsName);
                        if (ws != null && ws.isControllerPos(pos)) {
                            if (ws.isFrozen()) {
                                TickController.discardFrozenState(serverLevel, ws);
                            }
                            TickController.removeQueue(ws.getId());
                            if (manager.removeWorkspace(wsName)) {
                                RedstoneAI.LOGGER.info("[RedstoneAI] Workspace '{}' removed (controller block broken)", wsName);
                            }
                        }
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
