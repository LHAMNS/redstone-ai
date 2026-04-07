package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceCommandProtection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(SetBlockCommand.class)
public abstract class CommandProtectionSetBlockMixin {
    @Inject(method = "setBlock", at = @At("HEAD"))
    private static void redstoneai$blockSetBlock(CommandSourceStack source,
                                                 BlockPos pos,
                                                 BlockInput input,
                                                 SetBlockCommand.Mode mode,
                                                 Predicate<BlockInWorld> predicate,
                                                 CallbackInfoReturnable<Integer> cir) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorkspaceCommandProtection.checkBlockMutation(source, pos);
    }
}
