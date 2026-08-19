package com.raiiiden.ragdollified.api;

// Called on the client thread once per new contact, never from the physics worker.
@FunctionalInterface
public interface RagdollCollisionListener {
    void onRagdollCollision(RagdollCollision collision);
}
