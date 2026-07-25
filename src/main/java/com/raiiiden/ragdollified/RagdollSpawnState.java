package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the entity state handed from Minecraft to the client-side physics world.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public final class RagdollSpawnState {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final long SAMPLE_TTL_MS = 10_000L;
    private static final Map<Long, VelocitySample> PRE_HIT_VELOCITIES = new ConcurrentHashMap<>();
    private static int cleanupCounter;

    private RagdollSpawnState() {}

    private record VelocitySample(Vec3 velocity, long capturedAtMs) {}

    /**
     * Capture before vanilla/TACZ applies the fatal hit's knockback. Reading movement from
     * LivingDeathEvent is too late and mixes locomotion with the outside death impulse.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        PRE_HIT_VELOCITIES.put(key(entity),
                new VelocitySample(entity.getDeltaMovement(), System.currentTimeMillis()));
        if (++cleanupCounter >= 256) {
            cleanupCounter = 0;
            long cutoff = System.currentTimeMillis() - SAMPLE_TTL_MS;
            PRE_HIT_VELOCITIES.entrySet().removeIf(entry -> entry.getValue().capturedAtMs < cutoff);
        }
    }

    /**
     * Minecraft stores entity movement in blocks per tick, while jBullet expects blocks
     * per second. Prefer the pre-hit sample so fatal knockback is not mistaken for walking
     * or sprinting speed. Damage-specific recoil is applied separately after body creation.
     */
    public static Vec3 captureLinearVelocity(LivingEntity entity) {
        VelocitySample sample = PRE_HIT_VELOCITIES.remove(key(entity));
        Vec3 movement = sample != null ? sample.velocity : entity.getDeltaMovement();
        return movement.scale(TICKS_PER_SECOND);
    }

    private static long key(LivingEntity entity) {
        return ((entity.getId() & 0xffffffffL) << 1) | (entity.level().isClientSide ? 1L : 0L);
    }

    /**
     * Explosion knockback is added by vanilla after {@code LivingEntity.hurt()} returns,
     * but the death event fires from inside that call. Carry it as a separate velocity
     * change so it adds to, rather than replaces, the captured locomotion.
     */
    @Nullable
    public static Vec3 captureExplosionVelocityKick(LivingEntity entity,
                                                     @Nullable DamageSource damageSource) {
        if (damageSource == null || !damageSource.is(DamageTypeTags.IS_EXPLOSION)) return null;

        Vec3 explosionCenter = damageSource.getSourcePosition();
        if (explosionCenter == null) return null;

        Vec3 direction = entity.position().subtract(explosionCenter).normalize();
        float distance = (float) entity.position().distanceTo(explosionCenter);
        float bodyScale = Math.min(entity.getMaxHealth() / 10f, 5f);
        float distanceFalloff = Math.max(0.5f, 1.0f - (distance / 10f));
        float strength = bodyScale * distanceFalloff;
        double impulse = RagdollifiedConfig.get(RagdollifiedConfig.HIT_IMPULSE_EXPLOSION);
        return new Vec3(
                direction.x * impulse * strength,
                direction.y * impulse * 0.8 * strength + 3.0,
                direction.z * impulse * strength
        );
    }
}
