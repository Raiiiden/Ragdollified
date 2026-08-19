package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.server.CorpseManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

// Client report that a nearby player ragdoll settled: dead player, death entity, settle origin and
// six frozen part transforms, so the server can spawn the posed corpse from any observer.
public class CorpseSettlePacket {

    private final double originX, originY, originZ;
    private final RagdollTransform[] transforms; // fixed MAX_PARTS, entries may be null
    private final UUID ownerUUID;
    private final int ragdollEntityId;
    private final int impulseRevision;

    public CorpseSettlePacket(double originX, double originY, double originZ, RagdollTransform[] transforms) {
        this(originX, originY, originZ, transforms, null, -1, -1);
    }

    public CorpseSettlePacket(double originX, double originY, double originZ, RagdollTransform[] transforms,
                              UUID ownerUUID, int ragdollEntityId) {
        this(originX, originY, originZ, transforms, ownerUUID, ragdollEntityId, -1);
    }

    public CorpseSettlePacket(double originX, double originY, double originZ, RagdollTransform[] transforms,
                              UUID ownerUUID, int ragdollEntityId, int impulseRevision) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.transforms = transforms;
        this.ownerUUID = ownerUUID;
        this.ragdollEntityId = ragdollEntityId;
        this.impulseRevision = impulseRevision;
    }

    public static void encode(CorpseSettlePacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.originX);
        buf.writeDouble(msg.originY);
        buf.writeDouble(msg.originZ);
        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            RagdollTransform t = (msg.transforms != null && i < msg.transforms.length) ? msg.transforms[i] : null;
            boolean present = t != null;
            buf.writeBoolean(present);
            if (present) t.writeTo(buf);
        }
        // Appended so the original owner-only packet remains a valid prefix.
        buf.writeBoolean(msg.ownerUUID != null);
        if (msg.ownerUUID != null) buf.writeUUID(msg.ownerUUID);
        buf.writeInt(msg.ragdollEntityId);
        buf.writeInt(msg.impulseRevision);
    }

    public static CorpseSettlePacket decode(FriendlyByteBuf buf) {
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            if (buf.readBoolean()) transforms[i] = RagdollTransform.readFrom(buf);
        }
        // Old clients end after the transforms. Preserve their owner-only behavior.
        UUID ownerUUID = null;
        int ragdollEntityId = -1;
        int impulseRevision = -1;
        if (buf.readableBytes() > 0) {
            if (buf.readBoolean()) ownerUUID = buf.readUUID();
            if (buf.readableBytes() >= Integer.BYTES) ragdollEntityId = buf.readInt();
            if (buf.readableBytes() >= Integer.BYTES) impulseRevision = buf.readInt();
        }
        return new CorpseSettlePacket(ox, oy, oz, transforms, ownerUUID, ragdollEntityId, impulseRevision);
    }

    public static void handle(CorpseSettlePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return; // only meaningful server-side
            if (msg.ownerUUID != null && msg.ragdollEntityId >= 0) {
                CorpseManager.handleObservedSettle(sender, msg.ownerUUID, msg.ragdollEntityId,
                        msg.impulseRevision, msg.originX, msg.originY, msg.originZ, msg.transforms);
            } else {
                CorpseManager.handleSettle(sender, msg.originX, msg.originY, msg.originZ, msg.transforms);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
