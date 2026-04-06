package com.redstoneai.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.redstoneai.tick.TickInterceptor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps block entity tick execution within Level.tickBlockEntities().
 * <p>
 * Skips ticking block entities whose position falls inside a frozen workspace.
 * During step processing, TickController manually ticks workspace block entities,
 * so this mixin only suppresses the normal game-loop ticks.
 * <p>
 * Uses @WrapOperation (MixinExtras) for better mod compatibility.
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {

    @WrapOperation(
            method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V")
    )
    private void redstoneai$wrapBlockEntityTick(TickingBlockEntity ticker, Operation<Void> original) {
        if ((Object) this instanceof ServerLevel serverLevel
                && TickInterceptor.isFrozen(serverLevel, ticker.getPos())) {
            return;
        }
        original.call(ticker);
    }
}
