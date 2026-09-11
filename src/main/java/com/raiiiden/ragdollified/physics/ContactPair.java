package com.raiiiden.ragdollified.physics;

import javax.vecmath.Vector3f;

// Read-only cursor over contacts between two bodies, valid only inside the visit callback.
// distance() is negative when penetrating; getNormalOnB() points from bodyB towards bodyA.
public interface ContactPair {

    PhysicsBody bodyA();

    PhysicsBody bodyB();

    int contactCount();

    // Moves the cursor. All the per-point readers below describe the selected contact.
    void selectContact(int index);

    float distance();

    void getNormalOnB(Vector3f out);

    void getPositionOnB(Vector3f out);

    // Impulse the solver applied at this point on the previous step, or 0 where the backend does not
    // expose it. Callers that need a magnitude should prefer their own pre-step velocity sampling.
    float appliedImpulse();

    // True on the step the contact first appeared. The collision API reports each impact once.
    boolean isNewContact();
}
