package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.MotorSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.SixDofConstraint;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.TwoBodyConstraintRef;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.github.stephengold.joltjni.enumerate.EAxis;
import com.github.stephengold.joltjni.enumerate.EMotorState;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;

final class JoltConstraint implements PhysicsConstraint {

    final TwoBodyConstraint constraint;
    // Our own counted reference, so removing the joint while frozen doesn't free the native object.
    private final TwoBodyConstraintRef reference;
    private final JoltBody bodyA;
    private final JoltBody bodyB;
    private boolean keepsCollision;
    boolean inWorld;

    JoltConstraint(TwoBodyConstraint constraint, JoltBody bodyA, JoltBody bodyB) {
        this.constraint = constraint;
        this.reference = constraint.toRef();
        this.bodyA = bodyA;
        this.bodyB = bodyB;
    }

    @Override
    public PhysicsBody bodyA() {
        return bodyA;
    }

    @Override
    public PhysicsBody bodyB() {
        return bodyB;
    }

    private static final EAxis[] ROTATION_AXES = {EAxis.RotationX, EAxis.RotationY, EAxis.RotationZ};

    @Override
    public void driveToOrientation(float qx, float qy, float qz, float qw,
                                   float frequency, float damping, float maxTorque) {
        if (!(constraint instanceof SixDofConstraint sixDof)) return;

        // Zero is an instruction, not a no-op: it turns the motor off and leaves the joint to its
        // limits alone. The ragdoll uses it to let go of a body while it is off the ground.
        if (frequency <= 0f) {
            for (EAxis axis : ROTATION_AXES) sixDof.setMotorState(axis, EMotorState.Off);
            return;
        }

        for (EAxis axis : ROTATION_AXES) {
            MotorSettings motor = sixDof.getMotorSettings(axis);
            motor.getSpringSettings().setFrequency(frequency);
            motor.getSpringSettings().setDamping(damping);
            motor.setTorqueLimits(-maxTorque, maxTorque);
            sixDof.setMotorState(axis, EMotorState.Position);
        }
        // Constraint space: identity means the limb lined up with its parent, which relaxToRest passes.
        sixDof.setTargetOrientationCs(new Quat(qx, qy, qz, qw));
    }

    @Override
    public boolean keepsCollision() {
        return keepsCollision;
    }

    @Override
    public void setKeepsCollision(boolean keepsCollision) {
        this.keepsCollision = keepsCollision;
    }

    TwoBodyConstraintRef reference() {
        return reference;
    }
}
