package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.function.Supplier;

// Live pose frames for a ragdoll still in flight: owner client, to server, to nearby observers.
// Where RagdollStatePacket reconciles one settled pose, this carries the body mid-tumble so
// observers never guess a trajectory and get corrected. Players only — a wrong tumble on a mob
// costs nothing and the bandwidth does.
//
// Quantized hard because it goes out ~10x/second per viewer: only the torso is absolute, every
// other part is a 1/512-block offset from it, and rotations use smallest-three packing. 72
// bytes a frame against 168 for raw floats.
public class RagdollStreamPacket {
    // 1/512 block. A ragdoll part never leaves ±64 blocks of its own torso.
    private static final float OFFSET_SCALE = 512f;
    private static final float INV_SQRT2 = 0.70710678f;
    private static final int COMPONENT_RANGE = 511;

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

        RagdollTransform torso = part(msg.transforms, 0);
        int mask = 0;
        if (torso != null) {
            for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
                if (part(msg.transforms, i) != null) mask |= 1 << i;
            }
        }
        // Without the torso there is no anchor for the other offsets, so the whole frame is
        // dropped rather than sent as a partial pose the receiver could not reassemble.
        buf.writeVarInt(mask);
        if (mask == 0) return;

        buf.writeFloat(torso.position.x);
        buf.writeFloat(torso.position.y);
        buf.writeFloat(torso.position.z);
        buf.writeInt(packRotation(torso.rotation));

        for (int i = 1; i < RagdollTransform.MAX_PARTS; i++) {
            RagdollTransform transform = part(msg.transforms, i);
            if (transform == null) continue;
            buf.writeShort(quantizeOffset(transform.position.x - torso.position.x));
            buf.writeShort(quantizeOffset(transform.position.y - torso.position.y));
            buf.writeShort(quantizeOffset(transform.position.z - torso.position.z));
            buf.writeInt(packRotation(transform.rotation));
        }
    }

    public static RagdollStreamPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int sequence = buf.readVarInt();
        int mask = buf.readVarInt();
        RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
        if ((mask & 1) == 0) return new RagdollStreamPacket(entityId, sequence, transforms);

        float torsoX = buf.readFloat();
        float torsoY = buf.readFloat();
        float torsoZ = buf.readFloat();
        transforms[0] = new RagdollTransform(0,
                new Vector3f(torsoX, torsoY, torsoZ), unpackRotation(buf.readInt()));

        for (int i = 1; i < RagdollTransform.MAX_PARTS; i++) {
            if ((mask & (1 << i)) == 0) continue;
            float x = torsoX + buf.readShort() / OFFSET_SCALE;
            float y = torsoY + buf.readShort() / OFFSET_SCALE;
            float z = torsoZ + buf.readShort() / OFFSET_SCALE;
            transforms[i] = new RagdollTransform(i,
                    new Vector3f(x, y, z), unpackRotation(buf.readInt()));
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
        return transform;
    }

    private static short quantizeOffset(float offset) {
        int quantized = Math.round(offset * OFFSET_SCALE);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, quantized));
    }

    // Smallest-three: drop the largest component and rebuild it from the unit constraint, so
    // three 10-bit fields and a 2-bit index carry a whole rotation in one int. The rest are
    // bounded by 1/sqrt(2), which sets the scale factor.
    private static int packRotation(Quat4f rotation) {
        float[] components = { rotation.x, rotation.y, rotation.z, rotation.w };
        int largest = 0;
        float largestAbs = Math.abs(components[0]);
        for (int i = 1; i < 4; i++) {
            float abs = Math.abs(components[i]);
            if (abs > largestAbs) {
                largestAbs = abs;
                largest = i;
            }
        }
        // q and -q are the same rotation; forcing the dropped component positive is what
        // lets the decoder rebuild it without a sign bit.
        float sign = components[largest] < 0f ? -1f : 1f;

        int packed = largest << 30;
        int shift = 20;
        for (int i = 0; i < 4; i++) {
            if (i == largest) continue;
            float value = components[i] * sign / INV_SQRT2;
            int quantized = Math.round(value * COMPONENT_RANGE) + COMPONENT_RANGE;
            quantized = Math.max(0, Math.min(1023, quantized));
            packed |= quantized << shift;
            shift -= 10;
        }
        return packed;
    }

    private static Quat4f unpackRotation(int packed) {
        int largest = (packed >>> 30) & 0x3;
        float[] components = new float[4];
        float sumOfSquares = 0f;
        int shift = 20;
        for (int i = 0; i < 4; i++) {
            if (i == largest) continue;
            int quantized = (packed >>> shift) & 0x3FF;
            float value = (quantized - COMPONENT_RANGE) / (float) COMPONENT_RANGE * INV_SQRT2;
            components[i] = value;
            sumOfSquares += value * value;
            shift -= 10;
        }
        components[largest] = (float) Math.sqrt(Math.max(0f, 1f - sumOfSquares));
        Quat4f rotation = new Quat4f(components[0], components[1], components[2], components[3]);
        // Quantization leaves the result slightly off the unit sphere; jbullet's transform
        // maths assumes a unit quaternion.
        rotation.normalize();
        return rotation;
    }
}
