package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.world.phys.Vec3;

// Exact snapshot-box intersection returned by raycast.
public record RagdollHit(int entityId, RagdollPart part, Vec3 position, double distance) {}
