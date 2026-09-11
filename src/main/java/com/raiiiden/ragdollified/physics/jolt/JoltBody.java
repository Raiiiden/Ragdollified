package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotionProperties;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAllowedDofs;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsShape;

import javax.vecmath.Matrix3f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

// One Jolt rigid body plus bookkeeping Jolt doesn't keep; scratch vectors are reused, so no allocation.
final class JoltBody implements PhysicsBody {

    private final JoltWorld world;
    final Body body;
    final int bodyId;
    private final JoltShape shape;
    // Static as created: terrain. Never changes, and blocks the parking path below: a block was
    // never dynamic and has no mass properties to restore.
    private final boolean bornStatic;
    // Static right now, which for a ragdoll part means parked. See setStatic.
    private boolean isStatic;
    private float mass;

    private Object userPointer;
    private boolean respondsToContacts = true;
    boolean inWorld;

    // No-collide group id, assigned lazily by JoltWorld on first joint; -1 collides with everything.
    int collisionGroupId = -1;
    int collisionSubGroupId = -1;

    // Jolt has no swept-sphere radius and sets motion quality only in-world, so both are remembered here.
    private float ccdMotionThreshold;
    private float ccdSweptSphereRadius;

    private final RVec3 scratchLocation = new RVec3();
    private final Quat scratchOrientation = new Quat();
    private final Vec3 scratchVector = new Vec3();
    private final Quat4f quatOut = new Quat4f();
    private final Matrix3f aabbBasis = new Matrix3f();

    JoltBody(JoltWorld world, Body body, JoltShape shape, boolean isStatic, float mass) {
        this.world = world;
        this.body = body;
        this.bodyId = body.getId();
        this.shape = shape;
        this.bornStatic = isStatic;
        this.isStatic = isStatic;
        this.mass = mass;
    }

    // Transform

    @Override
    public void getWorldTransform(PhysTransform out) {
        body.getPositionAndRotation(scratchLocation, scratchOrientation);
        out.origin.set((float) scratchLocation.xx(), (float) scratchLocation.yy(),
                (float) scratchLocation.zz());
        out.basis.set(toVecmath(scratchOrientation, quatOut));
    }

    @Override
    public void setWorldTransform(PhysTransform transform) {
        transform.getRotation(quatOut);
        scratchLocation.set(transform.origin.x, transform.origin.y, transform.origin.z);
        scratchOrientation.set(quatOut.x, quatOut.y, quatOut.z, quatOut.w);
        setPositionAndRotation();
    }

    private void setPositionAndRotation() {
        if (inWorld) {
            // Through the body interface so the broadphase learns about the move. Doing it on the
            // body directly leaves a stale AABB in the tree and the body stops colliding.
            world.bodyInterface().setPositionAndRotation(
                    bodyId, scratchLocation, scratchOrientation, EActivation.Activate);
        } else {
            body.setPositionAndRotationInternal(scratchLocation, scratchOrientation);
        }
    }

    @Override
    public void getPosition(Vector3f out) {
        body.getPositionAndRotation(scratchLocation, scratchOrientation);
        out.set((float) scratchLocation.xx(), (float) scratchLocation.yy(),
                (float) scratchLocation.zz());
    }

    @Override
    public void getRotation(Quat4f out) {
        body.getPositionAndRotation(scratchLocation, scratchOrientation);
        toVecmath(scratchOrientation, out);
    }

    @Override
    public void getCenterOfMassPosition(Vector3f out) {
        body.getCenterOfMassPosition(scratchLocation);
        out.set((float) scratchLocation.xx(), (float) scratchLocation.yy(),
                (float) scratchLocation.zz());
    }

    @Override
    public void translate(Vector3f delta) {
        body.getPositionAndRotation(scratchLocation, scratchOrientation);
        scratchLocation.set(scratchLocation.xx() + delta.x,
                scratchLocation.yy() + delta.y,
                scratchLocation.zz() + delta.z);
        setPositionAndRotation();
    }

    // Velocity

    @Override
    public void getLinearVelocity(Vector3f out) {
        if (isStatic) {
            out.set(0f, 0f, 0f);
            return;
        }
        // Through the body interface rather than MotionProperties: this overload writes into a
        // caller-supplied Vec3, while the MotionProperties getter returns a fresh one every call.
        world.bodyInterface().getLinearVelocity(bodyId, scratchVector);
        out.set(scratchVector.getX(), scratchVector.getY(), scratchVector.getZ());
    }

    @Override
    public void setLinearVelocity(Vector3f velocity) {
        if (isStatic) return;
        scratchVector.set(velocity.x, velocity.y, velocity.z);
        body.setLinearVelocity(scratchVector);
    }

    @Override
    public void getAngularVelocity(Vector3f out) {
        if (isStatic) {
            out.set(0f, 0f, 0f);
            return;
        }
        world.bodyInterface().getAngularVelocity(bodyId, scratchVector);
        out.set(scratchVector.getX(), scratchVector.getY(), scratchVector.getZ());
    }

    @Override
    public void setAngularVelocity(Vector3f velocity) {
        if (isStatic) return;
        scratchVector.set(velocity.x, velocity.y, velocity.z);
        body.setAngularVelocity(scratchVector);
    }

    @Override
    public void applyCentralImpulse(Vector3f impulse) {
        if (isStatic) return;
        scratchVector.set(impulse.x, impulse.y, impulse.z);
        body.addImpulse(scratchVector);
    }

    @Override
    public void applyImpulse(Vector3f impulse, Vector3f relativePosition) {
        if (isStatic) return;
        // Bullet takes the lever arm relative to the centre of mass; Jolt takes a world-space point.
        body.getCenterOfMassPosition(scratchLocation);
        scratchVector.set(impulse.x, impulse.y, impulse.z);
        scratchLocation.set(scratchLocation.xx() + relativePosition.x,
                scratchLocation.yy() + relativePosition.y,
                scratchLocation.zz() + relativePosition.z);
        body.addImpulse(scratchVector, scratchLocation);
    }

    // Material and damping

    @Override
    public float getFriction() {
        return body.getFriction();
    }

    @Override
    public void setFriction(float friction) {
        body.setFriction(friction);
    }

    @Override
    public void setRestitution(float restitution) {
        body.setRestitution(restitution);
    }

    @Override
    public void setDamping(float linear, float angular) {
        MotionProperties motion = motion();
        if (motion == null) return;
        motion.setLinearDamping(linear);
        motion.setAngularDamping(angular);
    }

    @Override
    public float getCcdMotionThreshold() {
        return ccdMotionThreshold;
    }

    @Override
    public void setCcdMotionThreshold(float threshold) {
        ccdMotionThreshold = threshold;
        applyMotionQuality();
    }

    @Override
    public float getCcdSweptSphereRadius() {
        return ccdSweptSphereRadius;
    }

    @Override
    public void setCcdSweptSphereRadius(float radius) {
        // Jolt's linear cast derives its own sweep from the shape, so there is nothing to apply.
        // The value is still stored: the drag code reads it back and expects what it wrote.
        ccdSweptSphereRadius = radius;
    }

    void applyMotionQuality() {
        if (isStatic || !inWorld) return;
        world.bodyInterface().setMotionQuality(bodyId,
                ccdMotionThreshold > 0f ? EMotionQuality.LinearCast : EMotionQuality.Discrete);
    }

    // Activation

    @Override
    public void setStatic(boolean parked) {
        if (bornStatic || isStatic == parked) return;
        isStatic = parked;
        if (!inWorld) return;
        // Set motion type and object layer together, so a parked body moves to the static broadphase tree.
        world.bodyInterface().setMotionType(bodyId,
                parked ? EMotionType.Static : EMotionType.Dynamic,
                parked ? EActivation.DontActivate : EActivation.Activate);
        // A non-colliding part stays on the no-collision layer when parked.
        world.bodyInterface().setObjectLayer(bodyId, parkedObjectLayer(parked));
        world.onParkedChanged(parked);
        if (!parked) applyMotionQuality();
    }

    @Override
    public void activate() {
        if (isStatic || !inWorld) return;
        world.bodyInterface().activateBody(bodyId);
    }

    @Override
    public void setSleepingAllowed(boolean allowed) {
        if (isStatic) return;
        body.setAllowSleeping(allowed);
        if (!allowed && inWorld) world.bodyInterface().activateBody(bodyId);
    }

    @Override
    public void setSleepingThresholds(float linear, float angular) {
        // Jolt's sleep thresholds are a global PhysicsSettings value, not per body. The ragdoll runs
        // with sleeping disabled and settles bodies itself, so there is nothing to approximate here.
    }

    // Queries

    @Override
    public void setMass(float newMass) {
        MotionProperties motion = motion();
        if (motion == null || newMass <= 0f) return;
        // Take the shape's own inertia tensor and scale it to the new mass, which is what Bullet's
        // calculateLocalInertia produced for the same box.
        MassProperties massProperties = shape.shape.getMassProperties();
        massProperties.scaleToMass(newMass);
        motion.setMassProperties(EAllowedDofs.All, massProperties);
        mass = newMass;
    }

    // Resync the native motion type with the Java flag on every add, since it can't be set out of world.
    void applyMotionTypeOnAdd() {
        if (bornStatic) return;
        world.bodyInterface().setMotionType(bodyId,
                isStatic ? EMotionType.Static : EMotionType.Dynamic,
                isStatic ? EActivation.DontActivate : EActivation.Activate);
        world.bodyInterface().setObjectLayer(bodyId, parkedObjectLayer(isStatic));
    }

    private int parkedObjectLayer(boolean parked) {
        if (!respondsToContacts) return JoltWorld.OBJECT_LAYER_PHANTOM;
        return parked ? JoltWorld.OBJECT_LAYER_STATIC : JoltWorld.OBJECT_LAYER_MOVING;
    }

    @Override
    public void setContactResponse(boolean respond) {
        // bornStatic, not isStatic: a parked ragdoll part is temporarily static and still has to
        // record what layer it belongs in once it is dynamic again.
        if (bornStatic) return;
        // A no-collision object layer rather than the sensor flag, which would still report contacts.
        respondsToContacts = respond;
        world.bodyInterface().setObjectLayer(bodyId, parkedObjectLayer(isStatic));
    }

    boolean respondsToContacts() {
        return respondsToContacts;
    }

    @Override
    public float getInvMass() {
        return isStatic || mass <= 0f ? 0f : 1f / mass;
    }

    @Override
    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public PhysicsShape getShape() {
        return shape;
    }

    @Override
    public void getWorldAabb(Vector3f min, Vector3f max) {
        // Computed from the oriented box rather than read back from Jolt: it is exact for the boxes
        // this mod builds, allocation-free, and does not depend on the broadphase being up to date.
        body.getPositionAndRotation(scratchLocation, scratchOrientation);
        toVecmath(scratchOrientation, quatOut);
        aabbBasis.set(quatOut);
        float ex = Math.abs(aabbBasis.m00) * shape.halfX()
                + Math.abs(aabbBasis.m01) * shape.halfY()
                + Math.abs(aabbBasis.m02) * shape.halfZ();
        float ey = Math.abs(aabbBasis.m10) * shape.halfX()
                + Math.abs(aabbBasis.m11) * shape.halfY()
                + Math.abs(aabbBasis.m12) * shape.halfZ();
        float ez = Math.abs(aabbBasis.m20) * shape.halfX()
                + Math.abs(aabbBasis.m21) * shape.halfY()
                + Math.abs(aabbBasis.m22) * shape.halfZ();
        float px = (float) scratchLocation.xx();
        float py = (float) scratchLocation.yy();
        float pz = (float) scratchLocation.zz();
        min.set(px - ex, py - ey, pz - ez);
        max.set(px + ex, py + ey, pz + ez);
    }

    @Override
    public Object getUserPointer() {
        return userPointer;
    }

    @Override
    public void setUserPointer(Object userPointer) {
        this.userPointer = userPointer;
    }

    @Override
    public boolean isInWorld() {
        return inWorld;
    }

    // Internals

    private MotionProperties motion() {
        return isStatic ? null : body.getMotionPropertiesUnchecked();
    }

    private static Quat4f toVecmath(Quat from, Quat4f out) {
        out.set(from.getX(), from.getY(), from.getZ(), from.getW());
        return out;
    }
}
