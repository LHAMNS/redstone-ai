package com.redstoneai.mixin;

import net.minecraft.server.commands.data.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityDataAccessor.class)
public interface EntityDataAccessorMixin {
    @Accessor("entity")
    Entity redstoneai$getEntity();
}
