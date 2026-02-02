package com.raiiiden.ragdollified;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side only: Captures model poses from rendered entities
 */
public class MobPoseCapture {

    // Store poses with timestamp (entityId -> PoseCacheEntry)
    private static final Map<Integer, PoseCacheEntry> CAPTURED_POSES = new ConcurrentHashMap<>();

    private static class PoseCacheEntry {
        final MobPose pose;
        final long captureTime;

        PoseCacheEntry(MobPose pose) {
            this.pose = pose;
            this.captureTime = System.currentTimeMillis();
        }
    }

    /**
     * Captures the current pose of a mob from its rendered model
     */
    public static void capturePose(int entityId, EntityModel<?> model) {
        if (model == null) return;

        try {
            MobPose pose = null;

            if (model instanceof HumanoidModel<?> humanoidModel) {
                pose = captureHumanoidPose(humanoidModel);
            } else if (model instanceof CreeperModel<?> creeperModel) {
                pose = captureCreeperPose(creeperModel);
            }

            if (pose != null) {
                CAPTURED_POSES.put(entityId, new PoseCacheEntry(pose));
            }
        } catch (Exception e) {
            // Silently fail
        }
    }

    /**
     * Gets the captured pose for a mob (doesn't remove it)
     * Poses are kept for 5 seconds after capture
     */
    public static MobPose getPose(int entityId) {
        PoseCacheEntry entry = CAPTURED_POSES.get(entityId);
        if (entry == null) return null;

        // Check if pose is too old (5 seconds)
        long age = System.currentTimeMillis() - entry.captureTime;
        if (age > 5000) {
            CAPTURED_POSES.remove(entityId);
            return null;
        }

        return entry.pose;
    }

    /**
     * Gets and removes the captured pose for a mob (for backward compatibility)
     */
    public static MobPose getAndRemovePose(int entityId) {
        PoseCacheEntry entry = CAPTURED_POSES.remove(entityId);
        return entry != null ? entry.pose : null;
    }

    /**
     * Cleanup old poses
     */
    public static void cleanup() {
        if (CAPTURED_POSES.isEmpty()) return;

        long now = System.currentTimeMillis();
        CAPTURED_POSES.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().captureTime;
            return age > 5000; // Remove poses older than 5 seconds
        });
    }

    private static MobPose captureHumanoidPose(HumanoidModel<?> model) {
        Map<RagdollPart, PartPose> poses = new HashMap<>();

        poses.put(RagdollPart.TORSO, capturePartPose(model.body));
        poses.put(RagdollPart.HEAD, capturePartPose(model.head));
        poses.put(RagdollPart.LEFT_ARM, capturePartPose(model.leftArm));
        poses.put(RagdollPart.RIGHT_ARM, capturePartPose(model.rightArm));
        poses.put(RagdollPart.LEFT_LEG, capturePartPose(model.leftLeg));
        poses.put(RagdollPart.RIGHT_LEG, capturePartPose(model.rightLeg));

        return new MobPose(poses);
    }

    private static MobPose captureCreeperPose(CreeperModel<?> model) {
        Map<RagdollPart, PartPose> poses = new HashMap<>();

        ModelPart root = model.root();
        poses.put(RagdollPart.TORSO, capturePartPose(root.getChild("body")));
        poses.put(RagdollPart.HEAD, capturePartPose(root.getChild("head")));
        poses.put(RagdollPart.LEFT_ARM, capturePartPose(root.getChild("left_front_leg")));
        poses.put(RagdollPart.RIGHT_ARM, capturePartPose(root.getChild("right_front_leg")));
        poses.put(RagdollPart.LEFT_LEG, capturePartPose(root.getChild("left_hind_leg")));
        poses.put(RagdollPart.RIGHT_LEG, capturePartPose(root.getChild("right_hind_leg")));

        return new MobPose(poses);
    }

    private static PartPose capturePartPose(ModelPart part) {
        if (part == null) return new PartPose(0, 0, 0);
        return new PartPose(part.xRot, part.yRot, part.zRot);
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
                xRot += (float) Math.PI;
            }

            Quaternionf q = new Quaternionf()
                    .rotateXYZ(xRot, yRot, zRot);

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