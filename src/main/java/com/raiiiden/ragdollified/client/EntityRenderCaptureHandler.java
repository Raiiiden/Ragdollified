package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.compat.ETFCompatibilityHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class EntityRenderCaptureHandler {

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static <T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>> void onRenderLivingPre(
            RenderLivingEvent.Pre<T, M> event) {

        LivingEntity entity = event.getEntity();

        // Skip players
        if (entity instanceof net.minecraft.world.entity.player.Player) return;

        // Check if supported model type
        MobModelHelper.ModelType modelType = MobModelHelper.getActualModelType(entity);
        if (modelType == MobModelHelper.ModelType.UNSUPPORTED) return;

        try {
            ResourceLocation defaultTexture = ((net.minecraft.client.renderer.entity.LivingEntityRenderer<T, M>) event.getRenderer())
                    .getTextureLocation((T) entity);

            ResourceLocation actualTexture = ETFCompatibilityHelper.getVariantTexture(entity, defaultTexture);

            ClientMobTextureCache.cacheTexture(entity.getId(), actualTexture);

        } catch (Exception e) {
            // Ignore
        }
    }
}