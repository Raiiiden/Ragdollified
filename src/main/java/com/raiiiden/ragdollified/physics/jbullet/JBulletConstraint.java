package com.raiiiden.ragdollified.physics.jbullet;

import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;

final class JBulletConstraint implements PhysicsConstraint {

    final TypedConstraint constraint;
    private final JBulletBody bodyA;
    private final JBulletBody bodyB;
    private boolean keepsCollision;
    boolean inWorld;

    JBulletConstraint(TypedConstraint constraint, JBulletBody bodyA, JBulletBody bodyB) {
        this.constraint = constraint;
        this.bodyA = bodyA;
        this.bodyB = bodyB;
    }

    @Override
    public PhysicsBody bodyA() { return bodyA; }

    @Override
    public PhysicsBody bodyB() { return bodyB; }

    @Override
    public void driveToOrientation(float qx, float qy, float qz, float qw,
                                   float frequency, float damping, float maxTorque) {
        // Not implemented on JBullet (covers relaxToRest too): its motors are velocity-driven,
        // so this fallback keeps limits-only behaviour.
    }

    @Override
    public boolean keepsCollision() { return keepsCollision; }

    @Override
    public void setKeepsCollision(boolean keepsCollision) { this.keepsCollision = keepsCollision; }
}
