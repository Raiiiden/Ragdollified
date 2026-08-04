package com.raiiiden.ragdollified.client.compat;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.client.ClientMobModelCache;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

// Optional Guard Villagers integration. It links only Minecraft/Forge types, so the project can
// compile and run without Guard Villagers installed.
public final class GuardVillagersCompatibilityHelper {
    private static final String MOD_ID = "guardvillagers";

    private GuardVillagersCompatibilityHelper() {}

    public static void captureModel(LivingEntity entity, Object renderer,
                                    MobModelHelper.ModelType modelType) {
        if (!ModList.get().isLoaded(MOD_ID)
                || modelType != MobModelHelper.ModelType.HUMANOID_STANDARD) return;

        ResourceLocation entityType = EntityType.getKey(entity.getType());
        if (!MOD_ID.equals(entityType.getNamespace())) return;
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) return;
        if (!(living.getModel() instanceof HumanoidModel<?> humanoid)) return;

        // The guard texture is authored for GuardModel's custom UV layout, not vanilla's.
        ClientMobModelCache.cacheModel(entityType.toString(), humanoid);
    }
}
