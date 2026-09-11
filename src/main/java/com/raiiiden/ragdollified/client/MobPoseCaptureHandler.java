package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Captures poses client-side during rendering, players included; read only when building ragdolls.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class MobPoseCaptureHandler {

    private static int cleanupCounter = 0;

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static <T extends LivingEntity, M extends EntityModel<T>> void onRenderLivingPre(
            RenderLivingEvent.Pre<T, M> event) {

        LivingEntity entity = event.getEntity();

        // Players never reach ClientMobModelHelper, which resolves a type from the mob's model; they
        // are always the standard humanoid rig the factory builds them with.
        MobModelHelper.ModelType modelType = entity instanceof Player
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (modelType == MobModelHelper.ModelType.UNSUPPORTED) {
            return;
        }

        try {
            LivingEntityRenderer<T, M> renderer = (LivingEntityRenderer<T, M>) event.getRenderer();
            EntityModel<T> model = renderer.getModel();

            if (model != null) {
                ClientMobPoseCapture.beginRenderCapture(entity, modelType, model, event.getPoseStack());
            }
        } catch (Exception e) {
            PoseCaptureSession.abort();
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        // Unconditional: whatever happened between Pre and here, the session closes with this frame
        // rather than leaking part transforms into the next entity drawn.
        PoseCaptureSession.commit();

        cleanupCounter++;
        if (cleanupCounter > 200) {
            MobPoseCapture.cleanup();
            cleanupCounter = 0;
        }
    }
}
