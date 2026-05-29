package com.raiiiden.ragdollified;

import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MobPoseCapture {
    private static final Map<Integer, PoseCacheEntry> CAPTURED_POSES = new ConcurrentHashMap<>();

    private static class PoseCacheEntry {
        final MobPose pose;
        final long captureTime;

        PoseCacheEntry(MobPose pose) {
            this.pose = pose;
            this.captureTime = System.currentTimeMillis();
        }
    }

    public static void storePose(int entityId, MobPose pose) {
        if (pose != null) {
            CAPTURED_POSES.put(entityId, new PoseCacheEntry(pose));
        }
    }

    public static MobPose getPose(int entityId) {
        PoseCacheEntry entry = CAPTURED_POSES.get(entityId);
        if (entry == null) return null;

        long age = System.currentTimeMillis() - entry.captureTime;
        if (age > 5000) {
            CAPTURED_POSES.remove(entityId);
            return null;
        }

        return entry.pose;
    }

    public static MobPose getAndRemovePose(int entityId) {
        PoseCacheEntry entry = CAPTURED_POSES.remove(entityId);
        return entry != null ? entry.pose : null;
    }

    public static void cleanup() {
        if (CAPTURED_POSES.isEmpty()) return;

        long now = System.currentTimeMillis();
        CAPTURED_POSES.entrySet().removeIf(entry -> now - entry.getValue().captureTime > 5000);
    }

    public static class MobPose {
        private final Map<RagdollPart, PartPose> partPoses;

        public MobPose(Map<RagdollPart, PartPose> partPoses) {
            this.partPoses = partPoses;
        }

        public PartPose getPose(RagdollPart part) {
            return partPoses.getOrDefault(part, new PartPose(0, 0, 0));
        }

        public Quat4f getRotationQuaternion(RagdollPart part) {
            PartPose pose = getPose(part);

            float xRot = pose.xRot;
            float yRot = pose.yRot;
            float zRot = pose.zRot;

            if (part == RagdollPart.LEFT_ARM || part == RagdollPart.RIGHT_ARM) {
                xRot = -pose.xRot;
            }

            Quaternionf q = new Quaternionf().rotateXYZ(xRot, yRot, zRot);
            return new Quat4f(q.x, q.y, q.z, q.w);
        }
    }

    public static class PartPose {
        public final float xRot;
        public final float yRot;
        public final float zRot;

        public PartPose(float xRot, float yRot, float zRot) {
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
        }
    }
}
