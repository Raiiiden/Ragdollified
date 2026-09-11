package com.raiiiden.ragdollified.physics;

import javax.vecmath.Vector3f;

// Creation-time settings for a dynamic body. Mutable and meant to be reused across a build, since a
// ragdoll creates six bodies back to back from the same configuration values.
public final class BodyProperties {

    public float mass = 1f;
    public float linearDamping;
    public float angularDamping;
    public float friction = 0.5f;
    public float restitution;
    // False pins the body awake; the ragdoll owns settling. See PhysicsBody#setSleepingAllowed.
    public boolean allowSleeping;
    public float sleepingLinearThreshold = 0.3f;
    public float sleepingAngularThreshold = 0.3f;
    // Zero disables continuous collision.
    public float ccdMotionThreshold;
    public float ccdSweptSphereRadius;
    public final Vector3f linearVelocity = new Vector3f();

    public BodyProperties reset() {
        mass = 1f;
        linearDamping = 0f;
        angularDamping = 0f;
        friction = 0.5f;
        restitution = 0f;
        allowSleeping = false;
        sleepingLinearThreshold = 0.3f;
        sleepingAngularThreshold = 0.3f;
        ccdMotionThreshold = 0f;
        ccdSweptSphereRadius = 0f;
        linearVelocity.set(0f, 0f, 0f);
        return this;
    }
}
