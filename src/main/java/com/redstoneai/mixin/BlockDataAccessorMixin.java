package com.redstoneai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.data.BlockDataAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockDataAccessor.class)
public interface BlockDataAccessorMixin {
    @Accessor("pos")
    BlockPos redstoneai$getPos();

    @Accessor("entity")
    BlockEntity redstoneai$getEntity();
}
