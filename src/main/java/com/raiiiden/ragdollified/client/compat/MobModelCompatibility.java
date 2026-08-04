package com.raiiiden.ragdollified.client.compat;

import com.raiiiden.ragdollified.MobModelHelper;
import net.minecraft.world.entity.LivingEntity;

// Stable, dependency-free boundary between core render capture and optional mod adapters.
public final class MobModelCompatibility {
    private MobModelCompatibility() {}

    public static void capture(LivingEntity entity, Object renderer, MobModelHelper.ModelType modelType) {
        GuardVillagersCompatibilityHelper.captureModel(entity, renderer, modelType);
    }
}
