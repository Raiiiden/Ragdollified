package com.raiiiden.ragdollified;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// Where each part of a humanoid rig sits on a living entity, matching buildHumanoid's numbers.
// Used by both sides so a limb off a living mob spawns where its ragdoll would have put it.
public final class LimbAnchors {

    private LimbAnchors() {}

    // buildHumanoid works at a fixed reference size rather than the entity's own scale: only baby
    // state changes it. See createRagdollBodies, which picks the same offsets.
    private static final float ADULT_TORSO_HEIGHT = 1.2f;
    private static final float BABY_TORSO_HEIGHT = 0.6f;

    // Local offset from the torso centre in the rig's frame, before body yaw.
    public static Vector3f localOffset(RagdollPart part, boolean isBaby) {
        float bs = isBaby ? 0.5f : 1.0f;
        // Head scale varies per mob and only the client knows it; a uniform baby is close enough here.
        float hd = bs;
        switch (part) {
            case HEAD:      return new Vector3f(0f, 0.375f * bs + 0.25f * hd, 0f);
            case LEFT_LEG:  return new Vector3f(-0.11875f * bs, -0.75f * bs, 0f);
            case RIGHT_LEG: return new Vector3f(0.11875f * bs, -0.75f * bs, 0f);
            // An arm cube is centred level with the middle of the chest, six pixels out from the
            // midline, which is where a severed one has to appear for it to leave from the shoulder.
            case LEFT_ARM:  return new Vector3f(-0.375f * bs, 0f, 0f);
            case RIGHT_ARM: return new Vector3f(0.375f * bs, 0f, 0f);
            case TORSO:
            default:        return new Vector3f(0f, 0f, 0f);
        }
    }

    // Collision half extents of a part, matching the boxes the factory builds.
    public static Vector3f halfExtents(RagdollPart part, boolean isBaby) {
        float bs = isBaby ? 0.5f : 1.0f;
        float hd = bs;
        switch (part) {
            case HEAD:      return new Vector3f(0.25f * hd, 0.25f * hd, 0.25f * hd);
            case LEFT_LEG:
            case RIGHT_LEG:
            case LEFT_ARM:
            case RIGHT_ARM: return new Vector3f(0.125f * bs, 0.375f * bs, 0.125f * bs);
            case TORSO:
            default:        return new Vector3f(0.25f * bs, 0.375f * bs, 0.15f * bs);
        }
    }

    // The rig's base orientation for an entity: yaw only, from the body rotation rather than head yaw.
    public static Quaternionf baseRotation(LivingEntity entity) {
        float bodyYaw = entity.getVisualRotationYInDegrees();
        return new Quaternionf().rotateXYZ(0f, (float) Math.toRadians(180.0 - bodyYaw), 0f);
    }

    // World-space centre of a part on a living entity.
    public static Vec3 worldAnchor(LivingEntity entity, RagdollPart part) {
        boolean isBaby = entity.isBaby();
        Vector3f local = localOffset(part, isBaby);
        baseRotation(entity).transform(local);
        double torsoHeight = isBaby ? BABY_TORSO_HEIGHT : ADULT_TORSO_HEIGHT;
        return new Vec3(
                entity.getX() + local.x,
                entity.getY() + torsoHeight + local.y,
                entity.getZ() + local.z);
    }

    // Push for a limb coming off a standing body: outward and slightly up, scaled by the blow.
    // direction is the blow's travel direction, or null for a straight drop.
    public static Vec3 detachImpulse(LivingEntity entity, RagdollPart part, Vec3 direction, double strength) {
        Vector3f local = localOffset(part, entity.isBaby());
        Vector3f outward = new Vector3f(local.x, 0f, local.z);
        if (outward.lengthSquared() < 1.0e-6f) {
            outward.set(0f, 0f, 1f);
        }
        outward.normalize();
        baseRotation(entity).transform(outward);
        Vec3 push = new Vec3(outward.x, 0.0, outward.z).scale(strength * 0.5).add(0.0, strength * 0.35, 0.0);
        if (direction != null && direction.lengthSqr() > 1.0e-6) {
            push = push.add(direction.normalize().scale(strength));
        }
        return push;
    }
}
