package com.redstoneai.mixin;

import com.redstoneai.tick.TickInterceptor;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents pistons from pushing or pulling blocks across workspace boundaries.
 * <p>
 * Injected into {@code PistonBaseBlock.moveBlocks()} to cancel the move if any
 * block in the piston structure would cross a workspace boundary. This ensures:
 * <ul>
 *   <li>Blocks inside a workspace cannot be pushed out</li>
 *   <li>Blocks outside cannot be pushed in</li>
 *   <li>Blocks in one workspace cannot be pushed into another</li>
 * </ul>
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBoundaryMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void redstoneai$preventCrossBoundaryMove(Level level, BlockPos pistonPos, Direction direction,
                                                     boolean extending, CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        WorkspaceManager manager = WorkspaceManager.get(serverLevel);
        Workspace pistonWorkspace = manager.getWorkspaceAt(pistonPos);

        // Resolve what the piston would move
        PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, direction, extending);
        if (!resolver.resolve()) return; // Can't move anyway

        // Check every block that would be moved
        for (BlockPos movedPos : resolver.getToPush()) {
            BlockPos destination = movedPos.relative(direction);
            Workspace sourceWs = manager.getWorkspaceAt(movedPos);
            Workspace destWs = manager.getWorkspaceAt(destination);

            // If source and destination are in different workspaces (or one is null), cancel
            if (sourceWs != destWs) {
                cir.setReturnValue(false);
                return;
            }
        }

        // Check blocks that would be destroyed
        for (BlockPos destroyPos : resolver.getToDestroy()) {
            Workspace destroyWs = manager.getWorkspaceAt(destroyPos);
            if (destroyWs != pistonWorkspace) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
