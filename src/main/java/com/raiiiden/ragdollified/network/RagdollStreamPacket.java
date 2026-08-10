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

// Live pose frames for a ragdoll still in flight: owner client, to server, to nearby observers.
// Where RagdollStatePacket reconciles one settled pose, this carries the body mid-tumble so
// observers never guess a trajectory and get corrected. Players only — a wrong tumble on a mob
// costs nothing and the bandwidth does.
//
// Player bodies are rare, so this deliberately carries full-precision absolute position and
// quaternion floats for every part at 20 Hz. Observers receive the owner's actual Bullet
// transforms; no offset or rotation quantization can create a different endpoint or angle.
public class RagdollStreamPacket {
    private final int entityId;
    private final int sequence;
    private final RagdollTransform[] transforms;

    public RagdollStreamPacket(int entityId, int sequence, RagdollTransform[] transforms) {
        this.entityId = entityId;
        this.sequence = sequence;
        this.transforms = transforms;
    }

    public int entityId() { return entityId; }
    public int sequence() { return sequence; }
    public RagdollTransform[] transforms() { return transforms; }

    public static void encode(RagdollStreamPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeVarInt(msg.sequence);

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
        int mask = buf.readVarInt();
        RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
        if ((mask & 1) == 0) return new RagdollStreamPacket(entityId, sequence, transforms);

        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            if ((mask & (1 << i)) == 0) continue;
            transforms[i] = new RagdollTransform(i,
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
        return new RagdollStreamPacket(entityId, sequence, transforms);
    }

    public static void handle(RagdollStreamPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerRagdollSyncManager.handleStreamFrame(
                        sender, msg.entityId, msg.transforms, msg.sequence);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientRagdollManager.enqueueStreamedPose(
                                msg.entityId, msg.transforms, msg.sequence));
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
