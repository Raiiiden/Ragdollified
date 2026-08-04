package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Captures the entity state handed from Minecraft to the client-side physics world.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public final class RagdollSpawnState {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final long SAMPLE_TTL_MS = 10_000L;
    private static final Map<Long, VelocitySample> PRE_HIT_VELOCITIES = new ConcurrentHashMap<>();
    private static int cleanupCounter;

    private RagdollSpawnState() {}

    private record VelocitySample(Vec3 velocity, long capturedAtMs) {}

    // Capture before vanilla or TACZ applies the fatal hit's knockback. LivingDeathEvent is too
    // late and mixes locomotion in with the death impulse.
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

    // Minecraft stores movement in blocks per tick, jBullet wants blocks per second. Prefer the
    // pre-hit sample so knockback is not read as walking speed; damage recoil is applied
    // separately after the bodies exist.
    public static Vec3 captureLinearVelocity(LivingEntity entity) {
        VelocitySample sample = PRE_HIT_VELOCITIES.remove(key(entity));
        Vec3 movement = sample != null ? sample.velocity : entity.getDeltaMovement();
        return movement.scale(TICKS_PER_SECOND);
    }

    // A stationary victim has no inherited motion, so even a correctly directed limb impulse
    // can make the body fold mostly in place. Give low-motion deaths a small whole-body carry
    // in the attacker's facing direction. This is deliberately below normal mob running speed
    // and only fills horizontal motion; real locomotion and vertical fall velocity are retained.
    public static Vec3 applyAttackerDirectionFallback(LivingEntity victim,
                                                       @Nullable DamageSource damageSource,
                                                       Vec3 capturedVelocity) {
        Vec3 velocity = capturedVelocity != null ? capturedVelocity : Vec3.ZERO;
        if (damageSource == null || damageSource.is(DamageTypeTags.IS_EXPLOSION)) return velocity;

        final double minimumHorizontalSpeed = RagdollifiedConfig.get(
                RagdollifiedConfig.DIRECTIONAL_CARRY_SPEED); // blocks/second (jBullet units)
        if (minimumHorizontalSpeed <= 0.0) return velocity;
        if (velocity.horizontalDistanceSqr() >= minimumHorizontalSpeed * minimumHorizontalSpeed) {
            return velocity;
        }

        Entity attacker = damageSource.getEntity();
        if (!(attacker instanceof LivingEntity livingAttacker) || attacker == victim) return velocity;

        Vec3 look = livingAttacker.getLookAngle();
        Vec3 direction = new Vec3(look.x, 0.0, look.z);
        if (direction.lengthSqr() < 1.0e-6) {
            Vec3 awayFromAttacker = victim.position().subtract(livingAttacker.position());
            direction = new Vec3(awayFromAttacker.x, 0.0, awayFromAttacker.z);
        }
        if (direction.lengthSqr() < 1.0e-6) return velocity;
        direction = direction.normalize();

        // Replace only the negligible horizontal component so the resulting carry reliably
        // follows the attacker instead of retaining random AI/pathfinding drift. Preserve real
        // vertical motion and add a slight lift so low bodies are not pinned into the floor.
        return new Vec3(direction.x * minimumHorizontalSpeed,
                velocity.y + minimumHorizontalSpeed * 0.144,
                direction.z * minimumHorizontalSpeed);
    }

    private static long key(LivingEntity entity) {
        return ((entity.getId() & 0xffffffffL) << 1) | (entity.level().isClientSide ? 1L : 0L);
    }

    // Vanilla adds explosion knockback after LivingEntity.hurt() returns, but the death event
    // fires inside that call, so carry it separately and add it to the captured locomotion
    // rather than replacing it.
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
