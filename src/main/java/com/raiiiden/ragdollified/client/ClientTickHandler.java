package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientTickHandler {

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;

            // Submit a physics tick to the worker, non-blocking. A submission made while the previous
            // tick still runs is dropped: skipping a tick beats doubling up later.
            ClientRagdollManager.submitTick();

            // Corpse bridge — report the local player's ragdoll settle to the server and
            // hand off rendering from the physics ragdoll to the posed corpse entity.
            ClientRagdollManager.tickCorpseClient();
            ClientRagdollManager.tickRagdollSyncClient();

            ClientRagdollManager.tickRagdollStreamClient();

            // Hand the physics worker's queued contacts to API listeners on this thread.
            RagdollCollisionTracker.dispatchPending();

            // Cleanup every 5 seconds (still on render thread — cheap)
            if (tickCounter >= 100) {
                ClientMobTextureCache.cleanup();
                tickCounter = 0;
            }
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientRagdollManager.onWorldUnload();
            RagdollCollisionTracker.clear();
            GeckoLibArmorHelper.onWorldUnload();
        }
    }

    // A corpse keeps the blood and damage of the ragdoll it replaced, so free its wound textures when it
    // goes but keep the capture. The removal reason cannot distinguish gone from merely out of range.
    @SubscribeEvent
    public static void onCorpseLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof CorpseEntity corpse) {
            BetterBloodOverlayCompat.releaseTextures(corpse.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RagdollifiedConfig.clearServerSnapshot();
    }
}
