package com.raiiiden.ragdollified.tools;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SixDofConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAxis;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.enumerate.ESwingType;

// Drops the humanoid on a floor and measures torso lift (the 'triangle' resting pose).
// -Drig.boxes=legacy uses the old extents; -Drig.trials=N averages N differently toppled drops.
final class RagdollDrop {

    private static final int OBJECT_LAYER_STATIC = 0;
    private static final int OBJECT_LAYER_MOVING = 1;
    private static final int NUM_OBJECT_LAYERS = 3;
    private static final int NUM_BROADPHASE_LAYERS = 2;

    private static final int TORSO = 0, HEAD = 1, LEFT_LEG = 2, RIGHT_LEG = 3, LEFT_ARM = 4, RIGHT_ARM = 5;
    private static final String[] NAMES = {"torso", "head", "left_leg", "right_leg", "left_arm", "right_arm"};

    // Masses as RagdollBodyFactory authors them, and the note there says they are the load-bearing
    // part of this: torso 55% of the body, so it is what reaches the floor first.
    private static final float[] MASSES = {18f, 2.4f, 4.6f, 4.6f, 1.4f, 1.4f};

    private RagdollDrop() {
    }

    static void run() throws Exception {
        boolean legacy = "legacy".equalsIgnoreCase(System.getProperty("rig.boxes", "current"));
        int trials = (int) RagdollRig.property("rig.trials", 8f);
        float seconds = RagdollRig.property("rig.seconds", 8f);
        float armPose = RagdollRig.property("rig.armPose", 0f);
        float motor = RagdollRig.property("rig.motor", 0f);
        float motorDamping = RagdollRig.property("rig.motorDamping", 1.0f);
        float motorTorque = RagdollRig.property("rig.motorTorque", 1.5f);
        // The neck spring is 16x the limbs', the same ratio relaxJoints uses, so the bench mirrors the game.
        float neckMotor = RagdollRig.property("rig.neckMotor", motor <= 0f ? 0f : motor * 16f);
        float headMass = RagdollRig.property("rig.headMass", MASSES[HEAD]);
        // Mirrors the shipped physics block: friction 0.65, angular damping 0.15, and the linear
        // damping split between the parts by area over mass rather than shared flat.
        float friction = RagdollRig.property("rig.friction", 0.65f);
        float angularDamping = RagdollRig.property("rig.angularDamping", 0.15f);
        float linearDamping = RagdollRig.property("rig.linearDamping", 0.10f);
        float differentialDrag = RagdollRig.property("rig.differentialDrag", 1f);
        float armMass = RagdollRig.property("rig.armMass", MASSES[LEFT_ARM]);

        RagdollRig.loadNativeLibrary();
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        if (!Jolt.newFactory()) throw new IllegalStateException("Jolt.newFactory() returned false");
        Jolt.registerTypes();

        float[][] extents = legacy ? legacyExtents() : currentExtents();
        float[] masses = MASSES.clone();
        masses[HEAD] = headMass;
        masses[LEFT_ARM] = masses[RIGHT_ARM] = armMass;

        System.out.printf("motor: %s%n", motor <= 0f ? "off (limits only)"
                : String.format("limbs %.1f Hz / neck %.1f Hz, damping %.1f, max torque %.1f Nm",
                        motor, neckMotor, motorDamping, motorTorque));
        System.out.printf("%s boxes, head %.1fkg, arms %.1fkg, %d trials of %.0fs%n",
                legacy ? "legacy" : "current", headMass, armMass, trials, seconds);
        float[] shownDrag = RagdollRig.humanoidDrag(linearDamping, differentialDrag);
        System.out.printf("friction %.2f, angular damping %.2f, drag split %.2f "
                        + "(torso %.3f, leg %.3f, arm %.3f)%n",
                friction, angularDamping, differentialDrag,
                shownDrag[TORSO], shownDrag[LEFT_LEG], shownDrag[LEFT_ARM]);
        System.out.println("trial  torso-lift  frozen-at  lift-when-frozen  resting-on");

        float liftTotal = 0f;
        int propped = 0;
        float frozenLiftTotal = 0f;
        int frozenEarly = 0;
        for (int trial = 0; trial < trials; trial++) {
            Result result = drop(extents, masses, seconds, trial, armPose,
                    motor, motorDamping, motorTorque, neckMotor,
                    friction, angularDamping, RagdollRig.humanoidDrag(linearDamping, differentialDrag));
            liftTotal += result.torsoLift;
            if (result.torsoLift > 0.02f) propped++;
            if (result.freezeTick() >= 0) frozenEarly += result.liftWhenGameFreezes() > 0.02f ? 1 : 0;
            frozenLiftTotal += Math.max(0f, result.liftWhenGameFreezes());
            System.out.printf("%5d  %9.3f  %8s  %16s  %s%n", trial, result.torsoLift,
                    result.freezeTick() < 0 ? "never" : "t" + result.freezeTick(),
                    result.freezeTick() < 0 ? "-" : String.format("%.3f", result.liftWhenGameFreezes()),
                    result.restingOn);
        }
        System.out.printf("%naverage torso lift: %.3f blocks over %d trials, propped in %d of them%n",
                liftTotal / trials, trials, propped);
        System.out.printf("average lift at the moment the game would freeze it: %.3f blocks"
                + " (propped in %d of %d)%n", frozenLiftTotal / trials, frozenEarly, trials);
        System.out.println(liftTotal / trials <= 0.05f
                ? "VERDICT: left alone, the torso reaches the floor. No triangle in the physics."
                : "VERDICT: the torso is being held off the floor - the body is propping on its ends.");
        if (frozenLiftTotal / trials > 0.05f) {
            System.out.println("VERDICT: the settle gate freezes bodies before they finish"
                    + " collapsing - that is the propping the player sees.");
        }
    }

    // The humanoid exactly as the game builds it (vanilla-cube boxes, masses, collision filter,
    // joints, springs). Shared by the drop and free-fall tests.
    private static Body[] assemble(PhysicsSystem system, BodyInterface bodies, float[][] half,
                                   float[] masses, int trial, float armPose, float baseY,
                                   float friction, float angularDamping, float[] linearDamping,
                                   float motor, float motorDamping, float motorTorque,
                                   float neckMotor) {
        // Jointed pairs stop colliding, exactly as JoltWorld arranges it, except the arms; those
        // keep collision in the game, being the one pair authored flush against the torso.
        GroupFilterTable filter = new GroupFilterTable(6);
        filter.disableCollision(TORSO, HEAD);
        filter.disableCollision(TORSO, LEFT_LEG);
        filter.disableCollision(TORSO, RIGHT_LEG);

        // Torso centre a little above its standing height, tipped a different way each trial so the
        // average is over a spread of landings rather than one lucky pose.
        float lean = 0.35f + 0.1f * (trial % 4);
        float spin = (trial % 2 == 0) ? 1f : -1f;
        Quat tilt = Quat.sEulerAngles(new Vec3(lean * spin, 0.7f * trial, 0.2f * spin));

        Body[] parts = new Body[6];
        float[] offsets = {
                0f, 0f, 0f,
                0f, half[TORSO][1] + half[HEAD][1], 0f,
                -0.11875f, -half[TORSO][1] - half[LEFT_LEG][1], 0f,
                0.11875f, -half[TORSO][1] - half[RIGHT_LEG][1], 0f,
                -(half[TORSO][0] + half[LEFT_ARM][0]), 0f, 0f,
                half[TORSO][0] + half[RIGHT_ARM][0], 0f, 0f};

        // Arms out in front like a zombie's captured death pose, since an arm under the chest can prop the body.
        float armRadians = (float) Math.toRadians(armPose);
        if (armPose != 0f) {
            for (int arm : new int[]{LEFT_ARM, RIGHT_ARM}) {
                float pivotY = half[arm][1] - 0.125f;
                float reach = -half[arm][1] + 0.125f;
                offsets[arm * 3 + 1] = pivotY + (float) (reach * Math.cos(armRadians));
                offsets[arm * 3 + 2] = (float) (reach * Math.sin(armRadians));
            }
        }

        for (int i = 0; i < 6; i++) {
            float[] local = {offsets[i * 3], offsets[i * 3 + 1], offsets[i * 3 + 2]};
            float[] world = rotate(tilt, local[0], local[1], local[2]);
            Quat partRotation = (i == LEFT_ARM || i == RIGHT_ARM) && armPose != 0f
                    ? multiply(tilt, Quat.sEulerAngles(new Vec3(armRadians, 0f, 0f)))
                    : tilt;
            BodyCreationSettings creation = new BodyCreationSettings(
                    new BoxShape(new Vec3(half[i][0], half[i][1], half[i][2]),
                            Math.min(0.05f, Math.min(half[i][0], Math.min(half[i][1], half[i][2])) * 0.5f)),
                    new RVec3(world[0], baseY + world[1], world[2]), partRotation,
                    EMotionType.Dynamic, OBJECT_LAYER_MOVING);
            MassProperties mass = new MassProperties();
            mass.setMass(masses[i]);
            creation.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
            creation.setMassPropertiesOverride(mass);
            creation.setLinearDamping(linearDamping[i]);
            creation.setAngularDamping(angularDamping);
            creation.setFriction(friction);
            creation.setRestitution(0.0f);
            creation.setAllowSleeping(false);
            parts[i] = bodies.createBody(creation);
            parts[i].setCollisionGroup(new CollisionGroup(filter, 1, i));
            bodies.addBody(parts[i].getId(), EActivation.Activate);
        }

        joint(system, parts[TORSO], parts[HEAD], 0f, half[TORSO][1], 0f, 0f, -half[HEAD][1], 0f,
                -40, -35, -35, 40, 55, 35, neckMotor, motorDamping, motorTorque);
        joint(system, parts[TORSO], parts[LEFT_LEG], -0.11875f, -half[TORSO][1], 0f,
                0f, half[LEFT_LEG][1], 0f, -55, -35, -45, 75, 35, 45, motor, motorDamping, motorTorque);
        joint(system, parts[TORSO], parts[RIGHT_LEG], 0.11875f, -half[TORSO][1], 0f,
                0f, half[RIGHT_LEG][1], 0f, -55, -35, -45, 75, 35, 45, motor, motorDamping, motorTorque);
        joint(system, parts[TORSO], parts[LEFT_ARM],
                -(half[TORSO][0] + half[LEFT_ARM][0] * 0.5f), half[LEFT_ARM][1] - 0.125f, 0f,
                half[LEFT_ARM][0] * 0.5f, half[LEFT_ARM][1] - 0.125f, 0f, -120, -50, -95, 120, 50, 95, motor, motorDamping, motorTorque);
        joint(system, parts[TORSO], parts[RIGHT_ARM],
                half[TORSO][0] + half[RIGHT_ARM][0] * 0.5f, half[RIGHT_ARM][1] - 0.125f, 0f,
                -half[RIGHT_ARM][0] * 0.5f, half[RIGHT_ARM][1] - 0.125f, 0f, -120, -50, -95, 120, 50, 95, motor, motorDamping, motorTorque);
        return parts;
    }

    // Whole-body free fall with no floor; reports each part's rotation from the torso (only air causes it).
    static void freefall() throws Exception {
        float seconds = RagdollRig.property("rig.seconds", 4f);
        float differentialDrag = RagdollRig.property("rig.differentialDrag", 1f);
        float linear = RagdollRig.property("rig.linearDamping", 0.10f);
        float angularDamping = RagdollRig.property("rig.angularDamping", 0.15f);
        float friction = RagdollRig.property("rig.friction", 0.65f);
        float motor = RagdollRig.property("rig.motor", 0.3f);
        float motorDamping = RagdollRig.property("rig.motorDamping", 0.5f);
        float motorTorque = RagdollRig.property("rig.motorTorque", 1.5f);
        float neckMotor = RagdollRig.property("rig.neckMotor", motor <= 0f ? 0f : motor * 16f);
        float armPose = RagdollRig.property("rig.armPose", 0f);
        int collisionSteps = (int) RagdollRig.property("rig.collisionSteps", 3f);

        RagdollRig.loadNativeLibrary();
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        if (!Jolt.newFactory()) throw new IllegalStateException("Jolt.newFactory() returned false");
        Jolt.registerTypes();

        BroadPhaseLayerInterfaceTable layerMap =
                new BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_STATIC, 0);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, 1);
        ObjectLayerPairFilterTable pairFilter = new ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_STATIC);
        ObjectVsBroadPhaseLayerFilterTable broadPhase = new ObjectVsBroadPhaseLayerFilterTable(
                layerMap, NUM_BROADPHASE_LAYERS, pairFilter, NUM_OBJECT_LAYERS);

        PhysicsSystem system = new PhysicsSystem();
        system.init(64, 0, 1024, 1024, layerMap, broadPhase, pairFilter);
        PhysicsSettings settings = system.getPhysicsSettings();
        settings.setAllowSleeping(false);
        settings.setPenetrationSlop(0.01f);
        system.setPhysicsSettings(settings);
        system.setGravity(new Vec3(0f, -15f, 0f));
        BodyInterface bodies = system.getBodyInterfaceNoLock();

        float[] drag = RagdollRig.humanoidDrag(linear, differentialDrag);
        // trial 0 with no lean: straight upright, which is the pose the complaint is about.
        Body[] parts = assemble(system, bodies, currentExtents(), MASSES, -1, armPose, 200f,
                friction, angularDamping, drag, motor, motorDamping, motorTorque, neckMotor);
        system.optimizeBroadPhase();

        TempAllocatorImpl temp = new TempAllocatorImpl(32 * 1024 * 1024);
        JobSystemThreadPool jobs = new JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1);

        System.out.printf("free fall, no floor. differentialDrag %.2f -> torso %.3f, leg %.3f, arm %.3f%n",
                differentialDrag, drag[TORSO], drag[LEFT_LEG], drag[LEFT_ARM]);
        System.out.printf("relax limbs %.2f Hz / neck %.2f Hz, damping %.2f%n",
                motor, neckMotor, motorDamping);
        System.out.println("tick  fall-speed     head      l-leg      l-arm      r-arm");

        RVec3 at = new RVec3();
        Quat torsoRot = new Quat();
        Quat partRot = new Quat();
        float[] peak = new float[6];
        int ticks = Math.round(seconds * 20f);
        for (int tick = 0; tick <= ticks; tick++) {
            parts[TORSO].getPositionAndRotation(at, torsoRot);
            float[] swept = new float[6];
            for (int i = 0; i < 6; i++) {
                parts[i].getPositionAndRotation(at, partRot);
                swept[i] = RagdollRig.relativeAngle(torsoRot, partRot);
                if (swept[i] > peak[i]) peak[i] = swept[i];
            }
            if (tick % 10 == 0) {
                System.out.printf("%4d  %8.2f  %8.2f  %9.2f  %9.2f  %9.2f%n",
                        tick, -RagdollRig.length(bodies.getLinearVelocity(parts[TORSO].getId())),
                        swept[HEAD], swept[LEFT_LEG], swept[LEFT_ARM], swept[RIGHT_ARM]);
            }
            system.update(1f / 20f, collisionSteps, temp, jobs);
        }

        System.out.printf("%npeak sweep from torso: head %.1f, legs %.1f/%.1f, arms %.1f/%.1f deg%n",
                peak[HEAD], peak[LEFT_LEG], peak[RIGHT_LEG], peak[LEFT_ARM], peak[RIGHT_ARM]);
        float limbs = Math.max(Math.max(peak[LEFT_ARM], peak[RIGHT_ARM]),
                Math.max(peak[LEFT_LEG], peak[RIGHT_LEG]));
        System.out.println(limbs > 5f
                ? "VERDICT: the limbs trail. A falling body is not a statue."
                : "VERDICT: nothing moved. The body falls in the pose it left with.");
    }

    private static float[][] currentExtents() {
        return new float[][]{
                {0.25f, 0.375f, 0.15f},
                {0.25f, 0.25f, 0.25f},
                {0.125f, 0.375f, 0.125f},
                {0.125f, 0.375f, 0.125f},
                {0.125f, 0.375f, 0.125f},
                {0.125f, 0.375f, 0.125f}};
    }

    // What the rig was before the boxes were measured off the model: a shorter head, a taller
    // torso, longer legs, shorter and thinner arms.
    private static float[][] legacyExtents() {
        return new float[][]{
                {0.25f, 0.4f, 0.15f},
                {0.2f, 0.2f, 0.2f},
                {0.15f, 0.45f, 0.15f},
                {0.15f, 0.45f, 0.15f},
                {0.1f, 0.35f, 0.1f},
                {0.1f, 0.35f, 0.1f}};
    }

    private record Result(float torsoLift, String restingOn, float liftWhenGameFreezes, int freezeTick) {
    }

    // The game's velocity settle gate: every part under both thresholds, checked every fifth tick for
    // settleDelayTicks worth of checks; the torso lift at that moment is what the player sees.
    private static final float SETTLED_VELOCITY_THRESHOLD = 0.05f;
    private static final float SETTLED_ANG_VELOCITY_THRESHOLD = 0.15f;
    private static final int SETTLE_CHECK_INTERVAL = 5;
    private static final int SETTLE_CHECKS_REQUIRED = 4; // settleDelayTicks 20 / interval 5

    private static Result drop(float[][] half, float[] masses, float seconds, int trial, float armPose,
                               float motor, float motorDamping, float motorTorque, float neckMotor,
                               float friction, float angularDamping, float[] linearDamping) {
        BroadPhaseLayerInterfaceTable layerMap =
                new BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_STATIC, 0);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, 1);
        ObjectLayerPairFilterTable pairFilter = new ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_STATIC);
        ObjectVsBroadPhaseLayerFilterTable broadPhase = new ObjectVsBroadPhaseLayerFilterTable(
                layerMap, NUM_BROADPHASE_LAYERS, pairFilter, NUM_OBJECT_LAYERS);

        PhysicsSystem system = new PhysicsSystem();
        system.init(64, 0, 1024, 1024, layerMap, broadPhase, pairFilter);
        PhysicsSettings settings = system.getPhysicsSettings();
        settings.setAllowSleeping(false);
        settings.setPenetrationSlop(0.01f);
        system.setPhysicsSettings(settings);
        system.setGravity(new Vec3(0f, -15f, 0f));
        BodyInterface bodies = system.getBodyInterfaceNoLock();

        Body floor = bodies.createBody(new BodyCreationSettings(
                new BoxShape(new Vec3(20f, 0.5f, 20f), 0.05f),
                new RVec3(0.0, -0.5, 0.0), new Quat(0f, 0f, 0f, 1f),
                EMotionType.Static, OBJECT_LAYER_STATIC));
        bodies.addBody(floor.getId(), EActivation.DontActivate);

        Body[] parts = assemble(system, bodies, half, masses, trial, armPose, 1.4f,
                friction, angularDamping, linearDamping,
                motor, motorDamping, motorTorque, neckMotor);

        system.optimizeBroadPhase();
        TempAllocatorImpl temp = new TempAllocatorImpl(32 * 1024 * 1024);
        JobSystemThreadPool jobs = new JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1);

        int ticks = Math.round(seconds * 20f);
        RVec3 location = new RVec3();
        Quat orientation = new Quat();
        int quietChecks = 0;
        int freezeTick = -1;
        float liftWhenGameFreezes = -1f;

        for (int tick = 0; tick < ticks; tick++) {
            system.update(1f / 20f, 1, temp, jobs);

            if (freezeTick < 0 && tick % SETTLE_CHECK_INTERVAL == 0) {
                boolean allSlow = true;
                for (Body part : parts) {
                    if (RagdollRig.length(bodies.getLinearVelocity(part.getId())) > SETTLED_VELOCITY_THRESHOLD
                            || RagdollRig.length(bodies.getAngularVelocity(part.getId()))
                            > SETTLED_ANG_VELOCITY_THRESHOLD) {
                        allSlow = false;
                        break;
                    }
                }
                quietChecks = allSlow ? quietChecks + 1 : 0;
                if (quietChecks >= SETTLE_CHECKS_REQUIRED) {
                    freezeTick = tick;
                    parts[TORSO].getPositionAndRotation(location, orientation);
                    liftWhenGameFreezes = Math.max(0f, lowestCorner(location, orientation, half[TORSO]));
                }
            }
        }

        float torsoLift = Float.MAX_VALUE;
        StringBuilder resting = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            parts[i].getPositionAndRotation(location, orientation);
            float lowest = lowestCorner(location, orientation, half[i]);
            if (i == TORSO) torsoLift = lowest;
            if (lowest < 0.02f) {
                if (resting.length() > 0) resting.append('+');
                resting.append(NAMES[i]);
            }
        }
        return new Result(Math.max(0f, torsoLift), resting.length() == 0 ? "nothing" : resting.toString(),
                liftWhenGameFreezes, freezeTick);
    }

    // How far the box's lowest corner sits above the floor: its centre height minus the box's own
    // reach downward, which for a rotated box is the sum of each half extent projected onto -Y.
    private static float lowestCorner(RVec3 location, Quat orientation, float[] half) {
        float[] x = rotate(orientation, half[0], 0f, 0f);
        float[] y = rotate(orientation, 0f, half[1], 0f);
        float[] z = rotate(orientation, 0f, 0f, half[2]);
        float reach = Math.abs(x[1]) + Math.abs(y[1]) + Math.abs(z[1]);
        return (float) location.yy() - reach;
    }

    private static void joint(PhysicsSystem system, Body a, Body b,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float loX, float loY, float loZ, float hiX, float hiY, float hiZ,
                              float motor, float motorDamping, float motorTorque) {
        SixDofConstraintSettings creation = new SixDofConstraintSettings();
        creation.setSpace(EConstraintSpace.LocalToBodyCom);
        creation.setPosition1(new RVec3(ax, ay, az));
        creation.setPosition2(new RVec3(bx, by, bz));
        creation.setAxisX1(new Vec3(1f, 0f, 0f));
        creation.setAxisY1(new Vec3(0f, 1f, 0f));
        creation.setAxisX2(new Vec3(1f, 0f, 0f));
        creation.setAxisY2(new Vec3(0f, 1f, 0f));
        creation.setSwingType(ESwingType.Pyramid);
        RagdollRig.limit(creation, EAxis.TranslationX, -0.02f, 0.02f);
        RagdollRig.limit(creation, EAxis.TranslationY, -0.02f, 0.02f);
        RagdollRig.limit(creation, EAxis.TranslationZ, -0.02f, 0.02f);
        RagdollRig.limit(creation, EAxis.RotationX, radians(loX), radians(hiX));
        RagdollRig.limit(creation, EAxis.RotationY, radians(loY), radians(hiY));
        RagdollRig.limit(creation, EAxis.RotationZ, radians(loZ), radians(hiZ));
        RagdollRig.driveToRest(creation, motor, motorDamping, motorTorque);
        TwoBodyConstraint constraint = creation.create(a, b);
        RagdollRig.startMotors(constraint, motor);
        system.addConstraint(constraint);
    }

    // jolt-jni's Quat has no product operator, and the arm needs the body tilt carried onto its
    // own pose rather than replacing it.
    private static Quat multiply(Quat a, Quat b) {
        float ax = a.getX(), ay = a.getY(), az = a.getZ(), aw = a.getW();
        float bx = b.getX(), by = b.getY(), bz = b.getZ(), bw = b.getW();
        return new Quat(
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static float[] rotate(Quat q, float x, float y, float z) {
        float qx = q.getX(), qy = q.getY(), qz = q.getZ(), qw = q.getW();
        float tx = 2f * (qy * z - qz * y);
        float ty = 2f * (qz * x - qx * z);
        float tz = 2f * (qx * y - qy * x);
        return new float[]{
                x + qw * tx + (qy * tz - qz * ty),
                y + qw * ty + (qz * tx - qx * tz),
                z + qw * tz + (qx * ty - qy * tx)};
    }
}
