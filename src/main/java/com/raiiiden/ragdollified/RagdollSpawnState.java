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

    // Minecraft stores movement per tick and jBullet wants per second. The pre-hit sample is preferred
    // so knockback is not read as walking speed; damage recoil is applied separately.
    public static Vec3 captureLinearVelocity(LivingEntity entity) {
        VelocitySample sample = PRE_HIT_VELOCITIES.remove(key(entity));
        Vec3 movement = sample != null ? sample.velocity : entity.getDeltaMovement();

        // LivingHurtEvent never fires client-side and remote mobs' delta movement is usually empty,
        // so distance covered last tick is the reliable speed measure.
        if (sample == null) {
            Vec3 travelled = new Vec3(
                    entity.getX() - entity.xOld,
                    entity.getY() - entity.yOld,
                    entity.getZ() - entity.zOld);
            if (travelled.lengthSqr() > movement.lengthSqr()) movement = travelled;
        }

        return movement.scale(TICKS_PER_SECOND);
    }

    // A stationary victim has no inherited motion, so a limb impulse alone folds the body in place.
    // Low-motion deaths get a small horizontal carry along the attacker's facing, below running speed.
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

        // Replace only the negligible horizontal component, so the carry follows the attacker instead of
        // keeping AI drift. Real vertical motion is preserved, plus a lift so bodies are not floor-pinned.
        return new Vec3(direction.x * minimumHorizontalSpeed,
                velocity.y + minimumHorizontalSpeed * 0.144,
                direction.z * minimumHorizontalSpeed);
    }

    private static long key(LivingEntity entity) {
        return ((entity.getId() & 0xffffffffL) << 1) | (entity.level().isClientSide ? 1L : 0L);
    }

    // Throw distance goes with the square of launch speed, so the old steep falloff (times a max-health
    // multiplier that doubled it for players) sent point-blank kills off at 80 b/s while bodies a few
    // blocks out barely moved. Speed now eases to EXPLOSION_MIN_FALLOFF over vanilla TNT's damage reach
    // and ignores health.
    private static final double EXPLOSION_FALLOFF_DISTANCE = 8.0;
    private static final double EXPLOSION_MIN_FALLOFF = 0.6;
    // A foot-level blast aims nearly flat, which scraped bodies along the floor instead of lifting them.
    private static final double EXPLOSION_MIN_ELEVATION = Math.toRadians(30.0);
    private static final double EXPLOSION_MAX_ELEVATION = Math.toRadians(70.0);

    // Vanilla adds explosion knockback after hurt() returns while the death event fires inside it, so
    // it is carried separately and added to the captured locomotion rather than replacing it.
    @Nullable
    public static Vec3 captureExplosionVelocityKick(LivingEntity entity,
                                                     @Nullable DamageSource damageSource) {
        if (damageSource == null || !damageSource.is(DamageTypeTags.IS_EXPLOSION)) return null;

        Vec3 explosionCenter = damageSource.getSourcePosition();
        if (explosionCenter == null) return null;

        // Aimed at mid-body rather than the feet, as vanilla aims its push at the eyes.
        double dx = entity.getX() - explosionCenter.x;
        double dy = entity.getY() + entity.getBbHeight() * 0.5 - explosionCenter.y;
        double dz = entity.getZ() - explosionCenter.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(horizontal * horizontal + dy * dy);

        double headingX;
        double headingZ;
        if (horizontal > 1.0e-3) {
            headingX = dx / horizontal;
            headingZ = dz / horizontal;
        } else {
            // A blast straight under or inside the body has no outward heading: throw it backwards.
            double yaw = Math.toRadians(entity.getYRot());
            headingX = Math.sin(yaw);
            headingZ = -Math.cos(yaw);
        }
        double elevation = Math.max(EXPLOSION_MIN_ELEVATION,
                Math.min(EXPLOSION_MAX_ELEVATION, Math.atan2(dy, horizontal)));

        double reach = Math.min(distance / EXPLOSION_FALLOFF_DISTANCE, 1.0);
        double speed = RagdollifiedConfig.get(RagdollifiedConfig.HIT_IMPULSE_EXPLOSION)
                * (1.0 - (1.0 - EXPLOSION_MIN_FALLOFF) * reach);
        double horizontalSpeed = Math.cos(elevation) * speed;
        return new Vec3(headingX * horizontalSpeed, Math.sin(elevation) * speed, headingZ * horizontalSpeed);
    }
}
