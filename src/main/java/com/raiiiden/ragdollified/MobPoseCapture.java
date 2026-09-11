package com.raiiiden.ragdollified;

import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Last-drawn pose per part so the ragdoll starts from the animation. PartTransform is the full
// render matrix (preferred); PartPose is local Euler angles, the fallback for uncaptured parts.
public class MobPoseCapture {
    private static final Map<Integer, PoseCacheEntry> CAPTURED_POSES = new ConcurrentHashMap<>();

    // Undoes the renderer's 180-degree Z turn to convert a render matrix into a body orientation.
    private static final Quat4f MODEL_FLIP = new Quat4f(0f, 0f, 1f, 0f);

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

    // Age of the stored pose in milliseconds, or Long.MAX_VALUE when there is none. The death path
    // uses this to decide whether the last drawn frame is recent enough to trust.
    public static long getPoseAgeMs(int entityId) {
        PoseCacheEntry entry = CAPTURED_POSES.get(entityId);
        return entry == null ? Long.MAX_VALUE : System.currentTimeMillis() - entry.captureTime;
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

    // A part's drawn frame, expressed against the entity origin (its feet) on world axes.
    public static final class PartTransform {
        // Where the centre of the part's cubes sat, in blocks from the entity origin, the point a
        // physics body for that limb belongs at, not the pivot it swings on.
        public final Vector3f offset;
        // The orientation a physics body needs to reproduce the drawn part, flip already undone.
        public final Quat4f rotation;
        // Half the size of the drawn part, in blocks, with the renderer's scaling already in it.
        public final Vector3f halfExtents;

        public PartTransform(Vector3f offset, Quat4f rotation, Vector3f halfExtents) {
            this.offset = offset;
            this.rotation = rotation;
            this.halfExtents = halfExtents;
        }

        // Build from an entity-relative render matrix, measuring the point in the part's frame.
        // Null when that frame is degenerate.
        public static PartTransform fromRelative(org.joml.Matrix4f relative,
                                                 float centreX, float centreY, float centreZ,
                                                 float halfX, float halfY, float halfZ) {
            org.joml.Vector3f t = relative.transformPosition(
                    new org.joml.Vector3f(centreX, centreY, centreZ));
            // Unnormalized reader on purpose: the matrix carries mob/baby scale, which skews a normalized read.
            // The renderer's -1,-1,1 flip is a Z half turn, so it survives either reader.
            Quaternionf r = relative.getUnnormalizedRotation(new Quaternionf());
            Quat4f phys = new Quat4f(r.x, r.y, r.z, r.w);
            phys.mul(phys, MODEL_FLIP);

            float len = (float) Math.sqrt(
                    phys.x * phys.x + phys.y * phys.y + phys.z * phys.z + phys.w * phys.w);
            if (!Float.isFinite(len) || len < 1.0e-6f
                    || !Float.isFinite(t.x) || !Float.isFinite(t.y) || !Float.isFinite(t.z)) {
                // A part scaled away to nothing leaves no usable frame; the caller drops it and the
                // slot keeps its authored placement rather than taking a degenerate one.
                return null;
            }
            // A body wants a unit quaternion; a short one skews the basis it is built from.
            phys.scale(1f / len);

            // The size has to carry the same scaling the position did, or a villager (drawn at
            // 0.9375) would get bodies a sixteenth too big for the mesh they are placed on.
            org.joml.Vector3f scale = relative.getScale(new org.joml.Vector3f());
            Vector3f half = new Vector3f(
                    Math.abs(halfX * scale.x), Math.abs(halfY * scale.y), Math.abs(halfZ * scale.z));
            return new PartTransform(new Vector3f(t.x, t.y, t.z), phys, half);
        }
    }

    public static class MobPose {
        private final Map<RagdollPart, PartPose> partPoses;
        private final Map<RagdollPart, PartTransform> partTransforms;
        // Entity origin the transforms are placed against. Null until the spawn path binds the pose
        // to the position the ragdoll is actually being built at.
        private final Vector3f origin;

        public MobPose(Map<RagdollPart, PartPose> partPoses) {
            this(partPoses, null, null);
        }

        public MobPose(Map<RagdollPart, PartPose> partPoses,
                       Map<RagdollPart, PartTransform> partTransforms,
                       Vector3f origin) {
            this.partPoses = partPoses == null ? new EnumMap<>(RagdollPart.class) : partPoses;
            this.partTransforms = partTransforms == null ? new EnumMap<>(RagdollPart.class) : partTransforms;
            this.origin = origin;
        }

        // The captured transforms are stored against the entity origin at capture time; the ragdoll is
        // built at the origin recorded at death. Binding here keeps the pose cache itself position-free.
        public MobPose withOrigin(Vector3f spawnOrigin) {
            return new MobPose(partPoses, partTransforms, spawnOrigin);
        }

        public PartPose getPose(RagdollPart part) {
            return partPoses.getOrDefault(part, new PartPose(0, 0, 0));
        }

        public PartTransform getTransform(RagdollPart part) {
            return partTransforms.get(part);
        }

        // A transform is only usable for placement once it has an origin to be measured from.
        public boolean hasPlacement(RagdollPart part) {
            return origin != null && partTransforms.containsKey(part);
        }

        public Vector3f getOrigin() {
            return origin;
        }

        // The orientation to build this part's body with: the captured matrix when there is one, the
        // captured Euler angles otherwise, and the plain body rotation when the part was never seen.
        public Quat4f rotationFor(RagdollPart part, Quat4f baseQuat) {
            PartTransform transform = partTransforms.get(part);
            if (transform != null) return new Quat4f(transform.rotation);

            PartPose pose = partPoses.get(part);
            if (pose == null) return baseQuat;
            return applyLocalRotation(baseQuat, pose.xRot, pose.yRot, pose.zRot);
        }

        // Half size of the part as drawn, or null if not captured; humanoid proportions vary per mob.
        public Vector3f extentsFor(RagdollPart part) {
            PartTransform transform = partTransforms.get(part);
            if (transform == null) return null;
            Vector3f half = transform.halfExtents;
            // A part with no cubes of its own measures zero; there is nothing to build from.
            return half.x <= 1.0e-4f || half.y <= 1.0e-4f || half.z <= 1.0e-4f ? null : half;
        }

        // World centre of the part's body, straight off the frame it was drawn in. Null when this
        // part has no usable transform.
        public Vector3f centerFor(RagdollPart part) {
            PartTransform transform = partTransforms.get(part);
            if (transform == null || origin == null) return null;

            Vector3f result = new Vector3f(origin);
            result.add(transform.offset);
            return result;
        }

        // Deprecated: Euler angles lose the pivot and composition order; use rotationFor(RagdollPart, Quat4f).
        // Kept because addons build against it.
        @Deprecated
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

    // ModelPart composes Z, Y, X in model space (physics frame turned 180 about Z); conjugating by
    // that turn makes a captured angle mean the same thing to the physics body.
    public static Quat4f applyLocalRotation(Quat4f base, float xRot, float yRot, float zRot) {
        Quaternionf combined = new Quaternionf(base.x, base.y, base.z, base.w);
        Quaternionf flip = new Quaternionf().rotationZ((float) Math.PI);
        combined.mul(flip)
                .mul(new Quaternionf().rotationZYX(zRot, yRot, xRot))
                .mul(new Quaternionf(flip).conjugate());
        return new Quat4f(combined.x, combined.y, combined.z, combined.w);
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
