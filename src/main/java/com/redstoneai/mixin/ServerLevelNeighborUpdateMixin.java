package com.redstoneai.mixin;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures server-side neighbor updates that bypass Level's default implementation.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelNeighborUpdateMixin {

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void redstoneai$onNeighborChanged(BlockPos pos, Block sourceBlock, BlockPos fromPos, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Workspace ws = TickInterceptor.getWorkspaceIfFrozen(self, pos);
        if (ws != null) {
            FrozenTickQueue queue = TickController.getQueue(ws);
            if (queue != null) {
                queue.addNeighborUpdate(pos, self.getBlockState(pos), sourceBlock, fromPos, false);
            }
            ci.cancel();
        }
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void redstoneai$onNeighborChangedState(BlockState state, BlockPos pos, Block sourceBlock,
                                                   BlockPos fromPos, boolean movedByPiston, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Workspace ws = TickInterceptor.getWorkspaceIfFrozen(self, pos);
        if (ws != null) {
            FrozenTickQueue queue = TickController.getQueue(ws);
            if (queue != null) {
                queue.addNeighborUpdate(pos, state, sourceBlock, fromPos, movedByPiston);
            }
            ci.cancel();
        }
    }
}
