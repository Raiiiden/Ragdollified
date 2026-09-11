package com.raiiiden.ragdollified.physics;

// A joint between two bodies. The ragdoll only ever creates 6-DoF joints with fixed limits and never
// reads them back, so this is a lifetime handle rather than a control surface.
public interface PhysicsConstraint {

    PhysicsBody bodyA();

    PhysicsBody bodyB();

    // Opt this joint out of the default no-collision between its bodies, only where they don't overlap
    // (like arms against the torso). Read when the joint is added.
    boolean keepsCollision();

    void setKeepsCollision(boolean keepsCollision);

    // Drive this joint gently back toward its authored pose so a corpse gathers itself.
    // frequency in Hz (<=0 off), damping 1.0 = critical, maxTorque in N*m kept below lifting a limb.
    default void relaxToRest(float frequency, float damping, float maxTorque) {
        // The rest pose is the identity target (limb lined up with its parent); see driveToOrientation.
        driveToOrientation(0f, 0f, 0f, 1f, frequency, damping, maxTorque);
    }

    // Drive the angular motors toward a target orientation in constraint space (A's frame to B's).
    // frequency in Hz (<=0 off), damping 1.0 = critical, maxTorque in N*m; limits always win.
    void driveToOrientation(float qx, float qy, float qz, float qw,
                            float frequency, float damping, float maxTorque);
}
