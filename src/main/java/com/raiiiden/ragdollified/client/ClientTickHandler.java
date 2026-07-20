package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
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

            // Cleanup every 5 seconds (still on render thread — cheap)
            if (tickCounter >= 100) {
                ClientMobTextureCache.cleanup();
                tickCounter = 0;
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientRagdollManager.enqueueBlockChange(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientRagdollManager.enqueueBlockChange(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientRagdollManager.onWorldUnload();
            GeckoLibArmorHelper.onWorldUnload();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RagdollifiedConfig.clearServerSnapshot();
    }
}
