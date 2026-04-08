package com.redstoneai.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.redstoneai.workspace.WorkspaceCommandProtection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.data.BlockDataAccessor;
import net.minecraft.server.commands.data.DataAccessor;
import net.minecraft.server.commands.data.DataCommands;
import net.minecraft.server.commands.data.EntityDataAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DataCommands.class)
public abstract class CommandProtectionDataCommandsMixin {

    @WrapOperation(
            method = "mergeData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/commands/data/DataAccessor;setData(Lnet/minecraft/nbt/CompoundTag;)V"
            )
    )
    private static void redstoneai$guardMergeData(
            DataAccessor accessor,
            CompoundTag tag,
            Operation<Void> original,
            CommandSourceStack source
    ) throws CommandSyntaxException {
        redstoneai$checkAccessorMutation(source, accessor);
        original.call(accessor, tag);
    }

    @WrapOperation(
            method = "removeData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/commands/data/DataAccessor;setData(Lnet/minecraft/nbt/CompoundTag;)V"
            )
    )
    private static void redstoneai$guardRemoveData(
            DataAccessor accessor,
            CompoundTag tag,
            Operation<Void> original,
            CommandSourceStack source
    ) throws CommandSyntaxException {
        redstoneai$checkAccessorMutation(source, accessor);
        original.call(accessor, tag);
    }

    @WrapOperation(
            method = "manipulateData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/commands/data/DataAccessor;setData(Lnet/minecraft/nbt/CompoundTag;)V"
            )
    )
    private static void redstoneai$guardManipulateData(
            DataAccessor accessor,
            CompoundTag tag,
            Operation<Void> original,
            CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
        redstoneai$checkAccessorMutation(context.getSource(), accessor);
        original.call(accessor, tag);
    }

    private static void redstoneai$checkAccessorMutation(CommandSourceStack source, DataAccessor accessor) throws CommandSyntaxException {
        if (accessor instanceof BlockDataAccessor blockAccessor) {
            WorkspaceCommandProtection.checkBlockMutation(source, ((BlockDataAccessorMixin) blockAccessor).redstoneai$getPos());
            return;
        }
        if (accessor instanceof EntityDataAccessor entityAccessor) {
            WorkspaceCommandProtection.checkEntityMutation(((EntityDataAccessorMixin) entityAccessor).redstoneai$getEntity());
        }
    }
}
