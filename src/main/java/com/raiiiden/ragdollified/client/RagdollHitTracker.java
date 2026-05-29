package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollHitMapper;
import com.raiiiden.ragdollified.RagdollPart;
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
import java.util.concurrent.ConcurrentHashMap;

public final class RagdollHitTracker {

    private RagdollHitTracker() {}

    public static final class HitInfo {
        public final Vec3 hitPos;        // bullet's world-position at hit (close to the actual contact point)
        public final Vec3 direction;     // unit direction of bullet travel (or {0,0,0} if we couldn't read it)
        public final boolean isHeadShot;
        public final boolean isTaczBullet;
        // Final damage at hit time (TACZ Post amount). 0 if unknown — client fallback for
        // vanilla projectiles can't see damage easily without a hurt event.
        public final float damage;
        public final long captureTimeMs;

        public HitInfo(Vec3 hitPos, Vec3 direction, boolean isHeadShot, boolean isTaczBullet, float damage) {
            this.hitPos = hitPos;
            this.direction = direction;
            this.isHeadShot = isHeadShot;
            this.isTaczBullet = isTaczBullet;
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
        if (living instanceof net.minecraft.world.entity.player.Player) {
            // players are handled too — leave path open
        } else if (!MobModelHelper.shouldHaveRagdoll(living)) {
            return; // entity won't ragdoll, no point tracking
        }

        // xOld/yOld/zOld is the bullet's position one tick before the hit; usually closer
        // to the actual contact than the post-step current position, which has overshot.
        Vec3 hitPos = new Vec3(bullet.xOld, bullet.yOld, bullet.zOld);
        Vec3 vel = bullet.getDeltaMovement();
        Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;

        HIT_INFO.put(hurt.getId(), new HitInfo(
                hitPos, dir, getBoolean(event, "isHeadShot"), true, getFloat(event, "getAmount")));
        maybeCleanup();
    }

    public static HitInfo resolveAtDeath(int entityId, DamageSource source) {
        maybeCleanup();
        HitInfo tracked = HIT_INFO.get(entityId);
        if (tracked != null) return tracked;
        if (source == null) return null;

        Entity direct = source.getDirectEntity();
        if (!(direct instanceof Projectile)) return null;
        Vec3 hitPos = direct.position();
        Vec3 vel = direct.getDeltaMovement();
        Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;
        return new HitInfo(hitPos, dir, false, false, 0f);
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
        HitInfo hit = resolveAtDeath(entity.getId(), source);
        if (hit == null) return null;

        Vec3 impulse = RagdollHitMapper.computeImpulse(hit.direction, hit.isHeadShot, hit.isTaczBullet, hit.damage);
        if (impulse == null) return null;
        RagdollPart part = RagdollHitMapper.map(entity, hit.hitPos, hit.direction, hit.isHeadShot);
        return new ResolvedHit(part, impulse);
    }

    public static final class ResolvedHit {
        public final RagdollPart part;
        public final Vec3 impulse;

        public ResolvedHit(RagdollPart part, Vec3 impulse) {
            this.part = part;
            this.impulse = impulse;
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
