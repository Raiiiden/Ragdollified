package com.raiiiden.ragdollified.api;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

// Called on the client thread when a locally simulated player ragdoll reaches its rest pose.
// Settle tracking does no work at all until a listener registers.
@OnlyIn(Dist.CLIENT)
@FunctionalInterface
public interface RagdollSettleListener {
    void onRagdollSettled(RagdollSettleEvent event);
}
