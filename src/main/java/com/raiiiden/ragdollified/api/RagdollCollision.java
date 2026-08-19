package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

// One new contact on a ragdoll part, reported when it forms rather than every tick it lasts.
// impactSpeed is the closing speed along the normal in blocks per second, from pre-step velocities.
public record RagdollCollision(
        int entityId,
        RagdollPart part,
        RagdollCollisionType type,
        Vec3 position,
        Vec3 normal,
        double impactSpeed,
        double relativeSpeed,
        double appliedImpulse,
        double penetration,
        @Nullable BlockPos blockPos,
        int otherEntityId,
        @Nullable RagdollPart otherPart) {

    public boolean isTerrain() { return type == RagdollCollisionType.TERRAIN; }
    public boolean isRagdoll() { return type == RagdollCollisionType.RAGDOLL; }
}
