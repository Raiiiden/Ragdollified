package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

// One player ragdoll's rest pose, reported once per body per simulating client; pose is relative to
// origin, impulseRevision is the latest included push, gaveUp means it never settled in time.
public record RagdollSettleEvent(int ragdollEntityId, UUID playerUUID, Vec3 origin,
                                 RagdollTransform[] pose, int impulseRevision, boolean gaveUp) {
}
