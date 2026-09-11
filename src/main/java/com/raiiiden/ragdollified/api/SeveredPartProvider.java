package com.raiiiden.ragdollified.api;

import net.minecraft.world.entity.LivingEntity;

// Server-side source of parts an entity already lost, asked once per death on the server thread.
// Return a RagdollPart bitmask (0 for intact); providers are OR-ed together.
@FunctionalInterface
public interface SeveredPartProvider {
    int severedPartMask(LivingEntity entity);
}
