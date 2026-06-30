package com.raiiiden.ragdollified.menu;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Ragdollified.MODID);

    public static final RegistryObject<MenuType<CorpseMenu>> CORPSE =
            MENUS.register("corpse", () -> IForgeMenuType.create(CorpseMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
