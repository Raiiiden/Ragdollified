package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.ContactManifold;
import com.github.stephengold.joltjni.CustomContactListener;
import com.github.stephengold.joltjni.Vec3;

// Buffers Jolt's listener contacts during a step so the physics worker can walk them afterwards.
// Callbacks arrive on several job threads at once, so appends are synchronized.
final class JoltContactCollector extends CustomContactListener {

    private static final int INITIAL_CAPACITY = 4096;

    // Parallel arrays rather than objects: a heavy pile produces thousands of contacts per step, and
    // this is exactly the per-tick garbage the engine swap is supposed to remove.
    private long[] bodyVa = new long[INITIAL_CAPACITY * 2];
    private float[] data = new float[INITIAL_CAPACITY * 4];
    private boolean[] isNew = new boolean[INITIAL_CAPACITY];
    private int count;

    synchronized void reset() {
        count = 0;
    }

    // Synchronized on the append monitor so job-thread writes are visible after Jolt's native barrier.
    synchronized int count() {
        return count;
    }

    long bodyVa1(int index) {
        return bodyVa[index * 2];
    }

    long bodyVa2(int index) {
        return bodyVa[index * 2 + 1];
    }

    // Bullet's sign convention: negative while penetrating.
    float distance(int index) {
        return data[index * 4];
    }

    float normalX(int index) {
        return data[index * 4 + 1];
    }

    float normalY(int index) {
        return data[index * 4 + 2];
    }

    float normalZ(int index) {
        return data[index * 4 + 3];
    }

    boolean isNew(int index) {
        return isNew[index];
    }

    @Override
    public void onContactAdded(long body1Va, long body2Va, long manifoldVa, long settingsVa) {
        record(body1Va, body2Va, manifoldVa, true);
    }

    @Override
    public void onContactPersisted(long body1Va, long body2Va, long manifoldVa, long settingsVa) {
        record(body1Va, body2Va, manifoldVa, false);
    }

    private void record(long body1Va, long body2Va, long manifoldVa, boolean added) {
        ContactManifold manifold = new ContactManifold(manifoldVa);
        float depth = manifold.getPenetrationDepth();
        Vec3 normal = manifold.getWorldSpaceNormal();

        // Flip Jolt's normal (body 1 to 2) to Bullet's normalWorldOnB convention, once here.
        float nx = -normal.getX();
        float ny = -normal.getY();
        float nz = -normal.getZ();

        synchronized (this) {
            ensureCapacity(count + 1);
            int index = count++;
            bodyVa[index * 2] = body1Va;
            bodyVa[index * 2 + 1] = body2Va;
            int base = index * 4;
            // Depth only: this binding doesn't expose contact points; JoltWorld derives locations on demand.
            data[base] = -depth;
            data[base + 1] = nx;
            data[base + 2] = ny;
            data[base + 3] = nz;
            isNew[index] = added;
        }
    }

    private void ensureCapacity(int required) {
        if (required <= isNew.length) return;
        int capacity = Math.max(required, isNew.length * 2);
        long[] grownVa = new long[capacity * 2];
        float[] grownData = new float[capacity * 4];
        boolean[] grownNew = new boolean[capacity];
        System.arraycopy(bodyVa, 0, grownVa, 0, count * 2);
        System.arraycopy(data, 0, grownData, 0, count * 4);
        System.arraycopy(isNew, 0, grownNew, 0, count);
        bodyVa = grownVa;
        data = grownData;
        isNew = grownNew;
    }
}
