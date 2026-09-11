package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
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
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAxis;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.enumerate.ESwingType;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.physics.BodyProperties;
import com.raiiiden.ragdollified.physics.ContactPair;
import com.raiiiden.ragdollified.physics.ContactVisitor;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import com.raiiiden.ragdollified.physics.StepQuality;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

// Jolt Physics behind the seam. JoltNatives.load() must have succeeded before this class is
// touched; constructing it without that is a JVM crash, not an exception.
public final class JoltWorld implements PhysicsWorld {

    // Separate terrain and ragdoll layers, so static block bodies never test against each other.
    static final int OBJECT_LAYER_STATIC = 0;
    static final int OBJECT_LAYER_MOVING = 1;
    // Collides with nothing: for renderer-only proxy parts, without generating sensor contact events.
    static final int OBJECT_LAYER_PHANTOM = 2;
    private static final int BROADPHASE_LAYER_STATIC = 0;
    private static final int BROADPHASE_LAYER_MOVING = 1;
    private static final int NUM_OBJECT_LAYERS = 3;
    private static final int NUM_BROADPHASE_LAYERS = 2;

    // Body budget, well above the ~5,500 static-body peak; Jolt preallocates and fails creation when full.
    private static final int MAX_BODIES = 32768;
    private static final int MAX_BODY_PAIRS = 32768;
    private static final int MAX_CONTACT_CONSTRAINTS = 10240;
    private static final int TEMP_ALLOCATOR_BYTES = 16 * 1024 * 1024;

    // Static bodies land in the broadphase unsorted; Jolt wants a rebuild after a bulk insert. Doing
    // it per body would cost more than it saves, so it is amortised over a batch.
    private static final int OPTIMIZE_BROADPHASE_AFTER_STATIC_ADDS = 256;

    // Kept as fields purely so the GC cannot collect them while the native system still points at
    // them. Losing any one of these is a use-after-free inside Jolt, not a Java error.
    private final BroadPhaseLayerInterfaceTable layerMap;
    private final ObjectLayerPairFilterTable objectPairFilter;
    private final ObjectVsBroadPhaseLayerFilterTable objectVsBroadPhaseFilter;
    private final TempAllocatorImpl tempAllocator;
    private final JobSystemThreadPool jobSystem;
    private final JoltContactCollector contacts = new JoltContactCollector();

    private final PhysicsSystem system;
    private final BodyInterface bodyInterface;
    private final PhysicsSettings settings;

    // Native body address to wrapper. The contact listener is handed raw addresses, and this is what
    // turns them back into something the ragdoll code can talk about. Written only outside a step.
    private final Long2ObjectOpenHashMap<JoltBody> bodiesByAddress = new Long2ObjectOpenHashMap<>();

    private final RVec3 scratchLocation = new RVec3();
    private final Quat scratchOrientation = new Quat();
    private final Vec3 scratchVector = new Vec3();
    private final Quat4f scratchQuat = new Quat4f();
    private final CollectedContact cursor = new CollectedContact();
    private final Vector3f aabbMinA = new Vector3f();
    private final Vector3f aabbMaxA = new Vector3f();
    private final Vector3f aabbMinB = new Vector3f();
    private final Vector3f aabbMaxB = new Vector3f();

    // Contacts are collapsed to the deepest per pair each step, matching Bullet's one manifold per pair.
    // No-collide groups, one per ragdoll, sized for the largest skeleton (MAX_PARTS 10) with headroom.
    private static final int MAX_BODIES_PER_GROUP = 16;
    private final Int2ObjectOpenHashMap<NoCollideGroup> noCollideGroups = new Int2ObjectOpenHashMap<>();
    private int nextCollisionGroupId = 1;

    private final java.util.ArrayList<JoltBody> pendingDestroy = new java.util.ArrayList<>();
    private final Long2IntOpenHashMap pairSlots = new Long2IntOpenHashMap();
    private int[] collapsed = new int[256];
    private int collapsedCount;

    // Set on the main thread at unload and checked by every entry point,
    // since touching a destroyed Jolt system is an uncatchable native crash.
    private volatile boolean destroyed;
    private int dynamicBodiesInWorld;
    private int staticAddsSinceOptimize;
    private StepQuality lastQuality;
    // Jolt's own default until the owner sets one. See PhysicsWorld#setStaticFriction.
    private float staticFriction = 0.2f;

    public JoltWorld(int workerThreads) {
        layerMap = new BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_STATIC, BROADPHASE_LAYER_STATIC);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, BROADPHASE_LAYER_MOVING);
        layerMap.mapObjectToBroadPhaseLayer(OBJECT_LAYER_PHANTOM, BROADPHASE_LAYER_MOVING);

        objectPairFilter = new ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS);
        // The table starts with every pair disabled, so static-vs-static is off by omission.
        objectPairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING);
        objectPairFilter.enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_STATIC);

        objectVsBroadPhaseFilter = new ObjectVsBroadPhaseLayerFilterTable(
                layerMap, NUM_BROADPHASE_LAYERS, objectPairFilter, NUM_OBJECT_LAYERS);

        system = new PhysicsSystem();
        system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACT_CONSTRAINTS,
                layerMap, objectVsBroadPhaseFilter, objectPairFilter);
        // No-lock interface: every call into this world happens on the one physics worker, never
        // during a step. That invariant is documented on PhysicsWorld and is load-bearing here.
        bodyInterface = system.getBodyInterfaceNoLock();
        system.setContactListener(contacts);

        settings = system.getPhysicsSettings();
        // The ragdoll owns settling and removes settled bodies from the world outright, so engine
        // sleeping stays off, matching the Bullet backend rather than quietly changing behaviour.
        settings.setAllowSleeping(false);
        // Halve Jolt's 2 cm default penetration slop so limbs don't visibly sink; zero would jitter.
        settings.setPenetrationSlop(0.01f);
        system.setPhysicsSettings(settings);

        tempAllocator = new TempAllocatorImpl(TEMP_ALLOCATOR_BYTES);
        int threads = Math.max(1, workerThreads);
        jobSystem = new JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, threads);
        pairSlots.defaultReturnValue(-1);
        Ragdollified.LOGGER.info("Jolt physics world created with {} solver thread(s)", threads);
    }

    BodyInterface bodyInterface() {
        return bodyInterface;
    }

    @Override
    public String engineName() {
        return "jolt";
    }

    boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public PhysicsShape createBoxShape(float halfX, float halfY, float halfZ) {
        return new JoltShape(halfX, halfY, halfZ);
    }

    @Override
    public PhysicsBody createDynamicBody(PhysicsShape shape, Vector3f position, Quat4f rotation,
                                         BodyProperties properties) {
        if (destroyed) return null;
        JoltShape joltShape = (JoltShape) shape;
        scratchLocation.set(position.x, position.y, position.z);
        scratchOrientation.set(rotation.x, rotation.y, rotation.z, rotation.w);

        BodyCreationSettings creation = new BodyCreationSettings(
                joltShape.shape, scratchLocation, scratchOrientation,
                EMotionType.Dynamic, OBJECT_LAYER_MOVING);
        MassProperties mass = new MassProperties();
        mass.setMass(properties.mass);
        // CalculateInertia: take the inertia tensor from the shape and scale it to this mass, which
        // is what Bullet's calculateLocalInertia did for the same box.
        creation.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
        creation.setMassPropertiesOverride(mass);
        creation.setLinearDamping(properties.linearDamping);
        creation.setAngularDamping(properties.angularDamping);
        creation.setFriction(properties.friction);
        creation.setRestitution(properties.restitution);
        creation.setAllowSleeping(properties.allowSleeping);
        creation.setLinearVelocity(new Vec3(properties.linearVelocity.x,
                properties.linearVelocity.y, properties.linearVelocity.z));

        Body body = bodyInterface.createBody(creation);
        if (body == null) {
            Ragdollified.LOGGER.error(
                    "Jolt refused a body: the {}-body budget is exhausted", MAX_BODIES);
            return null;
        }
        JoltBody wrapper = new JoltBody(this, body, joltShape, false, properties.mass);
        wrapper.setCcdSweptSphereRadius(properties.ccdSweptSphereRadius);
        wrapper.setCcdMotionThreshold(properties.ccdMotionThreshold);
        bodiesByAddress.put(body.va(), wrapper);
        return wrapper;
    }

    @Override
    public PhysicsBody createStaticBody(PhysicsShape shape, PhysTransform transform) {
        if (destroyed) return null;
        JoltShape joltShape = (JoltShape) shape;
        transform.getRotation(scratchQuat);
        scratchLocation.set(transform.origin.x, transform.origin.y, transform.origin.z);
        scratchOrientation.set(scratchQuat.x, scratchQuat.y, scratchQuat.z, scratchQuat.w);

        BodyCreationSettings creation = new BodyCreationSettings(
                joltShape.shape, scratchLocation, scratchOrientation,
                EMotionType.Static, OBJECT_LAYER_STATIC);
        creation.setFriction(staticFriction);
        Body body = bodyInterface.createBody(creation);
        if (body == null) {
            Ragdollified.LOGGER.error(
                    "Jolt refused a static body: the {}-body budget is exhausted", MAX_BODIES);
            return null;
        }
        JoltBody wrapper = new JoltBody(this, body, joltShape, true, 0f);
        bodiesByAddress.put(body.va(), wrapper);
        return wrapper;
    }

    @Override
    public PhysicsConstraint createSixDofConstraint(
            PhysicsBody bodyA, PhysicsBody bodyB,
            PhysTransform frameInA, PhysTransform frameInB,
            Vector3f linearLower, Vector3f linearUpper,
            Vector3f angularLower, Vector3f angularUpper) {
        JoltBody a = (JoltBody) bodyA;
        JoltBody b = (JoltBody) bodyB;

        SixDofConstraintSettings creation = new SixDofConstraintSettings();
        // Local to each body's centre of mass, matching the frames Bullet's 6-DoF joint takes. For
        // the symmetric boxes this mod builds, centre of mass and body origin coincide.
        creation.setSpace(EConstraintSpace.LocalToBodyCom);
        creation.setPosition1(new RVec3(frameInA.origin.x, frameInA.origin.y, frameInA.origin.z));
        creation.setPosition2(new RVec3(frameInB.origin.x, frameInB.origin.y, frameInB.origin.z));
        creation.setAxisX1(new Vec3(frameInA.basis.m00, frameInA.basis.m10, frameInA.basis.m20));
        creation.setAxisY1(new Vec3(frameInA.basis.m01, frameInA.basis.m11, frameInA.basis.m21));
        creation.setAxisX2(new Vec3(frameInB.basis.m00, frameInB.basis.m10, frameInB.basis.m20));
        creation.setAxisY2(new Vec3(frameInB.basis.m01, frameInB.basis.m11, frameInB.basis.m21));
        // Pyramid, not the default cone: the authored limits are asymmetric (a knee bends one way),
        // and a cone swing can only express symmetric ones, so the default would quietly widen them.
        creation.setSwingType(ESwingType.Pyramid);

        applyLimit(creation, EAxis.TranslationX, linearLower.x, linearUpper.x);
        applyLimit(creation, EAxis.TranslationY, linearLower.y, linearUpper.y);
        applyLimit(creation, EAxis.TranslationZ, linearLower.z, linearUpper.z);
        applyLimit(creation, EAxis.RotationX, angularLower.x, angularUpper.x);
        applyLimit(creation, EAxis.RotationY, angularLower.y, angularUpper.y);
        applyLimit(creation, EAxis.RotationZ, angularLower.z, angularUpper.z);

        TwoBodyConstraint constraint = creation.create(a.body, b.body);
        return new JoltConstraint(constraint, a, b);
    }

    // Bullet's convention, carried across unchanged: lower > upper means the axis is free, lower ==
    // upper means it is locked, anything else is a limited range.
    private static void applyLimit(SixDofConstraintSettings settings, EAxis axis,
                                   float lower, float upper) {
        if (lower > upper) {
            settings.makeFreeAxis(axis);
        } else if (lower == upper) {
            settings.makeFixedAxis(axis);
        } else {
            settings.setLimitedAxis(axis, lower, upper);
        }
    }

    @Override
    public void addBody(PhysicsBody body) {
        if (destroyed) return;
        JoltBody joltBody = (JoltBody) body;
        if (joltBody.inWorld) return;
        bodyInterface.addBody(joltBody.bodyId,
                joltBody.isStatic() ? EActivation.DontActivate : EActivation.Activate);
        joltBody.inWorld = true;
        joltBody.applyMotionTypeOnAdd();
        if (joltBody.isStatic()) {
            staticAddsSinceOptimize++;
        } else {
            dynamicBodiesInWorld++;
            // Motion quality can only be set through the body interface once the body is in the
            // world, so a body that had CCD configured before being added gets it applied here.
            joltBody.applyMotionQuality();
        }
    }

    // A parked body lands unsorted in the static tree, so it counts towards the rebuild budget.
    void onParkedChanged(boolean parked) {
        if (parked) {
            dynamicBodiesInWorld--;
            staticAddsSinceOptimize++;
        } else {
            dynamicBodiesInWorld++;
        }
    }

    @Override
    public void removeBody(PhysicsBody body) {
        if (destroyed) return;
        JoltBody joltBody = (JoltBody) body;
        if (!joltBody.inWorld) return;
        bodyInterface.removeBody(joltBody.bodyId);
        joltBody.inWorld = false;
        if (!joltBody.isStatic()) dynamicBodiesInWorld--;
    }

    @Override
    public void destroyBody(PhysicsBody body) {
        if (destroyed) return;
        JoltBody joltBody = (JoltBody) body;
        removeBody(joltBody);
        leaveNoCollideGroup(joltBody);
        // Removed now, freed next step, so a new body can't reuse a pointer this tick's contacts still hold.
        pendingDestroy.add(joltBody);
    }

    private void flushPendingDestroys() {
        for (int i = 0; i < pendingDestroy.size(); i++) {
            JoltBody body = pendingDestroy.get(i);
            bodiesByAddress.remove(body.body.va());
            // Native memory: a body that is removed but never destroyed leaks for the session and
            // eventually exhausts the body budget. Every teardown path has to reach this call.
            bodyInterface.destroyBody(body.bodyId);
        }
        pendingDestroy.clear();
    }

    @Override
    public void addConstraint(PhysicsConstraint constraint) {
        if (destroyed) return;
        JoltConstraint joltConstraint = (JoltConstraint) constraint;
        if (joltConstraint.inWorld) return;
        if (!joltConstraint.keepsCollision()) {
            stopCollidingWithEachOther(
                    (JoltBody) joltConstraint.bodyA(), (JoltBody) joltConstraint.bodyB());
        }
        system.addConstraint(joltConstraint.constraint);
        joltConstraint.inWorld = true;
    }

    // Jolt lacks a no-collide flag for constrained bodies, so each assembly gets a group id and each
    // body a sub-group id; the group's table disables only the jointed pairs.
    private void stopCollidingWithEachOther(JoltBody a, JoltBody b) {
        if (a == null || b == null || a == b) return;

        if (a.collisionGroupId >= 0 && b.collisionGroupId >= 0
                && a.collisionGroupId != b.collisionGroupId) {
            // Joining two existing assemblies can't merge Jolt tables; never happens here, so just log it.
            Ragdollified.LOGGER.warn("Cannot disable collisions between bodies from two separate"
                    + " constraint groups ({} and {}); the joint will fight its own contacts.",
                    a.collisionGroupId, b.collisionGroupId);
            return;
        }

        int groupId = a.collisionGroupId >= 0 ? a.collisionGroupId : b.collisionGroupId;
        NoCollideGroup group;
        if (groupId < 0) {
            groupId = nextCollisionGroupId++;
            group = new NoCollideGroup();
            noCollideGroups.put(groupId, group);
        } else {
            group = noCollideGroups.get(groupId);
            if (group == null) return;
        }

        if (!join(a, groupId, group) || !join(b, groupId, group)) return;
        group.filter.disableCollision(a.collisionSubGroupId, b.collisionSubGroupId);
    }

    private boolean join(JoltBody body, int groupId, NoCollideGroup group) {
        if (body.collisionGroupId == groupId) return true;
        if (group.nextSubGroup >= MAX_BODIES_PER_GROUP) {
            Ragdollified.LOGGER.warn("Constraint group {} is full at {} bodies; further joints in"
                    + " this assembly will collide with each other.", groupId, MAX_BODIES_PER_GROUP);
            return false;
        }
        body.collisionGroupId = groupId;
        body.collisionSubGroupId = group.nextSubGroup++;
        group.members++;
        body.body.setCollisionGroup(
                new CollisionGroup(group.filter, groupId, body.collisionSubGroupId));
        return true;
    }

    private void leaveNoCollideGroup(JoltBody body) {
        if (body.collisionGroupId < 0) return;
        NoCollideGroup group = noCollideGroups.get(body.collisionGroupId);
        if (group != null && --group.members <= 0) {
            noCollideGroups.remove(body.collisionGroupId);
        }
        body.collisionGroupId = -1;
        body.collisionSubGroupId = -1;
    }

    // Keeps the filter table's Java wrapper alive as long as any of its bodies live.
    private static final class NoCollideGroup {
        final GroupFilterTable filter = new GroupFilterTable(MAX_BODIES_PER_GROUP);
        int nextSubGroup;
        int members;
    }

    @Override
    public void removeConstraint(PhysicsConstraint constraint) {
        if (destroyed) return;
        JoltConstraint joltConstraint = (JoltConstraint) constraint;
        if (!joltConstraint.inWorld) return;
        system.removeConstraint(joltConstraint.constraint);
        joltConstraint.inWorld = false;
    }

    @Override
    public void destroyConstraint(PhysicsConstraint constraint) {
        removeConstraint(constraint);
        // The counted reference JoltConstraint holds is released when it becomes unreachable.
    }

    @Override
    public void setGravity(float x, float y, float z) {
        system.setGravity(x, y, z);
    }

    @Override
    public void setStaticFriction(float friction) {
        // Only reaches bodies built after this call, which is every one of them: the owner sets this
        // alongside gravity when the world is created, before any terrain has been cached.
        staticFriction = friction;
    }

    @Override
    // Synchronized against destroy(): world unload runs on the main thread and only waits a
    // couple of seconds for the worker, so the two can genuinely overlap.
    public synchronized void step(float dt, StepQuality quality) {
        if (destroyed) return;
        if (staticAddsSinceOptimize >= OPTIMIZE_BROADPHASE_AFTER_STATIC_ADDS) {
            system.optimizeBroadPhase();
            staticAddsSinceOptimize = 0;
        }
        // Jolt-native step settings (~1 collision step per 1/60 s, 10 velocity / 2 position), not Bullet's.
        int collisionSteps;
        int velocitySteps;
        int positionSteps;
        switch (quality) {
            case HIGH -> { collisionSteps = 3; velocitySteps = 10; positionSteps = 2; }
            case BALANCED -> { collisionSteps = 2; velocitySteps = 10; positionSteps = 2; }
            case ECONOMY -> { collisionSteps = 2; velocitySteps = 8; positionSteps = 2; }
            default -> { collisionSteps = 2; velocitySteps = 6; positionSteps = 1; }
        }
        if (quality != lastQuality) {
            settings.setNumVelocitySteps(velocitySteps);
            settings.setNumPositionSteps(positionSteps);
            system.setPhysicsSettings(settings);
            lastQuality = quality;
        }

        flushPendingDestroys();
        contacts.reset();
        system.update(dt, collisionSteps, tempAllocator, jobSystem);
        collapseContacts();
    }

    private void collapseContacts() {
        pairSlots.clear();
        collapsedCount = 0;
        int reported = contacts.count();
        for (int i = 0; i < reported; i++) {
            JoltBody a = bodiesByAddress.get(contacts.bodyVa1(i));
            JoltBody b = bodiesByAddress.get(contacts.bodyVa2(i));
            if (a == null || b == null) continue;
            int low = Math.min(a.bodyId, b.bodyId);
            int high = Math.max(a.bodyId, b.bodyId);
            long key = ((long) low << 32) | (high & 0xFFFFFFFFL);
            int slot = pairSlots.get(key);
            if (slot < 0) {
                if (collapsedCount == collapsed.length) {
                    collapsed = java.util.Arrays.copyOf(collapsed, collapsedCount * 2);
                }
                collapsed[collapsedCount] = i;
                pairSlots.put(key, collapsedCount);
                collapsedCount++;
            } else if (contacts.distance(i) < contacts.distance(collapsed[slot])) {
                collapsed[slot] = i;
            }
        }
    }

    @Override
    public synchronized void skipStep() {
        if (destroyed) return;
        // Clear our own contact buffer, or a skipped tick would reapply last step's corrections.
        contacts.reset();
        pairSlots.clear();
        collapsedCount = 0;
        flushPendingDestroys();
    }

    @Override
    public void forEachContactPair(ContactVisitor visitor) {
        if (destroyed) return;
        for (int slot = 0; slot < collapsedCount; slot++) {
            int i = collapsed[slot];
            JoltBody a = bodiesByAddress.get(contacts.bodyVa1(i));
            JoltBody b = bodiesByAddress.get(contacts.bodyVa2(i));
            if (a == null || b == null) continue;
            cursor.bind(i, a, b);
            visitor.visit(cursor);
        }
    }

    @Override
    public int manifoldCount() {
        return collapsedCount;
    }

    @Override
    public int contactPointCount() {
        // One entry per pair after collapsing: Jolt reports a manifold's depth and normal, not its
        // individual points, so the two counts are the same figure on this backend.
        return collapsedCount;
    }

    @Override
    public int activeBodyCount() {
        return dynamicBodiesInWorld;
    }

    @Override
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        pendingDestroy.clear();
        noCollideGroups.clear();
        system.removeAllConstraints();
        system.removeAllBodies();
        system.destroyAllBodies();
        bodiesByAddress.clear();
        contacts.reset();
        pairSlots.clear();
        collapsedCount = 0;
        dynamicBodiesInWorld = 0;
    }

    // A cursor over one collected contact. Jolt reports a manifold as a single depth and normal
    // rather than a point list, so every pair here carries exactly one contact.
    private final class CollectedContact implements ContactPair {

        private int index;
        private JoltBody a;
        private JoltBody b;

        void bind(int index, JoltBody a, JoltBody b) {
            this.index = index;
            this.a = a;
            this.b = b;
        }

        @Override
        public PhysicsBody bodyA() {
            return a;
        }

        @Override
        public PhysicsBody bodyB() {
            return b;
        }

        @Override
        public int contactCount() {
            return 1;
        }

        @Override
        public void selectContact(int contactIndex) {
            // Single contact per pair; nothing to select.
        }

        @Override
        public float distance() {
            return contacts.distance(index);
        }

        @Override
        public void getNormalOnB(Vector3f out) {
            out.set(contacts.normalX(index), contacts.normalY(index), contacts.normalZ(index));
        }

        // Contact location is the centre of the overlapping AABBs, since the binding exposes no points.
        // Computed on demand, for the collision API only.
        @Override
        public void getPositionOnB(Vector3f out) {
            a.getWorldAabb(aabbMinA, aabbMaxA);
            b.getWorldAabb(aabbMinB, aabbMaxB);
            out.set(overlapCentre(aabbMinA.x, aabbMaxA.x, aabbMinB.x, aabbMaxB.x),
                    overlapCentre(aabbMinA.y, aabbMaxA.y, aabbMinB.y, aabbMaxB.y),
                    overlapCentre(aabbMinA.z, aabbMaxA.z, aabbMinB.z, aabbMaxB.z));
        }

        private float overlapCentre(float minA, float maxA, float minB, float maxB) {
            float low = Math.max(minA, minB);
            float high = Math.min(maxA, maxB);
            // Speculative contacts are reported before the boxes actually overlap, in which case
            // the midpoint of the gap is the best available answer.
            return low <= high ? (low + high) * 0.5f : (Math.min(maxA, maxB) + Math.max(minA, minB)) * 0.5f;
        }

        @Override
        public float appliedImpulse() {
            // Not exposed by Jolt's contact listener. Callers that need a magnitude fall back to
            // their own pre-step velocity sample, which is what the collision API already does.
            return 0f;
        }

        @Override
        public boolean isNewContact() {
            return contacts.isNew(index);
        }
    }
}
