package com.raiiiden.ragdollified.physics.jbullet;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import com.raiiiden.ragdollified.physics.BodyProperties;
import com.raiiiden.ragdollified.physics.ContactPair;
import com.raiiiden.ragdollified.physics.ContactVisitor;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import com.raiiiden.ragdollified.physics.StepQuality;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.List;

// The original engine, kept behind the seam. This backend is the behavioural reference: anything the
// Jolt backend does differently is a deliberate, documented difference rather than drift.
public final class JBulletWorld implements PhysicsWorld {

    private final CollisionConfiguration collisionConfig;
    private final CollisionDispatcher dispatcher;
    private final BroadphaseInterface broadphase;
    private final ConstraintSolver solver;
    private final DiscreteDynamicsWorld world;

    private final Transform scratchTransform = new Transform();
    private final Vector3f scratchInertia = new Vector3f();
    private final ManifoldCursor cursor = new ManifoldCursor();

    // Bullet's own default until the owner sets one. See PhysicsWorld#setStaticFriction.
    private float staticFriction = 0.5f;

    // Caps the work when a tick runs long; Bullet would otherwise try to catch up without bound.
    private static final int MAX_SUB_STEPS = 10;

    private int dynamicBodiesInWorld;

    public JBulletWorld() {
        collisionConfig = new DefaultCollisionConfiguration();
        dispatcher = new CollisionDispatcher(collisionConfig);
        // DbvtBroadphase: dynamic AABB tree, O(log N) updates, no handle cap. AxisSweep3 hit its
        // limit and slowed as static block bodies accumulated across cache regions.
        broadphase = new DbvtBroadphase();
        solver = new SequentialImpulseConstraintSolver();
        world = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
    }

    @Override
    public String engineName() {
        return "jbullet";
    }

    @Override
    public PhysicsShape createBoxShape(float halfX, float halfY, float halfZ) {
        return new JBulletShape(new BoxShape(new Vector3f(halfX, halfY, halfZ)));
    }

    @Override
    public PhysicsBody createDynamicBody(PhysicsShape shape, Vector3f position, Quat4f rotation,
                                         BodyProperties properties) {
        JBulletShape jShape = (JBulletShape) shape;
        scratchTransform.setIdentity();
        scratchTransform.origin.set(position);
        scratchTransform.setRotation(rotation);

        scratchInertia.set(0f, 0f, 0f);
        jShape.shape.calculateLocalInertia(properties.mass, scratchInertia);

        RigidBodyConstructionInfo info = new RigidBodyConstructionInfo(
                properties.mass, new DefaultMotionState(scratchTransform), jShape.shape, scratchInertia);
        info.linearDamping = properties.linearDamping;
        info.angularDamping = properties.angularDamping;
        info.restitution = properties.restitution;
        info.friction = properties.friction;
        info.additionalDamping = true;

        RigidBody body = new RigidBody(info);
        body.setLinearVelocity(properties.linearVelocity);
        body.setDamping(properties.linearDamping, properties.angularDamping);
        body.setSleepingThresholds(
                properties.sleepingLinearThreshold, properties.sleepingAngularThreshold);
        if (properties.ccdMotionThreshold > 0f) {
            body.setCcdSweptSphereRadius(properties.ccdSweptSphereRadius);
            body.setCcdMotionThreshold(properties.ccdMotionThreshold);
        }

        JBulletBody wrapper = new JBulletBody(body, jShape, false);
        wrapper.setSleepingAllowed(properties.allowSleeping);
        return wrapper;
    }

    @Override
    public PhysicsBody createStaticBody(PhysicsShape shape, PhysTransform transform) {
        JBulletShape jShape = (JBulletShape) shape;
        scratchTransform.basis.set(transform.basis);
        scratchTransform.origin.set(transform.origin);
        RigidBody body = new RigidBody(new RigidBodyConstructionInfo(
                0f, new DefaultMotionState(scratchTransform), jShape.shape, new Vector3f()));
        body.setCollisionFlags(body.getCollisionFlags() | CollisionFlags.STATIC_OBJECT);
        body.setFriction(staticFriction);
        return new JBulletBody(body, jShape, true);
    }

    @Override
    public PhysicsConstraint createSixDofConstraint(
            PhysicsBody bodyA, PhysicsBody bodyB,
            PhysTransform frameInA, PhysTransform frameInB,
            Vector3f linearLower, Vector3f linearUpper,
            Vector3f angularLower, Vector3f angularUpper) {
        JBulletBody a = (JBulletBody) bodyA;
        JBulletBody b = (JBulletBody) bodyB;
        Transform localA = new Transform();
        localA.basis.set(frameInA.basis);
        localA.origin.set(frameInA.origin);
        Transform localB = new Transform();
        localB.basis.set(frameInB.basis);
        localB.origin.set(frameInB.origin);

        Generic6DofConstraint constraint =
                new Generic6DofConstraint(a.body, b.body, localA, localB, true);
        constraint.setLinearLowerLimit(linearLower);
        constraint.setLinearUpperLimit(linearUpper);
        constraint.setAngularLowerLimit(angularLower);
        constraint.setAngularUpperLimit(angularUpper);
        return new JBulletConstraint(constraint, a, b);
    }

    @Override
    public void addBody(PhysicsBody body) {
        JBulletBody jBody = (JBulletBody) body;
        if (jBody.body.isInWorld()) return;
        world.addRigidBody(jBody.body);
        if (!jBody.isStatic()) dynamicBodiesInWorld++;
    }

    @Override
    public void removeBody(PhysicsBody body) {
        JBulletBody jBody = (JBulletBody) body;
        if (!jBody.body.isInWorld()) return;
        world.removeRigidBody(jBody.body);
        if (!jBody.isStatic()) dynamicBodiesInWorld--;
    }

    @Override
    public void destroyBody(PhysicsBody body) {
        removeBody(body);
        // JBullet bodies are plain JVM objects, so the GC is the only teardown they need. The method
        // exists because native backends do need it, and callers must not have to know which is live.
    }

    @Override
    public void addConstraint(PhysicsConstraint constraint) {
        JBulletConstraint jConstraint = (JBulletConstraint) constraint;
        if (jConstraint.inWorld) return;
        // true = disable collisions between the linked bodies, which is the seam's contract.
        world.addConstraint(jConstraint.constraint, !jConstraint.keepsCollision());
        jConstraint.inWorld = true;
    }

    @Override
    public void removeConstraint(PhysicsConstraint constraint) {
        JBulletConstraint jConstraint = (JBulletConstraint) constraint;
        if (!jConstraint.inWorld) return;
        world.removeConstraint(jConstraint.constraint);
        jConstraint.inWorld = false;
    }

    @Override
    public void destroyConstraint(PhysicsConstraint constraint) {
        removeConstraint(constraint);
    }

    @Override
    public void setStaticFriction(float friction) {
        staticFriction = friction;
    }

    @Override
    public void setGravity(float x, float y, float z) {
        world.setGravity(new Vector3f(x, y, z));
    }

    // The tiers the mod shipped with, unchanged: this backend stays the behavioural reference, so
    // its numbers are the ones the quality levels were originally named after.
    @Override
    public void step(float dt, StepQuality quality) {
        int iterations;
        float fixedSubStep;
        switch (quality) {
            case HIGH -> { iterations = 20; fixedSubStep = 1f / 120f; }
            case BALANCED -> { iterations = 10; fixedSubStep = 1f / 120f; }
            case ECONOMY -> { iterations = 10; fixedSubStep = 1f / 60f; }
            default -> { iterations = 6; fixedSubStep = 1f / 40f; }
        }
        world.getSolverInfo().numIterations = iterations;
        world.stepSimulation(dt, MAX_SUB_STEPS, fixedSubStep);
    }

    @Override
    public void skipStep() {
        // Nothing to do: Bullet's manifolds live with the bodies, and a body removed from the world
        // takes its manifolds with it, so a tick without a step already sees no stale contacts.
    }

    @Override
    public void forEachContactPair(ContactVisitor visitor) {
        List<PersistentManifold> manifolds = dispatcher.getInternalManifoldPointer();
        for (int i = 0, n = manifolds.size(); i < n; i++) {
            PersistentManifold manifold = manifolds.get(i);
            if (manifold.getNumContacts() == 0) continue;
            JBulletBody a = JBulletBody.of((CollisionObject) manifold.getBody0());
            JBulletBody b = JBulletBody.of((CollisionObject) manifold.getBody1());
            if (a == null || b == null) continue;
            cursor.bind(manifold, a, b);
            visitor.visit(cursor);
        }
    }

    @Override
    public int manifoldCount() {
        return dispatcher.getNumManifolds();
    }

    @Override
    public int contactPointCount() {
        int total = 0;
        int count = dispatcher.getNumManifolds();
        for (int i = 0; i < count; i++) {
            total += dispatcher.getManifoldByIndexInternal(i).getNumContacts();
        }
        return total;
    }

    @Override
    public int activeBodyCount() {
        return dynamicBodiesInWorld;
    }

    @Override
    public void destroy() {
        // Nothing native to release. Dropping the world drops every body with it.
    }

    private static final class ManifoldCursor implements ContactPair {

        private PersistentManifold manifold;
        private JBulletBody a;
        private JBulletBody b;
        private ManifoldPoint point;

        void bind(PersistentManifold manifold, JBulletBody a, JBulletBody b) {
            this.manifold = manifold;
            this.a = a;
            this.b = b;
            this.point = manifold.getContactPoint(0);
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
            return manifold.getNumContacts();
        }

        @Override
        public void selectContact(int index) {
            point = manifold.getContactPoint(index);
        }

        @Override
        public float distance() {
            return point.getDistance();
        }

        @Override
        public void getNormalOnB(Vector3f out) {
            out.set(point.normalWorldOnB);
        }

        @Override
        public void getPositionOnB(Vector3f out) {
            out.set(point.positionWorldOnB);
        }

        @Override
        public float appliedImpulse() {
            return point.appliedImpulse;
        }

        @Override
        public boolean isNewContact() {
            return point.getLifeTime() <= 1;
        }
    }
}
