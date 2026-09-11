package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat;
import com.raiiiden.ragdollified.client.compat.ETFCompatibilityHelper;
import com.raiiiden.ragdollified.client.compat.MobModelCompatibility;
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
        MobModelHelper.ModelType modelType = ClientMobModelHelper.getActualModelType(entity);
        if (modelType == MobModelHelper.ModelType.UNSUPPORTED) return;

        try {
            captureRenderState(entity, event.getRenderer(), modelType);

            // Snapshot the mob's procedural blood while it is alive, since BBO frees the wound textures
            // when it leaves the level, for the same reason its texture is cached here.
            BetterBloodOverlayCompat.capture(entity.getId(), entity);

        } catch (Exception e) {
            // Ignore
        }
    }

    // Capture on demand at death as well as from the render event: relying on the render event alone
    // leaves fast deaths, off-screen entities and packet races without a generated skin or model.
    public static void captureRenderState(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player) return;

        try {
            Object renderer = net.minecraft.client.Minecraft.getInstance()
                    .getEntityRenderDispatcher().getRenderer(entity);
            MobModelHelper.ModelType modelType = ClientMobModelHelper.getActualModelType(entity);
            captureRenderState(entity, renderer, modelType);
        } catch (Exception ignored) {
            // A missing/third-party renderer must not prevent the ragdoll from spawning.
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void captureRenderState(LivingEntity entity, Object renderer,
                                           MobModelHelper.ModelType modelType) {
        if (!(renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer living)) return;

        ResourceLocation defaultTexture = living.getTextureLocation(entity);
        if (defaultTexture != null) {
            ResourceLocation actualTexture = ETFCompatibilityHelper.getVariantTexture(entity, defaultTexture);
            if (actualTexture != null) ClientMobTextureCache.cacheTexture(entity.getId(), actualTexture);
        }

        MobModelCompatibility.capture(entity, living, modelType);
    }
}
