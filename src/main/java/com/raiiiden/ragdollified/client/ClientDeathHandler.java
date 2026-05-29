package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side death detection. Listens for LivingDeathEvent on the client
 * and creates ragdolls via ClientRagdollManager.
 * This enables ragdolls to work on vanilla servers without the mod installed.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT)
public class ClientDeathHandler {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // Only handle on client side
        if (!entity.level().isClientSide) return;

        // Skip if we already have a ragdoll for this entity
        if (ClientRagdollManager.hasRagdollFor(entity.getId())) return;

        // Check if this entity should have a ragdoll
        boolean isPlayer = entity instanceof Player;
        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isSupportedModelType(modelType)) {
            String mobType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
            Ragdollified.LOGGER.info("Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render", mobType);
            return;
        }

        // Hide the original entity on client
        entity.setInvisible(true);
        entity.clearFire();
        if (isPlayer) {
            entity.setCustomNameVisible(false);
        }

        // Create the ragdoll
        ClientRagdollManager.createFromEntity(entity, event.getSource());

        Ragdollified.LOGGER.debug("Client created ragdoll for {} (id: {})",
                entity.getName().getString(), entity.getId());
    }
}
