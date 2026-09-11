package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.RagdollHitMapper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RagdollHitTracker {

    private RagdollHitTracker() {}

    public static final class HitInfo {
        public final Vec3 hitPos;
        public final Vec3 direction;
        public final boolean isHeadShot;
        public final boolean isTaczBullet;
        public final boolean isMelee;
        public final float damage;
        public final long captureTimeMs;

        public HitInfo(Vec3 hitPos, Vec3 direction, boolean isHeadShot, boolean isTaczBullet, float damage) {
            this(hitPos, direction, isHeadShot, isTaczBullet, false, damage);
        }

        public HitInfo(Vec3 hitPos, Vec3 direction, boolean isHeadShot, boolean isTaczBullet,
                       boolean isMelee, float damage) {
            this.hitPos = hitPos;
            this.direction = direction;
            this.isHeadShot = isHeadShot;
            this.isTaczBullet = isTaczBullet;
            this.isMelee = isMelee;
            this.damage = damage;
            this.captureTimeMs = System.currentTimeMillis();
        }
    }

    private static final Map<Integer, HitInfo> HIT_INFO = new ConcurrentHashMap<>();
    private static final long ENTRY_TTL_MS = 10_000L;
    private static int cleanupTick = 0;

    public static void registerOptionalTaczHandler(IEventBus forgeBus) {
        if (!ModList.get().isLoaded("tacz")) return;
        try {
            registerTaczPost(forgeBus,
                    Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Post")
                            .asSubclass(Event.class));
            Ragdollified.LOGGER.debug("Registered optional TACZ client hit tracker");
        } catch (ClassNotFoundException e) {
            Ragdollified.LOGGER.warn("TACZ is loaded, but its client gun hurt event class was not found", e);
        }
    }

    private static <T extends Event> void registerTaczPost(IEventBus forgeBus, Class<T> eventClass) {
        forgeBus.addListener(EventPriority.NORMAL, false, eventClass, RagdollHitTracker::onTaczHurt);
    }

    private static void onTaczHurt(Event event) {
        Entity bullet = getEntity(event, "getBullet");
        Entity hurt = getEntity(event, "getHurtEntity");
        if (bullet == null || hurt == null) return;
        if (!(hurt instanceof LivingEntity living)) return;
        if (!MobModelHelper.shouldHaveRagdoll(living)) return;

        // position() during the hit handling is the start of the segment TACZ swept; see
        // RagdollHitMapper.projectileSegmentStart.
        Vec3 vel = bullet.getDeltaMovement();
        Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;
        Vec3 hitPos = RagdollHitMapper.projectileSegmentStart(bullet, dir);

        HIT_INFO.put(hurt.getId(), new HitInfo(
                hitPos, dir, getBoolean(event, "isHeadShot"), true, getFloat(event, "getAmount")));
        maybeCleanup();
    }

    public static HitInfo resolveAtDeath(int entityId, DamageSource source, LivingEntity victim) {
        maybeCleanup();
        HitInfo tracked = HIT_INFO.get(entityId);
        if (tracked != null) return tracked;
        if (source == null) return null;

        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) {
            Vec3 vel = direct.getDeltaMovement();
            Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;
            Vec3 hitPos = RagdollHitMapper.projectileSegmentStart(direct, dir);
            return new HitInfo(hitPos, dir, false, false, 0f);
        }

        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity attackerLiving) {
            Vec3 dir = attackerLiving.getLookAngle();
            if (dir.lengthSqr() < 1.0e-6) return null;
            dir = dir.normalize();
            if (victim != null) {
                return new HitInfo(traceAttackerLookToEntity(attackerLiving, victim, dir),
                        dir, false, false, true, 0f);
            }
            Vec3 eye = attackerLiving.getEyePosition();
            return new HitInfo(eye.add(dir.scale(3.0)), dir, false, false, true, 0f);
        }
        return null;
    }

    private static Vec3 traceAttackerLookToEntity(LivingEntity attacker, LivingEntity target, Vec3 dir) {
        Vec3 eye = attacker.getEyePosition();
        double distanceToTarget = eye.distanceTo(target.position().add(0.0, target.getBbHeight() * 0.5, 0.0));
        double reach = Math.max(4.5, distanceToTarget + target.getBbWidth() + 1.0);
        Vec3 end = eye.add(dir.scale(reach));
        Optional<Vec3> clipped = target.getBoundingBox().inflate(0.05).clip(eye, end);
        return clipped.orElseGet(() -> closestPointOnSegmentToTarget(eye, end, target));
    }

    private static Vec3 closestPointOnSegmentToTarget(Vec3 start, Vec3 end, LivingEntity target) {
        Vec3 center = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.lengthSqr();
        if (lenSqr < 1.0e-6) return center;
        double t = center.subtract(start).dot(segment) / lenSqr;
        t = Math.max(0.0, Math.min(1.0, t));
        return start.add(segment.scale(t));
    }

    public static void clear() {
        HIT_INFO.clear();
    }

    private static void maybeCleanup() {
        cleanupTick++;
        if (cleanupTick < 64) return;
        cleanupTick = 0;
        long now = System.currentTimeMillis();
        HIT_INFO.entrySet().removeIf(e -> now - e.getValue().captureTimeMs > ENTRY_TTL_MS);
    }

    public static ResolvedHit resolveAndPlan(LivingEntity entity, DamageSource source) {
        if (entity == null) return null;
        HitInfo hit = resolveAtDeath(entity.getId(), source, entity);
        if (hit == null) return null;

        Vec3 impulse = RagdollHitMapper.computeImpulse(
                hit.direction, hit.isHeadShot, hit.isTaczBullet, hit.isMelee, hit.damage);
        if (impulse == null) return null;

        RagdollHitMapper.Resolution resolution =
                RagdollHitMapper.resolve(entity, hit.hitPos, hit.direction, hit.isHeadShot);
        boolean centered = RagdollHitMapper.isCenteredHit(
                entity, resolution.impact, hit.isHeadShot, resolution.part);
        return new ResolvedHit(resolution.part, impulse, centered, resolution.impact);
    }

    public static final class ResolvedHit {
        public final RagdollPart part;
        public final Vec3 impulse;
        public final boolean centered;
        // World-space impact point or null; the pivot for the impulse. Only used without a server hit.
        public final Vec3 impact;

        public ResolvedHit(RagdollPart part, Vec3 impulse, boolean centered, Vec3 impact) {
            this.part = part;
            this.impulse = impulse;
            this.centered = centered;
            this.impact = impact;
        }
    }

    private static Entity getEntity(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof Entity entity ? entity : null;
    }

    private static boolean getBoolean(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof Boolean bool && bool;
    }

    private static float getFloat(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof Number number ? number.floatValue() : 0f;
    }

    private static Object invoke(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException e) {
            Ragdollified.LOGGER.debug("Could not read TACZ event method {}", methodName, e);
            return null;
        }
    }
}
