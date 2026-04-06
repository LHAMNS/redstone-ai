package com.redstoneai.registry;

import com.redstoneai.RedstoneAI;
import com.redstoneai.workspace.WorkspaceControllerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class RAIBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, RedstoneAI.ID);

    public static final RegistryObject<WorkspaceControllerBlock> WORKSPACE_CONTROLLER =
            BLOCKS.register("workspace_controller", () -> new WorkspaceControllerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_CYAN)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
            ));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
