package com.raiiiden.ragdollified.physics;

// Callback for PhysicsWorld#forEachContactPair. The pair is only valid inside visit().
@FunctionalInterface
public interface ContactVisitor {
    void visit(ContactPair pair);
}
