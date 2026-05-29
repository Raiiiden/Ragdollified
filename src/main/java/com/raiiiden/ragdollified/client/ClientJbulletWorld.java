package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.function.Supplier;

@OnlyIn(Dist.CLIENT)
public class ClientJbulletWorld {

    private static volatile ClientJbulletWorld instance;
    private static volatile ClientLevel currentLevel;

    /**
     * Synchronized to prevent two threads racing to create / dispose the singleton.
     * Called on the physics worker (per-tick) and during world unload from main.
     */
    public static synchronized ClientJbulletWorld get(ClientLevel level) {
        if (instance == null || currentLevel != level) {
            if (instance != null) instance.destroy();
            instance = new ClientJbulletWorld(level);
            currentLevel = level;
        }
        return instance;
    }

    public static synchronized void onWorldUnload() {
        if (instance != null) {
            instance.destroy();
            instance = null;
            currentLevel = null;
        }
    }

    // Cap new cache-entry creation to 3 per tick. Each miss scans up to 7³=343 blocks and
    // assembles a CompoundShape — doing many at once causes a visible frame hitch on spawn.
    // Ragdolls that hit the cap keep their old geometry and retry the next tick; they may
    // fall without floor collision for 1–2 ticks (< 0.05 blocks of drop), imperceptible.
    private static final int MAX_NEW_CACHE_ENTRIES_PER_TICK = 3;
    private int newCacheEntriesThisTick = 0;

    /** Per-tick stats. Reset each step()/maintainCache(), read by the manager. */
    public static final class CacheStats {
        public int hits;            // cache hit, no work done
        public int misses;          // cache miss, supplier called → new bodies created
        public int rateLimited;     // miss but creation budget exhausted, returned null
        public int staticBodiesCreatedThisTick; // sum of bodies created this tick
        public int liveCacheEntries;            // current size of the cache map
        public int liveStaticBodies;            // total static bodies across all cache entries

        public void resetCounters() {
            hits = 0;
            misses = 0;
            rateLimited = 0;
            staticBodiesCreatedThisTick = 0;
        }
    }
    public final CacheStats cacheStats = new CacheStats();

    private final ClientLevel level;
    private BroadphaseInterface broadphase;
    private CollisionConfiguration collisionConfig;
    private CollisionDispatcher dispatcher;
    private ConstraintSolver solver;
    private DiscreteDynamicsWorld dynamicsWorld;

    // Exactly mirrors JbulletWorld's cache — time-based expiry, refcounted.
    // 1-second TTL: long enough to absorb ragdolls oscillating between adjacent block
    // centers (which would otherwise thrash the cache), short enough that during heavy
    // churn (mass-spawn / pile collapse) we don't accumulate a huge backlog of stale
    // static-body geometry. Was 40 (2s) but in spike scenarios let cache grow to 91
    // entries / 5483 bodies; halving it caps the broadphase footprint.
    private final Map<BlockPos, CachedCollisionData> collisionCache = new HashMap<>();
    private static final int CACHE_LIFETIME_TICKS = 20;
    private int tickCount = 0;

    private static class CachedCollisionData {
        final List<RigidBody> bodies;
        final int createdTick;
        int refCount = 0;

        CachedCollisionData(List<RigidBody> bodies, int tick) {
            this.bodies = bodies;
            this.createdTick = tick;
        }
    }

    public ClientJbulletWorld(ClientLevel level) {
        this.level = level;
        collisionConfig = new DefaultCollisionConfiguration();
        dispatcher = new CollisionDispatcher(collisionConfig);
        // DbvtBroadphase: dynamic AABB-tree broadphase with O(log N) updates and no
        // fixed handle cap or world-bounds. AxisSweep3 was hitting its handle limit
        // and slowing as static-block bodies accumulated across many cache regions.
        broadphase = new DbvtBroadphase();
        solver = new SequentialImpulseConstraintSolver();
        dynamicsWorld = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        dynamicsWorld.setGravity(new Vector3f(0f, -9.81f, 0f));
        dynamicsWorld.getSolverInfo().numIterations = 20;
    }

    /**
     * Step the physics world. activeRagdollCount controls solver quality and substep rate.
     * Tiers are tuned for the dominant cost being constraint-solver iterations × contact
     * count, which explodes when many active ragdolls share contact islands (piles).
     *
     *   ≤4 active  → 20 iters, 1/120 substep (6 substeps/tick) — full quality
     *   ≤8 active  → 10 iters, 1/120 substep (6 substeps/tick)
     *   ≤15 active → 10 iters, 1/60 substep  (3 substeps/tick)
     *   >15 active → 6  iters, 1/40 substep  (2 substeps/tick) — pile mode
     *
     * Pile mode trades joint-resolution accuracy for a ~2-3x physics-step speedup.
     * Visible quality loss is minor because piled ragdolls are typically near-rest.
     */
    public void step(float dt, int activeRagdollCount) {
        newCacheEntriesThisTick = 0;
        cacheStats.resetCounters();
        int iterations;
        float substepSize;
        if (activeRagdollCount > 15) {
            iterations = 6;
            substepSize = 1f / 40f;
        } else if (activeRagdollCount > 8) {
            iterations = 10;
            substepSize = 1f / 60f;
        } else if (activeRagdollCount >= 5) {
            iterations = 10;
            substepSize = 1f / 120f;
        } else {
            iterations = 20;
            substepSize = 1f / 120f;
        }
        dynamicsWorld.getSolverInfo().numIterations = iterations;
        dynamicsWorld.stepSimulation(dt, 10, substepSize);
        tickCacheCleanup();
    }

    /**
     * Advance the cache TTL clock and remove expired static bodies WITHOUT running
     * any physics simulation. Called when there are no active dynamic bodies so we
     * avoid stepping a world full of static block geometry with nothing to collide against.
     * The AxisSweep3 broadphase still has to traverse all those bodies inside stepSimulation(),
     * which wastes 5–10 ms even with zero dynamic bodies — this skips that entirely.
     */
    public void maintainCache() {
        newCacheEntriesThisTick = 0;
        cacheStats.resetCounters();
        tickCacheCleanup();
    }

    private void tickCacheCleanup() {
        collisionCache.entrySet().removeIf(entry -> {
            CachedCollisionData data = entry.getValue();
            if (data.refCount == 0 && (tickCount - data.createdTick) > CACHE_LIFETIME_TICKS) {
                for (RigidBody body : data.bodies) {
                    dynamicsWorld.removeRigidBody(body);
                }
                return true;
            }
            return false;
        });
        tickCount++;
    }

    /**
     * Returns the cached static bodies for {@code center}, creating them if necessary.
     * Returns {@code null} (without creating) when the per-tick creation budget is full —
     * the caller should retain its old geometry and retry on the next tick.
     */
    public List<RigidBody> getOrCreateCollisionGeometry(BlockPos center,
                                                        Supplier<List<RigidBody>> creator) {
        CachedCollisionData cached = collisionCache.get(center);
        if (cached != null && (tickCount - cached.createdTick) <= CACHE_LIFETIME_TICKS) {
            cached.refCount++;
            cacheStats.hits++;
            return cached.bodies; // cache hit — free, no budget consumed
        }

        // Stale entry (age > TTL) with no active users — eagerly remove its bodies from
        // the world now so we don't add a duplicate set alongside the about-to-be-created ones.
        if (cached != null && cached.refCount == 0) {
            for (RigidBody body : cached.bodies) dynamicsWorld.removeRigidBody(body);
            collisionCache.remove(center);
        }

        // Rate-limit new entry creation to avoid a hitch when many ragdolls spawn at once.
        if (newCacheEntriesThisTick >= MAX_NEW_CACHE_ENTRIES_PER_TICK) {
            cacheStats.rateLimited++;
            return null; // caller keeps old geometry and retries next tick
        }
        newCacheEntriesThisTick++;

        List<RigidBody> newBodies = creator.get();
        cacheStats.misses++;
        cacheStats.staticBodiesCreatedThisTick += newBodies.size();
        CachedCollisionData newData = new CachedCollisionData(newBodies, tickCount);
        newData.refCount = 1;
        collisionCache.put(center, newData);
        return newBodies;
    }

    /** Refreshes liveCacheEntries / liveStaticBodies — call after step() each tick. */
    public void updateLiveCacheStats() {
        cacheStats.liveCacheEntries = collisionCache.size();
        int total = 0;
        for (CachedCollisionData d : collisionCache.values()) total += d.bodies.size();
        cacheStats.liveStaticBodies = total;
    }

    // Mirrors JbulletWorld.releaseCollisionGeometry() exactly
    public void releaseCollisionGeometry(BlockPos center) {
        CachedCollisionData cached = collisionCache.get(center);
        if (cached != null) {
            cached.refCount = Math.max(0, cached.refCount - 1);
        }
    }

    /**
     * Drop any cache entries whose region contains the changed block, immediately
     * removing their static bodies from the dynamics world. Called when a block
     * is broken / placed / changed so ragdolls don't keep colliding with phantom
     * floor geometry. Ragdolls referencing the dropped entries must reset their
     * own currentCachedBodies / lastCollisionCenter — see ClientRagdoll.onBlockChangedNear.
     *
     * Caller must already be on the physics thread (drainInputQueues).
     *
     * @param radius cache region half-extent — must match ClientRagdoll.COLLISION_RADIUS
     */
    public int invalidateCacheNear(BlockPos pos, int radius) {
        int removed = 0;
        Iterator<Map.Entry<BlockPos, CachedCollisionData>> it = collisionCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, CachedCollisionData> entry = it.next();
            BlockPos center = entry.getKey();
            int dx = Math.abs(center.getX() - pos.getX());
            int dy = Math.abs(center.getY() - pos.getY());
            int dz = Math.abs(center.getZ() - pos.getZ());
            if (dx > radius || dy > radius || dz > radius) continue;
            for (RigidBody body : entry.getValue().bodies) {
                dynamicsWorld.removeRigidBody(body);
            }
            it.remove();
            removed++;
        }
        return removed;
    }

    public DiscreteDynamicsWorld getDynamicsWorld() { return dynamicsWorld; }
    public CollisionDispatcher getDispatcher() { return dispatcher; }
    public DiscreteDynamicsWorld getWorld() { return dynamicsWorld; }
    public ClientLevel getLevel() { return level; }
    public int getTickCount() { return tickCount; }

    public void destroy() {
        for (CachedCollisionData data : collisionCache.values()) {
            for (RigidBody body : data.bodies) {
                dynamicsWorld.removeRigidBody(body);
            }
        }
        collisionCache.clear();
    }
}