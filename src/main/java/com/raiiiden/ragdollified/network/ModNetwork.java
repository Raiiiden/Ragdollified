package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "6";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Ragdollified.MODID, "main"),
            () -> PROTOCOL_VERSION,
            ModNetwork::acceptsVersion,
            ModNetwork::acceptsVersion
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    private static boolean acceptsVersion(String version) {
        // Keep the channel optional for vanilla servers/clients while rejecting a different
        // mod packet layout (v6 expands poses to ten parts for native spider/Ghast anatomy).
        return PROTOCOL_VERSION.equals(version)
                || NetworkRegistry.ABSENT.equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    public static void register() {
        CHANNEL.registerMessage(nextId(), RagdollSpawnPacket.class,
                RagdollSpawnPacket::encode,
                RagdollSpawnPacket::decode,
                RagdollSpawnPacket::handle);

        CHANNEL.registerMessage(nextId(), RagdollImpulsePacket.class,
                RagdollImpulsePacket::encode,
                RagdollImpulsePacket::decode,
                RagdollImpulsePacket::handle);

        CHANNEL.registerMessage(nextId(), CorpseSettlePacket.class,
                CorpseSettlePacket::encode,
                CorpseSettlePacket::decode,
                CorpseSettlePacket::handle);

        CHANNEL.registerMessage(nextId(), RagdollStatePacket.class,
                RagdollStatePacket::encode,
                RagdollStatePacket::decode,
                RagdollStatePacket::handle);

        CHANNEL.registerMessage(nextId(), RagdollStreamPacket.class,
                RagdollStreamPacket::encode,
                RagdollStreamPacket::decode,
                RagdollStreamPacket::handle);

        CHANNEL.messageBuilder(RagdollStreamOwnerPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RagdollStreamOwnerPacket::encode)
                .decoder(RagdollStreamOwnerPacket::decode)
                .consumerMainThread(RagdollStreamOwnerPacket::handle)
                .add();

        CHANNEL.messageBuilder(GameplayConfigSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GameplayConfigSyncPacket::encode)
                .decoder(GameplayConfigSyncPacket::decode)
                .consumerMainThread(GameplayConfigSyncPacket::handle)
                .add();
    }
}
