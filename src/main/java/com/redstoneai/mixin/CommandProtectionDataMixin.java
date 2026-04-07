package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceAccessControl;
import net.minecraft.server.commands.data.BlockDataAccessor;
import net.minecraft.server.commands.data.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevent vanilla /data commands from mutating protected workspaces or frozen entities.
 */
@Mixin({BlockDataAccessor.class, EntityDataAccessor.class})
public abstract class CommandProtectionDataMixin {

    @Inject(method = "setData", at = @At("HEAD"), cancellable = true)
    private void redstoneai$blockProtectedDataMutation(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) {
        Object self = this;
        if (self instanceof BlockDataAccessor blockAccessor) {
            BlockEntity blockEntity = ((BlockDataAccessorMixin) blockAccessor).redstoneai$getEntity();
            if (blockEntity != null
                    && blockEntity.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && WorkspaceAccessControl.findWorkspaceForCommandMutation(serverLevel, ((BlockDataAccessorMixin) blockAccessor).redstoneai$getPos()) != null) {
                ci.cancel();
            }
            return;
        }

        if (self instanceof EntityDataAccessor entityAccessor) {
            Entity entity = ((EntityDataAccessorMixin) entityAccessor).redstoneai$getEntity();
            if (WorkspaceAccessControl.findFrozenWorkspaceForEntity(entity) != null) {
                ci.cancel();
            }
        }
    }
}
