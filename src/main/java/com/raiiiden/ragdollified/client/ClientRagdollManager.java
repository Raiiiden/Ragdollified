package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.raiiiden.ragdollified.*;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.network.CorpseSettlePacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@OnlyIn(Dist.CLIENT)
public class ClientRagdollManager {

    // Owned by the physics thread. Render thread iterates values() for rendering;
    // ConcurrentHashMap allows that safely (weakly consistent — may miss recently-added
    // ragdolls for one frame, which is fine — they'll appear next frame).
    // Insertion order is no longer preserved here; force-settle uses ticksExisted instead.
    private static final Map<Integer, ClientRagdoll> ragdolls = new ConcurrentHashMap<>();
    private static final Set<Integer> processedEntityIds = ConcurrentHashMap.newKeySet();

    // Performance logging — logs every 100 ticks (~5 seconds).
    // All "lastX" fields are populated each tick and read by the periodic log.
    private static int perfTickCounter = 0;
    private static long lastTickAllNanos = 0;
    private static long lastSpawnQueueNanos = 0;
    private static long lastStatePassNanos = 0;
    private static long lastWakeLoopNanos = 0;
    private static long lastPhysicsStepNanos = 0;
    private static long lastPostTickNanos = 0;
    private static int lastSpawnsThisTick = 0;
    private static int lastSpawnQueueDepth = 0;
    private static int lastWakesThisTick = 0;
    private static int lastForceSettledThisTick = 0;
    private static int lastBlockChangesProcessed = 0;
    private static int lastManifoldCount = 0;
    private static int lastContactPointCount = 0;
    private static int lastDynamicBodyCount = 0;
    // Worst-tick tracker — captures the slowest tickAll inside the 100-tick window so
    // periodic spikes that happen between log intervals are still visible.
    private static long worstTickAllNanos = 0;
    private static int worstTickActiveCount = 0;
    private static int worstTickManifoldCount = 0;

    // Per-window distribution: tracks every tickAll in the 100-tick window so the log
    // can show min/max/avg/p99. A single sampled-tick value lies about how things felt
    // — most ticks could be 5ms with one 50ms spike, and the user only sees the spike.
    private static final long[] tickAllRing = new long[100];
    private static int tickAllRingFilled = 0;

    // GC pause tracking. JBullet allocates Vector3f / Transform / manifold objects
    // internally on every step; with many bodies + manifolds this adds up to MB/s of
    // allocation pressure. GC pauses don't show up in our nanoTime() phase timers but
    // DO cause visible frame stutter. ManagementFactory exposes per-collector counters
    // we can diff per-window to reveal whether GC is the culprit.
    private static final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private static long lastGcCount = sumGcCount();
    private static long lastGcTimeMs = sumGcTimeMs();
    private static long windowGcCount = 0;
    private static long windowGcTimeMs = 0;

    private static long sumGcCount() {
        long s = 0;
        for (GarbageCollectorMXBean b : gcBeans) s += Math.max(0, b.getCollectionCount());
        return s;
    }
    private static long sumGcTimeMs() {
        long s = 0;
        for (GarbageCollectorMXBean b : gcBeans) s += Math.max(0, b.getCollectionTime());
        return s;
    }

    // Cap how many settled ragdolls can be woken per tick to prevent cascade spikes.
    // Without this a pile of ragdolls landing at once can chain-wake each other in one
    // tick, dumping 10+ active bodies into the solver and spiking physics time.
    private static final int MAX_WAKES_PER_TICK = 3;

    // Reused per tick to avoid re-walking the full ragdoll collection inside the wake loop.
    private static final List<ClientRagdoll> wakeMovers = new ArrayList<>();
    private static final List<ClientRagdoll> wakeSettled = new ArrayList<>();

    // Spawn queue: when N entities die in the same tick (e.g. an explosion killing 20
    // zombies) we don't want to construct N ragdolls in one tick — that's N×6 dynamic
    // bodies + N×5 joints + up to MAX_NEW_CACHE_ENTRIES_PER_TICK static-geometry caches
    // (~200 bodies each) all going through broadphase insertion in a single frame.
    // createFromEntity captures the live entity into a SpawnData snapshot and enqueues
    // it; tickAll pops up to the configured spawn-per-tick limit. Spreads a 250ms hitch into
    // ~5 manageable ticks. Visible delay between death and ragdoll appearing is small
    // (matches MAX_NEW_CACHE_ENTRIES_PER_TICK so each spawned ragdoll can immediately
    // get its static-collision cache). The per-tick and queue limits are config-backed.
    private static final ConcurrentLinkedQueue<ClientRagdoll.SpawnData> spawnQueue = new ConcurrentLinkedQueue<>();

    // Cross-thread input queues. Inputs from main/render thread (clicks, block changes,
    // network packets) are enqueued here; the physics thread drains them at the start
    // of each tick and applies them through jbullet.
    public static final class ImpulseRequest {
        public final int ragdollId;
        public final int partIndex;
        public final float x, y, z;
        public ImpulseRequest(int ragdollId, int partIndex, float x, float y, float z) {
            this.ragdollId = ragdollId; this.partIndex = partIndex;
            this.x = x; this.y = y; this.z = z;
        }
    }
    private static final ConcurrentLinkedQueue<ImpulseRequest> impulseQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<BlockPos> blockChangeQueue = new ConcurrentLinkedQueue<>();
    // Corpse handoff: when a posed corpse entity arrives, the ONE redundant physics ragdoll it
    // replaced (matched by ragdoll entity id, not player UUID) is queued here for the physics
    // thread to destroy (no main-thread race). Matching by id means a lingering older corpse
    // can't cull a newer death's ragdoll for the same player.
    private static final ConcurrentLinkedQueue<Integer> removeByRagdollIdQueue = new ConcurrentLinkedQueue<>();

    // Single-thread executor that runs all physics work. Daemon so it dies with the JVM.
    // submitTick() is called from ClientTickEvent (render thread); the executor takes the
    // work off-thread so render never blocks on physics.
    private static volatile ExecutorService physicsExecutor = newPhysicsExecutor();
    private static final AtomicBoolean physicsBusy = new AtomicBoolean(false);

    private static ExecutorService newPhysicsExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ragdollified-Physics");
            t.setDaemon(true);
            return t;
        });
    }

    // Once jbullet's internal state corrupts (UnionFind / DbvtBroadphase exception),
    // every subsequent step throws. Set this flag on first crash, disable submissions
    // until the next world load resets state cleanly.
    private static volatile boolean physicsBroken = false;

    /**
     * Submit a physics tick to the worker thread. If the previous tick is still running,
     * THIS tick is dropped (not queued) — better to fall behind one tick than to backlog
     * and run two physics steps back-to-back. Called from ClientTickEvent at 20 Hz.
     */
    public static void submitTick() {
        if (physicsBroken) return;
        if (!physicsBusy.compareAndSet(false, true)) return;
        physicsExecutor.execute(() -> {
            try {
                tickAll();
            } catch (Throwable t) {
                if (!physicsBroken) {
                    physicsBroken = true;
                    Ragdollified.LOGGER.error(
                            "Ragdoll physics crashed — disabling worker until world reload. "
                          + "This usually means jbullet's world state was corrupted by "
                          + "a cross-thread modification.", t);
                }
            } finally {
                physicsBusy.set(false);
            }
        });
    }

    /** Enqueue a punch/click impulse for the physics thread to apply on its next tick. */
    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z) {
        impulseQueue.offer(new ImpulseRequest(ragdollId, partIndex, x, y, z));
    }

    /** Destroy a specific physics ragdoll (by entity id) on the physics thread (corpse handoff). */
    public static void requestRemoveRagdoll(int entityId) {
        removeByRagdollIdQueue.offer(entityId);
    }

    /**
     * Main-thread (client tick) corpse bridge. Two jobs, both cheap and only active when
     * corpses are enabled and the local player has a settled/settling ragdoll:
     *  1. Report the local player's ragdoll settle to the server (once) so it can pose the
     *     corpse at the true resting position + pose.
     *  2. Once the posed corpse entity has arrived, drop the now-redundant physics ragdoll.
     */
    public static void tickCorpseClient() {
        if (!RagdollifiedConfig.isCorpseEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (ragdolls.isEmpty()) return; // nothing to report or hand off
        UUID self = mc.player.getUUID();

        // Job 1 — settle report for the LOCAL player's own corpse (owner-authoritative).
        ClientRagdoll mine = null;
        for (ClientRagdoll r : ragdolls.values()) {
            if (r.isPlayer() && self.equals(r.getPlayerUUID())) { mine = r; break; }
        }
        // Report when the ragdoll has settled, OR shortly before it would despawn / the
        // server would time out — whichever comes first — so even a stuck (never-settling)
        // ragdoll still reports its ACTUAL current pose + position. Otherwise the server
        // timeout fires and the corpse appears as a synthetic pose at the death position
        // (the "mush above the ragdoll" case).
        if (mine != null && !mine.isCorpseSettleReported()) {
            int limit = Math.min(RagdollifiedConfig.getRagdollLifetime(),
                                 RagdollifiedConfig.getCorpseSettleTimeoutTicks());
            // Don't "give up" while the ragdoll is distance-frozen (paused because the player
            // walked away): it isn't stuck, just suspended, and its ticks no longer advance.
            // Reporting now would freeze the corpse at a mid-fall position; instead wait until
            // the player returns and the body actually settles.
            boolean nearGiveUp = !mine.isFrozen() && mine.getTicksExisted() >= Math.max(20, limit - 40);
            if (mine.isSettled() || nearGiveUp) {
                ClientRagdoll.TransformSnapshot snap = mine.getSnapshot();
                if (snap != null) {
                    Vector3f origin = snap.cachedTorsoPos;
                    RagdollTransform[] rel = new RagdollTransform[6];
                    for (int i = 0; i < 6 && i < snap.positions.length; i++) {
                        Vector3f p = snap.positions[i];
                        if (p == null) continue;
                        rel[i] = new RagdollTransform(i,
                                new Vector3f(p.x - origin.x, p.y - origin.y, p.z - origin.z),
                                new Quat4f(snap.rotations[i]));
                    }
                    ModNetwork.CHANNEL.sendToServer(new CorpseSettlePacket(origin.x, origin.y, origin.z, rel));
                    mine.markCorpseSettleReported();
                }
            }
        }

        // Job 2 — on EVERY client (owner included): once a posed corpse exists for the exact
        // ragdoll it replaced, drop that ragdoll so the corpse is the only visible body (no
        // double-up). Matched by ragdoll entity id (not player UUID), so an older corpse never
        // culls a newer death's ragdoll for the same player. Idempotent — once the ragdoll is
        // gone the lookup misses, so this naturally stops requesting (and handles repeat deaths).
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CorpseEntity c) || !c.isPosed()) continue;
            int ragId = c.getRagdollEntityId();
            if (ragId < 0) continue;
            ClientRagdoll r = ragdolls.get(ragId);
            if (r != null && !r.isDestroyed()) {
                requestRemoveRagdoll(ragId);
            }
        }
    }

    /** Enqueue a block-change wake event. Block updates fire frequently — keep cheap. */
    public static void enqueueBlockChange(BlockPos pos) {
        if (ragdolls.isEmpty()) return; // common case: no ragdolls, skip the alloc
        blockChangeQueue.offer(pos.immutable());
    }

    private static void drainInputQueues() {
        ImpulseRequest req;
        while ((req = impulseQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(req.ragdollId);
            if (r == null) continue;
            RagdollPart part = RagdollPart.byIndex(req.partIndex);
            if (part == null) continue;
            r.applyImpulse(part, new Vector3f(req.x, req.y, req.z));
        }
        // Corpse handoff removals — destroy the specific physics ragdoll (by id) on the
        // physics thread. The post-tick loop drops destroyed ragdolls from the map.
        Integer ragId;
        while ((ragId = removeByRagdollIdQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(ragId);
            if (r != null && !r.isDestroyed()) r.destroy();
        }
        BlockPos pos;
        if (blockChangeQueue.isEmpty()) return;
        ClientJbulletWorld physicsWorld = ClientJbulletWorld.get(net.minecraft.client.Minecraft.getInstance().level);
        if (physicsWorld == null) return;
        int processed = 0;
        while ((pos = blockChangeQueue.poll()) != null) {
            // 1. Drop any cache entries whose 7x7x7 region contains the changed block
            //    (also removes their static bodies from the dynamics world).
            physicsWorld.invalidateCacheNear(pos, ClientRagdoll.COLLISION_RADIUS);
            // 2. Per ragdoll: drop stale cache reference + wake if settled/frozen.
            for (ClientRagdoll ragdoll : ragdolls.values()) {
                ragdoll.onBlockChangedNear(pos);
            }
            processed++;
        }
        lastBlockChangesProcessed = processed;
    }

    // Hard cap on simultaneously-active (not settled / not distance-frozen) ragdolls.
    // This is a backstop for the constraint-solver's super-linear cost in contact
    // density (18 active ≈ 12 ms, 25 active ≈ 80 ms in pile scenarios). When the cap
    // fires, the oldest active ragdolls (LinkedHashMap insertion order) are
    // force-settled — they keep rendering at their last pose and can still be woken
    // by player click or block change. Set high enough that normal play doesn't
    // trigger it; pile-up explosions over the configured active cap will.

    public static void tickAll() {
        if (ragdolls.isEmpty() && spawnQueue.isEmpty()) return;
        long tickStart = System.nanoTime();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }

        ClientJbulletWorld physicsWorld = ClientJbulletWorld.get(level);
        physicsWorld.beginTick();

        // Phase 1 — drain cross-thread input queues, then spawn queue. Inputs (impulses,
        // block changes) come from the render thread; spawns come from death events.
        // Both are processed here so all jbullet mutation happens on this thread.
        long t0 = System.nanoTime();
        drainInputQueues();
        lastSpawnsThisTick = 0;
        if (!spawnQueue.isEmpty()) {
            lastSpawnsThisTick = processSpawnQueue(physicsWorld);
        }
        lastSpawnQueueDepth = spawnQueue.size();
        lastSpawnQueueNanos = System.nanoTime() - t0;

        if (ragdolls.isEmpty()) {
            lastTickAllNanos = System.nanoTime() - tickStart;
            recordTickWorstCase(0, 0);
            maybeLogPerf(0, 0, 0, physicsWorld);
            return;
        }

        // Phase 2 — state pass: count + collect wake lists.
        t0 = System.nanoTime();
        int activeCount = 0;
        int settledCount = 0;
        int frozenCount = 0;
        wakeMovers.clear();
        wakeSettled.clear();
        for (ClientRagdoll r : ragdolls.values()) {
            if (r.isActivelySimulating()) {
                activeCount++;
                if (r.isMovingSignificantly()) wakeMovers.add(r);
            } else if (r.isSettled()) {
                settledCount++;
                if (!r.isDestroyed()) wakeSettled.add(r);
            } else if (!r.isDestroyed()) {
                frozenCount++;
            }
        }
        lastStatePassNanos = System.nanoTime() - t0;

        // Enforce active-ragdoll cap. ConcurrentHashMap doesn't preserve insertion order,
        // so we sort active ragdolls by ticksExisted (largest first = oldest first) and
        // retire the oldest. They've been jiggling longest so they're the best candidates.
        int maxActiveRagdolls = RagdollifiedConfig.MAX_ACTIVE_RAGDOLLS.get();
        if (activeCount > maxActiveRagdolls) {
            int toRetire = activeCount - maxActiveRagdolls;
            lastForceSettledThisTick = toRetire;
            // Collect actives, sort by ticksExisted desc, settle the top N
            List<ClientRagdoll> actives = new ArrayList<>(activeCount);
            for (ClientRagdoll r : ragdolls.values()) {
                if (r.isActivelySimulating()) actives.add(r);
            }
            actives.sort((a, b) -> Integer.compare(b.getTicksExisted(), a.getTicksExisted()));
            for (int i = 0; i < toRetire && i < actives.size(); i++) {
                actives.get(i).forceSettle();
                activeCount--;
                settledCount++;
            }
            // Some retired ragdolls might have been on the wakeMovers list — drop any
            // that are no longer actively simulating so the wake loop doesn't process them.
            wakeMovers.removeIf(r -> !r.isActivelySimulating());
        } else {
            lastForceSettledThisTick = 0;
        }

        Vec3 cameraPos = mc.player != null ? mc.player.getEyePosition() : Vec3.ZERO;

        // Phase 3 — wake loop. Skip entirely when at the active cap: waking a settled
        // ragdoll would just push us over and we'd force-settle it (or another one) on
        // the next tick. That's pure oscillation — observed as forceSettled=2 / wakes=3
        // in the same window.
        t0 = System.nanoTime();
        int wakesThisTick = 0;
        if (activeCount < maxActiveRagdolls
                && !wakeMovers.isEmpty() && !wakeSettled.isEmpty()) {
            outer:
            for (ClientRagdoll mover : wakeMovers) {
                Vector3f aPos = mover.getTorsoPosition();
                for (ClientRagdoll other : wakeSettled) {
                    if (wakesThisTick >= MAX_WAKES_PER_TICK) break outer;
                    if (other.wakeIfNearRagdoll(aPos)) wakesThisTick++;
                }
            }
        }
        lastWakesThisTick = wakesThisTick;
        lastWakeLoopNanos = System.nanoTime() - t0;

        // Phase 4 — physics step (Bullet stepSimulation).
        t0 = System.nanoTime();
        if (activeCount > 0) {
            physicsWorld.step(1f / 20f, activeCount);
        } else {
            // No dynamic bodies — skip stepSimulation() to avoid the broadphase
            // traversing all static block-geometry bodies (~5-10 ms wasted).
            physicsWorld.maintainCache();
        }
        lastPhysicsStepNanos = System.nanoTime() - t0;
        lastDynamicBodyCount = activeCount * 6; // 6 parts per ragdoll

        // Capture Bullet world contact stats AFTER the step so they reflect the contact
        // density the solver actually had to resolve. Cheap — the dispatcher already
        // tracks this internally; we just iterate it.
        captureManifoldStats(physicsWorld);
        physicsWorld.updateLiveCacheStats();

        // Phase 5 — post-tick: per-ragdoll work (correctInterpenetrations, settle check,
        // distance freeze/unfreeze, world collision update). PHASE_STATS aggregates the
        // sub-phase nanos across all ragdoll ticks.
        t0 = System.nanoTime();
        ClientRagdoll.PHASE_STATS.reset();
        Iterator<Map.Entry<Integer, ClientRagdoll>> it = ragdolls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ClientRagdoll> entry = it.next();
            ClientRagdoll ragdoll = entry.getValue();
            ragdoll.tick(cameraPos);
            if (ragdoll.isDestroyed()) {
                processedEntityIds.remove(ragdoll.getOriginalEntityId());
                it.remove();
            }
        }
        lastPostTickNanos = System.nanoTime() - t0;

        lastTickAllNanos = System.nanoTime() - tickStart;
        recordTickWorstCase(activeCount, lastManifoldCount);
        maybeLogPerf(activeCount, settledCount, frozenCount, physicsWorld);
    }

    private static void captureManifoldStats(ClientJbulletWorld physicsWorld) {
        DiscreteDynamicsWorld dw = physicsWorld.getDynamicsWorld();
        int numManifolds = dw.getDispatcher().getNumManifolds();
        int totalContacts = 0;
        for (int i = 0; i < numManifolds; i++) {
            PersistentManifold m = dw.getDispatcher().getManifoldByIndexInternal(i);
            totalContacts += m.getNumContacts();
        }
        lastManifoldCount = numManifolds;
        lastContactPointCount = totalContacts;
    }

    private static void recordTickWorstCase(int activeCount, int manifoldCount) {
        if (lastTickAllNanos > worstTickAllNanos) {
            worstTickAllNanos = lastTickAllNanos;
            worstTickActiveCount = activeCount;
            worstTickManifoldCount = manifoldCount;
        }
        if (tickAllRingFilled < tickAllRing.length) {
            tickAllRing[tickAllRingFilled++] = lastTickAllNanos;
        }
    }

    private static void maybeLogPerf(int activeCount, int settledCount, int frozenCount,
                                     ClientJbulletWorld physicsWorld) {
        if (!RagdollifiedConfig.shouldLogPhysicsPerf()) return;
        perfTickCounter++;
        if (perfTickCounter < 100) return;
        perfTickCounter = 0;

        // Sample GC counters once per window — diff against last sample.
        long gcCountNow = sumGcCount();
        long gcTimeNow = sumGcTimeMs();
        windowGcCount = gcCountNow - lastGcCount;
        windowGcTimeMs = gcTimeNow - lastGcTimeMs;
        lastGcCount = gcCountNow;
        lastGcTimeMs = gcTimeNow;

        // Compute tickAll distribution over the window.
        long minTick = Long.MAX_VALUE, maxTick = 0, sumTick = 0;
        long p99Tick = 0;
        if (tickAllRingFilled > 0) {
            long[] sorted = Arrays.copyOf(tickAllRing, tickAllRingFilled);
            Arrays.sort(sorted);
            minTick = sorted[0];
            maxTick = sorted[sorted.length - 1];
            for (long v : sorted) sumTick += v;
            int p99Idx = Math.max(0, (int) Math.floor(sorted.length * 0.99) - 1);
            p99Tick = sorted[p99Idx];
        } else {
            minTick = 0;
        }
        long avgTick = tickAllRingFilled > 0 ? sumTick / tickAllRingFilled : 0;

        ClientRagdoll.PhaseStats ps = ClientRagdoll.PHASE_STATS;
        ClientJbulletWorld.CacheStats cs = physicsWorld.cacheStats;
        long renderNanos = ClientRagdollRenderer.lastRenderFrameNanos;
        long renderAvg = ClientRagdollRenderer.avgRenderFrameNanos();
        int renderedCount = ClientRagdollRenderer.lastRenderedCount;
        int culledCount = ClientRagdollRenderer.lastCulledCount;

        com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                "[Ragdoll Perf] total={} active={} settled={} frozen={} | "
                + "tickAll={}ms (spawn={} state={} wake={} physics={} postTick={})",
                ragdolls.size(), activeCount, settledCount, frozenCount,
                ms(lastTickAllNanos),
                ms(lastSpawnQueueNanos), ms(lastStatePassNanos), ms(lastWakeLoopNanos),
                ms(lastPhysicsStepNanos), ms(lastPostTickNanos)
        );
        com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                "[Ragdoll Perf]   postTick: cachedXform={} velClamp={} fluid={} playerColl={} "
                + "worldColl={} correct={} settled={} | dist-frozen={} unfrozen={} floorLost={} phantomCleared={}",
                ms(ps.updateCachedTransformsNanos), ms(ps.velocityClampNanos),
                ms(ps.fluidForcesNanos), ms(ps.playerCollisionsNanos),
                ms(ps.updateLocalWorldCollisionNanos), ms(ps.correctInterpenetrationsNanos),
                ms(ps.updateSettledStateNanos),
                ps.distanceFrozenThisTick, ps.unfrozenThisTick, ps.floorLostThisTick,
                ps.phantomCacheClearedThisTick
        );
        com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                "[Ragdoll Perf]   bullet: dynBodies={} manifolds={} contacts={} | "
                + "cache: live={} bodies={} hits={} miss={} rateLim={} created={} | "
                + "spawnQ: depth={} processed={} wakes={} forceSettled={} blockChanges={}",
                lastDynamicBodyCount, lastManifoldCount, lastContactPointCount,
                cs.liveCacheEntries, cs.liveStaticBodies,
                cs.hits, cs.misses, cs.rateLimited, cs.staticBodiesCreatedThisTick,
                lastSpawnQueueDepth, lastSpawnsThisTick, lastWakesThisTick,
                lastForceSettledThisTick, lastBlockChangesProcessed
        );
        com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                "[Ragdoll Perf]   render: lastFrame={}ms avg={}ms rendered={} culled={} | "
                + "tick distribution(n={}): min={}ms avg={}ms p99={}ms max={}ms | "
                + "GC: count={} totalPause={}ms",
                ms(renderNanos), ms(renderAvg), renderedCount, culledCount,
                tickAllRingFilled, ms(minTick), ms(avgTick), ms(p99Tick), ms(maxTick),
                windowGcCount, windowGcTimeMs
        );

        worstTickAllNanos = 0;
        worstTickActiveCount = 0;
        worstTickManifoldCount = 0;
        tickAllRingFilled = 0;
    }

    private static String ms(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }

    /**
     * Capture the entity's death state into a SpawnData snapshot and enqueue it.
     * The actual ClientRagdoll (and its physics bodies) is constructed later in
     * {@link #tickAll()}, rate-limited by config so
     * a mass kill (e.g. explosion taking out 20 mobs) doesn't spike a single tick.
     * Returns null because the ragdoll doesn't exist yet — callers don't use the
     * return value.
     */
    public static ClientRagdoll createFromEntity(LivingEntity entity, @Nullable DamageSource damageSource) {
        if (entity == null) return null;

        int entityId = entity.getId();
        if (processedEntityIds.contains(entityId)) return null;
        processedEntityIds.add(entityId);

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        boolean isPlayer = entity instanceof Player;
        String mobType = EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(mobType, isPlayer)) {
            processedEntityIds.remove(entityId);
            return null;
        }
        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isSupportedModelType(modelType)) {
            Ragdollified.LOGGER.info(
                    "Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render",
                    mobType);
            processedEntityIds.remove(entityId);
            return null;
        }
        float scale = isPlayer ? 1.0f : entity.getBbHeight() / 1.8f;
        // Use LivingEntity.isBaby() rather than an AgeableMob check: zombies, husks and
        // piglins are Monsters (not AgeableMob) but still override isBaby(), so the
        // instanceof check missed every baby zombie — they spawned adult-sized ragdolls.
        boolean isBaby = entity.isBaby();

        MobPoseCapture.MobPose capturedPose = MobPoseCapture.getPose(entityId);
        Vec3 vel = calculateDeathVelocity(entity, damageSource);

        ResourceLocation texture = null;
        if (!isPlayer) {
            texture = ClientMobTextureCache.getTextureForDeadMob(entityId);
        }

        // Resolve any directional hit info captured by the hit tracker (TACZ bullet,
        // vanilla projectile, …) so the constructor can apply a one-shot impulse to
        // the right body part. Returns null if there's no actionable hit data.
        RagdollHitTracker.ResolvedHit hit = RagdollHitTracker.resolveAndPlan(entity, damageSource);
        int hitPartIndex = hit != null
                ? (hit.centered ? RagdollHitMapper.CENTER_HIT_PART_INDEX : hit.part.index)
                : -1;
        Vec3 hitImpulse = hit != null ? hit.impulse : null;

        // Sheep wool state — only meaningful for sheep, ignored otherwise.
        boolean wasSheared = false;
        int dyeColorId = 0;
        if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep) {
            wasSheared = sheep.isSheared();
            dyeColorId = sheep.getColor().getId();
        }
        // Other per-mob overlay flags. Captured here so the local-spawn path (no server
        // packet — singleplayer / dedicated-server-with-mod) sees the same state the
        // server-broadcast path would have packed into the spawn packet.
        boolean chargedCreeper = entity instanceof net.minecraft.world.entity.monster.Creeper c && c.isPowered();
        boolean saddledPig = entity instanceof net.minecraft.world.entity.animal.Pig p && p.isSaddled();

        ClientRagdoll.SpawnData data = new ClientRagdoll.SpawnData(
                entityId, isPlayer, mobType, modelType, scale,
                isPlayer ? entity.getUUID() : null,
                isPlayer ? entity.getName().getString() : "",
                entity.getItemBySlot(EquipmentSlot.HEAD).copy(),
                entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
                entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
                entity.getItemBySlot(EquipmentSlot.FEET).copy(),
                entity.position(), entity.getYRot(), entity.getXRot(),
                vel, capturedPose,
                entity.getPose() == Pose.SWIMMING,
                isBaby,
                texture,
                hitPartIndex, hitImpulse,
                wasSheared, dyeColorId,
                chargedCreeper, saddledPig
        );

        // Drop oldest queued spawn if the queue is overflowing — better than blocking
        // on a death event handler. processedEntityIds also leaks for dropped spawns;
        // cleared on world unload.
        if (spawnQueue.size() >= RagdollifiedConfig.MAX_SPAWN_QUEUE_SIZE.get()) {
            spawnQueue.poll();
        }
        spawnQueue.add(data);
        return null;
    }

    /** Pop up to the configured spawn budget; returns count actually constructed. */
    private static int processSpawnQueue(ClientJbulletWorld physicsWorld) {
        int budget = RagdollifiedConfig.MAX_SPAWNS_PER_TICK.get();
        int spawned = 0;
        ClientRagdoll.SpawnData data;
        while (budget-- > 0 && (data = spawnQueue.poll()) != null) {
            if (!isSupportedSpawn(data)) {
                processedEntityIds.remove(data.originalEntityId);
                continue;
            }
            // If a ragdoll already exists for this entity (e.g. local spawn from death
            // event got replaced by an authoritative server packet), destroy it FIRST
            // — on this thread — so the body removal doesn't race with stepSimulation.
            ClientRagdoll existing = ragdolls.remove(data.originalEntityId);
            if (existing != null) {
                existing.destroy();
            }
            ClientRagdoll ragdoll = new ClientRagdoll(data, physicsWorld);
            enforceMaxRagdolls();
            ragdolls.put(data.originalEntityId, ragdoll);
            processedEntityIds.add(data.originalEntityId);
            // Cap how many of a single player's death ragdolls exist at once (corpses are
            // separate entities and never counted here). Keeps a rapid re-death from stacking
            // an unbounded number of bodies for one player.
            if (ragdoll.isPlayer() && ragdoll.getPlayerUUID() != null) {
                enforceMaxRagdollsPerPlayer(ragdoll.getPlayerUUID());
            }
            spawned++;
        }
        return spawned;
    }

    /**
     * Public entry point for queueing a ragdoll spawn from any thread (used by network
     * packet handlers). The actual ClientRagdoll construction — which calls into
     * jbullet — happens on the physics worker via processSpawnQueue.
     */
    public static void enqueueSpawn(ClientRagdoll.SpawnData data) {
        if (data == null) return;
        if (!isSupportedSpawn(data)) {
            processedEntityIds.remove(data.originalEntityId);
            return;
        }
        if (spawnQueue.size() >= RagdollifiedConfig.MAX_SPAWN_QUEUE_SIZE.get()) {
            spawnQueue.poll();
        }
        spawnQueue.offer(data);
        // Track the entity ID immediately so subsequent createFromEntity calls for the
        // same ID know we've already processed it (don't double-spawn).
        processedEntityIds.add(data.originalEntityId);
    }

    private static boolean isSupportedSpawn(ClientRagdoll.SpawnData data) {
        if (!RagdollifiedConfig.isRagdollEnabledFor(data.mobType, data.isPlayer)) return false;
        if (data.isPlayer) return true;
        if (MobModelHelper.isSupportedModelType(data.modelType)) return true;
        Ragdollified.LOGGER.info(
                "Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render",
                data.mobType);
        return false;
    }

    private static Vec3 calculateDeathVelocity(LivingEntity entity, @Nullable DamageSource damageSource) {
        Vec3 delta = entity.getDeltaMovement();
        // delta is blocks/tick (Minecraft entity physics is 20Hz). Multiply by 20 to convert
        // to physics m/s. Y is dampened slightly (15x) so a kill at terminal fall velocity
        // (~3.92 blocks/tick) doesn't blow past the per-tick velocity ceiling.
        Vec3 vel = new Vec3(delta.x * 20, delta.y * 15, delta.z * 20);

        // Synthesise a fallback velocity only when the entity wasn't really moving on its
        // own — otherwise the entity's actual delta (which already includes server-applied
        // knockback / fall / rider motion) is the source of truth and we just amplify it.
        boolean hasLowVelocity = delta.lengthSqr() < 0.02;

        if (damageSource != null && hasLowVelocity) {
            String damageType = damageSource.getMsgId();

            if (damageType.contains("tacz.bullet")) {
                Vec3 damagePos = damageSource.getSourcePosition();
                if (damagePos != null) {
                    Vec3 direction = entity.position().subtract(damagePos).normalize();
                    vel = new Vec3(direction.x * 3.0, direction.y * 4.0 + 2.0, direction.z * 3.0);
                } else {
                    Vec3 lookVec = entity.getLookAngle();
                    vel = new Vec3(lookVec.x * 5.0, 2.0, lookVec.z * 5.0);
                }
            } else if (damageType.contains("explosion")) {
                Vec3 explosionCenter = damageSource.getSourcePosition();
                if (explosionCenter != null) {
                    Vec3 direction = entity.position().subtract(explosionCenter).normalize();
                    float distance = (float) entity.position().distanceTo(explosionCenter);
                    float baseStrength = Math.min(entity.getMaxHealth() / 10f, 5f);
                    float distanceFalloff = Math.max(0.5f, 1.0f - (distance / 10f));
                    float explosionStrength = baseStrength * distanceFalloff;
                    vel = new Vec3(
                            direction.x * 10.0 * explosionStrength,
                            direction.y * 8.0 * explosionStrength + 3.0,
                            direction.z * 10.0 * explosionStrength
                    );
                }
            }
        }

        return vel;
    }

    /**
     * Retire the oldest of a single player's death ragdolls until they're at or under the
     * per-player cap. Runs on the physics thread (called from processSpawnQueue), so destroy()
     * is safe here. Corpses aren't in the ragdolls map, so they're inherently excluded.
     */
    private static void enforceMaxRagdollsPerPlayer(UUID playerUUID) {
        int max = RagdollifiedConfig.getMaxRagdollsPerPlayer();
        while (true) {
            int count = 0;
            ClientRagdoll oldest = null;
            int oldestTicks = -1;
            for (ClientRagdoll r : ragdolls.values()) {
                if (r.isPlayer() && playerUUID.equals(r.getPlayerUUID()) && !r.isDestroyed()) {
                    count++;
                    if (r.getTicksExisted() > oldestTicks) {
                        oldestTicks = r.getTicksExisted();
                        oldest = r;
                    }
                }
            }
            if (count <= max || oldest == null) break;
            oldest.destroy();
            processedEntityIds.remove(oldest.getOriginalEntityId());
            ragdolls.remove(oldest.getOriginalEntityId());
        }
    }

    private static void enforceMaxRagdolls() {
        int max = RagdollifiedConfig.getMaxRagdolls();
        while (ragdolls.size() >= max) {
            // Find the oldest ragdoll by ticksExisted (no insertion order in ConcurrentHashMap).
            ClientRagdoll oldest = null;
            int oldestTicks = -1;
            for (ClientRagdoll r : ragdolls.values()) {
                if (r.getTicksExisted() > oldestTicks) {
                    oldestTicks = r.getTicksExisted();
                    oldest = r;
                }
            }
            if (oldest == null) break;
            oldest.destroy();
            processedEntityIds.remove(oldest.getOriginalEntityId());
            ragdolls.remove(oldest.getOriginalEntityId());
        }
    }

    /**
     * @deprecated unsafe — constructs the ClientRagdoll on the calling thread, which
     * means jbullet body insertion races the physics worker. Use {@link #enqueueSpawn}.
     * Kept for compile compatibility; no longer called by mod code.
     */
    @Deprecated
    public static void addRagdoll(int id, ClientRagdoll ragdoll) {
        // Best-effort: if called, just put in map. The ragdoll's bodies were added on
        // whatever thread called us, which is the bug we're warning about.
        ClientRagdoll existing = ragdolls.put(id, ragdoll);
        if (existing != null && existing != ragdoll) {
            // Can't safely destroy here — caller may be on wrong thread. Leak is preferred
            // over a crash. This API shouldn't be called.
            Ragdollified.LOGGER.warn("addRagdoll called — physics may corrupt. Use enqueueSpawn.");
        }
        processedEntityIds.add(ragdoll.getOriginalEntityId());
    }

    public static ClientRagdoll get(int id) { return ragdolls.get(id); }
    public static Collection<ClientRagdoll> getAll() { return ragdolls.values(); }

    /**
     * @deprecated unsafe across threads — destroys jbullet bodies on the caller's
     * thread. Avoid using; deferred destroy happens automatically when the spawn
     * queue replaces an existing ragdoll, or on lifetime expiry inside the tick.
     */
    @Deprecated
    public static void remove(int id) {
        ClientRagdoll ragdoll = ragdolls.remove(id);
        if (ragdoll != null) {
            ragdoll.destroy();
            processedEntityIds.remove(ragdoll.getOriginalEntityId());
        }
    }

    public static boolean hasRagdollFor(int entityId) {
        return processedEntityIds.contains(entityId);
    }

    public static void clear() {
        for (ClientRagdoll ragdoll : ragdolls.values()) ragdoll.destroy();
        ragdolls.clear();
        processedEntityIds.clear();
        spawnQueue.clear();
    }

    public static void onWorldUnload() {
        // Stop accepting new ticks first.
        ExecutorService old = physicsExecutor;
        physicsExecutor = newPhysicsExecutor();
        old.shutdown();
        try {
            // Wait briefly for the current tick to finish so we don't tear down jbullet
            // out from under it. If it doesn't finish in time, force shutdown.
            if (!old.awaitTermination(2, TimeUnit.SECONDS)) {
                old.shutdownNow();
            }
        } catch (InterruptedException e) {
            old.shutdownNow();
            Thread.currentThread().interrupt();
        }
        physicsBusy.set(false);
        physicsBroken = false; // fresh world gets a fresh start
        impulseQueue.clear();
        blockChangeQueue.clear();
        removeByRagdollIdQueue.clear();
        clear();
        RagdollHitTracker.clear();
        ClientPlayerSkinCache.clear();
        ClientJbulletWorld.onWorldUnload();
    }

    /** @deprecated use {@link #enqueueBlockChange(BlockPos)} — direct call races with physics thread. */
    @Deprecated
    public static void onBlockChanged(BlockPos pos) {
        enqueueBlockChange(pos);
    }

    public static int getActiveCount() { return ragdolls.size(); }
}
