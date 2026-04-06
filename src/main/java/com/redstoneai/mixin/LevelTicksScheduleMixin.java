package com.redstoneai.mixin;

import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.LevelTickSourceRegistry;
import com.redstoneai.tick.TickController;
import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures scheduled ticks at enqueue time so frozen workspaces advance on
 * virtual time instead of real server time.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksScheduleMixin<T> {

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void redstoneai$queueScheduledTick(ScheduledTick<T> scheduledTick, CallbackInfo ci) {
        LevelTickSourceRegistry.TickSource source = LevelTickSourceRegistry.get(this);
        if (source == null) {
            return;
        }

        Workspace ws = TickInterceptor.getWorkspaceForDeferredScheduling(source.level(), scheduledTick.pos());
        if (ws == null) {
            return;
        }

        FrozenTickQueue queue = TickController.getQueue(ws);
        if (queue == null) {
            return;
        }

        int delay = (int) Math.max(0L, scheduledTick.triggerTick() - source.level().getLevelData().getGameTime());
        if (source.blockTicks()) {
            queue.addBlockTick(scheduledTick.pos(), (Block) scheduledTick.type(), delay, scheduledTick.priority(), scheduledTick.subTickOrder());
        } else {
            queue.addFluidTick(scheduledTick.pos(), (Fluid) scheduledTick.type(), delay, scheduledTick.priority(), scheduledTick.subTickOrder());
        }
        ci.cancel();
    }
}
