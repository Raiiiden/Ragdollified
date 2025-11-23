package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.client.DeathRagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DeathRagdollEndPacket {
    private final int entityId;

    public DeathRagdollEndPacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(DeathRagdollEndPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static DeathRagdollEndPacket decode(FriendlyByteBuf buf) {
        return new DeathRagdollEndPacket(buf.readInt());
    }

    public static void handle(DeathRagdollEndPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(DeathRagdollEndPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        DeathRagdollManager.remove(msg.entityId);
    }
}