package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceAccessControl;
import com.redstoneai.workspace.WorkspaceCommandProtection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
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
                                                 @Coerce Object lookAt,
                                                 CallbackInfo ci) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorkspaceCommandProtection.checkEntityMutation(entity);
        var targetBox = entity.getDimensions(entity.getPose()).makeBoundingBox(x, y, z);
        if (WorkspaceAccessControl.findWorkspaceForCommandMutation(level, new WorkspaceAccessControl.AABBLike() {
            @Override public double minX() { return targetBox.minX; }
            @Override public double minY() { return targetBox.minY; }
            @Override public double minZ() { return targetBox.minZ; }
            @Override public double maxX() { return targetBox.maxX; }
            @Override public double maxY() { return targetBox.maxY; }
            @Override public double maxZ() { return targetBox.maxZ; }
        }) != null) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    net.minecraft.network.chat.Component.literal("[RedstoneAI] Vanilla commands are blocked for protected workspaces.")
            ).create();
        }
    }
}
