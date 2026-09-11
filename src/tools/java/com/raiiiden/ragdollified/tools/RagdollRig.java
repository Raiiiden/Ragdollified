package com.raiiiden.ragdollified.tools;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotorSettings;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SixDofConstraint;
import com.github.stephengold.joltjni.SixDofConstraintSettings;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAxis;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EMotorState;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.enumerate.ESwingType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// Standalone shoulder bench mirroring RagdollBodyFactory and JoltWorld, to test if the joint lets an arm fall.
// Run with gradlew runRagdollRig; -Drig.startAngle/seconds/collisionSteps/angularDamping/shoulderPitch.
public final class RagdollRig {

    // Layers, mirroring JoltWorld.
    private static final int OBJECT_LAYER_STATIC = 0;
    private static final int OBJECT_LAYER_MOVING = 1;
    private static final int NUM_OBJECT_LAYERS = 3;
    private static final int BROADPHASE_LAYER_STATIC = 0;
    private static final int BROADPHASE_LAYER_MOVING = 1;
    private static final int NUM_BROADPHASE_LAYERS = 2;

    // The humanoid rig as the factory builds it.
    private static final float TORSO_HALF_X = 0.25f, TORSO_HALF_Y = 0.375f, TORSO_HALF_Z = 0.15f;
    private static final float LIMB_HALF_X = 0.125f, LIMB_HALF_Y = 0.375f, LIMB_HALF_Z = 0.125f;
    private static final float ARM_MASS = 1.4f;
    private static final float GRAVITY = 15.0f;

    private RagdollRig() {
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("rig.mode", "drop");
        if ("drop".equalsIgnoreCase(mode)) {
            RagdollDrop.run();
            return;
        }
        if ("fall".equalsIgnoreCase(mode)) {
            fall();
            return;
        }
        if ("freefall".equalsIgnoreCase(mode)) {
            RagdollDrop.freefall();
            return;
        }
        float startAngle = floatProperty("rig.startAngle", 90f);
        float seconds = floatProperty("rig.seconds", 3f);
        int collisionSteps = (int) floatProperty("rig.collisionSteps", 1f);
        float angularDamping = floatProperty("rig.angularDamping", 0.10f);
        float shoulderPitch = floatProperty("rig.shoulderPitch", 120f);
        float motor = floatProperty("rig.motor", 0f);
        float motorDamping = floatProperty("rig.motorDamping", 1.0f);
        float motorTorque = floatProperty("rig.motorTorque", 1.5f);

        loadNative();
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        if (!Jolt.newFactory()) throw new IllegalStateException("Jolt.newFactory() returned false");
        Jolt.registerTypes();
        System.out.println("jolt " + Jolt.versionString());

        BroadPhaseLayerInterfaceTable layerMap =
                new BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_STATIC, BROADPHASE_LAYER_STATIC);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, BROADPHASE_LAYER_MOVING);

        ObjectLayerPairFilterTable pairFilter = new ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_STATIC);
        ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter =
                new ObjectVsBroadPhaseLayerFilterTable(layerMap, NUM_BROADPHASE_LAYERS,
                        pairFilter, NUM_OBJECT_LAYERS);

        PhysicsSystem system = new PhysicsSystem();
        system.init(64, 0, 256, 256, layerMap, broadPhaseFilter, pairFilter);
        PhysicsSettings settings = system.getPhysicsSettings();
        settings.setAllowSleeping(false);
        settings.setPenetrationSlop(0.01f);
        system.setPhysicsSettings(settings);
        system.setGravity(new Vec3(0f, -GRAVITY, 0f));

        BodyInterface bodies = system.getBodyInterfaceNoLock();

        // The torso is pinned. Nothing else is in the world, so whatever the arm does is the
        // shoulder's doing and gravity's, and nothing else.
        var torso = bodies.createBody(new BodyCreationSettings(
                new BoxShape(new Vec3(TORSO_HALF_X, TORSO_HALF_Y, TORSO_HALF_Z), 0.05f),
                new RVec3(0.0, 2.0, 0.0), new Quat(0f, 0f, 0f, 1f),
                EMotionType.Static, OBJECT_LAYER_STATIC));
        bodies.addBody(torso.getId(), EActivation.DontActivate);

        // Shoulder pivot and arm centre as buildHumanoidJoints measures them.
        float pivotX = TORSO_HALF_X + LIMB_HALF_X * 0.5f;
        float pivotY = LIMB_HALF_Y - 0.125f;
        Vec3 pivot = new Vec3(pivotX, 2.0f + pivotY, 0f);
        // Arm centre relative to the pivot when hanging, then swung up by the start angle about X.
        float offX = LIMB_HALF_X * 0.5f;
        float offY = -LIMB_HALF_Y + 0.125f;
        double radians = Math.toRadians(startAngle);
        float rotatedY = (float) (offY * Math.cos(radians));
        float rotatedZ = (float) (offY * Math.sin(radians));
        Quat armRotation = Quat.sEulerAngles(new Vec3((float) radians, 0f, 0f));

        BodyCreationSettings armSettings = new BodyCreationSettings(
                new BoxShape(new Vec3(LIMB_HALF_X, LIMB_HALF_Y, LIMB_HALF_Z), 0.05f),
                new RVec3(pivot.getX() + offX, pivot.getY() + rotatedY, pivot.getZ() + rotatedZ),
                armRotation, EMotionType.Dynamic, OBJECT_LAYER_MOVING);
        MassProperties mass = new MassProperties();
        mass.setMass(ARM_MASS);
        armSettings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
        armSettings.setMassPropertiesOverride(mass);
        armSettings.setLinearDamping(0.10f);
        armSettings.setAngularDamping(angularDamping);
        armSettings.setAllowSleeping(false);
        var arm = bodies.createBody(armSettings);
        bodies.addBody(arm.getId(), EActivation.Activate);

        // The constraint the game builds: identity frames in each body's own local space, so the
        // limits are measured from "arm aligned with torso", which is the arm hanging.
        SixDofConstraintSettings creation = new SixDofConstraintSettings();
        creation.setSpace(EConstraintSpace.LocalToBodyCom);
        creation.setPosition1(new RVec3(pivotX, pivotY, 0f));
        // The same anchor, in the arm's own frame: the vector from the arm's centre to the pivot,
        // which is the negation of the centre's offset from it.
        creation.setPosition2(new RVec3(-offX, -offY, 0f));
        creation.setAxisX1(new Vec3(1f, 0f, 0f));
        creation.setAxisY1(new Vec3(0f, 1f, 0f));
        creation.setAxisX2(new Vec3(1f, 0f, 0f));
        creation.setAxisY2(new Vec3(0f, 1f, 0f));
        creation.setSwingType(ESwingType.Pyramid);
        limit(creation, EAxis.TranslationX, -0.02f, 0.02f);
        limit(creation, EAxis.TranslationY, -0.02f, 0.02f);
        limit(creation, EAxis.TranslationZ, -0.02f, 0.02f);
        limit(creation, EAxis.RotationX, (float) Math.toRadians(-shoulderPitch),
                (float) Math.toRadians(shoulderPitch));
        limit(creation, EAxis.RotationY, (float) Math.toRadians(-50), (float) Math.toRadians(50));
        limit(creation, EAxis.RotationZ, (float) Math.toRadians(-95), (float) Math.toRadians(95));
        driveToRest(creation, motor, motorDamping, motorTorque);
        TwoBodyConstraint shoulder = creation.create(torso, arm);
        startMotors(shoulder, motor);
        system.addConstraint(shoulder);

        system.optimizeBroadPhase();

        TempAllocatorImpl temp = new TempAllocatorImpl(32 * 1024 * 1024);
        JobSystemThreadPool jobs = new JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1);

        System.out.printf("motor: %s%n", motor <= 0f ? "off (limits only)"
                : String.format("%.1f Hz, damping %.1f, max torque %.1f Nm", motor, motorDamping, motorTorque));
        System.out.printf("shoulder: arm raised %.0f deg, pitch limit +-%.0f, angular damping %.2f%n",
                startAngle, shoulderPitch, angularDamping);
        System.out.println("tick   angle-from-hanging   angular-speed");

        int ticks = Math.round(seconds * 20f);
        float dt = 1f / 20f;
        RVec3 location = new RVec3();
        Quat orientation = new Quat();
        for (int tick = 0; tick <= ticks; tick++) {
            if (tick % 2 == 0) {
                arm.getPositionAndRotation(location, orientation);
                Vec3 spin = bodies.getAngularVelocity(arm.getId());
                System.out.printf("%4d   %8.2f deg        %6.3f rad/s%n",
                        tick, angleFromHanging(orientation), length(spin));
            }
            system.update(dt, collisionSteps, temp, jobs);
        }

        arm.getPositionAndRotation(location, orientation);
        float finalAngle = angleFromHanging(orientation);
        System.out.printf("%nfinal: %.2f deg from hanging (started at %.0f)%n", finalAngle, startAngle);
        System.out.println(finalAngle < startAngle * 0.25f
                ? "VERDICT: the shoulder lets gravity bring the arm down. Look upstream of the joint."
                : "VERDICT: the arm did NOT fall. The joint itself is holding it.");
    }


    // Relaxation: a soft Jolt position motor per axis toward identity (limb lined up with its parent),
    // with a torque ceiling below what's needed to lift the limb.
    static void driveToRest(SixDofConstraintSettings creation, float frequency, float damping,
                            float maxTorque) {
        if (frequency <= 0f) return;
        for (EAxis axis : new EAxis[]{EAxis.RotationX, EAxis.RotationY, EAxis.RotationZ}) {
            MotorSettings motor = creation.setMotorSettings(axis, new MotorSettings(frequency, damping));
            motor.setTorqueLimits(-maxTorque, maxTorque);
        }
    }

    static void startMotors(TwoBodyConstraint constraint, float frequency) {
        if (frequency <= 0f || !(constraint instanceof SixDofConstraint sixDof)) return;
        for (EAxis axis : new EAxis[]{EAxis.RotationX, EAxis.RotationY, EAxis.RotationZ}) {
            sixDof.setMotorState(axis, EMotorState.Position);
        }
        sixDof.setTargetOrientationCs(new Quat(0f, 0f, 0f, 1f));
    }

    // The humanoid as RagdollBodyFactory authors it, for the drag split below.
    private static final float[][] HUMANOID_HALF = {
            {0.25f, 0.375f, 0.15f}, {0.25f, 0.25f, 0.25f},
            {0.125f, 0.375f, 0.125f}, {0.125f, 0.375f, 0.125f},
            {0.125f, 0.375f, 0.125f}, {0.125f, 0.375f, 0.125f}};
    private static final float[] HUMANOID_MASS = {18f, 2.4f, 4.6f, 4.6f, 1.4f, 1.4f};

    // Per-part linear damping by RagdollBodyFactory#applyAerodynamicDrag's rule,
    // duplicated by hand since the bench doesn't link Minecraft.
    static float[] humanoidDrag(float base, float spread) {
        float[] out = new float[HUMANOID_MASS.length];
        float[] ratio = new float[HUMANOID_MASS.length];
        float areaTotal = 0f;
        float massTotal = 0f;
        for (int i = 0; i < out.length; i++) {
            float a = 2f * HUMANOID_HALF[i][0];
            float b = 2f * HUMANOID_HALF[i][1];
            float c = 2f * HUMANOID_HALF[i][2];
            float area = (a * b + b * c + c * a) * 0.5f;
            ratio[i] = area / HUMANOID_MASS[i];
            areaTotal += area;
            massTotal += HUMANOID_MASS[i];
        }
        float mean = areaTotal / massTotal;
        float weighted = 0f;
        float[] scale = new float[out.length];
        for (int i = 0; i < out.length; i++) {
            float r = Math.max(0.2f, Math.min(12f, ratio[i] / mean));
            scale[i] = spread == 1f ? r : (float) Math.pow(r, spread);
            weighted += HUMANOID_MASS[i] * scale[i];
        }
        float norm = massTotal / weighted;
        for (int i = 0; i < out.length; i++) out[i] = base * scale[i] * norm;
        return out;
    }

    // Free fall with one arm on a torso and no floor, reporting how far the arm swings (only air moves it).
    static void fall() throws Exception {
        float seconds = property("rig.seconds", 3f);
        float spread = property("rig.differentialDrag", 1f);
        float base = property("rig.linearDamping", 0.10f);
        float angularDamping = property("rig.angularDamping", 0.15f);
        int collisionSteps = (int) property("rig.collisionSteps", 3f);
        float motor = property("rig.motor", 0.3f);
        float motorDamping = property("rig.motorDamping", 0.5f);
        float motorTorque = property("rig.motorTorque", 1.5f);

        loadNativeLibrary();
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        if (!Jolt.newFactory()) throw new IllegalStateException("Jolt.newFactory() returned false");
        Jolt.registerTypes();

        BroadPhaseLayerInterfaceTable layerMap =
                new BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_STATIC, BROADPHASE_LAYER_STATIC);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, BROADPHASE_LAYER_MOVING);
        ObjectLayerPairFilterTable pairFilter = new ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING);
        pairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_STATIC);
        ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter =
                new ObjectVsBroadPhaseLayerFilterTable(layerMap, NUM_BROADPHASE_LAYERS,
                        pairFilter, NUM_OBJECT_LAYERS);

        PhysicsSystem system = new PhysicsSystem();
        system.init(64, 0, 256, 256, layerMap, broadPhaseFilter, pairFilter);
        PhysicsSettings settings = system.getPhysicsSettings();
        settings.setAllowSleeping(false);
        system.setPhysicsSettings(settings);
        system.setGravity(new Vec3(0f, -GRAVITY, 0f));
        BodyInterface bodies = system.getBodyInterfaceNoLock();

        float[] drag = humanoidDrag(base, spread);
        float torsoDamping = drag[0];
        float armDamping = drag[4];

        float pivotX = TORSO_HALF_X + LIMB_HALF_X * 0.5f;
        float pivotY = LIMB_HALF_Y - 0.125f;
        float offX = LIMB_HALF_X * 0.5f;
        float offY = -LIMB_HALF_Y + 0.125f;

        Body torso = dynamicBox(bodies, TORSO_HALF_X, TORSO_HALF_Y, TORSO_HALF_Z,
                18f, 0f, 100f, 0f, torsoDamping, angularDamping);
        Body arm = dynamicBox(bodies, LIMB_HALF_X, LIMB_HALF_Y, LIMB_HALF_Z,
                ARM_MASS, pivotX + offX, 100f + pivotY + offY, 0f, armDamping, angularDamping);

        // Jointed pairs stop colliding, as JoltWorld arranges it.
        GroupFilterTable filter = new GroupFilterTable(2);
        filter.disableCollision(0, 1);
        torso.setCollisionGroup(new CollisionGroup(filter, 1, 0));
        arm.setCollisionGroup(new CollisionGroup(filter, 1, 1));

        SixDofConstraintSettings creation = new SixDofConstraintSettings();
        creation.setSpace(EConstraintSpace.LocalToBodyCom);
        creation.setPosition1(new RVec3(pivotX, pivotY, 0f));
        creation.setPosition2(new RVec3(-offX, -offY, 0f));
        creation.setAxisX1(new Vec3(1f, 0f, 0f));
        creation.setAxisY1(new Vec3(0f, 1f, 0f));
        creation.setAxisX2(new Vec3(1f, 0f, 0f));
        creation.setAxisY2(new Vec3(0f, 1f, 0f));
        creation.setSwingType(ESwingType.Pyramid);
        limit(creation, EAxis.TranslationX, -0.02f, 0.02f);
        limit(creation, EAxis.TranslationY, -0.02f, 0.02f);
        limit(creation, EAxis.TranslationZ, -0.02f, 0.02f);
        limit(creation, EAxis.RotationX, (float) Math.toRadians(-120), (float) Math.toRadians(120));
        limit(creation, EAxis.RotationY, (float) Math.toRadians(-50), (float) Math.toRadians(50));
        limit(creation, EAxis.RotationZ, (float) Math.toRadians(-95), (float) Math.toRadians(95));
        driveToRest(creation, motor, motorDamping, motorTorque);
        TwoBodyConstraint shoulder = creation.create(torso, arm);
        startMotors(shoulder, motor);
        system.addConstraint(shoulder);
        system.optimizeBroadPhase();

        TempAllocatorImpl temp = new TempAllocatorImpl(32 * 1024 * 1024);
        JobSystemThreadPool jobs = new JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1);

        System.out.printf("free fall, no floor. differentialDrag %.2f -> torso %.3f, arm %.3f%n",
                spread, torsoDamping, armDamping);
        System.out.printf("relax spring %.1f Hz, damping %.1f%n", motor, motorDamping);
        System.out.println("tick   fall-speed   arm-swept-from-start");

        int ticks = Math.round(seconds * 20f);
        RVec3 torsoAt = new RVec3();
        RVec3 armAt = new RVec3();
        Quat torsoRot = new Quat();
        Quat armRot = new Quat();
        float peak = 0f;
        for (int tick = 0; tick <= ticks; tick++) {
            torso.getPositionAndRotation(torsoAt, torsoRot);
            arm.getPositionAndRotation(armAt, armRot);
            float swept = relativeAngleDegrees(torsoRot, armRot);
            if (swept > peak) peak = swept;
            if (tick % 5 == 0) {
                System.out.printf("%4d   %8.2f b/s   %14.2f deg%n",
                        tick, -bodies.getLinearVelocity(torso.getId()).getY(), swept);
            }
            system.update(1f / 20f, collisionSteps, temp, jobs);
        }
        System.out.printf("%npeak sweep: %.2f deg%n", peak);
        System.out.println(peak > 5f
                ? "VERDICT: the air moves the arm. A falling body is not a statue."
                : "VERDICT: the arm never left the torso. Nothing in free fall can move it.");
    }

    private static Body dynamicBox(BodyInterface bodies, float hx, float hy, float hz, float mass,
                                   float x, float y, float z, float linear, float angular) {
        BodyCreationSettings creation = new BodyCreationSettings(
                new BoxShape(new Vec3(hx, hy, hz), 0.05f),
                new RVec3(x, y, z), new Quat(0f, 0f, 0f, 1f),
                EMotionType.Dynamic, OBJECT_LAYER_MOVING);
        MassProperties properties = new MassProperties();
        properties.setMass(mass);
        creation.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
        creation.setMassPropertiesOverride(properties);
        creation.setLinearDamping(linear);
        creation.setAngularDamping(angular);
        creation.setAllowSleeping(false);
        Body body = bodies.createBody(creation);
        bodies.addBody(body.getId(), EActivation.Activate);
        return body;
    }

    // Total rotation of b relative to a: how far the joint moved for bodies that started aligned.
    // Shared with RagdollDrop's free-fall mode.
    static float relativeAngle(Quat a, Quat b) {
        return relativeAngleDegrees(a, b);
    }

    private static float relativeAngleDegrees(Quat a, Quat b) {
        float w = a.getW() * b.getW() + a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ();
        w = Math.min(1f, Math.abs(w));
        return (float) Math.toDegrees(2.0 * Math.acos(w));
    }

    private static float angleFromHanging(Quat orientation) {
        float[] down = rotate(orientation, 0f, -1f, 0f);
        float cos = Math.max(-1f, Math.min(1f, -down[1]));
        return (float) Math.toDegrees(Math.acos(cos));
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

    static float length(Vec3 v) {
        return (float) Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
    }

    static void limit(SixDofConstraintSettings settings, EAxis axis, float lower, float upper) {
        if (lower > upper) {
            settings.makeFreeAxis(axis);
        } else if (lower == upper) {
            settings.makeFixedAxis(axis);
        } else {
            settings.setLimitedAxis(axis, lower, upper);
        }
    }

    static float property(String key, float fallback) {
        return floatProperty(key, fallback);
    }

    static float floatPropertyPublic(String key, float fallback) {
        return floatProperty(key, fallback);
    }

    private static float floatProperty(String key, float fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // The same extraction JoltNatives does in the game, minus the Forge paths: the natives are on
    // this bench's classpath because build.gradle puts them there.
    static void loadNativeLibrary() throws IOException {
        loadNative();
    }

    private static void loadNative() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        String resource;
        String fileName;
        if (os.contains("win")) {
            resource = "windows/x86-64/com/github/stephengold/joltjni.dll";
            fileName = "joltjni.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            resource = "osx/" + (arm ? "aarch64" : "x86-64") + "/com/github/stephengold/libjoltjni.dylib";
            fileName = "libjoltjni.dylib";
        } else {
            resource = "linux/" + (arm ? "aarch64" : "x86-64") + "/com/github/stephengold/libjoltjni.so";
            fileName = "libjoltjni.so";
        }

        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "ragdollified-rig");
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName);
        try (InputStream in = RagdollRig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("native not on the classpath: " + resource);
            try (OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            if (!Files.exists(target)) throw e;
            // A previous run left it mapped; the copy on disk is the same build.
        }
        System.load(target.toAbsolutePath().toString());
    }
}
