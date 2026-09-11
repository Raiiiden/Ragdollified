package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.api.LimbHit;
import com.raiiiden.ragdollified.api.RagdollAmputationApi;
import com.raiiiden.ragdollified.api.RagdollHit;
import com.raiiiden.ragdollified.api.RagdollifiedApi;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollImpulsePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// Lets projectiles hit ragdolls, which aren't entities: each tick every projectile's path is swept
// against the ragdoll snapshots, and a hit becomes an ordinary server-ordered impulse.
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RagdollProjectileImpulseHandler {

    private RagdollProjectileImpulseHandler() {}

    // A projectile passes through a corpse once. Keyed on both ids because one arrow can pass
    // through a pile, and the pair is what "already hit this one" actually means.
    private static final Set<Long> ALREADY_HIT = new HashSet<>();
    // The same idea for loose limbs, in its own set: limb ids and ragdoll entity ids come from
    // separate counters, so one shared set would have them shadowing each other.
    private static final Set<Long> LIMBS_ALREADY_HIT = new HashSet<>();
    private static int sweepTick;

    // Below this a projectile is falling, not flying, and a dropped arrow rolling off a corpse
    // should not shove it. Blocks per tick, squared.
    private static final double MIN_SPEED_SQ = 0.15 * 0.15;
    // Impulse per unit of momentum. An arrow at full draw carries about 3 blocks per tick; this
    // puts it in the same range as a sword blow, which is roughly right for what one should do.
    private static final double IMPULSE_PER_SPEED = 3.0;
    private static final double MAX_IMPULSE = 24.0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clearHits();
            return;
        }
        // Nothing to hit, so nothing to sweep. This is the common case and it costs two map reads.
        // Limbs count: a body can rot away and leave an arm behind that is still worth shooting.
        if (ClientRagdollManager.getActiveCount() == 0 && ClientDetachedLimbManager.getCount() == 0) {
            clearHits();
            return;
        }

        // The set only grows while projectiles are in flight; a periodic clear is cheaper and
        // simpler than tracking each projectile's death, and a re-hit after ten seconds is fine.
        if (++sweepTick >= 200) {
            sweepTick = 0;
            ALREADY_HIT.clear();
            LIMBS_ALREADY_HIT.clear();
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile)) continue;
            sweep(projectile);
        }
    }

    private static void sweep(Projectile projectile) {
        // An arrow that has already stuck in something is a decoration, not a projectile, and it
        // has stopped moving, so the speed gate below is what actually rules it out.
        Vec3 travel = projectile.getDeltaMovement();
        if (travel.lengthSqr() < MIN_SPEED_SQ) return;

        // The segment actually covered this tick: the entity has already been moved by the time the
        // tick ends, so its previous position is where the segment starts.
        Vec3 end = projectile.position();
        Vec3 start = new Vec3(projectile.xOld, projectile.yOld, projectile.zOld);
        if (start.distanceToSqr(end) < 1.0e-6) return;

        Optional<RagdollHit> found = RagdollifiedApi.raycast(start, end,
                id -> !ALREADY_HIT.contains(key(projectile.getId(), id)));
        // Loose limbs are shot like anything else. Their own hit set, not the ragdoll one: a limb id
        // and a ragdoll entity id are separate counters and would collide as keys.
        Optional<LimbHit> limbFound = RagdollAmputationApi.raycastLimbs(start, end)
                .filter(h -> !LIMBS_ALREADY_HIT.contains(key(projectile.getId(), h.limbId())));
        if (found.isEmpty() && limbFound.isEmpty()) return;

        boolean limbIsNearer = found.isEmpty()
                || (limbFound.isPresent() && limbFound.get().distance() < found.get().distance());
        RagdollPart part = limbIsNearer ? limbFound.get().part() : found.get().part();
        if (part == null) return;

        double speed = travel.length();
        double strength = Math.min(MAX_IMPULSE, speed * IMPULSE_PER_SPEED);
        if (part == RagdollPart.HEAD) strength *= 1.25;
        Vec3 direction = travel.normalize();
        // A bullet drives a body along its own line with barely any lift; unlike a swing, there is
        // no arm behind it lifting the corpse off the ground.
        Vec3 impulse = direction.scale(strength).add(0.0, strength * 0.08, 0.0);

        if (limbIsNearer) {
            // Local only, for the same reason the melee path is: nothing about a loose limb is
            // synced, so there is nothing for a broadcast to agree about.
            LIMBS_ALREADY_HIT.add(key(projectile.getId(), limbFound.get().limbId()));
            RagdollAmputationApi.pushLimb(limbFound.get().limbId(), impulse);
            return;
        }

        RagdollHit hit = found.get();
        ALREADY_HIT.add(key(projectile.getId(), hit.entityId()));

        if (!RagdollifiedConfig.hasServerSnapshot()) {
            ClientRagdollManager.enqueueImpulse(hit.entityId(), part.index,
                    (float) impulse.x, (float) impulse.y, (float) impulse.z, 0, true,
                    hit.position().x, hit.position().y, hit.position().z);
        }
        try {
            ModNetwork.CHANNEL.sendToServer(new RagdollImpulsePacket(
                    hit.entityId(), part.index,
                    (float) impulse.x, (float) impulse.y, (float) impulse.z,
                    hit.position(), (float) strength));
        } catch (Exception ignored) {
            // Vanilla server, or one without the mod. The local push above still happened.
        }
    }

    private static void clearHits() {
        if (!ALREADY_HIT.isEmpty()) ALREADY_HIT.clear();
        if (!LIMBS_ALREADY_HIT.isEmpty()) LIMBS_ALREADY_HIT.clear();
    }

    private static long key(int projectileId, int ragdollId) {
        return ((long) projectileId << 32) | (ragdollId & 0xFFFFFFFFL);
    }
}
