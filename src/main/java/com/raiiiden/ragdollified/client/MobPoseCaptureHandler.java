// ============================================================================
// FIX 1: Capture pose immediately on death (LivingDeathEvent)
// Add to MobPoseCaptureHandler.java
// ============================================================================

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
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class MobPoseCaptureHandler {

    private static int cleanupCounter = 0;
    private static final Set<Integer> SENT_POSES = new HashSet<>();
    private static final Map<Integer, Float> LAST_HEALTH = new HashMap<>();

    // NEW: Capture pose immediately when mob dies on client
    @SubscribeEvent
    public static void onMobDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide) return;

        LivingEntity entity = event.getEntity();
        if (!shouldCapturePose(entity)) return;

        // Capture pose RIGHT NOW before entity disappears
        try {
            Minecraft mc = Minecraft.getInstance();
            var renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            if (renderer instanceof LivingEntityRenderer<?,?> livingRenderer) {
                EntityModel<?> model = livingRenderer.getModel();
                if (model != null) {
                    // Capture locally
                    MobPoseCapture.capturePose(entity.getId(), model);

                    // Send to server immediately
                    MobPoseCapture.MobPose pose = MobPoseCapture.getPose(entity.getId());
                    if (pose != null) {
                        ModNetwork.CHANNEL.sendToServer(new MobPoseSyncPacket(entity.getId(), pose));
                        Ragdollified.LOGGER.debug("Captured death pose for entity {}", entity.getId());
                    }
                }
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.warn("Failed to capture death pose for entity {}: {}",
                    entity.getId(), e.getMessage());
        }
    }

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

                // Check if entity took damage (health decreased)
                float currentHealth = entity.getHealth();
                Float previousHealth = LAST_HEALTH.get(entity.getId());

                // Send pose if:
                // 1. Entity took damage (health decreased) OR
                // 2. Entity is below 80% health (in case we missed initial damage)
                // AND we haven't sent it yet
                boolean tookDamage = previousHealth != null && currentHealth < previousHealth;
                boolean isLowHealth = currentHealth < entity.getMaxHealth() * 0.8f;

                if ((tookDamage || isLowHealth) && !SENT_POSES.contains(entity.getId())) {
                    MobPoseCapture.MobPose pose = MobPoseCapture.getPose(entity.getId());
                    if (pose != null) {
                        // Send pose to server immediately
                        ModNetwork.CHANNEL.sendToServer(new MobPoseSyncPacket(entity.getId(), pose));
                        SENT_POSES.add(entity.getId());

                        Ragdollified.LOGGER.debug("Sent damage pose for entity {} to server (health: {}/{})",
                                entity.getId(), (int)currentHealth, (int)entity.getMaxHealth());
                    }
                }

                // Update last known health
                LAST_HEALTH.put(entity.getId(), currentHealth);
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.debug("Failed to capture pose for entity {}: {}",
                    entity.getId(), e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        cleanupCounter++;
        if (cleanupCounter > 200) {
            MobPoseCapture.cleanup();
            SENT_POSES.clear();
            LAST_HEALTH.clear();
            cleanupCounter = 0;
        }
    }

    private static boolean shouldCapturePose(LivingEntity entity) {
        return entity instanceof net.minecraft.world.entity.monster.Zombie ||
                entity instanceof net.minecraft.world.entity.monster.Skeleton ||
                entity instanceof net.minecraft.world.entity.monster.Creeper ||
                entity instanceof net.minecraft.world.entity.animal.Chicken;
    }
}