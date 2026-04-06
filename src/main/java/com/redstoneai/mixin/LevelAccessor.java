package com.redstoneai.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Accessor mixin to expose Level's blockEntityTickers for tick-step processing.
 * Used by TickController to selectively tick block entities within a workspace.
 */
@Mixin(Level.class)
public interface LevelAccessor {

    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> redstoneai$getBlockEntityTickers();
}
