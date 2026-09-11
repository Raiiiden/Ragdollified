package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.world.phys.Vec3;

// Exact box intersection against one loose limb, keyed on limb id since limbs outlive their entity.
public record LimbHit(int limbId, RagdollPart part, Vec3 position, double distance) {}
