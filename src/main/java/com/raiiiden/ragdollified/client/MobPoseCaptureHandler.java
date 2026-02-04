package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.MobPoseSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class MobPoseCaptureHandler {

    private static int cleanupCounter = 0;

    // Track last sent pose to avoid spamming identical data
    private static final Map<Integer, Long> LAST_POSE_SEND_TIME = new HashMap<>();
    private static final int SEND_INTERVAL_TICKS = 5; // Send every 5 ticks (4 times per second)

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static <T extends LivingEntity, M extends EntityModel<T>> void onRenderLivingPre(
            RenderLivingEvent.Pre<T, M> event) {

        LivingEntity entity = event.getEntity();

        if (!shouldCapturePose(entity)) {
            return;
        }

        try {
            LivingEntityRenderer<T, M> renderer = (LivingEntityRenderer<T, M>) event.getRenderer();
            EntityModel<T> model = renderer.getModel();

            if (model != null) {
                // ALWAYS capture pose locally (for client-side use)
                MobPoseCapture.capturePose(entity.getId(), model);

                // Send to server periodically (not every frame)
                long currentTime = Minecraft.getInstance().level.getGameTime();
                Long lastSendTime = LAST_POSE_SEND_TIME.get(entity.getId());

                boolean shouldSend = lastSendTime == null ||
                        (currentTime - lastSendTime) >= SEND_INTERVAL_TICKS;

                if (shouldSend) {
                    MobPoseCapture.MobPose pose = MobPoseCapture.getPose(entity.getId());
                    if (pose != null) {
                        // Send pose to server
                        ModNetwork.CHANNEL.sendToServer(new MobPoseSyncPacket(entity.getId(), pose));
                        LAST_POSE_SEND_TIME.put(entity.getId(), currentTime);
                    }
                }
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

            // Cleanup old send times
            if (Minecraft.getInstance().level != null) {
                long currentTime = Minecraft.getInstance().level.getGameTime();
                LAST_POSE_SEND_TIME.entrySet().removeIf(entry ->
                        (currentTime - entry.getValue()) > 100); // Remove if not sent in 5 seconds
            }

            cleanupCounter = 0;
        }
    }

    private static boolean shouldCapturePose(LivingEntity entity) {
        return entity instanceof net.minecraft.world.entity.monster.Zombie ||
                entity instanceof net.minecraft.world.entity.monster.Skeleton ||
                entity instanceof net.minecraft.world.entity.monster.Creeper;
    }
}