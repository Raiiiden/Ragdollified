package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.physics.PhysicsBackends;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import com.raiiiden.ragdollified.physics.StepQuality;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Function;

// Owns the client's simulation and the static block geometry cached around it. Engine-agnostic: the
// engine itself is chosen once by PhysicsBackends and reached only through the PhysicsWorld seam.
@OnlyIn(Dist.CLIENT)
public class ClientPhysicsWorld {

    private static volatile ClientPhysicsWorld instance;
    private static volatile ClientLevel currentLevel;

    // Synchronized so two threads cannot race to create or dispose the singleton: this runs on
    // the physics worker every tick and from main during world unload.
    public static synchronized ClientPhysicsWorld get(ClientLevel level) {
        if (instance == null || currentLevel != level) {
            if (instance != null) instance.destroy();
            instance = new ClientPhysicsWorld(level);
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
        public int rateLimitedPriority; // subset of rateLimited that were fast movers; these tunnel
        public int budgetThisTick;  // region-creation budget this tick (scales with active count)
        public int unloadedSkipped; // region was not fully available yet
        public int poseDeferred;
        public int poseRejected;
        public int staticBodiesCreatedThisTick; // sum of bodies created this tick
        public int liveCacheEntries;            // current size of the cache map
        public int liveStaticBodies;            // total static bodies across all cache entries
        public int internedShapes;              // distinct block-AABB box shapes shared world-wide

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
    private final PhysicsWorld physics;

    // Time-based expiry, refcounted. A 1s TTL absorbs oscillation
    // between adjacent block centres without letting mass spawns pile up stale static geometry.
    private final Long2ObjectOpenHashMap<CachedBlockCollisionData> collisionCache =
            new Long2ObjectOpenHashMap<>();
    // Block shapes carry no position, so one cached shape per distinct size serves every static body.
    private final Long2ObjectOpenHashMap<PhysicsShape> internedBoxShapes =
            new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cacheBuildPos = new BlockPos.MutableBlockPos();
    private static final int CACHE_LIFETIME_TICKS = 20;
    private int tickCount = 0;

    public static final class BuiltBlockCollisionGeometry {
        final List<PhysicsBody> bodies;
        final int stateId;

        public BuiltBlockCollisionGeometry(List<PhysicsBody> bodies, int stateId) {
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
        final List<PhysicsBody> bodies;
        final int stateId;
        int refCount = 0;
        int unusedSinceTick = -1;
        boolean valid = true;

        CachedBlockCollisionData(long key, List<PhysicsBody> bodies, int stateId) {
            this.key = key;
            this.bodies = bodies;
            this.stateId = stateId;
        }
    }

    public ClientPhysicsWorld(ClientLevel level) {
        this.level = level;
        this.physics = PhysicsBackends.create();
        physics.setGravity(0f, -(float) RagdollifiedConfig.get(RagdollifiedConfig.GRAVITY), 0f);
        // The same figure the ragdoll bodies get, so a contact between a body and the ground and a
        // contact between two bodies resolve to the same friction. See PhysicsWorld#setStaticFriction.
        physics.setStaticFriction((float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION));
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

    // Step the world; activeRagdollCount picks the effort level (see StepQuality).
    public void step(float dt, int activeRagdollCount) {
        StepQuality quality;
        if (activeRagdollCount > 15) quality = StepQuality.PILE;
        else if (activeRagdollCount > 8) quality = StepQuality.ECONOMY;
        else if (activeRagdollCount >= 5) quality = StepQuality.BALANCED;
        else quality = StepQuality.HIGH;
        physics.step(dt, quality);
        tickCacheCleanup();
    }

    // Advance the cache TTL and drop expired static bodies without simulating. Used when nothing is
    // active, where a step would still walk every static block body for 5-10 ms of nothing.
    public void maintainCache() {
        physics.skipStep();
        tickCacheCleanup();
    }

    private void tickCacheCleanup() {
        collisionCache.long2ObjectEntrySet().removeIf(entry -> {
            CachedBlockCollisionData data = entry.getValue();
            if (data.refCount == 0 && data.unusedSinceTick >= 0
                    && (tickCount - data.unusedSinceTick) > CACHE_LIFETIME_TICKS) {
                destroyBodies(data);
                data.valid = false;
                return true;
            }
            return false;
        });
        tickCount++;
    }

    // Static bodies are native memory on the Jolt backend, so eviction has to destroy them and not
    // merely remove them from the simulation. A missed destroy here leaks for the whole session.
    private void destroyBodies(CachedBlockCollisionData data) {
        for (PhysicsBody body : data.bodies) {
            physics.destroyBody(body);
        }
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

    // Refreshes liveCacheEntries / liveStaticBodies. Only worth the walk when the perf log will read
    // it: this iterated every cache entry every tick regardless of whether anything consumed it.
    public void updateLiveCacheStats() {
        if (!RagdollifiedConfig.shouldLogPhysicsPerf()) return;
        cacheStats.liveCacheEntries = collisionCache.size();
        cacheStats.internedShapes = internedBoxShapes.size();
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
        destroyBodies(data);
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

    // A box shape shared by every static block body of this size. Terrain shapes only, and only from
    // the physics worker: the map is not synchronized and neither is the backend that fills it.
    public PhysicsShape internedBoxShape(float halfX, float halfY, float halfZ) {
        long key = shapeKey(halfX, halfY, halfZ);
        PhysicsShape shape = internedBoxShapes.get(key);
        if (shape == null) {
            shape = physics.createBoxShape(halfX, halfY, halfZ);
            internedBoxShapes.put(key, shape);
        }
        return shape;
    }

    // Block collision boxes land on sixteenths of a block, so quantising to 1/1024 is lossless for
    // them while making sizes that differ only by float noise share one shape.
    private static long shapeKey(float halfX, float halfY, float halfZ) {
        long x = Math.min(0x1FFFFFL, Math.round(halfX * 1024.0));
        long y = Math.min(0x1FFFFFL, Math.round(halfY * 1024.0));
        long z = Math.min(0x1FFFFFL, Math.round(halfZ * 1024.0));
        return (x << 42) | (y << 21) | z;
    }

    public PhysicsWorld getPhysics() { return physics; }
    public ClientLevel getLevel() { return level; }
    public int getTickCount() { return tickCount; }
    public String engineName() { return physics.engineName(); }

    public void destroy() {
        for (CachedBlockCollisionData data : collisionCache.values()) {
            data.valid = false;
            destroyBodies(data);
        }
        collisionCache.clear();
        internedBoxShapes.clear();
        physics.destroy();
    }
}
