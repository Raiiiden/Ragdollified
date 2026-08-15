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

// Owner pose relayed through the server to observers.
public class RagdollStreamPacket {
    private final int entityId;
    private final int sequence;
    private final int sampleTick;
    private final boolean hardSync;
    private final RagdollTransform[] transforms;

    public RagdollStreamPacket(int entityId, int sequence, int sampleTick,
                               RagdollTransform[] transforms) {
        this(entityId, sequence, sampleTick, false, transforms);
    }

    public RagdollStreamPacket(int entityId, int sequence, int sampleTick, boolean hardSync,
                               RagdollTransform[] transforms) {
        this.entityId = entityId;
        this.sequence = sequence;
        this.sampleTick = sampleTick;
        this.hardSync = hardSync;
        this.transforms = transforms;
    }

    public int entityId() { return entityId; }
    public int sequence() { return sequence; }
    public int sampleTick() { return sampleTick; }
    public boolean hardSync() { return hardSync; }
    public RagdollTransform[] transforms() { return transforms; }

    public static void encode(RagdollStreamPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeVarInt(msg.sequence);
        buf.writeVarInt(msg.sampleTick);
        buf.writeBoolean(msg.hardSync);

        int mask = 0;
        if (part(msg.transforms, 0) != null) {
            for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
                if (part(msg.transforms, i) != null) mask |= 1 << i;
            }
        }
        // The torso is mandatory so validation, tracking, and rendering always have an
        // authoritative root even though every part is encoded in absolute coordinates.
        buf.writeVarInt(mask);
        if (mask == 0) return;

        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            RagdollTransform transform = part(msg.transforms, i);
            if (transform == null) continue;
            buf.writeFloat(transform.position.x);
            buf.writeFloat(transform.position.y);
            buf.writeFloat(transform.position.z);
            buf.writeFloat(transform.rotation.x);
            buf.writeFloat(transform.rotation.y);
            buf.writeFloat(transform.rotation.z);
            buf.writeFloat(transform.rotation.w);
        }
    }

    public static RagdollStreamPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int sequence = buf.readVarInt();
        int sampleTick = buf.readVarInt();
        boolean hardSync = buf.readBoolean();
        int mask = buf.readVarInt();
        RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
        if ((mask & 1) == 0) {
            return new RagdollStreamPacket(entityId, sequence, sampleTick, hardSync, transforms);
        }

        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            if ((mask & (1 << i)) == 0) continue;
            transforms[i] = new RagdollTransform(i,
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
        return new RagdollStreamPacket(entityId, sequence, sampleTick, hardSync, transforms);
    }

    public static void handle(RagdollStreamPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerRagdollSyncManager.handleStreamFrame(
                        sender, msg.entityId, msg.transforms, msg.sequence,
                        msg.sampleTick, msg.hardSync);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientRagdollManager.enqueueStreamedPose(
                                msg.entityId, msg.transforms, msg.sequence,
                                msg.sampleTick, msg.hardSync));
            }
        });
        context.setPacketHandled(true);
    }

    private static RagdollTransform part(RagdollTransform[] transforms, int index) {
        if (transforms == null || index >= transforms.length) return null;
        RagdollTransform transform = transforms[index];
        if (transform == null || transform.partId != index) return null;
        if (!Float.isFinite(transform.position.x) || !Float.isFinite(transform.position.y)
                || !Float.isFinite(transform.position.z)) return null;
        if (!Float.isFinite(transform.rotation.x) || !Float.isFinite(transform.rotation.y)
                || !Float.isFinite(transform.rotation.z)
                || !Float.isFinite(transform.rotation.w)) return null;
        return transform;
    }
}
