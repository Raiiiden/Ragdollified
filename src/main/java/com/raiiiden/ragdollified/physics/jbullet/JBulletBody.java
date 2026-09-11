package com.raiiiden.ragdollified.physics.jbullet;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsShape;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

final class JBulletBody implements PhysicsBody {

    final RigidBody body;
    private final JBulletShape shape;
    private final boolean isStatic;
    // Parked in place as an immovable obstacle. Distinct from isStatic, which means "created as
    // terrain" and is never undone.
    private boolean parked;
    private float parkedMass;
    private Object userPointer;

    // Scratch for the PhysTransform <-> Bullet Transform conversions. One per body, touched only on
    // the physics worker, so this costs six objects per ragdoll instead of two per call.
    private final Transform scratch = new Transform();
    private final Vector3f scratchInertia = new Vector3f();

    JBulletBody(RigidBody body, JBulletShape shape, boolean isStatic) {
        this.body = body;
        this.shape = shape;
        this.isStatic = isStatic;
        // The contact iteration needs to get from a Bullet body back to its wrapper, and Bullet's
        // own user pointer is otherwise unused now that callers keep their tag on the wrapper.
        body.setUserPointer(this);
    }

    static JBulletBody of(CollisionObject object) {
        return object.getUserPointer() instanceof JBulletBody wrapper ? wrapper : null;
    }

    @Override
    public void getWorldTransform(PhysTransform out) {
        body.getWorldTransform(scratch);
        copy(scratch, out);
    }

    @Override
    public void setWorldTransform(PhysTransform transform) {
        copy(transform, scratch);
        // setCenterOfMassTransform also refreshes interpolation and world inertia, so teleports aren't stale.
        body.setCenterOfMassTransform(scratch);
        if (body.getMotionState() != null) body.getMotionState().setWorldTransform(scratch);
    }

    @Override
    public void getPosition(Vector3f out) {
        body.getWorldTransform(scratch);
        out.set(scratch.origin);
    }

    @Override
    public void getRotation(Quat4f out) {
        body.getWorldTransform(scratch);
        scratch.getRotation(out);
    }

    @Override
    public void getCenterOfMassPosition(Vector3f out) {
        body.getCenterOfMassPosition(out);
    }

    @Override
    public void translate(Vector3f delta) {
        body.translate(delta);
        if (body.getMotionState() != null) {
            body.getWorldTransform(scratch);
            body.getMotionState().setWorldTransform(scratch);
        }
    }

    @Override
    public void getLinearVelocity(Vector3f out) { body.getLinearVelocity(out); }

    @Override
    public void setLinearVelocity(Vector3f velocity) { body.setLinearVelocity(velocity); }

    @Override
    public void getAngularVelocity(Vector3f out) { body.getAngularVelocity(out); }

    @Override
    public void setAngularVelocity(Vector3f velocity) { body.setAngularVelocity(velocity); }

    @Override
    public void applyCentralImpulse(Vector3f impulse) { body.applyCentralImpulse(impulse); }

    @Override
    public void applyImpulse(Vector3f impulse, Vector3f relativePosition) {
        body.applyImpulse(impulse, relativePosition);
    }

    @Override
    public float getFriction() { return body.getFriction(); }

    @Override
    public void setFriction(float friction) { body.setFriction(friction); }

    @Override
    public void setRestitution(float restitution) { body.setRestitution(restitution); }

    @Override
    public void setDamping(float linear, float angular) { body.setDamping(linear, angular); }

    @Override
    public float getCcdMotionThreshold() { return body.getCcdMotionThreshold(); }

    @Override
    public void setCcdMotionThreshold(float threshold) { body.setCcdMotionThreshold(threshold); }

    @Override
    public float getCcdSweptSphereRadius() { return body.getCcdSweptSphereRadius(); }

    @Override
    public void setCcdSweptSphereRadius(float radius) { body.setCcdSweptSphereRadius(radius); }

    @Override
    public void activate() { body.activate(true); }

    @Override
    public void setSleepingAllowed(boolean allowed) {
        body.forceActivationState(allowed
                ? CollisionObject.ACTIVE_TAG : CollisionObject.DISABLE_DEACTIVATION);
    }

    @Override
    public void setSleepingThresholds(float linear, float angular) {
        body.setSleepingThresholds(linear, angular);
    }

    @Override
    public void setMass(float mass) {
        scratchInertia.set(0f, 0f, 0f);
        shape.shape.calculateLocalInertia(mass, scratchInertia);
        body.setMassProps(mass, scratchInertia);
        // setMassProps only updates the inverse-mass and inverse-inertia scalars; the cached world
        // inertia tensor the solver actually reads is rebuilt here.
        body.updateInertiaTensor();
    }

    @Override
    public void setContactResponse(boolean respond) {
        int flags = body.getCollisionFlags();
        body.setCollisionFlags(respond
                ? flags & ~CollisionFlags.NO_CONTACT_RESPONSE
                : flags | CollisionFlags.NO_CONTACT_RESPONSE);
    }

    @Override
    public void setStatic(boolean parked) {
        if (isStatic || parked == this.parked) return;
        this.parked = parked;
        int flags = body.getCollisionFlags();
        if (parked) {
            // Bullet reads "static" off zero inverse mass plus the flag; the flag alone still lets
            // the solver integrate it. The mass is kept so unparking restores the same inertia.
            parkedMass = body.getInvMass() > 0f ? 1f / body.getInvMass() : 0f;
            scratchInertia.set(0f, 0f, 0f);
            body.setMassProps(0f, scratchInertia);
            body.setCollisionFlags(flags | CollisionFlags.STATIC_OBJECT);
        } else {
            body.setCollisionFlags(flags & ~CollisionFlags.STATIC_OBJECT);
            if (parkedMass > 0f) setMass(parkedMass);
        }
        body.updateInertiaTensor();
    }

    @Override
    public float getInvMass() { return body.getInvMass(); }

    @Override
    public boolean isStatic() { return isStatic; }

    @Override
    public PhysicsShape getShape() { return shape; }

    @Override
    public void getWorldAabb(Vector3f min, Vector3f max) {
        body.getWorldTransform(scratch);
        shape.shape.getAabb(scratch, min, max);
    }

    @Override
    public Object getUserPointer() { return userPointer; }

    @Override
    public void setUserPointer(Object userPointer) { this.userPointer = userPointer; }

    @Override
    public boolean isInWorld() { return body.isInWorld(); }

    private void copy(Transform from, PhysTransform to) {
        to.basis.set(from.basis);
        to.origin.set(from.origin);
    }

    private void copy(PhysTransform from, Transform to) {
        to.basis.set(from.basis);
        to.origin.set(from.origin);
    }
}
