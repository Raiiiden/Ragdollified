package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Ragdollified.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    public static void register() {
        // Death ragdoll packets
        CHANNEL.registerMessage(nextId(), DeathRagdollStartPacket.class,
                DeathRagdollStartPacket::encode,
                DeathRagdollStartPacket::decode,
                DeathRagdollStartPacket::handle);

        CHANNEL.registerMessage(nextId(), DeathRagdollUpdatePacket.class,
                DeathRagdollUpdatePacket::encode,
                DeathRagdollUpdatePacket::decode,
                DeathRagdollUpdatePacket::handle);

        CHANNEL.registerMessage(nextId(), DeathRagdollEndPacket.class,
                DeathRagdollEndPacket::encode,
                DeathRagdollEndPacket::decode,
                DeathRagdollEndPacket::handle);
    }
}