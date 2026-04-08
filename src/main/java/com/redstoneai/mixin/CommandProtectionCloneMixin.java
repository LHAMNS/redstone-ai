package com.redstoneai.mixin;

import com.redstoneai.workspace.WorkspaceCommandProtection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.CloneCommands;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.function.Predicate;

@Mixin(CloneCommands.class)
public abstract class CommandProtectionCloneMixin {
    @Inject(method = "clone", at = @At("HEAD"))
    private static void redstoneai$blockClone(CommandSourceStack source,
                                              @Coerce Object begin,
                                              @Coerce Object end,
                                              @Coerce Object destination,
                                              Predicate<BlockInWorld> filter,
                                              @Coerce Object mode,
                                              CallbackInfoReturnable<Integer> cir) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.world.level.levelgen.structure.BoundingBox sourceBox = toBox(begin, end);
        net.minecraft.world.level.levelgen.structure.BoundingBox destinationBox = translateBox(begin, end, destination);
        if (sourceBox != null) {
            WorkspaceCommandProtection.checkBlockMutation(source, sourceBox);
        }
        if (destinationBox != null) {
            WorkspaceCommandProtection.checkBlockMutation(source, destinationBox);
        }
    }

    private static net.minecraft.world.level.levelgen.structure.BoundingBox toBox(Object start, Object end) {
        try {
            Method startPosition = start.getClass().getDeclaredMethod("position");
            Method endPosition = end.getClass().getDeclaredMethod("position");
            startPosition.setAccessible(true);
            endPosition.setAccessible(true);
            net.minecraft.core.BlockPos startPos = (net.minecraft.core.BlockPos) startPosition.invoke(start);
            net.minecraft.core.BlockPos endPos = (net.minecraft.core.BlockPos) endPosition.invoke(end);
            return net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(startPos, endPos);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static net.minecraft.world.level.levelgen.structure.BoundingBox translateBox(Object sourceStart, Object sourceEnd, Object destinationStart) {
        try {
            Method position = sourceStart.getClass().getDeclaredMethod("position");
            position.setAccessible(true);
            Method endPosition = sourceEnd.getClass().getDeclaredMethod("position");
            endPosition.setAccessible(true);
            Method destinationPosition = destinationStart.getClass().getDeclaredMethod("position");
            destinationPosition.setAccessible(true);

            net.minecraft.core.BlockPos sourceStartPos = (net.minecraft.core.BlockPos) position.invoke(sourceStart);
            net.minecraft.core.BlockPos sourceEndPos = (net.minecraft.core.BlockPos) endPosition.invoke(sourceEnd);
            net.minecraft.core.BlockPos destinationStartPos = (net.minecraft.core.BlockPos) destinationPosition.invoke(destinationStart);
            net.minecraft.core.BlockPos delta = sourceEndPos.subtract(sourceStartPos);
            net.minecraft.core.BlockPos destinationEndPos = destinationStartPos.offset(delta);
            return net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(destinationStartPos, destinationEndPos);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
