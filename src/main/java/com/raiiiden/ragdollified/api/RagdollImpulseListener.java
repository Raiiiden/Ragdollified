package com.raiiiden.ragdollified.api;

import net.minecraft.server.level.ServerPlayer;

// Server-side notice that a validated push is about to be broadcast. revision matches later
// RagdollSettleEvents, so a listener can tell whether a pose already includes this push.
@FunctionalInterface
public interface RagdollImpulseListener {
    void onRagdollImpulse(ServerPlayer sender, int ragdollEntityId, int revision);

    // Detailed form (part, force, impact point, weapon damage); override when the details matter.
    // Only this one is called per push; the default forwards to the plain form.
    default void onRagdollImpulse(ServerPlayer sender, RagdollImpulse impulse) {
        onRagdollImpulse(sender, impulse.ragdollEntityId(), impulse.revision());
    }
}
