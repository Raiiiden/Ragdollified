package com.raiiiden.ragdollified.item;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Ragdollified.MODID);

    public static final RegistryObject<CorpseCompassItem> CORPSE_COMPASS = ITEMS.register("corpse_compass",
            () -> new CorpseCompassItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    // Show it in a creative tab so it's obtainable for testing/admin use. It's normally handed
    // out automatically on respawn; this is just for /give-free access.
    @SubscribeEvent
    public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CORPSE_COMPASS.get());
        }
    }
}
