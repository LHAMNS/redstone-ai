package com.redstoneai.mixin;

import com.redstoneai.recording.StateRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records block state changes within workspace bounds for the recording system.
 * <p>
 * Injects at HEAD of setBlock to capture the old state before the change occurs.
 * Only active during step processing (StateRecorder.isRecording()), so the
 * overhead during normal gameplay is a single boolean check per setBlock call.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void redstoneai$beforeSetBlock(BlockPos pos, BlockState newState, int flags,
                                           int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (StateRecorder.isRecording() && (Object) this instanceof ServerLevel serverLevel) {
            BlockState oldState = this.getBlockState(pos);
            if (!oldState.equals(newState)) {
                StateRecorder.onBlockChanged(serverLevel, pos, oldState, newState);
            }
        }
    }
}
