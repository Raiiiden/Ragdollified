package com.raiiiden.ragdollified.physics;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

// One rigid body with a Bullet-shaped API; the semantics are defined here, not by the engine.
// Single-threaded: physics worker only, never during a step.
public interface PhysicsBody {

    // Transform

    void getWorldTransform(PhysTransform out);

    // Teleports the body. Also refreshes whatever the backend uses for interpolated reads, so a
    // subsequent getWorldTransform reflects the write immediately.
    void setWorldTransform(PhysTransform transform);

    void getPosition(Vector3f out);

    void getRotation(Quat4f out);

    // Centre of mass in world space. Equal to the origin for the symmetric boxes this mod builds,
    // but the collision API scores impacts off it, so it stays distinct.
    void getCenterOfMassPosition(Vector3f out);

    // Moves the body by delta without touching its velocity.
    void translate(Vector3f delta);

    // Velocity

    void getLinearVelocity(Vector3f out);

    void setLinearVelocity(Vector3f velocity);

    void getAngularVelocity(Vector3f out);

    void setAngularVelocity(Vector3f velocity);

    void applyCentralImpulse(Vector3f impulse);

    // Impulse applied at relativePosition, measured from the centre of mass; this is what produces
    // torque, and the death-hit code depends on it.
    void applyImpulse(Vector3f impulse, Vector3f relativePosition);

    // Material and damping

    float getFriction();

    void setFriction(float friction);

    void setRestitution(float restitution);

    void setDamping(float linear, float angular);

    // Continuous collision: engines with on/off motion quality treat a positive threshold as on
    // and remember the radius so drag code can save and restore both.
    float getCcdMotionThreshold();

    void setCcdMotionThreshold(float threshold);

    float getCcdSweptSphereRadius();

    void setCcdSweptSphereRadius(float radius);

    // Parking

    // Turn a dynamic body into an immovable in-place obstacle and back; still collidable, no solver cost.
    // Used for settled ragdolls to avoid pass-through and wake-up shoves. No-op on static bodies.
    void setStatic(boolean parked);

    // Activation

    void activate();

    // False pins the body awake; the ragdoll settles itself, and slept bodies stop integrating gravity.
    void setSleepingAllowed(boolean allowed);

    void setSleepingThresholds(float linear, float angular);

    // Mass and inertia

    // Rescales the body's mass, rebuilding the inertia tensor from its shape. Used by the per-part
    // weight config, which multiplies the authored mass after the skeleton is built.
    void setMass(float mass);

    // False makes the body pass through everything while still simulating: some skeletons carry
    // near-zero proxy parts that exist only to give the renderer a transform to hang a limb on.
    void setContactResponse(boolean respond);

    // Queries

    // Zero for static bodies. Used to tell terrain from ragdoll parts at contact time.
    float getInvMass();

    boolean isStatic();

    PhysicsShape getShape();

    // World-space AABB of the body's shape at its current transform.
    void getWorldAabb(Vector3f min, Vector3f max);

    // Caller-owned tag. Terrain bodies carry their BlockPos here. This is a plain Java reference on
    // every backend: native user data is an implementation detail the backends keep to themselves.
    Object getUserPointer();

    void setUserPointer(Object userPointer);

    // True while the body is present in the world's simulation set.
    boolean isInWorld();
}
