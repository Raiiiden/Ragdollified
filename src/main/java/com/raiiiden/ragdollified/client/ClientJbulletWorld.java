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
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.function.Function;

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

    // Limit expensive region acquisitions when many ragdolls spawn together.
    private static final int MAX_NEW_CACHE_ENTRIES_PER_TICK = 3;
    private static final int UNLOADED_STATE_ID = Block.getId(Blocks.AIR.defaultBlockState());
    private int newCacheEntriesThisTick = 0;

    /** Per-tick stats. Reset once at the start of ClientRagdollManager.tickAll(). */
    public static final class CacheStats {
        public int hits;            // cache hit, no work done
        public int misses;          // region needed one or more block entries
        public int rateLimited;     // miss but creation budget exhausted, returned null
        public int unloadedSkipped; // region was not fully available yet
        public int poseDeferred;
        public int poseRejected;
        public int staticBodiesCreatedThisTick; // sum of bodies created this tick
        public int liveCacheEntries;            // current size of the cache map
        public int liveStaticBodies;            // total static bodies across all cache entries

        public void resetCounters() {
            hits = 0;
            misses = 0;
            rateLimited = 0;
            unloadedSkipped = 0;
            poseDeferred = 0;
            poseRejected = 0;
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
    private final Long2ObjectOpenHashMap<CachedBlockCollisionData> collisionCache =
            new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cacheBuildPos = new BlockPos.MutableBlockPos();
    private static final int CACHE_LIFETIME_TICKS = 20;
    private int tickCount = 0;

    public static final class BuiltBlockCollisionGeometry {
        final List<RigidBody> bodies;
        final int stateId;

        public BuiltBlockCollisionGeometry(List<RigidBody> bodies, int stateId) {
            this.bodies = bodies;
            this.stateId = stateId;
        }
    }

    public static final class CollisionGeometryHandle {
        private final CachedBlockCollisionData[] blocks;
        private final long terrainSignature;
        private boolean released;

        private CollisionGeometryHandle(CachedBlockCollisionData[] blocks, long terrainSignature) {
            this.blocks = blocks;
            this.terrainSignature = terrainSignature;
        }

        public boolean isValid() {
            if (released) return false;
            for (CachedBlockCollisionData block : blocks) {
                if (block != null && !block.valid) return false;
            }
            return true;
        }

        public long terrainSignature() {
            return terrainSignature;
        }
    }

    private static final class CachedBlockCollisionData {
        final long key;
        final List<RigidBody> bodies;
        final int stateId;
        int refCount = 0;
        int unusedSinceTick = -1;
        boolean valid = true;

        CachedBlockCollisionData(long key, List<RigidBody> bodies, int stateId) {
            this.key = key;
            this.bodies = bodies;
            this.stateId = stateId;
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
        dynamicsWorld.setGravity(new Vector3f(0f, -(float) RagdollifiedConfig.get(RagdollifiedConfig.GRAVITY), 0f));
        dynamicsWorld.getSolverInfo().numIterations = 20;
    }

    public void beginTick() {
        newCacheEntriesThisTick = 0;
        cacheStats.resetCounters();
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
        tickCacheCleanup();
    }

    private void tickCacheCleanup() {
        collisionCache.long2ObjectEntrySet().removeIf(entry -> {
            CachedBlockCollisionData data = entry.getValue();
            if (data.refCount == 0 && data.unusedSinceTick >= 0
                    && (tickCount - data.unusedSinceTick) > CACHE_LIFETIME_TICKS) {
                for (RigidBody body : data.bodies) {
                    dynamicsWorld.removeRigidBody(body);
                }
                data.valid = false;
                return true;
            }
            return false;
        });
        tickCount++;
    }

    // Returns null when the per-tick creation budget is full.
    public CollisionGeometryHandle getOrCreateCollisionGeometry(
            BlockPos center, int radius, Function<BlockPos, BuiltBlockCollisionGeometry> creator) {
        boolean needsCreation = false;
        for (int dx = -radius; dx <= radius && !needsCreation; dx++) {
            for (int dy = -radius; dy <= radius && !needsCreation; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    if (!isBlockChunkLoaded(x, z)) {
                        continue;
                    }
                    long key = BlockPos.asLong(
                            x, center.getY() + dy, z);
                    CachedBlockCollisionData cached = collisionCache.get(key);
                    if (cached == null || !cached.valid) {
                        needsCreation = true;
                        break;
                    }
                }
            }
        }

        if (needsCreation && newCacheEntriesThisTick >= MAX_NEW_CACHE_ENTRIES_PER_TICK) {
            cacheStats.rateLimited++;
            return null;
        }
        if (needsCreation) newCacheEntriesThisTick++;

        int diameter = radius * 2 + 1;
        CachedBlockCollisionData[] acquired =
                new CachedBlockCollisionData[diameter * diameter * diameter];
        int acquiredIndex = 0;
        boolean countedUnavailable = false;
        long signature = 0xcbf29ce484222325L;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    if (!isBlockChunkLoaded(x, z)) {
                        if (!countedUnavailable) {
                            cacheStats.unloadedSkipped++;
                            countedUnavailable = true;
                        }
                        acquired[acquiredIndex++] = null;
                        signature ^= UNLOADED_STATE_ID;
                        signature *= 1099511628211L;
                        continue;
                    }
                    long key = BlockPos.asLong(x, y, z);
                    CachedBlockCollisionData data = collisionCache.get(key);
                    if (data == null || !data.valid) {
                        BuiltBlockCollisionGeometry built = creator.apply(cacheBuildPos.set(x, y, z));
                        data = new CachedBlockCollisionData(key, built.bodies, built.stateId);
                        collisionCache.put(key, data);
                        cacheStats.staticBodiesCreatedThisTick += built.bodies.size();
                    }
                    data.refCount++;
                    data.unusedSinceTick = -1;
                    acquired[acquiredIndex++] = data;
                    signature ^= data.stateId;
                    signature *= 1099511628211L;
                }
            }
        }

        if (needsCreation) cacheStats.misses++;
        else cacheStats.hits++;
        return new CollisionGeometryHandle(acquired, signature);
    }

    private boolean isBlockChunkLoaded(int blockX, int blockZ) {
        return level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4);
    }

    /** Refreshes liveCacheEntries / liveStaticBodies — call after step() each tick. */
    public void updateLiveCacheStats() {
        cacheStats.liveCacheEntries = collisionCache.size();
        int total = 0;
        for (CachedBlockCollisionData d : collisionCache.values()) total += d.bodies.size();
        cacheStats.liveStaticBodies = total;
    }

    public void releaseCollisionGeometry(CollisionGeometryHandle handle) {
        if (handle == null || handle.released) return;
        handle.released = true;
        for (CachedBlockCollisionData data : handle.blocks) {
            if (data == null) continue;
            data.refCount = Math.max(0, data.refCount - 1);
            if (data.refCount == 0) {
                data.unusedSinceTick = tickCount;
            }
        }
    }

    public void invalidateCollisionGeometry(CollisionGeometryHandle handle) {
        if (handle == null) return;
        for (CachedBlockCollisionData data : handle.blocks) {
            if (data == null) continue;
            invalidateBlock(data);
        }
    }

    private boolean invalidateBlock(BlockPos pos) {
        CachedBlockCollisionData data = collisionCache.get(pos.asLong());
        if (data == null) return false;
        return invalidateBlock(data);
    }

    private boolean invalidateBlock(CachedBlockCollisionData data) {
        if (!data.valid) return false;
        data.valid = false;
        for (RigidBody body : data.bodies) {
            dynamicsWorld.removeRigidBody(body);
        }
        if (collisionCache.get(data.key) == data) {
            collisionCache.remove(data.key);
        }
        return true;
    }

    // Contextual block shapes can change when a direct neighbor changes.
    public int invalidateBlockChange(BlockPos pos) {
        int removed = 0;
        if (invalidateBlock(pos)) removed++;
        for (Direction direction : Direction.values()) {
            if (invalidateBlock(pos.relative(direction))) removed++;
        }
        return removed;
    }

    public int invalidateCacheRegion(BlockPos center, int radius) {
        int removed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long key = BlockPos.asLong(
                            center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    CachedBlockCollisionData data = collisionCache.get(key);
                    if (data != null && invalidateBlock(data)) removed++;
                }
            }
        }
        return removed;
    }

    public DiscreteDynamicsWorld getDynamicsWorld() { return dynamicsWorld; }
    public CollisionDispatcher getDispatcher() { return dispatcher; }
    public DiscreteDynamicsWorld getWorld() { return dynamicsWorld; }
    public ClientLevel getLevel() { return level; }
    public int getTickCount() { return tickCount; }

    public void destroy() {
        for (CachedBlockCollisionData data : collisionCache.values()) {
            data.valid = false;
            for (RigidBody body : data.bodies) {
                dynamicsWorld.removeRigidBody(body);
            }
        }
        collisionCache.clear();
    }
}
