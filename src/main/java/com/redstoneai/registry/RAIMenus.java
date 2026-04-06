package com.redstoneai.registry;

import com.redstoneai.RedstoneAI;
import com.redstoneai.workspace.WorkspaceControllerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class RAIMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, RedstoneAI.ID);

    public static final RegistryObject<MenuType<WorkspaceControllerMenu>> WORKSPACE_CONTROLLER =
            MENUS.register("workspace_controller",
                    () -> IForgeMenuType.create(WorkspaceControllerMenu::new));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
