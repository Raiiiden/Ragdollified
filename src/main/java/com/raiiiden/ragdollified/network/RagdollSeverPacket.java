package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Server to client: take a part off an existing ragdoll and set it loose.
// No pose is sent; each client reads the limb from its own simulation.
public class RagdollSeverPacket {

    private final int ragdollEntityId;
    private final byte partIndex;
    private final int limbId;
    private final int limbLifetimeTicks;
    private final float impulseX, impulseY, impulseZ;

    public RagdollSeverPacket(int ragdollEntityId, RagdollPart part, int limbId, int limbLifetimeTicks,
                              float impulseX, float impulseY, float impulseZ) {
        this(ragdollEntityId, (byte) part.index, limbId, limbLifetimeTicks, impulseX, impulseY, impulseZ);
    }

    private RagdollSeverPacket(int ragdollEntityId, byte partIndex, int limbId, int limbLifetimeTicks,
                               float impulseX, float impulseY, float impulseZ) {
        this.ragdollEntityId = ragdollEntityId;
        this.partIndex = partIndex;
        this.limbId = limbId;
        this.limbLifetimeTicks = limbLifetimeTicks;
        this.impulseX = impulseX;
        this.impulseY = impulseY;
        this.impulseZ = impulseZ;
    }

    public static void encode(RagdollSeverPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.ragdollEntityId);
        buf.writeByte(msg.partIndex);
        buf.writeInt(msg.limbId);
        buf.writeVarInt(msg.limbLifetimeTicks);
        buf.writeFloat(msg.impulseX);
        buf.writeFloat(msg.impulseY);
        buf.writeFloat(msg.impulseZ);
    }

    public static RagdollSeverPacket decode(FriendlyByteBuf buf) {
        return new RagdollSeverPacket(buf.readInt(), buf.readByte(), buf.readInt(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(RagdollSeverPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(RagdollSeverPacket msg) {
        RagdollPart part = RagdollPart.byIndex(msg.partIndex);
        if (part == null || !part.isSeverable()) return;
        if (!Float.isFinite(msg.impulseX) || !Float.isFinite(msg.impulseY) || !Float.isFinite(msg.impulseZ)) return;
        ClientRagdollManager.enqueueSever(new ClientRagdollManager.SeverRequest(
                msg.ragdollEntityId, msg.partIndex, msg.limbId, msg.limbLifetimeTicks,
                msg.impulseX, msg.impulseY, msg.impulseZ));
    }
}
