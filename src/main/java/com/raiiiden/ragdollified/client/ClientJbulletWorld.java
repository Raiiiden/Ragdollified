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

    // Synchronized so two threads cannot race to create or dispose the singleton: this runs on
    // the physics worker every tick and from main during world unload.
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

    // Region acquisitions are budgeted, and the budget scales with active ragdolls: a flat one starves
    // mass deaths, leaving ragdolls on stale geometry that reads as phasing through walls.
    private static final int BASE_CACHE_ENTRIES_PER_TICK = 3;
    private static final int MAX_CACHE_ENTRIES_PER_TICK = 24;
    // Share of the budget reserved for fast movers, since a settled ragdoll can wait a tick for
    // geometry and one crossing blocks cannot. Slow requesters stop at (budget - reserve).
    private static final float PRIORITY_RESERVE_FRACTION = 0.4f;
    private static final int UNLOADED_STATE_ID = Block.getId(Blocks.AIR.defaultBlockState());
    private int newCacheEntriesThisTick = 0;
    private int cacheEntryBudgetThisTick = BASE_CACHE_ENTRIES_PER_TICK;
    private int normalPriorityBudgetThisTick = BASE_CACHE_ENTRIES_PER_TICK;

    // Per-tick stats. Reset once at the start of ClientRagdollManager.tickAll().
    public static final class CacheStats {
        public int hits;            // cache hit, no work done
        public int misses;          // region needed one or more block entries
        public int rateLimited;     // miss but creation budget exhausted, returned null
        public int rateLimitedPriority; // subset of rateLimited that were fast movers — these tunnel
        public int budgetThisTick;  // region-creation budget this tick (scales with active count)
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
            rateLimitedPriority = 0;
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

    // Mirrors JbulletWorld's cache: time-based expiry, refcounted. A 1s TTL absorbs oscillation
    // between adjacent block centres without letting mass spawns pile up stale static geometry.
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
        // DbvtBroadphase: dynamic AABB tree, O(log N) updates, no handle cap. AxisSweep3 hit its limit
        // and slowed as static block bodies accumulated across cache regions.
        broadphase = new DbvtBroadphase();
        solver = new SequentialImpulseConstraintSolver();
        dynamicsWorld = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        dynamicsWorld.setGravity(new Vector3f(0f, -(float) RagdollifiedConfig.get(RagdollifiedConfig.GRAVITY), 0f));
        dynamicsWorld.getSolverInfo().numIterations = 20;
    }

    // activeRagdollCount is last tick's total, sizing this tick's region budget. Close enough: the
    // active set moves by a few per tick, and a mass spawn just ramps the budget a tick later.
    public void beginTick(int activeRagdollCount) {
        newCacheEntriesThisTick = 0;
        // One region per active ragdoll is the ideal, since each can cross a block boundary per tick;
        // half that in practice, as only ragdolls that moved to a new block ask.
        int budget = BASE_CACHE_ENTRIES_PER_TICK + (activeRagdollCount / 2);
        cacheEntryBudgetThisTick = Math.min(MAX_CACHE_ENTRIES_PER_TICK, budget);
        int reserve = Math.max(1, Math.round(cacheEntryBudgetThisTick * PRIORITY_RESERVE_FRACTION));
        normalPriorityBudgetThisTick = Math.max(1, cacheEntryBudgetThisTick - reserve);
        cacheStats.budgetThisTick = cacheEntryBudgetThisTick;
        cacheStats.resetCounters();
    }

    // Step the world, activeRagdollCount picking solver quality and substep rate, since cost is
    // iterations times contacts. Pile mode trades joint accuracy for speed on near-resting bodies.
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

    // Advance the cache TTL and drop expired static bodies without simulating. Used when nothing is
    // active, where a step would still walk every static block body for 5-10 ms of nothing.
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

    // Null once the per-tick creation budget is full. highPriority means the requester is fast
    // enough that stale geometry would let it tunnel, so it may draw on the reserved slice.
    public CollisionGeometryHandle getOrCreateCollisionGeometry(
            BlockPos center, int radius, boolean highPriority,
            Function<BlockPos, BuiltBlockCollisionGeometry> creator) {
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

        int budget = highPriority ? cacheEntryBudgetThisTick : normalPriorityBudgetThisTick;
        if (needsCreation && newCacheEntriesThisTick >= budget) {
            cacheStats.rateLimited++;
            if (highPriority) cacheStats.rateLimitedPriority++;
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

    // Refreshes liveCacheEntries / liveStaticBodies — call after step() each tick.
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
