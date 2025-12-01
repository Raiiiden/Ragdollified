package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Creeper;
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
            manager.step(1f / 20f);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event){
        LivingEntity entity = event.getEntity();

        // Handle player deaths
        if (entity instanceof ServerPlayer player){
            player.setInvisible(true);

            List<DeathRagdollEntity> existingRagdolls = player.serverLevel()
                    .getEntitiesOfClass(DeathRagdollEntity.class,
                            new AABB(player.blockPosition()).inflate(10000));

            int maxRagdolls = RagdollifiedConfig.getMaxRagdolls();

            if (existingRagdolls.size() >= maxRagdolls) {
                existingRagdolls.stream()
                        .max(Comparator.comparingInt(r -> r.ticksExisted))
                        .ifPresent(oldest -> oldest.discard());
            }

            DeathRagdollEntity deathRagdoll = DeathRagdollEntity.createFromPlayer(player.level(), player);
            player.level().addFreshEntity(deathRagdoll);
        }
        // Handle mob deaths
        else if (shouldCreateMobRagdoll(entity)) {
            if (entity.level() instanceof ServerLevel serverLevel) {
                // Make mob invisible before removing
                entity.setInvisible(true);

                // Check max ragdolls
                List<MobRagdollEntity> existingMobRagdolls = serverLevel
                        .getEntitiesOfClass(MobRagdollEntity.class,
                                new AABB(entity.blockPosition()).inflate(10000));

                int maxRagdolls = RagdollifiedConfig.getMaxRagdolls();

                if (existingMobRagdolls.size() >= maxRagdolls) {
                    existingMobRagdolls.stream()
                            .max(Comparator.comparingInt(r -> r.ticksExisted))
                            .ifPresent(oldest -> oldest.discard());
                }

                // Create mob ragdoll
                MobRagdollEntity mobRagdoll = MobRagdollEntity.createFromMob(entity.level(), entity);
                entity.level().addFreshEntity(mobRagdoll);
            }
        }
    }

    /**
     * Determines which mobs should have ragdolls.
     * Add more mob types here as you implement their renderers.
     */
    private static boolean shouldCreateMobRagdoll(LivingEntity entity) {
        // Only create ragdolls for specific mob types
        return entity instanceof Zombie ||
                entity instanceof Skeleton ||
                entity instanceof Creeper;
        // Add more: entity instanceof Spider, etc.
    }
}