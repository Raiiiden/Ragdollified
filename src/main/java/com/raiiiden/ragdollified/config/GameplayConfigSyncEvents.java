package com.raiiiden.ragdollified.config;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.network.GameplayConfigSyncPacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

// Sends the server's COMMON gameplay values to clients without changing their local files
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GameplayConfigSyncEvents {
    private GameplayConfigSyncEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendTo(player);
    }

    public static void sendTo(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GameplayConfigSyncPacket(RagdollifiedConfig.createGameplaySnapshot()));
    }

    public static void sendToAll(MinecraftServer server) {
        GameplayConfigSyncPacket packet =
                new GameplayConfigSyncPacket(RagdollifiedConfig.createGameplaySnapshot());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
