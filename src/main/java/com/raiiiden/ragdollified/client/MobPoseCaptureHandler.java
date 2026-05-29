package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Captures mob poses locally on the client during rendering.
 * No server sync needed — poses are used client-side when creating ragdolls.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class MobPoseCaptureHandler {

    private static int cleanupCounter = 0;

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static <T extends LivingEntity, M extends EntityModel<T>> void onRenderLivingPre(
            RenderLivingEvent.Pre<T, M> event) {

        LivingEntity entity = event.getEntity();

        // Skip players
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return;
        }

        // Check if this mob has a supported model type
        MobModelHelper.ModelType modelType = ClientMobModelHelper.getActualModelType(entity);
        if (modelType == MobModelHelper.ModelType.UNSUPPORTED) {
            return;
        }

        try {
            LivingEntityRenderer<T, M> renderer = (LivingEntityRenderer<T, M>) event.getRenderer();
            EntityModel<T> model = renderer.getModel();

            if (model != null) {
                ClientMobPoseCapture.capturePose(entity.getId(), model);
            }
        } catch (Exception e) {
            // Silent fail
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        cleanupCounter++;
        if (cleanupCounter > 200) {
            MobPoseCapture.cleanup();
            cleanupCounter = 0;
        }
    }
}
