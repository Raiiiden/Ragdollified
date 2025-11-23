package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.DeathRagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DeathRagdollUpdatePacket {
    private final int entityId;
    private final int serverTick;          // ← 20 Hz server tick
    private final RagdollTransform[] transforms;

    public DeathRagdollUpdatePacket(int entityId, int serverTick, RagdollTransform[] transforms) {
        this.entityId = entityId;
        this.serverTick = serverTick;
        this.transforms = transforms;
    }

    public static void encode(DeathRagdollUpdatePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.serverTick);      // ← int, not long
        buf.writeInt(msg.transforms.length);
        for (RagdollTransform t : msg.transforms) t.writeTo(buf);
    }

    public static DeathRagdollUpdatePacket decode(FriendlyByteBuf buf) {
        int id   = buf.readInt();
        int tick = buf.readInt();          // ← int
        int len  = buf.readInt();
        RagdollTransform[] transforms = new RagdollTransform[len];
        for (int i = 0; i < len; i++) transforms[i] = RagdollTransform.readFrom(buf);
        return new DeathRagdollUpdatePacket(id, tick, transforms);
    }

    public static void handle(DeathRagdollUpdatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(DeathRagdollUpdatePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        DeathRagdollManager.addOrUpdate(msg.entityId, msg.transforms, msg.serverTick);
    }
}