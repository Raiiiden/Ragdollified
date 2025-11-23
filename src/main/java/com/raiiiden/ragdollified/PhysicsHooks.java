package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public class PhysicsHooks {
    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            JbulletWorld manager = JbulletWorld.get(level);
            manager.step(1f / 20f); // Minecraft server ticks at 20 TPS
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event){
        if (event.getEntity() instanceof ServerPlayer player){
            player.setInvisible(true);
            // Get all existing ragdolls in the level
            List<DeathRagdollEntity> existingRagdolls = player.serverLevel()
                    .getEntitiesOfClass(DeathRagdollEntity.class,
                            new AABB(player.blockPosition()).inflate(10000));

            int maxRagdolls = RagdollifiedConfig.getMaxRagdolls();

            // Remove oldest ragdoll if we're at the limit
            if (existingRagdolls.size() >= maxRagdolls) {
                existingRagdolls.stream()
                        .max(Comparator.comparingInt(r -> r.ticksExisted))
                        .ifPresent(oldest -> {
                            // Ragdollified.LOGGER.info("Max ragdolls reached (" + maxRagdolls + "), removing oldest ragdoll ID: " + oldest.getId());
                            oldest.discard();
                        });
            }

            // Create new death ragdoll
            DeathRagdollEntity deathRagdoll = DeathRagdollEntity.createFromPlayer(player.level(), player);
            player.level().addFreshEntity(deathRagdoll);
        }
    }
}