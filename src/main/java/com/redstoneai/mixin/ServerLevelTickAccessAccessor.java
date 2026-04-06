package com.redstoneai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelTickAccessAccessor {

    @Accessor("blockTicks")
    LevelTicks<Block> redstoneai$getBlockTicks();

    @Accessor("fluidTicks")
    LevelTicks<Fluid> redstoneai$getFluidTicks();
}
