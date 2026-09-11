package com.raiiiden.ragdollified.physics;

import javax.vecmath.Matrix3f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

// Mutable rigid transform (rotation basis + origin), shaped like Bullet's to avoid hot-path allocation.
public final class PhysTransform {

    public final Matrix3f basis = new Matrix3f();
    public final Vector3f origin = new Vector3f();

    public PhysTransform() {
        basis.setIdentity();
    }

    public PhysTransform(PhysTransform other) {
        set(other);
    }

    public void setIdentity() {
        basis.setIdentity();
        origin.set(0f, 0f, 0f);
    }

    public void set(PhysTransform other) {
        basis.set(other.basis);
        origin.set(other.origin);
    }

    public void set(Vector3f position, Quat4f rotation) {
        origin.set(position);
        setRotation(rotation);
    }

    public void setRotation(Quat4f q) {
        basis.set(q);
    }

    // Writes this transform's rotation into out and returns it. Shoemake's algorithm, since
    // Quat4f.set(Matrix3f) breaks near 180-degree off-axis rotations, which the factory uses often.
    public Quat4f getRotation(Quat4f out) {
        float trace = basis.m00 + basis.m11 + basis.m22;
        if (trace > 0f) {
            float s = (float) Math.sqrt(trace + 1.0f);
            float t = 0.5f / s;
            out.set((basis.m21 - basis.m12) * t,
                    (basis.m02 - basis.m20) * t,
                    (basis.m10 - basis.m01) * t,
                    s * 0.5f);
            return out;
        }
        // Pivot on the largest diagonal element, which is what keeps the square root well away
        // from zero in exactly the cases the naive form falls apart on.
        int i = basis.m00 < basis.m11
                ? (basis.m11 < basis.m22 ? 2 : 1)
                : (basis.m00 < basis.m22 ? 2 : 0);
        int j = (i + 1) % 3;
        int k = (i + 2) % 3;
        float s = (float) Math.sqrt(
                basis.getElement(i, i) - basis.getElement(j, j) - basis.getElement(k, k) + 1.0f);
        float t = 0.5f / s;
        float[] q = QUAT_SCRATCH.get();
        q[i] = s * 0.5f;
        q[3] = (basis.getElement(k, j) - basis.getElement(j, k)) * t;
        q[j] = (basis.getElement(j, i) + basis.getElement(i, j)) * t;
        q[k] = (basis.getElement(k, i) + basis.getElement(i, k)) * t;
        out.set(q[0], q[1], q[2], q[3]);
        return out;
    }

    // Thread-local because PhysTransform instances are also read from the render thread through the
    // published snapshot path, and this is the one method with intermediate state.
    private static final ThreadLocal<float[]> QUAT_SCRATCH = ThreadLocal.withInitial(() -> new float[4]);

    // Rotates and translates v in place.
    public void transform(Vector3f v) {
        basis.transform(v);
        v.add(origin);
    }

    // Applies only the inverse rotation to v in place: the linear part of the inverse transform.
    public void inverseRotate(Vector3f v) {
        float x = v.x, y = v.y, z = v.z;
        v.set(basis.m00 * x + basis.m10 * y + basis.m20 * z,
              basis.m01 * x + basis.m11 * y + basis.m21 * z,
              basis.m02 * x + basis.m12 * y + basis.m22 * z);
    }

    @Override
    public String toString() {
        return "PhysTransform[origin=" + origin + "]";
    }
}
