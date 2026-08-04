package com.raiiiden.ragdollified.api;

import net.minecraft.world.phys.Vec3;

// Server-authoritative spawn transform, so every peer builds the same body instead of one
// derived from its own interpolated entity copy.
public record RagdollSpawnTransform(Vec3 position, float bodyYaw, float pitch, Vec3 velocity, boolean swimming) {
    public RagdollSpawnTransform {
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        velocity = velocity == null ? Vec3.ZERO : velocity;
    }

    public static RagdollSpawnTransform at(Vec3 position, float bodyYaw, float pitch) {
        return new RagdollSpawnTransform(position, bodyYaw, pitch, Vec3.ZERO, false);
    }
}
