package com.redstoneai.mixin;

import com.redstoneai.tick.TickInterceptor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents non-player entities from ticking when they are <b>fully</b> inside
 * a frozen workspace. "Fully inside" means the entity's entire bounding box
 * (AABB) is contained within the workspace bounds — partial overlap does not
 * trigger freezing.
 * <p>
 * This covers all entity types: items, falling blocks, minecarts, armor stands,
 * mobs, projectiles, experience orbs, etc. Players are never frozen.
 * <p>
 * During workspace stepping, {@link TickInterceptor#isEntityFrozen} returns false
 * for the workspace being stepped, so entities within it tick normally.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void redstoneai$freezeEntity(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (TickInterceptor.isEntityFrozen(self, entity)) {
            ci.cancel();
        }
    }
}
