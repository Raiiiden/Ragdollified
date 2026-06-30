package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Ragdollified.MODID, "main"),
            () -> PROTOCOL_VERSION,
            // Accept any version on both sides so the mod works on vanilla servers
            v -> true,
            v -> true
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
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
    }
}
