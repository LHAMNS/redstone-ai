package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceAccessControl;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(KillCommand.class)
public abstract class CommandProtectionKillMixin {
    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private static void redstoneai$blockKill(CommandSourceStack source, Collection<? extends Entity> entities, CallbackInfoReturnable<Integer> cir) {
        for (Entity entity : entities) {
            if (WorkspaceAccessControl.isFrozenEntityDamageBlocked(entity)) {
                cir.setReturnValue(0);
                return;
            }
        }
    }
}
