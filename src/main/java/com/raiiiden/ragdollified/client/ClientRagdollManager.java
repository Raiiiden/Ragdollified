package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.api.DragEnd;
import com.raiiiden.ragdollified.api.DragTarget;
import com.raiiiden.ragdollified.api.RagdollSpawnTransform;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.network.CorpseSettlePacket;
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
    // Previous tick's active count, used to size the collision-geometry budget at the
    // start of this tick (before the state pass has recounted).
    private static int lastActiveRagdollCount = 0;
    // Worst-tick tracker — captures the slowest tickAll inside the 100-tick window so
    // periodic spikes that happen between log intervals are still visible.
    private static long worstTickAllNanos = 0;
    private static int worstTickActiveCount = 0;
    private static int worstTickManifoldCount = 0;
    private static final IdentityHashMap<RigidBody, ClientRagdoll> bodyOwners = new IdentityHashMap<>();
    private static final Set<ClientRagdoll> correctedRagdolls =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Vector3f penetrationNormal = new Vector3f();
    private static final Vector3f penetrationVelocity = new Vector3f();
    private static final float PENETRATION_DEPTH_THRESHOLD = -0.15f;
    private static final float MAX_GROUP_PENETRATION_CORRECTION = 0.08f;

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
    // One pending snapshot per entity id. The order queue contains ids rather than data so an
    // authoritative server packet can replace a local snapshot without constructing twice.
    private static final ConcurrentHashMap<Integer, ClientRagdoll.SpawnData> pendingSpawns =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> spawnOrder = new ConcurrentLinkedQueue<>();

    // Cross-thread input queues. Inputs from main/render thread (clicks, block changes,
    // network packets) are enqueued here; the physics thread drains them at the start
    // of each tick and applies them through jbullet.
    public static final class ImpulseRequest {
        public final int ragdollId;
        public final int partIndex;
        public final float x, y, z;
        public final int revision;
        public final boolean apply;
        public ImpulseRequest(int ragdollId, int partIndex, float x, float y, float z,
                              int revision, boolean apply) {
            this.ragdollId = ragdollId; this.partIndex = partIndex;
            this.x = x; this.y = y; this.z = z;
            this.revision = revision; this.apply = apply;
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
    // Corpse handoff: when a posed corpse entity arrives, the ONE redundant physics ragdoll it
    // replaced (matched by ragdoll entity id, not player UUID) is queued here for the physics
    // thread to destroy (no main-thread race). Matching by id means a lingering older corpse
    // can't cull a newer death's ragdoll for the same player.
    private static final ConcurrentLinkedQueue<Integer> removeByRagdollIdQueue = new ConcurrentLinkedQueue<>();

    // Public API drag targets. These are intentionally targets rather than impulses. Each
    // request can drive several limbs, and the complete immutable request is consumed before
    // Bullet's next step so paired arms/legs are never updated one at a time.
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
    // Owner-streamed player ragdolls. The server assigns exactly one owner per player body;
    // TRUE means "this client simulates and streams it", FALSE means "play back the stream".
    // No entry at all means simulate locally — mobs, singleplayer, and vanilla/older servers.
    private static final ConcurrentHashMap<Integer, Boolean> streamOwnership = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<StreamedPoseUpdate> streamPoseQueue = new ConcurrentLinkedQueue<>();
    // A spawn packet and its first owner pose can land in the same client tick. Input queues are
    // drained before spawn construction, so retain the newest early pose instead of discarding
    // the exact initial state merely because the Bullet body does not exist yet.
    private static final ConcurrentHashMap<Integer, StreamedPoseUpdate> pendingStreamPoses =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> streamSendSequences = new ConcurrentHashMap<>();

    private record StreamedPoseUpdate(int entityId, RagdollTransform[] transforms, int sequence) {}

    private static final ConcurrentHashMap<Integer, DragRequest> dragTargets = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> endedDragIds = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Integer> deathLifetimeRestarts = new ConcurrentLinkedQueue<>();
    // Bodies handed back at death, which must report a real settle before becoming a corpse.
    // No timer: the age-based give-up shortcut below is just switched off for them. That
    // shortcut exists for a ragdoll stuck mid-air and keys off age, which a body that spent a
    // whole knock alive always trips — why it fired the instant the handover landed. Written on
    // the caller's thread, not inside the ragdoll's restart, because that restart runs on the
    // physics worker while the corpse bridge runs on main in the gap before it drains.
    private static final Set<Integer> awaitingRealSettle = ConcurrentHashMap.newKeySet();

    // API spawns deliberately bypass the user's automatic-death enable list, but still
    // require a model type Ragdollified can actually construct and render.
    private static final Set<Integer> forcedSpawnIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> persistentRagdollIds = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, Boolean> pendingPersistenceUpdates = new ConcurrentHashMap<>();

    // Explicit visibility overrides are client-local. They are separate from the automatic
    // dead-entity hiding done by HideDeadEntityMixin.
    private static final Set<Integer> explicitlyHiddenEntityIds = ConcurrentHashMap.newKeySet();

    // Minecraft hands a respawning player their previous entity id back, so a corpse's stored
    // ragdoll id alone does NOT identify the body that corpse replaced — the same id is later
    // reused by that player's next death or downed body. Every queued player ragdoll bumps a
    // generation for its entity id, and each corpse remembers the generation that was current
    // when this client first saw it, so a corpse can only ever retire that exact body.
    private static final Map<Integer, Integer> playerRagdollGenerations = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> corpseHandoffGenerations = new ConcurrentHashMap<>();

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
    // every subsequent step throws. Latching this flag on the first crash used to disable
    // the worker for the rest of the session — and since processSpawnQueue runs inside
    // tickAll, that also meant no ragdoll ever spawned again until the player rejoined.
    // A crash is recovered from instead (see handlePhysicsCrash); the flag now only latches
    // once recovery has been tried MAX_PHYSICS_RECOVERIES times, so a deterministic crash
    // still can't spin forever.
    private static volatile boolean physicsBroken = false;
    private static final int MAX_PHYSICS_RECOVERIES = 3;
    private static int physicsRecoveries = 0;

    // Submit a physics tick to the worker. If the previous tick is still running this one is
    // dropped rather than queued — falling behind a tick beats backlogging into two steps
    // back-to-back. Driven from ClientTickEvent at 20 Hz.
    public static void submitTick() {
        if (physicsBroken) return;
        if (!physicsBusy.compareAndSet(false, true)) return;
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

    // Recover from a crash inside tickAll(). The corrupted jbullet world is what keeps throwing,
    // so every body is dropped and the world discarded; the next tick's ClientJbulletWorld.get
    // builds a clean one and spawning resumes. Runs on the physics worker, the only thread
    // allowed to touch jbullet. Bounded per world load, since a crash that is not really about
    // world state would otherwise rebuild forever — after the budget the worker latches off.
    private static void handlePhysicsCrash(Throwable t) {
        if (physicsRecoveries >= MAX_PHYSICS_RECOVERIES) {
            if (!physicsBroken) {
                physicsBroken = true;
                Ragdollified.LOGGER.error(
                        "Ragdoll physics crashed {} times — disabling worker until world reload. "
                      + "This usually means jbullet's world state was corrupted by "
                      + "a cross-thread modification.", physicsRecoveries, t);
            }
            return;
        }
        physicsRecoveries++;
        Ragdollified.LOGGER.error(
                "Ragdoll physics crashed (recovery {}/{}) — dropping every body and rebuilding "
              + "the jbullet world. Existing ragdolls are lost; new deaths will ragdoll again.",
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
        deathLifetimeRestarts.clear();
        awaitingRealSettle.clear();
        try {
            ClientJbulletWorld.onWorldUnload();
        } catch (Throwable disposeFailure) {
            Ragdollified.LOGGER.error("Failed to dispose the crashed jbullet world", disposeFailure);
        }
    }

    // Enqueue a punch/click impulse for the physics thread to apply on its next tick.
    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z) {
        enqueueImpulse(ragdollId, partIndex, x, y, z, 0, true);
    }

    public static void enqueueImpulse(int ragdollId, int partIndex, float x, float y, float z,
                                      int revision, boolean apply) {
        impulseQueue.offer(new ImpulseRequest(ragdollId, partIndex, x, y, z, revision, apply));
    }

    // Destroy a specific physics ragdoll (by entity id) on the physics thread (corpse handoff).
    public static void requestRemoveRagdoll(int entityId) {
        dragTargets.remove(entityId);
        pendingSpawns.remove(entityId);
        forcedSpawnIds.remove(entityId);
        persistentRagdollIds.remove(entityId);
        pendingPersistenceUpdates.remove(entityId);
        authoritativeStates.remove(entityId);
        streamOwnership.remove(entityId);
        pendingStreamPoses.remove(entityId);
        streamSendSequences.remove(entityId);
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

    // Turn an integration-owned body into a fresh death ragdoll in place. The pose survives, the
    // lifetime restarts, and it reports its rest pose again, so the corpse is built from where
    // the body actually lies rather than the server's stale death position.
    public static void restartDeathLifetime(int entityId) {
        if (!hasPendingOrActiveRagdoll(entityId)) return;
        // Hold the corpse report here, on the caller's thread, rather than relying only on the
        // grace the ragdoll sets inside its own restart. That restart runs on the physics worker,
        // which is asynchronous, so between this call and the queue being drained the body still
        // reads as an ancient, unreported, no-longer-dragged ragdoll — and tickCorpseClient runs
        // on the main thread inside exactly that window. That gap is how a body handed over at
        // death still converted to a corpse before it ever moved.
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

    // Make a clean client-local ragdoll from a loaded entity. Unlike automatic death ragdolls
    // this skips the enable list and deliberately applies no hit impulse.
    // Snapshot whatever the installed damage-visual mods are drawing on an entity, so the ragdoll
    // that replaces it can reproduce it after the entity is hidden or gone. Called from every
    // spawn path (death event, server spawn packet, API) because whichever one runs first is the
    // only one guaranteed to still see the live entity.
    public static void captureCompatVisuals(LivingEntity entity) {
        if (entity == null) return;
        // Better Blood Overlay: mobs are already captured every frame by
        // EntityRenderCaptureHandler, but that path skips players — so capture players here,
        // while their wounds are still registered.
        if (entity instanceof Player) {
            com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.capture(entity.getId(), entity);
            // Curios: the server empties the curio slots when it builds the corpse, so the worn
            // set has to be read now. Only the player path renders them (see
            // ClientRagdollRenderer#renderBodyCurios), so mobs are not captured.
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
                MobPoseCapture.getPose(entity.getId()),
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

    // Main-thread corpse bridge, cheap and only live when corpses are on and this client has a
    // settling player ragdoll. Two jobs: report a settle to the server once, so a nearby
    // observer can pose the corpse at the true rest position even with its owner far away; and
    // once the posed corpse entity arrives, drop the now-redundant physics ragdoll.
    public static void tickCorpseClient() {
        if (!RagdollifiedConfig.isCorpseEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Job 1 — settle reports for every player ragdoll simulated by this client. The
        // server validates UUID + death entity id + reporter proximity and accepts only the
        // first matching report, so every nearby observer can safely participate.
        for (ClientRagdoll r : ragdolls.values()) {
            if (!r.isPlayer() || r.getPlayerUUID() == null || r.isCorpseSettleReported()) continue;

            // A towed body is not a stuck one. Its pose changes every tick, and the drag itself
            // re-arms the report every tick, so the give-up branch below fires continuously for
            // as long as the tow lasts — a long-lived body is always past that age threshold.
            // The server drops those reports while the owner is alive, then accepts the first one
            // after they die, which converted a dragged player to a corpse on the very next tick.
            // Nothing is lost by waiting: the drag ends before the death is finished processing.
            if (isDragging(r.getId()) || r.isAwaitingSettleGrace()) continue;

            // Report when the ragdoll has settled, OR shortly before it would despawn / the
            // server would time out, so a stuck ragdoll still reports its actual current pose.
            int limit = Math.min(RagdollifiedConfig.getRagdollLifetime(),
                                 RagdollifiedConfig.getCorpseSettleTimeoutTicks());
            // A distance-frozen observer is not near the body; let another nearby client
            // report, or wait until this client returns and resumes the simulation.
            // A body handed over at death waits for a real settle instead: it is old by
            // definition, so the age test would fire immediately and skip the settle entirely.
            // The server's own settle deadline still covers one that never comes to rest.
            boolean nearGiveUp = !r.isFrozen() && !awaitingRealSettle.contains(r.getId())
                    && r.getTicksExisted() >= Math.max(20, limit - 40);
            if (r.isSettled() || nearGiveUp) {
                ClientRagdoll.TransformSnapshot snap = r.getSnapshot();
                if (snap != null) {
                    Vector3f origin = snap.cachedTorsoPos;
                    RagdollTransform[] rel = new RagdollTransform[RagdollTransform.MAX_PARTS];
                    for (int i = 0; i < RagdollTransform.MAX_PARTS && i < snap.positions.length; i++) {
                        Vector3f p = snap.positions[i];
                        if (p == null) continue;
                        rel[i] = new RagdollTransform(i,
                                new Vector3f(p.x - origin.x, p.y - origin.y, p.z - origin.z),
                                new Quat4f(snap.rotations[i]));
                    }
                    ModNetwork.CHANNEL.sendToServer(new CorpseSettlePacket(
                            origin.x, origin.y, origin.z, rel,
                            r.getPlayerUUID(), r.getOriginalEntityId(), r.getLastImpulseRevision()));
                    r.markCorpseSettleReported();
                    awaitingRealSettle.remove(r.getId());
                }
            }
        }

        // Job 2 — on EVERY client (owner included): once a posed corpse exists for the exact
        // ragdoll it replaced, drop that ragdoll so the corpse is the only visible body (no
        // double-up). Idempotent — once the ragdoll is gone the lookup misses, so this naturally
        // stops requesting (and handles repeat deaths).
        //
        // A corpse must be remembered even while this client simulates no ragdoll at all: the
        // player it belongs to can respawn, get their old entity id back, and only then produce
        // a new body. Recording the sighting late would make that new body look like the one
        // this corpse replaced and delete it. Scanning is throttled while there is nothing to
        // hand off, since the walk over the render list is the only cost here.
        if (ragdolls.isEmpty() && mc.level.getGameTime() % 10L != 0L) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CorpseEntity c) || !c.isPosed()) continue;
            int ragId = c.getRagdollEntityId();
            if (ragId < 0) continue;
            int handoffGeneration = corpseHandoffGenerations.computeIfAbsent(c.getUUID(),
                    ignored -> playerRagdollGenerations.getOrDefault(ragId, 0));
            // A newer generation means this id has been recycled by a later death or knock —
            // that body belongs to a different death and gets its own corpse. (A corpse whose
            // very first sighting here is already later than the body it replaced simply never
            // hands off; a redundant body for one lifetime beats deleting a live player's.)
            if (handoffGeneration != playerRagdollGenerations.getOrDefault(ragId, 0)) continue;
            ClientRagdoll r = ragdolls.get(ragId);
            // The body a corpse replaces is always its owner's player ragdoll, so require that
            // identity too. The stored id alone is not one: entity ids restart from low numbers
            // every server session while corpses persist across restarts and chunk unloads, so
            // a corpse loaded after a restart would otherwise delete whatever unrelated body
            // (usually a mob — mob spawns never bump the generation) inherited its id. With many
            // corpses loaded that is one poisoned id each, and ragdolls stop appearing at a rate
            // that scales with the corpse count.
            if (r == null || r.isDestroyed() || !r.isPlayer()) continue;
            UUID corpseOwner = c.getOwnerUUID();
            if (corpseOwner != null && corpseOwner.equals(r.getPlayerUUID())) {
                // Re-key the damage visuals onto the corpse before the ragdoll goes away — its
                // destroy() evicts them by entity id, which would leave the corpse clean.
                com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.transferTo(ragId, c.getUUID());
                com.raiiiden.ragdollified.client.compat.VisualHealthCompat.transferTo(ragId, c.getUUID());
                requestRemoveRagdoll(ragId);
            }
        }
    }

    // Server→client ownership assignment for a player ragdoll. Main thread.
    public static void enqueueStreamOwnership(int entityId, boolean owner) {
        streamOwnership.put(entityId, owner);
        if (!owner) streamSendSequences.remove(entityId);
    }

    // Server→client relayed pose frame from the owning client. Main thread.
    public static void enqueueStreamedPose(int entityId, RagdollTransform[] transforms, int sequence) {
        if (transforms == null) return;
        streamPoseQueue.offer(new StreamedPoseUpdate(entityId, transforms, sequence));
    }

    // Stream this client's owned player ragdolls to the server while they are still moving. At
    // 20 Hz: one frame per game tick, so an observer follows every authoritative physics step.
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
            RagdollTransform[] transforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
            for (int i = 0; i < transforms.length && i < snap.positions.length; i++) {
                if (snap.positions[i] == null || snap.rotations[i] == null) continue;
                transforms[i] = new RagdollTransform(i, snap.positions[i], snap.rotations[i]);
            }
            if (transforms[0] == null) continue; // no torso anchor to encode the rest against

            int sequence = streamSendSequences.merge(entry.getKey(), 1, Integer::sum);
            ModNetwork.CHANNEL.sendToServer(
                    new RagdollStreamPacket(entry.getKey(), sequence, transforms));
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
            // Only the elected physics owner may finalize a player ragdoll. The server enforces
            // this too, but suppressing observer reports avoids letting a stale/local pose even
            // reach the network during an ownership transition.
            if (ragdoll.isPlayer()
                    && !Boolean.TRUE.equals(streamOwnership.get(ragdoll.getOriginalEntityId()))) {
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
            ragdoll.markSettledPoseReported();
        }
    }

    // Enqueue a block-change wake event. Block updates fire frequently — keep cheap.
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
            RagdollPart part = RagdollPart.byIndex(req.partIndex);
            if (part == null) continue;
            if (req.apply) r.applyImpulse(part, new Vector3f(req.x, req.y, req.z));
            if (req.revision > 0) r.acknowledgeImpulseRevision(req.revision);
        }
        // Corpse handoff removals — destroy the specific physics ragdoll (by id) on the
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
                // already constructed. Seed Bullet directly; never turn the owner into playback.
                r.applyOwnerHandoffPose(streamed.transforms());
            } else {
                r.setReplicated(true);
                r.applyStreamedPose(streamed.transforms(), streamed.sequence());
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
        ClientJbulletWorld physicsWorld = ClientJbulletWorld.get(net.minecraft.client.Minecraft.getInstance().level);
        if (physicsWorld == null) return;
        int processed = 0;
        while ((pos = blockChangeQueue.poll()) != null) {
            // Drop the changed block collider and shape-dependent neighbors.
            physicsWorld.invalidateBlockChange(pos);
            // Drop stale handles and wake nearby frozen ragdolls.
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
        if (ragdolls.isEmpty() && pendingSpawns.isEmpty()) return;
        long tickStart = System.nanoTime();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }

        ClientJbulletWorld physicsWorld = ClientJbulletWorld.get(level);
        physicsWorld.beginTick(lastActiveRagdollCount);

        // Phase 1 — drain cross-thread input queues, then spawn queue. Inputs (impulses,
        // block changes) come from the render thread; spawns come from death events.
        // Both are processed here so all jbullet mutation happens on this thread.
        long t0 = System.nanoTime();
        drainInputQueues();
        lastSpawnsThisTick = 0;
        if (!pendingSpawns.isEmpty()) {
            lastSpawnsThisTick = processSpawnQueue(physicsWorld);
        }
        lastSpawnQueueDepth = pendingSpawns.size();
        lastSpawnQueueNanos = System.nanoTime() - t0;

        if (ragdolls.isEmpty()) {
            lastActiveRagdollCount = 0;
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
        lastActiveRagdollCount = activeCount;

        // Capture Bullet world contact stats AFTER the step so they reflect the contact
        // density the solver actually had to resolve. Cheap — the dispatcher already
        // tracks this internally; we just iterate it.
        captureManifoldStats(physicsWorld);
        physicsWorld.updateLiveCacheStats();
        long penetrationStart = System.nanoTime();
        correctInterpenetrations(physicsWorld);
        long penetrationNanos = System.nanoTime() - penetrationStart;

        // Phase 5 — post-tick: per-ragdoll work (settle check,
        // distance freeze/unfreeze, world collision update). PHASE_STATS aggregates the
        // sub-phase nanos across all ragdoll ticks.
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
                // Bodies that expire on their own never pass through requestRemoveRagdoll,
                // so their stream bookkeeping has to be released here or it accumulates for
                // the rest of the session.
                streamOwnership.remove(entry.getKey());
                streamSendSequences.remove(entry.getKey());
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

    private static void correctInterpenetrations(ClientJbulletWorld physicsWorld) {
        bodyOwners.clear();
        correctedRagdolls.clear();
        for (ClientRagdoll ragdoll : ragdolls.values()) {
            for (RigidBody body : ragdoll.ragdollParts) {
                bodyOwners.put(body, ragdoll);
            }
        }

        for (PersistentManifold manifold
                : physicsWorld.getDispatcher().getInternalManifoldPointer()) {
            int numContacts = manifold.getNumContacts();
            if (numContacts == 0) continue;

            RigidBody a = (RigidBody) manifold.getBody0();
            RigidBody b = (RigidBody) manifold.getBody1();
            ClientRagdoll ownerA = bodyOwners.get(a);
            ClientRagdoll ownerB = bodyOwners.get(b);
            if (ownerA == null && ownerB == null) continue;
            if (ownerA != null && ownerA == ownerB) continue;

            boolean aDynamic = a.getInvMass() > 0f;
            boolean bDynamic = b.getInvMass() > 0f;
            boolean bothDynamic = aDynamic && bDynamic;
            float damping = bothDynamic ? 0.92f : 0.5f;

            if (bothDynamic && ownerA != null && ownerB != null) {
                ManifoldPoint deepest = null;
                for (int i = 0; i < numContacts; i++) {
                    ManifoldPoint point = manifold.getContactPoint(i);
                    if (point.getDistance() >= PENETRATION_DEPTH_THRESHOLD) continue;
                    if (deepest == null || point.getDistance() < deepest.getDistance()) {
                        deepest = point;
                    }
                }
                if (deepest == null) continue;
                float depth = Math.abs(deepest.getDistance());
                float correction = Math.min(depth * 1.5f, 0.2f) * 0.15f;
                orientGroupCorrectionNormal(ownerA, ownerB, deepest.normalWorldOnB);
                ownerA.addGroupPenetrationCorrection(penetrationNormal, correction);
                ownerB.addGroupPenetrationCorrection(penetrationNormal, -correction);
                correctedRagdolls.add(ownerA);
                correctedRagdolls.add(ownerB);
                continue;
            }

            for (int i = 0; i < numContacts; i++) {
                ManifoldPoint point = manifold.getContactPoint(i);
                if (point.getDistance() >= PENETRATION_DEPTH_THRESHOLD) continue;

                float depth = Math.abs(point.getDistance());
                float correction = bothDynamic
                        ? Math.min(depth * 1.5f, 0.2f) * 0.15f
                        : Math.min(depth * 0.35f, 0.05f);
                penetrationNormal.set(point.normalWorldOnB);
                penetrationNormal.scale(correction);
                if (aDynamic) a.translate(penetrationNormal);
                penetrationNormal.scale(-1f);
                if (bDynamic) b.translate(penetrationNormal);

                if (aDynamic) dampBody(a, damping);
                if (bDynamic) dampBody(b, damping);
            }
        }

        for (ClientRagdoll ragdoll : correctedRagdolls) {
            ragdoll.applyGroupPenetrationCorrection(
                    MAX_GROUP_PENETRATION_CORRECTION, 0.92f);
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

    private static void dampBody(RigidBody body, float damping) {
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
                + "cache: live={} bodies={} hits={} miss={} rateLim={}(prio={} of budget={}) "
                + "unloaded={} created={} | "
                + "poses: deferred={} rejected={} | "
                + "spawnQ: depth={} processed={} wakes={} forceSettled={} blockChanges={}",
                lastDynamicBodyCount, lastManifoldCount, lastContactPointCount,
                cs.liveCacheEntries, cs.liveStaticBodies,
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

    // Snapshot the entity's death state into SpawnData and queue it. The ClientRagdoll and its
    // physics bodies are built later in tickAll(), rate-limited by config so a mass kill does
    // not spike one tick. Returns null because the ragdoll does not exist yet; no caller reads it.
    public static ClientRagdoll createFromEntity(LivingEntity entity, @Nullable DamageSource damageSource) {
        if (entity == null) return null;
        // Match the authoritative server hook: split-stage deaths are not final corpses.
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
        // Use LivingEntity.isBaby() rather than an AgeableMob check: zombies, husks and
        // piglins are Monsters (not AgeableMob) but still override isBaby(), so the
        // instanceof check missed every baby zombie — they spawned adult-sized ragdolls.
        boolean isBaby = entity.isBaby();

        MobPoseCapture.MobPose capturedPose = MobPoseCapture.getPose(entityId);
        Vec3 vel = RagdollSpawnState.applyAttackerDirectionFallback(
                entity, damageSource, RagdollSpawnState.captureLinearVelocity(entity));

        ResourceLocation texture = null;
        if (!isPlayer) {
            // The entity may never have reached RenderLivingEvent.Pre (off-screen/instant death),
            // so resolve renderer-owned textures while the live entity is still available.
            EntityRenderCaptureHandler.captureRenderState(entity);
            texture = ClientMobTextureCache.getTextureForDeadMob(entityId);
        }

        // Resolve any directional hit info captured by the hit tracker (TACZ bullet,
        // vanilla projectile, …) so the constructor can apply a one-shot impulse to
        // the right body part. Returns null if there's no actionable hit data.
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

        // Sheep wool state — only meaningful for sheep, ignored otherwise.
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
        // Other per-mob overlay flags. Captured here so the local-spawn path (no server
        // packet — singleplayer / dedicated-server-with-mod) sees the same state the
        // server-broadcast path would have packed into the spawn packet.
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
                // Body yaw — must match what PhysicsHooks sends, or the authoritative
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
    private static int processSpawnQueue(ClientJbulletWorld physicsWorld) {
        int budget = RagdollifiedConfig.get(RagdollifiedConfig.MAX_SPAWNS_PER_TICK);
        int spawned = 0;
        // Stale and unsupported entries used to consume budget, so a queue full of them
        // starved real spawns well below maxSpawnsPerTick. Only actual construction costs
        // anything, so only that decrements. scanGuard bounds the skip loop — spawnOrder is
        // already capped at maxSpawnQueueSize, this just keeps the drain from being unbounded
        // if that ever changes.
        int scanGuard = budget + RagdollifiedConfig.get(RagdollifiedConfig.MAX_SPAWN_QUEUE_SIZE);
        Integer entityId;
        while (budget > 0 && scanGuard-- > 0 && (entityId = spawnOrder.poll()) != null) {
            ClientRagdoll.SpawnData data = pendingSpawns.remove(entityId);
            if (data == null) continue; // stale order entry after replacement/drop
            boolean forced = forcedSpawnIds.remove(entityId);
            if (!isSupportedSpawn(data, forced)) {
                continue;
            }
            budget--;
            // If a ragdoll already exists for this entity (e.g. local spawn from death
            // event got replaced by an authoritative server packet), destroy it FIRST
            // — on this thread — so the body removal doesn't race with stepSimulation.
            ClientRagdoll existing = ragdolls.remove(data.originalEntityId);
            if (existing != null) {
                existing.destroy();
            }
            ClientRagdoll ragdoll = new ClientRagdoll(data, physicsWorld);
            ragdoll.setPersistent(persistentRagdollIds.contains(data.originalEntityId));
            // Apply a known observer role at construction. drainInputQueues runs before this
            // method, so waiting for its next pass would leak one tick of local simulation
            // into a body this client is only supposed to play back.
            if (Boolean.FALSE.equals(streamOwnership.get(data.originalEntityId))) {
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
                    ragdoll.applyStreamedPose(initialPose.transforms(), initialPose.sequence());
                }
            }
            enforceMaxRagdolls();
            ragdolls.put(data.originalEntityId, ragdoll);
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

    // Entry point for queueing a spawn from any thread, used by packet handlers. The
    // ClientRagdoll construction, which calls into jbullet, happens on the physics worker in
    // processSpawnQueue.
    public static void enqueueSpawn(ClientRagdoll.SpawnData data) {
        enqueueSpawn(data, false);
    }

    // Queue a spawn from an integration. Forced spawns skip the automatic-death config filters,
    // but an unsupported model type is always rejected.
    public static boolean enqueueSpawn(ClientRagdoll.SpawnData data, boolean force) {
        if (data == null || !isSupportedSpawn(data, force)) return false;
        // An integration-owned persistent body is already the visual authority for this id.
        // In particular, a downed player who dies for real keeps that exact physical body;
        // replacing it with the normal death packet would snap it back to the server player
        // origin and undo the downed simulation.
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
        // Queue order is the ordering that matters: a corpse arriving after this point belongs
        // to a later death than the body queued here, and must not adopt it.
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

    // Retire a player's oldest death ragdolls until they are back under the per-player cap.
    // Runs on the physics thread from processSpawnQueue, so destroy() is safe. Corpses are not
    // in the ragdolls map and so are excluded by construction.
    private static void enforceMaxRagdollsPerPlayer(UUID playerUUID) {
        // A corpse-bound player ragdoll is the live visual/physics representation of protected
        // loot until the server materializes its corpse. Never cull it through the cosmetic
        // per-player limit; rapid repeat deaths already materialize the older pending corpse.
        if (RagdollifiedConfig.isCorpseEnabled()) return;
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
    // worker on jbullet body insertion. Use enqueueSpawn. Kept only for compile compatibility.
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
    }

    public static ClientRagdoll get(int id) { return ragdolls.get(id); }
    public static Collection<ClientRagdoll> getAll() { return ragdolls.values(); }

    // Deprecated and unsafe across threads: destroys jbullet bodies on the caller's thread.
    // Deferred destroy already happens when the spawn queue replaces a ragdoll or its lifetime
    // expires inside the tick.
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
        pendingSpawns.clear();
        spawnOrder.clear();
        authoritativeStates.clear();
        dragTargets.clear();
        streamOwnership.clear();
        streamPoseQueue.clear();
        pendingStreamPoses.clear();
        streamSendSequences.clear();
        forcedSpawnIds.clear();
        persistentRagdollIds.clear();
        pendingPersistenceUpdates.clear();
        explicitlyHiddenEntityIds.clear();
        // Entity ids are only meaningful within one connection, so both sides of the corpse
        // handoff bookkeeping are dropped together.
        playerRagdollGenerations.clear();
        corpseHandoffGenerations.clear();
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
        physicsRecoveries = 0;
        impulseQueue.clear();
        blockChangeQueue.clear();
        removeByRagdollIdQueue.clear();
        deathLifetimeRestarts.clear();
        awaitingRealSettle.clear();
        clear();
        // Corpse-keyed damage visuals outlive individual ragdolls, so clear() does not reach
        // them — and both key spaces only mean anything within one connection anyway.
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.clearAll();
        com.raiiiden.ragdollified.client.compat.VisualHealthCompat.clearAll();
        com.raiiiden.ragdollified.client.compat.CuriosRenderCompat.clearAll();
        RagdollHitTracker.clear();
        ClientPlayerSkinCache.clear();
        // Entity renderers are rebuilt with the level, so the cached model instances go stale.
        ClientMobModelCache.clear();
        ClientJbulletWorld.onWorldUnload();
    }

    // Deprecated: use enqueueBlockChange(BlockPos); a direct call races the physics thread.
    @Deprecated
    public static void onBlockChanged(BlockPos pos) {
        enqueueBlockChange(pos);
    }

    public static int getActiveCount() { return ragdolls.size(); }
}
