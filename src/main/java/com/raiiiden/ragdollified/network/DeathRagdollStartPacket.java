package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.DeathRagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DeathRagdollStartPacket {
    private final int entityId;
    private final int serverTick;   // ← server tick instead of wall clock
    private final RagdollTransform[] transforms;

    public DeathRagdollStartPacket(int entityId, int serverTick, RagdollTransform[] transforms) {
        this.entityId = entityId;
        this.serverTick = serverTick;
        this.transforms = transforms;
    }

    public static void encode(DeathRagdollStartPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.serverTick);          // ← int
        buf.writeInt(msg.transforms.length);
        for (RagdollTransform t : msg.transforms) t.writeTo(buf);
    }

    public static DeathRagdollStartPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int tick     = buf.readInt();          // ← int
        int len      = buf.readInt();
        RagdollTransform[] transforms = new RagdollTransform[len];
        for (int i = 0; i < len; i++) transforms[i] = RagdollTransform.readFrom(buf);
        return new DeathRagdollStartPacket(entityId, tick, transforms);
    }

    public static void handle(DeathRagdollStartPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(DeathRagdollStartPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Ragdollified.LOGGER.info("CLIENT: Received DeathRagdollStartPacket for entity ID: " + msg.entityId);
        DeathRagdollManager.addOrUpdate(msg.entityId, msg.transforms, msg.serverTick);
        Ragdollified.LOGGER.info("CLIENT: Added to DeathRagdollManager. Active ragdolls: " + DeathRagdollManager.getAll().size());
    }
}