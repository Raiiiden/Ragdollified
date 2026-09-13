package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Client-side death detection: watches LivingDeathEvent and builds ragdolls through
// ClientRagdollManager, which is what makes them work on vanilla servers.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT)
public class ClientDeathHandler {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // Only handle on client side
        if (!entity.level().isClientSide) return;

        // Check if this entity should have a ragdoll
        boolean isPlayer = entity instanceof Player;
        String mobType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(mobType, isPlayer)) return;

        // Snapshot the compat visuals before the early-out below: a server spawn packet often lands
        // first, and this is the last moment the entity is guaranteed to still carry its wounds.
        ClientRagdollManager.captureCompatVisuals(entity);

        // Skip if either spawn path has already queued or constructed this entity.
        if (ClientRagdollManager.hasPendingOrActiveRagdoll(entity.getId())) return;

        // Singleplayer: build the ragdoll now instead of waiting a server round trip; the packet dedupes.
        if (RagdollifiedConfig.hasServerSnapshot()
                && !net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer()) {
            return;
        }

        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isSupportedModelType(modelType)) {
            Ragdollified.LOGGER.debug("Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render", mobType);
            return;
        }

        // Create the ragdoll
        ClientRagdollManager.createFromEntity(entity, event.getSource());

        Ragdollified.LOGGER.debug("Client queued ragdoll for {} (id: {})",
                entity.getName().getString(), entity.getId());
    }
}
