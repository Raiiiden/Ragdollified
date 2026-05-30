package com.raiiiden.ragdollified;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.LogicalSide;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side counterpart to {@code RagdollHitTracker}. The reason this exists in
 * addition to the client tracker:
 *
 * <p>{@code PhysicsHooks.onLivingDeath} runs <i>inside</i> {@code LivingEntity.die()},
 * which runs inside {@code LivingEntity.hurt()}. TACZ posts {@code EntityHurtByGunEvent.Post}
 * (and its mirror {@code ServerMessageGunHurt} packet) only <b>after</b> hurt() returns.
 * That means for a fatal shot:</p>
 *
 * <ol>
 *   <li>tacAttackEntity → hurt() → die() → LivingDeathEvent → PhysicsHooks queues RagdollSpawnPacket</li>
 *   <li>hurt() returns → EntityHurtByGunEvent.Post fires → ServerMessageGunHurt queued</li>
 * </ol>
 *
 * <p>Both packets ship same tick, but RagdollSpawnPacket is queued first, so it arrives
 * first on the client — which means the client tracker is empty when handleClient runs.
 * Result: kills look right intermittently (only when the target had already taken a hit
 * one tick earlier). To fix that race, we capture on the <b>Pre</b> event server-side
 * (which fires <i>before</i> hurt()), and {@link PhysicsHooks} reads from us to bake the
 * hit info directly into the spawn packet — no race window.</p>
 *
 * <p>Also subscribes to {@code LivingHurtEvent} so vanilla projectiles (arrows / tridents /
 * snowballs) get the same treatment when no TACZ event fires.</p>
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerRagdollHitTracker {

    private ServerRagdollHitTracker() {}

    public static final class HitInfo {
        public final Vec3 hitPos;
        public final Vec3 direction;
        public final boolean isHeadShot;
        public final boolean isTaczBullet;
        public final boolean isMelee;
        // Final damage amount at hit time (post-armor for vanilla LivingHurtEvent, raw
        // gun amount for TACZ Pre). Used to scale the impulse magnitude so pellet weapons
        // barely twitch the corpse and high-damage rounds whip it. 0 = damage unknown.
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

    /**
     * Captures a TACZ hit on the server side, BEFORE hurt() runs. Listening on Pre is
     * critical for fatal kills — see class javadoc for the race we're avoiding. We use
     * EventPriority.HIGHEST so we run before any handler that might cancel the event,
     * but it doesn't really matter (we don't mutate the event).
     */
    public static void registerOptionalTaczHandler(IEventBus forgeBus) {
        if (!ModList.get().isLoaded("tacz")) return;
        try {
            registerTaczPre(forgeBus,
                    Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre")
                            .asSubclass(Event.class));
            Ragdollified.LOGGER.debug("Registered optional TACZ server hit tracker");
        } catch (ClassNotFoundException e) {
            Ragdollified.LOGGER.warn("TACZ is loaded, but its server gun hurt event class was not found", e);
        }
    }

    private static <T extends Event> void registerTaczPre(IEventBus forgeBus, Class<T> eventClass) {
        forgeBus.addListener(EventPriority.HIGHEST, false, eventClass, ServerRagdollHitTracker::onTaczHurtPre);
    }

    private static void onTaczHurtPre(Event event) {
        if (getLogicalSide(event) != LogicalSide.SERVER) return;
        Entity bullet = getEntity(event, "getBullet");
        Entity hurt = getEntity(event, "getHurtEntity");
        if (bullet == null || hurt == null) return;
        if (!(hurt instanceof LivingEntity living)) return;
        if (!MobModelHelper.shouldHaveRagdoll(living)) return;

        // xOld/yOld/zOld is the bullet's position one tick before the hit — usually the
        // closest sample we have to the actual contact point (current position has
        // already integrated past the entity).
        Vec3 vel = bullet.getDeltaMovement();
        Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;
        Vec3 hitPos = projectileRayStart(bullet, dir);

        HIT_INFO.put(hurt.getId(), new HitInfo(
                hitPos, dir, getBoolean(event, "isHeadShot"), true, getFloat(event, "getAmount")));
        maybeCleanup();
    }

    /**
     * Vanilla projectile fallback (arrows, tridents, snowballs). Fires before TACZ's Pre
     * for non-TACZ damage; the TACZ handler above never fires for vanilla projectiles, so
     * there's no double-write conflict. Skip non-projectile damage to avoid creating
     * entries for melee, fire, fall, etc.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) return;
        LivingEntity living = event.getEntity();
        if (!MobModelHelper.shouldHaveRagdoll(living)) return;
        if (event.getSource() == null) return;

        Entity direct = event.getSource().getDirectEntity();
        if (direct instanceof Projectile) {
            Vec3 vel = direct.getDeltaMovement();
            Vec3 dir = vel.lengthSqr() > 1.0e-6 ? vel.normalize() : Vec3.ZERO;
            Vec3 hitPos = projectileRayStart(direct, dir);

            // Don't clobber a TACZ entry that may have arrived first this tick.
            HIT_INFO.putIfAbsent(living.getId(),
                    new HitInfo(hitPos, dir, false, false, event.getAmount()));
        } else {
            Entity attacker = event.getSource().getEntity();
            if (!(attacker instanceof LivingEntity attackerLiving)) return;
            Vec3 dir = attackerLiving.getLookAngle();
            if (dir.lengthSqr() < 1.0e-6) {
                dir = living.position().subtract(attackerLiving.position());
            }
            if (dir.lengthSqr() < 1.0e-6) return;
            dir = dir.normalize();
            Vec3 hitPos = traceAttackerLookToEntity(attackerLiving, living, dir);
            HIT_INFO.put(living.getId(), new HitInfo(hitPos, dir, false, false, true, event.getAmount()));
        }
        maybeCleanup();
    }

    private static Vec3 traceAttackerLookToEntity(LivingEntity attacker, LivingEntity target, Vec3 dir) {
        Vec3 eye = attacker.getEyePosition();
        double distanceToTarget = eye.distanceTo(target.position().add(0.0, target.getBbHeight() * 0.5, 0.0));
        double reach = Math.max(4.5, distanceToTarget + target.getBbWidth() + 1.0);
        Vec3 end = eye.add(dir.scale(reach));
        Optional<Vec3> clipped = target.getBoundingBox().inflate(0.05).clip(eye, end);
        return clipped.orElseGet(() -> closestPointOnSegmentToTarget(eye, end, target));
    }

    private static Vec3 projectileRayStart(Entity projectile, Vec3 dir) {
        Vec3 previous = new Vec3(projectile.xOld, projectile.yOld, projectile.zOld);
        if (dir.lengthSqr() < 1.0e-6) return previous;
        return previous.subtract(dir.scale(0.75));
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

    /**
     * Read + remove the captured hit info for the given entity. Called from
     * {@code PhysicsHooks.onLivingDeath}. Returns null if no hit has been captured for
     * this entity (e.g., melee kill, fire damage, fall — no projectile in the chain).
     */
    public static HitInfo consume(int entityId) {
        return HIT_INFO.remove(entityId);
    }

    /** Clear all entries (server stop). */
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

    private static Entity getEntity(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof Entity entity ? entity : null;
    }

    private static LogicalSide getLogicalSide(Object target) {
        Object value = invoke(target, "getLogicalSide");
        return value instanceof LogicalSide side ? side : null;
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
