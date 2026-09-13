package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.RagdollHitMapper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.ServerRagdollHitTracker;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.mixin.ExplosionAccessor;
import com.raiiiden.ragdollified.network.RagdollImpulsePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Pushes the server starts on bodies that already exist: knockback on a limp living player moves the body standing in for them, and a blast throws every body in its reach.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public final class RagdollWorldPushes {
    private RagdollWorldPushes() {}

    // Vanilla's knockback for an ordinary blow, the strength the melee impulse setting is sized for.
    private static final double STANDARD_KNOCKBACK = 0.4;
    private static final double MAX_KNOCKBACK_SCALE = 4.0;
    // A captured hit older than this was an earlier blow, not the one being knocked back now.
    private static final long HIT_MAX_AGE_MS = 250L;
    // How far the blow's line may pass from a part's centre and still count as striking it.
    private static final double MAX_STRIKE_MISS = 1.0;
    // Keeps the lever arm inside the struck part, as the click handler does.
    private static final double MAX_LEVER = 0.35;
    // The death-time blast kick's elevation window, so a floor-level blast lofts a body instead of scraping it along.
    private static final double MIN_BLAST_ELEVATION = Math.toRadians(30.0);
    private static final double MAX_BLAST_ELEVATION = Math.toRadians(70.0);
    // Blocks per second below which a blast at the edge of its reach is not worth waking a body for.
    private static final double MIN_BLAST_SPEED = 0.5;

    private record Strike(RagdollPart part, Vec3 impact) {}

    private record PendingKick(ServerLevel level, UUID owner, Vec3 anchor, Vec3 velocity) {}

    // A chain of blasts in one tick sums into one kick per body, sent once the tick is over.
    private static final Map<Integer, PendingKick> PENDING_KICKS = new LinkedHashMap<>();

    // Lowest, so the strength is final once every other mod has had its say, and a cancelled knockback pushes nothing.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isAlive()) return;
        if (!RagdollifiedConfig.get(RagdollifiedConfig.KNOCKBACK_PUSHES_LIVE_RAGDOLLS)) return;
        ServerRagdollSyncManager.RetainedBody body =
                ServerRagdollSyncManager.retainedBody(player.getId(), player.level().dimension());
        if (body == null || !body.live() || !player.getUUID().equals(body.victim())) return;

        double strength = event.getStrength() * (1.0 - player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        Vec3 away = new Vec3(-event.getRatioX(), 0.0, -event.getRatioZ());
        if (!(strength > 0.0) || away.lengthSqr() < 1.0e-8) return;
        away = away.normalize();

        ServerRagdollHitTracker.HitInfo hit = ServerRagdollHitTracker.peek(player.getId());
        if (hit != null && System.currentTimeMillis() - hit.captureTimeMs > HIT_MAX_AGE_MS) hit = null;
        boolean melee = hit == null || hit.isMelee;
        Vec3 line = hit != null && hit.direction.lengthSqr() > 1.0e-6 ? hit.direction.normalize() : away;
        // A blow drives the body level and away from the attacker, as vanilla drives the player; a projectile carries it along its own line.
        Vec3 direction = melee ? away : line;
        float damage = hit != null ? hit.damage : 0f;
        Vec3 impulse = RagdollHitMapper.computeImpulse(direction, false, false, melee, damage);
        if (impulse == null) return;
        impulse = impulse.scale(Math.min(MAX_KNOCKBACK_SCALE, strength / STANDARD_KNOCKBACK));

        Strike strike = hit != null && body.pose() != null ? strike(body.pose(), hit.hitPos, line) : null;
        RagdollPart part = strike != null ? strike.part() : RagdollPart.TORSO;
        Vec3 impact = strike != null ? strike.impact() : body.anchor();
        RagdollImpulsePacket.broadcastOrdered(player.serverLevel(), player, player.getId(), part.index,
                impulse, impact, damage);
    }

    // Detonate fires before anything is hurt, so the bodies of this blast's own victims are not retained yet and are not thrown twice.
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!RagdollifiedConfig.get(RagdollifiedConfig.EXPLOSIONS_PUSH_RAGDOLLS)) return;
        if (!(event.getExplosion() instanceof ExplosionAccessor blast)) return;
        double reach = blast.ragdollified$getRadius() * 2.0;
        double launch = RagdollifiedConfig.get(RagdollifiedConfig.HIT_IMPULSE_EXPLOSION);
        if (!(reach > 0.0) || !(launch > 0.0)) return;
        Vec3 centre = new Vec3(blast.ragdollified$getX(), blast.ragdollified$getY(), blast.ragdollified$getZ());
        double simulated = RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE);
        for (ServerRagdollSyncManager.RetainedBody body
                : ServerRagdollSyncManager.retainedBodiesNear(level.dimension(), centre, reach)) {
            // Only the client simulating a body can apply the kick; with none near enough, sending it would only throw away the retained pose.
            ServerPlayer owner = body.streamOwner() == null ? null
                    : level.getServer().getPlayerList().getPlayer(body.streamOwner());
            if (owner == null || owner.serverLevel() != level
                    || owner.position().distanceToSqr(body.anchor()) > simulated * simulated) continue;
            double speed = launch * (1.0 - body.anchor().distanceTo(centre) / reach) * exposure(level, centre, body);
            if (speed < MIN_BLAST_SPEED) continue;
            PendingKick kick = new PendingKick(level, owner.getUUID(), body.anchor(), kick(centre, body.anchor(), speed));
            PENDING_KICKS.merge(body.entityId(), kick, (earlier, later) -> new PendingKick(
                    later.level(), later.owner(), later.anchor(), earlier.velocity().add(later.velocity())));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_KICKS.isEmpty()) return;
        // However many blasts land at once, a body is thrown no harder than by one going off beside it.
        double launch = RagdollifiedConfig.get(RagdollifiedConfig.HIT_IMPULSE_EXPLOSION);
        for (Map.Entry<Integer, PendingKick> entry : PENDING_KICKS.entrySet()) {
            PendingKick kick = entry.getValue();
            ServerPlayer owner = kick.level().getServer().getPlayerList().getPlayer(kick.owner());
            if (owner == null) continue;
            Vec3 velocity = kick.velocity();
            double speed = velocity.length();
            if (speed > launch && speed > 0.0) velocity = velocity.scale(launch / speed);
            RagdollImpulsePacket.broadcastOrdered(kick.level(), owner, entry.getKey(),
                    RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX, velocity, kick.anchor(), 0f);
        }
        PENDING_KICKS.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_KICKS.clear();
    }

    // Share of the body the blast can see, vanilla's way of sheltering whatever is behind cover.
    private static double exposure(ServerLevel level, Vec3 centre, ServerRagdollSyncManager.RetainedBody body) {
        List<Vec3> points = new ArrayList<>();
        RagdollTransform[] pose = body.pose();
        if (pose != null) {
            for (RagdollTransform transform : pose) {
                if (transform != null) {
                    points.add(new Vec3(transform.position.x, transform.position.y, transform.position.z));
                }
            }
        }
        if (points.isEmpty()) points.add(body.anchor());
        int seen = 0;
        for (Vec3 point : points) {
            ClipContext ray = new ClipContext(point, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null);
            if (level.clip(ray).getType() == HitResult.Type.MISS) seen++;
        }
        return seen / (double) points.size();
    }

    // Away from the blast and up into the death-time kick's elevation window.
    private static Vec3 kick(Vec3 centre, Vec3 target, double speed) {
        double dx = target.x - centre.x;
        double dz = target.z - centre.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-4) return new Vec3(0.0, speed, 0.0);
        double elevation = Mth.clamp(Math.atan2(target.y - centre.y, horizontal),
                MIN_BLAST_ELEVATION, MAX_BLAST_ELEVATION);
        double flat = Math.cos(elevation) * speed / horizontal;
        return new Vec3(dx * flat, Math.sin(elevation) * speed, dz * flat);
    }

    // The part the blow's line passes closest to, and the point on that line nearest its centre; null when the line misses the body.
    @Nullable
    private static Strike strike(RagdollTransform[] pose, @Nullable Vec3 through, Vec3 line) {
        if (through == null || line.lengthSqr() < 1.0e-6) return null;
        Vec3 unit = line.normalize();
        Vec3 start = through.subtract(unit.scale(2.0));
        Strike best = null;
        double bestMiss = MAX_STRIKE_MISS * MAX_STRIKE_MISS;
        for (RagdollPart part : RagdollPart.values()) {
            if (part.index >= pose.length || pose[part.index] == null) continue;
            Vector3f position = pose[part.index].position;
            Vec3 centre = new Vec3(position.x, position.y, position.z);
            Vec3 closest = start.add(unit.scale(Mth.clamp(centre.subtract(start).dot(unit), 0.0, 4.0)));
            double miss = closest.distanceToSqr(centre);
            if (miss >= bestMiss) continue;
            bestMiss = miss;
            Vec3 lever = closest.subtract(centre);
            double length = lever.length();
            best = new Strike(part, length > MAX_LEVER ? centre.add(lever.scale(MAX_LEVER / length)) : closest);
        }
        return best;
    }
}
