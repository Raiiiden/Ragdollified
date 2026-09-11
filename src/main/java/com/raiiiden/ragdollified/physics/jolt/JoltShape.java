package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.Vec3;
import com.raiiiden.ragdollified.physics.PhysicsShape;

import javax.vecmath.Vector3f;

final class JoltShape implements PhysicsShape {

    // Shrink the convex radius for tiny parts, since Jolt rejects a radius larger than any half extent.
    private static final float MAX_CONVEX_RADIUS = Jolt.cDefaultConvexRadius;

    // The Java wrapper co-owns the native shape, so this field is what keeps the shape alive for as
    // long as any body references it. Do not let it go unreferenced while bodies still use it.
    final BoxShape shape;
    private final float halfX;
    private final float halfY;
    private final float halfZ;

    JoltShape(float halfX, float halfY, float halfZ) {
        // Jolt asserts on non-positive extents. A degenerate part is a bug upstream, but crashing
        // the JVM over it is not an acceptable way to report one.
        this.halfX = Math.max(1.0e-4f, halfX);
        this.halfY = Math.max(1.0e-4f, halfY);
        this.halfZ = Math.max(1.0e-4f, halfZ);
        float smallest = Math.min(this.halfX, Math.min(this.halfY, this.halfZ));
        float convexRadius = Math.min(MAX_CONVEX_RADIUS, smallest * 0.5f);
        this.shape = new BoxShape(new Vec3(this.halfX, this.halfY, this.halfZ), convexRadius);
    }

    @Override
    public void getHalfExtents(Vector3f out) {
        // Jolt carves the convex radius out of the box, so the requested extents are the collision
        // extents: no margin to add back, unlike Bullet.
        out.set(halfX, halfY, halfZ);
    }

    @Override
    public boolean isBox() {
        return true;
    }

    float halfX() {
        return halfX;
    }

    float halfY() {
        return halfY;
    }

    float halfZ() {
        return halfZ;
    }
}
