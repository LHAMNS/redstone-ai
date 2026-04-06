package com.redstoneai.mixin;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures server-side block events before they enter the vanilla queue.
 * <p>
 * Frozen workspaces own their block-event timing in {@link FrozenTickQueue},
 * so new block events are redirected at enqueue time and pre-existing vanilla
 * events are migrated during freeze().
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockEventMixin {

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void redstoneai$queueBlockEvent(BlockPos pos, net.minecraft.world.level.block.Block block,
                                            int paramA, int paramB, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Workspace ws = TickInterceptor.getWorkspaceForDeferredScheduling(self, pos);
        if (ws != null) {
            FrozenTickQueue queue = TickController.getQueue(ws);
            if (queue != null) {
                queue.addBlockEvent(new BlockEventData(pos, block, paramA, paramB), 0);
            }
            ci.cancel();
        }
    }
}
