package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.server.CorpseManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client→Server: "my ragdoll settled". Sent by the dead player's own client when its
 * local physics ragdoll comes to rest. Carries the settle origin (where to place the
 * corpse) and the 6 frozen part transforms relative to that origin so the corpse renders
 * the exact death pose on every client.
 */
public class CorpseSettlePacket {

    private final double originX, originY, originZ;
    private final RagdollTransform[] transforms; // length 6, entries may be null

    public CorpseSettlePacket(double originX, double originY, double originZ, RagdollTransform[] transforms) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.transforms = transforms;
    }

    public static void encode(CorpseSettlePacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.originX);
        buf.writeDouble(msg.originY);
        buf.writeDouble(msg.originZ);
        for (int i = 0; i < 6; i++) {
            RagdollTransform t = (msg.transforms != null && i < msg.transforms.length) ? msg.transforms[i] : null;
            boolean present = t != null;
            buf.writeBoolean(present);
            if (present) t.writeTo(buf);
        }
    }

    public static CorpseSettlePacket decode(FriendlyByteBuf buf) {
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        RagdollTransform[] transforms = new RagdollTransform[6];
        for (int i = 0; i < 6; i++) {
            if (buf.readBoolean()) transforms[i] = RagdollTransform.readFrom(buf);
        }
        return new CorpseSettlePacket(ox, oy, oz, transforms);
    }

    public static void handle(CorpseSettlePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return; // only meaningful server-side
            CorpseManager.handleSettle(sender, msg.originX, msg.originY, msg.originZ, msg.transforms);
        });
        ctx.get().setPacketHandled(true);
    }
}
