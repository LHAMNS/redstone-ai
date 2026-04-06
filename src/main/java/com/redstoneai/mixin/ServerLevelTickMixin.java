package com.redstoneai.mixin;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts scheduled block and fluid tick processing in ServerLevel.
 * <p>
 * When a tick fires for a position inside a frozen workspace, the tick is
 * captured in the workspace's {@link FrozenTickQueue} and the original
 * handler is cancelled. The tick has already been consumed from LevelTicks
 * at this point, so we must store it for later replay during step().
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickMixin {

    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void redstoneai$onTickBlock(BlockPos pos, Block block, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Workspace ws = TickInterceptor.getWorkspaceIfFrozen(self, pos);
        if (ws != null) {
            FrozenTickQueue queue = TickController.getQueue(ws);
            if (queue != null) {
                queue.addBlockTick(pos, block, 0, TickPriority.NORMAL);
            }
            ci.cancel();
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void redstoneai$onTickFluid(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Workspace ws = TickInterceptor.getWorkspaceIfFrozen(self, pos);
        if (ws != null) {
            FrozenTickQueue queue = TickController.getQueue(ws);
            if (queue != null) {
                queue.addFluidTick(pos, fluid, 0, TickPriority.NORMAL);
            }
            ci.cancel();
        }
    }
}
