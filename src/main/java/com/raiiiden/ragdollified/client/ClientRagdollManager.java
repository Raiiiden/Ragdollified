package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.physics.ContactPair;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.api.DragEnd;
import com.raiiiden.ragdollified.api.DragTarget;
import com.raiiiden.ragdollified.api.RagdollSettleEvent;
import com.raiiiden.ragdollified.api.RagdollSettleListener;
import com.raiiiden.ragdollified.api.RagdollSpawnTransform;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollStatePacket;
import com.raiiiden.ragdollified.network.RagdollStreamPacket;
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
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@OnlyIn(Dist.CLIENT)
public class ClientRagdollManager {

    // Owned by the physics thread; the render thread iterates values() safely (weakly consistent,
    // so a spawn can show a frame late). Force-settle orders by ticksExisted, not insertion order.
    private static final Map<Integer, ClientRagdoll> ragdolls = new ConcurrentHashMap<>();

    // Performance logging: logs every 100 ticks (~5 seconds).
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
    // Previous tick's active count, used to size the collision-geometry budget at the
    // start of this tick (before the state pass has recounted).
    private static int lastActiveRagdollCount = 0;
    // Worst-tick tracker: captures the slowest tickAll inside the 100-tick window so
    // periodic spikes that happen between log intervals are still visible.
    private static long worstTickAllNanos = 0;
    private static int worstTickActiveCount = 0;
    private static int worstTickManifoldCount = 0;
    private static final IdentityHashMap<PhysicsBody, ClientRagdoll> bodyOwners = new IdentityHashMap<>();
    private static final Set<ClientRagdoll> correctedRagdolls =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Vector3f penetrationNormal = new Vector3f();
    private static final Vector3f contactNormal = new Vector3f();
    private static final Vector3f penetrationVelocity = new Vector3f();
    private static final float PENETRATION_DEPTH_THRESHOLD = -0.15f;
    private static final float MAX_GROUP_PENETRATION_CORRECTION = 0.08f;

    // Every tickAll in the 100-tick window, so the log can show min/max/avg/p99: one sampled value
    // hides the single 50 ms spike among 5 ms ticks that is the only thing the player feels.
    private static final long[] tickAllRing = new long[100];
    private static int tickAllRingFilled = 0;

    // GC pause tracking; JBullet allocates heavily per step and these pauses don't show in phase timers.
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

    // Cap woken settled ragdolls per tick: a landing pile can chain-wake itself in a single tick
    // and dump 10+ active bodies into the solver.
    private static final int MAX_WAKES_PER_TICK = 3;

    // Reused per tick to avoid re-walking the full ragdoll collection inside the wake loop.
    private static final List<ClientRagdoll> wakeMovers = new ArrayList<>();
    private static final List<ClientRagdoll> wakeSettled = new ArrayList<>();

    // Spawn queue. Building N ragdolls in one tick means N*6 bodies and N*5 joints through
    // broadphase at once, so each death queues one snapshot per entity id, drained at a config rate.
    private static final ConcurrentHashMap<Integer, ClientRagdoll.SpawnData> pendingSpawns =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> spawnOrder = new ConcurrentLinkedQueue<>();

    // Cross-thread input queues: clicks, block changes and packets are enqueued from main/render
    // and drained by the physics thread at the start of each tick.
    public static final class ImpulseRequest {
        public final int ragdollId;
        public final int partIndex;
        public final float x, y, z;
        public final int revision;
        public final boolean apply;
        // World-space impact point (NaN if unknown), the lever the body turns about.
        public final double impactX, impactY, impactZ;

        public ImpulseRequest(int ragdollId, int partIndex, float x, float y, float z,
                              int revision, boolean apply) {
            this(ragdollId, partIndex, x, y, z, revision, apply, Double.NaN, Double.NaN, Double.NaN);
        }

        public ImpulseRequest(int ragdollId, int partIndex, float x, float y, float z,
                              int revision, boolean apply,
                              double impactX, double impactY, double impactZ) {
            this.ragdollId = ragdollId; this.partIndex = partIndex;
            this.x = x; this.y = y; this.z = z;
            this.revision = revision; this.apply = apply;
            this.impactX = impactX; this.impactY = impactY; this.impactZ = impactZ;
        }

        Vector3f impactPoint() {
            if (!Double.isFinite(impactX) || !Double.isFinite(impactY) || !Double.isFinite(impactZ)) {
                return null;
            }
            return new Vector3f((float) impactX, (float) impactY, (float) impactZ);
        }
    }
    private static final ConcurrentLinkedQueue<ImpulseRequest> impulseQueue = new ConcurrentLinkedQueue<>();
    public static final class AuthoritativeState {
        public final RagdollTransform[] transforms;
        public final int ageTicks;
        public final boolean settled;

        public AuthoritativeState(RagdollTransform[] transforms, int ageTicks, boolean settled) {
            this.transforms = transforms;
            this.ageTicks = ageTicks;
            this.settled = settled;
        }
    }
    private static final ConcurrentHashMap<Integer, AuthoritativeState> authoritativeStates =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<BlockPos> blockChangeQueue = new ConcurrentLinkedQueue<>();
    // Handoff: an arriving replacement body queues the one ragdoll it replaced (matched by ragdoll
    // entity id) for the physics thread to destroy, so an older replacement cannot cull a newer body.
    private static final ConcurrentLinkedQueue<Integer> removeByRagdollIdQueue = new ConcurrentLinkedQueue<>();

    // Public API drag targets, deliberately targets rather than impulses: one request can drive
    // several limbs and is consumed whole before the next step, so paired limbs move together.
    private static final class DragRequest {
        final Map<RagdollPart, Vec3> partTargets;
        final DragEnd end;
        final DragTarget endTarget;

        private DragRequest(Map<RagdollPart, Vec3> partTargets, DragEnd end, DragTarget endTarget) {
            this.partTargets = partTargets;
            this.end = end;
            this.endTarget = endTarget;
        }

        static DragRequest forParts(Map<RagdollPart, Vec3> targets) {
            EnumMap<RagdollPart, Vec3> copy = new EnumMap<>(RagdollPart.class);
            if (targets != null) {
                for (Map.Entry<RagdollPart, Vec3> entry : targets.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) copy.put(entry.getKey(), entry.getValue());
                }
            }
            return copy.isEmpty() ? null : new DragRequest(Map.copyOf(copy), null, null);
        }

        static DragRequest forEnd(DragEnd end, DragTarget target) {
            return end == null || target == null ? null : new DragRequest(Map.of(), end, target);
        }
    }
    // No entry means local-only simulation, such as on a vanilla server.
    private static final ConcurrentHashMap<Integer, Boolean> streamOwnership = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<StreamedPoseUpdate> streamPoseQueue = new ConcurrentLinkedQueue<>();
    // A spawn packet and its first owner pose can land in the same tick, and inputs drain before
    // construction, so keep the newest early pose rather than dropping the exact initial state.
    private static final ConcurrentHashMap<Integer, StreamedPoseUpdate> pendingStreamPoses =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> streamSendSequences = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> lastStreamSampleTicks =
            new ConcurrentHashMap<>();

    private record StreamedPoseUpdate(int entityId, RagdollTransform[] transforms,
                                      int sequence, int sampleTick, boolean hardSync) {}

    private static final ConcurrentHashMap<Integer, DragRequest> dragTargets = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> endedDragIds = new ConcurrentLinkedQueue<>();
    // Flail requests. Joints may only be written on the physics worker, so an API call from the
    // client thread parks the request here and the drain applies it, exactly as impulses do.
    private static final ConcurrentLinkedQueue<FlailRequest> flailQueue = new ConcurrentLinkedQueue<>();

    private record FlailRequest(int ragdollId, int durationTicks, int intervalTicks,
                                float spreadRadians, float strengthScale) {}
    private static final ConcurrentLinkedQueue<Integer> deathLifetimeRestarts = new ConcurrentLinkedQueue<>();
    // Bodies handed back at death must report a real settle first, so the age-based give-up shortcut
    // is switched off for them. Written on the caller's thread, ahead of the physics worker restart.
    private static final Set<Integer> awaitingRealSettle = ConcurrentHashMap.newKeySet();

    // API spawns deliberately bypass the user's automatic-death enable list, but still
    // require a model type Ragdollified can actually construct and render.
    private static final Set<Integer> forcedSpawnIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> coordinatedSpawnIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> persistentRagdollIds = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, Boolean> pendingPersistenceUpdates = new ConcurrentHashMap<>();

    // Explicit visibility overrides are client-local. They are separate from the automatic
    // dead-entity hiding done by HideDeadEntityMixin.
    private static final Set<Integer> explicitlyHiddenEntityIds = ConcurrentHashMap.newKeySet();

    // Per-id generation pins each replacement to one body, since respawns reuse entity ids.
    private static final Map<Integer, Integer> playerRagdollGenerations = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> handoffGenerations = new ConcurrentHashMap<>();

    // Settle reporting is entirely addon-driven: with no listener registered it costs nothing.
    private static final CopyOnWriteArrayList<RagdollSettleListener> settleListeners =
            new CopyOnWriteArrayList<>();
    private static volatile int settleDeadlineTicks = Integer.MAX_VALUE;
    private static volatile boolean playerRagdollCullingSuppressed = false;

    // Single-thread daemon executor running all physics work, so the render thread never blocks:
    // submitTick() is called from ClientTickEvent and hands the work off.
    private static volatile ExecutorService physicsExecutor = newPhysicsExecutor();
    private static final AtomicBoolean physicsBusy = new AtomicBoolean(false);

    private static ExecutorService newPhysicsExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ragdollified-Physics");
            t.setDaemon(true);
            return t;
        });
    }

    // Once the physics world's internal state corrupts every later step throws, so a crash is recovered from
    // (handlePhysicsCrash) and only latches the worker off after MAX_PHYSICS_RECOVERIES attempts.
    private static volatile boolean physicsBroken = false;
    private static final int MAX_PHYSICS_RECOVERIES = 3;
    private static int physicsRecoveries = 0;

    // Client ticks since load, stamped on snapshots so render knows how much time each covers.
    private static volatile int clientTick = 0;
    private static int droppedTicks = 0;

    public static int clientTick() {
        return clientTick;
    }

    // Submit a physics tick to the worker at 20 Hz, dropped if the previous one is still running.
    // Snapshots carry elapsed client ticks so the renderer spreads motion instead of jumping.
    public static void submitTick() {
        if (physicsBroken) return;
        clientTick++;
        if (!physicsBusy.compareAndSet(false, true)) {
            droppedTicks++;
            return;
        }
        physicsExecutor.execute(() -> {
            try {
                tickAll();
            } catch (Throwable t) {
                handlePhysicsCrash(t);
            } finally {
                physicsBusy.set(false);
            }
        });
    }

    // Recover from a crash inside tickAll by dropping every body and discarding the corrupted world,
    // so the next tick builds a clean one. Physics worker only, and bounded per world load.
    private static void handlePhysicsCrash(Throwable t) {
        if (physicsRecoveries >= MAX_PHYSICS_RECOVERIES) {
            if (!physicsBroken) {
                physicsBroken = true;
                Ragdollified.LOGGER.error(
                        "Ragdoll physics crashed {} times — disabling worker until world reload. "
                      + "This usually means the physics world's state was corrupted by "
                      + "a cross-thread modification.", physicsRecoveries, t);
            }
            return;
        }
        physicsRecoveries++;
        Ragdollified.LOGGER.error(
                "Ragdoll physics crashed (recovery {}/{}) — dropping every body and rebuilding "
              + "the physics world. Existing ragdolls are lost; new deaths will ragdoll again.",
                physicsRecoveries, MAX_PHYSICS_RECOVERIES, t);

        // Tear the bodies down one at a time: destroy() reaches into the world that just threw,
        // so one unrecoverable body must not abort the teardown of the rest.
        for (ClientRagdoll ragdoll : ragdolls.values()) {
            try {
                ragdoll.destroy();
            } catch (Throwable ignored) {
                // Dropped with the world instance below either way.
            }
        }
        ragdolls.clear();
        try {
            ClientDetachedLimbManager.clear();
        } catch (Throwable ignored) {
            // Dropped with the world instance below either way.
        }
        severQueue.clear();
        try {
            clear();
        } catch (Throwable ignored) {
            // Already emptied above; the remaining bookkeeping is cleared below.
        }
        pendingSpawns.clear();
        spawnOrder.clear();
        impulseQueue.clear();
        blockChangeQueue.clear();
        removeByRagdollIdQueue.clear();
        streamPoseQueue.clear();
        pendingStreamPoses.clear();
        endedDragIds.clear();
        flailQueue.clear();
        deathLifetimeRestarts.clear();
        awaitingRealSettle.clear();
        try {
            ClientPhysicsWorld.onWorldUnload();
        } catch (Throwable disposeFailure) {
            Ragdollified.LOGGER.error("Failed to dispose the crashed physics world", disposeFailure);
        }
    }

    // Amputation

    // One pending 'take this part off that body' request for the physics thread.
    public static final class SeverRequest {
        final int entityId;
        final int partIndex;
        final int limbId;
        final int limbLifetimeTicks;
        final float impulseX, impulseY, impulseZ;
        // Wait a few ticks for the body to be built, since a death amputation can arrive before it exists.
        int attemptsLeft;

        public SeverRequest(int entityId, int partIndex, int limbId, int limbLifetimeTicks,
                            float impulseX, float impulseY, float impulseZ) {
            this(entityId, partIndex, limbId, limbLifetimeTicks, impulseX, impulseY, impulseZ,
                    SEVER_RETRY_TICKS);
        }

        public SeverRequest(int entityId, int partIndex, int limbId, int limbLifetimeTicks,
                            float impulseX, float impulseY, float impulseZ, int attemptsLeft) {
            this.entityId = entityId;
            this.partIndex = partIndex;
            this.limbId = limbId;
            this.limbLifetimeTicks = limbLifetimeTicks;
            this.impulseX = impulseX;
            this.impulseY = impulseY;
            this.impulseZ = impulseZ;
            this.attemptsLeft = attemptsLeft;
        }
    }

    // Two seconds. Long enough to cover a spawn queue under load, short enough that a request for a
    // body that never arrives (the client was out of range when it died) is not held for ever.
    private static final int SEVER_RETRY_TICKS = 40;

    private static final ConcurrentLinkedQueue<SeverRequest> severQueue = new ConcurrentLinkedQueue<>();

    // Queue an amputation for the physics thread. Any thread; see RagdollAmputationApi.
    public static void enqueueSever(SeverRequest request) {
        if (request != null) severQueue.offer(request);
    }

    // Physics thread; runs after the spawn queue and before the limb manager builds.
    private static void drainSeverQueue() {
        if (severQueue.isEmpty()) return;
        // Deferred requests go into a holding list and are put back after the drain: re-offering
        // mid-loop would keep handing the same request back to this same pass, for ever.
        List<SeverRequest> retry = null;
        SeverRequest request;
        while ((request = severQueue.poll()) != null) {
            RagdollPart part = RagdollPart.byIndex(request.partIndex);
            if (part == null) continue;
            ClientRagdoll ragdoll = ragdolls.get(request.entityId);
            if (ragdoll == null || ragdoll.isDestroyed()) {
                // Only wait on a body that has not been built yet. One that existed and is gone is
                // gone, and a queued spawn is the only reason to expect one to appear.
                if (request.attemptsLeft > 0 && ragdoll == null) {
                    request.attemptsLeft--;
                    if (retry == null) retry = new ArrayList<>(2);
                    retry.add(request);
                }
                continue;
            }

            ClientRagdoll.SeveredPart severed;
            try {
                severed = ragdoll.severPart(part);
            } catch (Throwable t) {
                Ragdollified.LOGGER.error("Failed to sever {} from ragdoll {}", part, request.entityId, t);
                continue;
            }
            if (severed == null) continue;

            ClientDetachedLimb.SpawnData limb = new ClientDetachedLimb.SpawnData(
                    request.limbId, request.entityId, part,
                    ragdoll.getModelType(), ragdoll.getMobType(),
                    ragdoll.isPlayer(), ragdoll.isBaby(), ragdoll.getScale(), ragdoll.getPlayerUUID(),
                    new Vec3(severed.position.x, severed.position.y, severed.position.z),
                    severed.rotation,
                    new Vec3(severed.linearVelocity.x + request.impulseX,
                             severed.linearVelocity.y + request.impulseY,
                             severed.linearVelocity.z + request.impulseZ),
                    new Vec3(severed.angularVelocity.x, severed.angularVelocity.y, severed.angularVelocity.z),
                    severed.halfExtents,
                    request.limbLifetimeTicks);
            limb.texture = ragdoll.getCachedTexture();
            ClientDetachedLimbManager.enqueueSpawn(limb);
        }
        if (retry != null) severQueue.addAll(retry);
    }

    // Enqueue a punch/click impulse for the physics thread to apply on its next tick.
    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z) {
        enqueueImpulse(ragdollId, partIndex, x, y, z, 0, true);
    }

    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z,
                                      int revision, boolean apply) {
        impulseQueue.offer(new ImpulseRequest(ragdollId, partIndex, x, y, z, revision, apply));
    }

    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z,
                                      int revision, boolean apply,
                                      double impactX, double impactY, double impactZ) {
        impulseQueue.offer(new ImpulseRequest(ragdollId, partIndex, x, y, z, revision, apply,
                impactX, impactY, impactZ));
    }

    // Destroy a specific physics ragdoll (by entity id) on the physics thread (addon handoff).
    // Queue a flail, or stop one with durationTicks <= 0. Safe from any thread.
    public static void requestFlail(int entityId, int durationTicks, int intervalTicks,
                                    float spreadRadians, float strengthScale) {
        flailQueue.offer(new FlailRequest(entityId, durationTicks, intervalTicks,
                spreadRadians, strengthScale));
    }

    public static void requestRemoveRagdoll(int entityId) {
        dragTargets.remove(entityId);
        pendingSpawns.remove(entityId);
        forcedSpawnIds.remove(entityId);
        coordinatedSpawnIds.remove(entityId);
        persistentRagdollIds.remove(entityId);
        pendingPersistenceUpdates.remove(entityId);
        authoritativeStates.remove(entityId);
        streamOwnership.remove(entityId);
        pendingStreamPoses.remove(entityId);
        streamSendSequences.remove(entityId);
        lastStreamSampleTicks.remove(entityId);
        removeByRagdollIdQueue.offer(entityId);
    }

    // Start (or replace) a no-impulse drag for one ragdoll part.
    public static boolean beginDrag(int entityId, RagdollPart part, Vec3 target) {
        return beginDrag(entityId, part == null || target == null ? null : Map.of(part, target));
    }

    // Start a no-impulse drag that moves every supplied part atomically.
    public static boolean beginDrag(int entityId, Map<RagdollPart, Vec3> targets) {
        DragRequest request = DragRequest.forParts(targets);
        if (request == null || !hasPendingOrActiveRagdoll(entityId)) return false;
        dragTargets.put(entityId, request);
        return true;
    }

    // Start a paired arm or leg drag. Target offsets are calculated on the physics worker.
    public static boolean beginDrag(int entityId, DragEnd end, DragTarget target) {
        DragRequest request = DragRequest.forEnd(end, target);
        if (request == null || !hasPendingOrActiveRagdoll(entityId)) return false;
        dragTargets.put(entityId, request);
        return true;
    }

    // Update an existing single-part drag target.
    public static boolean updateDrag(int entityId, Vec3 target) {
        DragRequest current = dragTargets.get(entityId);
        if (target == null || current == null || current.partTargets.size() != 1) return false;
        return updateDrag(entityId, Map.of(current.partTargets.keySet().iterator().next(), target));
    }

    // Replace all direct limb targets as one atomic update.
    public static boolean updateDrag(int entityId, Map<RagdollPart, Vec3> targets) {
        DragRequest request = DragRequest.forParts(targets);
        if (request == null || !dragTargets.containsKey(entityId) || !hasPendingOrActiveRagdoll(entityId)) return false;
        dragTargets.put(entityId, request);
        return true;
    }

    // Update the anchor for a paired arm/leg drag.
    public static boolean updateDrag(int entityId, DragTarget target) {
        DragRequest current = dragTargets.get(entityId);
        if (target == null || current == null || current.end == null || !hasPendingOrActiveRagdoll(entityId)) return false;
        dragTargets.replace(entityId, current, DragRequest.forEnd(current.end, target));
        return true;
    }

    // Stop a drag. The selected part then resumes normal simulation.
    public static void endDrag(int entityId) {
        if (dragTargets.remove(entityId) != null) endedDragIds.offer(entityId);
    }

    public static boolean isDragging(int entityId) {
        return dragTargets.containsKey(entityId) && hasRagdollFor(entityId);
    }

    // Turn an integration-owned body into a fresh death ragdoll in place: the pose survives, the
    // lifetime restarts, and it reports its rest pose so a replacement is built from where it lies.
    public static void restartDeathLifetime(int entityId) {
        if (!hasPendingOrActiveRagdoll(entityId)) return;
        // Hold the settle report here on the caller's thread as well: until the physics worker drains
        // the restart the body still reads as ancient and unreported, and main ticks inside that gap.
        awaitingRealSettle.add(entityId);
        deathLifetimeRestarts.offer(entityId);
    }

    // Set a ragdoll's externally controlled lifetime. Safe before its queued spawn is built.
    public static void setPersistent(int entityId, boolean persistent) {
        if (persistent) persistentRagdollIds.add(entityId);
        else persistentRagdollIds.remove(entityId);
        pendingPersistenceUpdates.put(entityId, persistent);
    }

    public static boolean isPersistent(int entityId) {
        return persistentRagdollIds.contains(entityId);
    }

    // Max age of a captured pose to count as the death pose (three frames at 60fps).
    private static final long POSE_FRESHNESS_MS = 50L;

    // Pose to build from: the last drawn frame, or posed on demand if missing or stale.
    public static MobPoseCapture.MobPose resolvePose(LivingEntity entity, MobModelHelper.ModelType modelType) {
        if (entity == null) return null;

        int entityId = entity.getId();
        if (MobPoseCapture.getPoseAgeMs(entityId) > POSE_FRESHNESS_MS) {
            ClientMobPoseCapture.captureNow(entity, modelType);
        }
        return MobPoseCapture.getPose(entityId);
    }

    // The ragdoll nearest a point, for the debug command. Null when there are none.
    public static ClientRagdoll nearestRagdoll(net.minecraft.world.phys.Vec3 point) {
        ClientRagdoll nearest = null;
        double best = Double.MAX_VALUE;
        for (ClientRagdoll ragdoll : ragdolls.values()) {
            javax.vecmath.Vector3f torso = ragdoll.getTorsoPosition();
            double distSq = point.distanceToSqr(torso.x, torso.y, torso.z);
            if (distSq < best) {
                best = distSq;
                nearest = ragdoll;
            }
        }
        return nearest;
    }

    // Snapshot what the installed damage-visual mods are drawing on an entity so the ragdoll can
    // reproduce it. Called from every spawn path, since whichever runs first still sees the entity.
    public static void captureCompatVisuals(LivingEntity entity) {
        if (entity == null) return;
        // Better Blood Overlay captures mobs every frame through EntityRenderCaptureHandler but skips
        // players, so players are captured here while their wounds are still registered.
        if (entity instanceof Player) {
            com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.capture(entity.getId(), entity);
            // Curios: an addon that loots the body empties the curio slots, so the worn set is
            // read now. Only the player path renders them, so mobs are not captured.
            com.raiiiden.ragdollified.client.compat.CuriosRenderCompat.capture(entity.getId(), entity);
        }
        // Visual Health: one snapshot of the damage tier covers players and mobs alike.
        com.raiiiden.ragdollified.client.compat.VisualHealthCompat.capture(entity.getId(), entity);
    }

    public static boolean spawnFromEntity(LivingEntity entity) {
        return spawnFromEntity(entity, false, null);
    }

    public static boolean spawnFromEntity(LivingEntity entity, boolean persistent) {
        return spawnFromEntity(entity, persistent, null);
    }

    // Queue a clean ragdoll using an optional immutable authoritative transform.
    public static boolean spawnFromEntity(LivingEntity entity, boolean persistent, RagdollSpawnTransform spawnTransform) {
        if (entity == null || hasPendingOrActiveRagdoll(entity.getId())) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entity.level() != mc.level) return false;

        boolean isPlayer = entity instanceof Player;
        String mobType = EntityType.getKey(entity.getType()).toString();
        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isSupportedModelType(modelType)) return false;

        boolean wasSheared = entity instanceof net.minecraft.world.entity.animal.Sheep sheep && sheep.isSheared();
        int dyeColorId = entity instanceof net.minecraft.world.entity.animal.Sheep sheep
                ? sheep.getColor().getId() : 0;
        if (entity instanceof net.minecraft.world.entity.animal.Wolf wolf) {
            wasSheared = wolf.isTame();
            dyeColorId = wolf.getCollarColor().getId();
        }
        if (entity instanceof net.minecraft.world.entity.animal.goat.Goat goat) {
            dyeColorId = (goat.hasLeftHorn() ? 1 : 0) | (goat.hasRightHorn() ? 2 : 0);
        }
        if (entity instanceof net.minecraft.world.entity.animal.Turtle turtle) {
            wasSheared = turtle.hasEgg();
        }
        if (entity instanceof net.minecraft.world.entity.animal.SnowGolem snowGolem) {
            wasSheared = snowGolem.hasPumpkin();
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractChestedHorse horse) {
            wasSheared = horse.hasChest();
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.Horse horse) {
            dyeColorId = horse.getMarkings().getId();
        }
        boolean chargedCreeper = entity instanceof net.minecraft.world.entity.monster.Creeper creeper
                && creeper.isPowered();
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) {
            chargedCreeper = horse.isSaddled();
        }
        boolean saddledPig = entity instanceof net.minecraft.world.entity.animal.Pig pig && pig.isSaddled();
        if (entity instanceof net.minecraft.world.entity.monster.Strider strider) saddledPig = strider.isSaddled();
        if (!isPlayer) EntityRenderCaptureHandler.captureRenderState(entity);
        ResourceLocation texture = !isPlayer ? ClientMobTextureCache.getTextureForDeadMob(entity.getId()) : null;

        ClientRagdoll.SpawnData data = new ClientRagdoll.SpawnData(
                entity.getId(), isPlayer, mobType, modelType,
                isPlayer ? 1.0f : entity.getBbHeight() / 1.8f,
                isPlayer ? entity.getUUID() : null,
                isPlayer ? entity.getName().getString() : "",
                entity.getItemBySlot(EquipmentSlot.HEAD).copy(),
                entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
                entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
                entity.getItemBySlot(EquipmentSlot.FEET).copy(),
                spawnTransform != null ? spawnTransform.position() : entity.position(),
                spawnTransform != null ? spawnTransform.bodyYaw() : entity.getVisualRotationYInDegrees(),
                spawnTransform != null ? spawnTransform.pitch() : entity.getXRot(),
                spawnTransform != null ? spawnTransform.velocity() : RagdollSpawnState.captureLinearVelocity(entity),
                resolvePose(entity, modelType),
                spawnTransform != null ? spawnTransform.swimming() : entity.getPose() == Pose.SWIMMING,
                entity.isBaby(), texture,
                -1, null, wasSheared, dyeColorId, chargedCreeper, saddledPig);
        if (persistent) persistentRagdollIds.add(entity.getId());
        boolean queued = enqueueSpawn(data, true);
        if (!queued) persistentRagdollIds.remove(entity.getId());
        // API spawns ragdollify a live entity without any death event, so this is their only
        // chance to snapshot the damage visuals.
        if (queued) captureCompatVisuals(entity);
        return queued;
    }

    public static void setEntityHidden(int entityId, boolean hidden) {
        if (hidden) explicitlyHiddenEntityIds.add(entityId);
        else explicitlyHiddenEntityIds.remove(entityId);
    }

    public static boolean isEntityExplicitlyHidden(int entityId) {
        return explicitlyHiddenEntityIds.contains(entityId);
    }

    // Main-thread settle bridge, live only while a listener is registered. Each body reports once,
    // and consumers must accept only the first report.
    public static void tickSettleReports() {
        if (settleListeners.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        for (ClientRagdoll r : ragdolls.values()) {
            if (!r.isPlayer() || r.getPlayerUUID() == null || r.isSettleReported()) continue;
            if (RagdollifiedConfig.hasServerSnapshot()
                    && !Boolean.TRUE.equals(streamOwnership.get(r.getOriginalEntityId()))) continue;

            // A towed body is not a stuck one: the drag re-arms the report every tick, so the age-based
            // give-up would fire for as long as the tow lasts. Waiting costs nothing, the drag ends first.
            if (isDragging(r.getId()) || r.isAwaitingSettleGrace()) continue;

            // Report when the ragdoll has settled, OR shortly before it would despawn / the listener's
            // own deadline expires, so a stuck ragdoll still reports its actual current pose.
            int limit = Math.min(RagdollifiedConfig.getRagdollLifetime(), settleDeadlineTicks);
            // A distance-frozen observer is not near the body, and a body handed over at death is old by
            // definition, so both wait for a real settle; the consumer's own deadline covers the rest.
            boolean nearGiveUp = !r.isFrozen() && !awaitingRealSettle.contains(r.getId())
                    && r.getTicksExisted() >= Math.max(20, limit - 40);
            if (!r.isSettled() && !nearGiveUp) continue;

            ClientRagdoll.TransformSnapshot snap = r.getSnapshot();
            if (snap == null) continue;
            Vector3f origin = snap.cachedTorsoPos;
            RagdollTransform[] rel = new RagdollTransform[RagdollTransform.MAX_PARTS];
            for (int i = 0; i < RagdollTransform.MAX_PARTS && i < snap.positions.length; i++) {
                Vector3f p = snap.positions[i];
                if (p == null) continue;
                rel[i] = new RagdollTransform(i,
                        new Vector3f(p.x - origin.x, p.y - origin.y, p.z - origin.z),
                        new Quat4f(snap.rotations[i]));
            }
            RagdollSettleEvent event = new RagdollSettleEvent(r.getOriginalEntityId(), r.getPlayerUUID(),
                    new Vec3(origin.x, origin.y, origin.z), rel, r.getLastImpulseRevision(), !r.isSettled());
            for (RagdollSettleListener listener : settleListeners) {
                try {
                    listener.onRagdollSettled(event);
                } catch (Throwable t) {
                    Ragdollified.LOGGER.error("Settle listener {} failed", listener.getClass().getName(), t);
                }
            }
            r.markSettleReported();
            awaitingRealSettle.remove(r.getId());
        }
    }

    public static void addSettleListener(RagdollSettleListener listener) {
        if (listener != null) settleListeners.addIfAbsent(listener);
    }

    public static boolean removeSettleListener(RagdollSettleListener listener) {
        return listener != null && settleListeners.remove(listener);
    }

    // Deadline for the age-based give-up above. Lowered rather than replaced, so several addons
    // asking for different deadlines all get a report inside the one they asked for.
    public static void setSettleDeadlineTicks(int ticks) {
        if (ticks > 0) settleDeadlineTicks = Math.min(settleDeadlineTicks, ticks);
    }

    // Hand a settled player ragdoll to an addon's replacement body, re-keying its visuals first.
    // Owner UUID and a per-key generation stop a recycled entity id from being adopted.
    public static boolean handOffPlayerRagdoll(int ragdollEntityId, UUID expectedOwner, UUID replacementKey) {
        if (ragdollEntityId < 0 || replacementKey == null) return false;
        int handoffGeneration = handoffGenerations.computeIfAbsent(replacementKey,
                ignored -> playerRagdollGenerations.getOrDefault(ragdollEntityId, 0));
        // A newer generation means the id was recycled by a later death, which gets its own
        // replacement. A key first seen after the body it replaces simply never hands off.
        if (handoffGeneration != playerRagdollGenerations.getOrDefault(ragdollEntityId, 0)) return false;
        ClientRagdoll r = ragdolls.get(ragdollEntityId);
        if (r == null || r.isDestroyed() || !r.isPlayer()) return false;
        if (expectedOwner != null && !expectedOwner.equals(r.getPlayerUUID())) return false;
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.transferTo(ragdollEntityId, replacementKey);
        com.raiiiden.ragdollified.client.compat.VisualHealthCompat.transferTo(ragdollEntityId, replacementKey);
        requestRemoveRagdoll(ragdollEntityId);
        return true;
    }

    // A player ragdoll an addon is going to replace with its own persistent body stands for that
    // body until it materializes, so the cosmetic per-player limit must not cull it meanwhile.
    public static void setPlayerRagdollCullingSuppressed(boolean suppressed) {
        playerRagdollCullingSuppressed = suppressed;
    }

    // Server→client ownership assignment. Main thread.
    public static void enqueueStreamOwnership(int entityId, boolean owner) {
        Boolean previous = streamOwnership.put(entityId, owner);
        if (!owner || !Boolean.TRUE.equals(previous)) {
            streamSendSequences.remove(entityId);
            lastStreamSampleTicks.remove(entityId);
        }
    }

    // Server→client relayed pose frame from the owning client. Main thread.
    public static void enqueueStreamedPose(int entityId, RagdollTransform[] transforms,
                                           int sequence, int sampleTick, boolean hardSync) {
        if (transforms == null) return;
        streamPoseQueue.offer(new StreamedPoseUpdate(
                entityId, transforms, sequence, sampleTick, hardSync));
    }

    // Players stream at 20 Hz and mobs at 10 Hz.
    public static void tickRagdollStreamClient() {
        if (streamOwnership.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (!ModNetwork.CHANNEL.isRemotePresent(mc.getConnection().getConnection())) return;

        for (Map.Entry<Integer, Boolean> entry : streamOwnership.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            ClientRagdoll ragdoll = ragdolls.get(entry.getKey());
            if (ragdoll == null || ragdoll.isDestroyed()) continue;
            // Once settled, RagdollStatePacket carries the single authoritative resting pose
            // that every observer ends on. Streaming a frozen body past that is pure waste.
            if (ragdoll.isSettled()) continue;

            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null || snap.destroyed) continue;
            Integer lastSampleTick = lastStreamSampleTicks.get(entry.getKey());
            int interval = 1;
            if (lastSampleTick != null && snap.sampleTick - lastSampleTick < interval) continue;
            RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
            for (int i = 0; i < transforms.length && i < snap.positions.length; i++) {
                if (snap.positions[i] == null || snap.rotations[i] == null) continue;
                transforms[i] = new RagdollTransform(i, snap.positions[i], snap.rotations[i]);
            }
            if (transforms[0] == null) continue; // no torso anchor to encode the rest against

            boolean hardSync = ragdoll.consumeStationaryHardSyncRequest();
            int sequence = streamSendSequences.merge(entry.getKey(), 1, Integer::sum);
            ModNetwork.CHANNEL.sendToServer(
                    new RagdollStreamPacket(
                            entry.getKey(), sequence, snap.sampleTick, hardSync, transforms));
            lastStreamSampleTicks.put(entry.getKey(), snap.sampleTick);
        }
    }

    // Report each ragdoll's first stable pose to a modded server, which keeps it for the
    // ragdoll lifetime and replays it to players who enter the area later.
    public static void tickRagdollSyncClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (!ModNetwork.CHANNEL.isRemotePresent(mc.getConnection().getConnection())) return;

        for (ClientRagdoll ragdoll : ragdolls.values()) {
            if (!ragdoll.isSettled() || ragdoll.isSettledPoseReported() || ragdoll.isDestroyed()) continue;
            if (Boolean.FALSE.equals(streamOwnership.get(ragdoll.getOriginalEntityId()))) {
                continue;
            }
            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null || snap.destroyed) continue;

            RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
            for (int i = 0; i < transforms.length && i < snap.positions.length; i++) {
                if (snap.positions[i] == null || snap.rotations[i] == null) continue;
                transforms[i] = new RagdollTransform(i, snap.positions[i], snap.rotations[i]);
            }
            ModNetwork.CHANNEL.sendToServer(new RagdollStatePacket(
                    ragdoll.getOriginalEntityId(), transforms,
                    ragdoll.getLastImpulseRevision(), 0, true));
        }
    }

    // Enqueue a block-change wake event. Block updates fire frequently; keep cheap.
    public static void enqueueBlockChange(BlockPos pos) {
        if (ragdolls.isEmpty()) return; // common case: no ragdolls, skip the alloc
        blockChangeQueue.offer(pos.immutable());
    }

    private static void drainInputQueues() {
        ImpulseRequest req;
        while ((req = impulseQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(req.ragdollId);
            if (r == null) continue;
            if (req.revision > 0 && req.revision <= r.getLastImpulseRevision()) continue;
            if (req.partIndex == RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX) {
                // A whole-body kick, from a blast going off beside a body that already exists.
                if (req.apply) r.applyVelocityKick(new Vec3(req.x, req.y, req.z));
            } else {
                RagdollPart part = RagdollPart.byIndex(req.partIndex);
                if (part == null) continue;
                if (req.apply) r.applyImpulse(part, new Vector3f(req.x, req.y, req.z), req.impactPoint());
            }
            if (req.revision > 0) r.acknowledgeImpulseRevision(req.revision);
        }
        // Handoff removals: destroy the specific physics ragdoll (by id) on the
        // physics thread. The post-tick loop drops destroyed ragdolls from the map.
        Integer ragId;
        while ((ragId = removeByRagdollIdQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(ragId);
            if (r != null && !r.isDestroyed()) r.destroy();
        }
        while ((ragId = endedDragIds.poll()) != null) {
            ClientRagdoll r = ragdolls.get(ragId);
            if (r != null && !r.isDestroyed()) r.endDrag();
        }
        FlailRequest flail;
        while ((flail = flailQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(flail.ragdollId());
            if (r == null || r.isDestroyed()) continue;
            if (flail.durationTicks() <= 0) r.stopFlail();
            else r.beginFlail(flail.durationTicks(), flail.intervalTicks(),
                    flail.spreadRadians(), flail.strengthScale());
        }
        while ((ragId = deathLifetimeRestarts.poll()) != null) {
            ClientRagdoll r = ragdolls.get(ragId);
            if (r != null && !r.isDestroyed()) r.restartDeathLifetime();
        }
        // Ownership is re-applied every tick rather than once on arrival: the assignment can
        // land before the body is built, and setReplicated is a no-op when nothing changed.
        for (Map.Entry<Integer, Boolean> entry : streamOwnership.entrySet()) {
            ClientRagdoll r = ragdolls.get(entry.getKey());
            if (r != null && !r.isDestroyed()) {
                r.setReplicated(!entry.getValue());
            }
        }
        StreamedPoseUpdate streamed;
        while ((streamed = streamPoseQueue.poll()) != null) {
            ClientRagdoll r = ragdolls.get(streamed.entityId());
            if (r == null || r.isDestroyed()) {
                if (pendingSpawns.containsKey(streamed.entityId())
                        || streamOwnership.containsKey(streamed.entityId())) {
                    pendingStreamPoses.merge(streamed.entityId(), streamed,
                            (oldPose, newPose) -> newPose.sequence() > oldPose.sequence()
                                    ? newPose : oldPose);
                }
                continue;
            }
            // A frame arriving before the ownership packet is itself proof this client is an
            // observer, so it doubles as the assignment and avoids a gap of local simulation.
            streamOwnership.putIfAbsent(streamed.entityId(), Boolean.FALSE);
            if (Boolean.TRUE.equals(streamOwnership.get(streamed.entityId()))) {
                // A newly elected owner may receive the retained last pose after its body was
                // already constructed. Seed the solver directly; never turn the owner into playback.
                r.applyOwnerHandoffPose(streamed.transforms());
            } else {
                r.setReplicated(true);
                r.applyStreamedPose(
                        streamed.transforms(), streamed.sequence(), streamed.sampleTick(),
                        streamed.hardSync());
            }
        }
        for (Map.Entry<Integer, Boolean> entry : pendingPersistenceUpdates.entrySet()) {
            ClientRagdoll r = ragdolls.get(entry.getKey());
            if (r != null && pendingPersistenceUpdates.remove(entry.getKey(), entry.getValue())) {
                r.setPersistent(entry.getValue());
            }
        }
        for (Map.Entry<Integer, DragRequest> entry : dragTargets.entrySet()) {
            ClientRagdoll r = ragdolls.get(entry.getKey());
            DragRequest drag = entry.getValue();
            if (r == null && pendingSpawns.containsKey(entry.getKey())) continue;
            if (r == null || r.isDestroyed()) {
                dragTargets.remove(entry.getKey(), drag);
                continue;
            }
            if (drag.end != null) r.dragEndTo(drag.end, drag.endTarget);
            else r.dragPartsTo(drag.partTargets);
        }
        for (Map.Entry<Integer, AuthoritativeState> entry : authoritativeStates.entrySet()) {
            // A newer server spawn snapshot is about to replace the current/local body.
            // Leave its retained state for processSpawnQueue to apply to that replacement.
            if (pendingSpawns.containsKey(entry.getKey())) continue;
            ClientRagdoll ragdoll = ragdolls.get(entry.getKey());
            if (ragdoll == null) continue;
            AuthoritativeState state = entry.getValue();
            if (authoritativeStates.remove(entry.getKey(), state)) {
                ragdoll.applyAuthoritativeState(state.transforms, state.ageTicks, state.settled);
            }
        }
        BlockPos pos;
        if (blockChangeQueue.isEmpty()) return;
        ClientPhysicsWorld physicsWorld = ClientPhysicsWorld.get(net.minecraft.client.Minecraft.getInstance().level);
        if (physicsWorld == null) return;
        int processed = 0;
        while ((pos = blockChangeQueue.poll()) != null) {
            // Drop the changed block collider and shape-dependent neighbors.
            physicsWorld.invalidateBlockChange(pos);
            // Drop stale handles and wake nearby frozen ragdolls.
            for (ClientRagdoll ragdoll : ragdolls.values()) {
                ragdoll.onBlockChangedNear(pos);
            }
            // And the loose limbs, which have their own terrain shells and their own parked state.
            ClientDetachedLimbManager.onBlockChangedNear(pos);
            processed++;
        }
        lastBlockChangesProcessed = processed;
    }

    // Hard cap on simultaneously active ragdolls, backstopping the solver's super-linear cost in
    // contact density. Over the cap the oldest are force-settled, still rendered and still wakeable.

    public static void tickAll() {
        if (ragdolls.isEmpty() && pendingSpawns.isEmpty() && ClientDetachedLimbManager.isEmpty()) return;
        long tickStart = System.nanoTime();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }

        ClientPhysicsWorld physicsWorld = ClientPhysicsWorld.get(level);
        physicsWorld.beginTick(lastActiveRagdollCount);

        // Drain cross-thread input queues, then the spawn queue, so all physics mutation happens on
        // this thread.
        long t0 = System.nanoTime();
        drainInputQueues();
        lastSpawnsThisTick = 0;
        if (!pendingSpawns.isEmpty()) {
            lastSpawnsThisTick = processSpawnQueue(physicsWorld);
        }
        drainSeverQueue();
        ClientDetachedLimbManager.prepare(physicsWorld);
        // Before the state pass, so a body a piston wakes is counted and stepped this tick.
        RagdollPistonPusher.apply(ragdolls.values());
        lastSpawnQueueDepth = pendingSpawns.size();
        lastSpawnQueueNanos = System.nanoTime() - t0;

        if (ragdolls.isEmpty()) {
            // Loose limbs outlive the body they came off, so a world holding only limbs still has
            // to be stepped. Everything below this point is per-ragdoll work with nothing to do.
            int movingLimbs = ClientDetachedLimbManager.getSimulatingCount();
            if (movingLimbs > 0) {
                physicsWorld.step(1f / 20f, movingLimbs);
            } else {
                physicsWorld.maintainCache();
            }
            ClientDetachedLimbManager.tick();
            physicsWorld.updateLiveCacheStats();
            lastActiveRagdollCount = 0;
            lastTickAllNanos = System.nanoTime() - tickStart;
            recordTickWorstCase(0, 0);
            maybeLogPerf(0, 0, 0, physicsWorld);
            return;
        }

        // State pass: count + collect wake lists.
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

        // Enforce the active cap. ConcurrentHashMap keeps no insertion order, so active ragdolls are
        // sorted by ticksExisted and the oldest retired: they have been jiggling longest.
        int maxActiveRagdolls = RagdollifiedConfig.get(RagdollifiedConfig.MAX_ACTIVE_RAGDOLLS);
        if (activeCount > maxActiveRagdolls) {
            int toRetire = activeCount - maxActiveRagdolls;
            List<ClientRagdoll> actives = new ArrayList<>(activeCount);
            for (ClientRagdoll r : ragdolls.values()) {
                if (r.isActivelySimulating()) actives.add(r);
            }
            actives.sort((a, b) -> Integer.compare(b.getTicksExisted(), a.getTicksExisted()));
            int retired = 0;
            for (ClientRagdoll ragdoll : actives) {
                if (retired >= toRetire) break;
                if (ragdoll.forceSettle()) {
                    retired++;
                    activeCount--;
                    settledCount++;
                }
            }
            lastForceSettledThisTick = retired;
            // Some retired ragdolls might have been on the wakeMovers list; drop any
            // that are no longer actively simulating so the wake loop doesn't process them.
            wakeMovers.removeIf(r -> !r.isActivelySimulating());
        } else {
            lastForceSettledThisTick = 0;
        }

        Vec3 cameraPos = mc.player != null ? mc.player.getEyePosition() : Vec3.ZERO;

        // Wake loop. Skipped at the active cap, where a wake would only be force-settled next tick,
        // pure oscillation, seen as forceSettled=2 / wakes=3 in one window.
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

        // Physics step.
        t0 = System.nanoTime();
        RagdollCollisionTracker.captureVelocities(ragdolls.values());
        int movingLimbs = ClientDetachedLimbManager.getSimulatingCount();
        if (activeCount > 0 || movingLimbs > 0) {
            physicsWorld.step(1f / 20f, Math.max(activeCount, 1));
        } else {
            // No dynamic bodies: skip stepSimulation() to avoid the broadphase
            // traversing all static block-geometry bodies (~5-10 ms wasted).
            physicsWorld.maintainCache();
        }
        lastPhysicsStepNanos = System.nanoTime() - t0;
        lastDynamicBodyCount = activeCount * 6; // 6 parts per ragdoll
        lastActiveRagdollCount = activeCount;

        // Contact stats after the step, so they reflect the density the solver actually resolved.
        // Cheap: the dispatcher already tracks this and we only iterate it.
        captureManifoldStats(physicsWorld);
        physicsWorld.updateLiveCacheStats();
        rebuildBodyOwners();
        // Only after a real step: without one the manifolds are unchanged and their contacts
        // would read as new again every tick.
        if (activeCount > 0) RagdollCollisionTracker.collect(physicsWorld, bodyOwners);
        long penetrationStart = System.nanoTime();
        correctInterpenetrations(physicsWorld);
        long penetrationNanos = System.nanoTime() - penetrationStart;

        // Post-tick per-ragdoll work (settle check, distance freeze, world collision update).
        // PHASE_STATS aggregates the sub-phase nanos across every ragdoll tick.
        t0 = System.nanoTime();
        ClientRagdoll.PHASE_STATS.reset();
        ClientRagdoll.PHASE_STATS.correctInterpenetrationsNanos = penetrationNanos;
        Iterator<Map.Entry<Integer, ClientRagdoll>> it = ragdolls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ClientRagdoll> entry = it.next();
            ClientRagdoll ragdoll = entry.getValue();
            ragdoll.tick(cameraPos);
            if (ragdoll.isDestroyed()) {
                it.remove();
                // Bodies that expire on their own never pass through requestRemoveRagdoll, so their
                // stream bookkeeping is released here or it accumulates for the session.
                streamOwnership.remove(entry.getKey());
                streamSendSequences.remove(entry.getKey());
                lastStreamSampleTicks.remove(entry.getKey());
            }
        }
        lastPostTickNanos = System.nanoTime() - t0;

        // After the step, like the ragdoll pass above, so a limb publishes the pose the step just
        // gave it rather than the one it had going in.
        ClientDetachedLimbManager.tick();

        lastTickAllNanos = System.nanoTime() - tickStart;
        recordTickWorstCase(activeCount, lastManifoldCount);
        maybeLogPerf(activeCount, settledCount, frozenCount, physicsWorld);
    }

    // Perf-log only, and it walks every manifold to get there, so it is gated on the log being on.
    private static void captureManifoldStats(ClientPhysicsWorld physicsWorld) {
        if (!RagdollifiedConfig.shouldLogPhysicsPerf()) {
            lastManifoldCount = 0;
            lastContactPointCount = 0;
            return;
        }
        lastManifoldCount = physicsWorld.getPhysics().manifoldCount();
        lastContactPointCount = physicsWorld.getPhysics().contactPointCount();
    }

    // Body to owning ragdoll, rebuilt once per tick and shared by interpenetration correction and
    // the collision API so neither has to walk every part again.
    private static void rebuildBodyOwners() {
        bodyOwners.clear();
        for (ClientRagdoll ragdoll : ragdolls.values()) {
            for (PhysicsBody body : ragdoll.ragdollParts) {
                bodyOwners.put(body, ragdoll);
            }
        }
    }

    // The ragdoll a body belongs to, or null for terrain and for proxies. Physics thread only: the
    // map is rebuilt at the top of each tick and read by the passes that follow it.
    static ClientRagdoll ownerOf(PhysicsBody body) {
        return bodyOwners.get(body);
    }

    private static void correctInterpenetrations(ClientPhysicsWorld physicsWorld) {
        correctedRagdolls.clear();
        physicsWorld.getPhysics().forEachContactPair(ClientRagdollManager::correctInterpenetration);

        for (ClientRagdoll ragdoll : correctedRagdolls) {
            ragdoll.applyGroupPenetrationCorrection(
                    MAX_GROUP_PENETRATION_CORRECTION, 0.92f);
        }
    }

    private static void correctInterpenetration(ContactPair pair) {
        int numContacts = pair.contactCount();
        if (numContacts == 0) return;

        PhysicsBody a = pair.bodyA();
        PhysicsBody b = pair.bodyB();
        ClientRagdoll ownerA = bodyOwners.get(a);
        ClientRagdoll ownerB = bodyOwners.get(b);
        if (ownerA == null && ownerB == null) return;
        if (ownerA != null && ownerA == ownerB) return;

        boolean aDynamic = a.getInvMass() > 0f;
        boolean bDynamic = b.getInvMass() > 0f;
        boolean bothDynamic = aDynamic && bDynamic;
        float damping = bothDynamic ? 0.92f : 0.5f;

        if (bothDynamic && ownerA != null && ownerB != null) {
            // Two ragdolls: correct the pair as wholes off their single deepest contact, rather than
            // shoving individual parts, so a pile does not tear bodies apart at the joints.
            int deepestIndex = -1;
            float deepestDistance = PENETRATION_DEPTH_THRESHOLD;
            for (int i = 0; i < numContacts; i++) {
                pair.selectContact(i);
                float distance = pair.distance();
                if (distance >= PENETRATION_DEPTH_THRESHOLD) continue;
                if (deepestIndex < 0 || distance < deepestDistance) {
                    deepestIndex = i;
                    deepestDistance = distance;
                }
            }
            if (deepestIndex < 0) return;
            pair.selectContact(deepestIndex);
            pair.getNormalOnB(contactNormal);
            float depth = Math.abs(deepestDistance);
            float correction = Math.min(depth * 1.5f, 0.2f) * 0.15f;
            orientGroupCorrectionNormal(ownerA, ownerB, contactNormal);
            ownerA.addGroupPenetrationCorrection(penetrationNormal, correction);
            ownerB.addGroupPenetrationCorrection(penetrationNormal, -correction);
            correctedRagdolls.add(ownerA);
            correctedRagdolls.add(ownerB);
            return;
        }

        for (int i = 0; i < numContacts; i++) {
            pair.selectContact(i);
            float distance = pair.distance();
            if (distance >= PENETRATION_DEPTH_THRESHOLD) continue;

            float depth = Math.abs(distance);
            float correction = bothDynamic
                    ? Math.min(depth * 1.5f, 0.2f) * 0.15f
                    : Math.min(depth * 0.35f, 0.05f);
            pair.getNormalOnB(penetrationNormal);
            penetrationNormal.scale(correction);
            if (aDynamic) a.translate(penetrationNormal);
            penetrationNormal.scale(-1f);
            if (bDynamic) b.translate(penetrationNormal);

            if (aDynamic) dampBody(a, damping);
            if (bDynamic) dampBody(b, damping);
        }
    }

    private static void orientGroupCorrectionNormal(
            ClientRagdoll ownerA, ClientRagdoll ownerB, Vector3f contactNormal) {
        Vector3f positionA = ownerA.getTorsoPosition();
        Vector3f positionB = ownerB.getTorsoPosition();
        float dx = positionA.x - positionB.x;
        float dy = positionA.y - positionB.y;
        float dz = positionA.z - positionB.z;
        penetrationNormal.set(contactNormal);
        if (dx * dx + dy * dy + dz * dz > 0.0025f) {
            if (penetrationNormal.x * dx + penetrationNormal.y * dy
                    + penetrationNormal.z * dz < 0f) {
                penetrationNormal.scale(-1f);
            }
            return;
        }

        int minId = Math.min(ownerA.getId(), ownerB.getId());
        int maxId = Math.max(ownerA.getId(), ownerB.getId());
        switch ((minId * 31 + maxId) & 3) {
            case 0 -> penetrationNormal.set(1f, 0f, 0f);
            case 1 -> penetrationNormal.set(-1f, 0f, 0f);
            case 2 -> penetrationNormal.set(0f, 0f, 1f);
            default -> penetrationNormal.set(0f, 0f, -1f);
        }
        if (ownerA.getId() != minId) penetrationNormal.scale(-1f);
    }

    private static void dampBody(PhysicsBody body, float damping) {
        body.getLinearVelocity(penetrationVelocity);
        penetrationVelocity.scale(damping);
        body.setLinearVelocity(penetrationVelocity);
        body.getAngularVelocity(penetrationVelocity);
        penetrationVelocity.scale(damping);
        body.setAngularVelocity(penetrationVelocity);
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
                                     ClientPhysicsWorld physicsWorld) {
        if (!RagdollifiedConfig.shouldLogPhysicsPerf()) {
            droppedTicks = 0;
            return;
        }
        perfTickCounter++;
        if (perfTickCounter < 100) return;
        perfTickCounter = 0;

        // Sample GC counters once per window; diff against last sample.
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
        ClientPhysicsWorld.CacheStats cs = physicsWorld.cacheStats;
        long renderNanos = ClientRagdollRenderer.lastRenderFrameNanos;
        long renderAvg = ClientRagdollRenderer.avgRenderFrameNanos();
        int renderedCount = ClientRagdollRenderer.lastRenderedCount;
        int culledCount = ClientRagdollRenderer.lastCulledCount;

        com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                "[Ragdoll Perf] engine={} total={} active={} settled={} frozen={} | "
                + "tickAll={}ms (spawn={} state={} wake={} physics={} postTick={})",
                physicsWorld.engineName(),
                ragdolls.size(), activeCount, settledCount, frozenCount,
                ms(lastTickAllNanos),
                ms(lastSpawnQueueNanos), ms(lastStatePassNanos), ms(lastWakeLoopNanos),
                ms(lastPhysicsStepNanos), ms(lastPostTickNanos)
        );
        if (droppedTicks > 0) {
            com.raiiiden.ragdollified.Ragdollified.LOGGER.info(
                    "[Ragdoll Perf]   dropped={} physics ticks since the last window - the worker "
                  + "overran 50ms that many times, and ragdolls rendered in slow motion for each",
                    droppedTicks);
        }
        droppedTicks = 0;
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
                "[Ragdoll Perf]   solver: dynBodies={} manifolds={} contacts={} | "
                + "cache: live={} bodies={} shapes={} hits={} miss={} rateLim={}(prio={} of budget={}) "
                + "unloaded={} created={} | "
                + "poses: deferred={} rejected={} | "
                + "spawnQ: depth={} processed={} wakes={} forceSettled={} blockChanges={}",
                lastDynamicBodyCount, lastManifoldCount, lastContactPointCount,
                cs.liveCacheEntries, cs.liveStaticBodies, cs.internedShapes,
                cs.hits, cs.misses, cs.rateLimited, cs.rateLimitedPriority, cs.budgetThisTick,
                cs.unloadedSkipped,
                cs.staticBodiesCreatedThisTick, cs.poseDeferred, cs.poseRejected,
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

    // Snapshot the entity's death state into SpawnData and queue it; the bodies are built later in
    // tickAll at a config-backed rate. Returns null since the ragdoll does not exist yet.
    public static ClientRagdoll createFromEntity(LivingEntity entity, @Nullable DamageSource damageSource) {
        if (entity == null) return null;
        // Match the authoritative server hook: split-stage deaths are not final deaths.
        // MagmaCube is covered because it inherits Slime.
        if (entity instanceof net.minecraft.world.entity.monster.Slime slime && slime.getSize() > 1) return null;

        int entityId = entity.getId();
        if (hasPendingOrActiveRagdoll(entityId)) return null;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        boolean isPlayer = entity instanceof Player;
        String mobType = EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(mobType, isPlayer)) {
            return null;
        }
        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isSupportedModelType(modelType)) {
            Ragdollified.LOGGER.info(
                    "Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render",
                    mobType);
            return null;
        }
        float scale = isPlayer ? 1.0f : entity.getBbHeight() / 1.8f;
        // LivingEntity.isBaby() rather than an AgeableMob check: zombies, husks and piglins are
        // Monsters that still override isBaby(), so the instanceof test gave babies adult ragdolls.
        boolean isBaby = entity.isBaby();

        MobPoseCapture.MobPose capturedPose = resolvePose(entity, modelType);
        Vec3 vel = RagdollSpawnState.applyAttackerDirectionFallback(
                entity, damageSource, RagdollSpawnState.captureLinearVelocity(entity));

        ResourceLocation texture = null;
        if (!isPlayer) {
            // The entity may never have reached RenderLivingEvent.Pre (off-screen/instant death),
            // so resolve renderer-owned textures while the live entity is still available.
            EntityRenderCaptureHandler.captureRenderState(entity);
            texture = ClientMobTextureCache.getTextureForDeadMob(entityId);
        }

        // Directional hit info captured by the hit tracker (TACZ bullet, vanilla projectile), so the
        // constructor can apply a one-shot impulse to the right part. Null when there is none.
        Vec3 explosionKick = RagdollSpawnState.captureExplosionVelocityKick(entity, damageSource);
        RagdollHitTracker.ResolvedHit hit = explosionKick == null
                ? RagdollHitTracker.resolveAndPlan(entity, damageSource)
                : null;
        int hitPartIndex = explosionKick != null
                ? RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX
                : hit != null
                        ? (hit.centered ? RagdollHitMapper.CENTER_HIT_PART_INDEX : hit.part.index)
                        : -1;
        Vec3 hitImpulse = explosionKick != null ? explosionKick : hit != null ? hit.impulse : null;

        // Sheep wool state: only meaningful for sheep, ignored otherwise.
        boolean wasSheared = false;
        int dyeColorId = 0;
        if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep) {
            wasSheared = sheep.isSheared();
            dyeColorId = sheep.getColor().getId();
        }
        if (entity instanceof net.minecraft.world.entity.animal.Wolf wolf) {
            wasSheared = wolf.isTame();
            dyeColorId = wolf.getCollarColor().getId();
        }
        if (entity instanceof net.minecraft.world.entity.animal.goat.Goat goat) {
            dyeColorId = (goat.hasLeftHorn() ? 1 : 0) | (goat.hasRightHorn() ? 2 : 0);
        }
        if (entity instanceof net.minecraft.world.entity.animal.Turtle turtle) {
            wasSheared = turtle.hasEgg();
        }
        if (entity instanceof net.minecraft.world.entity.animal.SnowGolem snowGolem) {
            wasSheared = snowGolem.hasPumpkin();
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractChestedHorse horse) {
            wasSheared = horse.hasChest();
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.Horse horse) {
            dyeColorId = horse.getMarkings().getId();
        }
        // Other per-mob overlay flags, captured here so the local spawn path sees the same state the
        // server-broadcast path would have packed into its packet.
        boolean chargedCreeper = entity instanceof net.minecraft.world.entity.monster.Creeper c && c.isPowered();
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) {
            chargedCreeper = horse.isSaddled();
        }
        boolean saddledPig = entity instanceof net.minecraft.world.entity.animal.Pig p && p.isSaddled();
        if (entity instanceof net.minecraft.world.entity.monster.Strider strider) saddledPig = strider.isSaddled();

        ClientRagdoll.SpawnData data = new ClientRagdoll.SpawnData(
                entityId, isPlayer, mobType, modelType, scale,
                isPlayer ? entity.getUUID() : null,
                isPlayer ? entity.getName().getString() : "",
                entity.getItemBySlot(EquipmentSlot.HEAD).copy(),
                entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
                entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
                entity.getItemBySlot(EquipmentSlot.FEET).copy(),
                // Body yaw: must match what PhysicsHooks sends, or the authoritative
                // server spawn would visibly snap the locally-spawned ragdoll around.
                entity.position(), entity.getVisualRotationYInDegrees(), entity.getXRot(),
                vel, capturedPose,
                entity.getPose() == Pose.SWIMMING,
                isBaby,
                texture,
                hitPartIndex, hitImpulse,
                wasSheared, dyeColorId,
                chargedCreeper, saddledPig
        );

        offerSpawn(data);
        return null;
    }

    // Pop up to the configured spawn budget; returns count actually constructed.
    private static int processSpawnQueue(ClientPhysicsWorld physicsWorld) {
        int budget = RagdollifiedConfig.get(RagdollifiedConfig.MAX_SPAWNS_PER_TICK);
        int spawned = 0;
        // Only real construction spends budget: a queue of stale or unsupported entries used to starve
        // real spawns. scanGuard just bounds the skip loop should the queue cap ever change.
        int scanGuard = budget + RagdollifiedConfig.get(RagdollifiedConfig.MAX_SPAWN_QUEUE_SIZE);
        Integer entityId;
        while (budget > 0 && scanGuard-- > 0 && (entityId = spawnOrder.poll()) != null) {
            ClientRagdoll.SpawnData data = pendingSpawns.remove(entityId);
            if (data == null) continue; // stale order entry after replacement/drop
            boolean forced = forcedSpawnIds.remove(entityId);
            boolean coordinated = coordinatedSpawnIds.remove(entityId);
            if (!isSupportedSpawn(data, forced)) {
                continue;
            }
            budget--;
            // An existing ragdoll for this entity (local death spawn replaced by an authoritative
            // packet) is destroyed first, on this thread, so removal cannot race stepSimulation.
            ClientRagdoll existing = ragdolls.remove(data.originalEntityId);
            if (existing != null) {
                existing.destroy();
            }
            ClientRagdoll ragdoll = new ClientRagdoll(data, physicsWorld);
            ragdoll.setPersistent(persistentRagdollIds.contains(data.originalEntityId));
            // Apply a known observer role at construction: drainInputQueues has already run, so waiting
            // for its next pass would leak a tick of local simulation into a playback-only body.
            if (coordinated
                    && !Boolean.TRUE.equals(streamOwnership.get(data.originalEntityId))) {
                ragdoll.setReplicated(true);
            }
            AuthoritativeState retainedState = authoritativeStates.remove(data.originalEntityId);
            if (retainedState != null) {
                ragdoll.applyAuthoritativeState(
                        retainedState.transforms, retainedState.ageTicks, retainedState.settled);
            }
            StreamedPoseUpdate initialPose = pendingStreamPoses.remove(data.originalEntityId);
            if (initialPose != null) {
                if (Boolean.TRUE.equals(streamOwnership.get(data.originalEntityId))) {
                    ragdoll.applyOwnerHandoffPose(initialPose.transforms());
                } else {
                    ragdoll.setReplicated(true);
                    ragdoll.applyStreamedPose(
                            initialPose.transforms(), initialPose.sequence(), initialPose.sampleTick(),
                            initialPose.hardSync());
                }
            }
            enforceMaxRagdolls();
            ragdolls.put(data.originalEntityId, ragdoll);
            // Cap how many of one player's death ragdolls exist at once, so a rapid re-death cannot
            // stack bodies. Addon replacement bodies are separate entities and are never counted.
            if (ragdoll.isPlayer() && ragdoll.getPlayerUUID() != null) {
                enforceMaxRagdollsPerPlayer(ragdoll.getPlayerUUID());
            }
            spawned++;
        }
        return spawned;
    }

    // Queue a spawn from any thread, used by packet handlers. Construction calls into the physics world and so
    // happens on the physics worker in processSpawnQueue.
    public static void enqueueSpawn(ClientRagdoll.SpawnData data) {
        enqueueSpawn(data, false);
    }

    public static void enqueueCoordinatedSpawn(ClientRagdoll.SpawnData data) {
        if (data == null) return;
        coordinatedSpawnIds.add(data.originalEntityId);
        if (!enqueueSpawn(data, false)) coordinatedSpawnIds.remove(data.originalEntityId);
    }

    // Queue a spawn from an integration. Forced spawns skip the automatic-death config filters,
    // but an unsupported model type is always rejected.
    public static boolean enqueueSpawn(ClientRagdoll.SpawnData data, boolean force) {
        if (data == null || !isSupportedSpawn(data, force)) return false;
    // An integration-owned persistent body already is this id's visual authority: a downed player who
    // then dies keeps it, where the death packet would snap them back to the server origin.
        if (!force && persistentRagdollIds.contains(data.originalEntityId)
                && hasPendingOrActiveRagdoll(data.originalEntityId)) return false;
        if (force) forcedSpawnIds.add(data.originalEntityId);
        offerSpawn(data);
        return true;
    }

    public static void enqueueAuthoritativeState(int entityId, RagdollTransform[] transforms,
                                                 int ageTicks, boolean settled) {
        authoritativeStates.put(entityId, new AuthoritativeState(transforms, ageTicks, settled));
    }

    private static void offerSpawn(ClientRagdoll.SpawnData data) {
        int entityId = data.originalEntityId;
        // Queue order is the ordering that matters: a replacement body arriving after this point
        // belongs to a later death than the body queued here, and must not adopt it.
        if (data.isPlayer) playerRagdollGenerations.merge(entityId, 1, Integer::sum);
        ClientRagdoll.SpawnData previous = pendingSpawns.put(entityId, data);
        if (previous != null) return; // authoritative data replaced the pending local snapshot

        int max = RagdollifiedConfig.get(RagdollifiedConfig.MAX_SPAWN_QUEUE_SIZE);
        while (pendingSpawns.size() > max) {
            Integer droppedId = spawnOrder.poll();
            if (droppedId == null) break;
            pendingSpawns.remove(droppedId);
            authoritativeStates.remove(droppedId);
        }
        spawnOrder.offer(entityId);
    }

    private static boolean isSupportedSpawn(ClientRagdoll.SpawnData data) {
        return isSupportedSpawn(data, false);
    }

    private static boolean isSupportedSpawn(ClientRagdoll.SpawnData data, boolean force) {
        if (!force && !RagdollifiedConfig.isRagdollEnabledFor(data.mobType, data.isPlayer)) return false;
        if (data.isPlayer) return true;
        if (MobModelHelper.isSupportedModelType(data.modelType)) return true;
        Ragdollified.LOGGER.info(
                "Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render",
                data.mobType);
        return false;
    }

    // Retire a player's oldest death ragdolls back under the per-player cap. Physics thread only, so
    // destroy() is safe; addon-owned replacement bodies are not in the map and cannot be reached.
    private static void enforceMaxRagdollsPerPlayer(UUID playerUUID) {
        // A body an addon is going to replace stands for whatever that replacement protects, so the
        // cosmetic per-player limit never culls it. See setPlayerRagdollCullingSuppressed.
        if (playerRagdollCullingSuppressed) return;
        int max = RagdollifiedConfig.getMaxRagdollsPerPlayer();
        while (true) {
            int count = 0;
            ClientRagdoll oldest = null;
            int oldestTicks = -1;
            for (ClientRagdoll r : ragdolls.values()) {
                if (r.isPlayer() && playerUUID.equals(r.getPlayerUUID()) && !r.isDestroyed()) {
                    count++;
                    if (!r.isPersistent() && r.getTicksExisted() > oldestTicks) {
                        oldestTicks = r.getTicksExisted();
                        oldest = r;
                    }
                }
            }
            if (count <= max || oldest == null) break;
            oldest.destroy();
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
                if (!r.isPersistent() && r.getTicksExisted() > oldestTicks) {
                    oldestTicks = r.getTicksExisted();
                    oldest = r;
                }
            }
            // Externally persistent bodies are intentionally exempt from cosmetic capacity
            // pruning; their owner must explicitly remove them.
            if (oldest == null) break;
            oldest.destroy();
            ragdolls.remove(oldest.getOriginalEntityId());
        }
    }

    // Deprecated and unsafe: builds the ClientRagdoll on the calling thread, racing the physics
    // worker on physics body insertion. Use enqueueSpawn. Kept only for compile compatibility.
    @Deprecated
    public static void addRagdoll(int id, ClientRagdoll ragdoll) {
        // Best-effort: if called, just put in map. The ragdoll's bodies were added on
        // whatever thread called us, which is the bug we're warning about.
        ClientRagdoll existing = ragdolls.put(id, ragdoll);
        if (existing != null && existing != ragdoll) {
            // Can't safely destroy here: caller may be on wrong thread. Leak is preferred
            // over a crash. This API shouldn't be called.
            Ragdollified.LOGGER.warn("addRagdoll called — physics may corrupt. Use enqueueSpawn.");
        }
    }

    public static ClientRagdoll get(int id) { return ragdolls.get(id); }
    public static Collection<ClientRagdoll> getAll() { return ragdolls.values(); }

    // Deprecated and unsafe across threads: destroys physics bodies on the caller's thread. Deferred
    // destroy already covers queue replacement and lifetime expiry inside the tick.
    @Deprecated
    public static void remove(int id) {
        ClientRagdoll ragdoll = ragdolls.remove(id);
        if (ragdoll != null) {
            ragdoll.destroy();
        }
    }

    public static boolean hasRagdollFor(int entityId) {
        ClientRagdoll ragdoll = ragdolls.get(entityId);
        return ragdoll != null && !ragdoll.isDestroyed();
    }

    public static boolean hasPendingOrActiveRagdoll(int entityId) {
        return pendingSpawns.containsKey(entityId) || hasRagdollFor(entityId);
    }

    public static void clear() {
        for (ClientRagdoll ragdoll : ragdolls.values()) ragdoll.destroy();
        ragdolls.clear();
        // Loose limbs live in the same physics world and would otherwise survive it.
        ClientDetachedLimbManager.clear();
        severQueue.clear();
        pendingSpawns.clear();
        spawnOrder.clear();
        authoritativeStates.clear();
        dragTargets.clear();
        streamOwnership.clear();
        streamPoseQueue.clear();
        pendingStreamPoses.clear();
        streamSendSequences.clear();
        lastStreamSampleTicks.clear();
        forcedSpawnIds.clear();
        coordinatedSpawnIds.clear();
        persistentRagdollIds.clear();
        pendingPersistenceUpdates.clear();
        explicitlyHiddenEntityIds.clear();
        // Entity ids are only meaningful within one connection, so both sides of the handoff
        // bookkeeping are dropped together.
        playerRagdollGenerations.clear();
        handoffGenerations.clear();
    }

    public static void onWorldUnload() {
        // Stop accepting new ticks first.
        ExecutorService old = physicsExecutor;
        physicsExecutor = newPhysicsExecutor();
        old.shutdown();
        try {
            // Wait briefly for the current tick to finish so we don't tear down the physics world
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
        physicsRecoveries = 0;
        impulseQueue.clear();
        blockChangeQueue.clear();
        removeByRagdollIdQueue.clear();
        deathLifetimeRestarts.clear();
        awaitingRealSettle.clear();
        clear();
        // Handoff-keyed damage visuals outlive individual ragdolls, so clear() does not reach
        // them, and both key spaces only mean anything within one connection anyway.
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.clearAll();
        com.raiiiden.ragdollified.client.compat.VisualHealthCompat.clearAll();
        com.raiiiden.ragdollified.client.compat.CuriosRenderCompat.clearAll();
        RagdollHitTracker.clear();
        ClientPlayerSkinCache.clear();
        // Entity renderers are rebuilt with the level, so the cached model instances go stale.
        ClientMobModelCache.clear();
        // Same for the measured rigs, which hold ModelPart references straight out of those
        // renderers: a stale one would draw a body at geometry that no longer exists.
        GenericRigExtractor.clear();
        ClientPhysicsWorld.onWorldUnload();
    }

    // Deprecated: use enqueueBlockChange(BlockPos); a direct call races the physics thread.
    @Deprecated
    public static void onBlockChanged(BlockPos pos) {
        enqueueBlockChange(pos);
    }

    public static int getActiveCount() { return ragdolls.size(); }
}
