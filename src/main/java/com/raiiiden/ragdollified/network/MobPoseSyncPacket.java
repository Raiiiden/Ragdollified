package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.ServerMobPoseCache;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Packet to sync captured mob poses from client to server when mob dies
 */
public class MobPoseSyncPacket {
    private final int entityId;
    private final Map<RagdollPart, PoseData> poses;

    // Constructor for SENDING (client side)
    public MobPoseSyncPacket(int entityId, MobPoseCapture.MobPose pose) {
        this.entityId = entityId;
        this.poses = new HashMap<>();

        if (pose != null) {
            for (RagdollPart part : RagdollPart.values()) {
                MobPoseCapture.PartPose partPose = pose.getPose(part);
                poses.put(part, new PoseData(partPose.xRot, partPose.yRot, partPose.zRot));
            }
        }
    }

    // Constructor for RECEIVING (server side)
    public MobPoseSyncPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readInt();
        this.poses = new HashMap<>();

        int count = buf.readByte();
        for (int i = 0; i < count; i++) {
            int partIndex = buf.readByte();
            float xRot = buf.readFloat();
            float yRot = buf.readFloat();
            float zRot = buf.readFloat();

            poses.put(RagdollPart.values()[partIndex], new PoseData(xRot, yRot, zRot));
        }
    }

    // Encode to send over network
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByte(poses.size());

        for (Map.Entry<RagdollPart, PoseData> entry : poses.entrySet()) {
            buf.writeByte(entry.getKey().ordinal());
            buf.writeFloat(entry.getValue().xRot);
            buf.writeFloat(entry.getValue().yRot);
            buf.writeFloat(entry.getValue().zRot);
        }
    }

    // Handle on server side
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Store pose on server for when ragdoll is created
            if (!poses.isEmpty()) {
                Map<RagdollPart, MobPoseCapture.PartPose> partPoses = new HashMap<>();
                for (Map.Entry<RagdollPart, PoseData> entry : poses.entrySet()) {
                    PoseData data = entry.getValue();
                    partPoses.put(entry.getKey(), new MobPoseCapture.PartPose(data.xRot, data.yRot, data.zRot));
                }

                MobPoseCapture.MobPose mobPose = new MobPoseCapture.MobPose(partPoses);
                ServerMobPoseCache.storePose(entityId, mobPose);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static class PoseData {
        final float xRot, yRot, zRot;

        PoseData(float xRot, float yRot, float zRot) {
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
        }
    }
}