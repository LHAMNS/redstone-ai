package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceCommandProtection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(FillCommand.class)
public abstract class CommandProtectionFillMixin {
    @Inject(method = "fillBlocks", at = @At("HEAD"))
    private static void redstoneai$blockFill(CommandSourceStack source,
                                             BoundingBox bounds,
                                             BlockInput input,
                                             @Coerce Object mode,
                                             Predicate<BlockInWorld> predicate,
                                             CallbackInfoReturnable<Integer> cir) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorkspaceCommandProtection.checkBlockMutation(source, bounds);
    }
}
