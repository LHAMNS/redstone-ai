package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceAccessControl;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(TeleportCommand.class)
public abstract class CommandProtectionTeleportMixin {
    @Inject(method = "performTeleport", at = @At("HEAD"), cancellable = true)
    private static void redstoneai$blockTeleport(CommandSourceStack source,
                                                 Entity entity,
                                                 ServerLevel level,
                                                 double x, double y, double z,
                                                 Set<RelativeMovement> relativeMovements,
                                                 float yRot, float xRot,
                                                 Object lookAt,
                                                 CallbackInfo ci) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (WorkspaceAccessControl.isFrozenEntityTeleportBlocked(entity)) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    net.minecraft.network.chat.Component.literal("[RedstoneAI] Teleport is blocked for frozen protected entities.")
            ).create();
        }
    }
}
