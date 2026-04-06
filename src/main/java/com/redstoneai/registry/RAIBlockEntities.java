package com.redstoneai.registry;

import com.redstoneai.RedstoneAI;
import com.redstoneai.workspace.WorkspaceControllerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class RAIBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, RedstoneAI.ID);

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<WorkspaceControllerBlockEntity>> WORKSPACE_CONTROLLER =
            BLOCK_ENTITIES.register("workspace_controller", () ->
                    BlockEntityType.Builder.of(
                            WorkspaceControllerBlockEntity::new,
                            RAIBlocks.WORKSPACE_CONTROLLER.get()
                    ).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
