package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

// Every loose limb on this client. Requests are queued from any thread and drained by the physics
// worker inside ClientRagdollManager.tickAll, the only place limb bodies are touched.
@OnlyIn(Dist.CLIENT)
public final class ClientDetachedLimbManager {

    private ClientDetachedLimbManager() {}

    private static final Map<Integer, ClientDetachedLimb> limbs = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<ClientDetachedLimb.SpawnData> pendingSpawns =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<int[]> pendingRemovals = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<float[]> pendingImpulses = new ConcurrentLinkedQueue<>();

    // Cosmetic cap, not a safety one: limbs are cheap, but a hundred arms on a mob-farm floor is
    // noise. The oldest go first, matching how the ragdoll cap retires bodies.
    private static volatile int maxLimbs = 64;
    // Bounded so a burst that arrives while the worker is behind cannot grow without limit.
    private static final int MAX_QUEUED_SPAWNS = 256;

    public static void setMaxLimbs(int limit) {
        maxLimbs = Math.max(0, limit);
    }

    public static int getMaxLimbs() { return maxLimbs; }

    // Queue a limb for the physics worker to build on its next tick. Any thread.
    public static boolean enqueueSpawn(ClientDetachedLimb.SpawnData data) {
        if (data == null || data.part == null || data.position == null) return false;
        if (maxLimbs <= 0) return false;
        if (pendingSpawns.size() >= MAX_QUEUED_SPAWNS) return false;
        if (limbs.containsKey(data.limbId)) return false;
        pendingSpawns.offer(data);
        return true;
    }

    // Queue a push against a loose limb. Any thread.
    public static void enqueueImpulse(int limbId, float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
        pendingImpulses.offer(new float[]{limbId, x, y, z});
    }

    // Queue a limb for removal. Any thread.
    public static void enqueueRemove(int limbId) {
        pendingRemovals.offer(new int[]{limbId});
    }

    // Advance every limb; called by the physics worker inside the ragdoll tick, sharing its world and step.
    public static void tick() {
        if (limbs.isEmpty()) return;

        List<ClientDetachedLimb> finished = null;
        for (ClientDetachedLimb limb : limbs.values()) {
            boolean alive;
            try {
                alive = limb.tick();
            } catch (Throwable t) {
                Ragdollified.LOGGER.error("Detached limb {} failed to tick", limb.getLimbId(), t);
                alive = false;
            }
            if (!alive) {
                if (finished == null) finished = new ArrayList<>(4);
                finished.add(limb);
            }
        }
        if (finished == null) return;
        for (ClientDetachedLimb limb : finished) {
            limbs.remove(limb.getLimbId());
            limb.destroy();
        }
    }

    // Build everything queued before the step, so a new limb moves in the same step as its body.
    public static void prepare(ClientPhysicsWorld physicsWorld) {
        int[] removal;
        while ((removal = pendingRemovals.poll()) != null) {
            ClientDetachedLimb limb = limbs.remove(removal[0]);
            if (limb != null) limb.destroy();
        }

        ClientDetachedLimb.SpawnData data;
        while ((data = pendingSpawns.poll()) != null) {
            if (limbs.containsKey(data.limbId)) continue;
            ClientDetachedLimb limb;
            try {
                limb = new ClientDetachedLimb(data, physicsWorld);
            } catch (Throwable t) {
                Ragdollified.LOGGER.error("Could not build detached limb {}", data.limbId, t);
                continue;
            }
            if (limb.isDestroyed()) {
                ClientDetachedLimb.logBuildFailure(data.limbId);
                limb.destroy();
                continue;
            }
            limbs.put(data.limbId, limb);
        }
        enforceLimit();

        float[] impulse;
        while ((impulse = pendingImpulses.poll()) != null) {
            ClientDetachedLimb limb = limbs.get((int) impulse[0]);
            if (limb == null) continue;
            limb.applyImpulse(new Vector3f(impulse[1], impulse[2], impulse[3]));
        }
    }

    private static void enforceLimit() {
        int limit = maxLimbs;
        if (limbs.size() <= limit) return;
        List<ClientDetachedLimb> byAge = new ArrayList<>(limbs.values());
        byAge.sort(Comparator.comparingInt(ClientDetachedLimb::getTicksExisted).reversed());
        int toDrop = limbs.size() - limit;
        for (int i = 0; i < toDrop && i < byAge.size(); i++) {
            ClientDetachedLimb limb = byAge.get(i);
            limbs.remove(limb.getLimbId());
            limb.destroy();
        }
    }

    public static ClientDetachedLimb get(int limbId) { return limbs.get(limbId); }

    public static Collection<ClientDetachedLimb> getAll() { return limbs.values(); }

    public static boolean isEmpty() { return limbs.isEmpty() && pendingSpawns.isEmpty(); }

    public static int getCount() { return limbs.size(); }

    // Limbs still moving; a world holding only parked limbs needs no step.
    public static int getSimulatingCount() {
        int simulating = 0;
        for (ClientDetachedLimb limb : limbs.values()) {
            if (limb.isSimulating()) simulating++;
        }
        return simulating;
    }

    // Notify limbs near a changed block so parked ones fall if their floor is gone. Physics thread.
    public static void onBlockChangedNear(net.minecraft.core.BlockPos pos) {
        if (limbs.isEmpty()) return;
        for (ClientDetachedLimb limb : limbs.values()) {
            try {
                limb.onBlockChangedNear(pos);
            } catch (Throwable t) {
                Ragdollified.LOGGER.error("Detached limb {} failed a block change", limb.getLimbId(), t);
            }
        }
    }

    // Drop everything. Physics thread, on world unload or a physics recovery.
    public static void clear() {
        pendingSpawns.clear();
        pendingImpulses.clear();
        pendingRemovals.clear();
        for (ClientDetachedLimb limb : limbs.values()) {
            try {
                limb.destroy();
            } catch (Throwable ignored) {
                // The world is going away with it either way.
            }
        }
        limbs.clear();
    }
}
