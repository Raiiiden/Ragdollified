package com.raiiiden.ragdollified.physics;

import javax.vecmath.Vector3f;

// A collision shape owned by a PhysicsWorld. Every shape this mod builds is a box, so the interface
// stays deliberately narrow; anything wider would be speculative surface for backends to implement.
public interface PhysicsShape {

    // True collision half extents with margins and convex radii resolved, so callers never add a fudge.
    void getHalfExtents(Vector3f out);

    boolean isBox();
}
