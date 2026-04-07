package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceAccessControl;
import com.redstoneai.workspace.WorkspaceBypassContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Enforces frozen-entity absolute protection: no collision, no damage, no teleport.
 */
@Mixin(Entity.class)
public abstract class EntityProtectionMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockFrozenEntityDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.shouldBlockEntityDamage(self, source.getEntity())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockFrozenEntityKill(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.isFrozenEntityDamageBlocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockKilledRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceBypassContext.isEntityRemovalBypassed()) {
            return;
        }
        if ((reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED)
                && WorkspaceAccessControl.isFrozenEntityDamageBlocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "teleportToWithTicket", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockFrozenEntityTicketTeleport(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.isFrozenEntityTeleportBlocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockFrozenEntityLocalTeleport(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.isFrozenEntityTeleportBlocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockFrozenEntityCrossLevelTeleport(ServerLevel level, double x, double y, double z,
                                                                Set<RelativeMovement> relativeMovements, float yRot, float xRot,
                                                                CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.isFrozenEntityTeleportBlocked(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void redstoneai$disablePairCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.shouldBlockExternalEntityInteraction(self, other)
                || WorkspaceAccessControl.shouldBlockExternalEntityInteraction(other, self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void redstoneai$disableEntityPush(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.shouldBlockExternalEntityInteraction(self, other)
                || WorkspaceAccessControl.shouldBlockExternalEntityInteraction(other, self)) {
            ci.cancel();
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockWorkspaceBoundaryCrossing(MoverType moverType, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (WorkspaceAccessControl.wouldCrossWorkspaceBoundary(self, self.getBoundingBox().move(movement))) {
            ci.cancel();
        }
    }
}
