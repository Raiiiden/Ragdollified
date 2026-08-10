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

            // Submit physics tick to the worker thread — non-blocking. If the previous
            // physics tick is still running, this submission is dropped (we'd rather
            // skip a tick than backlog and double up later).
            ClientRagdollManager.submitTick();

            // Corpse bridge — report the local player's ragdoll settle to the server and
            // hand off rendering from the physics ragdoll to the posed corpse entity.
            ClientRagdollManager.tickCorpseClient();
            ClientRagdollManager.tickRagdollSyncClient();

            // In-flight pose frames for player ragdolls this client owns at 20 Hz.
            // Player bodies are rare enough to publish every game tick, so observers receive
            // every authoritative physics step instead of reconstructing a missing one.
            ClientRagdollManager.tickRagdollStreamClient();

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
            GeckoLibArmorHelper.onWorldUnload();
        }
    }

    // A corpse carries the blood and damage captured from the ragdoll it replaced, keyed by its
    // own UUID. Free its wound textures when it goes away — an unbounded number of out-of-range
    // corpses each holding a set would leak GL memory — but keep the capture itself so walking
    // back into range rebuilds them.
    //
    // Deliberately not branching on the removal reason: the client removes an entity that simply
    // left tracking range with RemovalReason.DISCARDED, the same reason a looted corpse gets, so
    // "gone for good" is not distinguishable here. The captures are small and are dropped
    // wholesale on disconnect (ClientRagdollManager#onWorldUnload).
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
