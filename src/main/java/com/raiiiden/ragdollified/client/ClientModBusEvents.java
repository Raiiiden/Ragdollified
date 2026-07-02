package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.screen.CorpseScreen;
import com.raiiiden.ragdollified.entity.ModEntities;
import com.raiiiden.ragdollified.item.ModItems;
import com.raiiiden.ragdollified.menu.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModBusEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CORPSE.get(), CorpseRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.CORPSE.get(), CorpseScreen::new);
            // "angle" (minecraft:angle) drives the compass_XX frame overrides in the item model,
            // same predicate name vanilla compasses use — registered per-item, so this is safe.
            ItemProperties.register(ModItems.CORPSE_COMPASS.get(),
                    new ResourceLocation("angle"), new CorpseCompassAngle());
        });
    }
}
