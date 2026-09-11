package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.physics.ContactPair;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsConstraint;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.api.DragEnd;
import com.raiiiden.ragdollified.api.DragTarget;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@OnlyIn(Dist.CLIENT)
public class ClientRagdoll {

    public static final int CENTER_HIT_PART_INDEX = RagdollHitMapper.CENTER_HIT_PART_INDEX;
    private final int id;

    // Physics: mirrors MobRagdollPhysics field layout
    private final ClientPhysicsWorld physicsWorld;
    private final PhysicsWorld world;
    public final List<PhysicsBody> ragdollParts = new ArrayList<>(6);
    // O(1) body membership for contact and hit checks.
    private final Set<PhysicsBody> ragdollPartsSet = new HashSet<>(8);
    private final List<PhysicsConstraint> ragdollJoints = new ArrayList<>(5);
    // Amputated parts keep their body and joint but are hidden and non-colliding.
    // Written on the physics thread, read from render, hence volatile.
    private int hiddenPartMask;
    private volatile int renderHiddenPartMask;
    private BlockPos lastCollisionCenter = BlockPos.ZERO;
    private ClientPhysicsWorld.CollisionGeometryHandle currentCollisionGeometry;
    private int currentCollisionRadius = 0;
    private BlockPos settledTerrainCenter = BlockPos.ZERO;
    private long settledTerrainSignature;
    private boolean hasSettledTerrainSignature;
    private int collisionGeometryAcquiredTick = Integer.MIN_VALUE;
    private final Set<BlockPos> activeGroundSupportBlocks = new HashSet<>(8);
    private final Set<BlockPos> settledGroundSupportBlocks = new HashSet<>(8);
    // Ragdolls holding this body up, which terrain checks can't see. Any supporter counts as support;
    // only settled supporters allow a voluntary settle.
    private final Set<Integer> supportingRagdolls = new HashSet<>(4);
    private boolean supportedBySettledBody = false;
    private final Set<Integer> settledSupportRagdolls = new HashSet<>(4);
    public static final int COLLISION_RADIUS = 3;
    // (10 blocks/s)²: above this the torso gets priority on the geometry budget.
    private static final float GEOMETRY_PRIORITY_SPEED_SQ = 100f;
    // (2 blocks/s)²: below this a body is settling or drifting and needs the innermost shell only.
    private static final float GEOMETRY_REST_SPEED_SQ = 4f;

    // Scratch vectors reused across per-tick physics calls, one set per ragdoll (single thread, no
    // contention), purely to avoid the hundreds of new Vector3f allocations a tick would cost.
    private final Vector3f scratchVel = new Vector3f();
    // Holds a dragged limb's pre-drive velocity so its fall speed survives the drag override.
    private final Vector3f scratchDragVel = new Vector3f();
    private final Vector3f scratchAng = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();
    private final Vector3f contactNormal = new Vector3f();
    private final Vector3f groupPenetrationCorrection = new Vector3f();
    private final Vector3f supportAabbMin = new Vector3f();
    private final Vector3f supportAabbMax = new Vector3f();
    private final Vector3f supportHalfExtents = new Vector3f();
    private final float[] supportRotation = new float[9];
    private final float[] supportAbsRotation = new float[9];
    private final float[] supportTranslation = new float[3];
    private final float[] supportWorldDelta = new float[3];
    private final float[] supportBoxExtents = new float[3];
    private final float[] supportTerrainExtents = new float[3];
    private final BlockPos.MutableBlockPos fluidSamplePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidSurfacePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos supportBlockPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos terrainScanPos = new BlockPos.MutableBlockPos();
    private static final float TERRAIN_CONTACT_DISTANCE = 0.05f;
    private static final float GROUND_NORMAL_MIN_Y = 0.55f;
    private static final double SUPPORT_BELOW_TOLERANCE = 0.12;
    private static final double SUPPORT_ABOVE_TOLERANCE = 0.08;
    // How far below the feet the death-time ground lever will look for a floor, and how far above
    // them a surface may sit and still count as the one they are standing on.
    private static final double GROUND_LEVER_REACH = 0.5;
    private static final double GROUND_LEVER_ABOVE_TOLERANCE = 0.1;
    private static final float PHANTOM_VERTICAL_SPEED = 0.12f;
    private static final float FLUID_SURFACE_BELOW = 1.0f;
    private static final float FLUID_SURFACE_ABOVE = 0.2f;

    // Temporary drag-only collision tuning. Integrations provide target positions only;
    // these solver details stay internal to the physics worker.
    private static final int DRAG_COLLISION_RADIUS = 5;
    private static final float DRAG_FRICTION = 0.35f;
    private static final float DRAG_CCD_MOTION_THRESHOLD = 0.08f;
    private static final float DRAG_CCD_MIN_RADIUS = 0.12f;
    private static final float DRAG_STIFFNESS = 7.0f;
    // Deadbeat gain for the fixed 20 Hz step: one tick of error closes in exactly one tick.
    private static final float DRAG_MAX_STIFFNESS = 20.0f;
    // Margin, in squared blocks, by which the swapped limb assignment must win before it is taken.
    private static final double DRAG_SWAP_HYSTERESIS_SQ = 0.06;
    private static final float DRAG_TORSO_ASSIST = 0.65f;
    // Above a sprint, so towing at full speed is not clipped into a permanent trail.
    private static final float DRAG_TORSO_MAX_SPEED = 9.0f;
    // A paired velocity pull must not let joint correction accumulate into a somersault.
    // These are deliberately drag-only: ordinary death ragdolls retain their full spin.
    private static final float DRAG_ANGULAR_DAMPING = 0.38f;
    private static final float DRAG_LIMB_MAX_ANGULAR_SPEED = 1.15f;
    private static final float DRAG_TORSO_MAX_ANGULAR_SPEED = 0.90f;
    private final Vector3f draggedLimbVelocity = new Vector3f();
    private final float[] dragOriginalFriction = new float[RagdollTransform.MAX_PARTS];
    private final float[] dragOriginalCcdThreshold = new float[RagdollTransform.MAX_PARTS];
    private final float[] dragOriginalCcdRadius = new float[RagdollTransform.MAX_PARTS];
    private final Vector3f smoothedDragAnchor = new Vector3f();
    private boolean dragAnchorInitialized;
    private float dragFacingX = 1f;
    private float dragFacingZ;
    private boolean dragFacingInitialized;
    // Whether the paired drag currently hands the left limb the right-hand target.
    private boolean dragEndsCrossed;
    private boolean dragPhysicsActive;


    // Lifecycle
    private volatile int ticksExisted = 0;
    private final int lifetime;
    // External integrations can keep a downed body alive until they explicitly remove it.
    // This is read and written only on the physics worker through ClientRagdollManager.
    private boolean persistent;
    private volatile boolean destroyed = false;

    // Owner-streamed playback. A replicated body runs no solver at all: its bodies leave the dynamics
    // world and each tick writes an interpolated stream pose, so no divergent trajectory is built.
    private volatile boolean replicated;
    private volatile boolean hasReceivedStreamPose;
    private final ArrayDeque<StreamedPose> streamPoses = new ArrayDeque<>(STREAM_MAX_BUFFERED);
    private int lastStreamSequence = Integer.MIN_VALUE;
    private double streamPlaybackTick;
    private boolean streamPlaybackInitialized;
    // Playback runs this far behind the newest sample so ordinary network jitter has slack
    // to absorb rather than showing up as a stutter.
    private static final int STREAM_BUFFER_TICKS = 1;
    private static final int STREAM_MAX_BUFFERED = 12;
    private static final int STATIONARY_HARD_SYNC_TICKS = 100;
    private static final float HARD_SYNC_LINEAR_SPEED_SQ = 0.0004f;
    private static final float HARD_SYNC_ANGULAR_SPEED_SQ = 0.0025f;
    private int stationaryHardSyncTicks;
    private boolean stationaryHardSyncLatched;
    private final AtomicBoolean stationaryHardSyncRequested = new AtomicBoolean();
    // Separate from scratchInterpPos/scratchInterpRot, which belong to the render thread,
    // stream playback runs on the physics worker and must not share their storage.
    private final Vector3f streamScratchPos = new Vector3f();
    private final Quat4f streamScratchRot = new Quat4f();

    // World-space impact point of the killing blow, or null when none was captured. Physics
    // thread only; used as the lever arm so a death impulse can produce rotation.
    private Vector3f deathHitPoint;
    private final Vector3f scratchLever = new Vector3f();
    private final Vector3f scratchHalfExtents = new Vector3f();

    // One received stream frame, stamped with the local playback tick it arrived on.
    private static final class StreamedPose {
        final Vector3f[] positions = new Vector3f[RagdollTransform.MAX_PARTS];
        final Quat4f[] rotations = new Quat4f[RagdollTransform.MAX_PARTS];
        final int sampleTick;

        StreamedPose(RagdollTransform[] transforms, int sampleTick) {
            this.sampleTick = sampleTick;
            for (int i = 0; i < RagdollTransform.MAX_PARTS && i < transforms.length; i++) {
                RagdollTransform transform = transforms[i];
                if (transform == null || transform.partId != i) continue;
                positions[i] = new Vector3f(transform.position);
                rotations[i] = new Quat4f(transform.rotation);
            }
        }
    }

    // Settled detection
    private int settledTicks = 0;
    // Ticks a handed-over body must simulate before it may settle again, short on purpose: it only
    // ensures a frozen body genuinely wakes. When the settle is reported is decided by coming to rest.
    private static final int HANDOVER_SETTLE_GRACE_TICKS = 20;
    // Written on the physics worker, read from the client tick by the settle bridge.
    private volatile int settleGraceTicks = 0;
    private volatile boolean settled = false;
    private boolean pendingTerrainValidation = false;
    // Set with `settled` when the resting surface was fluid rather than solid ground, so the renderer
    // can bob the frozen body without re-running physics. Cleared on every wake path.
    private boolean settledOnLiquid = false;
    private static final float SETTLED_VELOCITY_THRESHOLD = 0.05f;
    private static final float SETTLED_ANG_VELOCITY_THRESHOLD = 0.15f;
    // The settle check runs on this cadence, so it is the granularity of settleDelayTicks.
    private static final int SETTLE_CHECK_INTERVAL = 5;
    private volatile boolean bodiesFrozen = false;
    // True: parts parked as static obstacles; false: removed from the world. See freezeBodies.
    private boolean parkedAsObstacle = false;

    // Displacement settle: snapshot every part every N ticks and force a settle when none has moved,
    // so pile jitter settles but falling limbs keep the body awake.
    private final Vector3f[] settleAnchorPos = new Vector3f[RagdollTransform.MAX_PARTS];
    private final Quat4f settleAnchorRot = new Quat4f(0f, 0f, 0f, 1f);
    private int settleAnchorTick = -1;
    // In piles, ragdolls jiggle for many seconds before velocity drops below the static threshold;
    // settling on displacement sooner drains the active set, cutting manifold count and solver work.
    private static final float SETTLE_DISPLACEMENT_THRESHOLD_SQ = 0.04f; // (0.2 blocks)²
    // ~8 degrees. Generous next to the wobble of a body shifting in a heap, and nowhere near what a
    // ragdoll going over from upright covers in the same window.
    private static final float SETTLE_DISPLACEMENT_ROTATION = 0.14f;    // radians

    // Cached transforms: current tick
    private final RagdollTransform[] cachedTransforms = new RagdollTransform[RagdollTransform.MAX_PARTS];
    private final Vector3f cachedTorsoPos = new Vector3f();
    private final PhysTransform tempTransform = new PhysTransform();

    // Previous-tick transforms for render interpolation
    private final Vector3f[] prevPositions = new Vector3f[RagdollTransform.MAX_PARTS];
    private final Quat4f[] prevRotations = new Quat4f[RagdollTransform.MAX_PARTS];
    private final Vector3f prevTorsoPos = new Vector3f();
    private boolean hasPrevTransforms = false;

    // Cached skins
    private final ResourceLocation cachedPlayerSkin;
    private final boolean cachedIsSlim;

    // Immutable render snapshot published atomically at the end of updateCachedTransforms and read
    // lock-free. About 5 MB/s at 50 ragdolls, all of which stays in eden; it lives one tick.
    public static final class TransformSnapshot implements RenderPlayback.Segment {
        public final Vector3f[] positions;     // 6 part positions (current)
        public final Quat4f[] rotations;       // 6 part rotations (current)
        public final Vector3f[] halfExtents;   // 6 part collision half extents (current)
        public final Vector3f[] prevPositions; // 6 part positions (previous tick)
        public final Quat4f[] prevRotations;   // 6 part rotations (previous tick)
        public final Vector3f cachedTorsoPos;
        public final Vector3f prevTorsoPos;
        public final boolean hasPrev;
        public final boolean destroyed;
        public final boolean settled;
        public final boolean frozen;
        public final int ageTicks;
        public final int sampleTick;
        // Client ticks of motion this snapshot covers; more than one when the worker overran.
        // RenderPlayback spreads it over that time.
        public final int spanTicks;

        @Override
        public int spanTicks() {
            return spanTicks;
        }

        TransformSnapshot(Vector3f[] positions, Quat4f[] rotations, Vector3f[] halfExtents,
                          Vector3f[] prevPositions, Quat4f[] prevRotations,
                          Vector3f cachedTorsoPos, Vector3f prevTorsoPos,
                          boolean hasPrev, boolean destroyed, boolean settled, boolean frozen,
                          int ageTicks, int sampleTick, int spanTicks) {
            this.spanTicks = spanTicks;
            this.positions = positions;
            this.rotations = rotations;
            this.halfExtents = halfExtents;
            this.prevPositions = prevPositions;
            this.prevRotations = prevRotations;
            this.cachedTorsoPos = cachedTorsoPos;
            this.prevTorsoPos = prevTorsoPos;
            this.hasPrev = hasPrev;
            this.destroyed = destroyed;
            this.settled = settled;
            this.frozen = frozen;
            this.ageTicks = ageTicks;
            this.sampleTick = sampleTick;
        }

        // Interpolated transform for the given part, for use on render thread.
        public RagdollTransform getInterpolatedTransform(RagdollPart part, float partialTick) {
            int i = part.index;
            if (i >= positions.length || positions[i] == null) return null;
            Vector3f curr = positions[i];
            Quat4f currRot = rotations[i];
            if (!hasPrev || prevPositions[i] == null) {
                return new RagdollTransform(i, curr, currRot);
            }
            Vector3f prev = prevPositions[i];
            Quat4f prevRot = prevRotations[i];
            Vector3f pos = new Vector3f(
                    prev.x + (curr.x - prev.x) * partialTick,
                    prev.y + (curr.y - prev.y) * partialTick,
                    prev.z + (curr.z - prev.z) * partialTick
            );
            Quat4f rot = slerpQuat(prevRot, currRot, partialTick);
            return new RagdollTransform(i, pos, rot);
        }

        public Vector3f getInterpolatedTorsoPosition(float partialTick) {
            if (!hasPrev) return cachedTorsoPos;
            return new Vector3f(
                    prevTorsoPos.x + (cachedTorsoPos.x - prevTorsoPos.x) * partialTick,
                    prevTorsoPos.y + (cachedTorsoPos.y - prevTorsoPos.y) * partialTick,
                    prevTorsoPos.z + (cachedTorsoPos.z - prevTorsoPos.z) * partialTick
            );
        }
    }

    // Latest published transform state: written by the physics thread in publishSnapshot(),
    // read freely by the render thread. Volatile, so every read sees a consistent snapshot.
    private volatile TransformSnapshot publishedSnapshot = null;
    private int snapshotSampleTick;
    // Client tick of the previous publish, so publishSnapshot can measure the gap. Physics worker only.
    private int lastPublishClientTick = Integer.MIN_VALUE;

    public TransformSnapshot getSnapshot() {
        // Do not render a server-coordinated observer from its locally constructed placeholder.
        // It becomes visible only after an owner frame or authoritative settled pose is applied.
        return replicated && !hasReceivedStreamPose ? null : publishedSnapshot;
    }

    // Render-thread playback clock. The worker publishes mid-tick, so a snapshot is played from
    // when it was published rather than from the tick boundary; see RenderPlayback for why.
    private final RenderPlayback<TransformSnapshot> playback = new RenderPlayback<>();

    private final Vector3f[] smoothPositions = new Vector3f[RagdollTransform.MAX_PARTS];
    private final Quat4f[] smoothRotations = new Quat4f[RagdollTransform.MAX_PARTS];
    private final Vector3f smoothTorsoPos = new Vector3f();
    private long smoothRenderFrame = Long.MIN_VALUE;
    private final Vector3f scratchInterpPos = new Vector3f();
    private final Quat4f scratchInterpRot = new Quat4f();

    // Update the smoothed render state from the latest snapshot, once per ragdoll per frame
    // from the renderer. Render thread only.
    public void updateSmoothedRenderState(TransformSnapshot snap, float partialTick) {
        updateSmoothedRenderState(snap, partialTick, Long.MIN_VALUE);
    }

    // Update once per render frame. The camera prepares its attached ragdoll before world
    // rendering, so the renderer's later call with the same token is deliberately a no-op.
    public void updateSmoothedRenderState(TransformSnapshot snap, float partialTick, long renderFrame) {
        if (snap == null || snap.destroyed) return;
        if (renderFrame != Long.MIN_VALUE && smoothRenderFrame == renderFrame) return;

        // Not partialTick, and not necessarily this snapshot: the clock runs from the publish and
        // holds a snapshot that arrives before the one on screen has finished. See RenderPlayback.
        playback.offer(snap);
        TransformSnapshot playing = playback.advance(System.nanoTime());
        if (playing == null) return;
        float t = playback.phase();

        for (int i = 0; i < playing.positions.length && i < RagdollTransform.MAX_PARTS; i++) {
            if (playing.positions[i] == null) continue;
            interpolateInto(playing, i, t, scratchInterpPos, scratchInterpRot);

            if (smoothPositions[i] == null) {
                smoothPositions[i] = new Vector3f(scratchInterpPos);
                smoothRotations[i] = new Quat4f(scratchInterpRot);
            } else {
                smoothPositions[i].set(scratchInterpPos);
                smoothRotations[i].set(scratchInterpRot);
            }
        }

        smoothTorsoPos.set(playing.getInterpolatedTorsoPosition(t));
        smoothRenderFrame = renderFrame;
    }

    // Transform built from the smoothed render state. Allocates a fresh RagdollTransform the
    // renderer treats as immutable for the frame.
    public RagdollTransform getSmoothedTransform(RagdollPart part) {
        return getSmoothedTransform(part.index);
    }

    public RagdollTransform getSmoothedTransform(int i) {
        if (i < 0 || i >= RagdollTransform.MAX_PARTS || smoothPositions[i] == null) return null;
        // smoothPositions is never cleared once seeded, so a part hidden mid-life would keep
        // rendering at wherever it was standing when the limb came off without this.
        if ((renderHiddenPartMask & (1 << i)) != 0) return null;
        return new RagdollTransform(i, new Vector3f(smoothPositions[i]), new Quat4f(smoothRotations[i]));
    }

    public Vector3f getSmoothedTorsoPos() { return new Vector3f(smoothTorsoPos); }

    // Part pose straight off the playback clock, skipping the per-frame smoothing cache.
    public RagdollTransform getPlaybackTransform(RagdollPart part) {
        TransformSnapshot playing = playback.current();
        return playing == null ? null : playing.getInterpolatedTransform(part, playback.phase());
    }

    // Helper: write the snapshot's interpolated transform into the provided buffers.
    private static void interpolateInto(TransformSnapshot snap, int i, float t,
                                        Vector3f outPos, Quat4f outRot) {
        Vector3f curr = snap.positions[i];
        Quat4f currRot = snap.rotations[i];
        if (!snap.hasPrev || snap.prevPositions[i] == null) {
            outPos.set(curr);
            outRot.set(currRot);
            return;
        }
        Vector3f prev = snap.prevPositions[i];
        Quat4f prevRot = snap.prevRotations[i];
        outPos.set(prev.x + (curr.x - prev.x) * t,
                   prev.y + (curr.y - prev.y) * t,
                   prev.z + (curr.z - prev.z) * t);
        slerpInto(prevRot, currRot, t, outRot);
    }

    // Slerp a→b by t, written into out (which may alias a or b).
    private static void slerpInto(Quat4f a, Quat4f b, float t, Quat4f out) {
        float dot = a.x*b.x + a.y*b.y + a.z*b.z + a.w*b.w;
        float bx = b.x, by = b.y, bz = b.z, bw = b.w;
        if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw; }
        float scale0, scale1;
        if (dot > 0.9995f) {
            scale0 = 1f - t; scale1 = t;
        } else {
            float angle = (float) Math.acos(dot);
            float sinAngle = (float) Math.sin(angle);
            scale0 = (float) Math.sin((1f - t) * angle) / sinAngle;
            scale1 = (float) Math.sin(t * angle) / sinAngle;
        }
        out.set(scale0 * a.x + scale1 * bx,
                scale0 * a.y + scale1 * by,
                scale0 * a.z + scale1 * bz,
                scale0 * a.w + scale1 * bw);
    }

    // Render data
    private final boolean isPlayer;
    private final String mobType;
    private final float scale;
    private final UUID playerUUID;
    private final String playerName;
    private final ItemStack helmet;
    private final ItemStack chestplate;
    private final ItemStack leggings;
    private final ItemStack boots;
    private final MobModelHelper.ModelType modelType;
    // Sheep state, meaningful only for a QUADRUPED sheep: wasSheared skips the wool overlay,
    // otherwise dyeColorId tints the SheepFurModel via DyeColor.byId.
    private final boolean wasSheared;
    private final int dyeColorId;
    // Other mob-specific overlay state captured at death: chargedCreeper draws the energy swirl and
    // saddledPig the saddle. Both are ignored unless mobType matches.
    private final boolean chargedCreeper;
    private final boolean saddledPig;
    private final boolean isBaby;
    // Villager profession state, empty type meaning no profession layer. Registry keys so mod-added
    // types survive without a remap; level 1..5, 0 unknown.
    private final String villagerType;
    private final String villagerProfession;
    private final int villagerLevel;
    private ResourceLocation cachedTexture;
    private final int originalEntityId;
    private final ClientLevel level;

    public static class SpawnData {
        public final int originalEntityId;
        public final boolean isPlayer;
        public final String mobType;
        public final MobModelHelper.ModelType modelType;
        public final float scale;
        public final UUID playerUUID;
        public final String playerName;
        public final ItemStack helmet;
        public final ItemStack chestplate;
        public final ItemStack leggings;
        public final ItemStack boots;
        // Exact death position (entity feet/origin) and inherited linear velocity in
        // Physics units (blocks per second).
        public final Vec3 position;
        public final float yRot;
        public final float xRot;
        public final Vec3 velocity;
        public final MobPoseCapture.MobPose capturedPose;
        public final boolean isSwimming;
        public final boolean isBaby;
        public final ResourceLocation texture;
        // Optional hit info, applied as a one-shot impulse to a single part once bodies exist, so a
        // killing bullet whips the right part along its travel direction. -1 skips it.
        public final int hitPartIndex;
        public final Vec3 hitImpulse;
        // Impact point relative to position; null when none was captured.
        public final Vec3 hitOffset;
        // Sheep state captured at death so the renderer can decide whether to draw the
        // wool layer and which dye color to tint it. Ignored for non-sheep mobs.
        public final boolean wasSheared;
        public final int dyeColorId; // 0..15, DyeColor.byId; only meaningful if !wasSheared
        // Other per-mob overlay flags. Only meaningful when mobType matches.
        public final boolean chargedCreeper;
        public final boolean saddledPig;
        // Villager profession state, empty type meaning no profession layer. Registry keys so mod-added
        // biomes and professions survive without an id remap.
        public final String villagerType;
        public final String villagerProfession;
        public final int villagerLevel;
        // Parts this body spawns without, as a RagdollPart bitmask; set only by amputation addons.
        public int severedMask;

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming,
                         ResourceLocation texture) {
            this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, false, texture,
                    -1, null, false, 0, false, false);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse) {
            this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, false, texture,
                    hitPartIndex, hitImpulse, false, 0, false, false);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId) {
            this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, false, texture,
                    hitPartIndex, hitImpulse, wasSheared, dyeColorId, false, false);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig) {
            this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, false, texture,
                    hitPartIndex, hitImpulse,
                    wasSheared, dyeColorId,
                    chargedCreeper, saddledPig,
                    "", "", 0);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming, boolean isBaby,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig) {
            this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, isBaby, texture,
                    hitPartIndex, hitImpulse,
                    wasSheared, dyeColorId,
                    chargedCreeper, saddledPig,
                    "", "", 0);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming, boolean isBaby,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig,
                         String villagerType, String villagerProfession, int villagerLevel) {
            this.hitOffset = null;
            this.originalEntityId = originalEntityId;
            this.isPlayer = isPlayer;
            this.mobType = mobType;
            this.modelType = resolveModelType(isPlayer, mobType, null);
            this.scale = scale;
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.position = position;
            this.yRot = yRot;
            this.xRot = xRot;
            this.velocity = velocity;
            this.capturedPose = capturedPose;
            this.isSwimming = isSwimming;
            this.isBaby = isBaby;
            this.texture = texture;
            this.hitPartIndex = hitPartIndex;
            this.hitImpulse = hitImpulse;
            this.wasSheared = wasSheared;
            this.dyeColorId = dyeColorId;
            this.chargedCreeper = chargedCreeper;
            this.saddledPig = saddledPig;
            this.villagerType = villagerType != null ? villagerType : "";
            this.villagerProfession = villagerProfession != null ? villagerProfession : "";
            this.villagerLevel = villagerLevel;
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType,
                         MobModelHelper.ModelType modelType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming, boolean isBaby,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig) {
            this(originalEntityId, isPlayer, mobType, modelType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots,
                    position, yRot, xRot, velocity,
                    capturedPose, isSwimming, isBaby, texture,
                    hitPartIndex, hitImpulse,
                    wasSheared, dyeColorId,
                    chargedCreeper, saddledPig,
                    "", "", 0);
        }

        public SpawnData(int originalEntityId, boolean isPlayer, String mobType,
                         MobModelHelper.ModelType modelType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming, boolean isBaby,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig,
                         String villagerType, String villagerProfession, int villagerLevel) {
            this(originalEntityId, isPlayer, mobType, modelType, scale, playerUUID, playerName,
                    helmet, chestplate, leggings, boots, position, yRot, xRot, velocity,
                    capturedPose, isSwimming, isBaby, texture, hitPartIndex, hitImpulse, null,
                    wasSheared, dyeColorId, chargedCreeper, saddledPig,
                    villagerType, villagerProfession, villagerLevel);
        }

        // Canonical form. hitOffset is the impact point relative to position, the lever arm that turns
        // the death impulse into rotation; null falls back to a torque-free centre-of-mass impulse.
        public SpawnData(int originalEntityId, boolean isPlayer, String mobType,
                         MobModelHelper.ModelType modelType, float scale,
                         UUID playerUUID, String playerName,
                         ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         Vec3 position, float yRot, float xRot, Vec3 velocity,
                         MobPoseCapture.MobPose capturedPose, boolean isSwimming, boolean isBaby,
                         ResourceLocation texture,
                         int hitPartIndex, Vec3 hitImpulse, Vec3 hitOffset,
                         boolean wasSheared, int dyeColorId,
                         boolean chargedCreeper, boolean saddledPig,
                         String villagerType, String villagerProfession, int villagerLevel) {
            this.hitOffset = hitOffset;
            this.originalEntityId = originalEntityId;
            this.isPlayer = isPlayer;
            this.mobType = mobType;
            this.modelType = resolveModelType(isPlayer, mobType, modelType);
            this.scale = scale;
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.position = position;
            this.yRot = yRot;
            this.xRot = xRot;
            this.velocity = velocity;
            this.capturedPose = capturedPose;
            this.isSwimming = isSwimming;
            this.isBaby = isBaby;
            this.texture = texture;
            this.hitPartIndex = hitPartIndex;
            this.hitImpulse = hitImpulse;
            this.wasSheared = wasSheared;
            this.dyeColorId = dyeColorId;
            this.chargedCreeper = chargedCreeper;
            this.saddledPig = saddledPig;
            this.villagerType = villagerType != null ? villagerType : "";
            this.villagerProfession = villagerProfession != null ? villagerProfession : "";
            this.villagerLevel = villagerLevel;
        }

        private static MobModelHelper.ModelType resolveModelType(boolean isPlayer, String mobType,
                                                                 MobModelHelper.ModelType modelType) {
            if (isPlayer) return MobModelHelper.ModelType.HUMANOID_STANDARD;
            return modelType != null ? modelType : MobModelHelper.getModelTypeFromMobType(mobType);
        }
    }

    public ClientRagdoll(SpawnData data, ClientPhysicsWorld physicsWorld) {
        this.id = data.originalEntityId;
        this.physicsWorld = physicsWorld;
        this.world = physicsWorld.getPhysics();
        this.level = physicsWorld.getLevel();
        this.lifetime = RagdollifiedConfig.getRagdollLifetime();

        this.isPlayer = data.isPlayer;
        this.mobType = data.mobType;
        this.scale = data.scale;
        this.playerUUID = data.playerUUID;
        this.playerName = data.playerName != null ? data.playerName : "";
        this.helmet = data.helmet != null ? data.helmet.copy() : ItemStack.EMPTY;
        this.chestplate = data.chestplate != null ? data.chestplate.copy() : ItemStack.EMPTY;
        this.leggings = data.leggings != null ? data.leggings.copy() : ItemStack.EMPTY;
        this.boots = data.boots != null ? data.boots.copy() : ItemStack.EMPTY;
        this.cachedTexture = data.texture;
        this.originalEntityId = data.originalEntityId;
        this.wasSheared = data.wasSheared;
        this.dyeColorId = data.dyeColorId;
        this.chargedCreeper = data.chargedCreeper;
        this.saddledPig = data.saddledPig;
        this.isBaby = data.isBaby;
        this.villagerType = data.villagerType != null ? data.villagerType : "";
        this.villagerProfession = data.villagerProfession != null ? data.villagerProfession : "";
        this.villagerLevel = data.villagerLevel;

        this.modelType = data.modelType;

        createRagdollBodies(data);
        ragdollPartsSet.addAll(ragdollParts);
        // Before the first transform pass, so a body that spawned already missing a limb never
        // publishes a snapshot carrying it.
        hidePartsFromMask(data.severedMask);
        updateCachedTransforms();
        updateLocalWorldCollision();

        // Death-time directional impulse, applied after bodies exist and transforms are cached so the
        // first rendered frame already shows the recoil. On the physics worker, so the world is safe.
        if (data.hitImpulse != null) {
            // World-space impact point, used as the lever arm below.
            deathHitPoint = data.hitOffset == null ? null : new Vector3f(
                    (float) (data.position.x + data.hitOffset.x),
                    (float) (data.position.y + data.hitOffset.y),
                    (float) (data.position.z + data.hitOffset.z));
            float sizeScale = modelSizeVelocityScale();
            if (data.hitPartIndex == RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX) {
                applyGlobalVelocityKick(data.hitImpulse.scale(sizeScale));
            } else if (data.hitPartIndex == CENTER_HIT_PART_INDEX) {
                applyCenteredDeathImpulse(data.hitImpulse);
            } else if (data.hitPartIndex >= 0 && data.hitPartIndex < ragdollParts.size()) {
                RagdollPart part = RagdollPart.byIndex(data.hitPartIndex);
                applyHit(data.hitPartIndex, scaledImpulse(data.hitImpulse, sizeScale
                        * (part != null ? RagdollifiedConfig.getDeathPartKnockbackMultiplier(part) : 1.0f)),
                        deathHitPoint, true);
            }
        }
        // Resolve player skin and slim variant now, while the entity is loaded. Going through
        // ClientPlayerSkinCache also seeds the per-UUID cache an addon's replacement body reads.
        if (isPlayer && playerUUID != null) {
            ClientPlayerSkinCache.Skin resolved = ClientPlayerSkinCache.resolve(playerUUID);
            cachedPlayerSkin = resolved.texture;
            cachedIsSlim = resolved.slim;
        } else {
            cachedPlayerSkin = null;
            cachedIsSlim = false;
        }
    }

    // Tick: mirrors MobRagdollPhysics.update() order exactly

    // Per-phase timings aggregated over every ragdoll tick in the cycle, reset and logged by
    // ClientRagdollManager.tickAll. Public so the manager can read it; client thread only.
    public static final class PhaseStats {
        public long updateCachedTransformsNanos;
        public long velocityClampNanos;
        public long fluidForcesNanos;
        public long playerCollisionsNanos;
        public long updateLocalWorldCollisionNanos;
        public long correctInterpenetrationsNanos;
        public long updateSettledStateNanos;
        public int distanceFrozenThisTick;   // ragdolls frozen because too far from camera
        public int unfrozenThisTick;         // ragdolls re-added after coming back into range
        public int floorLostThisTick;        // settled ragdolls woken because the floor under them disappeared
        public int phantomCacheClearedThisTick; // active ragdolls stuck on stale cache had it invalidated

        public void reset() {
            updateCachedTransformsNanos = 0;
            velocityClampNanos = 0;
            fluidForcesNanos = 0;
            playerCollisionsNanos = 0;
            updateLocalWorldCollisionNanos = 0;
            correctInterpenetrationsNanos = 0;
            updateSettledStateNanos = 0;
            distanceFrozenThisTick = 0;
            unfrozenThisTick = 0;
            floorLostThisTick = 0;
            phantomCacheClearedThisTick = 0;
        }
    }
    public static final PhaseStats PHASE_STATS = new PhaseStats();

    public void tick(Vec3 cameraPos) {
        if (destroyed) return;

        // Replicated bodies are driven entirely by the owner's stream. Checked before the
        // cached-transform read because playback writes the transforms it then publishes.
        if (replicated) {
            tickReplicated();
            return;
        }

        long t = System.nanoTime();
        updateCachedTransforms();
        PHASE_STATS.updateCachedTransformsNanos += System.nanoTime() - t;

        if (settled) {
            // Settled bodies keep aging toward despawn even while the player is away.
            ticksExisted++;
            if (!persistent && ticksExisted >= lifetime) {
                destroy();
                return;
            }
            if (pendingTerrainValidation) {
                BlockPos torsoBlock = currentTorsoBlock();
                if (!isSupportAreaLoaded()) return;
                pendingTerrainValidation = false;
                boolean onGround = collectWorldTerrainSupport();
                boolean atSurface = isFloatingAtSurface();
                boolean submergedBelowSurface = !atSurface && isSubmergedBelowSurface();
                if ((!onGround && !atSurface) || submergedBelowSurface) {
                    physicsWorld.cacheStats.poseRejected++;
                    unsettleAndDropCache();
                    PHASE_STATS.floorLostThisTick++;
                    return;
                }
                settledOnLiquid = atSurface;
                settledGroundSupportBlocks.clear();
                settledSupportRagdolls.clear();
                if (!atSurface) settledGroundSupportBlocks.addAll(activeGroundSupportBlocks);
                settledTerrainCenter = torsoBlock;
                settledTerrainSignature = computeTerrainSignature(torsoBlock,baseCollisionRadius());
                hasSettledTerrainSignature = true;
            }
            // Periodic support check: a broken support block does not always fire a client-side
            // notify, so every 10 ticks re-verify ground or carrying fluid and rebuild the cache.
            boolean terrainChanged = hasSettledTerrainSignature
                    && Math.floorMod(ticksExisted + id, 20) == 0
                    && computeTerrainSignature(settledTerrainCenter,baseCollisionRadius()) != settledTerrainSignature;
            boolean supportLost = false;
            boolean submergedBelowSurface = false;
            if (ticksExisted % 10 == 0) {
                boolean atFluidSurface = isFloatingAtSurface();
                boolean liquidSupported = settledOnLiquid && atFluidSurface;
                submergedBelowSurface = !atFluidSurface && isSubmergedBelowSurface();
                supportLost = !liquidSupported && !hasSettledGroundSupport();
            }
            if (terrainChanged || supportLost || submergedBelowSurface) {
                unsettleAndDropCache();
                PHASE_STATS.floorLostThisTick++;
            }
            return;
        }

        double distSq = cameraPos.distanceToSqr(cachedTorsoPos.x, cachedTorsoPos.y, cachedTorsoPos.z);

        double physicsDistance = RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE);
        if (distSq > physicsDistance * physicsDistance) {
            // Too far to simulate: pause without advancing ticksExisted, so the body is suspended
            // rather than aging and resumes exactly where it left off, settle report included.
            if (!bodiesFrozen) {
                freezeBodies();
                PHASE_STATS.distanceFrozenThisTick++;
            }
            return;
        } else if (bodiesFrozen) {
            unfreezeBodies();
            PHASE_STATS.unfrozenThisTick++;
        }

        updateStationaryHardSync();

        // Actively simulating now: advance the lifetime clock (skipped while paused above).
        ticksExisted++;
        if (!persistent && ticksExisted >= lifetime) {
            destroy();
            return;
        }

        // Mirrors MobRagdollPhysics.update() order exactly:
        // 1. velocity clamp
        t = System.nanoTime();
        // Hoisted out of the loop: these were a config lookup per body per tick, and a config value
        // cannot change between two parts of the same ragdoll in the same tick anyway.
        float maxLinearSpeed = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_LINEAR_SPEED);
        float maxAngularSpeed = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_ANGULAR_SPEED);
        for (PhysicsBody r : ragdollParts) {
            r.getLinearVelocity(scratchVel);
            float speed = scratchVel.length();
            if (speed > maxLinearSpeed) { scratchVel.scale(maxLinearSpeed / speed); r.setLinearVelocity(scratchVel); }

            r.getAngularVelocity(scratchAng);
            float angSpeed = scratchAng.length();
            if (angSpeed > maxAngularSpeed) { scratchAng.scale(maxAngularSpeed / angSpeed); r.setAngularVelocity(scratchAng); }
        }
        PHASE_STATS.velocityClampNanos += System.nanoTime() - t;

        // 2. hand the joints back while the body is falling
        updateAirborneRelaxation();

        // 2b. Flail, if requested; runs after the airborne pass so its targets win.
        updateFlail();

        // Fluid forces always run, ungated by camera distance: the lookups are cheap and a body
        // falling into water would otherwise sink to the bottom while the player looked away.
        t = System.nanoTime();
        applyFluidForces();
        PHASE_STATS.fluidForcesNanos += System.nanoTime() - t;

        // 3. player collisions
        double playerCollisionDistance = RagdollifiedConfig.get(RagdollifiedConfig.PLAYER_COLLISION_DISTANCE);
        if (distSq <= playerCollisionDistance * playerCollisionDistance) {
            t = System.nanoTime();
            applyPlayerCollisions();
            PHASE_STATS.playerCollisionsNanos += System.nanoTime() - t;
        }

        // 4. update world collision geometry
        t = System.nanoTime();
        updateLocalWorldCollision();
        PHASE_STATS.updateLocalWorldCollisionNanos += System.nanoTime() - t;

        // Settled detection: only checked on this cadence to save CPU
        if (ticksExisted % SETTLE_CHECK_INTERVAL == 0) {
            t = System.nanoTime();
            updateSettledState();
            PHASE_STATS.updateSettledStateNanos += System.nanoTime() - t;
        }

        // Phantom-cache check: rebuild if stopped with no real support (skipped in fluid).
        // Must follow the support check above, so bodies resting on ragdolls aren't shoved.
        if (ticksExisted % 10 == 0
                && collisionGeometryReadyForContacts()
                && !collectTerrainGroundContacts()
                && supportingRagdolls.isEmpty()
                && hasLowVerticalSpeed()
                && !isAnyPartInLiquid()) {
            physicsWorld.invalidateCollisionGeometry(currentCollisionGeometry);
            releaseCurrentCollisionGeometry();

            for (PhysicsBody r : ragdollParts) {
                r.setSleepingAllowed(false);
                r.activate();

                r.getLinearVelocity(scratchVel);
                if (scratchVel.y > -1.0f) {
                    scratchVel.y = -1.0f;
                    r.setLinearVelocity(scratchVel);
                }
            }

            PHASE_STATS.phantomCacheClearedThisTick++;
        }
    }

    // True if this ragdoll is actively being simulated (not destroyed, settled, or frozen).
    public boolean isActivelySimulating() {
        return !destroyed && !replicated && !settled && !bodiesFrozen;
    }

    // Returns false while the ragdoll has no confirmed ground or surface support.
    public boolean forceSettle() {
        if (destroyed || settled) return false;
        boolean onGround = collisionGeometryReadyForContacts() && collectTerrainGroundContacts();
        boolean atSurface = isFloatingAtSurface();
        if (!atSurface && isSubmergedBelowSurface()) return false;
        // Looser than voluntary settle: any supporter counts, so the active cap can retire bodies in a pile.
        if (!onGround && !atSurface && supportingRagdolls.isEmpty()) return false;
        settleAtCurrentSupport(atSurface, "active-cap");
        return true;
    }

    // Wake from settled because the support floor is gone: drop this body's cached geometry and
    // invalidate nearby entries so the area rebuilds. Called from the floor check in tick().
    private void unsettleAndDropCache() {
        settled = false;
        pendingTerrainValidation = false;
        settledOnLiquid = false;
        settledTicks = 0;
        settledSupportRagdolls.clear();
        hasSettledTerrainSignature = false;
        markSettledPoseDirty();
        if (bodiesFrozen) unfreezeBodies();
        // Nuke the cache near our torso, which covers the case where the broken block's
        // event never fired on the client (server-initiated change, etc.).
        BlockPos torsoBlock = new BlockPos(
                (int) Math.floor(cachedTorsoPos.x),
                (int) Math.floor(cachedTorsoPos.y),
                (int) Math.floor(cachedTorsoPos.z));
        physicsWorld.invalidateCacheRegion(torsoBlock,baseCollisionRadius());
        releaseCurrentCollisionGeometry();
    }

    private void freezeBodies() {
        bodiesFrozen = true;
        if (settled) {
            settledTerrainCenter = new BlockPos(
                    (int) Math.floor(cachedTorsoPos.x),
                    (int) Math.floor(cachedTorsoPos.y),
                    (int) Math.floor(cachedTorsoPos.z));
            // Only reuse the handle's signature when it covers the same radius,
            // or the body would wake every tick.
            if (currentCollisionGeometry != null
                    && currentCollisionGeometry.isValid()
                    && currentCollisionRadius == baseCollisionRadius()
                    && settledTerrainCenter.equals(lastCollisionCenter)) {
                settledTerrainSignature = currentCollisionGeometry.terrainSignature();
            } else {
                settledTerrainSignature = computeTerrainSignature(settledTerrainCenter,baseCollisionRadius());
            }
            hasSettledTerrainSignature = true;
        } else {
            hasSettledTerrainSignature = false;
        }
        releaseCurrentCollisionGeometry();
        // Zero velocities so the bodies start clean, and while they are still dynamic: a parked body
        // ignores velocity writes.
        for (PhysicsBody r : ragdollParts) {
            r.setLinearVelocity(new Vector3f(0, 0, 0));
            r.setAngularVelocity(new Vector3f(0, 0, 0));
        }
        // The joints go either way. Between two parked parts they hold nothing, and re-adding them
        // before the parts are dynamic again would hand the solver a joint it cannot solve.
        for (PhysicsConstraint c : ragdollJoints) world.removeConstraint(c);

        // Settled bodies are parked as static obstacles so others can't pass through them;
        // bodies frozen for distance leave the world entirely.
        parkedAsObstacle = settled;
        if (parkedAsObstacle) {
            for (PhysicsBody r : ragdollParts) r.setStatic(true);
        } else {
            for (PhysicsBody r : ragdollParts) world.removeBody(r);
        }
    }

    private void unfreezeBodies() {
        bodiesFrozen = false;
        if (!settled) {
            hasSettledTerrainSignature = false;
            settledGroundSupportBlocks.clear();
            settledSupportRagdolls.clear();
        }
        // Bodies back to dynamic, or back into the world, before constraints either way: the
        // constraint solver expects live bodies it can actually move.
        if (parkedAsObstacle) {
            for (PhysicsBody r : ragdollParts) r.setStatic(false);
        }
        parkedAsObstacle = false;
        for (PhysicsBody r : ragdollParts) {
            world.addBody(r);
            // Sleeping stays off so the body never auto-sleeps: after a floor-loss wake velocity is
            // zero, and the engine would deactivate it seconds later, leaving the ragdoll floating.
            r.setSleepingAllowed(false);
            r.activate();
        }
        for (PhysicsConstraint c : ragdollJoints) world.addConstraint(c);
    }

    private void updateSettledState() {
        // A body handed back as a fresh death ragdoll already lies still, so the velocity gate would
        // re-freeze it within a tenth of a second. Hold the settle off until it has really simulated.
        if (settleGraceTicks > 0) {
            settleGraceTicks--;
            settledTicks = 0;
            // Drag the displacement anchor along too, or that fallback fires the moment the
            // grace expires and undoes the whole point of it.
            captureSettleAnchor();
            return;
        }
        boolean onGround = collisionGeometryReadyForContacts() && collectTerrainGroundContacts();
        boolean atSurface = isFloatingAtSurface();
        if (!atSurface && isSubmergedBelowSurface()) {
            settledTicks = 0;
            return;
        }
        // A voluntary settle only counts ragdolls that have themselves stopped.
        boolean canSettle = onGround || atSurface || supportedBySettledBody;

        // Buoyancy and gravity leave ~0.07 m/s residual in vy at the half-submerged equilibrium, which
        // the 0.05 ground threshold rejects; 0.4 catches that bob while still rejecting moving bodies.
        float vThreshold = atSurface ? 0.4f : SETTLED_VELOCITY_THRESHOLD;
        float aThreshold = atSurface ? 0.6f : SETTLED_ANG_VELOCITY_THRESHOLD;

        boolean allSlow = true;
        for (PhysicsBody r : ragdollParts) {
            r.getLinearVelocity(scratchVel);
            r.getAngularVelocity(scratchAng);
            if (scratchVel.length() > vThreshold || scratchAng.length() > aThreshold) {
                allSlow = false;
                break;
            }
        }

        if (allSlow && canSettle) {
            settledTicks++;

            if (settledTicks >= settleChecksRequired()) {
                settleAtCurrentSupport(atSurface, "velocity");
                return;
            }
        } else {
            settledTicks = 0;
        }

        // Displacement fallback: settle a torso that hasn't moved or rotated over the window.
        // Rotation matters, or a body toppling about its feet froze mid-fall.
        int window = settleDisplacementWindow();
        if (settleAnchorTick < 0) {
            captureSettleAnchor();
        } else if (ticksExisted - settleAnchorTick >= window) {
            boolean stayedPut = everyPartStayedPut()
                    && torsoRotationSinceAnchor() < SETTLE_DISPLACEMENT_ROTATION;
            if (stayedPut && canSettle) {
                settleAtCurrentSupport(atSurface, "displacement");
                return;
            }
            captureSettleAnchor();
        }
    }

    private void captureSettleAnchor() {
        for (int i = 0; i < settleAnchorPos.length; i++) {
            RagdollTransform transform = i < cachedTransforms.length ? cachedTransforms[i] : null;
            if (transform == null) {
                settleAnchorPos[i] = null;
                continue;
            }
            if (settleAnchorPos[i] == null) settleAnchorPos[i] = new Vector3f();
            settleAnchorPos[i].set(transform.position);
        }
        if (cachedTransforms[0] != null) settleAnchorRot.set(cachedTransforms[0].rotation);
        settleAnchorTick = ticksExisted;
    }

    // True while no part has travelled further than the displacement threshold since the anchor.
    private boolean everyPartStayedPut() {
        for (int i = 0; i < settleAnchorPos.length; i++) {
            Vector3f anchor = settleAnchorPos[i];
            RagdollTransform transform = i < cachedTransforms.length ? cachedTransforms[i] : null;
            if (anchor == null || transform == null) continue;
            float dx = transform.position.x - anchor.x;
            float dy = transform.position.y - anchor.y;
            float dz = transform.position.z - anchor.z;
            if (dx * dx + dy * dy + dz * dz >= SETTLE_DISPLACEMENT_THRESHOLD_SQ) return false;
        }
        return true;
    }

    // Angle in radians between the torso's orientation now and when the anchor was taken.
    private float torsoRotationSinceAnchor() {
        if (cachedTransforms[0] == null) return 0f;
        Quat4f now = cachedTransforms[0].rotation;
        float dot = now.x * settleAnchorRot.x + now.y * settleAnchorRot.y
                + now.z * settleAnchorRot.z + now.w * settleAnchorRot.w;
        return (float) (2.0 * Math.acos(Math.min(1.0, Math.abs(dot))));
    }

    // How many consecutive quiet checks the velocity gate wants. The checks run every five ticks, so
    // the configured delay is rounded up to whole checks and never drops below one.
    private static int settleChecksRequired() {
        int delay = RagdollifiedConfig.get(RagdollifiedConfig.SETTLE_DELAY_TICKS);
        return Math.max(1, (delay + SETTLE_CHECK_INTERVAL - 1) / SETTLE_CHECK_INTERVAL);
    }

    // The displacement fallback is the looser of the two paths: it settles bodies that are still
    // moving, just not going anywhere, so it waits twice as long as the velocity gate.
    private static int settleDisplacementWindow() {
        return Math.max(SETTLE_CHECK_INTERVAL,
                2 * RagdollifiedConfig.get(RagdollifiedConfig.SETTLE_DELAY_TICKS));
    }

    // Angular speed at freeze above which a limb counts as visibly moving; half the settle threshold.
    private static final float SETTLE_REPORT_ANG_SPEED = SETTLED_ANG_VELOCITY_THRESHOLD * 0.5f;

    private void settleAtCurrentSupport(boolean atSurface, String reason) {
        reportPrematureSettle(reason);
        settled = true;
        pendingTerrainValidation = false;
        settledOnLiquid = atSurface;
        settledGroundSupportBlocks.clear();
        settledSupportRagdolls.clear();
        if (!atSurface) {
            settledGroundSupportBlocks.addAll(activeGroundSupportBlocks);
            settledSupportRagdolls.addAll(supportingRagdolls);
        }
        freezeBodies();
    }

    private boolean hasSettledGroundSupport() {
        if (!settledGroundSupportBlocks.isEmpty()) {
            for (BlockPos pos : settledGroundSupportBlocks) {
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !state.getCollisionShape(level, pos).isEmpty()) {
                    return true;
                }
            }
            if (settledSupportRagdolls.isEmpty()) return false;
        }
        // Held up by a heap: only check the supporting ragdoll still exists, not whether it's at rest,
        // since a moving supporter already wakes its neighbours.
        if (!settledSupportRagdolls.isEmpty()) {
            for (int supporterId : settledSupportRagdolls) {
                ClientRagdoll supporter = ClientRagdollManager.get(supporterId);
                if (supporter != null && !supporter.isDestroyed()) return true;
            }
            return false;
        }
        return collectWorldTerrainSupport();
    }

    private boolean collectWorldTerrainSupport() {
        activeGroundSupportBlocks.clear();
        for (PhysicsBody body : ragdollParts) {
            body.getWorldTransform(tempTransform);
            body.getWorldAabb(supportAabbMin, supportAabbMax);

            double minX = supportAabbMin.x;
            double maxX = supportAabbMax.x;
            double minZ = supportAabbMin.z;
            double maxZ = supportAabbMax.z;
            double bottom = supportAabbMin.y;
            if (maxX <= minX || maxZ <= minZ) continue;

            int blockMinX = (int) Math.floor(minX);
            int blockMaxX = (int) Math.floor(maxX - 1.0e-5);
            int blockMinY = (int) Math.floor(bottom - SUPPORT_BELOW_TOLERANCE);
            int blockMaxY = (int) Math.floor(bottom + SUPPORT_ABOVE_TOLERANCE);
            int blockMinZ = (int) Math.floor(minZ);
            int blockMaxZ = (int) Math.floor(maxZ - 1.0e-5);
            PhysicsShape partShape = body.getShape();
            boolean isBoxPart = partShape.isBox();
            if (isBoxPart) partShape.getHalfExtents(supportHalfExtents);

            for (int x = blockMinX; x <= blockMaxX; x++) {
                for (int y = blockMinY; y <= blockMaxY; y++) {
                    for (int z = blockMinZ; z <= blockMaxZ; z++) {
                        supportBlockPos.set(x, y, z);
                        VoxelShape shape = level.getBlockState(supportBlockPos)
                                .getCollisionShape(level, supportBlockPos);
                        if (shape.isEmpty()) continue;
                        for (AABB box : shape.toAabbs()) {
                            double worldMinX = x + box.minX;
                            double worldMaxX = x + box.maxX;
                            double worldMinZ = z + box.minZ;
                            double worldMaxZ = z + box.maxZ;
                            double worldTop = y + box.maxY;
                            if (worldTop < bottom - SUPPORT_BELOW_TOLERANCE
                                    || worldTop > bottom + SUPPORT_ABOVE_TOLERANCE) continue;
                            boolean intersects = isBoxPart
                                    ? orientedBoxIntersectsAabb(
                                            tempTransform, supportHalfExtents,
                                            worldMinX, y + box.minY, worldMinZ,
                                            worldMaxX,
                                            worldTop + SUPPORT_BELOW_TOLERANCE,
                                            worldMaxZ)
                                    : worldMaxX > minX && worldMinX < maxX
                                            && worldMaxZ > minZ && worldMinZ < maxZ;
                            if (!intersects) continue;
                            activeGroundSupportBlocks.add(supportBlockPos.immutable());
                            break;
                        }
                    }
                }
            }
        }
        return !activeGroundSupportBlocks.isEmpty();
    }

    private boolean isSupportAreaLoaded() {
        for (PhysicsBody body : ragdollParts) {
            body.getWorldTransform(tempTransform);
            body.getWorldAabb(supportAabbMin, supportAabbMax);
            int minChunkX = ((int) Math.floor(supportAabbMin.x)) >> 4;
            int maxChunkX = ((int) Math.floor(supportAabbMax.x)) >> 4;
            int minChunkZ = ((int) Math.floor(supportAabbMin.z)) >> 4;
            int maxChunkZ = ((int) Math.floor(supportAabbMax.z)) >> 4;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return false;
                }
            }
        }
        return true;
    }

    private boolean orientedBoxIntersectsAabb(
            PhysTransform transform, Vector3f halfExtents,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        supportRotation[0] = transform.basis.m00;
        supportRotation[1] = transform.basis.m10;
        supportRotation[2] = transform.basis.m20;
        supportRotation[3] = transform.basis.m01;
        supportRotation[4] = transform.basis.m11;
        supportRotation[5] = transform.basis.m21;
        supportRotation[6] = transform.basis.m02;
        supportRotation[7] = transform.basis.m12;
        supportRotation[8] = transform.basis.m22;
        for (int i = 0; i < 9; i++) {
            supportAbsRotation[i] = Math.abs(supportRotation[i]) + 1.0e-5f;
        }

        supportBoxExtents[0] = halfExtents.x;
        supportBoxExtents[1] = halfExtents.y;
        supportBoxExtents[2] = halfExtents.z;
        supportTerrainExtents[0] = (float) ((maxX - minX) * 0.5);
        supportTerrainExtents[1] = (float) ((maxY - minY) * 0.5);
        supportTerrainExtents[2] = (float) ((maxZ - minZ) * 0.5);

        float dx = (float) ((minX + maxX) * 0.5) - transform.origin.x;
        float dy = (float) ((minY + maxY) * 0.5) - transform.origin.y;
        float dz = (float) ((minZ + maxZ) * 0.5) - transform.origin.z;
        supportTranslation[0] = dx * supportRotation[0]
                + dy * supportRotation[1] + dz * supportRotation[2];
        supportTranslation[1] = dx * supportRotation[3]
                + dy * supportRotation[4] + dz * supportRotation[5];
        supportTranslation[2] = dx * supportRotation[6]
                + dy * supportRotation[7] + dz * supportRotation[8];

        for (int i = 0; i < 3; i++) {
            float radius = supportTerrainExtents[0] * supportAbsRotation[i * 3]
                    + supportTerrainExtents[1] * supportAbsRotation[i * 3 + 1]
                    + supportTerrainExtents[2] * supportAbsRotation[i * 3 + 2];
            if (Math.abs(supportTranslation[i]) > supportBoxExtents[i] + radius) return false;
        }

        supportWorldDelta[0] = dx;
        supportWorldDelta[1] = dy;
        supportWorldDelta[2] = dz;
        for (int j = 0; j < 3; j++) {
            float radius = supportTerrainExtents[j]
                    + supportBoxExtents[0] * supportAbsRotation[j]
                    + supportBoxExtents[1] * supportAbsRotation[3 + j]
                    + supportBoxExtents[2] * supportAbsRotation[6 + j];
            if (Math.abs(supportWorldDelta[j]) > radius) return false;
        }

        for (int i = 0; i < 3; i++) {
            int i1 = (i + 1) % 3;
            int i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3;
                int j2 = (j + 2) % 3;
                float boxRadius = supportBoxExtents[i1] * supportAbsRotation[i2 * 3 + j]
                        + supportBoxExtents[i2] * supportAbsRotation[i1 * 3 + j];
                float terrainRadius =
                        supportTerrainExtents[j1] * supportAbsRotation[i * 3 + j2]
                        + supportTerrainExtents[j2] * supportAbsRotation[i * 3 + j1];
                float distance = Math.abs(
                        supportTranslation[i2] * supportRotation[i1 * 3 + j]
                        - supportTranslation[i1] * supportRotation[i2 * 3 + j]);
                if (distance > boxRadius + terrainRadius) return false;
            }
        }
        return true;
    }

    // Walks this tick's contacts once: returns the supporting blocks, and fills fields for whether
    // anything supports the body and which settled ragdolls do.
    private boolean collectTerrainGroundContacts() {
        activeGroundSupportBlocks.clear();
        supportingRagdolls.clear();
        supportedBySettledBody = false;
        world.forEachContactPair(this::collectTerrainGroundContact);
        return !activeGroundSupportBlocks.isEmpty();
    }

    private void collectTerrainGroundContact(ContactPair pair) {
        PhysicsBody bodyA = pair.bodyA();
        PhysicsBody bodyB = pair.bodyB();
        boolean aIsThis = ragdollPartsSet.contains(bodyA);
        boolean bIsThis = ragdollPartsSet.contains(bodyB);
        if (aIsThis == bIsThis) return;

        PhysicsBody other = aIsThis ? bodyB : bodyA;
        BlockPos supportPos = other.getInvMass() == 0f
                && other.getUserPointer() instanceof BlockPos pos ? pos : null;
        // Anything dynamic that is not terrain: another ragdoll's part, or a proxy. Proxies carry no
        // owner and are skipped, which is right: a player pushing a body is not holding it up.
        ClientRagdoll otherOwner = supportPos == null
                ? ClientRagdollManager.ownerOf(other) : null;
        if (supportPos == null && otherOwner == null) return;

        for (int i = 0, n = pair.contactCount(); i < n; i++) {
            pair.selectContact(i);
            if (pair.distance() > TERRAIN_CONTACT_DISTANCE) continue;
            pair.getNormalOnB(contactNormal);
            // The seam's normal points from B towards A, so it reads as "up" only when this
            // ragdoll's part is A; from the other side the same contact points down.
            float supportNormalY = aIsThis ? contactNormal.y : -contactNormal.y;
            if (supportNormalY > GROUND_NORMAL_MIN_Y) {
                if (supportPos != null) {
                    activeGroundSupportBlocks.add(supportPos);
                } else if (!otherOwner.isDestroyed()) {
                    // The owner map is a tick old when the active-cap pass consults it, which is
                    // fine for a body that is merely stale but not for one already torn down.
                    supportingRagdolls.add(otherOwner.getOriginalEntityId());
                    if (otherOwner.isSettled()) supportedBySettledBody = true;
                }
                return;
            }
        }
    }

    private boolean collisionGeometryReadyForContacts() {
        return currentCollisionGeometry != null
                && currentCollisionGeometry.isValid()
                && physicsWorld.getTickCount() > collisionGeometryAcquiredTick;
    }

    private boolean hasLowVerticalSpeed() {
        for (PhysicsBody body : ragdollParts) {
            body.getLinearVelocity(scratchVel);
            if (Math.abs(scratchVel.y) > PHANTOM_VERTICAL_SPEED) return false;
        }
        return true;
    }

    // Drop affected cache references and wake nearby frozen ragdolls.
    public void onBlockChangedNear(BlockPos changedPos) {
        // Invalidate our cache reference if its region contains the changed block
        if (currentCollisionGeometry != null) {
            int dx = Math.abs(lastCollisionCenter.getX() - changedPos.getX());
            int dy = Math.abs(lastCollisionCenter.getY() - changedPos.getY());
            int dz = Math.abs(lastCollisionCenter.getZ() - changedPos.getZ());
            int radius=currentCollisionRadius>0?currentCollisionRadius:baseCollisionRadius();
            if (dx <= radius && dy <= radius && dz <= radius) {
                releaseCurrentCollisionGeometry();
            }
        }

        // An observer never owns the response to terrain changes. Keep displaying the last
        // authoritative pose until the owner streams the awakened body.
        if (replicated) return;

        // Wake up if settled / distance-frozen and torso is near
        if (settled || bodiesFrozen) {
            double dx = changedPos.getX() + 0.5 - cachedTorsoPos.x;
            double dy = changedPos.getY() + 0.5 - cachedTorsoPos.y;
            double dz = changedPos.getZ() + 0.5 - cachedTorsoPos.z;
            int radius=baseCollisionRadius();
            if (dx*dx + dy*dy + dz*dz < (radius+1)*(radius+1)) {
                settled = false;
                pendingTerrainValidation = false;
                settledOnLiquid = false;
                settledTicks = 0;
                hasSettledTerrainSignature = false;
                markSettledPoseDirty();
                if (bodiesFrozen) unfreezeBodies();
            }
        }
    }

    // Deprecated: use onBlockChangedNear, which also invalidates the stale cache.
    @Deprecated
    public void wakeIfNear(BlockPos changedPos) {
        onBlockChangedNear(changedPos);
    }

    // Wake this ragdoll when an active torso comes within 2 blocks, since settled bodies are out of
    // the world and would be fallen through. True only if this call woke it.
    public boolean wakeIfNearRagdoll(Vector3f otherTorsoPos) {
        if (replicated) return false;
        if (!settled) return false; // distance-frozen bodies are too far to matter
        // Water-settled corpses skip cascade-wake: they are out of the world anyway, and a pond full
        // of them used to bounce each other awake every few ticks. Block changes still wake them.
        if (settledOnLiquid) return false;
        float dx = otherTorsoPos.x - cachedTorsoPos.x;
        float dy = otherTorsoPos.y - cachedTorsoPos.y;
        float dz = otherTorsoPos.z - cachedTorsoPos.z;
        if (dx * dx + dy * dy + dz * dz < 4.0f) { // 2-block radius
            settled = false;
            pendingTerrainValidation = false;
            settledOnLiquid = false;
            settledTicks = 0;
            markSettledPoseDirty();
            unfreezeBodies();
            return true;
        }
        return false;
    }

    // World collision: mirrors MobRagdollPhysics.updateLocalWorldCollision() exactly

    private void updateLocalWorldCollision() {
        Vector3f torsoPos = cachedTorsoPos;
        BlockPos center = new BlockPos(
                (int) Math.floor(torsoPos.x),
                (int) Math.floor(torsoPos.y),
                (int) Math.floor(torsoPos.z));

        if (currentCollisionGeometry != null && !currentCollisionGeometry.isValid()) {
            releaseCurrentCollisionGeometry();
        }

        int collisionRadius = dragPhysicsActive
                ? Math.max(DRAG_COLLISION_RADIUS,baseCollisionRadius()) : adaptiveCollisionRadius();
        if (center.equals(lastCollisionCenter) && currentCollisionGeometry != null
                && currentCollisionRadius == collisionRadius) {
            if (Math.floorMod(ticksExisted + id, 20) != 0) return;
            long currentSignature = computeTerrainSignature(center,collisionRadius);
            if (currentSignature == currentCollisionGeometry.terrainSignature()) return;
            physicsWorld.invalidateCollisionGeometry(currentCollisionGeometry);
            releaseCurrentCollisionGeometry();
        }

        // Acquire new geometry first, keeping the old and retrying next tick when the budget is spent,
        // rather than leaving the body with no floor. Per-AABB static bodies beat one CompoundShape.
        ClientPhysicsWorld.CollisionGeometryHandle newGeometry =
                physicsWorld.getOrCreateCollisionGeometry(
                        center, collisionRadius, isOutrunningCollisionGeometry(),
                        this::buildBlockCollisionGeometry);

        if (newGeometry == null) {
            // Rate-limited this tick: keep old geometry, lastCollisionCenter unchanged so
            // we retry next tick (center != lastCollisionCenter will be true again).
            return;
        }

        // Release old AFTER acquiring new, so the ragdoll always has valid floor coverage.
        if (currentCollisionGeometry != null) {
            physicsWorld.releaseCollisionGeometry(currentCollisionGeometry);
        }

        lastCollisionCenter = center;
        currentCollisionRadius = collisionRadius;
        currentCollisionGeometry = newGeometry;
        collisionGeometryAcquiredTick = physicsWorld.getTickCount();

        // No wakeUpAndClearContacts(): clearing manifolds destroys floor contacts and causes the
        // settle-sink-bounce cycle. Contacts for the new geometry rebuild within a substep or two.
    }

    // Terrain radius this ragdoll needs: scales with speed to avoid tunnelling,
    // but always covers every part.
    private int adaptiveCollisionRadius() {
        int base = baseCollisionRadius();
        if (ragdollParts.isEmpty()) return base;
        ragdollParts.get(0).getLinearVelocity(scratchVel);
        float speedSq = scratchVel.x * scratchVel.x
                + scratchVel.y * scratchVel.y
                + scratchVel.z * scratchVel.z;
        // Fast enough to tunnel: keep the full bubble, unchanged from before.
        if (speedSq > GEOMETRY_PRIORITY_SPEED_SQ) return base;

        // At 2 blocks/s a body covers 0.1 blocks per tick, so even the innermost shell is many
        // ticks of margin; at 10 blocks/s it is 0.5 per tick against a two-block shell.
        int tier = speedSq > GEOMETRY_REST_SPEED_SQ ? base - 1 : base - 2;
        return Math.max(partCoverageRadius(), Math.max(1, Math.min(base, tier)));
    }

    // Blocks from the torso's own block that the outermost part reaches, plus one for whatever it
    // is resting on.
    private int partCoverageRadius() {
        float maxOffset = 0f;
        for (RagdollTransform transform : cachedTransforms) {
            if (transform == null) continue;
            maxOffset = Math.max(maxOffset, Math.abs(transform.position.x - cachedTorsoPos.x));
            maxOffset = Math.max(maxOffset, Math.abs(transform.position.y - cachedTorsoPos.y));
            maxOffset = Math.max(maxOffset, Math.abs(transform.position.z - cachedTorsoPos.z));
        }
        return (int) Math.ceil(maxOffset) + 1;
    }

    // True when the torso moves fast enough that a tick of stale geometry risks tunnelling out of its
    // COLLISION_RADIUS bubble. At the threshold that is 0.5 blocks a tick, about 6 ticks of margin.
    private boolean isOutrunningCollisionGeometry() {
        if (ragdollParts.isEmpty()) return false;
        // Torso only: parts are jointed so it tracks the body as a whole, and the
        // geometry bubble is centred on the torso anyway.
        ragdollParts.get(0).getLinearVelocity(scratchVel);
        float lsq = scratchVel.x * scratchVel.x
                + scratchVel.y * scratchVel.y
                + scratchVel.z * scratchVel.z;
        return lsq > GEOMETRY_PRIORITY_SPEED_SQ;
    }

    private ClientPhysicsWorld.BuiltBlockCollisionGeometry buildBlockCollisionGeometry(BlockPos pos) {
        return buildBlockCollisionGeometry(physicsWorld, level, world, pos);
    }

    // Static so severed limbs fill the shared terrain cache with the same entries a ragdoll would.
    static ClientPhysicsWorld.BuiltBlockCollisionGeometry buildBlockCollisionGeometry(
            ClientPhysicsWorld physicsWorld, ClientLevel level, PhysicsWorld world, BlockPos pos) {
        List<PhysicsBody> bodies = new ArrayList<>();
        BlockState state = level.getBlockState(pos);
        int stateId = Block.getId(state);
        if (state.isAir()) {
            return new ClientPhysicsWorld.BuiltBlockCollisionGeometry(bodies, stateId);
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty() || isCompletelySurrounded(level, pos, shape)) {
            return new ClientPhysicsWorld.BuiltBlockCollisionGeometry(bodies, stateId);
        }

        float terrainFriction = (float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION);
        for (AABB box : shape.toAabbs()) {
            Vector3f halfExtents = new Vector3f(
                    (float)(box.getXsize() / 2),
                    (float)(box.getYsize() / 2),
                    (float)(box.getZsize() / 2)
            );
            // Interned: a full cube is a full cube wherever it stands, and the transform lives on
            // the body, so one shape instance serves every static block body of this size.
            PhysicsShape cs = physicsWorld.internedBoxShape(
                    halfExtents.x, halfExtents.y, halfExtents.z);
            PhysTransform transform = new PhysTransform();
            transform.setIdentity();
            transform.origin.set(
                    (float)(pos.getX() + box.minX + box.getXsize() / 2),
                    (float)(pos.getY() + box.minY + box.getYsize() / 2),
                    (float)(pos.getZ() + box.minZ + box.getZsize() / 2)
            );
            PhysicsBody body = world.createStaticBody(cs, transform);
            // A native backend refuses bodies once its pool is full. Terrain degrading to "no floor
            // here this tick" is survivable; the ragdoll retries as soon as room frees up.
            if (body == null) continue;
            body.setFriction(terrainFriction);
            body.setRestitution(0f);
            body.setUserPointer(pos.immutable());
            world.addBody(body);
            bodies.add(body);
        }
        return new ClientPhysicsWorld.BuiltBlockCollisionGeometry(bodies, stateId);
    }

    private int baseCollisionRadius(){return getBodyProfile()==RagdollBodyFactory.BodyProfile.GHAST?6:COLLISION_RADIUS;}

    private long computeTerrainSignature(BlockPos center,int radius) {
        long signature = terrainSignatureSeed();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    terrainScanPos.set(
                            center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    signature = mixTerrainSignature(
                            signature, level.getBlockState(terrainScanPos));
                }
            }
        }
        return signature;
    }

    private static long terrainSignatureSeed() {
        return 0xcbf29ce484222325L;
    }

    private static long mixTerrainSignature(long signature, BlockState state) {
        return (signature ^ Block.getId(state)) * 0x100000001b3L;
    }

    private void releaseCurrentCollisionGeometry() {
        if (currentCollisionGeometry != null) {
            physicsWorld.releaseCollisionGeometry(currentCollisionGeometry);
            currentCollisionGeometry = null;
        }
        collisionGeometryAcquiredTick = Integer.MIN_VALUE;
        lastCollisionCenter = BlockPos.ZERO;
    }

    private BlockPos currentTorsoBlock() {
        return new BlockPos(
                (int) Math.floor(cachedTorsoPos.x),
                (int) Math.floor(cachedTorsoPos.y),
                (int) Math.floor(cachedTorsoPos.z));
    }

    private boolean isCompletelySurrounded(BlockPos pos, VoxelShape shape) {
        return isCompletelySurrounded(level, pos, shape);
    }

    static boolean isCompletelySurrounded(ClientLevel level, BlockPos pos, VoxelShape shape) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            VoxelShape neighborShape = neighborState.getCollisionShape(level, neighbor);
            if (!Shapes.blockOccudes(shape, neighborShape, dir)) {
                return false;
            }
        }
        return true;
    }

    // Physics body creation: unchanged from previous version

    private void createRagdollBodies(SpawnData data) {
        float xRotDeg = MobModelHelper.isHumanoidModelType(modelType) ? 0f : data.xRot;
        if (data.isSwimming) xRotDeg = 90;

        float spawnYOffset = isPlayer ? 1.2f : (modelType == MobModelHelper.ModelType.QUADRUPED ||
                modelType == MobModelHelper.ModelType.WOLF || modelType == MobModelHelper.ModelType.FOX ||
                modelType == MobModelHelper.ModelType.PANDA ||
                modelType == MobModelHelper.ModelType.GOAT || modelType == MobModelHelper.ModelType.POLAR_BEAR ||
                modelType == MobModelHelper.ModelType.TURTLE ||
                modelType == MobModelHelper.ModelType.CAMEL || modelType == MobModelHelper.ModelType.LLAMA ||
                modelType == MobModelHelper.ModelType.RABBIT || modelType == MobModelHelper.ModelType.FROG ||
                modelType == MobModelHelper.ModelType.HOGLIN || modelType == MobModelHelper.ModelType.SNIFFER ||
                modelType == MobModelHelper.ModelType.RAVAGER || modelType == MobModelHelper.ModelType.PHANTOM ||
                modelType == MobModelHelper.ModelType.PARROT || modelType == MobModelHelper.ModelType.SLIME ||
                modelType == MobModelHelper.ModelType.MAGMA_CUBE ||
                modelType == MobModelHelper.ModelType.SILVERFISH || modelType == MobModelHelper.ModelType.ENDERMITE ||
                modelType == MobModelHelper.ModelType.ALLAY || modelType == MobModelHelper.ModelType.STRIDER ||
                modelType == MobModelHelper.ModelType.SNOW_GOLEM || modelType == MobModelHelper.ModelType.BLAZE ||
                modelType == MobModelHelper.ModelType.SPIDER || modelType == MobModelHelper.ModelType.SHULKER ||
                modelType == MobModelHelper.ModelType.GHAST || modelType == MobModelHelper.ModelType.VEX ||
                modelType == MobModelHelper.ModelType.WARDEN ||
                modelType == MobModelHelper.ModelType.GUARDIAN || modelType == MobModelHelper.ModelType.SQUID ||
                modelType == MobModelHelper.ModelType.DOLPHIN || modelType == MobModelHelper.ModelType.AXOLOTL ||
                modelType == MobModelHelper.ModelType.FISH || modelType == MobModelHelper.ModelType.WITHER ||
                modelType == MobModelHelper.ModelType.ENDER_DRAGON ||
                modelType == MobModelHelper.ModelType.CHICKEN ? 0f : 1.2f);
        // Baby humanoids are built at half size, so their torso centre sits ~half as high,
        // spawn them lower or they'd drop in from an adult's chest height.
        if (isBabyHumanoid()) spawnYOffset = 0.6f;

        // Quadruped/chicken pos adjusted again below; keep consistent with factory call
        if (modelType == MobModelHelper.ModelType.QUADRUPED)
            spawnYOffset = 0.7f * data.scale;
        if (modelType == MobModelHelper.ModelType.CHICKEN)
            spawnYOffset = 0.4f * data.scale;
        // Bat/bee are small winged mobs on their own layouts (not quadruped/chicken), so the
        // 1.2 default above is far too high; drop them close to the ground.
        if (modelType == MobModelHelper.ModelType.BAT) spawnYOffset = 0.3f;
        if (modelType == MobModelHelper.ModelType.BEE) spawnYOffset = isBabyBee() ? 0.18f : 0.35f;
        RagdollBodyFactory.BodyProfile bodyProfile = getBodyProfile();
        if (bodyProfile == RagdollBodyFactory.BodyProfile.COW) {
            spawnYOffset = isBabyCow() ? 0.54f : 1.07f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.PIG) {
            spawnYOffset = isBabyPig() ? 0.32f : 0.63f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SHEEP) {
            spawnYOffset = isBabySheep() ? 0.47f : 0.94f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.CAT) {
            spawnYOffset = isBabyCat() ? 0.20f : 0.36f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.WOLF) {
            spawnYOffset = isBabyWolf() ? 0.3125f : 0.625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.FOX) {
            spawnYOffset = isBabyFox() ? 0.234375f : 0.469f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.PANDA) {
            spawnYOffset = isBabyPanda() ? 0.270833f : 0.875f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.GOAT) {
            spawnYOffset = isBabyGoat() ? 0.34375f : 0.6875f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.POLAR_BEAR) {
            spawnYOffset = isBabyPolarBear() ? 0.50625f : 1.0125f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.TURTLE) {
            spawnYOffset = isBabyTurtle() ? 0.046875f : 0.28125f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.CAMEL) {
            spawnYOffset = isBabyCamel() ? 0.73078f : 1.625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.LLAMA) {
            spawnYOffset = isBabyLlama() ? 0.363636f : 1.0625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.RABBIT) {
            spawnYOffset = isBabyRabbit() ? 0.156f : 0.234f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.FROG) {
            spawnYOffset = 0.15625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.HOGLIN) {
            spawnYOffset = isBabyHoglin() ? 0.53125f : 1.0625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SNIFFER) {
            spawnYOffset = isBabySniffer() ? 0.578125f : 1.15625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.RAVAGER) {
            spawnYOffset = 1.625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.PHANTOM) {
            spawnYOffset = 1.34375f * getPhantomRenderScale();
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.PARROT) {
            spawnYOffset = 0.28125f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SLIME ||
                bodyProfile == RagdollBodyFactory.BodyProfile.MAGMA_CUBE) {
            spawnYOffset = 0.25f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SILVERFISH ||
                bodyProfile == RagdollBodyFactory.BodyProfile.ENDERMITE ||
                bodyProfile == RagdollBodyFactory.BodyProfile.ALLAY) {
            spawnYOffset = 0.125f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.STRIDER) {
            spawnYOffset = isBabyStrider() ? .65625f : 1.3125f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SNOW_GOLEM) {
            spawnYOffset = 1f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.BLAZE) {
            spawnYOffset = 1.2f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SPIDER) {
            spawnYOffset = .5625f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.CAVE_SPIDER) {
            spawnYOffset = .39375f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SHULKER) {
            spawnYOffset = .25f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.GHAST) {
            spawnYOffset = 2.25f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.VEX) {
            spawnYOffset = .35f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.WARDEN) {
            spawnYOffset = 1.46875f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.GUARDIAN) {
            spawnYOffset = .5f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.ELDER_GUARDIAN) {
            spawnYOffset = 1.175f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SQUID) {
            // The mantle centre sits a block above the tentacle tips, so the authored 0.3 would spawn
            // the whole tentacle ring inside the floor. Measured from the tips instead.
            spawnYOffset = 1.6f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.DOLPHIN) {
            spawnYOffset = .345f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.AXOLOTL) {
            spawnYOffset = .282f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.SALMON) {
            spawnYOffset = .251f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.COD
                || bodyProfile == RagdollBodyFactory.BodyProfile.TROPICAL_FISH
                || bodyProfile == RagdollBodyFactory.BodyProfile.PUFFERFISH
                || bodyProfile == RagdollBodyFactory.BodyProfile.TADPOLE) {
            spawnYOffset = .126f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.WITHER) {
            spawnYOffset = 1.702f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.ENDER_DRAGON) {
            spawnYOffset = 4.5f;
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.CHICKEN) {
            spawnYOffset = isBabyChicken() ? 0.27f : 0.54f;
        } else if (modelType == MobModelHelper.ModelType.EQUINE) {
            float rendererScale = bodyProfile == RagdollBodyFactory.BodyProfile.HORSE ? 1.1f
                    : bodyProfile == RagdollBodyFactory.BodyProfile.DONKEY ? 0.87f
                    : bodyProfile == RagdollBodyFactory.BodyProfile.MULE ? 0.92f : 1.0f;
            spawnYOffset = (isBaby ? 0.63125f : 1.0f) * rendererScale;
        }
        if (modelType == MobModelHelper.ModelType.IRON_GOLEM) spawnYOffset = 1.515625f;
        if (modelType == MobModelHelper.ModelType.ENDERMAN) spawnYOffset = 2.0f;

        // Keep X/Z on the entity's exact death origin. The Y offset is neither prediction nor jitter:
        // it converts the feet-level entity origin into this body's authored torso location.
        Vector3f pos = new Vector3f(
                (float) data.position.x,
                (float) data.position.y + spawnYOffset,
                (float) data.position.z
        );

        Quaternionf q = new Quaternionf().rotateXYZ(
                (float) Math.toRadians(xRotDeg),
                (float) Math.toRadians(180 - data.yRot),
                0f
        );
        Quat4f baseQuat = new Quat4f(q.x, q.y, q.z, q.w);

        Vector3f initialVel = new Vector3f(
                (float) data.velocity.x,
                (float) data.velocity.y,
                (float) data.velocity.z
        );
        initialVel.scale((float) RagdollifiedConfig.get(RagdollifiedConfig.INITIAL_VELOCITY_SCALE)
                * modelSizeVelocityScale());

        // Captured transforms are entity-origin relative, so they need the death position to place parts.
        MobPoseCapture.MobPose capturedPose = data.capturedPose == null ? null
                : data.capturedPose.withOrigin(new Vector3f(
                        (float) data.position.x, (float) data.position.y, (float) data.position.z));

        // A measured rig carries its own torso height, where every other model type reads one out
        // of the table above. Applied here, after the table, so the two cannot disagree.
        com.raiiiden.ragdollified.GenericRig genericRig = null;
        if (modelType == MobModelHelper.ModelType.GENERIC) {
            GenericRigExtractor.Rig measured = GenericRigExtractor.byMobType(mobType);
            if (measured == null) {
                // Measured at death and cached by entity type, so the only way to be here is a
                // resource reload between the death and the body being built.
                Ragdollified.LOGGER.warn("No measured rig for {} — skipping ragdoll", mobType);
                return;
            }
            genericRig = measured.rig();
            pos.y = (float) data.position.y + genericRig.spawnYOffset * scale;
        }

        RagdollBodyFactory.build(world, ragdollParts, ragdollJoints,
                modelType, pos, baseQuat, scale, initialVel, capturedPose, bodyProfile,
                isBaby(), isBaby() && babyScalesHead(), genericRig);
        for (PhysicsBody r : ragdollParts) {
            r.setSleepingAllowed(false);
            r.activate();
        }
    }

    // Forces: mirrors MobRagdollPhysics exactly

    // Per-part buoyancy and drag: each submerged part gains upward velocity with its depth below the
    // local surface, so the body hovers half-submerged and can reach the water-surface settle.
    private void applyFluidForces() {
        final float dt = 1f / 20f;
        final float gravity = (float) RagdollifiedConfig.get(RagdollifiedConfig.GRAVITY);

        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            if (cachedTransforms[i] == null) continue;
            PhysicsBody body = ragdollParts.get(i);
            Vector3f partPos = cachedTransforms[i].position;
            fluidSamplePos.set(
                    (int) Math.floor(partPos.x),
                    (int) Math.floor(partPos.y),
                    (int) Math.floor(partPos.z));
            FluidState fluid = level.getFluidState(fluidSamplePos);
            if (fluid.isEmpty()) continue;

            float depth = sampleFluidDepth(partPos, fluid);
            if (depth <= 0f) continue; // part center is above the local surface
            float submersion = Math.min(1f, depth);

            float buoyancyAccel = gravity * 2.0f * submersion;

            body.getLinearVelocity(scratchVel);
            scratchVel.y += buoyancyAccel * dt;
            scratchVel.x *= 0.7f;
            scratchVel.y *= 0.7f;
            scratchVel.z *= 0.7f;

            // Flow scaled by submersion so a part barely dipping in doesn't get yanked
            // downstream. y-flow ignored; vertical motion is fully owned by buoyancy.
            Vec3 flow = fluid.getFlow(level, fluidSamplePos);
            scratchVel.x += (float) flow.x * 0.4f * submersion;
            scratchVel.z += (float) flow.z * 0.4f * submersion;

            body.setLinearVelocity(scratchVel);

            body.getAngularVelocity(scratchAng);
            scratchAng.scale(0.7f);
            body.setAngularVelocity(scratchAng);
        }
    }

    // Any submerged part suppresses stale-floor recovery while buoyancy is active.
    private boolean isAnyPartInLiquid() {
        for (RagdollTransform transform : cachedTransforms) {
            if (transform == null) continue;
            fluidSamplePos.set(
                    (int) Math.floor(transform.position.x),
                    (int) Math.floor(transform.position.y),
                    (int) Math.floor(transform.position.z));
            if (!level.getFluidState(fluidSamplePos).isEmpty()) return true;
        }
        return false;
    }

    // Require torso support or at least two other parts near a fluid surface.
    private boolean isFloatingAtSurface() {
        int surfaceSamples = 0;
        for (int i = 0; i < cachedTransforms.length; i++) {
            RagdollTransform transform = cachedTransforms[i];
            if (transform == null) continue;
            fluidSamplePos.set(
                    (int) Math.floor(transform.position.x),
                    (int) Math.floor(transform.position.y),
                    (int) Math.floor(transform.position.z));
            FluidState fluid = level.getFluidState(fluidSamplePos);
            if (fluid.isEmpty()) continue;
            float depth = sampleFluidDepth(transform.position, fluid);
            if (depth < -FLUID_SURFACE_ABOVE || depth > FLUID_SURFACE_BELOW) continue;
            if (i == RagdollPart.TORSO.index) return true;
            surfaceSamples++;
        }
        return surfaceSamples >= 2;
    }

    private boolean isSubmergedBelowSurface() {
        int submergedParts = 0;
        for (int i = 0; i < cachedTransforms.length; i++) {
            RagdollTransform transform = cachedTransforms[i];
            if (transform == null) continue;
            fluidSamplePos.set(
                    (int) Math.floor(transform.position.x),
                    (int) Math.floor(transform.position.y),
                    (int) Math.floor(transform.position.z));
            FluidState fluid = level.getFluidState(fluidSamplePos);
            if (fluid.isEmpty()
                    || sampleFluidDepth(transform.position, fluid) <= FLUID_SURFACE_BELOW) {
                continue;
            }
            if (i == RagdollPart.TORSO.index) return true;
            submergedParts++;
        }
        return submergedParts >= 2;
    }

    private float sampleFluidDepth(Vector3f partPos, FluidState initialFluid) {
        int x = (int) Math.floor(partPos.x);
        int z = (int) Math.floor(partPos.z);
        int y = (int) Math.floor(partPos.y);
        fluidSurfacePos.set(x, y, z);
        float depth = y + initialFluid.getHeight(level, fluidSurfacePos) - partPos.y;

        while (depth <= FLUID_SURFACE_BELOW) {
            fluidSurfacePos.set(x, ++y, z);
            FluidState above = level.getFluidState(fluidSurfacePos);
            if (!isSameFluidFamily(initialFluid, above)) break;
            depth = y + above.getHeight(level, fluidSurfacePos) - partPos.y;
        }
        return depth;
    }

    private static boolean isSameFluidFamily(FluidState first, FluidState second) {
        if (second.isEmpty()) return false;
        if (first.is(FluidTags.WATER)) return second.is(FluidTags.WATER);
        if (first.is(FluidTags.LAVA)) return second.is(FluidTags.LAVA);
        return first.getType() == second.getType();
    }

    private void applyPlayerCollisions() {
        // A dragger is already steering the body through its paired limbs. Vanilla's local
        // player-shove adds a competing impulse and was the source of most drag flips.
        if (dragPhysicsActive) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 playerPos = mc.player.position();
        Vec3 playerVel = mc.player.getDeltaMovement();
        if (playerVel.lengthSqr() < 0.01) return;

        float playerSpeed = (float) playerVel.length() * modelSizeVelocityScale();
        for (PhysicsBody part : ragdollParts) {
            part.getWorldTransform(tempTransform);
            Vector3f partPos = tempTransform.origin;

            float dx = (float)(playerPos.x - partPos.x);
            float dy = (float)(playerPos.y - partPos.y);
            float dz = (float)(playerPos.z - partPos.z);
            float distSq = dx*dx + dy*dy + dz*dz;

            if (distSq < 1.5f * 1.5f) {
                float dist = (float) Math.sqrt(distSq);
                if (dist < 0.1f) dist = 0.1f;
                float pushStrength = playerSpeed * 15f * (1.5f - dist) / 1.5f;
                float invDist = pushStrength / dist;
                scratchNormal.set(-dx * invDist, -dy * invDist, -dz * invDist);
                part.applyCentralImpulse(scratchNormal);
                part.activate();
            }
        }
    }

    // Damping for everything that injects momentum: inherited death velocity, impulses, later hits.
    // scale is bbHeight / 1.8, so a player is 1.0 and nothing player-sized or larger is touched.
    private float modelSizeVelocityScale() {
        return RagdollifiedConfig.getModelSizeVelocityScale(scale);
    }

    // Public API

    public void applyImpulse(RagdollPart part, Vector3f impulse) {
        applyImpulse(part, impulse, null);
    }

    // Push a part, pivoting the body about impactPoint (null falls back to the part's centre).
    public void applyImpulse(RagdollPart part, Vector3f impulse, Vector3f impactPoint) {
        if (part == null || part.index >= ragdollParts.size()) return;
        // A severed part is not there to be hit. The blow is dropped rather than redirected: the
        // caller aimed at an arm that is lying on the floor two blocks away.
        if ((hiddenPartMask & part.bit()) != 0) return;
        // The server broadcasts impulses to every client for ordering, but only the elected
        // owner is allowed to feed one into the solver. Observers move when its streamed pose lands.
        if (replicated) return;
        if (settled || bodiesFrozen) {
            settled = false;
            pendingTerrainValidation = false;
            settledOnLiquid = false;
            settledTicks = 0;
            markSettledPoseDirty();
            unfreezeBodies();
        }
        Vector3f scaled = new Vector3f(impulse);
        scaled.scale(RagdollifiedConfig.getPartKnockbackMultiplier(part) * modelSizeVelocityScale());
        // Impact point, or the part's centre if none; never the stale death-time point.
        applyHit(part.index, scaled, impactPoint);
        // Any previously sent settle pose predates this push and must be reported again after
        // the body comes to rest. The server also rejects reports with an older revision.
        markSettledPoseDirty();
    }

    // Drive a whole limb group on the physics worker, every target landing before the next step so
    // paired limbs never fight across ticks. Velocity-driven: no teleport, no impulse.
    public void dragPartsTo(Map<RagdollPart, Vec3> targets) {
        drivePartsTo(targets, DRAG_STIFFNESS, DragTarget.DEFAULT_MAX_HORIZONTAL_SPEED,
                DragTarget.DEFAULT_MAX_VERTICAL_SPEED, DRAG_TORSO_MAX_SPEED);
    }

    private void drivePartsTo(Map<RagdollPart, Vec3> targets, float stiffness, float maxHorizontalSpeed,
                              float maxVerticalSpeed, float maxTorsoFollowSpeed) {
        if (targets == null || targets.isEmpty()) return;
        boolean hasValidTarget = false;
        for (Map.Entry<RagdollPart, Vec3> entry : targets.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getKey().index < ragdollParts.size()) {
                hasValidTarget = true;
                break;
            }
        }
        if (!hasValidTarget) return;

        wakeForDrag();
        enableDragPhysics();
        draggedLimbVelocity.set(0f, 0f, 0f);
        int targetCount = 0;
        for (Map.Entry<RagdollPart, Vec3> entry : targets.entrySet()) {
            RagdollPart part = entry.getKey();
            Vec3 target = entry.getValue();
            if (part == null || target == null || part.index >= ragdollParts.size()) continue;
            driveDraggedPart(part, target, stiffness, maxHorizontalSpeed, maxVerticalSpeed, draggedLimbVelocity);
            targetCount++;
        }
        if (targetCount > 0) {
            draggedLimbVelocity.scale(1f / targetCount);
            assistTorsoDuringDrag(draggedLimbVelocity, maxTorsoFollowSpeed);
        }
        stabilizeDragRotation();
        markSettledPoseDirty();
    }

    // Derive stable, smoothed raised left/right targets for a paired arm or leg drag.
    public void dragEndTo(DragEnd end, DragTarget target) {
        if (end == null || target == null) return;
        updateSmoothedDragAnchor(target);
        updateDragFacing(target);
        float sideX = -dragFacingZ;
        float sideZ = dragFacingX;
        float separation = (end == DragEnd.ARMS
                ? DragTarget.DEFAULT_ARM_SEPARATION : DragTarget.DEFAULT_LEG_SEPARATION) * 0.5f;
        float lift = target.liftOffset() >= 0f ? target.liftOffset()
                : (end == DragEnd.ARMS ? DragTarget.DEFAULT_ARM_LIFT : DragTarget.DEFAULT_LEG_LIFT);
        Vec3 anchor = new Vec3(smoothedDragAnchor.x, smoothedDragAnchor.y, smoothedDragAnchor.z);
        Vec3 sideA = anchor.add(sideX * separation, lift, sideZ * separation);
        Vec3 sideB = anchor.add(-sideX * separation, lift, -sideZ * separation);
        RagdollPart leftPart = end == DragEnd.ARMS ? RagdollPart.LEFT_ARM : RagdollPart.LEFT_LEG;
        RagdollPart rightPart = end == DragEnd.ARMS ? RagdollPart.RIGHT_ARM : RagdollPart.RIGHT_LEG;

        // Hand each target to whichever limb is already nearest, since the targets are laid out along
        // the dragger's side axis: a prone body used to cross its limbs over and roll onto its face.
        boolean crossed = pickCrossedAssignment(leftPart, rightPart, sideA, sideB);
        Map<RagdollPart, Vec3> targets = new EnumMap<>(RagdollPart.class);
        targets.put(leftPart, crossed ? sideB : sideA);
        targets.put(rightPart, crossed ? sideA : sideB);
        // Follow responsiveness drives limbs as well as the anchor, leaving a lag of (speed / gain).
        // Capped because on a fixed 20 Hz step a gain above 1/dt overshoots and rings every tick.
        drivePartsTo(targets, Math.min(DRAG_MAX_STIFFNESS, target.followResponsiveness()),
                target.maxHorizontalSpeed(), target.maxVerticalSpeed(),
                Math.min(DRAG_TORSO_MAX_SPEED, target.maxHorizontalSpeed()));
    }

    // Decide whether to swap the paired targets, keeping the previous answer unless the other clearly
    // wins. Without that hysteresis a body square to the pull flips every tick and looks like shivering.
    private boolean pickCrossedAssignment(RagdollPart leftPart, RagdollPart rightPart,
                                          Vec3 sideA, Vec3 sideB) {
        if (leftPart.index >= ragdollParts.size() || rightPart.index >= ragdollParts.size()) {
            return dragEndsCrossed;
        }
        double straight = distanceSqrToPart(leftPart, sideA) + distanceSqrToPart(rightPart, sideB);
        double crossed = distanceSqrToPart(leftPart, sideB) + distanceSqrToPart(rightPart, sideA);
        if (crossed + DRAG_SWAP_HYSTERESIS_SQ < straight) dragEndsCrossed = true;
        else if (straight + DRAG_SWAP_HYSTERESIS_SQ < crossed) dragEndsCrossed = false;
        return dragEndsCrossed;
    }

    private double distanceSqrToPart(RagdollPart part, Vec3 point) {
        ragdollParts.get(part.index).getWorldTransform(tempTransform);
        double dx = point.x - tempTransform.origin.x;
        double dy = point.y - tempTransform.origin.y;
        double dz = point.z - tempTransform.origin.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // Restore normal friction and CCD values after a drag session ends.
    public void endDrag() {
        if (!dragPhysicsActive) return;
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            PhysicsBody body = ragdollParts.get(i);
            body.setFriction(dragOriginalFriction[i]);
            body.setCcdMotionThreshold(dragOriginalCcdThreshold[i]);
            body.setCcdSweptSphereRadius(dragOriginalCcdRadius[i]);
        }
        dragPhysicsActive = false;
        dragAnchorInitialized = false;
        dragFacingInitialized = false;
        dragEndsCrossed = false;
    }

    private void wakeForDrag() {
        if (settled || bodiesFrozen) {
            settled = false;
            pendingTerrainValidation = false;
            settledOnLiquid = false;
            settledTicks = 0;
            unfreezeBodies();
        }
    }

    private void enableDragPhysics() {
        if (dragPhysicsActive) return;
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            PhysicsBody body = ragdollParts.get(i);
            dragOriginalFriction[i] = body.getFriction();
            dragOriginalCcdThreshold[i] = body.getCcdMotionThreshold();
            dragOriginalCcdRadius[i] = body.getCcdSweptSphereRadius();
            body.setFriction(Math.min(DRAG_FRICTION, dragOriginalFriction[i]));
            body.setCcdMotionThreshold(Math.min(DRAG_CCD_MOTION_THRESHOLD, dragOriginalCcdThreshold[i]));
            body.setCcdSweptSphereRadius(Math.max(DRAG_CCD_MIN_RADIUS, dragOriginalCcdRadius[i]));
            body.setSleepingAllowed(false);
            body.activate();
        }
        dragPhysicsActive = true;
    }

    private void driveDraggedPart(RagdollPart part, Vec3 target, float stiffness, float maxHorizontalSpeed,
                                  float maxVerticalSpeed, Vector3f velocitySum) {
        PhysicsBody body = ragdollParts.get(part.index);
        body.getWorldTransform(tempTransform);
        body.getLinearVelocity(scratchDragVel);
        scratchVel.set((float) (target.x - tempTransform.origin.x) * stiffness,
                (float) (target.y - tempTransform.origin.y) * stiffness,
                (float) (target.z - tempTransform.origin.z) * stiffness);
        float horizontal = (float) Math.sqrt(scratchVel.x * scratchVel.x + scratchVel.z * scratchVel.z);
        if (horizontal > maxHorizontalSpeed) {
            float scale = maxHorizontalSpeed / horizontal;
            scratchVel.x *= scale;
            scratchVel.z *= scale;
        }
        // Vertical is asymmetric on purpose, and it is what gives a towed body weight: clamping both
        // ways replaced gravity and put the body on a rail. Only the lift is capped; below the grip it falls.
        if (scratchVel.y > maxVerticalSpeed) scratchVel.y = maxVerticalSpeed;
        if (scratchVel.y < 0F) scratchVel.y = Math.min(0F, scratchDragVel.y);
        body.setLinearVelocity(scratchVel);
        body.activate();
        velocitySum.add(scratchVel);
    }

    // The constraints feed some towing velocity into angular momentum; damping it before the step
    // stops a long pull building into a flip while leaving enough rotation to conform to terrain.
    private void stabilizeDragRotation() {
        for (int i = 0; i < ragdollParts.size(); i++) {
            PhysicsBody body = ragdollParts.get(i);
            body.getAngularVelocity(scratchAng);
            scratchAng.scale(DRAG_ANGULAR_DAMPING);
            float maximum = i == RagdollPart.TORSO.index
                    ? DRAG_TORSO_MAX_ANGULAR_SPEED : DRAG_LIMB_MAX_ANGULAR_SPEED;
            float speed = scratchAng.length();
            if (speed > maximum) scratchAng.scale(maximum / speed);
            body.setAngularVelocity(scratchAng);
        }
    }

    private void updateSmoothedDragAnchor(DragTarget target) {
        Vec3 desired = target.position();
        if (!dragAnchorInitialized) {
            // Begin from the body rather than snapping the first dragged frame straight to
            // the reviver's anchor. Subsequent target changes use the same capped smoothing.
            smoothedDragAnchor.set(cachedTorsoPos);
            dragAnchorInitialized = true;
        }
        // Exponential smoothing is frame-rate independent at the fixed 20 Hz physics step.
        float alpha = 1f - (float) Math.exp(-target.followResponsiveness() / 20f);
        float stepX = ((float) desired.x - smoothedDragAnchor.x) * alpha;
        float stepY = ((float) desired.y - smoothedDragAnchor.y) * alpha;
        float stepZ = ((float) desired.z - smoothedDragAnchor.z) * alpha;
        float horizontal = (float) Math.sqrt(stepX * stepX + stepZ * stepZ);
        float maxHorizontalStep = target.maxHorizontalSpeed() / 20f;
        if (horizontal > maxHorizontalStep) {
            float scale = maxHorizontalStep / horizontal;
            stepX *= scale;
            stepZ *= scale;
        }
        // Only the rise is capped, matching the limb drive. Capping descent made a towed body go
        // weightless downhill: the anchor hung in the air above the dragger and pulled the limbs up.
        float maxRiseStep = target.maxVerticalSpeed() / 20f;
        if (stepY > maxRiseStep) stepY = maxRiseStep;
        smoothedDragAnchor.set(smoothedDragAnchor.x + stepX,
                smoothedDragAnchor.y + stepY, smoothedDragAnchor.z + stepZ);
    }

    private void updateDragFacing(DragTarget target) {
        Vec3 facing = target.facing();
        float wantedX = facing != null ? (float) facing.x : 0f;
        float wantedZ = facing != null ? (float) facing.z : 0f;
        float length = (float) Math.sqrt(wantedX * wantedX + wantedZ * wantedZ);
        if (length < 0.001f) {
            if (dragFacingInitialized) return; // Compatibility targets retain a stable initial direction.
            wantedX = smoothedDragAnchor.x - cachedTorsoPos.x;
            wantedZ = smoothedDragAnchor.z - cachedTorsoPos.z;
            length = (float) Math.sqrt(wantedX * wantedX + wantedZ * wantedZ);
            if (length < 0.001f) { wantedX = 1f; wantedZ = 0f; length = 1f; }
        }
        wantedX /= length;
        wantedZ /= length;
        if (!dragFacingInitialized) {
            dragFacingX = wantedX;
            dragFacingZ = wantedZ;
            dragFacingInitialized = true;
            return;
        }
        float currentAngle = (float) Math.atan2(dragFacingZ, dragFacingX);
        float wantedAngle = (float) Math.atan2(wantedZ, wantedX);
        float delta = wantedAngle - currentAngle;
        while (delta > Math.PI) delta -= (float) (Math.PI * 2.0);
        while (delta < -Math.PI) delta += (float) (Math.PI * 2.0);
        float maxTurn = (float) Math.toRadians(target.maxTurnRateDegrees()) / 20f;
        delta = Math.max(-maxTurn, Math.min(maxTurn, delta));
        float result = currentAngle + delta;
        dragFacingX = (float) Math.cos(result);
        dragFacingZ = (float) Math.sin(result);
    }

    private void assistTorsoDuringDrag(Vector3f grabbedVelocity, float maxFollowSpeed) {
        if (ragdollParts.isEmpty()) return;
        PhysicsBody torso = ragdollParts.get(RagdollPart.TORSO.index);
        torso.getLinearVelocity(scratchVel);
        scratchVel.x += (grabbedVelocity.x - scratchVel.x) * DRAG_TORSO_ASSIST;
        scratchVel.z += (grabbedVelocity.z - scratchVel.z) * DRAG_TORSO_ASSIST;
        float speed = (float) Math.sqrt(scratchVel.x * scratchVel.x + scratchVel.z * scratchVel.z);
        float speedLimit = Math.max(0.1f, maxFollowSpeed);
        if (speed > speedLimit) {
            float scale = speedLimit / speed;
            scratchVel.x *= scale;
            scratchVel.z *= scale;
        }
        torso.setLinearVelocity(scratchVel);
        // The joint solver may otherwise convert a fast limb relocation into a large torso
        // spin. Keep the body visually stable without applying any corrective impulse.
        torso.getAngularVelocity(scratchAng);
        torso.activate();
    }

    // Owner-streamed replication

    public boolean isReplicated() { return replicated; }

    // Switch between local simulation and stream playback, physics thread only. Playback pulls the
    // bodies out of the world, so a replicated ragdoll costs no solver time and cannot shove a live one.
    public void setReplicated(boolean replicated) {
        if (this.replicated == replicated || destroyed) return;
        this.replicated = replicated;
        if (replicated) {
            hasReceivedStreamPose = false;
            streamPlaybackInitialized = false;
            resetStationaryHardSync();
            if (!bodiesFrozen) freezeBodies();
        } else {
            streamPoses.clear();
            lastStreamSequence = Integer.MIN_VALUE;
            streamPlaybackInitialized = false;
            // Resume from wherever playback left the body rather than from the original
            // spawn pose, so taking over a stream mid-flight is continuous.
            if (bodiesFrozen && !settled) unfreezeBodies();
        }
    }

    // Buffer one frame from the owner, physics thread only, drained by ClientRagdollManager.
    // Out-of-order frames are dropped: they are stale by definition and would rewind playback.
    public void applyStreamedPose(RagdollTransform[] transforms, int sequence, int sampleTick,
                                  boolean hardSync) {
        if (destroyed || transforms == null || transforms.length == 0) return;
        if (transforms[0] == null) return; // no torso anchor: nothing to place the rest against
        if (sequence <= lastStreamSequence) return;
        lastStreamSequence = sequence;
        if (hardSync) {
            streamPoses.clear();
            StreamedPose pose = new StreamedPose(transforms, sampleTick);
            streamPoses.addLast(pose);
            streamPlaybackTick = sampleTick;
            streamPlaybackInitialized = true;
            writeStreamedPose(pose, null, 0f);
            updateCachedTransforms();
            for (int i = 0; i < cachedTransforms.length; i++) {
                if (cachedTransforms[i] == null) continue;
                prevPositions[i].set(cachedTransforms[i].position);
                prevRotations[i].set(cachedTransforms[i].rotation);
            }
            prevTorsoPos.set(cachedTorsoPos);
            publishSnapshot();
            hasReceivedStreamPose = true;
            return;
        }
        if (streamPoses.size() >= STREAM_MAX_BUFFERED) streamPoses.pollFirst();
        streamPoses.addLast(new StreamedPose(transforms, sampleTick));
    }

    // Downward speeds for entering and leaving the falling state; the gap stops springs flickering.
    private static final float AIRBORNE_ENTER_SPEED = 2.0f;
    private static final float AIRBORNE_EXIT_SPEED = 1.0f;
    private boolean relaxAirborne = false;

    // Release joint springs while falling so air drag can move limbs, and restore them on landing.
    // Uses speed rather than contacts: cheaper, and measures falling directly.
    private void updateAirborneRelaxation() {
        if (ragdollJoints.isEmpty() || ragdollParts.isEmpty()) return;
        ragdollParts.get(0).getLinearVelocity(scratchVel);
        float descent = -scratchVel.y;
        boolean airborne = relaxAirborne
                ? descent > AIRBORNE_EXIT_SPEED
                : descent > AIRBORNE_ENTER_SPEED;
        if (airborne == relaxAirborne) return;
        relaxAirborne = airborne;
        float scale = airborne
                ? (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_AIRBORNE_SCALE)
                : 1f;
        RagdollBodyFactory.applyRelaxation(ragdollJoints, 0, scale);
    }

    // Flail state. Owned by the physics thread: begin() is only ever reached through the
    // manager's drain queue, for the same reason every other joint write is.
    private int flailTicksRemaining = 0;
    private int flailInterval = 4;
    private int flailUntilRetarget = 0;
    private float flailSpread = 0.35f;
    private float flailStrength = 1f;
    private java.util.Random flailRng;

    // Thrash this ragdoll's joints for a while, then hand them back to updateAirborneRelaxation.
    // intervalTicks between targets, spreadRadians cone half-angle, strengthScale spring multiplier.
    public void beginFlail(int durationTicks, int intervalTicks, float spreadRadians, float strengthScale) {
        if (durationTicks <= 0 || ragdollJoints.isEmpty()) return;
        flailTicksRemaining = durationTicks;
        flailInterval = Math.max(1, intervalTicks);
        flailUntilRetarget = 0;
        flailSpread = spreadRadians;
        flailStrength = strengthScale;
        if (flailRng == null) flailRng = new java.util.Random();
    }

    public boolean isFlailing() {
        return flailTicksRemaining > 0;
    }

    public void stopFlail() {
        if (flailTicksRemaining <= 0) return;
        flailTicksRemaining = 0;
        releaseFlail();
    }

    private void updateFlail() {
        if (flailTicksRemaining <= 0) return;
        if (ragdollJoints.isEmpty()) { flailTicksRemaining = 0; return; }

        flailTicksRemaining--;
        if (flailTicksRemaining <= 0) {
            releaseFlail();
            return;
        }
        if (--flailUntilRetarget > 0) return;
        flailUntilRetarget = flailInterval;
        RagdollBodyFactory.applyFlail(ragdollJoints, 0, flailStrength, flailSpread, flailRng);
    }

    // Restore the rest-pose springs unconditionally, recomputing the airborne flag from live descent.
    private void releaseFlail() {
        if (ragdollJoints.isEmpty() || ragdollParts.isEmpty()) return;
        ragdollParts.get(0).getLinearVelocity(scratchVel);
        float descent = -scratchVel.y;
        relaxAirborne = descent > (relaxAirborne ? AIRBORNE_EXIT_SPEED : AIRBORNE_ENTER_SPEED);
        float scale = relaxAirborne
                ? (float) RagdollifiedConfig.get(RagdollifiedConfig.JOINT_RELAX_AIRBORNE_SCALE)
                : 1f;
        RagdollBodyFactory.applyRelaxation(ragdollJoints, 0, scale);
    }

    private void updateStationaryHardSync() {
        boolean stationary = true;
        for (PhysicsBody body : ragdollParts) {
            body.getLinearVelocity(scratchVel);
            body.getAngularVelocity(scratchAng);
            if (scratchVel.lengthSquared() > HARD_SYNC_LINEAR_SPEED_SQ
                    || scratchAng.lengthSquared() > HARD_SYNC_ANGULAR_SPEED_SQ) {
                stationary = false;
                break;
            }
        }
        if (!stationary) {
            resetStationaryHardSync();
            return;
        }
        if (!stationaryHardSyncLatched
                && ++stationaryHardSyncTicks >= STATIONARY_HARD_SYNC_TICKS) {
            stationaryHardSyncLatched = true;
            stationaryHardSyncRequested.set(true);
        }
    }

    private void resetStationaryHardSync() {
        stationaryHardSyncTicks = 0;
        stationaryHardSyncLatched = false;
        stationaryHardSyncRequested.set(false);
    }

    public boolean consumeStationaryHardSyncRequest() {
        return stationaryHardSyncRequested.compareAndSet(true, false);
    }

    // Seed a newly elected owner that entered after the death: it must resume simulating, so write the
    // server-retained pose and keep its spawn velocities as the best continuation available.
    public void applyOwnerHandoffPose(RagdollTransform[] transforms) {
        if (destroyed || transforms == null || transforms.length == 0 || transforms[0] == null) return;
        if (replicated) setReplicated(false);
        settled = false;
        pendingTerrainValidation = false;
        settledOnLiquid = false;
        settledTicks = 0;

        for (int i = 0; i < ragdollParts.size() && i < transforms.length; i++) {
            RagdollTransform pose = transforms[i];
            if (pose == null || pose.partId != i) continue;
            tempTransform.setIdentity();
            tempTransform.origin.set(pose.position);
            tempTransform.setRotation(pose.rotation);
            PhysicsBody body = ragdollParts.get(i);
            body.setWorldTransform(tempTransform);
            body.activate();
        }
        updateCachedTransforms();
        updateLocalWorldCollision();
    }

    private void tickReplicated() {
        ticksExisted++;
        if (!persistent && ticksExisted >= lifetime) {
            destroy();
            return;
        }
        // Never fall back to an independent observer simulation when the stream pauses: holding the
        // last authoritative frame can freeze briefly, but cannot invent a second resting place.
        if (streamPoses.isEmpty()) return;

        StreamedPose newest = streamPoses.peekLast();
        double targetTick = newest.sampleTick - STREAM_BUFFER_TICKS;
        if (!streamPlaybackInitialized) {
            streamPlaybackTick = targetTick;
            streamPlaybackInitialized = true;
        } else {
            streamPlaybackTick += 1.0;
            double error = targetTick - streamPlaybackTick;
            streamPlaybackTick += Math.max(-0.05, Math.min(0.25, error * 0.1));
        }

        StreamedPose from = null;
        StreamedPose to = null;
        for (StreamedPose pose : streamPoses) {
            if (pose.sampleTick <= streamPlaybackTick) {
                from = pose;
            } else {
                to = pose;
                break;
            }
        }
        // Before the buffer has filled, hold the oldest frame; if playback has caught up with
        // the newest, hold that. Holding beats extrapolating a body into terrain.
        if (from == null) from = streamPoses.peekFirst();
        float alpha = 0f;
        if (to != null) {
            int span = to.sampleTick - from.sampleTick;
            if (span > 0) {
                alpha = Math.min(1f, Math.max(0f,
                        (float) ((streamPlaybackTick - from.sampleTick) / span)));
            }
        }
        writeStreamedPose(from, to, alpha);

        // Anything strictly older than the frame currently being played from is unreachable.
        while (streamPoses.size() > 1 && streamPoses.peekFirst() != from) streamPoses.pollFirst();

        updateCachedTransforms();
        hasReceivedStreamPose = true;
    }

    private void writeStreamedPose(StreamedPose from, StreamedPose to, float alpha) {
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            if (from.positions[i] == null) continue;
            streamScratchPos.set(from.positions[i]);
            streamScratchRot.set(from.rotations[i]);
            if (to != null && to.positions[i] != null && alpha > 0f) {
                streamScratchPos.interpolate(to.positions[i], alpha);
                slerpInto(from.rotations[i], to.rotations[i], alpha, streamScratchRot);
            }
            tempTransform.setIdentity();
            tempTransform.origin.set(streamScratchPos);
            tempTransform.setRotation(streamScratchRot);

            PhysicsBody body = ragdollParts.get(i);
            // setWorldTransform is authoritative on every backend, including for a body that is
            // out of the world and therefore never stepped.
            body.setWorldTransform(tempTransform);
        }
    }

    // Apply a server-retained state on the physics thread. Settled transforms are absolute world
    // coordinates, so a player arriving later sees the already-resting pose.
    public void applyAuthoritativeState(RagdollTransform[] transforms, int ageTicks, boolean settledPose) {
        ticksExisted = Math.max(ticksExisted, Math.max(0, ageTicks));
        if (!settledPose || transforms == null) return;

        if (bodiesFrozen && !replicated) unfreezeBodies();
        for (int i = 0; i < ragdollParts.size() && i < transforms.length; i++) {
            RagdollTransform authoritative = transforms[i];
            if (authoritative == null || authoritative.partId != i) continue;

            PhysTransform worldTransform = new PhysTransform();
            worldTransform.setIdentity();
            worldTransform.origin.set(authoritative.position);
            worldTransform.setRotation(authoritative.rotation);

            PhysicsBody body = ragdollParts.get(i);
            body.setWorldTransform(worldTransform);
            body.setLinearVelocity(new Vector3f());
            body.setAngularVelocity(new Vector3f());
        }

        updateCachedTransforms();

        if (replicated) {
            hasReceivedStreamPose = true;
            settled = true;
            settledOnLiquid = false;
            settledTicks = 2;
            pendingTerrainValidation = false;
            settledGroundSupportBlocks.clear();
            settledSupportRagdolls.clear();
            hasSettledTerrainSignature = false;
            if (!bodiesFrozen) freezeBodies();
            settledPoseReported = true;
            return;
        }

        updateLocalWorldCollision();

        if (isPlayer) {
            settled = true;
            settledOnLiquid = false;
            settledTicks = 2;
            pendingTerrainValidation = false;
            settledGroundSupportBlocks.clear();
            settledSupportRagdolls.clear();
            hasSettledTerrainSignature = false;
            freezeBodies();
            settledPoseReported = true;
            return;
        }

        BlockPos torsoBlock = currentTorsoBlock();
        if (!isSupportAreaLoaded()) {
            physicsWorld.cacheStats.poseDeferred++;
            settled = true;
            settledOnLiquid = false;
            settledTicks = 2;
            pendingTerrainValidation = true;
            settledGroundSupportBlocks.clear();
            settledSupportRagdolls.clear();
            freezeBodies();
            hasSettledTerrainSignature = false;
            settledPoseReported = true;
            return;
        }

        boolean onGround = collectWorldTerrainSupport();
        boolean atSurface = isFloatingAtSurface();
        boolean submergedBelowSurface = !atSurface && isSubmergedBelowSurface();
        if ((!onGround && !atSurface) || submergedBelowSurface) {
            physicsWorld.cacheStats.poseRejected++;
            settled = false;
            pendingTerrainValidation = false;
            settledOnLiquid = false;
            settledTicks = 0;
            markSettledPoseDirty();
            for (PhysicsBody body : ragdollParts) {
                body.setSleepingAllowed(false);
                body.activate();
            }
            return;
        }

        settledTicks = 2;
        settleAtCurrentSupport(atSurface, "authoritative-pose");
        settledPoseReported = true;
    }

    private volatile int lastImpulseRevision = 0;
    public int getLastImpulseRevision() { return lastImpulseRevision; }
    public void acknowledgeImpulseRevision(int revision) {
        if (revision > lastImpulseRevision) lastImpulseRevision = revision;
    }

    // A dead-centre chest shot takes the ordinary path, where a near-zero lever gives near-zero torque.
    private void applyCenteredDeathImpulse(Vec3 impulse) {
        float centerScale = Math.max(0.75f,
                (float) RagdollifiedConfig.get(RagdollifiedConfig.HIT_CENTER_DISTRIBUTION_SCALE))
                * modelSizeVelocityScale();
        applyHit(RagdollPart.TORSO.index, scaledImpulse(impulse, centerScale
                        * RagdollifiedConfig.getDeathPartKnockbackMultiplier(RagdollPart.TORSO)),
                deathHitPoint, true);
    }

    // A hit: the full impulse goes to the whole body at the impact point, so it turns about the wound,
    // plus an extra non-conservative whip on the struck limb (limbWhipFor).
    private void applyHit(int partIndex, Vector3f impulse, Vector3f impactPoint) {
        applyHit(partIndex, impulse, impactPoint, false);
    }

    // fromStanding: the death-time hit, where the feet can lever against the floor; false for later shoves.
    private void applyHit(int partIndex, Vector3f impulse, Vector3f impactPoint,
                          boolean fromStanding) {
        if (partIndex < 0 || partIndex >= ragdollParts.size()) return;

        // The push and the turn: everything, every time, wherever it landed.
        transferToWholeBody(impulse, partIndex, impactPoint, fromStanding);

        float whip = limbWhipFor(partIndex);
        if (whip <= 0f) return;
        PhysicsBody body = ragdollParts.get(partIndex);
        applyOffCentreImpulse(body, new Vector3f(
                impulse.x * whip, impulse.y * whip, impulse.z * whip), impactPoint);
        body.activate();
    }

    // Extra kick for the struck part, scaled inversely with its mass so light limbs flail.
    // The ratio is clamped against tiny proxy bodies, and the result is capped.
    private float limbWhipFor(int partIndex) {
        float base = (float) Math.max(0.0, RagdollifiedConfig.get(RagdollifiedConfig.HIT_LIMB_WHIP));
        float bias = (float) RagdollifiedConfig.get(RagdollifiedConfig.HIT_LIMB_WHIP_MASS_BIAS);
        if (base <= 0f || bias <= 0f) return base;
        float total = 0f;
        int counted = 0;
        for (PhysicsBody part : ragdollParts) {
            float invMass = part.getInvMass();
            if (invMass <= 0f) continue;
            total += 1f / invMass;
            counted++;
        }
        float invMass = ragdollParts.get(partIndex).getInvMass();
        if (counted == 0 || invMass <= 0f || total <= 0f) return base;
        // Average part mass over the struck part's own mass: above 1 for anything lighter than
        // average, below 1 for anything heavier.
        float ratio = Math.max(0.1f, Math.min(10f, (total / counted) * invMass));
        return Math.min(3f, base * (float) Math.pow(ratio, bias));
    }

    // Apply an impulse to all parts as one rigid body: J/M linear, w = I^-1 (d x J) spin.
    // Standing bodies solve the spin about the ground support point, blended by groundLever.
    private void transferToWholeBody(Vector3f impulse, int partIndex, Vector3f impactPoint,
                                     boolean fromStanding) {
        int count = ragdollParts.size();
        float[][] centres = new float[count][];
        float[] masses = new float[count];
        float totalMass = 0f;
        float cx = 0f, cy = 0f, cz = 0f;
        Vector3f centre = new Vector3f();
        Vector3f halfExtents = new Vector3f();
        for (int i = 0; i < count; i++) {
            PhysicsBody part = ragdollParts.get(i);
            float invMass = part.getInvMass();
            if (invMass <= 0f) continue;
            float mass = 1f / invMass;
            part.getCenterOfMassPosition(centre);
            centres[i] = new float[]{centre.x, centre.y, centre.z};
            masses[i] = mass;
            totalMass += mass;
            cx += centre.x * mass;
            cy += centre.y * mass;
            cz += centre.z * mass;
        }
        if (totalMass <= 1.0e-4f) return;
        cx /= totalMass;
        cy /= totalMass;
        cz /= totalMass;

        // Symmetric inertia tensor about the centre of mass, packed as xx, yy, zz, xy, xz, yz.
        float[] inertia = new float[6];
        for (int i = 0; i < count; i++) {
            if (centres[i] == null) continue;
            float mass = masses[i];
            float dx = centres[i][0] - cx;
            float dy = centres[i][1] - cy;
            float dz = centres[i][2] - cz;
            inertia[0] += mass * (dy * dy + dz * dz);
            inertia[1] += mass * (dx * dx + dz * dz);
            inertia[2] += mass * (dx * dx + dy * dy);
            inertia[3] -= mass * dx * dy;
            inertia[4] -= mass * dx * dz;
            inertia[5] -= mass * dy * dz;
            PhysicsShape shape = ragdollParts.get(i).getShape();
            if (shape != null && shape.isBox()) {
                shape.getHalfExtents(halfExtents);
                float own = mass * (halfExtents.x * halfExtents.x
                        + halfExtents.y * halfExtents.y
                        + halfExtents.z * halfExtents.z) / 3f;
                inertia[0] += own;
                inertia[1] += own;
                inertia[2] += own;
            }
        }

        // Where the shot landed, in world space. Without a recorded impact point the struck part's
        // own centre is still off the middle, which is enough to turn the body.
        float px, py, pz;
        if (impactPoint != null) {
            px = impactPoint.x;
            py = impactPoint.y;
            pz = impactPoint.z;
        } else if (centres[partIndex] != null) {
            px = centres[partIndex][0];
            py = centres[partIndex][1];
            pz = centres[partIndex][2];
        } else {
            px = cx;
            py = cy;
            pz = cz;
        }

        float spin = (float) RagdollifiedConfig.get(RagdollifiedConfig.HIT_SPIN_SCALE);
        float[] omega = solveAboutPivot(inertia, totalMass,
                cx, cy, cz, cx, cy, cz, px, py, pz, impulse, spin);
        // Free-body translation: every part gains J / M, and the rotation about the centre of mass
        // adds no net momentum to it.
        float vx = impulse.x / totalMass;
        float vy = impulse.y / totalMass;
        float vz = impulse.z / totalMass;
        float pivotY = cy;
        float lever = 0f;

        // A standing body pivots about the floor, trading translation for rotation (the com moves at
        // w x (com - pivot)); groundLever sets how much of that friction supplies.
        if (fromStanding) {
            lever = (float) RagdollifiedConfig.get(RagdollifiedConfig.HIT_GROUND_LEVER);
            if (lever > 0f) {
                float supportY = groundSupportHeight(cx, cz);
                if (Float.isNaN(supportY) || cy - supportY <= 1.0e-3f) {
                    lever = 0f;
                } else {
                    float[] pinned = solveAboutPivot(inertia, totalMass,
                            cx, supportY, cz, cx, cy, cz, px, py, pz, impulse, spin);
                    pivotY = supportY;
                    // The pinned field carries no J / M of its own; the centre of mass moves only
                    // because it is swinging about the pivot, and that is folded in per part below.
                    vx += (0f - vx) * lever;
                    vy += (0f - vy) * lever;
                    vz += (0f - vz) * lever;
                    omega[0] += (pinned[0] - omega[0]) * lever;
                    omega[1] += (pinned[1] - omega[1]) * lever;
                    omega[2] += (pinned[2] - omega[2]) * lever;
                }
            }
        }

        float maxAngular = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_ANGULAR_SPEED);
        float angularSpeed = (float) Math.sqrt(
                omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2]);
        if (angularSpeed > maxAngular && angularSpeed > 1.0e-6f) {
            float trim = maxAngular / angularSpeed;
            omega[0] *= trim;
            omega[1] *= trim;
            omega[2] *= trim;
        }

        // Each part swings about the blended pivot, which is the centre of mass when nothing was
        // standing and the floor beneath it when something was.
        float turnY = cy + (pivotY - cy) * lever;
        Vector3f velocity = new Vector3f();
        for (int i = 0; i < count; i++) {
            if (centres[i] == null) continue;
            PhysicsBody part = ragdollParts.get(i);
            float dx = centres[i][0] - cx;
            float dy = centres[i][1] - turnY;
            float dz = centres[i][2] - cz;
            part.getLinearVelocity(velocity);
            velocity.x += vx + (omega[1] * dz - omega[2] * dy);
            velocity.y += vy + (omega[2] * dx - omega[0] * dz);
            velocity.z += vz + (omega[0] * dy - omega[1] * dx);
            part.setLinearVelocity(velocity);
            part.getAngularVelocity(velocity);
            velocity.x += omega[0];
            velocity.y += omega[1];
            velocity.z += omega[2];
            part.setAngularVelocity(velocity);
            part.activate();
        }
    }

    // Spin from an impulse at a point about an arbitrary pivot, via the parallel axis theorem.
    // Passing the centre of mass gives the free-body answer.
    private static float[] solveAboutPivot(float[] inertia, float totalMass,
                                           float pivotX, float pivotY, float pivotZ,
                                           float comX, float comY, float comZ,
                                           float pointX, float pointY, float pointZ,
                                           Vector3f impulse, float spin) {
        float ax = comX - pivotX;
        float ay = comY - pivotY;
        float az = comZ - pivotZ;
        float[] about = inertia;
        if (ax * ax + ay * ay + az * az > 1.0e-8f) {
            about = new float[]{
                    inertia[0] + totalMass * (ay * ay + az * az),
                    inertia[1] + totalMass * (ax * ax + az * az),
                    inertia[2] + totalMass * (ax * ax + ay * ay),
                    inertia[3] - totalMass * ax * ay,
                    inertia[4] - totalMass * ax * az,
                    inertia[5] - totalMass * ay * az};
        }
        float lx = pointX - pivotX;
        float ly = pointY - pivotY;
        float lz = pointZ - pivotZ;
        return solveSymmetric3(about,
                (ly * impulse.z - lz * impulse.y) * spin,
                (lz * impulse.x - lx * impulse.z) * spin,
                (lx * impulse.y - ly * impulse.x) * spin);
    }

    // Floor height under a point from a short column scan, or NaN when there's nothing close below.
    private float groundSupportHeight(double x, double z) {
        float lowest = Float.POSITIVE_INFINITY;
        for (PhysicsBody part : ragdollParts) {
            if (part.getInvMass() <= 0f) continue;
            part.getWorldAabb(supportAabbMin, supportAabbMax);
            if (supportAabbMin.y < lowest) lowest = supportAabbMin.y;
        }
        if (!Float.isFinite(lowest)) return Float.NaN;

        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int top = (int) Math.floor(lowest + GROUND_LEVER_ABOVE_TOLERANCE);
        int bottom = (int) Math.floor(lowest - GROUND_LEVER_REACH);
        for (int y = top; y >= bottom; y--) {
            supportBlockPos.set(blockX, y, blockZ);
            VoxelShape shape = level.getBlockState(supportBlockPos)
                    .getCollisionShape(level, supportBlockPos);
            if (shape.isEmpty()) continue;
            double surface = y + shape.max(Direction.Axis.Y);
            if (surface > lowest + GROUND_LEVER_ABOVE_TOLERANCE) continue;
            return (float) surface;
        }
        return Float.NaN;
    }

    // Solves I w = L for a symmetric 3x3 (xx, yy, zz, xy, xz, yz), with a diagonal ridge against singularity.
    private static float[] solveSymmetric3(float[] m, float lx, float ly, float lz) {
        float ridge = 1.0e-4f * Math.max(1f, Math.max(m[0], Math.max(m[1], m[2])));
        float a = m[0] + ridge, b = m[1] + ridge, c = m[2] + ridge;
        float d = m[3], e = m[4], f = m[5];
        float c00 = b * c - f * f;
        float c01 = e * f - d * c;
        float c02 = d * f - e * b;
        float determinant = a * c00 + d * c01 + e * c02;
        if (Math.abs(determinant) < 1.0e-9f) return new float[]{0f, 0f, 0f};
        float c11 = a * c - e * e;
        float c12 = e * d - a * f;
        float c22 = a * b - d * d;
        float inv = 1f / determinant;
        return new float[]{
                (c00 * lx + c01 * ly + c02 * lz) * inv,
                (c01 * lx + c11 * ly + c12 * lz) * inv,
                (c02 * lx + c12 * ly + c22 * lz) * inv};
    }

    // Impulse at the impact point, not the centre of mass, so r x J can tip the body.
    // The arm is clamped to the part's radius; the point is always passed in explicitly.
    private void applyOffCentreImpulse(PhysicsBody body, Vector3f impulse, Vector3f impactPoint) {
        if (impactPoint == null) {
            body.applyCentralImpulse(impulse);
            return;
        }
        body.getWorldTransform(tempTransform);
        scratchLever.set(impactPoint.x - tempTransform.origin.x,
                impactPoint.y - tempTransform.origin.y,
                impactPoint.z - tempTransform.origin.z);

        float radius = partBoundingRadius(body);
        float arm = scratchLever.length();
        if (arm < 1.0e-4f) {
            body.applyCentralImpulse(impulse);
            return;
        }
        if (arm > radius) scratchLever.scale(radius / arm);
        body.applyImpulse(impulse, scratchLever);
    }

    private float partBoundingRadius(PhysicsBody body) {
        PhysicsShape shape = body.getShape();
        if (shape.isBox()) {
            shape.getHalfExtents(scratchHalfExtents);
            return scratchHalfExtents.length();
        }
        return 0.25f;
    }

    private void applyGlobalVelocityKick(Vec3 velocityKick) {
        Vector3f currentVelocity = new Vector3f();
        Vector3f kick = new Vector3f(
                (float) velocityKick.x,
                (float) velocityKick.y,
                (float) velocityKick.z);
        for (PhysicsBody body : ragdollParts) {
            body.getLinearVelocity(currentVelocity);
            currentVelocity.add(kick);
            body.setLinearVelocity(currentVelocity);
            body.activate();
        }
    }

    private static Vector3f scaledImpulse(Vec3 impulse, float scale) {
        return new Vector3f(
                (float) impulse.x * scale,
                (float) impulse.y * scale,
                (float) impulse.z * scale);
    }

    // Manual ray test against the cached part positions, for clicks on settled ragdolls whose
    // bodies have left the physics world. Closest RagdollPart within reach, or null.
    public RagdollPart findHitPart(Vector3f from, Vector3f to) {
        // Read from the published snapshot; runs on render thread (click handler),
        // so we can't touch the physics-thread-owned cachedTransforms directly.
        TransformSnapshot snap = publishedSnapshot;
        if (snap == null || snap.destroyed) return null;

        Vector3f rayDir = new Vector3f(to.x - from.x, to.y - from.y, to.z - from.z);
        float rayLen = rayDir.length();
        if (rayLen < 0.001f) return null;
        rayDir.scale(1f / rayLen);

        RagdollPart bestPart = null;
        float bestDist = 0.5f; // max distance from ray to part center (blocks)

        for (int i = 0; i < snap.positions.length && i < RagdollTransform.MAX_PARTS; i++) {
            if (snap.positions[i] == null) continue;
            Vector3f partPos = snap.positions[i];

            // Project part centre onto ray
            Vector3f toPoint = new Vector3f(partPos.x - from.x, partPos.y - from.y, partPos.z - from.z);
            float t = toPoint.dot(rayDir);
            if (t < 0 || t > rayLen) continue; // behind or beyond reach distance

            // Closest point on ray to part centre
            Vector3f closest = new Vector3f(from.x + rayDir.x * t,
                    from.y + rayDir.y * t,
                    from.z + rayDir.z * t);
            float distToRay = new Vector3f(closest.x - partPos.x,
                    closest.y - partPos.y,
                    closest.z - partPos.z).length();

            if (distToRay < bestDist) {
                bestDist = distToRay;
                bestPart = RagdollPart.byIndex(i);
            }
        }
        return bestPart;
    }

    private void updateCachedTransforms() {
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            // A hidden part's slot is cleared before every publish, so reading its body here would
            // only allocate a transform to throw away again a few lines later.
            if ((hiddenPartMask & (1 << i)) != 0) continue;
            ragdollParts.get(i).getWorldTransform(tempTransform);

            if (cachedTransforms[i] != null) {
                // Save current as previous before overwriting (in place, no alloc).
                prevPositions[i].set(cachedTransforms[i].position);
                prevRotations[i].set(cachedTransforms[i].rotation);
                cachedTransforms[i].position.set(tempTransform.origin);
                tempTransform.getRotation(cachedTransforms[i].rotation);
            } else {
                // First call: allocate prev/current pair. After this we mutate in place.
                Vector3f pos = new Vector3f(tempTransform.origin);
                Quat4f rot = tempTransform.getRotation(new Quat4f());
                prevPositions[i] = new Vector3f(pos);
                prevRotations[i] = new Quat4f(rot);
                cachedTransforms[i] = new RagdollTransform(i, pos, rot);
            }
        }
        if (!ragdollParts.isEmpty()) {
            ragdollParts.get(0).getWorldTransform(tempTransform);
            if (hasPrevTransforms) {
                prevTorsoPos.set(cachedTorsoPos);
            }
            cachedTorsoPos.set(tempTransform.origin);
            if (!hasPrevTransforms) {
                prevTorsoPos.set(cachedTorsoPos);
                hasPrevTransforms = true;
            }
        }

        publishSnapshot();
    }

    // Max stretch of a snapshot's motion; after a longer gap (freeze, off-screen) play at normal speed.
    private static final int MAX_INTERPOLATION_SPAN_TICKS = 4;

    // Publish an immutable copy of the current cached transforms for the render thread. Fresh
    // Vector3f/Quat4f instances, so the render thread never holds one physics is about to mutate.
    private void publishSnapshot() {
        int count = Math.min(ragdollParts.size(), RagdollTransform.MAX_PARTS);
        Vector3f[] pos = new Vector3f[count];
        Quat4f[] rot = new Quat4f[count];
        Vector3f[] halfExtents = new Vector3f[count];
        Vector3f[] prev = new Vector3f[count];
        Quat4f[] prevRot = new Quat4f[count];
        for (int i = 0; i < count; i++) {
            if (cachedTransforms[i] == null) continue;
            pos[i] = new Vector3f(cachedTransforms[i].position);
            rot[i] = new Quat4f(cachedTransforms[i].rotation);
            PhysicsShape partShape = ragdollParts.get(i).getShape();
            if (partShape.isBox()) {
                halfExtents[i] = new Vector3f();
                partShape.getHalfExtents(halfExtents[i]);
            }
            if (prevPositions[i] != null) {
                prev[i] = new Vector3f(prevPositions[i]);
                prevRot[i] = new Quat4f(prevRotations[i]);
            }
        }
        int now = ClientRagdollManager.clientTick();
        int span = lastPublishClientTick == Integer.MIN_VALUE
                ? 1
                : Math.max(1, Math.min(MAX_INTERPOLATION_SPAN_TICKS, now - lastPublishClientTick));
        lastPublishClientTick = now;
        publishedSnapshot = new TransformSnapshot(
                pos, rot, halfExtents, prev, prevRot,
                new Vector3f(cachedTorsoPos),
                new Vector3f(prevTorsoPos),
                hasPrevTransforms,
                destroyed, settled, bodiesFrozen, ticksExisted, ++snapshotSampleTick, span
        );
    }

    // Transform interpolated between the previous and current physics tick by partialTick, 0
    // being the previous tick and 1 the current, so frame rates above 20 Hz stay smooth.
    public RagdollTransform getInterpolatedTransform(RagdollPart part, float partialTick) {
        if (part.index >= ragdollParts.size() || part.index >= RagdollTransform.MAX_PARTS) return null;
        RagdollTransform curr = cachedTransforms[part.index];
        if (curr == null) return null;
        if (!hasPrevTransforms || prevPositions[part.index] == null) return curr;

        Vector3f prev = prevPositions[part.index];
        Quat4f prevRot = prevRotations[part.index];
        float t = partialTick;

        Vector3f pos = new Vector3f(
                prev.x + (curr.position.x - prev.x) * t,
                prev.y + (curr.position.y - prev.y) * t,
                prev.z + (curr.position.z - prev.z) * t
        );
        Quat4f rot = slerpQuat(prevRot, curr.rotation, t);
        return new RagdollTransform(part.index, pos, rot);
    }

    public Vector3f getInterpolatedTorsoPosition(float partialTick) {
        if (!hasPrevTransforms) return cachedTorsoPos;
        float t = partialTick;
        return new Vector3f(
                prevTorsoPos.x + (cachedTorsoPos.x - prevTorsoPos.x) * t,
                prevTorsoPos.y + (cachedTorsoPos.y - prevTorsoPos.y) * t,
                prevTorsoPos.z + (cachedTorsoPos.z - prevTorsoPos.z) * t
        );
    }

    private static Quat4f slerpQuat(Quat4f a, Quat4f b, float t) {
        float dot = a.x*b.x + a.y*b.y + a.z*b.z + a.w*b.w;
        float bx = b.x, by = b.y, bz = b.z, bw = b.w;
        if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw; }
        float scale0, scale1;
        if (dot > 0.9995f) {
            scale0 = 1f - t; scale1 = t;
        } else {
            float angle = (float) Math.acos(dot);
            float sinAngle = (float) Math.sin(angle);
            scale0 = (float) Math.sin((1f - t) * angle) / sinAngle;
            scale1 = (float) Math.sin(t * angle) / sinAngle;
        }
        return new Quat4f(
                scale0 * a.x + scale1 * bx,
                scale0 * a.y + scale1 * by,
                scale0 * a.z + scale1 * bz,
                scale0 * a.w + scale1 * bw
        );
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        // Publish a destroyed-flagged snapshot immediately so the render thread skips this ragdoll
        // rather than reading bodies that are about to be cleared.
        TransformSnapshot prev = publishedSnapshot;
        if (prev != null) {
            publishedSnapshot = new TransformSnapshot(
                    prev.positions, prev.rotations, prev.halfExtents, prev.prevPositions, prev.prevRotations,
                    prev.cachedTorsoPos, prev.prevTorsoPos, prev.hasPrev, true,
                    prev.settled, prev.frozen, prev.ageTicks, prev.sampleTick, prev.spanTicks);
        }

        releaseCurrentCollisionGeometry();

        // Destroy, not just remove, so native memory is freed; destroyBody removes first.
        for (PhysicsConstraint c : ragdollJoints) world.destroyConstraint(c);
        for (PhysicsBody r : ragdollParts) world.destroyBody(r);
        if (!isPlayer) {
            ClientMobTextureCache.evict(originalEntityId);
        }
        // Queue this ragdoll's rebuilt blood textures for release on the render thread.
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.evict(originalEntityId);
        // Drop the Visual Health snapshot (and its reference to the dead entity).
        com.raiiiden.ragdollified.client.compat.VisualHealthCompat.evict(originalEntityId);
        // Worn curios are not handed on; an addon replacement body carries its own snapshot.
        com.raiiiden.ragdollified.client.compat.CuriosRenderCompat.evict(originalEntityId);

        ragdollParts.clear();
        ragdollJoints.clear();
    }

    // Amputation

    // Pose and motion of a part when it came off, so a free limb can continue seamlessly.
    public static final class SeveredPart {
        public final RagdollPart part;
        public final Vector3f position;
        public final Quat4f rotation;
        public final Vector3f linearVelocity;
        public final Vector3f angularVelocity;
        public final Vector3f halfExtents;

        SeveredPart(RagdollPart part, Vector3f position, Quat4f rotation,
                    Vector3f linearVelocity, Vector3f angularVelocity, Vector3f halfExtents) {
            this.part = part;
            this.position = position;
            this.rotation = rotation;
            this.linearVelocity = linearVelocity;
            this.angularVelocity = angularVelocity;
            this.halfExtents = halfExtents;
        }
    }

    // Hide the parts named by a spawn mask. Physics thread, construction only: the bodies are not
    // yet in anyone's hands, so nothing has to be told they went away.
    private void hidePartsFromMask(int mask) {
        if (mask == 0) return;
        for (RagdollPart part : RagdollPart.values()) {
            if (!part.isSeverable() || !RagdollPart.maskContains(mask, part)) continue;
            hidePart(part);
        }
    }

    private void hidePart(RagdollPart part) {
        int i = part.index;
        if (i >= ragdollParts.size() || (hiddenPartMask & part.bit()) != 0) return;
        hiddenPartMask |= part.bit();
        renderHiddenPartMask = hiddenPartMask;
        PhysicsBody body = ragdollParts.get(i);
        // Still simulated and still jointed, so the solver keeps the skeleton it was built with,
        // but it no longer touches the world or anything in it.
        body.setContactResponse(false);
        cachedTransforms[i] = null;
    }

    // Take a part off this body: destroy its joints and hide it, leaving a stump. Physics thread only.
    // Returns the limb's pose and velocity, or null if already gone, not severable or not on this rig.
    public SeveredPart severPart(RagdollPart part) {
        if (destroyed || part == null || !part.isSeverable()) return null;
        int i = part.index;
        if (i >= ragdollParts.size() || (hiddenPartMask & part.bit()) != 0) return null;

        PhysicsBody body = ragdollParts.get(i);
        // Read the state before the joints go: a joint destroyed first can leave the solver having
        // already corrected the body this step, and the limb then flies off along that correction.
        Vector3f position = new Vector3f();
        Quat4f rotation = new Quat4f();
        Vector3f linear = new Vector3f();
        Vector3f angular = new Vector3f();
        body.getPosition(position);
        body.getRotation(rotation);
        body.getLinearVelocity(linear);
        body.getAngularVelocity(angular);
        Vector3f halfExtents = new Vector3f(0.1f, 0.3f, 0.1f);
        PhysicsShape shape = body.getShape();
        if (shape != null && shape.isBox()) shape.getHalfExtents(halfExtents);

        // Every joint the part is an end of, which for the humanoid rig is the single one tying it
        // to the torso, but the loop does not have to know that.
        for (java.util.Iterator<PhysicsConstraint> it = ragdollJoints.iterator(); it.hasNext(); ) {
            PhysicsConstraint joint = it.next();
            if (joint.bodyA() != body && joint.bodyB() != body) continue;
            it.remove();
            world.destroyConstraint(joint);
        }

        hidePart(part);
        // The rest of the skeleton was braced against this limb until a moment ago; left asleep it
        // simply hangs in the pose the joint was holding it in.
        for (PhysicsBody other : ragdollParts) other.activate();
        markSettledPoseDirty();
        settled = false;
        settledTicks = 0;
        if (bodiesFrozen) unfreezeBodies();

        return new SeveredPart(part, position, rotation, linear, angular, halfExtents);
    }

    public boolean isPartSevered(RagdollPart part) {
        return part != null && (renderHiddenPartMask & part.bit()) != 0;
    }

    public int getSeveredPartMask() { return renderHiddenPartMask; }

    public boolean hasBody(PhysicsBody obj) { return ragdollPartsSet.contains(obj); }

    void addGroupPenetrationCorrection(Vector3f normal, float scale) {
        groupPenetrationCorrection.x += normal.x * scale;
        groupPenetrationCorrection.y += normal.y * scale;
        groupPenetrationCorrection.z += normal.z * scale;
    }

    void applyGroupPenetrationCorrection(float maxDistance, float damping) {
        float lengthSq = groupPenetrationCorrection.lengthSquared();
        if (lengthSq <= 1.0e-8f) {
            groupPenetrationCorrection.set(0f, 0f, 0f);
            return;
        }
        if (lengthSq > maxDistance * maxDistance) {
            groupPenetrationCorrection.scale(maxDistance / (float) Math.sqrt(lengthSq));
        }
        for (PhysicsBody body : ragdollParts) {
            body.translate(groupPenetrationCorrection);
            body.getLinearVelocity(scratchVel);
            scratchVel.scale(damping);
            body.setLinearVelocity(scratchVel);
            body.getAngularVelocity(scratchAng);
            scratchAng.scale(damping);
            body.setAngularVelocity(scratchAng);
        }
        groupPenetrationCorrection.set(0f, 0f, 0f);
    }

    // Pre-step centre of mass, linear and angular velocity per part, nine floats each. Filled only
    // with a collision listener registered, so impacts score off pre-step rather than post-solver.
    private final float[] impactSample = new float[RagdollTransform.MAX_PARTS * 9];
    private boolean impactSampleValid;

    public void captureImpactSample() {
        int count = Math.min(ragdollParts.size(), RagdollTransform.MAX_PARTS);
        for (int i = 0; i < count; i++) {
            PhysicsBody body = ragdollParts.get(i);
            body.getCenterOfMassPosition(scratchVel);
            int base = i * 9;
            impactSample[base] = scratchVel.x;
            impactSample[base + 1] = scratchVel.y;
            impactSample[base + 2] = scratchVel.z;
            body.getLinearVelocity(scratchVel);
            impactSample[base + 3] = scratchVel.x;
            impactSample[base + 4] = scratchVel.y;
            impactSample[base + 5] = scratchVel.z;
            body.getAngularVelocity(scratchVel);
            impactSample[base + 6] = scratchVel.x;
            impactSample[base + 7] = scratchVel.y;
            impactSample[base + 8] = scratchVel.z;
        }
        impactSampleValid = count > 0;
    }

    public void clearImpactSample() {
        impactSampleValid = false;
    }

    // World velocity the part had at contactPoint before the step, written into out.
    public boolean readImpactVelocity(int partIndex, Vector3f contactPoint, Vector3f out) {
        if (!impactSampleValid || partIndex < 0 || partIndex >= RagdollTransform.MAX_PARTS
                || partIndex >= ragdollParts.size()) {
            return false;
        }
        int base = partIndex * 9;
        float rx = contactPoint.x - impactSample[base];
        float ry = contactPoint.y - impactSample[base + 1];
        float rz = contactPoint.z - impactSample[base + 2];
        float wx = impactSample[base + 6], wy = impactSample[base + 7], wz = impactSample[base + 8];
        out.set(
                impactSample[base + 3] + wy * rz - wz * ry,
                impactSample[base + 4] + wz * rx - wx * rz,
                impactSample[base + 5] + wx * ry - wy * rx);
        return true;
    }

    public int partIndexOf(PhysicsBody body) {
        for (int i = 0; i < ragdollParts.size(); i++) {
            if (ragdollParts.get(i) == body) return i;
        }
        return -1;
    }

    public RagdollTransform getTransform(RagdollPart part) {
        if (part.index >= ragdollParts.size() || part.index >= RagdollTransform.MAX_PARTS) return null;
        return cachedTransforms[part.index];
    }
    public RagdollTransform[] getAllTransforms() { return cachedTransforms; }
    public Vector3f getTorsoPosition() { return cachedTorsoPos; }
    public int getId() { return id; }
    public boolean isDestroyed() { return destroyed; }
    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }

    // Restart death bookkeeping for a body an integration owned. The pose is untouched; the lifetime
    // and one-shot pose and settle reports are re-armed. Physics thread only.
    public void restartDeathLifetime() {
        ticksExisted = 0;
        // A settle consumer counts pushes from zero, so a body shoved while its owner lived would
        // report a revision it can never match and never get a replacement built from this pose.
        lastImpulseRevision = 0;
        // A body an integration held has almost certainly settled already, which would report a pose
        // that looks like it hit the floor on death. Clear the settle so the new lifetime is simulated.
        settled = false;
        settledOnLiquid = false;
        settledTicks = 0;
        settleGraceTicks = HANDOVER_SETTLE_GRACE_TICKS;
        pendingTerrainValidation = false;
        hasSettledTerrainSignature = false;
        settledGroundSupportBlocks.clear();
        settledSupportRagdolls.clear();
        if (bodiesFrozen) {
            unfreezeBodies();
        } else {
            // Already in the world, but the engine may have slept them while they were settled.
            for (PhysicsBody body : ragdollParts) {
                body.setSleepingAllowed(false);
                body.activate();
            }
        }
        markSettledPoseDirty();
    }
    // True while a body handed back as a fresh death ragdoll is still owed its active window. Blocks
    // both settle and its report, since a replacement built inside the grace freezes an unmoved body.
    public boolean isAwaitingSettleGrace() { return settleGraceTicks > 0; }
    // Log a freeze that caught a limb mid-motion: which path froze it and how much motion was lost.
    private void reportPrematureSettle(String reason) {
        if (!Ragdollified.LOGGER.isDebugEnabled()) return;

        RagdollPart worst = null;
        float worstSpeed = SETTLE_REPORT_ANG_SPEED;
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            ragdollParts.get(i).getAngularVelocity(scratchAng);
            float speed = scratchAng.length();
            if (speed > worstSpeed) {
                worstSpeed = speed;
                worst = RagdollPart.byIndex(i);
            }
        }
        if (worst == null) return;

        Ragdollified.LOGGER.debug(
                "ragdoll {} froze via {} after {} ticks while its {} was still turning at {} rad/s",
                id, reason, ticksExisted, worst.name().toLowerCase(),
                String.format("%.2f", worstSpeed));
    }

    // One line per part with linear and angular speed, for the debug command.
    public String motionReport() {
        StringBuilder out = new StringBuilder();
        out.append(settled ? "settled" : "active")
                .append(bodiesFrozen ? "/frozen" : "")
                .append(" age=").append(ticksExisted)
                .append(" quietChecks=").append(settledTicks);
        Vector3f linear = new Vector3f();
        Vector3f angular = new Vector3f();
        for (int i = 0; i < ragdollParts.size() && i < RagdollTransform.MAX_PARTS; i++) {
            RagdollPart part = RagdollPart.byIndex(i);
            PhysicsBody body = ragdollParts.get(i);
            body.getLinearVelocity(linear);
            body.getAngularVelocity(angular);
            out.append(String.format("\n  %-9s v=%.2f w=%.2f",
                    part == null ? "part" + i : part.name().toLowerCase(),
                    linear.length(), angular.length()));
        }
        return out.toString();
    }

    public boolean isSettled() { return settled; }
    public boolean isSettledOnLiquid() { return settledOnLiquid; }
    public boolean isFrozen() { return bodiesFrozen; }

    // True if any body is moving fast enough to be a valid cascade-wake source (> 0.3 m/s).
    public boolean isMovingSignificantly() {
        for (PhysicsBody r : ragdollParts) {
            r.getLinearVelocity(scratchVel);
            // length() > 0.3 → length()² > 0.09. Skips the sqrt.
            float lsq = scratchVel.x * scratchVel.x + scratchVel.y * scratchVel.y + scratchVel.z * scratchVel.z;
            if (lsq > 0.09f) return true;
        }
        return false;
    }
    public int getTicksExisted() { return ticksExisted; }
    public boolean isPlayer() { return isPlayer; }
    public String getMobType() { return mobType; }
    public float getScale() { return scale; }
    public UUID getPlayerUUID() { return playerUUID; }
    public String getPlayerName() { return playerName; }
    public ItemStack getHelmet() { return helmet; }
    public ItemStack getChestplate() { return chestplate; }
    public ItemStack getLeggings() { return leggings; }
    public ItemStack getBoots() { return boots; }
    public MobModelHelper.ModelType getModelType() { return modelType; }
    public ResourceLocation getCachedTexture() { return cachedTexture; }
    public void setCachedTexture(ResourceLocation texture) { this.cachedTexture = texture; }
    public boolean wasSheared() { return wasSheared; }
    public int getDyeColorId() { return dyeColorId; }
    public boolean isChargedCreeper() { return chargedCreeper; }
    public boolean isSaddledPig() { return saddledPig; }
    public boolean isSaddledEquine() { return modelType == MobModelHelper.ModelType.EQUINE && chargedCreeper; }
    public boolean hasEquineChest() { return modelType == MobModelHelper.ModelType.EQUINE && wasSheared; }
    public int getHorseMarkingsId() { return modelType == MobModelHelper.ModelType.EQUINE ? dyeColorId : 0; }
    public boolean isBaby() { return isBaby; }
    public boolean isBabyCow() {
        return isBaby && (isMobPath("cow") || isMobPath("mooshroom"));
    }
    public boolean isBabyPig() { return isBaby && isMobPath("pig"); }
    public boolean isBabySheep() { return isBaby && isMobPath("sheep"); }
    public boolean isBabyChicken() { return isBaby && isMobPath("chicken"); }
    public boolean isBabyCat() { return isBaby && (isMobPath("cat") || isMobPath("ocelot")); }
    public boolean isBabyWolf() { return isBaby && isMobPath("wolf"); }
    public boolean isBabyFox() { return isBaby && isMobPath("fox"); }
    public boolean isBabyPanda() { return isBaby && isMobPath("panda"); }
    public boolean isBabyGoat() { return isBaby && isMobPath("goat"); }
    public boolean isBabyPolarBear() { return isBaby && isMobPath("polar_bear"); }
    public boolean isBabyTurtle() { return isBaby && isMobPath("turtle"); }
    public boolean isBabyCamel() { return isBaby && isMobPath("camel"); }
    public boolean isBabyLlama() { return isBaby && (isMobPath("llama") || isMobPath("trader_llama")); }
    public boolean isBabyRabbit() { return isBaby && isMobPath("rabbit"); }
    public boolean isBabyHoglin() { return isBaby && (isMobPath("hoglin") || isMobPath("zoglin")); }
    public boolean isBabySniffer() { return isBaby && isMobPath("sniffer"); }
    public boolean isBabyStrider() { return isBaby && isMobPath("strider"); }
    public boolean isBabyBee() { return isBaby && isMobPath("bee"); }
    public boolean isBabyEquine() { return isBaby && modelType == MobModelHelper.ModelType.EQUINE; }
    // Baby humanoid (baby zombie/husk/piglin/zombie-villager, …): scaled-down body + model.
    public boolean isBabyHumanoid() {
        return isBaby && MobModelHelper.isHumanoidModelType(modelType);
    }
    // Whether this mob's vanilla model enlarges the baby head instead of shrinking uniformly: zombie
    // family and zombie villagers use HumanoidModel scaleHead, plain villagers scale uniformly.
    public boolean babyScalesHead() {
        if (!MobModelHelper.isHumanoidModelType(modelType)) return false;
        if (modelType == MobModelHelper.ModelType.ILLAGER) {
            // ILLAGER covers villagers + zombie villagers here; only the zombie variant gets
            // the big head in vanilla (regular illagers never spawn young).
            return mobType.contains("zombie");
        }
        return true;
    }
    public boolean usesBabyBodyScale() {
        return isBabyCow() || isBabyPig() || isBabySheep() || isBabyChicken() || isBabyCat()
                || isBabyWolf() || isBabyFox() || isBabyPanda() || isBabyGoat()
                || isBabyPolarBear() || isBabyTurtle() || isBabyCamel() || isBabyLlama()
                || isBabyRabbit() || isBabyHoglin() || isBabySniffer() || isBabyStrider();
    }
    public RagdollBodyFactory.BodyProfile getBodyProfile() {
        if (isMobPath("cow") || isMobPath("mooshroom")) return RagdollBodyFactory.BodyProfile.COW;
        if (isMobPath("pig")) return RagdollBodyFactory.BodyProfile.PIG;
        if (isMobPath("sheep")) return RagdollBodyFactory.BodyProfile.SHEEP;
        if (isMobPath("cat") || isMobPath("ocelot")) return RagdollBodyFactory.BodyProfile.CAT;
        if (isMobPath("wolf")) return RagdollBodyFactory.BodyProfile.WOLF;
        if (isMobPath("fox")) return RagdollBodyFactory.BodyProfile.FOX;
        if (isMobPath("panda")) return RagdollBodyFactory.BodyProfile.PANDA;
        if (isMobPath("goat")) return RagdollBodyFactory.BodyProfile.GOAT;
        if (isMobPath("polar_bear")) return RagdollBodyFactory.BodyProfile.POLAR_BEAR;
        if (isMobPath("turtle")) return RagdollBodyFactory.BodyProfile.TURTLE;
        if (isMobPath("camel")) return RagdollBodyFactory.BodyProfile.CAMEL;
        if (isMobPath("llama") || isMobPath("trader_llama")) return RagdollBodyFactory.BodyProfile.LLAMA;
        if (isMobPath("rabbit")) return RagdollBodyFactory.BodyProfile.RABBIT;
        if (isMobPath("frog")) return RagdollBodyFactory.BodyProfile.FROG;
        if (isMobPath("hoglin") || isMobPath("zoglin")) return RagdollBodyFactory.BodyProfile.HOGLIN;
        if (isMobPath("sniffer")) return RagdollBodyFactory.BodyProfile.SNIFFER;
        if (isMobPath("ravager")) return RagdollBodyFactory.BodyProfile.RAVAGER;
        if (isMobPath("phantom")) return RagdollBodyFactory.BodyProfile.PHANTOM;
        if (isMobPath("parrot")) return RagdollBodyFactory.BodyProfile.PARROT;
        if (isMobPath("magma_cube")) return RagdollBodyFactory.BodyProfile.MAGMA_CUBE;
        if (isMobPath("slime")) return RagdollBodyFactory.BodyProfile.SLIME;
        if (isMobPath("silverfish")) return RagdollBodyFactory.BodyProfile.SILVERFISH;
        if (isMobPath("endermite")) return RagdollBodyFactory.BodyProfile.ENDERMITE;
        if (isMobPath("allay")) return RagdollBodyFactory.BodyProfile.ALLAY;
        if (isMobPath("strider")) return RagdollBodyFactory.BodyProfile.STRIDER;
        if (isMobPath("snow_golem")) return RagdollBodyFactory.BodyProfile.SNOW_GOLEM;
        if (isMobPath("blaze")) return RagdollBodyFactory.BodyProfile.BLAZE;
        if (isMobPath("cave_spider")) return RagdollBodyFactory.BodyProfile.CAVE_SPIDER;
        if (isMobPath("spider")) return RagdollBodyFactory.BodyProfile.SPIDER;
        if (isMobPath("shulker")) return RagdollBodyFactory.BodyProfile.SHULKER;
        if (isMobPath("ghast")) return RagdollBodyFactory.BodyProfile.GHAST;
        if (isMobPath("vex")) return RagdollBodyFactory.BodyProfile.VEX;
        if (isMobPath("warden")) return RagdollBodyFactory.BodyProfile.WARDEN;
        if (isMobPath("elder_guardian")) return RagdollBodyFactory.BodyProfile.ELDER_GUARDIAN;
        if (isMobPath("guardian")) return RagdollBodyFactory.BodyProfile.GUARDIAN;
        if (isMobPath("squid") || isMobPath("glow_squid")) return RagdollBodyFactory.BodyProfile.SQUID;
        if (isMobPath("dolphin")) return RagdollBodyFactory.BodyProfile.DOLPHIN;
        if (isMobPath("axolotl")) return RagdollBodyFactory.BodyProfile.AXOLOTL;
        if (isMobPath("salmon")) return RagdollBodyFactory.BodyProfile.SALMON;
        if (isMobPath("tropical_fish")) return RagdollBodyFactory.BodyProfile.TROPICAL_FISH;
        if (isMobPath("pufferfish")) return RagdollBodyFactory.BodyProfile.PUFFERFISH;
        if (isMobPath("tadpole")) return RagdollBodyFactory.BodyProfile.TADPOLE;
        if (isMobPath("cod")) return RagdollBodyFactory.BodyProfile.COD;
        if (isMobPath("wither")) return RagdollBodyFactory.BodyProfile.WITHER;
        if (isMobPath("ender_dragon")) return RagdollBodyFactory.BodyProfile.ENDER_DRAGON;
        if (isMobPath("chicken")) return RagdollBodyFactory.BodyProfile.CHICKEN;
        if (isMobPath("horse")) return RagdollBodyFactory.BodyProfile.HORSE;
        if (isMobPath("donkey")) return RagdollBodyFactory.BodyProfile.DONKEY;
        if (isMobPath("mule")) return RagdollBodyFactory.BodyProfile.MULE;
        return RagdollBodyFactory.BodyProfile.DEFAULT;
    }

    // Exact registry path match; avoids treating piglins as pigs or vindicators as cats.
    private boolean isMobPath(String expectedPath) {
        int separator = mobType.indexOf(':');
        int pathStart = separator >= 0 ? separator + 1 : 0;
        return mobType.length() - pathStart == expectedPath.length()
                && mobType.regionMatches(pathStart, expectedPath, 0, expectedPath.length());
    }
    public float getPhantomRenderScale() {
        int phantomSize = Math.max(0, Math.round((scale * 3.6f - 1f) * 4.5f));
        return 1f + .15f * phantomSize;
    }
    public String getVillagerType() { return villagerType; }
    public String getVillagerProfession() { return villagerProfession; }
    public int getVillagerLevel() { return villagerLevel; }
    public int getOriginalEntityId() { return originalEntityId; }
    public ClientLevel getLevel() { return level; }
    public ResourceLocation getCachedPlayerSkin() { return cachedPlayerSkin; }
    public boolean isCachedSlim() { return cachedIsSlim; }

    // Settle reporting: per-client one-shot guard so each observer reports this ragdoll's
    // settle at most once. The server de-duplicates reports from multiple observers.
    private volatile boolean settleReported = false;
    public boolean isSettleReported() { return settleReported; }
    public void markSettleReported() { settleReported = true; }

    // Generic server-retained pose report used for late area entrants.
    private volatile boolean settledPoseReported = false;
    public boolean isSettledPoseReported() { return settledPoseReported; }
    public void markSettledPoseReported() { settledPoseReported = true; }

    private void markSettledPoseDirty() {
        settleReported = false;
        settledPoseReported = false;
    }

    // Math helpers (kept locally for interpolation, body/joint creation delegated to RagdollBodyFactory)

}
