package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.physics.BodyProperties;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.function.Function;

// Shared builder for ragdoll rigid bodies and joints, used by both ClientRagdoll and
// ServerRagdollPhysics so the two simulate identically.
public final class RagdollBodyFactory {

    private RagdollBodyFactory() {}

    public enum BodyProfile {
        DEFAULT,
        COW,
        PIG,
        SHEEP,
        CHICKEN,
        CAT,
        HORSE,
        DONKEY,
        MULE,
        WOLF,
        FOX,
        PANDA,
        GOAT,
        POLAR_BEAR,
        TURTLE,
        CAMEL,
        LLAMA,
        RABBIT,
        FROG,
        HOGLIN,
        SNIFFER,
        RAVAGER,
        PHANTOM,
        PARROT,
        SLIME,
        MAGMA_CUBE,
        SILVERFISH,
        ENDERMITE,
        ALLAY,
        STRIDER,
        SNOW_GOLEM,
        BLAZE,
        SPIDER,
        CAVE_SPIDER,
        SHULKER,
        GHAST,
        VEX,
        WARDEN,
        GUARDIAN,
        ELDER_GUARDIAN,
        SQUID,
        DOLPHIN,
        AXOLOTL,
        // One profile per small fish: they share the FISH rig and differ only in dimensions.
        COD,
        SALMON,
        TROPICAL_FISH,
        PUFFERFISH,
        TADPOLE,
        WITHER,
        ENDER_DRAGON
    }

    // Entry point

    // Build every body and joint for a ragdoll: parts gets 6 rigid bodies, joints 5 constraints. pos is
    // the spawn centre, capturedPose the per-part rotation offsets taken before death, or null.
    public static void build(PhysicsWorld world,
                             List<PhysicsBody> parts, List<PhysicsConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose) {
        build(world, parts, joints, modelType, pos, baseQuat, scale, initialVel, capturedPose, false);
    }

    public static void build(PhysicsWorld world,
                             List<PhysicsBody> parts, List<PhysicsConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose,
                             boolean isBabyCow) {
        build(world, parts, joints, modelType, pos, baseQuat, scale, initialVel, capturedPose,
                isBabyCow ? BodyProfile.COW : BodyProfile.DEFAULT, isBabyCow, false);
    }

    public static void build(PhysicsWorld world,
                             List<PhysicsBody> parts, List<PhysicsConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose,
                             BodyProfile bodyProfile, boolean isBaby, boolean babyBigHead) {
        build(world, parts, joints, modelType, pos, baseQuat, scale, initialVel, capturedPose,
                bodyProfile, isBaby, babyBigHead, null);
    }

    // genericRig: the measured skeleton, required for ModelType.GENERIC and ignored otherwise.
    public static void build(PhysicsWorld world,
                             List<PhysicsBody> parts, List<PhysicsConstraint> joints,
                             MobModelHelper.ModelType modelType,
                             Vector3f pos, Quat4f baseQuat, float scale,
                             Vector3f initialVel, MobPoseCapture.MobPose capturedPose,
                             BodyProfile bodyProfile, boolean isBaby, boolean babyBigHead,
                             GenericRig genericRig) {
        if (modelType == MobModelHelper.ModelType.GENERIC) {
            if (genericRig == null) {
                throw new IllegalArgumentException("GENERIC ragdoll built without a measured rig");
            }
            buildGeneric(world, parts, joints, genericRig, pos, baseQuat, scale, initialVel);
            return;
        }
        // Bat, bee and creeper physics is authored at the vanilla model's natural pixel size, matching the
        // unscaled models the renderer draws, so their body scale stays 1.0.
        float bodyScale = (bodyProfile == BodyProfile.DEFAULT
                && modelType != MobModelHelper.ModelType.BAT
                && modelType != MobModelHelper.ModelType.BEE
                && modelType != MobModelHelper.ModelType.CREEPER) ? scale : 1.0f;
        if (modelType == MobModelHelper.ModelType.PHANTOM) {
            // Packet scale is bbHeight/1.8. Phantom dimensions grow by 2/9 per size while
            // the renderer grows by .15; recover the integer size and its render scale.
            int phantomSize = Math.max(0, Math.round((scale * 3.6f - 1f) * 4.5f));
            bodyScale = 1f + .15f * phantomSize;
        }

        switch (modelType) {
            case CREEPER:
                buildCreeper(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose);
                break;
            case QUADRUPED:
            case WOLF:
            case FOX:
            case PANDA:
            case GOAT:
            case POLAR_BEAR:
            case CHICKEN:
                buildQuadruped(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose,
                        modelType == MobModelHelper.ModelType.CHICKEN, bodyProfile, isBaby);
                break;
            case EQUINE:
                buildEquine(world, parts, pos, baseQuat, initialVel, capturedPose, bodyProfile, isBaby);
                break;
            case IRON_GOLEM:
                buildIronGolem(world, parts, pos, baseQuat, initialVel);
                break;
            case TURTLE:
                buildTurtle(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case ENDERMAN:
                buildEnderman(world, parts, pos, baseQuat, initialVel);
                break;
            case CAMEL:
                buildCamel(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case LLAMA:
                buildLlama(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case RABBIT:
                buildRabbit(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case FROG:
                buildFrog(world, parts, pos, baseQuat, initialVel);
                break;
            case HOGLIN:
                buildHoglin(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case SNIFFER:
                buildSniffer(world, parts, pos, baseQuat, initialVel, isBaby);
                break;
            case RAVAGER:
                buildRavager(world, parts, pos, baseQuat, initialVel);
                break;
            case PHANTOM:
                buildPhantom(world, parts, pos, baseQuat, initialVel, bodyScale);
                break;
            case PARROT:
                buildParrot(world, parts, pos, baseQuat, initialVel);
                break;
            case SLIME:
            case MAGMA_CUBE:
                buildCubeMob(world, parts, pos, baseQuat, initialVel);
                break;
            case SILVERFISH: buildSilverfish(world,parts,pos,baseQuat,initialVel); break;
            case ENDERMITE: buildEndermite(world,parts,pos,baseQuat,initialVel); break;
            case ALLAY: buildAllay(world,parts,pos,baseQuat,initialVel); break;
            case STRIDER: buildStrider(world,parts,pos,baseQuat,initialVel,isBaby); break;
            case SNOW_GOLEM: buildSnowGolem(world,parts,pos,baseQuat,initialVel); break;
            case BLAZE: buildBlaze(world,parts,pos,baseQuat,initialVel); break;
            case SPIDER: buildSpider(world,parts,pos,baseQuat,initialVel,bodyProfile==BodyProfile.CAVE_SPIDER?.7f:1f); break;
            case SHULKER: buildShulker(world,parts,pos,baseQuat,initialVel); break;
            case GHAST: buildGhast(world,parts,pos,baseQuat,initialVel); break;
            case VEX: buildVex(world,parts,pos,baseQuat,initialVel); break;
            case WARDEN: buildWarden(world,parts,pos,baseQuat,initialVel); break;
            case GUARDIAN: buildGuardian(world,parts,pos,baseQuat,initialVel,guardianScale(bodyProfile)); break;
            case SQUID: buildSquid(world,parts,pos,baseQuat,initialVel); break;
            case DOLPHIN: buildDolphin(world,parts,pos,baseQuat,initialVel); break;
            case AXOLOTL: buildAxolotl(world,parts,pos,baseQuat,initialVel); break;
            case FISH: buildFish(world,parts,pos,baseQuat,initialVel,bodyProfile); break;
            case WITHER: buildWither(world,parts,pos,baseQuat,initialVel); break;
            case ENDER_DRAGON: buildEnderDragon(world,parts,pos,baseQuat,initialVel); break;
            case BAT:
                buildBat(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose);
                break;
            case BEE:
                buildBee(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose, isBaby);
                break;
            default:
                buildHumanoid(world, parts, pos, baseQuat, bodyScale, initialVel, capturedPose, isBaby, babyBigHead);
                break;
        }

        applyPartWeights(parts,bodyProfile);

        buildJoints(world, parts, joints, modelType, bodyScale, bodyProfile, isBaby, babyBigHead);
    }

    // Scale each body's authored mass by its configured per-part weight, here rather than at ~40 call
    // sites. Inertia is recomputed: a stale tensor would spin a heavier part as if it were still light.
    private static void applyPartWeights(List<PhysicsBody> parts,BodyProfile profile) {
        for (int i = 0; i < parts.size(); i++) {
            RagdollPart part = RagdollPart.byIndex(i);
            if (part == null) continue;
            // Extended anatomies own indexes that happen to overlap the legacy humanoid slots;
            // do not accidentally apply arm/leg config weights to arbitrary tentacles/legs.
            if (profile == BodyProfile.GHAST && i > 0) continue;
            if ((profile == BodyProfile.SPIDER || profile == BodyProfile.CAVE_SPIDER) && i > 1) continue;
            // Squid tentacles and the dragon's neck/tail/hind legs occupy limb indexes without being
            // the limbs those weights describe.
            if (profile == BodyProfile.SQUID && i > 0) continue;
            if (profile == BodyProfile.ENDER_DRAGON && i > 5) continue;
            float multiplier = RagdollifiedConfig.getPartWeightMultiplier(part);
            if (multiplier == 1f) continue;
            PhysicsBody body = parts.get(i);
            float invMass = body.getInvMass();
            if (invMass <= 0f) continue; // static/kinematic: no mass to scale
            // setMass rebuilds the inertia tensor from the shape as well: a stale tensor would spin
            // a heavier part as if it were still light, which is the bug this comment used to guard.
            body.setMass((1f / invMass) * multiplier);
        }
    }

    // Build a skeleton from a measured rig, one box and joint per part in a single forward pass.
    // Skips part weights, bakes joints at the built pose, and scales mass with volume.
    private static void buildGeneric(PhysicsWorld world, List<PhysicsBody> parts,
                                     List<PhysicsConstraint> joints, GenericRig rig,
                                     Vector3f pos, Quat4f baseQuat, float scale, Vector3f initialVel) {
        Quaternionf q = new Quaternionf(baseQuat.x, baseQuat.y, baseQuat.z, baseQuat.w);
        Function<Vector3f, Vector3f> tw = local -> {
            org.joml.Vector3f tmp = new org.joml.Vector3f(local.x, local.y, local.z);
            q.transform(tmp);
            Vector3f out = new Vector3f(tmp.x, tmp.y, tmp.z);
            out.add(pos);
            return out;
        };

        for (GenericRig.Part part : rig.parts) {
            Vector3f half = v(Math.max(MIN_GENERIC_HALF_EXTENT, part.hx() * scale),
                    Math.max(MIN_GENERIC_HALF_EXTENT, part.hy() * scale),
                    Math.max(MIN_GENERIC_HALF_EXTENT, part.hz() * scale));
            float mass = Math.max(GENERIC_MIN_MASS,
                    GENERIC_DENSITY * (2 * half.x) * (2 * half.y) * (2 * half.z));
            parts.add(makePart(world, box(world, half),
                    tw.apply(v(part.ox() * scale, part.oy() * scale, part.oz() * scale)),
                    baseQuat, mass, initialVel));
        }

        int firstJoint = joints.size();
        for (int i = 1; i < rig.parts.size(); i++) {
            GenericRig.Part part = rig.parts.get(i);
            PhysicsBody child = parts.get(i);
            PhysicsBody parent = parts.get(Math.max(0, part.parentIndex()));
            Vector3f anchor = tw.apply(v(part.jx() * scale, part.jy() * scale, part.jz() * scale));
            joints.add(joint(world, parent, child, anchor,
                    GENERIC_LINEAR_SLOP_LOWER, GENERIC_LINEAR_SLOP_UPPER,
                    GENERIC_ANGULAR_LOWER, GENERIC_ANGULAR_UPPER, false, true));
        }

        applyRelaxation(joints, firstJoint, 1f);
        applyAerodynamicDrag(parts);
    }

    // Nothing measured off a model is allowed to be thinner than this. A part one pixel deep gives a
    // box 3 cm thick, which tunnels through a floor between two steps however good the CCD is.
    private static final float MIN_GENERIC_HALF_EXTENT = 0.03f;
    // Chosen so a vanilla-sized humanoid torso lands near the 8 kg the authored rig gives it, which
    // keeps a measured mob falling at the same rate as the ones next to it.
    private static final float GENERIC_DENSITY = 90f;
    private static final float GENERIC_MIN_MASS = 0.15f;
    // One middle-ground limit for every joint of an unauthored rig: loose enough to fold, tight enough to hold.
    private static final Vector3f GENERIC_ANGULAR_LOWER = new Vector3f(-45, -35, -35);
    private static final Vector3f GENERIC_ANGULAR_UPPER = new Vector3f(45, 35, 35);
    private static final Vector3f GENERIC_LINEAR_SLOP_LOWER = new Vector3f(-.015f, -.015f, -.015f);
    private static final Vector3f GENERIC_LINEAR_SLOP_UPPER = new Vector3f(.015f, .015f, .015f);

    // Body builders

    private static void buildHumanoid(PhysicsWorld world, List<PhysicsBody> parts,
                                      Vector3f pos, Quat4f baseQuat, float scale,
                                      Vector3f vel, MobPoseCapture.MobPose pose,
                                      boolean isBaby, boolean babyBigHead) {
        // Humanoid bodies use a fixed reference size. Baby scaling mirrors vanilla per-mob: models that
        // enlarge the baby head pass babyBigHead=true, uniformly scaled ones pass false.
        float bs = isBaby ? 0.5f : 1.0f;                       // torso, arms, legs
        float hd = isBaby ? (babyBigHead ? 0.75f : 0.5f) : 1.0f; // head
        // Pose all six parts, not just the arms, so the ragdoll keeps its death pose instead of snapping upright.
        Quat4f torsoRot = poseRot(pose, RagdollPart.TORSO, baseQuat);
        Quat4f headRot  = poseRot(pose, RagdollPart.HEAD, baseQuat);
        Quat4f lLegRot  = poseRot(pose, RagdollPart.LEFT_LEG, baseQuat);
        Quat4f rLegRot  = poseRot(pose, RagdollPart.RIGHT_LEG, baseQuat);
        Quat4f lArmRot  = poseRot(pose, RagdollPart.LEFT_ARM, baseQuat);
        Quat4f rArmRot  = poseRot(pose, RagdollPart.RIGHT_ARM, baseQuat);
        // Boxes are the drawn cubes, with the vanilla player's 4x12x4 limbs as the fallback so feet don't spawn in the floor.
        Vector3f authoredLimb = new Vector3f(0.125f*bs, 0.375f*bs, 0.125f*bs);
        Vector3f torsoHalf = poseBox(pose, RagdollPart.TORSO, new Vector3f(0.25f*bs, 0.375f*bs, 0.15f*bs));
        Vector3f headHalf = poseBox(pose, RagdollPart.HEAD, new Vector3f(0.25f*hd, 0.25f*hd, 0.25f*hd));
        Vector3f lLegHalf = poseBox(pose, RagdollPart.LEFT_LEG, authoredLimb);
        Vector3f rLegHalf = poseBox(pose, RagdollPart.RIGHT_LEG, authoredLimb);
        Vector3f lArmHalf = poseBox(pose, RagdollPart.LEFT_ARM, authoredLimb);
        Vector3f rArmHalf = poseBox(pose, RagdollPart.RIGHT_ARM, authoredLimb);
        // The capture gives rotations, not places: only the torso keeps its drawn position, as the root, and every other part hangs from its joint anchor at its drawn angle.
        Vector3f torsoPos = new Vector3f(posePos(pose, RagdollPart.TORSO, pos));
        Vector3f headPos = calcPos(torsoPos, torsoRot, humanoidNeck(torsoHalf), headRot, new Vector3f(0f, headHalf.y, 0f));
        Vector3f lLegPos = calcPos(torsoPos, torsoRot, humanoidHip(torsoHalf, bs, -1f), lLegRot, new Vector3f(0f, -lLegHalf.y, 0f));
        Vector3f rLegPos = calcPos(torsoPos, torsoRot, humanoidHip(torsoHalf, bs, 1f), rLegRot, new Vector3f(0f, -rLegHalf.y, 0f));
        Vector3f lArmPos = calcPos(torsoPos, torsoRot, humanoidShoulder(torsoHalf, lArmHalf, bs, -1f), lArmRot, humanoidArmHang(lArmHalf, bs, -1f));
        Vector3f rArmPos = calcPos(torsoPos, torsoRot, humanoidShoulder(torsoHalf, rArmHalf, bs, 1f), rArmRot, humanoidArmHang(rArmHalf, bs, 1f));
        float lift = liftToFeet(pose, new Vector3f[]{lLegPos, rLegPos}, new Quat4f[]{lLegRot, rLegRot}, new Vector3f[]{lLegHalf, rLegHalf});
        for (Vector3f placed : new Vector3f[]{torsoPos, headPos, lLegPos, rLegPos, lArmPos, rArmPos}) placed.y += lift;
        // Masses follow real body fractions (torso 55%, head 7%, arm 4%, leg 14%, ~32 total) so the torso
        // reaches the floor.
        parts.add(makePart(world, box(world, torsoHalf), torsoPos, torsoRot, 18*bs, vel));
        parts.add(makePart(world, box(world, headHalf), headPos, headRot, 2.4f*hd, vel));
        parts.add(makePart(world, box(world, lLegHalf), lLegPos, lLegRot, 4.6f*bs, vel));
        parts.add(makePart(world, box(world, rLegHalf), rLegPos, rLegRot, 4.6f*bs, vel));
        parts.add(makePart(world, box(world, lArmHalf), lArmPos, lArmRot, 1.4f*bs, vel));
        parts.add(makePart(world, box(world, rArmHalf), rArmPos, rArmRot, 1.4f*bs, vel));
    }

    // Humanoid joint anchors in the torso's frame, shared by buildHumanoid and buildHumanoidJoints so a part and its joint can never disagree; side is -1 left, +1 right.
    private static Vector3f humanoidNeck(Vector3f torsoHalf) {
        return new Vector3f(0f, torsoHalf.y, 0f);
    }

    // Hips sit 1.9 pixels off the midline, the same place the model puts them.
    private static Vector3f humanoidHip(Vector3f torsoHalf, float bs, float side) {
        return new Vector3f(side * 0.11875f * bs, -torsoHalf.y, 0f);
    }

    // The model's shoulder pivot: 5 pixels out and 2 up from the torso centre on a player.
    private static Vector3f humanoidShoulder(Vector3f torsoHalf, Vector3f armHalf, float bs, float side) {
        return new Vector3f(side * (torsoHalf.x + armHalf.x * 0.5f), armHalf.y - 0.125f * bs, 0f);
    }

    // The same shoulder in the arm's own frame: a quarter of the arm's width toward the torso and just under its top.
    private static Vector3f humanoidShoulderOnArm(Vector3f armHalf, float bs, float side) {
        return new Vector3f(-side * armHalf.x * 0.5f, armHalf.y - 0.125f * bs, 0f);
    }

    private static Vector3f humanoidArmHang(Vector3f armHalf, float bs, float side) {
        Vector3f hang = humanoidShoulderOnArm(armHalf, bs, side);
        hang.negate();
        return hang;
    }

    // How far to raise a posed body so no leg starts below the feet it was drawn on: hanging limbs off a pitched torso, as in a crouch, can put a hip lower than the model drew it.
    private static float liftToFeet(MobPoseCapture.MobPose pose, Vector3f[] centres, Quat4f[] rotations, Vector3f[] halves) {
        if (pose == null || pose.getOrigin() == null || centres.length == 0) return 0f;
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < centres.length; i++) lowest = Math.min(lowest, lowestY(centres[i], rotations[i], halves[i]));
        return Math.max(0f, pose.getOrigin().y - lowest);
    }

    // The lowest point of a turned box: its centre less the vertical reach of each of its half axes.
    private static float lowestY(Vector3f centre, Quat4f rotation, Vector3f half) {
        return centre.y - Math.abs(rotQ(rotation, new Vector3f(half.x, 0f, 0f)).y)
                - Math.abs(rotQ(rotation, new Vector3f(0f, half.y, 0f)).y)
                - Math.abs(rotQ(rotation, new Vector3f(0f, 0f, half.z)).y);
    }

    private static void buildCreeper(PhysicsWorld world, List<PhysicsBody> parts,
                                     Vector3f pos, Quat4f baseQuat, float s,
                                     Vector3f vel, MobPoseCapture.MobPose pose) {
        Quat4f torsoRot = poseRot(pose, RagdollPart.TORSO, baseQuat);
        Quat4f headRot  = poseRot(pose, RagdollPart.HEAD, baseQuat);
        Quat4f flRot    = poseRot(pose, RagdollPart.LEFT_ARM, baseQuat);
        Quat4f frRot    = poseRot(pose, RagdollPart.RIGHT_ARM, baseQuat);
        Quat4f blRot    = poseRot(pose, RagdollPart.LEFT_LEG, baseQuat);
        Quat4f brRot    = poseRot(pose, RagdollPart.RIGHT_LEG, baseQuat);
        // As on the humanoid, the capture gives rotations, not places: the torso is the drawn root and every other part hangs from its joint anchor.
        Vector3f torsoPos = new Vector3f(posePos(pose, RagdollPart.TORSO, pos));
        Vector3f legHalf = new Vector3f(CREEPER_LEG_HALF_XZ*s, CREEPER_LEG_HALF_Y*s, CREEPER_LEG_HALF_XZ*s);
        Vector3f legHang = new Vector3f(0f, -legHalf.y, 0f);
        Vector3f headPos = calcPos(torsoPos, torsoRot, creeperNeck(s), headRot, new Vector3f(0f, CREEPER_HEAD_HALF*s, 0f));
        Vector3f flPos = calcPos(torsoPos, torsoRot, creeperHip(s, -1f, -1f), flRot, legHang);
        Vector3f frPos = calcPos(torsoPos, torsoRot, creeperHip(s,  1f, -1f), frRot, legHang);
        Vector3f blPos = calcPos(torsoPos, torsoRot, creeperHip(s, -1f,  1f), blRot, legHang);
        Vector3f brPos = calcPos(torsoPos, torsoRot, creeperHip(s,  1f,  1f), brRot, legHang);
        float lift = liftToFeet(pose, new Vector3f[]{flPos, frPos, blPos, brPos},
                new Quat4f[]{flRot, frRot, blRot, brRot}, new Vector3f[]{legHalf, legHalf, legHalf, legHalf});
        for (Vector3f placed : new Vector3f[]{torsoPos, headPos, flPos, frPos, blPos, brPos}) placed.y += lift;
        // The body cube is the humanoid torso's 8x12x4, so it takes that box; the 0.6-deep one before held a creeper lying down well off the floor.
        parts.add(makePart(world, box(world, new Vector3f(0.25f*s, 0.375f*s, 0.15f*s)), torsoPos, torsoRot, 10*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(CREEPER_HEAD_HALF*s, CREEPER_HEAD_HALF*s, CREEPER_HEAD_HALF*s)), headPos, headRot, 4*s, vel));
        // Body slots follow the capture's: hind pair in the LEG slots, front pair in the ARM slots, which is how the joints, hit mapper and renderer read them; the front pair used to go first.
        parts.add(makePart(world, box(world, new Vector3f(legHalf)), blPos, blRot, 3*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(legHalf)), brPos, brRot, 3*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(legHalf)), flPos, flRot, 3*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(legHalf)), frPos, frRot, 3*s, vel));
    }

    // CreeperModel measured from the body cube's centre: each leg hangs from 2 px out, 6 px down and 4 px fore or aft, the head from the top of the body.
    private static final float CREEPER_HIP_X = 0.125f;
    private static final float CREEPER_HIP_Y = -0.375f;
    private static final float CREEPER_HIP_Z = 0.25f;
    private static final float CREEPER_NECK_Y = 0.375f;
    private static final float CREEPER_HEAD_HALF = 0.25f;
    // A 4x6x4 leg, a hair narrower than drawn so the left and right legs, which the model sets side by side, do not start pressed together.
    private static final float CREEPER_LEG_HALF_XZ = 0.12f;
    private static final float CREEPER_LEG_HALF_Y = 0.1875f;

    // Creeper leg anchors in the torso's frame, shared with buildCreeperJoints: side -1 is left and +1 right, end -1 front and +1 back, matching the slots the pose capture fills (left_front_leg is LEFT_ARM).
    private static Vector3f creeperHip(float s, float side, float end) {
        return new Vector3f(side * CREEPER_HIP_X * s, CREEPER_HIP_Y * s, end * CREEPER_HIP_Z * s);
    }

    private static Vector3f creeperNeck(float s) {
        return new Vector3f(0f, CREEPER_NECK_Y * s, 0f);
    }

    // Bat: one body per wing plus torso and head. The model has no legs, so those slots hold hidden
    // stubs welded inside the torso. Authored at BatRenderer's 0.35 scale to match the live mob.
    private static void buildBat(PhysicsWorld world, List<PhysicsBody> parts,
                                 Vector3f pos, Quat4f baseQuat, float s,
                                 Vector3f vel, MobPoseCapture.MobPose pose) {
        final float ns = s * 0.35f; // BatRenderer draws the model at 0.35×
        Quat4f torsoRot = poseRot(pose, RagdollPart.TORSO, baseQuat);
        Quat4f headRot  = poseRot(pose, RagdollPart.HEAD, baseQuat);
        Quat4f lwRot    = poseRot(pose, RagdollPart.LEFT_ARM, baseQuat);
        Quat4f rwRot    = poseRot(pose, RagdollPart.RIGHT_ARM, baseQuat);
        Quat4f lsRot    = poseRot(pose, RagdollPart.LEFT_LEG, baseQuat);
        Quat4f rsRot    = poseRot(pose, RagdollPart.RIGHT_LEG, baseQuat);
        Vector3f headPos = calcPos(pos, torsoRot, new Vector3f(0f, 0.625f*ns, 0f),                 headRot, new Vector3f());
        Vector3f lwPos   = calcPos(pos, torsoRot, new Vector3f(-0.4375f*ns, 0.0625f*ns, 0.125f*ns), lwRot,  new Vector3f());
        Vector3f rwPos   = calcPos(pos, torsoRot, new Vector3f( 0.4375f*ns, 0.0625f*ns, 0.125f*ns), rwRot,  new Vector3f());
        Vector3f lsPos   = calcPos(pos, torsoRot, new Vector3f(-0.06f*ns, -0.1f*ns, 0f),             lsRot,  new Vector3f());
        Vector3f rsPos   = calcPos(pos, torsoRot, new Vector3f( 0.06f*ns, -0.1f*ns, 0f),             rsRot,  new Vector3f());
        parts.add(makePart(world, box(world, new Vector3f(0.1875f*ns, 0.375f*ns,  0.1875f*ns)), pos,     torsoRot, 4*ns,    vel)); // TORSO
        parts.add(makePart(world, box(world, new Vector3f(0.1875f*ns, 0.1875f*ns, 0.1875f*ns)), headPos, headRot,  2*ns,    vel)); // HEAD
        parts.add(makePart(world, box(world, new Vector3f(0.05f*ns,   0.05f*ns,   0.05f*ns)),   lsPos,   lsRot,    0.3f*ns, vel)); // LEFT_LEG  stub
        parts.add(makePart(world, box(world, new Vector3f(0.05f*ns,   0.05f*ns,   0.05f*ns)),   rsPos,   rsRot,    0.3f*ns, vel)); // RIGHT_LEG stub
        parts.add(makePart(world, box(world, new Vector3f(0.3125f*ns, 0.5f*ns,    0.05f*ns)),   lwPos,   lwRot,    1.5f*ns, vel)); // LEFT_ARM  = left wing (+tip)
        parts.add(makePart(world, box(world, new Vector3f(0.3125f*ns, 0.5f*ns,    0.05f*ns)),   rwPos,   rwRot,    1.5f*ns, vel)); // RIGHT_ARM = right wing (+tip)
    }

    // Bee: torso is the body box with a hidden head stub for the neck joint and the flat wings in the
    // arm slots. Its legs render on the torso, so the leg slots hold hidden stubs. Babies are half scale.
    private static void buildBee(PhysicsWorld world, List<PhysicsBody> parts,
                                 Vector3f pos, Quat4f baseQuat, float s,
                                 Vector3f vel, MobPoseCapture.MobPose pose, boolean isBaby) {
        final float ns = isBaby ? s * 0.5f : s;
        Quat4f torsoRot = poseRot(pose, RagdollPart.TORSO, baseQuat);
        Quat4f headRot  = poseRot(pose, RagdollPart.HEAD, baseQuat);
        Quat4f lwRot    = poseRot(pose, RagdollPart.LEFT_ARM, baseQuat);
        Quat4f rwRot    = poseRot(pose, RagdollPart.RIGHT_ARM, baseQuat);
        Quat4f lsRot    = poseRot(pose, RagdollPart.LEFT_LEG, baseQuat);
        Quat4f rsRot    = poseRot(pose, RagdollPart.RIGHT_LEG, baseQuat);
        Vector3f headPos = calcPos(pos, torsoRot, new Vector3f(0f, 0.1f*ns, -0.28f*ns),           headRot, new Vector3f());
        Vector3f lwPos   = calcPos(pos, torsoRot, new Vector3f(-0.414f*ns, 0.219f*ns, -0.079f*ns), lwRot,  new Vector3f());
        Vector3f rwPos   = calcPos(pos, torsoRot, new Vector3f( 0.414f*ns, 0.219f*ns, -0.079f*ns), rwRot,  new Vector3f());
        Vector3f lsPos   = calcPos(pos, torsoRot, new Vector3f(-0.06f*ns, -0.05f*ns, 0f),           lsRot,  new Vector3f());
        Vector3f rsPos   = calcPos(pos, torsoRot, new Vector3f( 0.06f*ns, -0.05f*ns, 0f),           rsRot,  new Vector3f());
        parts.add(makePart(world, box(world, new Vector3f(0.21875f*ns, 0.21875f*ns, 0.3125f*ns)), pos,     torsoRot, 4*ns,    vel)); // TORSO = body
        parts.add(makePart(world, box(world, new Vector3f(0.08f*ns,    0.08f*ns,    0.08f*ns)),    headPos, headRot,  0.5f*ns, vel)); // HEAD stub
        parts.add(makePart(world, box(world, new Vector3f(0.05f*ns,    0.05f*ns,    0.05f*ns)),    lsPos,   lsRot,    0.3f*ns, vel)); // LEFT_LEG  stub
        parts.add(makePart(world, box(world, new Vector3f(0.05f*ns,    0.05f*ns,    0.05f*ns)),    rsPos,   rsRot,    0.3f*ns, vel)); // RIGHT_LEG stub
        parts.add(makePart(world, box(world, new Vector3f(0.28f*ns,    0.02f*ns,    0.1875f*ns)),  lwPos,   lwRot,    0.5f*ns, vel)); // LEFT_ARM  = left wing
        parts.add(makePart(world, box(world, new Vector3f(0.28f*ns,    0.02f*ns,    0.1875f*ns)),  rwPos,   rwRot,    0.5f*ns, vel)); // RIGHT_ARM = right wing
    }

    private static void buildQuadruped(PhysicsWorld world, List<PhysicsBody> parts,
                                       Vector3f torsoPos, Quat4f baseQuat, float s,
                                       Vector3f vel, MobPoseCapture.MobPose pose,
                                       boolean isChicken, BodyProfile bodyProfile, boolean isBaby) {
        Quat4f torsoRot = poseRot(pose, RagdollPart.TORSO, baseQuat);
        Quat4f headRot  = poseRot(pose, RagdollPart.HEAD, baseQuat);
        Quat4f flRot    = poseRot(pose, RagdollPart.LEFT_ARM, baseQuat);
        Quat4f frRot    = poseRot(pose, RagdollPart.RIGHT_ARM, baseQuat);
        Quat4f blRot    = poseRot(pose, RagdollPart.LEFT_LEG, baseQuat);
        Quat4f brRot    = poseRot(pose, RagdollPart.RIGHT_LEG, baseQuat);
        if (isChicken) {
            float b = isBaby ? 0.5f : 1.0f;
            float hb = isBaby ? 0.5f : 1.0f;
            float headZ = (isBaby ? -0.28f : -0.23f) * hb * s;
            Vector3f headPos = calcPos(torsoPos, torsoRot, new Vector3f(0f, 0.16f*hb*s, headZ), headRot, new Vector3f(0f, 0.06f*hb*s, 0f));
            Vector3f llPos   = calcPos(torsoPos, torsoRot, new Vector3f( 0.1f*b*s,-0.2f*b*s,0f), blRot,   new Vector3f(0f,-0.07f*b*s,0f));
            Vector3f rlPos   = calcPos(torsoPos, torsoRot, new Vector3f(-0.1f*b*s,-0.2f*b*s,0f), brRot,   new Vector3f(0f,-0.07f*b*s,0f));
            Vector3f lwPos   = calcPos(torsoPos, torsoRot, new Vector3f( 0.25f*b*s,0.05f*b*s,0f), flRot,  new Vector3f(-0.03f*b*s,0f,0f));
            Vector3f rwPos   = calcPos(torsoPos, torsoRot, new Vector3f(-0.25f*b*s,0.05f*b*s,0f), frRot,  new Vector3f( 0.03f*b*s,0f,0f));
            parts.add(makePart(world, box(world, new Vector3f(0.2f*b*s,  0.22f*b*s, 0.15f*b*s)), torsoPos, torsoRot, 5*s, vel));
            parts.add(makePart(world, box(world, new Vector3f(0.12f*s, 0.18f*s, 0.12f*s)), headPos,  headRot,  2*s, vel));
            parts.add(makePart(world, box(world, new Vector3f(0.06f*b*s, 0.18f*b*s, 0.06f*b*s)), llPos,    blRot,    1*s, vel));
            parts.add(makePart(world, box(world, new Vector3f(0.06f*b*s, 0.18f*b*s, 0.06f*b*s)), rlPos,    brRot,    1*s, vel));
            parts.add(makePart(world, box(world, new Vector3f(0.05f*b*s, 0.18f*b*s, 0.12f*b*s)), lwPos,    flRot,    1*s, vel));
            parts.add(makePart(world, box(world, new Vector3f(0.05f*b*s, 0.18f*b*s, 0.12f*b*s)), rwPos,    frRot,    1*s, vel));
        } else if (isBaby) {
            QuadLayout l = layoutFor(bodyProfile, true);
            buildProfiledQuadruped(world, parts, torsoPos, torsoRot, headRot, flRot, frRot, blRot, brRot, s, vel, l);
        } else {
            QuadLayout l = layoutFor(bodyProfile, false);
            buildProfiledQuadruped(world, parts, torsoPos, torsoRot, headRot, flRot, frRot, blRot, brRot, s, vel, l);
        }
    }

    // HorseModel is not a QuadrupedModel: its body, neck/head unit and legs have their own pivots.
    // Horse, donkey, mule and both undead horses share the geometry, differing only in render scale.
    private static void buildEquine(PhysicsWorld world, List<PhysicsBody> parts,
                                    Vector3f torsoPos, Quat4f baseQuat, Vector3f vel,
                                    MobPoseCapture.MobPose pose, BodyProfile profile, boolean isBaby) {
        float rs = equineRenderScale(profile);
        // HorseModel's ageable parameters are easy to misread: babyHeadScale is 2.7272 but vanilla draws
        // the head at 1.5 / that, and the body uses its own babyBodyScale of 2.0. All five share this.
        float bs = isBaby ? 0.5f : 1.0f;
        float hs = isBaby ? (1.5f / 2.7272f) : 1.0f;

        // As with cow/pig/sheep, bodies start at baked cube centres and authored default rotations stay
        // in render: HorseModel's pivots are not cube centres and its mirrored legs are a pixel off.
        Quat4f torsoRot = baseQuat;
        Quat4f headRot  = baseQuat;
        Quat4f flRot    = baseQuat;
        Quat4f frRot    = baseQuat;
        Quat4f hlRot    = baseQuat;
        Quat4f hrRot    = baseQuat;

        // Baby leg cubes are inflated 5.5 pixels vertically before the 0.5 body scale, leaving
        // the same 5.5-pixel rendered half-height as an adult leg.
        float legHalfY = 0.34375f * rs;
        Vector3f headPos;
        Vector3f flPos;
        Vector3f frPos;
        Vector3f hlPos;
        Vector3f hrPos;
        if (!isBaby) {
            // Exact adult centres from HorseModel#createBodyMesh relative to the body cube centre. The
            // head includes its authored PI/6 pitch, the legs their one-pixel local X bias.
            headPos = offset(torsoPos, torsoRot, v(0f, 0.296875f*rs, -0.60631f*rs));
            // Vanilla puts front-leg pivots on the body's front face, where a freely rotating rigid limb
            // visibly separates, so inset two pixels. Model +X limbs belong on negative local X for UVs.
            flPos = offset(torsoPos, torsoRot, v(-0.1875f*rs, -0.655625f*rs, -0.55625f*rs));
            frPos = offset(torsoPos, torsoRot, v( 0.1875f*rs, -0.655625f*rs, -0.55625f*rs));
            hlPos = offset(torsoPos, torsoRot, v(-0.1875f*rs, -0.655625f*rs,  0.5625f*rs));
            hrPos = offset(torsoPos, torsoRot, v( 0.1875f*rs, -0.655625f*rs,  0.5625f*rs));
        } else {
            // Centres after AgeableListModel's distinct head/body translations and scales.
            headPos = offset(torsoPos, torsoRot, v(0f, 0.2002f*rs, -0.28984f*rs));
            // prepareMobModel moves standing front-leg pivots from baked Z=-12 to Z=-10; at the 0.5 baby
            // scale the baked pivot leaves both legs a rendered pixel out toward the head.
            flPos = offset(torsoPos, torsoRot, v(-0.09375f*rs, -0.33406f*rs, -0.278125f*rs));
            frPos = offset(torsoPos, torsoRot, v( 0.09375f*rs, -0.33406f*rs, -0.278125f*rs));
            hlPos = offset(torsoPos, torsoRot, v(-0.09375f*rs, -0.33406f*rs,  0.28125f*rs));
            hrPos = offset(torsoPos, torsoRot, v( 0.09375f*rs, -0.33406f*rs,  0.28125f*rs));
        }

        parts.add(makePart(world, box(world, v(0.3125f*bs*rs, 0.3125f*bs*rs, 0.6875f*bs*rs)), torsoPos, torsoRot, 14f*bs*rs, vel));
        parts.add(makePart(world, box(world, v(0.22f*hs*rs, 0.59f*hs*rs, 0.44f*hs*rs)), headPos, headRot, 5f*hs*rs, vel));
        parts.add(makePart(world, box(world, v(0.125f*bs*rs, legHalfY, 0.125f*bs*rs)), hlPos, hlRot, 3f*bs*rs, vel));
        parts.add(makePart(world, box(world, v(0.125f*bs*rs, legHalfY, 0.125f*bs*rs)), hrPos, hrRot, 3f*bs*rs, vel));
        parts.add(makePart(world, box(world, v(0.125f*bs*rs, legHalfY, 0.125f*bs*rs)), flPos, flRot, 3f*bs*rs, vel));
        parts.add(makePart(world, box(world, v(0.125f*bs*rs, legHalfY, 0.125f*bs*rs)), frPos, frRot, 3f*bs*rs, vel));
    }

    private static float equineRenderScale(BodyProfile profile) {
        return switch (profile) {
            case HORSE -> 1.1f;
            case DONKEY -> 0.87f;
            case MULE -> 0.92f;
            default -> 1.0f; // skeleton horse and zombie horse
        };
    }

    private record QuadLayout(
            float headY, float headZ, float headCenterZ, float headX, float headHalfY, float headHalfZ,
            float legX, float legY, float frontZ, float hindZ, float legCenterY, float legHalfX, float legHalfY, float legHalfZ,
            float torsoHalfX, float torsoHalfY, float torsoHalfZ,
            float torsoMass, float headMass, float legMass,
            // Hind-leg vertical layout, kept separate from the front legs so mobs whose legs differ in
            // length or attach height place both correctly. Cow/pig/sheep pass identical values.
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
            // Natural OcelotModel geometry times the 0.8 render scale. OcelotModel has scaleHead, so a
            // kitten head draws at 0.75x and its body at 0.5x; the head box uses that 0.75 factor.
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
            // Exact standing WolfModel cube centres, relative to the rotated body cube centre.
            // The unscaled head is retained for pups; the body and legs use vanilla's 0.5 scale.
            case WOLF -> {
                float whf = 1.0f;
                yield new QuadLayout(
                        0.09375f, baby ? -0.546875f : -0.71875f, baby ? 0f : -0.09375f,
                        0.1875f * whf, 0.25f * whf, 0.21875f * whf,
                        0.09375f * b, -0.125f * b, 0.53125f * b, 0.15625f * b, -0.25f * b,
                        0.0625f * b, 0.25f * b, 0.0625f * b,
                        0.25f * b, 0.21875f * b, 0.47f * b,
                        5f, 1.5f, 0.8f,
                        -0.125f * b, -0.25f * b, 0.25f * b);
            }
            // Exact standing FoxModel centres. FoxModel enlarges a kit's head to 0.75 while
            // scaling its body and legs to 0.5.
            case FOX -> {
                float fhf = baby ? 0.75f : 1.0f;
                yield new QuadLayout(
                        baby ? 0.1171875f : 0f, baby ? -0.25703f : -0.40625f,
                        baby ? 0f : -0.21875f,
                        0.25f * fhf, 0.25f * fhf, 0.28125f * fhf,
                        0.125f * b, -0.0625f * b, 0.21875f * b, 0.21875f * b, -0.21875f * b,
                        0.0625f * b, 0.1875f * b, 0.0625f * b,
                        0.1875f * b, 0.1875f * b, 0.34375f * b,
                        3f, 1.2f, 0.5f,
                        -0.0625f * b, -0.21875f * b, 0.1875f * b);
            }
            // PandaModel uses a rotated body, a broad head union and 6x9x6 legs. Cubs use vanilla's 1/3
            // body and 1.5/2.7 head scales, with the ageable translations folded into these centres.
            case PANDA -> {
                float pbs = baby ? (1f / 3f) : 1f;
                float phs = baby ? (1.5f / 2.7f) : 1f;
                yield new QuadLayout(
                        baby ? 0.083333f : 0f, baby ? -0.440972f : -1.0625f,
                        baby ? 0f : -0.03125f,
                        0.53125f * phs, 0.40625f * phs, 0.34375f * phs,
                        -0.34375f * pbs, -0.3125f * pbs, 0.5625f * pbs, 0.5625f * pbs,
                        -0.28125f * pbs,
                        0.1875f * pbs, 0.28125f * pbs, 0.1875f * pbs,
                        0.59375f * pbs, 0.40625f * pbs, 0.8125f * pbs,
                        15f, 5f, 4f,
                        -0.3125f * pbs, -0.28125f * pbs, 0.28125f * pbs);
            }
            case GOAT -> {
                float gbs = baby ? 0.5f : 1f;
                float ghs = baby ? 0.6f : 1f;
                yield new QuadLayout(
                        baby ? 0.05f : 0.15625f, baby ? -0.409375f : -0.71875f,
                        baby ? 0f : -0.03125f,
                        // Exact union of the head part's baked ear and goatee cubes: the old half-block Z
                        // extent enclosed empty space and made the two bodies overlap deeply.
                        0.34375f*ghs, 0.46875f*ghs, 0.15625f*ghs,
                        -0.125f*gbs, -0.0625f*gbs, 0.3125f*gbs, 0.3125f*gbs,
                        -0.3125f*gbs,
                        0.09375f*gbs, 0.3125f*gbs, 0.09375f*gbs,
                        0.34375f*gbs, 0.4375f*gbs, 0.53125f*gbs,
                        9f, 3f, 2f,
                        -0.0625f*gbs, -0.4375f*gbs, 0.1875f*gbs);
            }
            case POLAR_BEAR -> {
                final float rs = 1.2f;
                float pbs = baby ? 0.5f : 1f;
                float phs = baby ? (2f/3f) : 1f;
                yield new QuadLayout(
                        baby ? -0.00625f : 0.0375f, baby ? -0.65f : -1.2375f,
                        baby ? 0f : -0.0375f,
                        0.28125f*phs*rs, 0.25f*phs*rs, 0.3125f*phs*rs,
                        -0.28125f*pbs*rs, -0.21875f*pbs*rs, 0.375f*pbs*rs, 0.5f*pbs*rs,
                        -0.3125f*pbs*rs,
                        0.125f*pbs*rs, 0.3125f*pbs*rs, 0.1875f*pbs*rs,
                        0.4375f*pbs*rs, 0.34375f*pbs*rs, 0.8125f*pbs*rs,
                        18f, 5f, 4f,
                        -0.21875f*pbs*rs, -0.3125f*pbs*rs, 0.3125f*pbs*rs);
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

    private static void buildProfiledQuadruped(PhysicsWorld world, List<PhysicsBody> parts,
                                               Vector3f torsoPos, Quat4f torsoRot, Quat4f headRot,
                                               Quat4f flRot, Quat4f frRot, Quat4f blRot, Quat4f brRot,
                                               float s, Vector3f vel, QuadLayout l) {
        Vector3f headPos = calcPos(torsoPos, torsoRot, new Vector3f(0f, l.headY*s, l.headZ*s), headRot, new Vector3f(0f, 0f, l.headCenterZ*s));
        Vector3f flPos   = calcPos(torsoPos, torsoRot, new Vector3f( l.legX*s, l.legY*s, -l.frontZ*s), flRot, new Vector3f(0f, l.legCenterY*s, 0f));
        Vector3f frPos   = calcPos(torsoPos, torsoRot, new Vector3f(-l.legX*s, l.legY*s, -l.frontZ*s), frRot, new Vector3f(0f, l.legCenterY*s, 0f));
        Vector3f blPos   = calcPos(torsoPos, torsoRot, new Vector3f( l.legX*s, l.hindLegY*s,  l.hindZ*s), blRot, new Vector3f(0f, l.hindLegCenterY*s, 0f));
        Vector3f brPos   = calcPos(torsoPos, torsoRot, new Vector3f(-l.legX*s, l.hindLegY*s,  l.hindZ*s), brRot, new Vector3f(0f, l.hindLegCenterY*s, 0f));
        parts.add(makePart(world, box(world, new Vector3f(l.torsoHalfX*s, l.torsoHalfY*s, l.torsoHalfZ*s)), torsoPos, torsoRot, l.torsoMass*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(l.headX*s, l.headHalfY*s, l.headHalfZ*s)), headPos, headRot, l.headMass*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(l.legHalfX*s, l.hindLegHalfY*s, l.legHalfZ*s)), blPos, blRot, l.legMass*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(l.legHalfX*s, l.hindLegHalfY*s, l.legHalfZ*s)), brPos, brRot, l.legMass*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(l.legHalfX*s, l.legHalfY*s, l.legHalfZ*s)), flPos, flRot, l.legMass*s, vel));
        parts.add(makePart(world, box(world, new Vector3f(l.legHalfX*s, l.legHalfY*s, l.legHalfZ*s)), frPos, frRot, l.legMass*s, vel));
    }

    // IronGolemModel is humanoid in topology but not proportions (30-pixel arms, an 18-pixel torso, a
    // root above the usual ground plane), so the centres below are exact baked cube unions.
    private static void buildIronGolem(PhysicsWorld world, List<PhysicsBody> parts,
                                       Vector3f torsoPos, Quat4f baseQuat, Vector3f vel) {
        Vector3f headPos = offset(torsoPos, baseQuat, v(0f, 0.828125f, -0.25f));
        Vector3f leftLegPos = offset(torsoPos, baseQuat, v(-0.28125f, -1.015625f, 0f));
        Vector3f rightLegPos = offset(torsoPos, baseQuat, v(0.28125f, -1.015625f, 0f));
        Vector3f leftArmPos = offset(torsoPos, baseQuat, v(-0.6875f, -0.359375f, 0.03125f));
        Vector3f rightArmPos = offset(torsoPos, baseQuat, v(0.6875f, -0.359375f, 0.03125f));
        parts.add(makePart(world, box(world, v(0.5625f, 0.546875f, 0.34375f)), torsoPos, baseQuat, 25f, vel));
        parts.add(makePart(world, box(world, v(0.25f, 0.34375f, 0.3125f)), headPos, baseQuat, 8f, vel));
        parts.add(makePart(world, box(world, v(0.1875f, 0.5f, 0.15625f)), leftLegPos, baseQuat, 10f, vel));
        parts.add(makePart(world, box(world, v(0.1875f, 0.5f, 0.15625f)), rightLegPos, baseQuat, 10f, vel));
        parts.add(makePart(world, box(world, v(0.125f, 0.9375f, 0.1875f)), leftArmPos, baseQuat, 10f, vel));
        parts.add(makePart(world, box(world, v(0.125f, 0.9375f, 0.1875f)), rightArmPos, baseQuat, 10f, vel));
    }

    private static void buildTurtle(PhysicsWorld world, List<PhysicsBody> parts,
                                    Vector3f torsoPos, Quat4f baseQuat, Vector3f vel,
                                    boolean baby) {
        float b = baby ? 1f/6f : 1f;
        Vector3f headPos = offset(torsoPos, baseQuat,
                baby ? v(0f,-0.010417f,-0.135417f) : v(0f,-0.0625f,-0.8125f));
        Vector3f leftHind = offset(torsoPos, baseQuat, v(-0.21875f*b,-0.1875f*b,0.8125f*b));
        Vector3f rightHind = offset(torsoPos, baseQuat, v(0.21875f*b,-0.1875f*b,0.8125f*b));
        Vector3f leftFront = offset(torsoPos, baseQuat, v(-0.71875f*b,-0.125f*b,-0.40625f*b));
        Vector3f rightFront = offset(torsoPos, baseQuat, v(0.71875f*b,-0.125f*b,-0.40625f*b));
        parts.add(makePart(world, box(world, v(0.59375f*b,0.28125f*b,0.625f*b)), torsoPos, baseQuat, 9f*b, vel));
        parts.add(makePart(world, box(world, v(0.1875f*b,0.15625f*b,0.1875f*b)), headPos, baseQuat, 1.5f*b, vel));
        parts.add(makePart(world, box(world, v(0.125f*b,0.03125f*b,0.3125f*b)), leftHind, baseQuat, 1f*b, vel));
        parts.add(makePart(world, box(world, v(0.125f*b,0.03125f*b,0.3125f*b)), rightHind, baseQuat, 1f*b, vel));
        parts.add(makePart(world, box(world, v(0.40625f*b,0.03125f*b,0.15625f*b)), leftFront, baseQuat, 1f*b, vel));
        parts.add(makePart(world, box(world, v(0.40625f*b,0.03125f*b,0.15625f*b)), rightFront, baseQuat, 1f*b, vel));
    }

    private static void buildEnderman(PhysicsWorld world, List<PhysicsBody> parts,
                                      Vector3f torsoPos, Quat4f baseQuat, Vector3f vel) {
        parts.add(makePart(world, box(world, v(.25f,.375f,.125f)), torsoPos, baseQuat, 7f, vel));
        parts.add(makePart(world, box(world, v(.25f,.25f,.25f)),
                offset(torsoPos,baseQuat,v(0,.5625f,0)), baseQuat, 3f, vel));
        parts.add(makePart(world, box(world, v(.0625f,.9375f,.0625f)),
                offset(torsoPos,baseQuat,v(-.125f,-1.125f,0)), baseQuat, 4f, vel));
        parts.add(makePart(world, box(world, v(.0625f,.9375f,.0625f)),
                offset(torsoPos,baseQuat,v(.125f,-1.125f,0)), baseQuat, 4f, vel));
        parts.add(makePart(world, box(world, v(.0625f,.9375f,.0625f)),
                offset(torsoPos,baseQuat,v(-.3125f,-.5625f,0)), baseQuat, 3.5f, vel));
        parts.add(makePart(world, box(world, v(.0625f,.9375f,.0625f)),
                offset(torsoPos,baseQuat,v(.3125f,-.5625f,0)), baseQuat, 3.5f, vel));
    }

    private static void buildCamel(PhysicsWorld world, List<PhysicsBody> parts,
                                   Vector3f torsoPos, Quat4f q, Vector3f vel, boolean baby) {
        float b=baby?.45f:1f;
        parts.add(makePart(world,box(world,v(.46875f*b,.375f*b,.84375f*b)),torsoPos,q,18f*b,vel));
        parts.add(makePart(world,box(world,v(.21875f*b,.6875f*b,.78125f*b)),offset(torsoPos,q,v(0,.4375f*b,-1.125f*b)),q,6f*b,vel));
        parts.add(makePart(world,box(world,v(.15625f*b,.65625f*b,.15625f*b)),offset(torsoPos,q,v(-.30625f*b,-.96875f*b,.625f*b)),q,4f*b,vel));
        parts.add(makePart(world,box(world,v(.15625f*b,.65625f*b,.15625f*b)),offset(torsoPos,q,v(.30625f*b,-.96875f*b,.625f*b)),q,4f*b,vel));
        parts.add(makePart(world,box(world,v(.15625f*b,.65625f*b,.15625f*b)),offset(torsoPos,q,v(-.30625f*b,-.96875f*b,-.625f*b)),q,4f*b,vel));
        parts.add(makePart(world,box(world,v(.15625f*b,.65625f*b,.15625f*b)),offset(torsoPos,q,v(.30625f*b,-.96875f*b,-.625f*b)),q,4f*b,vel));
    }

    private static void buildLlama(PhysicsWorld world, List<PhysicsBody> parts,
                                   Vector3f torsoPos, Quat4f q, Vector3f vel, boolean baby) {
        if (!baby) {
            parts.add(makePart(world,box(world,v(.375f,.3125f,.5625f)),torsoPos,q,11f,vel));
            parts.add(makePart(world,box(world,v(.25f,.65625f,.3125f)),offset(torsoPos,q,v(0,.53125f,-.75f)),q,4f,vel));
            parts.add(makePart(world,box(world,v(.125f,.4375f,.125f)),offset(torsoPos,q,v(-.21875f,-.625f,.3125f)),q,2.5f,vel));
            parts.add(makePart(world,box(world,v(.125f,.4375f,.125f)),offset(torsoPos,q,v(.21875f,-.625f,.3125f)),q,2.5f,vel));
            parts.add(makePart(world,box(world,v(.125f,.4375f,.125f)),offset(torsoPos,q,v(-.21875f,-.625f,-.375f)),q,2.5f,vel));
            parts.add(makePart(world,box(world,v(.125f,.4375f,.125f)),offset(torsoPos,q,v(.21875f,-.625f,-.375f)),q,2.5f,vel));
        } else {
            parts.add(makePart(world,box(world,v(.234375f,.142045f,.255682f)),torsoPos,q,5f,vel));
            parts.add(makePart(world,box(world,v(.178571f,.426136f,.248016f)),offset(torsoPos,q,v(0,.34494f,-.39944f)),q,2f,vel));
            parts.add(makePart(world,box(world,v(.056818f,.180785f,.056818f)),offset(torsoPos,q,v(-.099432f,-.15496f,.142045f)),q,1f,vel));
            parts.add(makePart(world,box(world,v(.056818f,.180785f,.056818f)),offset(torsoPos,q,v(.099432f,-.15496f,.142045f)),q,1f,vel));
            parts.add(makePart(world,box(world,v(.056818f,.180785f,.056818f)),offset(torsoPos,q,v(-.099432f,-.15496f,-.170455f)),q,1f,vel));
            parts.add(makePart(world,box(world,v(.056818f,.180785f,.056818f)),offset(torsoPos,q,v(.099432f,-.15496f,-.170455f)),q,1f,vel));
        }
    }

    private static void buildRabbit(PhysicsWorld world, List<PhysicsBody> parts,
                                    Vector3f torsoPos, Quat4f q, Vector3f vel, boolean baby) {
        float bs=baby?.4f:.6f, hs=baby?.5666667f:.6f;
        float headY=baby?.1575f:.141f, headZ=baby?-.1314f:-.2487f;
        float hindX=baby?-.075f:-.1125f, hindY=baby?-.07475f:-.112f, hindZ=baby?.03175f:.0476f;
        float frontX=baby?-.075f:-.1125f, frontY=baby?-.067f:-.10075f, frontZ=baby?-.1185f:-.1777f;
        parts.add(makePart(world,box(world,v(.1875f*bs,.15625f*bs,.3125f*bs)),torsoPos,q,2.5f*bs,vel));
        parts.add(makePart(world,box(world,v(.2f*hs,.28125f*hs,.2f*hs)),offset(torsoPos,q,v(0,headY,headZ)),q,1f*hs,vel));
        parts.add(makePart(world,box(world,v(.125f*bs,.30f*bs,.35f*bs)),offset(torsoPos,q,v(hindX,hindY,hindZ)),q,.8f*bs,vel));
        parts.add(makePart(world,box(world,v(.125f*bs,.30f*bs,.35f*bs)),offset(torsoPos,q,v(-hindX,hindY,hindZ)),q,.8f*bs,vel));
        parts.add(makePart(world,box(world,v(.0625f*bs,.21875f*bs,.08333f*bs)),offset(torsoPos,q,v(frontX,frontY,frontZ)),q,.5f*bs,vel));
        parts.add(makePart(world,box(world,v(.0625f*bs,.21875f*bs,.08333f*bs)),offset(torsoPos,q,v(-frontX,frontY,frontZ)),q,.5f*bs,vel));
    }

    private static void buildFrog(PhysicsWorld world, List<PhysicsBody> parts,
                                  Vector3f torsoPos, Quat4f q, Vector3f vel) {
        parts.add(makePart(world,box(world,v(.21875f,.09375f,.28125f)),torsoPos,q,2f,vel));
        // Hands and feet are zero-thickness planes in FrogModel; colliding as full footprints made every
        // limb overlap the torso, so the solid cubes are used while the planes still render.
        parts.add(makePart(world,box(world,v(.21875f,.09375f,.28125f)),offset(torsoPos,q,v(0,.125f,0)),q,1f,vel));
        parts.add(makePart(world,box(world,v(.09375f,.09375f,.125f)),offset(torsoPos,q,v(-.25f,-.0625f,.21875f)),q,.5f,vel));
        parts.add(makePart(world,box(world,v(.09375f,.09375f,.125f)),offset(torsoPos,q,v(.25f,-.0625f,.21875f)),q,.5f,vel));
        parts.add(makePart(world,box(world,v(.0625f,.09375f,.09375f)),offset(torsoPos,q,v(-.25f,-.0625f,-.15625f)),q,.5f,vel));
        parts.add(makePart(world,box(world,v(.0625f,.09375f,.09375f)),offset(torsoPos,q,v(.25f,-.0625f,-.15625f)),q,.5f,vel));
    }

    private static void buildHoglin(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel,boolean baby){
        float bs=baby?.5f:1f,hs=baby?(1.5f/1.9f):1f;
        float hy=baby?.1163f:-.1423f,hz=baby?-.5974f:-1.1317f;
        parts.add(makePart(world,box(world,v(.5f*bs,.4375f*bs,.8125f*bs)),p,q,18f*bs,vel));
        // Head cube rotated by vanilla's 50-degree resting pitch, represented by its exact AABB.
        parts.add(makePart(world,box(world,v(.4375f*hs,.5751f*hs,.5251f*hs)),offset(p,q,v(0,hy,hz)),q,7f*hs,vel));
        parts.add(makePart(world,box(world,v(.15625f*bs,.34375f*bs,.15625f*bs)),offset(p,q,v(-.15625f*bs,-.71875f*bs,.625f*bs)),q,3f*bs,vel));
        parts.add(makePart(world,box(world,v(.15625f*bs,.34375f*bs,.15625f*bs)),offset(p,q,v(.15625f*bs,-.71875f*bs,.625f*bs)),q,3f*bs,vel));
        parts.add(makePart(world,box(world,v(.1875f*bs,.4375f*bs,.1875f*bs)),offset(p,q,v(-.25f*bs,-.625f*bs,-.53125f*bs)),q,4f*bs,vel));
        parts.add(makePart(world,box(world,v(.1875f*bs,.4375f*bs,.1875f*bs)),offset(p,q,v(.25f*bs,-.625f*bs,-.53125f*bs)),q,4f*bs,vel));
    }

    private static void buildSniffer(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel,boolean baby){
        float bs=baby?.5f:1f,hs=baby?.6f:1f;
        parts.add(makePart(world,box(world,v(.78125f*bs,.90625f*bs,1.25f*bs)),p,q,28f*bs,vel));
        parts.add(makePart(world,box(world,v(.469375f*hs,.59375f*hs,.625f*hs)),
                offset(p,q,baby?v(0,-.28125f,-.905625f):v(0,-.5f,-1.87375f)),q,8f*hs,vel));
        // Six rendered legs share four physics limbs: front and hind articulate; the middle
        // pair remains attached to the torso in the renderer.
        float lx=.46875f*bs,ly=-.84375f*bs;
        parts.add(makePart(world,box(world,v(.21875f*bs,.3125f*bs,.25f*bs)),offset(p,q,v(-lx,ly,.9375f*bs)),q,4f*bs,vel));
        parts.add(makePart(world,box(world,v(.21875f*bs,.3125f*bs,.25f*bs)),offset(p,q,v(lx,ly,.9375f*bs)),q,4f*bs,vel));
        parts.add(makePart(world,box(world,v(.21875f*bs,.3125f*bs,.25f*bs)),offset(p,q,v(-lx,ly,-.9375f*bs)),q,4f*bs,vel));
        parts.add(makePart(world,box(world,v(.21875f*bs,.3125f*bs,.25f*bs)),offset(p,q,v(lx,ly,-.9375f*bs)),q,4f*bs,vel));
    }

    private static void buildRavager(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel){
        parts.add(makePart(world,box(world,v(.4375f,.625f,.90625f)),p,q,32f,vel));
        // Use the solid head cube: the whole neck/head subtree AABB reached back through both front legs,
        // and those unconnected bodies expelled one another and flipped the ragdoll.
        parts.add(makePart(world,box(world,v(.5f,.625f,.5f)),offset(p,q,v(0,-.0625f,-1.5f)),q,13f,vel));
        parts.add(makePart(world,box(world,v(.25f,1.15625f,.25f)),offset(p,q,v(-.5f,-.46875f,.71875f)),q,8f,vel));
        parts.add(makePart(world,box(world,v(.25f,1.15625f,.25f)),offset(p,q,v(.5f,-.46875f,.71875f)),q,8f,vel));
        parts.add(makePart(world,box(world,v(.25f,1.15625f,.25f)),offset(p,q,v(-.5f,-.46875f,-.71875f)),q,8f,vel));
        parts.add(makePart(world,box(world,v(.25f,1.15625f,.25f)),offset(p,q,v(.5f,-.46875f,-.71875f)),q,8f,vel));
    }

    private static void buildPhantom(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel,float s){
        parts.add(makePart(world,box(world,v(.15625f*s,.09375f*s,.28125f*s)),p,q,5f*s,vel));
        parts.add(makePart(world,box(world,v(.21875f*s,.09375f*s,.15625f*s)),offset(p,q,v(0,-.0625f*s,-.375f*s)),q,2f*s,vel));
        parts.add(makePart(world,box(world,v(.09375f*s,.0625f*s,.1875f*s)),offset(p,q,v(0,.03125f*s,.46875f*s)),q,.7f*s,vel));
        parts.add(makePart(world,box(world,v(.03125f*s,.03125f*s,.1875f*s)),offset(p,q,v(0,.03125f*s,.84375f*s)),q,.5f*s,vel));
        parts.add(makePart(world,box(world,v(.59375f*s,.0625f*s,.28125f*s)),offset(p,q,v(-.75f*s,.03125f*s,0)),q,2f*s,vel));
        parts.add(makePart(world,box(world,v(.59375f*s,.0625f*s,.28125f*s)),offset(p,q,v(.75f*s,.03125f*s,0)),q,2f*s,vel));
    }

    private static void buildParrot(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel){
        parts.add(makePart(world,box(world,v(.09375f,.1875f,.09375f)),p,q,1.2f,vel));
        parts.add(makePart(world,box(world,v(.09375f,.1875f,.1875f)),offset(p,q,v(0,.238125f,.015f)),q,.5f,vel));
        parts.add(makePart(world,box(world,v(.03125f,.0625f,.03125f)),offset(p,q,v(-.0625f,-.21875f,.121875f)),q,.2f,vel));
        parts.add(makePart(world,box(world,v(.03125f,.0625f,.03125f)),offset(p,q,v(.0625f,-.21875f,.121875f)),q,.2f,vel));
        parts.add(makePart(world,box(world,v(.03125f,.15625f,.09375f)),offset(p,q,v(-.09375f,.00375f,.015f)),q,.25f,vel));
        parts.add(makePart(world,box(world,v(.03125f,.15625f,.09375f)),offset(p,q,v(.09375f,.00375f,.015f)),q,.25f,vel));
    }

    private static void buildCubeMob(PhysicsWorld world,List<PhysicsBody> parts,
            Vector3f p,Quat4f q,Vector3f vel){
        parts.add(makePart(world,box(world,v(.25f,.25f,.25f)),p,q,2f,vel));
        for(int i=1;i<6;i++){
            PhysicsBody proxy=makePart(world,box(world,v(.005f,.005f,.005f)),p,q,.01f,vel);
            proxy.setContactResponse(false);
            parts.add(proxy);
        }
    }

    private static PhysicsBody proxy(PhysicsWorld w,Vector3f p,Quat4f q,Vector3f vel){
        PhysicsBody b=makePart(w,box(w,v(.005f,.005f,.005f)),p,q,.01f,vel);
        b.setContactResponse(false);return b;
    }

    private static void buildSilverfish(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.1875f,.125f,.09375f)),p,q,1.2f,vel));
        a.add(makePart(w,box(w,v(.125f,.09375f,.125f)),offset(p,q,v(0,-.03125f,-.21875f)),q,.6f,vel));
        a.add(makePart(w,box(w,v(.09375f,.09375f,.09375f)),offset(p,q,v(0,-.03125f,.1875f)),q,.4f,vel));
        a.add(makePart(w,box(w,v(.0625f,.0625f,.09375f)),offset(p,q,v(0,-.0625f,.375f)),q,.3f,vel));
        a.add(makePart(w,box(w,v(.0625f,.03125f,.0625f)),offset(p,q,v(0,-.09375f,.53125f)),q,.2f,vel));
        a.add(makePart(w,box(w,v(.03125f,.03125f,.0625f)),offset(p,q,v(0,-.09375f,.65625f)),q,.15f,vel));
    }

    private static void buildEndermite(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.1875f,.125f,.15625f)),p,q,1f,vel));
        a.add(makePart(w,box(w,v(.125f,.09375f,.0625f)),offset(p,q,v(0,-.03125f,-.21875f)),q,.5f,vel));
        a.add(makePart(w,box(w,v(.09375f,.09375f,.03125f)),offset(p,q,v(0,-.03125f,.1875f)),q,.3f,vel));
        a.add(makePart(w,box(w,v(.03125f,.0625f,.03125f)),offset(p,q,v(0,-.0625f,.25f)),q,.15f,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
    }

    private static void buildAllay(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.09375f,.1625f,.06875f)),p,q,1f,vel));
        a.add(makePart(w,box(w,v(.15625f,.15625f,.15625f)),offset(p,q,v(0,.311875f,0)),q,.6f,vel));
        // Wings are zero-thickness decorative planes. Giving them colliders makes the tiny
        // Allay skeleton lever itself apart, so their reserved slots are non-contact proxies.
        a.add(proxy(w,p,q,vel));
        a.add(proxy(w,p,q,vel));
        a.add(makePart(w,box(w,v(.03125f,.125f,.0625f)),offset(p,q,v(-.125f,.03125f,0)),q,.25f,vel));
        a.add(makePart(w,box(w,v(.03125f,.125f,.0625f)),offset(p,q,v(.125f,.03125f,0)),q,.25f,vel));
    }

    private static void buildStrider(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,boolean baby){
        float s=baby?.5f:1f;a.add(makePart(w,box(w,v(.5f*s,.4375f*s,.5f*s)),p,q,8f*s,vel));
        a.add(proxy(w,p,q,vel));
        a.add(makePart(w,box(w,v(.125f*s,.5f*s,.125f*s)),offset(p,q,v(-.25f*s,-.8125f*s,0)),q,2f*s,vel));
        a.add(makePart(w,box(w,v(.125f*s,.5f*s,.125f*s)),offset(p,q,v(.25f*s,-.8125f*s,0)),q,2f*s,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
    }

    private static void buildSnowGolem(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.28125f,.28125f,.28125f)),p,q,4f,vel));
        a.add(makePart(w,box(w,v(.21875f,.21875f,.21875f)),offset(p,q,v(0,.5f,0)),q,2f,vel));
        a.add(makePart(w,box(w,v(.34375f,.34375f,.34375f)),offset(p,q,v(0,-.625f,0)),q,5f,vel));
        a.add(proxy(w,p,q,vel));
        // Stick arms are renderer props. Thin angled colliders were both unstable and mapped
        // poorly to their rotated vanilla cubes.
        a.add(proxy(w,p,q,vel));
        a.add(proxy(w,p,q,vel));
    }

    private static void buildBlaze(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.25f,.25f,.25f)),p,q,4f,vel));
        // The twelve rods orbit through one another in the vanilla animation. Grouping them
        // into broad colliders caused permanent self-overlap and violent solver ejection.
        for(int i=0;i<5;i++) a.add(proxy(w,p,q,vel));
    }

    private static void buildSpider(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,float s){
        a.add(makePart(w,box(w,v(.3125f*s,.25f*s,.5625f*s)),p,q,5f*s,vel));
        a.add(makePart(w,box(w,v(.25f*s,.25f*s,.25f*s)),offset(p,q,v(0,0,-.8125f*s)),q,2f*s,vel));
        addSpiderLegBody(w,a,p,q,vel,s, 4, 2,  7,-(float)Math.PI/4,(float)Math.PI/4);
        addSpiderLegBody(w,a,p,q,vel,s,-4, 2, -7, (float)Math.PI/4,-(float)Math.PI/4);
        addSpiderLegBody(w,a,p,q,vel,s, 4, 1,  7,-(float)Math.PI/8,.58119464f);
        addSpiderLegBody(w,a,p,q,vel,s,-4, 1, -7, (float)Math.PI/8,-.58119464f);
        addSpiderLegBody(w,a,p,q,vel,s, 4, 0,  7, (float)Math.PI/8,.58119464f);
        addSpiderLegBody(w,a,p,q,vel,s,-4, 0, -7,-(float)Math.PI/8,-.58119464f);
        addSpiderLegBody(w,a,p,q,vel,s, 4,-1,  7, (float)Math.PI/4,(float)Math.PI/4);
        addSpiderLegBody(w,a,p,q,vel,s,-4,-1, -7,-(float)Math.PI/4,-(float)Math.PI/4);
    }

    private static void buildShulker(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.5f,.25f,.5f)),p,q,8f,vel));
        a.add(makePart(w,box(w,v(.5f,.375f,.5f)),offset(p,q,v(0,.625f,0)),q,7f,vel));
        for(int i=0;i<4;i++) a.add(proxy(w,p,q,vel));
    }

    private static void buildGhast(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        // GhastRenderer scales the one-block model by 4.5.
        a.add(makePart(w,box(w,v(2.25f,2.25f,2.25f)),p,q,24f,vel));
        int[] lengths={8,13,9,11,11,10,12,9,12};
        for(int i=0;i<9;i++) addGhastTentacleBody(w,a,p,q,vel,i,lengths[i]);
    }

    private static void addSpiderLegBody(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,float s,float pivotX,float pivotZ,float cubeCenterX,float yRot,float zRot){
        org.joml.Vector3f center=new org.joml.Vector3f(cubeCenterX,0,0);
        new Quaternionf().rotationZYX(zRot,yRot,0).transform(center);
        Vector3f local=v(-(pivotX+center.x)*s/16f,-center.y*s/16f,(pivotZ+center.z-6f)*s/16f);
        PhysicsShape shape=box(w,v(.37f*s,.028f*s,.028f*s));
        a.add(makePart(w,shape,offset(p,q,local),modelPartRotation(q,0,yRot,zRot),.35f*s,vel));
    }

    private static void addGhastTentacleBody(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,int i,int length){
        float pivotX=((i%3)-(i/3%2)*.5f-.75f)*5f,pivotZ=(i/3-1)*5f;
        org.joml.Vector3f center=new org.joml.Vector3f(0,length*.5f,0);
        new Quaternionf().rotationX(.4f).transform(center);
        float scale=4.5f;Vector3f local=v(-(pivotX+center.x)*scale/16f,-(7f+center.y)*scale/16f,(pivotZ+center.z)*scale/16f);
        PhysicsShape shape=box(w,v(.18f,length*scale/32f,.18f));
        a.add(makePart(w,shape,offset(p,q,local),modelPartRotation(q,.4f,0,0),1.2f,vel));
    }

    private static void buildVex(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.09375f,.15625f,.0625f)),p,q,1f,vel));
        a.add(makePart(w,box(w,v(.15625f,.15625f,.15625f)),offset(p,q,v(0,.28125f,0)),q,.7f,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
        a.add(makePart(w,box(w,v(.05625f,.1125f,.05625f)),offset(p,q,v(-.125f,-.109375f,0)),q,.25f,vel));
        a.add(makePart(w,box(w,v(.05625f,.1125f,.05625f)),offset(p,q,v(.125f,-.109375f,0)),q,.25f,vel));
    }

    private static void buildWarden(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.5625f,.65625f,.34375f)),p,q,18f,vel));
        a.add(makePart(w,box(w,v(.5f,.5f,.3125f)),offset(p,q,v(0,1.15625f,0)),q,8f,vel));
        a.add(makePart(w,box(w,v(.1875f,.40625f,.1875f)),offset(p,q,v(-.36875f,-1.0625f,0)),q,4f,vel));
        a.add(makePart(w,box(w,v(.1875f,.40625f,.1875f)),offset(p,q,v(.36875f,-1.0625f,0)),q,4f,vel));
        a.add(makePart(w,box(w,v(.25f,.875f,.25f)),offset(p,q,v(-.8125f,-.21875f,.0625f)),q,6f,vel));
        a.add(makePart(w,box(w,v(.25f,.875f,.25f)),offset(p,q,v(.8125f,-.21875f,.0625f)),q,6f,vel));
    }


    // Guardian and elder guardian: shell is the torso, tail segments the chain; spikes are renderer-only.
    private static void buildGuardian(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,float s){
        a.add(makePart(w,box(w,v(.4375f*s,.4375f*s,.5f*s)),p,q,14f*s*s*s,vel));
        a.add(makePart(w,box(w,v(.125f*s,.125f*s,.25f*s)),offset(p,q,v(0,0,.6875f*s)),q,2.5f*s*s*s,vel));
        a.add(makePart(w,box(w,v(.09375f*s,.09375f*s,.21875f*s)),offset(p,q,v(0,0,1.09375f*s)),q,1.6f*s*s*s,vel));
        a.add(makePart(w,box(w,v(.0625f*s,.140625f*s,.1875f*s)),offset(p,q,v(0,0,1.625f*s)),q,1.1f*s*s*s,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
    }

    static float guardianScale(BodyProfile profile){ return profile==BodyProfile.ELDER_GUARDIAN?2.35f:1f; }

    // Squid: the mantle plus all eight tentacles, which is nine bodies. Tentacle k hangs from a point
    // 5px out on the circle at angle k*2pi/8, matching SquidModel's authored ring.
    private static void buildSquid(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.375f,.5f,.375f)),p,q,9f,vel));
        for(int k=0;k<8;k++){
            double ang=k*Math.PI*2.0/8.0;
            float lx=-(float)Math.cos(ang)*.3125f, lz=(float)Math.sin(ang)*.3125f;
            a.add(makePart(w,box(w,v(.0625f,.5625f,.0625f)),offset(p,q,v(lx,-1f,lz)),q,.7f,vel));
        }
    }

    // Dolphin, axolotl and every small fish share one shape: a trunk, a nose-end and a tail, with the
    // fins left as renderer geometry because a zero-thickness plane makes a useless collider.
    private static void buildDolphin(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.25f,.21875f,.40625f)),p,q,7f,vel));
        a.add(makePart(w,box(w,v(.25f,.21875f,.1875f)),offset(p,q,v(0,0,-.59375f)),q,3f,vel));
        a.add(makePart(w,box(w,v(.125f,.15625f,.34375f)),offset(p,q,v(0,-.0625f,.625f)),q,2.5f,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
    }

    private static void buildAxolotl(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.25f,.15625f,.3125f)),p,q,2.2f,vel));
        a.add(makePart(w,box(w,v(.25f,.15625f,.15625f)),offset(p,q,v(0,0,-.46875f)),q,1f,vel));
        a.add(makePart(w,box(w,v(.03125f,.15625f,.375f)),offset(p,q,v(0,0,.6875f)),q,.6f,vel));
        a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));a.add(proxy(w,p,q,vel));
    }

    // Half extents of the trunk and of the tail, then the tail's offset behind the trunk centre and the
    // trunk mass, in blocks, taken straight off each vanilla fish model's cube bounds.
    private static float[] fishDimensions(BodyProfile profile){
        switch(profile){
            case SALMON:        return new float[]{.046875f,.078125f,.171875f, .046875f,.078125f,.125f,   .59375f, 1.1f};
            case TROPICAL_FISH: return new float[]{.03125f,.046875f,.09375f,   .015625f,.046875f,.09375f, .375f,   .5f};
            case PUFFERFISH:    return new float[]{.046875f,.03125f,.046875f,  0f,0f,0f,                  0f,      .5f};
            case TADPOLE:       return new float[]{.046875f,.03125f,.046875f,  .015625f,.03125f,.109375f, .3125f,  .35f};
            default:            return new float[]{.03125f,.078125f,.171875f,  .015625f,.0625f,.0625f,    .46875f, .8f};
        }
    }

    private static void buildFish(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel,BodyProfile profile){
        float[] d=fishDimensions(profile);float m=d[7];
        a.add(makePart(w,box(w,v(d[0],d[1],d[2])),p,q,m,vel));
        // The pufferfish is a single ball with no tail, so its second slot is a non-contact proxy.
        if(d[6]==0f) a.add(proxy(w,p,q,vel));
        else a.add(makePart(w,box(w,v(d[3],d[4],d[5])),offset(p,q,v(0,0,d[6])),q,m*.35f,vel));
        for(int i=0;i<4;i++) a.add(proxy(w,p,q,vel));
    }

    // Wither. Every extent is doubled because WitherBossRenderer draws the model at scale 2. The torso
    // collider spans the shoulder bar and the ribcage together; the renderer draws the two separately.
    private static void buildWither(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.625f,.40625f,.09375f)),p,q,40f,vel));
        a.add(makePart(w,box(w,v(.25f,.25f,.25f)),offset(p,q,v(0,1.3f,-.125f)),q,10f,vel));
        a.add(makePart(w,box(w,v(.09375f,.1875f,.09375f)),offset(p,q,v(.0625f,-1.1615f,.2535f)),q,6f,vel));
        a.add(proxy(w,p,q,vel));
        a.add(makePart(w,box(w,v(.1875f,.1875f,.1875f)),offset(p,q,v(-1.125f,.925f,-.25f)),q,7f,vel));
        a.add(makePart(w,box(w,v(.1875f,.1875f,.1875f)),offset(p,q,v(1.125f,.925f,-.25f)),q,7f,vel));
    }

    // Ender dragon at MAX_PARTS bodies: neck and tail are one rigid body each, and the renderer
    // still draws every segment along its body's local Z.
    private static void buildEnderDragon(PhysicsWorld w,List<PhysicsBody> a,Vector3f p,Quat4f q,Vector3f vel){
        a.add(makePart(w,box(w,v(.75f,.9375f,2f)),p,q,400f,vel));
        a.add(makePart(w,box(w,v(.5f,.625f,.9375f)),offset(p,q,v(0,-.3125f,-5.9375f)),q,60f,vel));
        a.add(makePart(w,box(w,v(.25f,1.59375f,.65625f)),offset(p,q,v(-.75f,-1.78125f,-1.78125f)),q,45f,vel));
        a.add(makePart(w,box(w,v(.25f,1.59375f,.65625f)),offset(p,q,v(.75f,-1.78125f,-1.78125f)),q,45f,vel));
        a.add(makePart(w,box(w,v(1.75f,.25f,1.9375f)),offset(p,q,v(-2.5f,.5f,.3125f)),q,70f,vel));
        a.add(makePart(w,box(w,v(1.75f,.25f,1.9375f)),offset(p,q,v(2.5f,.5f,.3125f)),q,70f,vel));
        a.add(makePart(w,box(w,v(.5625f,2.28125f,.875f)),offset(p,q,v(-1f,-2.21875f,.75f)),q,65f,vel));
        a.add(makePart(w,box(w,v(.5625f,2.28125f,.875f)),offset(p,q,v(1f,-2.21875f,.75f)),q,65f,vel));
        a.add(makePart(w,box(w,v(.3125f,.4375f,.9375f)),offset(p,q,v(0,-.3125f,-3.5f)),q,55f,vel));
        a.add(makePart(w,box(w,v(.3125f,.4375f,3.75f)),offset(p,q,v(0,.3125f,5.6875f)),q,90f,vel));
    }

    // Joint builders

    private static void buildJoints(PhysicsWorld world, List<PhysicsBody> parts,
                                    List<PhysicsConstraint> joints,
                                    MobModelHelper.ModelType modelType, float s, BodyProfile bodyProfile,
                                    boolean isBaby, boolean babyBigHead) {
        if (parts.size() < 6) return;
        PhysicsBody torso = parts.get(RagdollPart.TORSO.index);
        PhysicsBody head  = parts.get(RagdollPart.HEAD.index);
        PhysicsBody lLeg  = parts.get(RagdollPart.LEFT_LEG.index);
        PhysicsBody rLeg  = parts.get(RagdollPart.RIGHT_LEG.index);
        PhysicsBody lArm  = parts.get(RagdollPart.LEFT_ARM.index);
        PhysicsBody rArm  = parts.get(RagdollPart.RIGHT_ARM.index);
        PhysTransform tTorso = wt(torso), tHead = wt(head), tLLeg = wt(lLeg), tRLeg = wt(rLeg), tLArm = wt(lArm), tRArm = wt(rArm);
        int firstJoint = joints.size();
        Function<Vector3f, Vector3f> tw = local -> {
            Vector3f out = rotQ(tTorso.getRotation(new Quat4f()), local);
            out.add(tTorso.origin);
            return out;
        };
        switch (modelType) {
            case CREEPER:   buildCreeperJoints  (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tw, s); break;
            case QUADRUPED:
            case WOLF:
            case FOX:       buildQuadJoints     (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s, bodyProfile, isBaby); break;
            case CHICKEN:   buildChickenJoints  (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            case BAT:       buildBatJoints      (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            case BEE:       buildBeeJoints      (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s); break;
            case EQUINE:    buildEquineJoints   (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, bodyProfile, isBaby); break;
            case PANDA:     buildQuadJoints     (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s, bodyProfile, isBaby); break;
            case GOAT:
            case POLAR_BEAR: buildQuadJoints    (world, joints, torso, head, lArm, rArm, lLeg, rLeg, tHead, tLArm, tRArm, tLLeg, tRLeg, tw, s, bodyProfile, isBaby); break;
            case IRON_GOLEM: buildIronGolemJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw); break;
            case TURTLE:     buildTurtleJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw, isBaby); break;
            case ENDERMAN:   buildEndermanJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw); break;
            case CAMEL:      buildCamelJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw, isBaby); break;
            case LLAMA:      buildLlamaJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw, isBaby); break;
            case RABBIT:     buildRabbitJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw, isBaby); break;
            case FROG:       buildFrogJoints(world, joints, torso, head, lArm, rArm, lLeg, rLeg, tw); break;
            case HOGLIN:     buildHoglinJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw,isBaby); break;
            case SNIFFER:    buildSnifferJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw,isBaby); break;
            case RAVAGER:    buildRavagerJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw); break;
            case PHANTOM:    buildPhantomJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw,s); break;
            case PARROT:     buildParrotJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw); break;
            case SLIME:
            case MAGMA_CUBE: buildCubeMobJoints(world,joints,torso,head,lArm,rArm,lLeg,rLeg,tw); break;
            case SILVERFISH: buildSegmentJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,false); break;
            case ENDERMITE: buildSegmentJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,true); break;
            case ALLAY: buildAllayJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case STRIDER: buildStriderJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,isBaby); break;
            case SNOW_GOLEM: buildSnowGolemJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case BLAZE: buildRadialJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case SPIDER: buildSpiderJoints(world,joints,parts,tw,bodyProfile==BodyProfile.CAVE_SPIDER?.7f:1f); break;
            case SHULKER: buildShulkerJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case GHAST: buildGhastJoints(world,joints,parts,tw); break;
            case VEX: buildVexJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case WARDEN: buildWardenJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case GUARDIAN: buildGuardianJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,guardianScale(bodyProfile)); break;
            case SQUID: buildSquidJoints(world,joints,parts,tw); break;
            case DOLPHIN: buildFinnedJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,-.34f,.34f); break;
            case AXOLOTL: buildFinnedJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,-.29f,.32f); break;
            case FISH: buildFishJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw,bodyProfile); break;
            case WITHER: buildWitherJoints(world,joints,torso,head,lLeg,rLeg,lArm,rArm,tw); break;
            case ENDER_DRAGON: buildEnderDragonJoints(world,joints,parts,tw); break;
            default:        buildHumanoidJoints (world, joints, torso, head, lLeg, rLeg, lArm, rArm, tHead, tLLeg, tRLeg, tLArm, tRArm, tw, s, isBaby, babyBigHead); break;
        }

        // Every skeleton, not just the humanoid: each one hangs its head off the torso as its first
        // joint, so that one takes the stiffer spring and the limbs behind it the loose one.
        applyRelaxation(joints, firstJoint, 1f);

        applyAerodynamicDrag(parts);
    }

    // Clamp on a part's drag ratio, so thin light parts (ghast tentacles, dragon wings) don't hang in the air.
    private static final float DRAG_RATIO_FLOOR = 0.2f;
    private static final float DRAG_RATIO_CEILING = 12f;

    // Split linear damping by each part's area-over-mass so light broad limbs trail the torso in a fall.
    // Mass-weighted about the configured figure, so total drag is unchanged; works for every skeleton.
    private static void applyAerodynamicDrag(List<PhysicsBody> parts) {
        float base = (float) RagdollifiedConfig.get(RagdollifiedConfig.LINEAR_DAMPING);
        float spread = (float) RagdollifiedConfig.get(RagdollifiedConfig.DIFFERENTIAL_DRAG);
        if (base <= 0f || spread <= 0f) return;
        float angular = (float) RagdollifiedConfig.get(RagdollifiedConfig.ANGULAR_DAMPING);

        int count = parts.size();
        float[] scale = new float[count];
        float[] mass = new float[count];
        float areaTotal = 0f;
        float massTotal = 0f;
        for (int i = 0; i < count; i++) {
            PhysicsBody body = parts.get(i);
            float invMass = body.getInvMass();
            if (invMass <= 0f) continue;
            float area = meanProjectedArea(body);
            if (area <= 0f) continue;
            mass[i] = 1f / invMass;
            scale[i] = area / mass[i];
            areaTotal += area;
            massTotal += mass[i];
        }
        if (areaTotal <= 0f || massTotal <= 0f) return;

        // The mass-weighted mean of area/mass reduces to total area over total mass, so dividing by
        // it leaves each part a multiplier either side of 1: about 0.42 for a torso, 3.1 for an arm.
        float mean = areaTotal / massTotal;
        float weighted = 0f;
        for (int i = 0; i < count; i++) {
            if (scale[i] <= 0f) continue;
            float ratio = Math.max(DRAG_RATIO_FLOOR, Math.min(DRAG_RATIO_CEILING, scale[i] / mean));
            // Exaggerate by a power, not linear interpolation, which can go negative and add energy.
            scale[i] = spread == 1f ? ratio : (float) Math.pow(ratio, spread);
            weighted += mass[i] * scale[i];
        }
        if (weighted <= 0f) return;

        // Normalise by the mass-weighted mean so total drag stays at the configured figure.
        float norm = massTotal / weighted;
        for (int i = 0; i < count; i++) {
            if (scale[i] <= 0f) continue;
            parts.get(i).setDamping(base * scale[i] * norm, angular);
        }
    }

    // Mean projected area of a box over all orientations: a quarter of its surface area.
    private static float meanProjectedArea(PhysicsBody body) {
        PhysicsShape shape = body.getShape();
        if (shape == null || !shape.isBox()) return 0f;
        Vector3f half = new Vector3f();
        shape.getHalfExtents(half);
        float a = 2f * half.x, b = 2f * half.y, c = 2f * half.z;
        return (a * b + b * c + c * a) * 0.5f;
    }

    private static void buildHumanoidJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody lLeg, PhysicsBody rLeg, PhysicsBody lArm, PhysicsBody rArm,
            PhysTransform tHead, PhysTransform tLLeg, PhysTransform tRLeg, PhysTransform tLArm, PhysTransform tRArm,
            Function<Vector3f, Vector3f> tw, float s, boolean isBaby, boolean babyBigHead) {
        // Anchor offsets track the scaled extents from buildHumanoid so baby joints sit at the shrunken
        // boundaries: head on the head scale, torso and limbs on the body scale. Angular limits unchanged.
        float bs = isBaby ? 0.5f : 1.0f;
        float hd = isBaby ? (babyBigHead ? 0.75f : 0.5f) : 1.0f;
        // Humanoid joint cones are the only player-tunable limits (config 'humanoidJointLimits').
        float[] neck = RagdollifiedConfig.getJointLimits(RagdollifiedConfig.NECK_LIMITS);
        float[] hip = RagdollifiedConfig.getJointLimits(RagdollifiedConfig.HIP_LIMITS);
        float[] shoulder = RagdollifiedConfig.getJointLimits(RagdollifiedConfig.SHOULDER_LIMITS);
        // Anchors are measured off the built bodies, so captured proportions (like illager heads) line up.
        Vector3f authoredLimb = new Vector3f(0.125f*bs, 0.375f*bs, 0.125f*bs);
        Vector3f torsoHalf = halfExtentsOf(torso, new Vector3f(0.25f*bs, 0.375f*bs, 0.15f*bs));
        Vector3f headHalf = halfExtentsOf(head, new Vector3f(0.25f*hd, 0.25f*hd, 0.25f*hd));
        Vector3f lLegHalf = halfExtentsOf(lLeg, authoredLimb);
        Vector3f rLegHalf = halfExtentsOf(rLeg, authoredLimb);
        Vector3f lArmHalf = halfExtentsOf(lArm, authoredLimb);
        Vector3f rArmHalf = halfExtentsOf(rArm, authoredLimb);
        // Each joint is the anchor buildHumanoid hung its part from, measured once off the torso and once off the part; they agree, and the midpoint only guards a body placed some other way.
        joints.add(joint(world, torso, head, mid(tw.apply(humanoidNeck(torsoHalf)), onBody(tHead, new Vector3f(0f, -headHalf.y, 0f))), v(0,0,0), v(0,0,0), lo(neck), hi(neck)));
        joints.add(joint(world, torso, lLeg, mid(tw.apply(humanoidHip(torsoHalf, bs, -1f)), onBody(tLLeg, new Vector3f(0f, lLegHalf.y, 0f))), v(-0.05f*bs,0f,-0.05f*bs), v(0.05f*bs,0f,0.05f*bs), lo(hip), hi(hip)));
        joints.add(joint(world, torso, rLeg, mid(tw.apply(humanoidHip(torsoHalf, bs, 1f)), onBody(tRLeg, new Vector3f(0f, rLegHalf.y, 0f))), v(-0.05f*bs,0f,-0.05f*bs), v(0.05f*bs,0f,0.05f*bs), lo(hip), hi(hip)));
        // keepCollision: arms sit flush against the torso, so their contact is free and stops them clipping the chest.
        joints.add(joint(world, torso, lArm, mid(tw.apply(humanoidShoulder(torsoHalf, lArmHalf, bs, -1f)), onBody(tLArm, humanoidShoulderOnArm(lArmHalf, bs, -1f))), v(-0.02f*bs,-0.02f*bs,-0.02f*bs), v(0.02f*bs,0.02f*bs,0.02f*bs), lo(shoulder), hi(shoulder), true));
        joints.add(joint(world, torso, rArm, mid(tw.apply(humanoidShoulder(torsoHalf, rArmHalf, bs, 1f)), onBody(tRArm, humanoidShoulderOnArm(rArmHalf, bs, 1f))), v(-0.02f*bs,-0.02f*bs,-0.02f*bs), v(0.02f*bs,0.02f*bs,0.02f*bs), lo(shoulder), hi(shoulder), true));
    }

    // A point given in a body's own frame, in world space.
    private static Vector3f onBody(PhysTransform body, Vector3f local) {
        Vector3f point = rotQ(body.getRotation(new Quat4f()), local);
        point.add(body.origin);
        return point;
    }

    // The size a body was actually built with. Falls back to the authored figure for a backend that
    // does not publish a box, so this can never be the thing that stops a joint being made.
    private static Vector3f halfExtentsOf(PhysicsBody body, Vector3f authored) {
        try {
            PhysicsShape shape = body.getShape();
            if (shape != null && shape.isBox()) {
                Vector3f out = new Vector3f();
                shape.getHalfExtents(out);
                if (out.x > 1.0e-4f && out.y > 1.0e-4f && out.z > 1.0e-4f) return out;
            }
        } catch (Exception ignored) {
            // Fall through to the authored size.
        }
        return authored;
    }

    // Every joint gets a weak spring toward its authored pose; the neck's is this much stiffer,
    // so a free head doesn't roll to an unnatural angle. See relaxJoints.
    public static final float NECK_RELAX_SCALE = 16f;

    // Apply relaxation springs scaled by scale (1 = configured, 0 = off); re-callable at runtime.
    // The neck keeps its multiple either way.
    public static void applyRelaxation(List<PhysicsConstraint> joints, int firstJoint, float scale) {
        relaxJoints(joints, firstJoint, firstJoint + 1, NECK_RELAX_SCALE * scale);
        relaxJoints(joints, firstJoint + 1, joints.size(), scale);
    }

    private static void relaxJoints(List<PhysicsConstraint> joints, int from, int to, float scale) {
        float frequency = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_FREQUENCY) * scale;
        float damping = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_DAMPING);
        float torque = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_TORQUE);
        for (int i = Math.max(0, from); i < Math.min(to, joints.size()); i++) {
            joints.get(i).relaxToRest(frequency, damping, torque);
        }
    }

    // Drive every joint toward a fresh random target instead of the rest pose; call on an interval.
    // strengthScale multiplies spring frequency (<=0 releases); spreadRadians is the cone half-angle.
    public static void applyFlail(List<PhysicsConstraint> joints, int firstJoint,
                                  float strengthScale, float spreadRadians, java.util.Random rng) {
        float frequency = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_FREQUENCY) * strengthScale;
        float damping = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_DAMPING);
        float torque = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_TORQUE);
        for (int i = Math.max(0, firstJoint); i < joints.size(); i++) {
            // Random axis rather than random Euler angles, which bunch targets toward the cube's corners.
            float ax = (float) rng.nextGaussian();
            float ay = (float) rng.nextGaussian();
            float az = (float) rng.nextGaussian();
            float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (len < 1.0e-6f) { ax = 0f; ay = 1f; az = 0f; len = 1f; }
            float angle = spreadRadians * (rng.nextFloat() * 2f - 1f);
            float half = angle * 0.5f;
            float sin = (float) Math.sin(half) / len;
            joints.get(i).driveToOrientation(ax * sin, ay * sin, az * sin, (float) Math.cos(half),
                    frequency, damping, torque);
        }
    }

    private static Vector3f lo(float[] limits) { return v(limits[0], limits[1], limits[2]); }

    private static Vector3f hi(float[] limits) { return v(limits[3], limits[4], limits[5]); }

    private static void buildIronGolemJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody leftArm, PhysicsBody rightArm,
            PhysicsBody leftLeg, PhysicsBody rightLeg, Function<Vector3f, Vector3f> tw) {
        Vector3f zero = v(0,0,0);
        Vector3f lin = v(-0.015f,-0.015f,-0.015f);
        Vector3f liu = v(0.015f,0.015f,0.015f);
        joints.add(joint(world, torso, head, tw.apply(v(0f,0.55f,-0.16f)), zero, zero,
                v(-25,-30,-20), v(35,30,20)));
        joints.add(joint(world, torso, leftLeg, tw.apply(v(-0.28125f,-0.53f,0f)), lin, liu,
                v(-45,-12,-15), v(65,12,15)));
        joints.add(joint(world, torso, rightLeg, tw.apply(v(0.28125f,-0.53f,0f)), lin, liu,
                v(-45,-12,-15), v(65,12,15)));
        joints.add(joint(world, torso, leftArm, tw.apply(v(-0.61f,0.55f,0f)), lin, liu,
                v(-110,-20,-35), v(110,20,35)));
        joints.add(joint(world, torso, rightArm, tw.apply(v(0.61f,0.55f,0f)), lin, liu,
                v(-110,-20,-35), v(110,20,35)));
    }

    private static void buildTurtleJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody leftFront, PhysicsBody rightFront,
            PhysicsBody leftHind, PhysicsBody rightHind, Function<Vector3f, Vector3f> tw,
            boolean baby) {
        float b = baby ? 1f/6f : 1f;
        Vector3f lin = v(-.015f*b,-.015f*b,-.015f*b), liu = v(.015f*b,.015f*b,.015f*b);
        joints.add(joint(world, torso, head, tw.apply(v(0,-.02f*b,-.62f*b)), lin, liu,
                v(-30,-35,-25),v(30,35,25)));
        joints.add(joint(world, torso, leftFront, tw.apply(v(-.48f*b,-.15f*b,-.35f*b)), lin, liu,
                v(-25,-20,-45),v(25,20,45)));
        joints.add(joint(world, torso, rightFront, tw.apply(v(.48f*b,-.15f*b,-.35f*b)), lin, liu,
                v(-25,-20,-45),v(25,20,45)));
        joints.add(joint(world, torso, leftHind, tw.apply(v(-.22f*b,-.18f*b,.55f*b)), lin, liu,
                v(-25,-30,-25),v(25,30,25)));
        joints.add(joint(world, torso, rightHind, tw.apply(v(.22f*b,-.18f*b,.55f*b)), lin, liu,
                v(-25,-30,-25),v(25,30,25)));
    }

    private static void buildEndermanJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody leftArm, PhysicsBody rightArm,
            PhysicsBody leftLeg, PhysicsBody rightLeg, Function<Vector3f, Vector3f> tw) {
        Vector3f lin=v(-.015f,-.015f,-.015f), liu=v(.015f,.015f,.015f);
        joints.add(joint(world,torso,head,tw.apply(v(0,.35f,0)),lin,liu,v(-35,-35,-25),v(45,35,25)));
        joints.add(joint(world,torso,leftLeg,tw.apply(v(-.125f,-.28f,0)),lin,liu,v(-55,-12,-15),v(70,12,15)));
        joints.add(joint(world,torso,rightLeg,tw.apply(v(.125f,-.28f,0)),lin,liu,v(-55,-12,-15),v(70,12,15)));
        joints.add(joint(world,torso,leftArm,tw.apply(v(-.3125f,.375f,0)),lin,liu,v(-120,-25,-40),v(120,25,40)));
        joints.add(joint(world,torso,rightArm,tw.apply(v(.3125f,.375f,0)),lin,liu,v(-120,-25,-40),v(120,25,40)));
    }

    private static void buildCamelJoints(PhysicsWorld world,List<PhysicsConstraint> joints,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw,boolean baby){
        float b=baby?.45f:1f; Vector3f l=v(-.015f*b,-.015f*b,-.015f*b),u=v(.015f*b,.015f*b,.015f*b);
        joints.add(joint(world,torso,head,tw.apply(v(0,.25f*b,-.72f*b)),l,u,v(-45,-30,-25),v(55,30,25)));
        joints.add(joint(world,torso,lf,tw.apply(v(-.31f*b,-.36f*b,-.62f*b)),l,u,v(-55,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,rf,tw.apply(v(.31f*b,-.36f*b,-.62f*b)),l,u,v(-55,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,lh,tw.apply(v(-.31f*b,-.36f*b,.62f*b)),l,u,v(-55,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,rh,tw.apply(v(.31f*b,-.36f*b,.62f*b)),l,u,v(-55,-12,-15),v(55,12,15)));
    }

    private static void buildLlamaJoints(PhysicsWorld world,List<PhysicsConstraint> joints,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw,boolean baby){
        float x=baby?.10f:.22f,y=baby?-.14f:-.31f,fz=baby?-.17f:-.375f,hz=baby?.142f:.3125f;
        Vector3f l=v(-.015f,-.015f,-.015f),u=v(.015f,.015f,.015f);
        joints.add(joint(world,torso,head,tw.apply(v(0,baby?.2f:.38f,baby?-.28f:-.55f)),l,u,v(-45,-30,-25),v(55,30,25)));
        joints.add(joint(world,torso,lf,tw.apply(v(-x,y,fz)),l,u,v(-50,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,rf,tw.apply(v(x,y,fz)),l,u,v(-50,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,lh,tw.apply(v(-x,y,hz)),l,u,v(-50,-12,-15),v(55,12,15)));
        joints.add(joint(world,torso,rh,tw.apply(v(x,y,hz)),l,u,v(-50,-12,-15),v(55,12,15)));
    }

    private static void buildRabbitJoints(PhysicsWorld world,List<PhysicsConstraint> joints,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw,boolean baby){
        float k=baby?.667f:1f; Vector3f l=v(-.008f,-.008f,-.008f),u=v(.008f,.008f,.008f);
        joints.add(joint(world,torso,head,tw.apply(v(0,.12f*k,-.18f*k)),l,u,v(-40,-35,-25),v(55,35,25)));
        joints.add(joint(world,torso,lf,tw.apply(v(-.11f*k,-.05f*k,-.15f*k)),l,u,v(-65,-15,-20),v(45,15,20)));
        joints.add(joint(world,torso,rf,tw.apply(v(.11f*k,-.05f*k,-.15f*k)),l,u,v(-65,-15,-20),v(45,15,20)));
        joints.add(joint(world,torso,lh,tw.apply(v(-.11f*k,-.04f*k,.08f*k)),l,u,v(-70,-20,-25),v(70,20,25)));
        joints.add(joint(world,torso,rh,tw.apply(v(.11f*k,-.04f*k,.08f*k)),l,u,v(-70,-20,-25),v(70,20,25)));
    }

    private static void buildFrogJoints(PhysicsWorld world,List<PhysicsConstraint> joints,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.008f,-.008f,-.008f),u=v(.008f,.008f,.008f);
        // Anchors are the real FrogModel pivots relative to the solid body's cube centre. All four limb
        // pivots sit above their body centres; anchoring below folded the meshes inward on the first step.
        joints.add(joint(world,torso,head,tw.apply(v(0,.0625f,0)),l,u,v(-30,-30,-20),v(35,30,20)));
        joints.add(joint(world,torso,lf,tw.apply(v(-.25f,.03125f,-.1875f)),l,u,v(-50,-25,-45),v(50,25,45)));
        joints.add(joint(world,torso,rf,tw.apply(v(.25f,.03125f,-.1875f)),l,u,v(-50,-25,-45),v(50,25,45)));
        joints.add(joint(world,torso,lh,tw.apply(v(-.21875f,.03125f,.21875f)),l,u,v(-60,-35,-40),v(60,35,40)));
        joints.add(joint(world,torso,rh,tw.apply(v(.21875f,.03125f,.21875f)),l,u,v(-60,-35,-40),v(60,35,40)));
    }

    private static void buildHoglinJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw,boolean baby){
        float b=baby?.5f:1f;Vector3f l=v(-.015f*b,-.015f*b,-.015f*b),u=v(.015f*b,.015f*b,.015f*b);
        js.add(joint(w,torso,head,tw.apply(baby?v(0,.18f,-.45f):v(0,0,-.72f)),l,u,v(-40,-25,-20),v(45,25,20)));
        js.add(joint(w,torso,lf,tw.apply(v(-.25f*b,-.19f*b,-.53f*b)),l,u,v(-50,-12,-15),v(55,12,15)));
        js.add(joint(w,torso,rf,tw.apply(v(.25f*b,-.19f*b,-.53f*b)),l,u,v(-50,-12,-15),v(55,12,15)));
        js.add(joint(w,torso,lh,tw.apply(v(-.15625f*b,-.375f*b,.625f*b)),l,u,v(-50,-12,-15),v(55,12,15)));
        js.add(joint(w,torso,rh,tw.apply(v(.15625f*b,-.375f*b,.625f*b)),l,u,v(-50,-12,-15),v(55,12,15)));
    }

    private static void buildSnifferJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw,boolean baby){
        float b=baby?.5f:1f;Vector3f l=v(-.02f*b,-.02f*b,-.02f*b),u=v(.02f*b,.02f*b,.02f*b);
        js.add(joint(w,torso,head,tw.apply(baby?v(0,-.15f,-.58f):v(0,-.25f,-1.25f)),l,u,v(-35,-25,-20),v(40,25,20)));
        float x=.46875f*b,y=-.53125f*b;
        js.add(joint(w,torso,lf,tw.apply(v(-x,y,-.9375f*b)),l,u,v(-45,-10,-12),v(50,10,12)));
        js.add(joint(w,torso,rf,tw.apply(v(x,y,-.9375f*b)),l,u,v(-45,-10,-12),v(50,10,12)));
        js.add(joint(w,torso,lh,tw.apply(v(-x,y,.9375f*b)),l,u,v(-45,-10,-12),v(50,10,12)));
        js.add(joint(w,torso,rh,tw.apply(v(x,y,.9375f*b)),l,u,v(-45,-10,-12),v(50,10,12)));
    }

    private static void buildRavagerJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lf,PhysicsBody rf,PhysicsBody lh,PhysicsBody rh,
            Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.02f,-.02f,-.02f),u=v(.02f,.02f,.02f);
        js.add(joint(w,torso,head,tw.apply(v(0,0,-1f)),l,u,v(-30,-20,-18),v(35,20,18)));
        js.add(joint(w,torso,lf,tw.apply(v(-.5f,.6f,-.71875f)),l,u,v(-40,-8,-10),v(45,8,10)));
        js.add(joint(w,torso,rf,tw.apply(v(.5f,.6f,-.71875f)),l,u,v(-40,-8,-10),v(45,8,10)));
        js.add(joint(w,torso,lh,tw.apply(v(-.5f,.6f,.71875f)),l,u,v(-40,-8,-10),v(45,8,10)));
        js.add(joint(w,torso,rh,tw.apply(v(.5f,.6f,.71875f)),l,u,v(-40,-8,-10),v(45,8,10)));
    }

    private static void buildPhantomJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lw,PhysicsBody rw,PhysicsBody tailBase,PhysicsBody tailTip,
            Function<Vector3f,Vector3f> tw,float s){
        Vector3f l=v(-.01f*s,-.01f*s,-.01f*s),u=v(.01f*s,.01f*s,.01f*s);
        js.add(joint(w,torso,head,tw.apply(v(0,0,-.28f*s)),l,u,v(-30,-25,-20),v(35,25,20)));
        js.add(joint(w,torso,lw,tw.apply(v(-.15f*s,.06f*s,-.25f*s)),l,u,v(-25,-15,-70),v(25,15,70)));
        js.add(joint(w,torso,rw,tw.apply(v(.15f*s,.06f*s,-.25f*s)),l,u,v(-25,-15,-70),v(25,15,70)));
        js.add(joint(w,torso,tailBase,tw.apply(v(0,.02f*s,.3f*s)),l,u,v(-35,-15,-15),v(35,15,15)));
        js.add(joint(w,tailBase,tailTip,tw.apply(v(0,.03f*s,.66f*s)),l,u,v(-35,-15,-15),v(35,15,15)));
    }

    private static void buildParrotJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody lw,PhysicsBody rw,PhysicsBody ll,PhysicsBody rl,
            Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.005f,-.005f,-.005f),u=v(.005f,.005f,.005f);
        js.add(joint(w,torso,head,tw.apply(v(0,.14f,0)),l,u,v(-35,-30,-25),v(45,30,25)));
        js.add(joint(w,torso,lw,tw.apply(v(-.09f,.08f,0)),l,u,v(-65,-30,-55),v(65,30,55)));
        js.add(joint(w,torso,rw,tw.apply(v(.09f,.08f,0)),l,u,v(-65,-30,-55),v(65,30,55)));
        js.add(joint(w,torso,ll,tw.apply(v(-.0625f,-.16f,.12f)),l,u,v(-35,-15,-15),v(40,15,15)));
        js.add(joint(w,torso,rl,tw.apply(v(.0625f,-.16f,.12f)),l,u,v(-35,-15,-15),v(40,15,15)));
    }

    private static void buildCubeMobJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody la,PhysicsBody ra,PhysicsBody ll,PhysicsBody rl,
            Function<Vector3f,Vector3f> tw){
        Vector3f z=v(0,0,0),a=v(-1,-1,-1),b=v(1,1,1);Vector3f anchor=tw.apply(z);
        js.add(joint(w,torso,head,anchor,z,z,a,b));
        js.add(joint(w,torso,la,anchor,z,z,a,b));js.add(joint(w,torso,ra,anchor,z,z,a,b));
        js.add(joint(w,torso,ll,anchor,z,z,a,b));js.add(joint(w,torso,rl,anchor,z,z,a,b));
    }

    private static void buildSegmentJoints(PhysicsWorld w,List<PhysicsConstraint> js,
            PhysicsBody torso,PhysicsBody head,PhysicsBody s3,PhysicsBody s4,PhysicsBody s5,PhysicsBody s6,
            Function<Vector3f,Vector3f> tw,boolean endermite){
        Vector3f l=v(-.004f,-.004f,-.004f),u=v(.004f,.004f,.004f),al=v(-25,-20,-20),au=v(25,20,20);
        js.add(joint(w,head,torso,tw.apply(v(0,0,-.08f)),l,u,al,au));
        js.add(joint(w,torso,s3,tw.apply(v(0,0,.1f)),l,u,al,au));
        js.add(joint(w,s3,s4,tw.apply(v(0,-.04f,.28f)),l,u,al,au));
        if(endermite){Vector3f z=v(0,0,0),a=v(-1,-1,-1),b=v(1,1,1);js.add(joint(w,torso,s5,tw.apply(z),z,z,a,b));js.add(joint(w,torso,s6,tw.apply(z),z,z,a,b));}
        else{js.add(joint(w,s4,s5,tw.apply(v(0,-.07f,.46f)),l,u,al,au));js.add(joint(w,s5,s6,tw.apply(v(0,-.09f,.59f)),l,u,al,au));}
    }

    private static void buildAllayJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody h,PhysicsBody lw,PhysicsBody rw,PhysicsBody la,PhysicsBody ra,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.004f,-.004f,-.004f),u=v(.004f,.004f,.004f);
        js.add(joint(w,t,h,tw.apply(v(0,.17f,0)),l,u,v(-35,-30,-25),v(45,30,25)));
        js.add(joint(w,t,la,tw.apply(v(-.1f,.12f,0)),l,u,v(-80,-30,-50),v(80,30,50)));js.add(joint(w,t,ra,tw.apply(v(.1f,.12f,0)),l,u,v(-80,-30,-50),v(80,30,50)));
        addFixedProxyJoints(w,js,t,tw,lw,rw);
    }

    private static void buildStriderJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody proxyHead,PhysicsBody ll,PhysicsBody rl,PhysicsBody p1,PhysicsBody p2,Function<Vector3f,Vector3f> tw,boolean baby){
        float s=baby?.5f:1f;Vector3f z=v(0,0,0),a=v(-1,-1,-1),b=v(1,1,1),l=v(-.01f*s,-.01f*s,-.01f*s),u=v(.01f*s,.01f*s,.01f*s);
        js.add(joint(w,t,ll,tw.apply(v(-.25f*s,-.4f*s,0)),l,u,v(-55,-12,-15),v(55,12,15)));js.add(joint(w,t,rl,tw.apply(v(.25f*s,-.4f*s,0)),l,u,v(-55,-12,-15),v(55,12,15)));
        js.add(joint(w,t,proxyHead,tw.apply(z),z,z,a,b));js.add(joint(w,t,p1,tw.apply(z),z,z,a,b));js.add(joint(w,t,p2,tw.apply(z),z,z,a,b));
    }

    private static void buildSnowGolemJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody h,PhysicsBody lower,PhysicsBody proxy,PhysicsBody la,PhysicsBody ra,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.01f,-.01f,-.01f),u=v(.01f,.01f,.01f);
        js.add(joint(w,t,h,tw.apply(v(0,.28f,0)),l,u,v(-30,-30,-20),v(40,30,20)));js.add(joint(w,t,lower,tw.apply(v(0,-.3f,0)),l,u,v(-20,-15,-15),v(20,15,15)));
        addFixedProxyJoints(w,js,t,tw,proxy,la,ra);
    }

    private static void buildRadialJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody a,PhysicsBody b,PhysicsBody c,PhysicsBody d,PhysicsBody e,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.01f,-.01f,-.01f),u=v(.01f,.01f,.01f),al=v(-45,-45,-45),au=v(45,45,45),p=tw.apply(v(0,0,0));
        js.add(joint(w,t,a,p,l,u,al,au));js.add(joint(w,t,b,p,l,u,al,au));js.add(joint(w,t,c,p,l,u,al,au));js.add(joint(w,t,d,p,l,u,al,au));js.add(joint(w,t,e,p,l,u,al,au));
    }

    private static void buildSpiderJoints(PhysicsWorld w,List<PhysicsConstraint> js,List<PhysicsBody> parts,Function<Vector3f,Vector3f> tw,float s){
        PhysicsBody t=parts.get(0),h=parts.get(1);
        Vector3f l=v(-.01f*s,-.01f*s,-.01f*s),u=v(.01f*s,.01f*s,.01f*s);
        js.add(joint(w,t,h,tw.apply(v(0,0,-.42f*s)),l,u,v(-25,-30,-20),v(30,30,20)));
        float[] px={4,-4,4,-4,4,-4,4,-4},pz={2,2,1,1,0,0,-1,-1};
        for(int i=0;i<8;i++){
            Vector3f anchor=tw.apply(v(-px[i]*s/16f,0,(pz[i]-6f)*s/16f));
            js.add(jointAtCurrentPose(w,t,parts.get(i+2),anchor,l,u,v(-50,-35,-60),v(50,35,60)));
        }
    }

    private static void buildGhastJoints(PhysicsWorld w,List<PhysicsConstraint> js,List<PhysicsBody> parts,Function<Vector3f,Vector3f> tw){
        PhysicsBody body=parts.get(0);Vector3f l=v(-.01f,-.01f,-.01f),u=v(.01f,.01f,.01f);
        for(int i=0;i<9;i++){
            float px=((i%3)-(i/3%2)*.5f-.75f)*5f,pz=(i/3-1)*5f,scale=4.5f;
            Vector3f anchor=tw.apply(v(-px*scale/16f,-7f*scale/16f,pz*scale/16f));
            js.add(jointAtCurrentPose(w,body,parts.get(i+1),anchor,l,u,v(-35,-18,-35),v(35,18,35)));
        }
    }

    private static void buildShulkerJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody lid,PhysicsBody p2,PhysicsBody p3,PhysicsBody p4,PhysicsBody p5,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.006f,-.006f,-.006f),u=v(.006f,.006f,.006f);
        js.add(joint(w,t,lid,tw.apply(v(0,.25f,0)),l,u,v(-18,-12,-18),v(18,12,18)));
        addFixedProxyJoints(w,js,t,tw,p2,p3,p4,p5);
    }

    private static void buildVexJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody h,PhysicsBody p2,PhysicsBody p3,PhysicsBody la,PhysicsBody ra,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.003f,-.003f,-.003f),u=v(.003f,.003f,.003f);
        js.add(joint(w,t,h,tw.apply(v(0,.16f,0)),l,u,v(-35,-30,-25),v(45,30,25)));
        js.add(joint(w,t,la,tw.apply(v(-.09f,.04f,0)),l,u,v(-100,-35,-65),v(100,35,65)));
        js.add(joint(w,t,ra,tw.apply(v(.09f,.04f,0)),l,u,v(-100,-35,-65),v(100,35,65)));
        addFixedProxyJoints(w,js,t,tw,p2,p3);
    }

    private static void buildWardenJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody h,PhysicsBody ll,PhysicsBody rl,PhysicsBody la,PhysicsBody ra,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.015f,-.015f,-.015f),u=v(.015f,.015f,.015f);
        js.add(joint(w,t,h,tw.apply(v(0,.65f,0)),l,u,v(-35,-35,-25),v(45,35,25)));
        js.add(joint(w,t,ll,tw.apply(v(-.37f,-.65f,0)),l,u,v(-45,-15,-18),v(65,15,18)));
        js.add(joint(w,t,rl,tw.apply(v(.37f,-.65f,0)),l,u,v(-45,-15,-18),v(65,15,18)));
        js.add(joint(w,t,la,tw.apply(v(-.56f,.15f,.06f)),l,u,v(-100,-35,-45),v(100,35,45)));
        js.add(joint(w,t,ra,tw.apply(v(.56f,.15f,.06f)),l,u,v(-100,-35,-45),v(100,35,45)));
    }

    private static void addFixedProxyJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,Function<Vector3f,Vector3f> tw,PhysicsBody... proxies){
        Vector3f z=v(0,0,0),tinyLow=v(-1,-1,-1),tinyHigh=v(1,1,1),anchor=tw.apply(z);
        for(PhysicsBody proxy:proxies) js.add(joint(w,t,proxy,anchor,z,z,tinyLow,tinyHigh));
    }

    private static void buildEquineJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody fl, PhysicsBody fr, PhysicsBody hl, PhysicsBody hr,
            PhysTransform tHead, PhysTransform tFL, PhysTransform tFR, PhysTransform tHL, PhysTransform tHR,
            Function<Vector3f, Vector3f> tw, BodyProfile profile, boolean isBaby) {
        float rs = equineRenderScale(profile);
        float bs = isBaby ? 0.5f : 1.0f;
        Vector3f zero = v(0,0,0);
        joints.add(joint(world, torso, head,
                tw.apply(isBaby ? v(0f, 0.15f*rs, -0.30f*rs)
                        : v(0f, 0.25f*rs, -0.6875f*rs)), zero, zero,
                v(-45,-35,-25), v(55,35,25)));
        Vector3f linL = v(-0.015f,-0.015f,-0.015f), linU = v(0.015f,0.015f,0.015f);
        Vector3f angL = v(-55,-12,-15), angU = v(55,12,15);
        float jointX = (isBaby ? 0.125f : 0.25f) * rs;
        float jointY = (isBaby ? -0.12f : -0.375f) * rs;
        float frontJointZ = (isBaby ? -0.278125f : -0.5625f) * rs;
        float hindJointZ = (isBaby ? 0.28f : 0.5f) * rs;
        joints.add(joint(world, torso, fl, tw.apply(v(-jointX,jointY,frontJointZ)), linL, linU, angL, angU));
        joints.add(joint(world, torso, fr, tw.apply(v( jointX,jointY,frontJointZ)), linL, linU, angL, angU));
        joints.add(joint(world, torso, hl, tw.apply(v(-jointX,jointY,hindJointZ)), linL, linU, angL, angU));
        joints.add(joint(world, torso, hr, tw.apply(v( jointX,jointY,hindJointZ)), linL, linU, angL, angU));
    }

    private static void buildCreeperJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody fl, PhysicsBody fr, PhysicsBody bl, PhysicsBody br,
            PhysTransform tHead, Function<Vector3f, Vector3f> tw, float s) {
        // The anchors buildCreeper hung the parts from; the legs used to be jointed on the mirrored side, which pinned every captured leg to a point across the body.
        joints.add(joint(world, torso, head, mid(tw.apply(creeperNeck(s)), onBody(tHead, new Vector3f(0f, -CREEPER_HEAD_HALF*s, 0f))), v(0,0,0), v(0,0,0), v(-20,-20,-20), v(20,20,20)));
        float ll = 14;
        Vector3f lin = v(0f,0f,0f), liu = v(0f,0f,0f), al = v(-ll,-5,-ll), au = v(ll,5,ll);
        joints.add(joint(world, torso, fl, tw.apply(creeperHip(s, -1f, -1f)), lin, liu, al, au));
        joints.add(joint(world, torso, fr, tw.apply(creeperHip(s,  1f, -1f)), lin, liu, al, au));
        joints.add(joint(world, torso, bl, tw.apply(creeperHip(s, -1f,  1f)), lin, liu, al, au));
        joints.add(joint(world, torso, br, tw.apply(creeperHip(s,  1f,  1f)), lin, liu, al, au));
    }

    private static void buildQuadJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody fl, PhysicsBody fr, PhysicsBody hl, PhysicsBody hr,
            PhysTransform tHead, PhysTransform tFL, PhysTransform tFR, PhysTransform tHL, PhysTransform tHR,
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

    private static void buildChickenJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody lw, PhysicsBody rw, PhysicsBody ll, PhysicsBody rl,
            PhysTransform tHead, PhysTransform tLW, PhysTransform tRW, PhysTransform tLL, PhysTransform tRL,
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

    // Bat and bee joints anchor at body-origin midpoints rather than torso-local offsets, so they hold
    // at any model-specific build scale; the shared `s` here is the unscaled body scale.
    private static void buildBatJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody lWing, PhysicsBody rWing, PhysicsBody lStub, PhysicsBody rStub,
            PhysTransform tHead, PhysTransform tLWing, PhysTransform tRWing, PhysTransform tLStub, PhysTransform tRStub,
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

    private static void buildBeeJoints(PhysicsWorld world, List<PhysicsConstraint> joints,
            PhysicsBody torso, PhysicsBody head, PhysicsBody lWing, PhysicsBody rWing, PhysicsBody lStub, PhysicsBody rStub,
            PhysTransform tHead, PhysTransform tLWing, PhysTransform tRWing, PhysTransform tLStub, PhysTransform tRStub,
            Function<Vector3f, Vector3f> tw, float s) {
        Vector3f torsoO = tw.apply(v(0f, 0f, 0f));
        joints.add(joint(world, torso, head, mid(torsoO, tHead.origin), v(0,0,0), v(0,0,0), v(-30,-30,-30), v(30,30,30)));
        joints.add(joint(world, torso, lWing, mid(torsoO, tLWing.origin), v(0,0,0), v(0,0,0), v(-60,-50,-60), v(60,50,60)));
        joints.add(joint(world, torso, rWing, mid(torsoO, tRWing.origin), v(0,0,0), v(0,0,0), v(-60,-50,-60), v(60,50,60)));
        // unused leg stubs welded to the torso (legs stay rendered on the body instead)
        joints.add(joint(world, torso, lStub, tLStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
        joints.add(joint(world, torso, rStub, tRStub.origin, v(0,0,0), v(0,0,0), v(-1,-1,-1), v(1,1,1)));
    }

    // Low-level helpers

    public static PhysicsBody makePart(PhysicsWorld world, PhysicsShape shape,
                                       Vector3f position, Quat4f rotation,
                                       float mass, Vector3f initialVel) {
        BodyProperties properties = PART_PROPERTIES.reset();
        properties.mass = mass * (float) RagdollifiedConfig.get(RagdollifiedConfig.MASS_SCALE);
        properties.linearDamping = (float) RagdollifiedConfig.get(RagdollifiedConfig.LINEAR_DAMPING);
        properties.angularDamping = (float) RagdollifiedConfig.get(RagdollifiedConfig.ANGULAR_DAMPING);
        properties.restitution = (float) RagdollifiedConfig.get(RagdollifiedConfig.RESTITUTION);
        properties.friction = (float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION);
        properties.sleepingLinearThreshold = 0.3f;
        properties.sleepingAngularThreshold = 0.3f;
        // Sleeping stays off: a slept body also stops receiving gravity, so one that woke from a
        // floor break with zero velocity floated. Manual settle detection is authoritative.
        properties.allowSleeping = false;
        // No initial-velocity clamp: the per-tick clamp handles excess, while capping at construction
        // would erase death-time momentum, and sprint speed alone already exceeds any low cap.
        properties.linearVelocity.set(initialVel);

        if (shape.isBox()) {
            shape.getHalfExtents(scratchHalfExtents);
            float smallestHalfExtent = Math.min(scratchHalfExtents.x,
                    Math.min(scratchHalfExtents.y, scratchHalfExtents.z));
            properties.ccdSweptSphereRadius = Math.max(0.005f, smallestHalfExtent * 0.8f);
            properties.ccdMotionThreshold = 0.15f;
        }

        PhysicsBody body = world.createDynamicBody(shape, position, rotation, properties);
        if (body == null) {
            // A native backend refuses bodies once its preallocated pool is full. Saying so here is
            // what turns that into a legible log line rather than a null dereference a frame later.
            throw new IllegalStateException(
                    "physics backend refused a ragdoll body (body budget exhausted?)");
        }
        world.addBody(body);
        return body;
    }

    // Reused across the six bodies of one ragdoll. Body building runs only on the physics worker.
    private static final BodyProperties PART_PROPERTIES = new BodyProperties();
    private static final Vector3f scratchHalfExtents = new Vector3f();


    private static void buildGuardianJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody t0,PhysicsBody t1,PhysicsBody t2,PhysicsBody p4,PhysicsBody p5,Function<Vector3f,Vector3f> tw,float s){
        Vector3f l=v(-.01f*s,-.01f*s,-.01f*s),u=v(.01f*s,.01f*s,.01f*s),al=v(-30,-25,-25),au=v(30,25,25);
        js.add(joint(w,t,t0,tw.apply(v(0,0,.5f*s)),l,u,al,au));
        js.add(joint(w,t0,t1,tw.apply(v(0,0,.875f*s)),l,u,al,au));
        js.add(joint(w,t1,t2,tw.apply(v(0,0,1.3125f*s)),l,u,al,au));
        addFixedProxyJoints(w,js,t,tw,p4,p5);
    }

    private static void buildSquidJoints(PhysicsWorld w,List<PhysicsConstraint> js,List<PhysicsBody> parts,Function<Vector3f,Vector3f> tw){
        PhysicsBody body=parts.get(0);Vector3f l=v(-.008f,-.008f,-.008f),u=v(.008f,.008f,.008f);
        for(int k=0;k<8;k++){
            double ang=k*Math.PI*2.0/8.0;
            Vector3f anchor=tw.apply(v(-(float)Math.cos(ang)*.3125f,-.4375f,(float)Math.sin(ang)*.3125f));
            js.add(jointAtCurrentPose(w,body,parts.get(k+1),anchor,l,u,v(-55,-25,-55),v(55,25,55)));
        }
    }

    // Trunk / nose / tail chain shared by the dolphin and the axolotl: headZ and tailZ are the anchor
    // planes between the trunk and each end, in the factory's local Z.
    private static void buildFinnedJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody head,PhysicsBody tail,PhysicsBody p3,PhysicsBody p4,PhysicsBody p5,Function<Vector3f,Vector3f> tw,float headZ,float tailZ){
        Vector3f l=v(-.006f,-.006f,-.006f),u=v(.006f,.006f,.006f);
        js.add(joint(w,t,head,tw.apply(v(0,0,headZ)),l,u,v(-25,-25,-20),v(25,25,20)));
        js.add(joint(w,t,tail,tw.apply(v(0,0,tailZ)),l,u,v(-30,-30,-25),v(30,30,25)));
        addFixedProxyJoints(w,js,t,tw,p3,p4,p5);
    }

    private static void buildFishJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody tail,PhysicsBody p2,PhysicsBody p3,PhysicsBody p4,PhysicsBody p5,Function<Vector3f,Vector3f> tw,BodyProfile profile){
        float[] d=fishDimensions(profile);
        Vector3f l=v(-.004f,-.004f,-.004f),u=v(.004f,.004f,.004f);
        if(d[6]==0f) addFixedProxyJoints(w,js,t,tw,tail);
        else js.add(joint(w,t,tail,tw.apply(v(0,0,d[2])),l,u,v(-30,-25,-20),v(30,25,20)));
        addFixedProxyJoints(w,js,t,tw,p2,p3,p4,p5);
    }

    private static void buildWitherJoints(PhysicsWorld w,List<PhysicsConstraint> js,PhysicsBody t,PhysicsBody h,PhysicsBody tail,PhysicsBody proxy,PhysicsBody lh,PhysicsBody rh,Function<Vector3f,Vector3f> tw){
        Vector3f l=v(-.02f,-.02f,-.02f),u=v(.02f,.02f,.02f);
        js.add(joint(w,t,h,tw.apply(v(0,.73f,-.125f)),l,u,v(-35,-40,-25),v(45,40,25)));
        js.add(joint(w,t,lh,tw.apply(v(-.78f,.85f,-.25f)),l,u,v(-30,-40,-30),v(30,40,30)));
        js.add(joint(w,t,rh,tw.apply(v(.78f,.85f,-.25f)),l,u,v(-30,-40,-30),v(30,40,30)));
        js.add(joint(w,t,tail,tw.apply(v(.0625f,-.69f,.2535f)),l,u,v(-40,-20,-20),v(40,20,20)));
        addFixedProxyJoints(w,js,t,tw,proxy);
    }

    private static void buildEnderDragonJoints(PhysicsWorld w,List<PhysicsConstraint> js,List<PhysicsBody> parts,Function<Vector3f,Vector3f> tw){
        PhysicsBody body=parts.get(0),head=parts.get(1),neck=parts.get(8),tail=parts.get(9);
        Vector3f l=v(-.05f,-.05f,-.05f),u=v(.05f,.05f,.05f);
        js.add(joint(w,body,neck,tw.apply(v(0,-.15f,-2.28f)),l,u,v(-30,-35,-20),v(30,35,20)));
        js.add(joint(w,neck,head,tw.apply(v(0,-.3125f,-4.72f)),l,u,v(-35,-40,-25),v(35,40,25)));
        js.add(joint(w,body,tail,tw.apply(v(0,.31f,1.97f)),l,u,v(-30,-35,-20),v(30,35,20)));
        js.add(joint(w,body,parts.get(2),tw.apply(v(-.75f,-.4375f,-1.375f)),l,u,v(-45,-20,-30),v(45,20,30)));
        js.add(joint(w,body,parts.get(3),tw.apply(v(.75f,-.4375f,-1.375f)),l,u,v(-45,-20,-30),v(45,20,30)));
        js.add(joint(w,body,parts.get(6),tw.apply(v(-1f,-.1875f,1.125f)),l,u,v(-45,-20,-30),v(45,20,30)));
        js.add(joint(w,body,parts.get(7),tw.apply(v(1f,-.1875f,1.125f)),l,u,v(-45,-20,-30),v(45,20,30)));
        js.add(joint(w,body,parts.get(4),tw.apply(v(-.75f,.5f,.3125f)),l,u,v(-25,-20,-55),v(25,20,55)));
        js.add(joint(w,body,parts.get(5),tw.apply(v(.75f,.5f,.3125f)),l,u,v(-25,-20,-55),v(25,20,55)));
    }

    private static PhysicsShape box(PhysicsWorld world, Vector3f halfExtents) {
        return world.createBoxShape(halfExtents.x, halfExtents.y, halfExtents.z);
    }

    public static PhysicsBody makeStaticBody(PhysicsWorld world, PhysicsShape shape, PhysTransform t) {
        PhysicsBody body = world.createStaticBody(shape, t);
        world.addBody(body);
        return body;
    }

    public static PhysicsConstraint joint(PhysicsWorld world,
                                              PhysicsBody a, PhysicsBody b, Vector3f anchor,
                                              Vector3f linL, Vector3f linU,
                                              Vector3f angLDeg, Vector3f angUDeg) {
        return joint(world, a, b, anchor, linL, linU, angLDeg, angUDeg, false, false);
    }

    // keepCollision leaves the two parts colliding with each other; see PhysicsConstraint. Only safe
    // for a pair whose authored shapes do not already overlap.
    public static PhysicsConstraint joint(PhysicsWorld world,
                                              PhysicsBody a, PhysicsBody b, Vector3f anchor,
                                              Vector3f linL, Vector3f linU,
                                              Vector3f angLDeg, Vector3f angUDeg,
                                              boolean keepCollision) {
        return joint(world, a, b, anchor, linL, linU, angLDeg, angUDeg, keepCollision, false);
    }

    // Build one joint and add it to the world. bakeCurrentPose makes the built pose the neutral (for
    // rigs that aren't axis-aligned); never set it on a humanoid, or its death pose becomes permanent.
    public static PhysicsConstraint joint(PhysicsWorld world,
                                              PhysicsBody a, PhysicsBody b, Vector3f anchor,
                                              Vector3f linL, Vector3f linU,
                                              Vector3f angLDeg, Vector3f angUDeg,
                                              boolean keepCollision, boolean bakeCurrentPose) {
        PhysTransform ta = wt(a), tb = wt(b);
        PhysTransform localA = new PhysTransform(); localA.setIdentity();
        localA.origin.set(toLocal(ta, anchor));
        PhysTransform localB = new PhysTransform(); localB.setIdentity();
        localB.origin.set(toLocal(tb, anchor));
        if (bakeCurrentPose) {
            localB.basis.transpose(tb.basis);
            localB.basis.mul(ta.basis);
        }

        Vector3f[] angular = angularLimits(angLDeg, angUDeg);
        PhysicsConstraint c = world.createSixDofConstraint(a, b, localA, localB,
                linL, linU, angular[0], angular[1]);
        c.setKeepsCollision(keepCollision);

        a.activate(); b.activate();
        world.addConstraint(c);
        return c;
    }

    private static PhysicsConstraint jointAtCurrentPose(PhysicsWorld world,
            PhysicsBody a, PhysicsBody b, Vector3f anchor, Vector3f linL, Vector3f linU,
            Vector3f angLDeg, Vector3f angUDeg) {
        return joint(world, a, b, anchor, linL, linU, angLDeg, angUDeg, false, true);
    }

    // The widest a limit is allowed to get once scaled. Past this a joint stops resisting at all and
    // the axis may as well have been freed outright, which freeAngularAxes does deliberately.
    private static final float MAX_LIMIT_DEG = 175f;

    // Angular limits in radians: authored degrees, twist/roll widened by rangeScale or freed entirely.
    // Pitch is left unscaled. Returns lower limits at [0] and upper at [1].
    private static Vector3f[] angularLimits(Vector3f lowerDeg, Vector3f upperDeg) {
        if (RagdollifiedConfig.get(RagdollifiedConfig.FREE_ANGULAR_AXES)) {
            // lower > upper is how both backends spell "free axis"; see PhysicsWorld.
            return new Vector3f[]{v(1f, 1f, 1f), v(-1f, -1f, -1f)};
        }
        float scale = (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RANGE_SCALE);
        if (scale == 1f) return new Vector3f[]{rad(lowerDeg), rad(upperDeg)};
        return new Vector3f[]{
                rad(v(lowerDeg.x, clampLimitDeg(lowerDeg.y * scale), clampLimitDeg(lowerDeg.z * scale))),
                rad(v(upperDeg.x, clampLimitDeg(upperDeg.y * scale), clampLimitDeg(upperDeg.z * scale)))
        };
    }

    private static float clampLimitDeg(float deg) {
        return Math.max(-MAX_LIMIT_DEG, Math.min(MAX_LIMIT_DEG, deg));
    }

    // Math utilities (package-visible for tests, private use)

    public static Quat4f mul(Quat4f q1, Quat4f q2) {
        float w = q1.w*q2.w - q1.x*q2.x - q1.y*q2.y - q1.z*q2.z;
        float x = q1.w*q2.x + q1.x*q2.w + q1.y*q2.z - q1.z*q2.y;
        float y = q1.w*q2.y - q1.x*q2.z + q1.y*q2.w + q1.z*q2.x;
        float z = q1.w*q2.z + q1.x*q2.y - q1.y*q2.x + q1.z*q2.w;
        return new Quat4f(x, y, z, w);
    }

    // The orientation this part was drawn at, or the plain body rotation when nothing was captured.
    private static Quat4f poseRot(MobPoseCapture.MobPose pose, RagdollPart part, Quat4f baseQuat) {
        return pose == null ? baseQuat : pose.rotationFor(part, baseQuat);
    }

    // Where the part's body goes: the centre it was drawn at, falling back to the authored placement
    // for a part with no captured transform.
    private static Vector3f posePos(MobPoseCapture.MobPose pose, RagdollPart part, Vector3f authored) {
        if (pose == null) return authored;
        Vector3f captured = pose.centerFor(part);
        return captured != null ? captured : authored;
    }

    // Body size as drawn, falling back to authored; the humanoid rig is a topology, not fixed proportions.
    private static Vector3f poseBox(MobPoseCapture.MobPose pose, RagdollPart part, Vector3f authored) {
        if (pose == null) return authored;
        Vector3f captured = pose.extentsFor(part);
        return captured != null ? new Vector3f(captured) : authored;
    }

    // The renderer converts physics space to model space with a 180-degree Z turn, so conjugating the
    // baked rotation by that turn gives the physics orientation that renders as the original angle.
    private static Quat4f modelPartRotation(Quat4f base,float xRot,float yRot,float zRot){
        Quaternionf combined=new Quaternionf(base.x,base.y,base.z,base.w);
        Quaternionf flip=new Quaternionf().rotationZ((float)Math.PI);
        combined.mul(flip).mul(new Quaternionf().rotationZYX(zRot,yRot,xRot)).mul(new Quaternionf(flip).conjugate());
        return new Quat4f(combined.x,combined.y,combined.z,combined.w);
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

    private static Vector3f offset(Vector3f parentPos, Quat4f parentRot, Vector3f localOffset) {
        Vector3f result = new Vector3f(parentPos);
        result.add(rotQ(parentRot, localOffset));
        return result;
    }

    private static PhysTransform wt(PhysicsBody b) {
        PhysTransform t = new PhysTransform(); b.getWorldTransform(t); return t;
    }

    private static Vector3f toLocal(PhysTransform wt, Vector3f worldPt) {
        Vector3f d = new Vector3f(worldPt); d.sub(wt.origin);
        return rotQ(new Quat4f(-wt.getRotation(new Quat4f()).x, -wt.getRotation(new Quat4f()).y,
                               -wt.getRotation(new Quat4f()).z,  wt.getRotation(new Quat4f()).w), d);
    }

    private static Vector3f mid(Vector3f a, Vector3f b) {
        return new Vector3f((a.x+b.x)*0.5f, (a.y+b.y)*0.5f, (a.z+b.z)*0.5f);
    }

    // Converts a degree-triple to radians in-place and returns a new Vector3f.
    private static Vector3f rad(Vector3f deg) {
        return new Vector3f((float)Math.toRadians(deg.x), (float)Math.toRadians(deg.y), (float)Math.toRadians(deg.z));
    }

    private static Vector3f v(float x, float y, float z) { return new Vector3f(x, y, z); }
}
