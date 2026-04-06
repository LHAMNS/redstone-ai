package com.redstoneai.registry;

import com.redstoneai.RedstoneAI;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class RAIItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RedstoneAI.ID);

    public static final RegistryObject<BlockItem> WORKSPACE_CONTROLLER =
            ITEMS.register("workspace_controller", () ->
                    new BlockItem(RAIBlocks.WORKSPACE_CONTROLLER.get(), new Item.Properties()));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
