package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

// One validated, server-ordered push: part, impulse, world impactPoint (nullable), sourceDamage
// (0 if unknown) and revision; enough for an addon to decide whether a limb comes off.
public record RagdollImpulse(int ragdollEntityId, RagdollPart part, Vec3 impulse,
                             @Nullable Vec3 impactPoint, float sourceDamage, int revision) {

    // Magnitude of the push.
    public double strength() {
        return impulse == null ? 0.0 : impulse.length();
    }
}
