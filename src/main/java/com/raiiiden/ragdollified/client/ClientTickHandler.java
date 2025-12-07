package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientTickHandler {

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;

            // Cleanup every 5 seconds
            if (tickCounter >= 100) {
                ClientMobTextureCache.cleanup();
                tickCounter = 0;
            }
        }
    }
}