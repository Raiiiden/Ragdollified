package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client→server settled-pose report and server→client retained state for late area entrants.
 */
public class RagdollStatePacket {
    private final int entityId;
    private final RagdollTransform[] transforms;
    private final int impulseRevision;
    private final int ageTicks;
    private final boolean settled;

    public RagdollStatePacket(int entityId, RagdollTransform[] transforms,
                              int impulseRevision, int ageTicks, boolean settled) {
        this.entityId = entityId;
        this.transforms = transforms;
        this.impulseRevision = impulseRevision;
        this.ageTicks = ageTicks;
        this.settled = settled;
    }

    public static void encode(RagdollStatePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.impulseRevision);
        buf.writeVarInt(Math.max(0, msg.ageTicks));
        buf.writeBoolean(msg.settled);
        for (int i = 0; i < 6; i++) {
            RagdollTransform transform =
                    msg.transforms != null && i < msg.transforms.length ? msg.transforms[i] : null;
            buf.writeBoolean(transform != null);
            if (transform != null) transform.writeTo(buf);
        }
    }

    public static RagdollStatePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int impulseRevision = buf.readInt();
        int ageTicks = buf.readVarInt();
        boolean settled = buf.readBoolean();
        RagdollTransform[] transforms = new RagdollTransform[6];
        for (int i = 0; i < transforms.length; i++) {
            if (buf.readBoolean()) transforms[i] = RagdollTransform.readFrom(buf);
        }
        return new RagdollStatePacket(entityId, transforms, impulseRevision, ageTicks, settled);
    }

    public static void handle(RagdollStatePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerRagdollSyncManager.handlePoseReport(
                        sender, msg.entityId, msg.transforms, msg.impulseRevision, msg.settled);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientRagdollManager.enqueueAuthoritativeState(
                                msg.entityId, msg.transforms, msg.ageTicks, msg.settled));
            }
        });
        context.setPacketHandled(true);
    }
}
