package com.redstoneai.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.redstoneai.workspace.WorkspaceManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SummonCommand.class)
public abstract class CommandProtectionSummonMixin {
    @Inject(method = "createEntity", at = @At("HEAD"))
    private static void redstoneai$blockSummonIntoProtectedWorkspace(CommandSourceStack source,
                                                                     Holder.Reference<EntityType<?>> entityType,
                                                                     Vec3 position,
                                                                     CompoundTag tag,
                                                                     boolean finalizeMobSpawn,
                                                                     CallbackInfoReturnable<Entity> cir) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        AABB summonBox = entityType.value().getDimensions().makeBoundingBox(position);
        WorkspaceManager manager = WorkspaceManager.get(level);
        for (var workspace : manager.getAllWorkspacesSnapshot()) {
            if (workspace.isAllowVanillaCommands()) {
                continue;
            }
            boolean overlaps = summonBox.maxX > workspace.getBounds().minX()
                    && summonBox.minX < workspace.getBounds().maxX() + 1.0
                    && summonBox.maxY > workspace.getBounds().minY()
                    && summonBox.minY < workspace.getBounds().maxY() + 1.0
                    && summonBox.maxZ > workspace.getBounds().minZ()
                    && summonBox.minZ < workspace.getBounds().maxZ() + 1.0;
            if (overlaps) {
                throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                        net.minecraft.network.chat.Component.literal("[RedstoneAI] Vanilla commands are blocked for protected workspaces.")
                ).create();
            }
            if (workspace.getBounds().isInside(BlockPos.containing(position)) || workspace.getControllerPos().equals(BlockPos.containing(position))) {
                throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                        net.minecraft.network.chat.Component.literal("[RedstoneAI] Vanilla commands are blocked for protected workspaces.")
                ).create();
            }
        }
    }
}
