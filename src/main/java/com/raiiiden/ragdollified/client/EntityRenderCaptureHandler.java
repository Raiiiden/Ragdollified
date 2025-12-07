package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.compat.ETFCompatibilityHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Captures textures during entity rendering where ETF data is available
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class EntityRenderCaptureHandler {

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static <T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>> void onRenderLivingPre(
            RenderLivingEvent.Pre<T, M> event) {

        LivingEntity entity = event.getEntity();

        // Only cache for mobs we support
        if (!shouldCaptureMobTexture(entity)) return;

        try {
            // Get default texture
            ResourceLocation defaultTexture = ((net.minecraft.client.renderer.entity.LivingEntityRenderer<T, M>) event.getRenderer())
                    .getTextureLocation((T) entity);

            // Get ETF variant if available (uses reflection, safe without ETF)
            ResourceLocation actualTexture = ETFCompatibilityHelper.getVariantTexture(entity, defaultTexture);

            // Store in cache - when this mob dies, we'll use this texture
            ClientMobTextureCache.cacheTexture(entity.getId(), actualTexture);

        } catch (Exception e) {
            // Ignore errors - will fall back to default texture
        }
    }

    private static boolean shouldCaptureMobTexture(LivingEntity entity) {
        return entity instanceof net.minecraft.world.entity.monster.Zombie ||
                entity instanceof net.minecraft.world.entity.monster.Skeleton ||
                entity instanceof net.minecraft.world.entity.monster.Creeper;
    }
}