package com.redstoneai.mixin;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts neighbor update notifications targeting frozen workspace positions.
 * <p>
 * When a neighbor update targets a position inside a frozen workspace, it is
 * queued in the workspace's FrozenTickQueue for processing during the next step.
 * Updates targeting positions outside any frozen workspace pass through normally.
 * <p>
 * During stepping, isFrozen() returns false for the stepped workspace, so
 * cascading neighbor updates within the workspace execute immediately.
 */
@Mixin(Level.class)
public abstract class LevelNeighborUpdateMixin {

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void redstoneai$onNeighborChanged(BlockPos pos, Block sourceBlock, BlockPos fromPos,
                                              CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            Workspace ws = TickInterceptor.getWorkspaceIfFrozen(serverLevel, pos);
            if (ws != null) {
                FrozenTickQueue queue = TickController.getQueue(ws);
                if (queue != null) {
                    queue.addNeighborUpdate(pos, this.getBlockState(pos), sourceBlock, fromPos, false);
                }
                ci.cancel();
            }
        }
    }
}
