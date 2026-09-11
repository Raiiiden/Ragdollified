package com.raiiiden.ragdollified.physics;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

// One simulation; every engine call goes through here so no caller names a Bullet or Jolt type.
// All methods: physics worker thread only, never during step() (undefined behaviour in Jolt).
public interface PhysicsWorld {

    // For logs and the perf readout, so an A/B run says which engine produced the numbers.
    String engineName();

    // Construction

    PhysicsShape createBoxShape(float halfX, float halfY, float halfZ);

    PhysicsBody createDynamicBody(PhysicsShape shape, Vector3f position, Quat4f rotation,
                                  BodyProperties properties);

    PhysicsBody createStaticBody(PhysicsShape shape, PhysTransform transform);

    // A 6-DoF joint. frameInA / frameInB are the joint frames in each body's local space; the
    // angular limits are radians. Bodies must already be in the world.
    PhysicsConstraint createSixDofConstraint(
            PhysicsBody bodyA, PhysicsBody bodyB,
            PhysTransform frameInA, PhysTransform frameInB,
            Vector3f linearLower, Vector3f linearUpper,
            Vector3f angularLower, Vector3f angularUpper);

    // Membership. Removing is reversible and keeps the body alive; that is how the ragdoll freezes
    // settled bodies out of the broadphase. Destroying releases the backend's resources and is not.
    void addBody(PhysicsBody body);

    void removeBody(PhysicsBody body);

    void destroyBody(PhysicsBody body);

    // Adding a constraint disables collision between its bodies unless keepsCollision is set,
    // since ragdoll parts overlap by design.
    void addConstraint(PhysicsConstraint constraint);

    void removeConstraint(PhysicsConstraint constraint);

    void destroyConstraint(PhysicsConstraint constraint);

    // Simulation

    void setGravity(float x, float y, float z);

    // Friction for every static block body. Set explicitly because engines combine frictions
    // differently (Jolt sqrt(a*b), Bullet a*b); one number keeps terrain and ragdolls consistent.
    void setStaticFriction(float friction);

    // Advances by dt at the requested effort. The manager picks the quality from the active body
    // count; each backend decides what that means in its own units. See StepQuality.
    void step(float dt, StepQuality quality);

    // Called instead of step() on a skipped tick, to clear last step's contacts and finish deferred teardown.
    void skipStep();

    // Contacts from the most recent step. Visits each body pair once.
    void forEachContactPair(ContactVisitor visitor);

    // Counts from the most recent step, for the perf log.
    int manifoldCount();

    int contactPointCount();

    int activeBodyCount();

    void destroy();
}
