package com.raiiiden.ragdollified.api;

// What a ragdoll part struck.
public enum RagdollCollisionType {
    // A static block collider. RagdollCollision#blockPos is set.
    TERRAIN,
    // Another ragdoll. RagdollCollision#otherEntityId and #otherPart are set.
    RAGDOLL,
    // Any other body in the physics world.
    OTHER
}
