package com.raiiiden.ragdollified;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.function.Function;

/**
 * Shared static utility for creating ragdoll rigid bodies and joints.
 * Used by both ClientRagdoll and ServerRagdollPhysics so physics matches exactly.
 */
public final class RagdollBodyFactory {

    private RagdollBodyFactory() {}

    public enum BodyProfile {
        DEFAULT,
        COW,
        PIG,
        SHEEP,
        CHICKEN,
        CAT
    }

    // ===========================
    // Entry point
    // ===========================

    /**
     * Build all bodies and joints for a ragdoll, adding them to the provided lists and world.
     *
     * @param world        dynamics world to add bodies/constraints to
     * @param parts        output list that receives the 6 rigid bodies
     * @param joints       output list that receives the 5 joint constraints
     * @param modelType    shape layout (HUMANOID, CREEPER, QUADRUPED, CHICKEN)
     * @param pos          spawn centre (already Y-offset and jitter applied by caller)
     * @param baseQuat     spawn orientation
     * @param scale        body-size multiplier
     * @param initialVel   initial linear velocity for every part
     * @param capturedPose per-part rotation offsets captured before death (may be null)
     */
    public static void build(DiscreteDynamicsWorld world,
                             List<RigidBody> parts, List<TypedConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose) {
        build(world, parts, joints, modelType, pos, baseQuat, scale, initialVel, capturedPose, false);
    }

    public static void build(DiscreteDynamicsWorld world,
                             List<RigidBody> parts, List<TypedConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose,
                             boolean isBabyCow) {
        build(world, parts, joints, modelType, pos, baseQuat, scale, initialVel, capturedPose,
                isBabyCow ? BodyProfile.COW : BodyProfile.DEFAULT, isBabyCow, false);
    }

    public static void build(DiscreteDynamicsWorld world,
                             List<RigidBody> parts, List<TypedConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose,
                             BodyProfile bodyProfile, boolean isBaby, boolean babyBigHead) {
        // Bat/bee physics is authored at the vanilla model's natural pixel size (matching the
        // unscaled BatModel/BeeModel the renderer draws), so keep their body scale at 1.0
        // instead of the bbHeight-derived scale used for generic quadrupeds.
        float bodyScale = (bodyProfile == BodyProfile.DEFAULT
                && modelType != MobModelHelper.ModelType.BAT
                && modelType != MobModelHelper.ModelType.BEE) ? scale : 1.0f;

        Quaternionf q = new Quaternionf(baseQuat.x, baseQuat.y, baseQuat.z, baseQuat.w);
        Function<Vector3f, Vector3f> worldOffset = local -> {
            org.joml.Vector3f tmp = new org.joml.Vector3f(local.x, local.y, local.z);
            q.transform(tmp);
            Vector3f r = new Vector3f(tmp.x, tmp.y, tmp.z);
            r.add(pos);
            return r;
        };

        switch (modelType) {
            case CREEPER:
                buildCreeper(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose);
                break;
            case QUADRUPED:
            case CHICKEN:
                buildQuadruped(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose,
                        modelType == MobModelHelper.ModelType.CHICKEN, bodyProfile, isBaby);
                break;
            case BAT:
                buildBat(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose);
                break;
            case BEE:
                buildBee(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose, isBaby);
                break;
            default:
                buildHumanoid(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose, worldOffset, isBaby, babyBigHead);
                break;
        }

        buildJoints(world, parts, joints, modelType, bodyScale, bodyProfile, isBaby, babyBigHead);
    }

    // ===========================
    // Body builders
    // ===========================

    private static void buildHumanoid(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                      Vector3f pos, Quat4f baseQuat, float scale,
                                      Vector3f vel, MobPoseCapture.MobPose pose,
                                      Function<Vector3f, Vector3f> worldOffset, boolean isBaby, boolean babyBigHead) {
        // Humanoid bodies are authored at a fixed reference size (scale param is unused for
        // adults). Baby scaling matches what vanilla does per-mob: mobs whose vanilla model
        // enlarges the baby head (zombie/husk/piglin/drowned/zombie-villager — HumanoidModel
        // scaleHead=true → head 0.75, body 0.5) pass babyBigHead=true; mobs that scale
        // uniformly (plain villagers via VillagerRenderer#scale) pass false → head 0.5 too.
        float bs = isBaby ? 0.5f : 1.0f;                       // torso, arms, legs
        float hd = isBaby ? (babyBigHead ? 0.75f : 0.5f) : 1.0f; // head
        // Head sits on top of the torso: torso half-height (0.4) + head half-height (0.2)
        // minus the same 0.05 neck overlap the adult layout used (0.4+0.2-0.05 = 0.55).
        float headOffY = 0.4f*bs + 0.2f*hd - 0.05f*bs;
        Quat4f lArmRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_ARM))  : baseQuat;
        Quat4f rArmRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_ARM)) : baseQuat;
        Vector3f lArmPos = pose != null
                ? calcPos(pos, baseQuat, new Vector3f(-0.35f*bs, 0.22f*bs, 0f), lArmRot, new Vector3f(0f, -0.35f*bs, 0f))
                : worldOffset.apply(new Vector3f(-0.35f*bs, -0.13f*bs, 0f));
        Vector3f rArmPos = pose != null
                ? calcPos(pos, baseQuat, new Vector3f( 0.35f*bs, 0.22f*bs, 0f), rArmRot, new Vector3f(0f, -0.35f*bs, 0f))
                : worldOffset.apply(new Vector3f( 0.35f*bs, -0.13f*bs, 0f));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.25f*bs, 0.4f*bs,  0.15f*bs)), pos,                                              baseQuat, 8*bs, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.2f*hd,  0.2f*hd,  0.2f*hd)),  worldOffset.apply(new Vector3f(0f, headOffY, 0f)),   baseQuat, 4*hd, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.15f*bs, 0.45f*bs, 0.15f*bs)), worldOffset.apply(new Vector3f(-0.1f*bs,-0.75f*bs,0f)), baseQuat, 6*bs, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.15f*bs, 0.45f*bs, 0.15f*bs)), worldOffset.apply(new Vector3f( 0.1f*bs,-0.75f*bs,0f)), baseQuat, 6*bs, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.1f*bs,  0.35f*bs, 0.1f*bs)),  lArmPos, lArmRot, 4*bs, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.1f*bs,  0.35f*bs, 0.1f*bs)),  rArmPos, rArmRot, 4*bs, vel));
    }

    private static void buildCreeper(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                     Vector3f pos, Quat4f baseQuat, float s,
                                     Vector3f vel, MobPoseCapture.MobPose pose) {
        Quat4f torsoRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.TORSO))     : baseQuat;
        Quat4f headRot  = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.HEAD))      : baseQuat;
        Quat4f flRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_ARM))  : baseQuat;
        Quat4f frRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_ARM)) : baseQuat;
        Quat4f blRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_LEG))  : baseQuat;
        Quat4f brRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_LEG)) : baseQuat;
        Vector3f headPos = calcPos(pos, torsoRot, new Vector3f(0f, 0.5f*s, 0f), headRot, new Vector3f(0f, 0.125f*s, 0f));
        Vector3f flPos  = calcPos(pos, torsoRot, new Vector3f( 0.11f*s,-0.3f*s,-0.22f*s), flRot, new Vector3f(0f,-0.255f*s,0f));
        Vector3f frPos  = calcPos(pos, torsoRot, new Vector3f(-0.11f*s,-0.3f*s,-0.22f*s), frRot, new Vector3f(0f,-0.255f*s,0f));
        Vector3f blPos  = calcPos(pos, torsoRot, new Vector3f( 0.11f*s,-0.3f*s, 0.22f*s), blRot, new Vector3f(0f,-0.255f*s,0f));
        Vector3f brPos  = calcPos(pos, torsoRot, new Vector3f(-0.11f*s,-0.3f*s, 0.22f*s), brRot, new Vector3f(0f,-0.255f*s,0f));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.3f*s, 0.5f*s,  0.3f*s)),   pos,     torsoRot, 10*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.25f*s,0.25f*s, 0.25f*s)),  headPos, headRot,   4*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.12f*s,0.3f*s,  0.12f*s)),  flPos,   flRot,     3*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.12f*s,0.3f*s,  0.12f*s)),  frPos,   frRot,     3*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.12f*s,0.3f*s,  0.12f*s)),  blPos,   blRot,     3*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(0.12f*s,0.3f*s,  0.12f*s)),  brPos,   brRot,     3*s, vel));
    }

    /**
     * Bat. One body per wing (tip included, rendered as the wing's rigid child), plus torso and
     * head. The two leg slots are unused by the bat model, so they hold tiny hidden stubs welded
     * inside the torso — just enough to fill the six-body skeleton. Physics is authored at the
     * model's 0.35 render scale (BatRenderer#scale) so the ragdoll matches the live mob's size.
     */
    private static void buildBat(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                 Vector3f pos, Quat4f baseQuat, float s,
                                 Vector3f vel, MobPoseCapture.MobPose pose) {
        final float ns = s * 0.35f; // BatRenderer draws the model at 0.35×
        Quat4f torsoRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.TORSO))     : baseQuat;
        Quat4f headRot  = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.HEAD))      : baseQuat;
        Quat4f lwRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_ARM))  : baseQuat;
        Quat4f rwRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_ARM)) : baseQuat;
        Quat4f lsRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_LEG))  : baseQuat;
        Quat4f rsRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_LEG)) : baseQuat;
        Vector3f headPos = calcPos(pos, torsoRot, new Vector3f(0f, 0.625f*ns, 0f),                 headRot, new Vector3f());
        Vector3f lwPos   = calcPos(pos, torsoRot, new Vector3f(-0.4375f*ns, 0.0625f*ns, 0.125f*ns), lwRot,  new Vector3f());
        Vector3f rwPos   = calcPos(pos, torsoRot, new Vector3f( 0.4375f*ns, 0.0625f*ns, 0.125f*ns), rwRot,  new Vector3f());
        Vector3f lsPos   = calcPos(pos, torsoRot, new Vector3f(-0.06f*ns, -0.1f*ns, 0f),             lsRot,  new Vector3f());
        Vector3f rsPos   = calcPos(pos, torsoRot, new Vector3f( 0.06f*ns, -0.1f*ns, 0f),             rsRot,  new Vector3f());
        parts.add(makePart(world, new BoxShape(new Vector3f(0.1875f*ns, 0.375f*ns,  0.1875f*ns)), pos,     torsoRot, 4*ns,    vel)); // TORSO
        parts.add(makePart(world, new BoxShape(new Vector3f(0.1875f*ns, 0.1875f*ns, 0.1875f*ns)), headPos, headRot,  2*ns,    vel)); // HEAD
        parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*ns,   0.05f*ns,   0.05f*ns)),   lsPos,   lsRot,    0.3f*ns, vel)); // LEFT_LEG  stub
        parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*ns,   0.05f*ns,   0.05f*ns)),   rsPos,   rsRot,    0.3f*ns, vel)); // RIGHT_LEG stub
        parts.add(makePart(world, new BoxShape(new Vector3f(0.3125f*ns, 0.5f*ns,    0.05f*ns)),   lwPos,   lwRot,    1.5f*ns, vel)); // LEFT_ARM  = left wing (+tip)
        parts.add(makePart(world, new BoxShape(new Vector3f(0.3125f*ns, 0.5f*ns,    0.05f*ns)),   rwPos,   rwRot,    1.5f*ns, vel)); // RIGHT_ARM = right wing (+tip)
    }

    /**
     * Bee. Torso = body box (carries the antennae + stinger in the render), a small hidden head
     * stub at the front for the neck joint, and the two flat wings in the arm slots. The bee's
     * legs are tiny flat strips that stay attached to the body (rendered on the torso), so the
     * two leg slots hold hidden stubs welded inside the torso. Baby bees are built (and rendered)
     * at half scale to match vanilla.
     */
    private static void buildBee(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                 Vector3f pos, Quat4f baseQuat, float s,
                                 Vector3f vel, MobPoseCapture.MobPose pose, boolean isBaby) {
        final float ns = isBaby ? s * 0.5f : s;
        Quat4f torsoRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.TORSO))     : baseQuat;
        Quat4f headRot  = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.HEAD))      : baseQuat;
        Quat4f lwRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_ARM))  : baseQuat;
        Quat4f rwRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_ARM)) : baseQuat;
        Quat4f lsRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_LEG))  : baseQuat;
        Quat4f rsRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_LEG)) : baseQuat;
        Vector3f headPos = calcPos(pos, torsoRot, new Vector3f(0f, 0.1f*ns, -0.28f*ns),           headRot, new Vector3f());
        Vector3f lwPos   = calcPos(pos, torsoRot, new Vector3f(-0.414f*ns, 0.219f*ns, -0.079f*ns), lwRot,  new Vector3f());
        Vector3f rwPos   = calcPos(pos, torsoRot, new Vector3f( 0.414f*ns, 0.219f*ns, -0.079f*ns), rwRot,  new Vector3f());
        Vector3f lsPos   = calcPos(pos, torsoRot, new Vector3f(-0.06f*ns, -0.05f*ns, 0f),           lsRot,  new Vector3f());
        Vector3f rsPos   = calcPos(pos, torsoRot, new Vector3f( 0.06f*ns, -0.05f*ns, 0f),           rsRot,  new Vector3f());
        parts.add(makePart(world, new BoxShape(new Vector3f(0.21875f*ns, 0.21875f*ns, 0.3125f*ns)), pos,     torsoRot, 4*ns,    vel)); // TORSO = body
        parts.add(makePart(world, new BoxShape(new Vector3f(0.08f*ns,    0.08f*ns,    0.08f*ns)),    headPos, headRot,  0.5f*ns, vel)); // HEAD stub
        parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*ns,    0.05f*ns,    0.05f*ns)),    lsPos,   lsRot,    0.3f*ns, vel)); // LEFT_LEG  stub
        parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*ns,    0.05f*ns,    0.05f*ns)),    rsPos,   rsRot,    0.3f*ns, vel)); // RIGHT_LEG stub
        parts.add(makePart(world, new BoxShape(new Vector3f(0.28f*ns,    0.02f*ns,    0.1875f*ns)),  lwPos,   lwRot,    0.5f*ns, vel)); // LEFT_ARM  = left wing
        parts.add(makePart(world, new BoxShape(new Vector3f(0.28f*ns,    0.02f*ns,    0.1875f*ns)),  rwPos,   rwRot,    0.5f*ns, vel)); // RIGHT_ARM = right wing
    }

    private static void buildQuadruped(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                       Vector3f torsoPos, Quat4f baseQuat, float s,
                                       Vector3f vel, MobPoseCapture.MobPose pose,
                                       boolean isChicken, BodyProfile bodyProfile, boolean isBaby) {
        Quat4f torsoRot = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.TORSO))     : baseQuat;
        Quat4f headRot  = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.HEAD))      : baseQuat;
        Quat4f flRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_ARM))  : baseQuat;
        Quat4f frRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_ARM)) : baseQuat;
        Quat4f blRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.LEFT_LEG))  : baseQuat;
        Quat4f brRot    = pose != null ? mul(baseQuat, pose.getRotationQuaternion(RagdollPart.RIGHT_LEG)) : baseQuat;
        if (isChicken) {
            float b = isBaby ? 0.5f : 1.0f;
            float hb = isBaby ? 0.5f : 1.0f;
            float headZ = (isBaby ? -0.28f : -0.23f) * hb * s;
            Vector3f headPos = calcPos(torsoPos, torsoRot, new Vector3f(0f, 0.16f*hb*s, headZ), headRot, new Vector3f(0f, 0.06f*hb*s, 0f));
            Vector3f llPos   = calcPos(torsoPos, torsoRot, new Vector3f( 0.1f*b*s,-0.2f*b*s,0f), blRot,   new Vector3f(0f,-0.07f*b*s,0f));
            Vector3f rlPos   = calcPos(torsoPos, torsoRot, new Vector3f(-0.1f*b*s,-0.2f*b*s,0f), brRot,   new Vector3f(0f,-0.07f*b*s,0f));
            Vector3f lwPos   = calcPos(torsoPos, torsoRot, new Vector3f( 0.25f*b*s,0.05f*b*s,0f), flRot,  new Vector3f(-0.03f*b*s,0f,0f));
            Vector3f rwPos   = calcPos(torsoPos, torsoRot, new Vector3f(-0.25f*b*s,0.05f*b*s,0f), frRot,  new Vector3f( 0.03f*b*s,0f,0f));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.2f*b*s,  0.22f*b*s, 0.15f*b*s)), torsoPos, torsoRot, 5*s, vel));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.12f*s, 0.18f*s, 0.12f*s)), headPos,  headRot,  2*s, vel));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.06f*b*s, 0.18f*b*s, 0.06f*b*s)), llPos,    blRot,    1*s, vel));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.06f*b*s, 0.18f*b*s, 0.06f*b*s)), rlPos,    brRot,    1*s, vel));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*b*s, 0.18f*b*s, 0.12f*b*s)), lwPos,    flRot,    1*s, vel));
            parts.add(makePart(world, new BoxShape(new Vector3f(0.05f*b*s, 0.18f*b*s, 0.12f*b*s)), rwPos,    frRot,    1*s, vel));
        } else if (isBaby) {
            QuadLayout l = layoutFor(bodyProfile, true);
            buildProfiledQuadruped(world, parts, torsoPos, torsoRot, headRot, flRot, frRot, blRot, brRot, s, vel, l);
        } else {
            QuadLayout l = layoutFor(bodyProfile, false);
            buildProfiledQuadruped(world, parts, torsoPos, torsoRot, headRot, flRot, frRot, blRot, brRot, s, vel, l);
        }
    }

    private record QuadLayout(
            float headY, float headZ, float headCenterZ, float headX, float headHalfY, float headHalfZ,
            float legX, float legY, float frontZ, float hindZ, float legCenterY, float legHalfX, float legHalfY, float legHalfZ,
            float torsoHalfX, float torsoHalfY, float torsoHalfZ,
            float torsoMass, float headMass, float legMass,
            // Hind-leg vertical layout, separated from the front legs so mobs whose front
            // and hind legs differ in length or attach height (cats) place both correctly.
            // Cow/pig/sheep pass the same values as their front legs → identical behaviour.
            float hindLegY, float hindLegCenterY, float hindLegHalfY) {}

    private static QuadLayout layoutFor(BodyProfile profile, boolean baby) {
        BodyProfile p = profile == BodyProfile.DEFAULT ? BodyProfile.COW : profile;
        float b = baby ? 0.5f : 1.0f;
        float hb = baby ? 0.5f : 1.0f;
        return switch (p) {
            case PIG -> new QuadLayout(
                    (baby ? 0.06f : 0.12f), -0.37f * hb, -0.25f * hb, 0.25f, 0.25f, 0.31f,
                    0.1875f * b, -0.25f * b, 0.3125f * b, 0.4375f * b, -0.075f * b,
                    0.105f * b, 0.16f * b, 0.105f * b,
                    0.28f * b, 0.23f * b, 0.44f * b,
                    6f, 2.5f, 1.6f,
                    -0.25f * b, -0.075f * b, 0.16f * b);
            case SHEEP -> new QuadLayout(
                    (baby ? 0.125f : 0.25f), -0.425f * hb, -0.16f * hb, 0.1875f, 0.1875f, 0.25f,
                    0.1875f * b, -0.1875f * b, 0.3125f * b, 0.4375f * b, -0.235f * b,
                    0.105f * b, 0.34f * b, 0.105f * b,
                    0.23f * b, 0.17f * b, 0.44f * b,
                    8f, 2.5f, 2.2f,
                    -0.1875f * b, -0.235f * b, 0.34f * b);
            // All values are the natural OcelotModel geometry × 0.8 (CatRenderer#scale draws the
            // model at 0.8×), so the physics bodies match the rendered cat/ocelot size. Unlike
            // cow/pig/sheep (scaleHead=false → baby head stays 1.0×), OcelotModel has
            // scaleHead=true with babyHeadScale=2, so a kitten's head renders at 1.5/2 = 0.75×
            // while its body renders at 1.0/2 = 0.5×. The head box + offsets use that 0.75 factor
            // (chf) so the physics head matches the rendered head instead of staying adult-sized.
            case CAT -> {
                float chf = baby ? 0.75f : 1.0f;
                yield new QuadLayout(
                        0.1f * b, -0.4f * b, -0.125f * chf, 0.125f * chf, 0.1f * chf, 0.125f * chf,
                        0.0576f * b, 0.145f * b, 0.25f * b, 0.3f * b, -0.25f * b,
                        0.05f * b, 0.25f * b, 0.05f * b,
                        0.1f * b, 0.15f * b, 0.4f * b,
                        3f, 1f, 0.6f,
                        -0.05f * b, -0.15f * b, 0.15f * b);
            }
            case COW, DEFAULT -> new QuadLayout(
                    (baby ? 0.11f : 0.22f), -0.56f * hb, -0.15f * hb, 0.3125f, 0.28125f, 0.1875f,
                    0.25f * b, -0.3125f * b, 0.4375f * b, 0.375f * b, -0.235f * b,
                    0.105f * b, 0.34f * b, 0.105f * b,
                    0.34f * b, 0.29f * b, 0.50f * b,
                    10f, 3f, 3f,
                    -0.3125f * b, -0.235f * b, 0.34f * b);
            default -> throw new IllegalStateException("Unexpected quadruped profile " + p);
        };
    }

    private static void buildProfiledQuadruped(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                               Vector3f torsoPos, Quat4f torsoRot, Quat4f headRot,
                                               Quat4f flRot, Quat4f frRot, Quat4f blRot, Quat4f brRot,
                                               float s, Vector3f vel, QuadLayout l) {
        Vector3f headPos = calcPos(torsoPos, torsoRot, new Vector3f(0f, l.headY*s, l.headZ*s), headRot, new Vector3f(0f, 0f, l.headCenterZ*s));
        Vector3f flPos   = calcPos(torsoPos, torsoRot, new Vector3f( l.legX*s, l.legY*s, -l.frontZ*s), flRot, new Vector3f(0f, l.legCenterY*s, 0f));
        Vector3f frPos   = calcPos(torsoPos, torsoRot, new Vector3f(-l.legX*s, l.legY*s, -l.frontZ*s), frRot, new Vector3f(0f, l.legCenterY*s, 0f));
        Vector3f blPos   = calcPos(torsoPos, torsoRot, new Vector3f( l.legX*s, l.hindLegY*s,  l.hindZ*s), blRot, new Vector3f(0f, l.hindLegCenterY*s, 0f));
        Vector3f brPos   = calcPos(torsoPos, torsoRot, new Vector3f(-l.legX*s, l.hindLegY*s,  l.hindZ*s), brRot, new Vector3f(0f, l.hindLegCenterY*s, 0f));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.torsoHalfX*s, l.torsoHalfY*s, l.torsoHalfZ*s)), torsoPos, torsoRot, l.torsoMass*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.headX*s, l.headHalfY*s, l.headHalfZ*s)), headPos, headRot, l.headMass*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.legHalfX*s, l.hindLegHalfY*s, l.legHalfZ*s)), blPos, blRot, l.legMass*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.legHalfX*s, l.hindLegHalfY*s, l.legHalfZ*s)), brPos, brRot, l.legMass*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.legHalfX*s, l.legHalfY*s, l.legHalfZ*s)), flPos, flRot, l.legMass*s, vel));
        parts.add(makePart(world, new BoxShape(new Vector3f(l.legHalfX*s, l.legHalfY*s, l.legHalfZ*s)), frPos, frRot, l.legMass*s, vel));
    }

    // ===========================
    // Joint builders
    // ===========================

    private static void buildJoints(DiscreteDynamicsWorld world, List<RigidBody> parts,
                                    List<TypedConstraint> joints,
                                    MobModelHelper.ModelType modelType, float s, BodyProfile bodyProfile,
                                    boolean isBaby, boolean babyBigHead) {
        if (parts.size() < 6) return;
        RigidBody torso = parts.get(RagdollPart.TORSO.index);
        RigidBody head  = parts.get(RagdollPart.HEAD.index);
        RigidBody lLeg  = parts.get(RagdollPart.LEFT_LEG.index);
        RigidBody rLeg  = parts.get(RagdollPart.RIGHT_LEG.index);
        RigidBody lArm  = parts.get(RagdollPart.LEFT_ARM.index);
        RigidBody rArm  = parts.get(RagdollPart.RIGHT_ARM.index);
        Transform tTorso = wt(torso), tHead = wt(head), tLLeg = wt(lLeg), tRLeg = wt(rLeg), tLArm = wt(lArm), tRArm = wt(rArm);
        Function<Vector3f, Vector3f> tw = local -> {
            Vector3f out = rotQ(tTorso.getRotation(new Quat4f()), local);
            out.add(tTorso.origin);
            return out;
        };
        switch (modelType) {
            case CREEPER:   buildCreeperJoints  (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tw, s); break;
            case QUADRUPED: buildQuadJoints     (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s, bodyProfile, isBaby); break;
            case CHICKEN:   buildChickenJoints  (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            case BAT:       buildBatJoints      (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            case BEE:       buildBeeJoints      (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            default:        buildHumanoidJoints (world, joints, torso, head, lLeg, rLeg, lArm, rArm, tHead, tLLeg, tRLeg, tLArm, tRArm, tw, s, isBaby, babyBigHead); break;
        }
    }

    private static void buildHumanoidJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody lLeg, RigidBody rLeg, RigidBody lArm, RigidBody rArm,
            Transform tHead, Transform tLLeg, Transform tRLeg, Transform tLArm, Transform tRArm,
            Function<Vector3f, Vector3f> tw, float s, boolean isBaby, boolean babyBigHead) {
        // Anchor offsets must track the scaled extents used in buildHumanoid so baby joints
        // sit at the shrunken part boundaries. Head uses the head scale (hd — 0.75 for big-
        // head mobs, else the body scale); torso/arms/legs use the body scale (bs). Angular
        // limits (degrees) are unchanged.
        float bs = isBaby ? 0.5f : 1.0f;
        float hd = isBaby ? (babyBigHead ? 0.75f : 0.5f) : 1.0f;
        Vector3f torsoTop = tw.apply(new Vector3f(0f, 0.4f*bs, 0f));
        Vector3f headBot  = rotQ(tHead.getRotation(new Quat4f()), new Vector3f(0f,-0.2f*hd,0f)); headBot.add(tHead.origin);
        joints.add(joint(world, torso, head, mid(torsoTop,headBot), v(0,0,0), v(0,0,0), v(-30,-20,-30), v(30,50,30)));
        Vector3f lHip = tw.apply(new Vector3f(-0.1f*bs,-0.40f*bs,0f));
        Vector3f lLegTop = rotQ(tLLeg.getRotation(new Quat4f()), new Vector3f(0f,0.45f*bs,0f)); lLegTop.add(tLLeg.origin);
        joints.add(joint(world, torso, lLeg, mid(lHip,lLegTop), v(-0.05f*bs,0f,-0.05f*bs), v(0.05f*bs,0f,0.05f*bs), v(-10,0,-10), v(40,0,10)));
        Vector3f rHip = tw.apply(new Vector3f(0.1f*bs,-0.40f*bs,0f));
        Vector3f rLegTop = rotQ(tRLeg.getRotation(new Quat4f()), new Vector3f(0f,0.45f*bs,0f)); rLegTop.add(tRLeg.origin);
        joints.add(joint(world, torso, rLeg, mid(rHip,rLegTop), v(-0.05f*bs,0f,-0.05f*bs), v(0.05f*bs,0f,0.05f*bs), v(-10,0,-10), v(40,0,10)));
        Vector3f lSh = tw.apply(new Vector3f(-0.35f*bs,0.22f*bs,0f));
        Vector3f lAT = rotQ(tLArm.getRotation(new Quat4f()), new Vector3f(0f,0.35f*bs,0f)); lAT.add(tLArm.origin);
        joints.add(joint(world, torso, lArm, mid(lSh,lAT), v(-0.02f*bs,-0.02f*bs,-0.02f*bs), v(0.02f*bs,0.02f*bs,0.02f*bs), v(-80,-30,-40), v(80,30,40)));
        Vector3f rSh = tw.apply(new Vector3f(0.35f*bs,0.22f*bs,0f));
        Vector3f rAT = rotQ(tRArm.getRotation(new Quat4f()), new Vector3f(0f,0.35f*bs,0f)); rAT.add(tRArm.origin);
        joints.add(joint(world, torso, rArm, mid(rSh,rAT), v(-0.02f*bs,-0.02f*bs,-0.02f*bs), v(0.02f*bs,0.02f*bs,0.02f*bs), v(-80,-30,-40), v(80,30,40)));
    }

    private static void buildCreeperJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody fl, RigidBody fr, RigidBody bl, RigidBody br,
            Transform tHead, Function<Vector3f, Vector3f> tw, float s) {
        Vector3f headBot = rotQ(tHead.getRotation(new Quat4f()), new Vector3f(0f,-0.25f*s,0f)); headBot.add(tHead.origin);
        joints.add(joint(world, torso, head, mid(tw.apply(new Vector3f(0f,0.5f*s,0f)), headBot), v(0,0,0), v(0,0,0), v(-20,-20,-20), v(20,20,20)));
        float ll = 14;
        Vector3f lin = v(0f,0f,0f), liu = v(0f,0f,0f), al = v(-ll,-5,-ll), au = v(ll,5,ll);
        joints.add(joint(world, torso, fl, tw.apply(new Vector3f( 0.11f*s,-0.375f*s,-0.22f*s)), lin, liu, al, au));
        joints.add(joint(world, torso, fr, tw.apply(new Vector3f(-0.11f*s,-0.375f*s,-0.22f*s)), lin, liu, al, au));
        joints.add(joint(world, torso, bl, tw.apply(new Vector3f( 0.11f*s,-0.375f*s, 0.22f*s)), lin, liu, al, au));
        joints.add(joint(world, torso, br, tw.apply(new Vector3f(-0.11f*s,-0.375f*s, 0.22f*s)), lin, liu, al, au));
    }

    private static void buildQuadJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody fl, RigidBody fr, RigidBody hl, RigidBody hr,
            Transform tHead, Transform tFL, Transform tFR, Transform tHL, Transform tHR,
            Function<Vector3f, Vector3f> tw, float s, BodyProfile bodyProfile, boolean isBaby) {
        float ll = 40, hl2 = 35;
        QuadLayout q = layoutFor(bodyProfile, isBaby);
        Vector3f torsoFront = tw.apply(new Vector3f(0f, q.headY*s, q.headZ*s + 0.08f*s));
        Vector3f headBack = rotQ(tHead.getRotation(new Quat4f()), new Vector3f(0f,0f,-q.headCenterZ*s)); headBack.add(tHead.origin);
        joints.add(joint(world, torso, head, mid(torsoFront,headBack), v(0,0,0), v(0,0,0), v(-hl2,-25,-20), v(hl2,25,20)));
        Vector3f lin = v(-0.02f,-0.02f,-0.02f), liu = v(0.02f,0.02f,0.02f);
        Vector3f angL = v(-ll,-10,-10), angU = v(ll,10,10);
        joints.add(joint(world, torso, fl, tw.apply(new Vector3f( q.legX*s, q.legY*s,-q.frontZ*s)), lin, liu, angL, angU));
        joints.add(joint(world, torso, fr, tw.apply(new Vector3f(-q.legX*s, q.legY*s,-q.frontZ*s)), lin, liu, angL, angU));
        joints.add(joint(world, torso, hl, tw.apply(new Vector3f( q.legX*s, q.hindLegY*s, q.hindZ*s)), lin, liu, angL, angU));
        joints.add(joint(world, torso, hr, tw.apply(new Vector3f(-q.legX*s, q.hindLegY*s, q.hindZ*s)), lin, liu, angL, angU));
    }

    private static void buildChickenJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody lw, RigidBody rw, RigidBody ll, RigidBody rl,
            Transform tHead, Transform tLW, Transform tRW, Transform tLL, Transform tRL,
            Function<Vector3f, Vector3f> tw, float s) {
        float wl = 28, legl = 35, hl = 30;
        Vector3f torsoTop = tw.apply(new Vector3f(0f,0.25f*s,-0.15f*s));
        Vector3f headBot  = rotQ(tHead.getRotation(new Quat4f()), new Vector3f(0f,-0.15f*s,0f)); headBot.add(tHead.origin);
        joints.add(joint(world, torso, head, mid(torsoTop,headBot), v(0,0,0), v(0,0,0), v(-hl,-20,-hl), v(hl,20,hl)));
        Vector3f wi = v(-0.01f,-0.01f,-0.01f), wu = v(0.01f,0.01f,0.01f);
        joints.add(joint(world, torso, lw, tw.apply(new Vector3f( 0.22f*s,0.1f*s,0f)), wi, wu, v(-wl,-8,-wl), v(wl,8,wl)));
        joints.add(joint(world, torso, rw, tw.apply(new Vector3f(-0.22f*s,0.1f*s,0f)), wi, wu, v(-wl,-8,-wl), v(wl,8,wl)));
        Vector3f li = v(-0.02f,-0.02f,-0.02f), lu = v(0.02f,0.02f,0.02f);
        joints.add(joint(world, torso, ll, tw.apply(new Vector3f( 0.1f*s,-0.2f*s,0f)), li, lu, v(-legl,-8,-8), v(legl,8,8)));
        joints.add(joint(world, torso, rl, tw.apply(new Vector3f(-0.1f*s,-0.2f*s,0f)), li, lu, v(-legl,-8,-8), v(legl,8,8)));
    }

    // Bat/bee joints anchor at body-origin midpoints (not torso-local offsets) so they stay
    // correct regardless of the model-specific build scale (bat 0.35×, baby bee 0.5×) — the
    // shared `s` passed here is the unscaled body scale and wouldn't match those bodies.
    private static void buildBatJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody lWing, RigidBody rWing, RigidBody lStub, RigidBody rStub,
            Transform tHead, Transform tLWing, Transform tRWing, Transform tLStub, Transform tRStub,
            Function<Vector3f, Vector3f> tw, float s) {
        Vector3f torsoO = tw.apply(v(0f, 0f, 0f));
        joints.add(joint(world, torso, head, mid(torsoO, tHead.origin), v(0,0,0), v(0,0,0), v(-40,-40,-40), v(40,40,40)));
        // wings hinge off the torso with a wide flap range
        joints.add(joint(world, torso, lWing, mid(torsoO, tLWing.origin), v(0,0,0), v(0,0,0), v(-70,-60,-70), v(70,60,70)));
        joints.add(joint(world, torso, rWing, mid(torsoO, tRWing.origin), v(0,0,0), v(0,0,0), v(-70,-60,-70), v(70,60,70)));
        // unused leg stubs welded to the torso (rigid, invisible)
        joints.add(joint(world, torso, lStub, tLStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
        joints.add(joint(world, torso, rStub, tRStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
    }

    private static void buildBeeJoints(DiscreteDynamicsWorld world, List<TypedConstraint> joints,
            RigidBody torso, RigidBody head, RigidBody lWing, RigidBody rWing, RigidBody lStub, RigidBody rStub,
            Transform tHead, Transform tLWing, Transform tRWing, Transform tLStub, Transform tRStub,
            Function<Vector3f, Vector3f> tw, float s) {
        Vector3f torsoO = tw.apply(v(0f, 0f, 0f));
        joints.add(joint(world, torso, head, mid(torsoO, tHead.origin), v(0,0,0), v(0,0,0), v(-30,-30,-30), v(30,30,30)));
        joints.add(joint(world, torso, lWing, mid(torsoO, tLWing.origin), v(0,0,0), v(0,0,0), v(-60,-50,-60), v(60,50,60)));
        joints.add(joint(world, torso, rWing, mid(torsoO, tRWing.origin), v(0,0,0), v(0,0,0), v(-60,-50,-60), v(60,50,60)));
        // unused leg stubs welded to the torso (legs stay rendered on the body instead)
        joints.add(joint(world, torso, lStub, tLStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
        joints.add(joint(world, torso, rStub, tRStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
    }

    // ===========================
    // Low-level helpers
    // ===========================

    public static RigidBody makePart(DiscreteDynamicsWorld world, CollisionShape shape,
                                     Vector3f position, Quat4f rotation,
                                     float mass, Vector3f initialVel) {
        float effectiveMass = mass * (float) RagdollifiedConfig.get(RagdollifiedConfig.MASS_SCALE);
        Transform t = new Transform();
        t.setIdentity();
        t.origin.set(position);
        t.setRotation(rotation);

        Vector3f inertia = new Vector3f();
        shape.calculateLocalInertia(effectiveMass, inertia);

        RigidBodyConstructionInfo info = new RigidBodyConstructionInfo(
                effectiveMass, new DefaultMotionState(t), shape, inertia);
        info.linearDamping  = (float) RagdollifiedConfig.get(RagdollifiedConfig.LINEAR_DAMPING);
        info.angularDamping = (float) RagdollifiedConfig.get(RagdollifiedConfig.ANGULAR_DAMPING);
        info.restitution    = (float) RagdollifiedConfig.get(RagdollifiedConfig.RESTITUTION);
        info.friction       = (float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION);
        info.additionalDamping = true;

        RigidBody body = new RigidBody(info);

        // No per-body initial-velocity clamp here — the ragdoll's per-tick clamp (90 m/s
        // linear, 8 rad/s angular) handles excess. Capping at construction would otherwise
        // erase the entity's death-time momentum (sprint speed alone is ~5.6 m/s and can
        // exceed any low cap the moment knockback or fall is added).
        body.setLinearVelocity(new Vector3f(initialVel));

        body.setDamping((float) RagdollifiedConfig.get(RagdollifiedConfig.LINEAR_DAMPING),
                (float) RagdollifiedConfig.get(RagdollifiedConfig.ANGULAR_DAMPING));
        body.setSleepingThresholds(0.3f, 0.3f);
        // Disable Bullet's auto-deactivation. Bullet sleeps bodies that stay below
        // the velocity threshold for ~2s, AND once asleep gravity stops being applied
        // to them — which leaves ragdolls floating mid-air after they wake from a
        // floor break (zero velocity → auto-sleep before gravity can build speed).
        // Our manual settle detection (freezeBodies) is the authoritative way to
        // deactivate ragdolls; we don't need or want Bullet's version on top of it.
        body.setActivationState(CollisionObject.DISABLE_DEACTIVATION);
        // CCD disabled: at our worst-case substep (1/40s) and velocity-clamped speed
        // (~8 m/s = 0.2 blocks/substep), bodies move less than the thinnest block
        // collision face — they can't tunnel. CCD's per-substep swept-sphere test
        // against world geometry was adding 1-3ms to physics time when many bodies
        // were active. Re-enable if you see ragdolls phasing through thin floors.

        world.addRigidBody(body);
        return body;
    }

    public static RigidBody makeStaticBody(DiscreteDynamicsWorld world, CollisionShape shape, Transform t) {
        RigidBody rb = new RigidBody(new RigidBodyConstructionInfo(0f, new DefaultMotionState(t), shape, new Vector3f()));
        rb.setCollisionFlags(rb.getCollisionFlags() | CollisionFlags.STATIC_OBJECT);
        world.addRigidBody(rb);
        return rb;
    }

    public static Generic6DofConstraint joint(DiscreteDynamicsWorld world,
                                              RigidBody a, RigidBody b, Vector3f anchor,
                                              Vector3f linL, Vector3f linU,
                                              Vector3f angLDeg, Vector3f angUDeg) {
        Transform ta = wt(a), tb = wt(b);
        Transform localA = new Transform(); localA.setIdentity();
        localA.origin.set(toLocal(ta, anchor));
        Transform localB = new Transform(); localB.setIdentity();
        localB.origin.set(toLocal(tb, anchor));

        Generic6DofConstraint c = new Generic6DofConstraint(a, b, localA, localB, true);
        c.setLinearLowerLimit(linL);
        c.setLinearUpperLimit(linU);
        c.setAngularLowerLimit(rad(angLDeg));
        c.setAngularUpperLimit(rad(angUDeg));

        a.activate(); b.activate();
        world.addConstraint(c, true);
        return c;
    }

    // ===========================
    // Math utilities (package-visible for tests, private use)
    // ===========================

    public static Quat4f mul(Quat4f q1, Quat4f q2) {
        float w = q1.w*q2.w - q1.x*q2.x - q1.y*q2.y - q1.z*q2.z;
        float x = q1.w*q2.x + q1.x*q2.w + q1.y*q2.z - q1.z*q2.y;
        float y = q1.w*q2.y - q1.x*q2.z + q1.y*q2.w + q1.z*q2.x;
        float z = q1.w*q2.z + q1.x*q2.y - q1.y*q2.x + q1.z*q2.w;
        return new Quat4f(x, y, z, w);
    }

    public static Vector3f rotQ(Quat4f q, Vector3f v) {
        Vector3f qv = new Vector3f(q.x, q.y, q.z);
        Vector3f t = new Vector3f(2f*(qv.y*v.z-qv.z*v.y), 2f*(qv.z*v.x-qv.x*v.z), 2f*(qv.x*v.y-qv.y*v.x));
        Vector3f r = new Vector3f(v);
        Vector3f qt = new Vector3f(t); qt.scale(q.w); r.add(qt);
        r.add(new Vector3f(qv.y*t.z-qv.z*t.y, qv.z*t.x-qv.x*t.z, qv.x*t.y-qv.y*t.x));
        return r;
    }

    public static Vector3f calcPos(Vector3f parentPos, Quat4f parentRot,
                                   Vector3f localOff, Quat4f partRot, Vector3f partCenter) {
        Vector3f joint = new Vector3f(parentPos); joint.add(rotQ(parentRot, localOff));
        Vector3f r = new Vector3f(joint); r.add(rotQ(partRot, partCenter));
        return r;
    }

    private static Transform wt(RigidBody b) {
        Transform t = new Transform(); b.getMotionState().getWorldTransform(t); return t;
    }

    private static Vector3f toLocal(Transform wt, Vector3f worldPt) {
        Vector3f d = new Vector3f(worldPt); d.sub(wt.origin);
        return rotQ(new Quat4f(-wt.getRotation(new Quat4f()).x, -wt.getRotation(new Quat4f()).y,
                               -wt.getRotation(new Quat4f()).z,  wt.getRotation(new Quat4f()).w), d);
    }

    private static Vector3f mid(Vector3f a, Vector3f b) {
        return new Vector3f((a.x+b.x)*0.5f, (a.y+b.y)*0.5f, (a.z+b.z)*0.5f);
    }

    /** Converts a degree-triple to radians in-place and returns a new Vector3f. */
    private static Vector3f rad(Vector3f deg) {
        return new Vector3f((float)Math.toRadians(deg.x), (float)Math.toRadians(deg.y), (float)Math.toRadians(deg.z));
    }

    private static Vector3f v(float x, float y, float z) { return new Vector3f(x, y, z); }
}
