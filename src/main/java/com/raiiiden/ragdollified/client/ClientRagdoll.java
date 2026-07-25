package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import com.raiiiden.ragdollified.*;
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

@OnlyIn(Dist.CLIENT)
public class ClientRagdoll {

    public static final int CENTER_HIT_PART_INDEX = RagdollHitMapper.CENTER_HIT_PART_INDEX;
    private final int id;

    // Physics — mirrors MobRagdollPhysics field layout
    private final ClientJbulletWorld physicsWorld;
    private final DiscreteDynamicsWorld world;
    public final List<RigidBody> ragdollParts = new ArrayList<>(6);
    // O(1) body membership for contact and hit checks.
    private final Set<RigidBody> ragdollPartsSet = new HashSet<>(8);
    private final List<TypedConstraint> ragdollJoints = new ArrayList<>(5);
    private BlockPos lastCollisionCenter = BlockPos.ZERO;
    private ClientJbulletWorld.CollisionGeometryHandle currentCollisionGeometry;
    private BlockPos settledTerrainCenter = BlockPos.ZERO;
    private long settledTerrainSignature;
    private boolean hasSettledTerrainSignature;
    private int collisionGeometryAcquiredTick = Integer.MIN_VALUE;
    private final Set<BlockPos> activeGroundSupportBlocks = new HashSet<>(8);
    private final Set<BlockPos> settledGroundSupportBlocks = new HashSet<>(8);
    public static final int COLLISION_RADIUS = 3;

    // Scratch vectors reused across per-tick physics calls. Each ragdoll has its own
    // (single client thread, no contention) — purely to avoid GC pressure from the
    // hundreds of `new Vector3f()` allocations per tick across all active ragdolls.
    private final Vector3f scratchVel = new Vector3f();
    private final Vector3f scratchAng = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();
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
    private static final float PHANTOM_VERTICAL_SPEED = 0.12f;
    private static final float FLUID_SURFACE_BELOW = 1.0f;
    private static final float FLUID_SURFACE_ABOVE = 0.2f;


    // Lifecycle
    private int ticksExisted = 0;
    private final int lifetime;
    private boolean destroyed = false;

    // Settled detection
    private int settledTicks = 0;
    private boolean settled = false;
    private boolean pendingTerrainValidation = false;
    // Set alongside `settled` when the resting surface was fluid (water/lava) instead of
    // solid ground. Renderer reads this to apply a sin-based bob offset so frozen bodies
    // visibly float without re-running physics. Cleared on every wake path.
    private boolean settledOnLiquid = false;
    private static final float SETTLED_VELOCITY_THRESHOLD = 0.05f;
    private static final float SETTLED_ANG_VELOCITY_THRESHOLD = 0.15f;
    private static final int SETTLED_CHECKS_REQUIRED = 4; // 4 checks × 5 ticks = 20 ticks to settle
    private boolean bodiesFrozen = false;

    // Displacement-based settle: ragdolls in piles often vibrate above the velocity
    // threshold (contact-induced jitter) but don't actually move anywhere. Without this,
    // a pile of 15+ ragdolls stays "active" indefinitely and the solver runs full-cost
    // every tick. Snapshot the torso every N ticks; if it hasn't moved, force settle.
    private final Vector3f settleAnchorPos = new Vector3f();
    private int settleAnchorTick = -1;
    private static final int SETTLE_DISPLACEMENT_INTERVAL = 20;     // 1s — was 2s; in
    //   piles, ragdolls jiggle for many seconds before velocity drops below the static
    //   threshold. Faster displacement-based settle drains the active set sooner, which
    //   directly cuts manifold count which directly cuts solver work.
    private static final float SETTLE_DISPLACEMENT_THRESHOLD_SQ = 0.04f; // (0.2 blocks)²

    // Cached transforms — current tick
    private final RagdollTransform[] cachedTransforms = new RagdollTransform[6];
    private final Vector3f cachedTorsoPos = new Vector3f();
    private final Transform tempTransform = new Transform();

    // Previous-tick transforms for render interpolation
    private final Vector3f[] prevPositions = new Vector3f[6];
    private final Quat4f[] prevRotations = new Quat4f[6];
    private final Vector3f prevTorsoPos = new Vector3f();
    private boolean hasPrevTransforms = false;

    // Cached skins
    private final ResourceLocation cachedPlayerSkin;
    private final boolean cachedIsSlim;

    /**
     * Immutable snapshot of all transform state needed for rendering. Published
     * atomically by the physics thread at the end of each updateCachedTransforms;
     * read by the render thread (and click raycast) without any locking.
     *
     * Allocations: 1 snapshot + 26 small vectors per ragdoll per physics tick =
     * ~5MB/sec at 50 ragdolls × 20Hz. Manageable, and the snapshot lifetime is
     * exactly one tick so it dies young and lives in the eden generation.
     */
    public static final class TransformSnapshot {
        public final Vector3f[] positions;     // 6 part positions (current)
        public final Quat4f[] rotations;       // 6 part rotations (current)
        public final Vector3f[] halfExtents;   // 6 part BoxShape half extents (current)
        public final Vector3f[] prevPositions; // 6 part positions (previous tick)
        public final Quat4f[] prevRotations;   // 6 part rotations (previous tick)
        public final Vector3f cachedTorsoPos;
        public final Vector3f prevTorsoPos;
        public final boolean hasPrev;
        public final boolean destroyed;

        TransformSnapshot(Vector3f[] positions, Quat4f[] rotations, Vector3f[] halfExtents,
                          Vector3f[] prevPositions, Quat4f[] prevRotations,
                          Vector3f cachedTorsoPos, Vector3f prevTorsoPos,
                          boolean hasPrev, boolean destroyed) {
            this.positions = positions;
            this.rotations = rotations;
            this.halfExtents = halfExtents;
            this.prevPositions = prevPositions;
            this.prevRotations = prevRotations;
            this.cachedTorsoPos = cachedTorsoPos;
            this.prevTorsoPos = prevTorsoPos;
            this.hasPrev = hasPrev;
            this.destroyed = destroyed;
        }

        /** Interpolated transform for the given part — for use on render thread. */
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

    /**
     * The latest published transform state. Written by the physics thread inside
     * publishSnapshot(); read freely by the render thread. Volatile guarantees
     * visibility — every render-thread read sees a consistent snapshot.
     */
    private volatile TransformSnapshot publishedSnapshot = null;

    public TransformSnapshot getSnapshot() { return publishedSnapshot; }

    // ============================
    // Render-thread-only smoothed state — DO NOT touch from any other thread.
    // ============================
    // Physics produces jitter under heavy contact pressure (the solver oscillates
    // bodies a few mm/tick when piles compress). The raw snapshot interpolation shows
    // every wiggle. We low-pass-filter on the render side: each frame, exponentially
    // blend the displayed transform toward the interpolated snapshot value.
    //
    // alpha = 1 - exp(-dt * SMOOTHING_RATE) — dt-aware so it works at any FPS.
    // SMOOTHING_RATE = 25 → half-life ~28ms, enough to kill 50ms-period jitter
    // (the typical tick-to-tick wobble) without visible lag on real motion.
    private static final float SMOOTHING_RATE = 25f;
    private final Vector3f[] smoothPositions = new Vector3f[6];
    private final Quat4f[] smoothRotations = new Quat4f[6];
    private final Vector3f smoothTorsoPos = new Vector3f();
    private boolean smoothInitialized = false;
    private long smoothLastFrameNanos = 0;
    // Reusable scratch for per-frame interpolation (render thread only)
    private final Vector3f scratchInterpPos = new Vector3f();
    private final Quat4f scratchInterpRot = new Quat4f();

    /**
     * Update the smoothed render state from the latest snapshot. Called once per
     * ragdoll per frame from the renderer. Render thread only.
     */
    public void updateSmoothedRenderState(TransformSnapshot snap, float partialTick) {
        if (snap == null || snap.destroyed) return;

        long now = System.nanoTime();
        // First call after a long gap (or initial frame) → snap to current value
        // instead of blending in from stale state.
        boolean needInit = !smoothInitialized
                || smoothLastFrameNanos == 0
                || now - smoothLastFrameNanos > 250_000_000L; // 250ms gap = re-init
        float dt = needInit ? 0f : (now - smoothLastFrameNanos) / 1_000_000_000f;
        smoothLastFrameNanos = now;

        float alpha = needInit ? 1f : (float) (1.0 - Math.exp(-dt * SMOOTHING_RATE));

        // Smooth each part
        for (int i = 0; i < snap.positions.length && i < 6; i++) {
            if (snap.positions[i] == null) continue;
            // Compute the snapshot-interpolated target for this part
            interpolateInto(snap, i, partialTick, scratchInterpPos, scratchInterpRot);

            if (smoothPositions[i] == null) {
                smoothPositions[i] = new Vector3f(scratchInterpPos);
                smoothRotations[i] = new Quat4f(scratchInterpRot);
            } else {
                smoothPositions[i].x += (scratchInterpPos.x - smoothPositions[i].x) * alpha;
                smoothPositions[i].y += (scratchInterpPos.y - smoothPositions[i].y) * alpha;
                smoothPositions[i].z += (scratchInterpPos.z - smoothPositions[i].z) * alpha;
                slerpInto(smoothRotations[i], scratchInterpRot, alpha, smoothRotations[i]);
            }
        }

        // Smooth torso pos (used for distance culling — keep it consistent with parts)
        if (!smoothInitialized) {
            smoothTorsoPos.set(snap.getInterpolatedTorsoPosition(partialTick));
        } else {
            Vector3f targetTorso = snap.getInterpolatedTorsoPosition(partialTick);
            smoothTorsoPos.x += (targetTorso.x - smoothTorsoPos.x) * alpha;
            smoothTorsoPos.y += (targetTorso.y - smoothTorsoPos.y) * alpha;
            smoothTorsoPos.z += (targetTorso.z - smoothTorsoPos.z) * alpha;
        }

        smoothInitialized = true;
    }

    /**
     * Returns a transform built from the smoothed render state. Allocates a fresh
     * RagdollTransform — caller (renderer) treats it as immutable for the frame.
     */
    public RagdollTransform getSmoothedTransform(RagdollPart part) {
        int i = part.index;
        if (i >= 6 || smoothPositions[i] == null) return null;
        return new RagdollTransform(i, smoothPositions[i], smoothRotations[i]);
    }

    public Vector3f getSmoothedTorsoPos() { return smoothTorsoPos; }

    /** Helper — write the snapshot's interpolated transform into the provided buffers. */
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

    /** Slerp a→b by t, written into out (which may alias a or b). */
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
    // Sheep state (only meaningful when modelType is QUADRUPED and the mob is a sheep).
    // wasSheared = true → renderer skips the wool overlay; otherwise dyeColorId picks the
    // tint applied to the SheepFurModel via DyeColor.byId(dyeColorId).
    private final boolean wasSheared;
    private final int dyeColorId;
    // Other mob-specific overlay state captured at death.
    // chargedCreeper → render the creeper energy-swirl overlay; saddledPig → render the
    // pig saddle overlay. Both are ignored unless mobType matches.
    private final boolean chargedCreeper;
    private final boolean saddledPig;
    private final boolean isBaby;
    // Villager profession state — empty type means "no profession layer to render".
    // Registry keys (e.g. "minecraft:plains", "minecraft:farmer") so mod-added types
    // survive without a registry-id remap. Level 1..5; 0 = unknown.
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
        // jBullet units (blocks per second).
        public final Vec3 position;
        public final float yRot;
        public final float xRot;
        public final Vec3 velocity;
        public final MobPoseCapture.MobPose capturedPose;
        public final boolean isSwimming;
        public final boolean isBaby;
        public final ResourceLocation texture;
        // Optional hit info — applied as a one-shot impulse to a single part right after
        // bodies are constructed. Set hitPartIndex = -1 to skip. Used so a TACZ bullet
        // that kills a mob whips the right body part in the bullet's travel direction.
        public final int hitPartIndex;
        public final Vec3 hitImpulse;
        // Sheep state captured at death so the renderer can decide whether to draw the
        // wool layer and which dye color to tint it. Ignored for non-sheep mobs.
        public final boolean wasSheared;
        public final int dyeColorId; // 0..15, DyeColor.byId; only meaningful if !wasSheared
        // Other per-mob overlay flags. Only meaningful when mobType matches.
        public final boolean chargedCreeper;
        public final boolean saddledPig;
        // Villager profession state. Empty type = "no profession layer". Strings are
        // registry keys ("minecraft:plains", "minecraft:farmer") so mod-added biomes
        // and professions survive without an id remap.
        public final String villagerType;
        public final String villagerProfession;
        public final int villagerLevel;

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

    public ClientRagdoll(SpawnData data, ClientJbulletWorld physicsWorld) {
        this.id = data.originalEntityId;
        this.physicsWorld = physicsWorld;
        this.world = physicsWorld.getDynamicsWorld();
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
        updateCachedTransforms();
        updateLocalWorldCollision();

        // Death-time directional impulse (TACZ bullet hit, vanilla arrow, etc.).
        // Applied after bodies exist + transforms cached, before publish, so the very
        // first rendered frame already shows the recoil. Runs on the physics worker
        // (we're inside processSpawnQueue), so direct jbullet calls are safe here.
        if (data.hitImpulse != null) {
            if (data.hitPartIndex == RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX) {
                applyGlobalVelocityKick(data.hitImpulse);
            } else if (data.hitPartIndex == CENTER_HIT_PART_INDEX) {
                applyCenteredDeathImpulse(data.hitImpulse);
            } else if (data.hitPartIndex >= 0 && data.hitPartIndex < ragdollParts.size()) {
                RagdollPart part = RagdollPart.byIndex(data.hitPartIndex);
                RigidBody body = ragdollParts.get(data.hitPartIndex);
                body.activate(true);
                body.applyCentralImpulse(scaledImpulse(data.hitImpulse,
                        part != null ? RagdollifiedConfig.getDeathPartKnockbackMultiplier(part) : 1.0f));
            }
        }
        // Resolve player skin + slim variant NOW, while the entity is still loaded. Resolving
        // through ClientPlayerSkinCache (connection PlayerInfo, not just loaded entities) both
        // makes this robust when the owner is briefly out of range and seeds the per-UUID cache,
        // so the corpse that replaces this ragdoll later still finds the real skin.
        if (isPlayer && playerUUID != null) {
            ClientPlayerSkinCache.Skin resolved = ClientPlayerSkinCache.resolve(playerUUID);
            cachedPlayerSkin = resolved.texture;
            cachedIsSlim = resolved.slim;
        } else {
            cachedPlayerSkin = null;
            cachedIsSlim = false;
        }
    }

    // ============================
    // Tick — mirrors MobRagdollPhysics.update() order exactly
    // ============================

    /**
     * Aggregated per-phase timings across all ragdoll ticks in the current cycle.
     * Reset at the start of each ClientRagdollManager.tickAll() and logged at the end.
     * Public so the manager can read and reset; not thread-safe (client thread only).
     */
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

        long t = System.nanoTime();
        updateCachedTransforms();
        PHASE_STATS.updateCachedTransformsNanos += System.nanoTime() - t;

        if (settled) {
            // Settled bodies keep aging toward despawn even while the player is away.
            ticksExisted++;
            if (ticksExisted >= lifetime) {
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
                if (!atSurface) settledGroundSupportBlocks.addAll(activeGroundSupportBlocks);
                settledTerrainCenter = torsoBlock;
                settledTerrainSignature = computeTerrainSignature(torsoBlock);
                hasSettledTerrainSignature = true;
            }
            // Periodic support check — ragdolls don't always get a NeighborNotifyEvent
            // when their support block is broken (especially for server-initiated
            // changes that don't fire client-side events). Every 10 ticks (0.5s),
            // verify there's still ground under us — OR fluid carrying us. If neither,
            // wake up and force the collision cache to rebuild.
            boolean terrainChanged = hasSettledTerrainSignature
                    && Math.floorMod(ticksExisted + id, 20) == 0
                    && computeTerrainSignature(settledTerrainCenter) != settledTerrainSignature;
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
            // Too far to simulate: pause the ragdoll. Crucially we do NOT advance ticksExisted
            // here, so a distance-frozen body is suspended rather than aging — it resumes exactly
            // where it left off when the player returns, and (for player corpses) the settle
            // report waits for the real resting place instead of giving up mid-fall at the death
            // position.
            if (!bodiesFrozen) {
                freezeBodies();
                PHASE_STATS.distanceFrozenThisTick++;
            }
            return;
        } else if (bodiesFrozen) {
            unfreezeBodies();
            PHASE_STATS.unfrozenThisTick++;
        }

        // Actively simulating now — advance the lifetime clock (skipped while paused above).
        ticksExisted++;
        if (ticksExisted >= lifetime) {
            destroy();
            return;
        }

        // Mirrors MobRagdollPhysics.update() order exactly:
        // 1. velocity clamp
        t = System.nanoTime();
        for (RigidBody r : ragdollParts) {
            r.getLinearVelocity(scratchVel);
            float maxFallSpeed = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_FALL_SPEED);
            float maxLinearSpeed = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_LINEAR_SPEED);
            float maxAngularSpeed = (float) RagdollifiedConfig.get(RagdollifiedConfig.MAX_ANGULAR_SPEED);
            if (scratchVel.y < -maxFallSpeed) scratchVel.y = -maxFallSpeed;
            float speed = scratchVel.length();
            if (speed > maxLinearSpeed) { scratchVel.scale(maxLinearSpeed / speed); r.setLinearVelocity(scratchVel); }

            r.getAngularVelocity(scratchAng);
            float angSpeed = scratchAng.length();
            if (angSpeed > maxAngularSpeed) { scratchAng.scale(maxAngularSpeed / angSpeed); r.setAngularVelocity(scratchAng); }
        }
        PHASE_STATS.velocityClampNanos += System.nanoTime() - t;

        // 2. fluid forces — always run (not gated by camera distance). Per-part fluid
        // lookups are cheap, and a ragdoll falling into water needs buoyancy regardless
        // of where the player is standing — otherwise it would sink to the bottom while
        // the player was looking elsewhere and never reach the water-surface settle path.
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

        // Settled detection — only check every 5 ticks to save CPU
        if (ticksExisted % 5 == 0) {
            t = System.nanoTime();
            updateSettledState();
            PHASE_STATS.updateSettledStateNanos += System.nanoTime() - t;
        }

        // Phantom-cache check — if this active ragdoll has stopped moving vertically
        // but isn't actually on real ground, it's wedged on cached static geometry
        // for blocks that no longer exist (the cache wasn't invalidated by an event,
        // and since the ragdoll isn't moving its center didn't change so updateLocal-
        // WorldCollision returned early). Force a cache rebuild so gravity can win.
        // Every 10 ticks to limit cost; only triggers when the ragdoll is genuinely
        // stuck (small vy + no real support). Skip when floating in fluid — buoyancy is
        // the legitimate reason there's no ground contact, no phantom cache to fix.
        if (ticksExisted % 10 == 0
                && collisionGeometryReadyForContacts()
                && !collectTerrainGroundContacts()
                && hasLowVerticalSpeed()
                && !isAnyPartInLiquid()) {
            physicsWorld.invalidateCollisionGeometry(currentCollisionGeometry);
            releaseCurrentCollisionGeometry();

            for (RigidBody r : ragdollParts) {
                r.forceActivationState(CollisionObject.DISABLE_DEACTIVATION);
                r.activate(true);

                r.getLinearVelocity(scratchVel);
                if (scratchVel.y > -1.0f) {
                    scratchVel.y = -1.0f;
                    r.setLinearVelocity(scratchVel);
                }
            }

            PHASE_STATS.phantomCacheClearedThisTick++;
        }
    }

    /** True if this ragdoll is actively being simulated (not destroyed, settled, or frozen). */
    public boolean isActivelySimulating() {
        return !destroyed && !settled && !bodiesFrozen;
    }

    // Returns false while the ragdoll has no confirmed ground or surface support.
    public boolean forceSettle() {
        if (destroyed || settled) return false;
        boolean onGround = collisionGeometryReadyForContacts() && collectTerrainGroundContacts();
        boolean atSurface = isFloatingAtSurface();
        if (!atSurface && isSubmergedBelowSurface()) return false;
        if (!onGround && !atSurface) return false;
        settleAtCurrentSupport(atSurface);
        return true;
    }

    /**
     * Wake from settled state because the support floor is gone. Drops the cached
     * collision geometry so the next physics tick rebuilds without the missing block,
     * AND invalidates nearby cache entries so other ragdolls in the same area also
     * see fresh geometry. Called from the periodic floor check in tick().
     */
    private void unsettleAndDropCache() {
        settled = false;
        pendingTerrainValidation = false;
        settledOnLiquid = false;
        settledTicks = 0;
        hasSettledTerrainSignature = false;
        markSettledPoseDirty();
        if (bodiesFrozen) unfreezeBodies();
        // Nuke the cache near our torso — covers the case where the broken block's
        // event never fired on the client (server-initiated change, etc.).
        BlockPos torsoBlock = new BlockPos(
                (int) Math.floor(cachedTorsoPos.x),
                (int) Math.floor(cachedTorsoPos.y),
                (int) Math.floor(cachedTorsoPos.z));
        physicsWorld.invalidateCacheRegion(torsoBlock, COLLISION_RADIUS);
        releaseCurrentCollisionGeometry();
    }

    private void freezeBodies() {
        bodiesFrozen = true;
        if (settled) {
            settledTerrainCenter = new BlockPos(
                    (int) Math.floor(cachedTorsoPos.x),
                    (int) Math.floor(cachedTorsoPos.y),
                    (int) Math.floor(cachedTorsoPos.z));
            if (currentCollisionGeometry != null
                    && currentCollisionGeometry.isValid()
                    && settledTerrainCenter.equals(lastCollisionCenter)) {
                settledTerrainSignature = currentCollisionGeometry.terrainSignature();
            } else {
                settledTerrainSignature = computeTerrainSignature(settledTerrainCenter);
            }
            hasSettledTerrainSignature = true;
        } else {
            hasSettledTerrainSignature = false;
        }
        releaseCurrentCollisionGeometry();
        // Zero velocities so the bodies start clean when re-added.
        for (RigidBody r : ragdollParts) {
            r.setLinearVelocity(new Vector3f(0, 0, 0));
            r.setAngularVelocity(new Vector3f(0, 0, 0));
        }
        // Completely remove from the dynamics world — this is the only way to make them
        // truly invisible to the broadphase and solver. DISABLE_SIMULATION still leaves
        // bodies in the world as unresponsive obstacles; an active body hitting one gets
        // all the impulse and gets flung. Removing them entirely avoids that entirely.
        for (TypedConstraint c : ragdollJoints) world.removeConstraint(c);
        for (RigidBody r : ragdollParts) world.removeRigidBody(r);
    }

    private void unfreezeBodies() {
        bodiesFrozen = false;
        if (!settled) {
            hasSettledTerrainSignature = false;
            settledGroundSupportBlocks.clear();
        }
        // Re-add bodies before constraints (constraint solver expects live bodies).
        for (RigidBody r : ragdollParts) {
            world.addRigidBody(r);
            // DISABLE_DEACTIVATION = body never auto-sleeps. Critical here: after a wake
            // from floor-loss, bodies have zero velocity. Without this, Bullet would
            // deactivate them ~2s later, gravity stops, and the ragdoll floats mid-air.
            r.forceActivationState(CollisionObject.DISABLE_DEACTIVATION);
            r.activate(true);
        }
        for (TypedConstraint c : ragdollJoints) world.addConstraint(c, true);
    }

    private void updateSettledState() {
        boolean onGround = collisionGeometryReadyForContacts() && collectTerrainGroundContacts();
        boolean atSurface = isFloatingAtSurface();
        if (!atSurface && isSubmergedBelowSurface()) {
            settledTicks = 0;
            return;
        }
        boolean canSettle = onGround || atSurface;

        // Buoyancy + gravity at the half-submerged equilibrium leaves a ~0.07 m/s residual
        // in vy each tick (gravity adds 0.49, buoyancy + drag cancels most of it). The
        // ground threshold (0.05) is too tight for that, so water-floating ragdolls would
        // never reach the velocity-settle path and would bob around chewing solver time.
        // 0.4 captures the steady-state oscillation amplitude while still rejecting bodies
        // that are actually moving (a kicked ragdoll keeps vel > 1 m/s for >10 ticks).
        float vThreshold = atSurface ? 0.4f : SETTLED_VELOCITY_THRESHOLD;
        float aThreshold = atSurface ? 0.6f : SETTLED_ANG_VELOCITY_THRESHOLD;

        boolean allSlow = true;
        for (RigidBody r : ragdollParts) {
            r.getLinearVelocity(scratchVel);
            r.getAngularVelocity(scratchAng);
            if (scratchVel.length() > vThreshold || scratchAng.length() > aThreshold) {
                allSlow = false;
                break;
            }
        }

        if (allSlow && canSettle) {
            settledTicks++;

            if (settledTicks >= 2) {
                settleAtCurrentSupport(atSurface);
                return;
            }
        } else {
            settledTicks = 0;
        }

        // Displacement-based fallback: jittering pile-bound ragdolls never reach the
        // velocity-based settle. If the torso hasn't moved meaningfully in the last
        // SETTLE_DISPLACEMENT_INTERVAL ticks, settle anyway. The velocity gate may still
        // be hot but the ragdoll isn't actually going anywhere — solver work is wasted.
        if (settleAnchorTick < 0) {
            settleAnchorPos.set(cachedTorsoPos);
            settleAnchorTick = ticksExisted;
        } else if (ticksExisted - settleAnchorTick >= SETTLE_DISPLACEMENT_INTERVAL) {
            float dx = cachedTorsoPos.x - settleAnchorPos.x;
            float dy = cachedTorsoPos.y - settleAnchorPos.y;
            float dz = cachedTorsoPos.z - settleAnchorPos.z;
            if (dx * dx + dy * dy + dz * dz < SETTLE_DISPLACEMENT_THRESHOLD_SQ && canSettle) {
                settleAtCurrentSupport(atSurface);
                return;
            }
            settleAnchorPos.set(cachedTorsoPos);
            settleAnchorTick = ticksExisted;
        }
    }

    private void settleAtCurrentSupport(boolean atSurface) {
        settled = true;
        pendingTerrainValidation = false;
        settledOnLiquid = atSurface;
        settledGroundSupportBlocks.clear();
        if (!atSurface) settledGroundSupportBlocks.addAll(activeGroundSupportBlocks);
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
            return false;
        }
        return collectWorldTerrainSupport();
    }

    private boolean collectWorldTerrainSupport() {
        activeGroundSupportBlocks.clear();
        for (RigidBody body : ragdollParts) {
            body.getWorldTransform(tempTransform);
            body.getCollisionShape().getAabb(tempTransform, supportAabbMin, supportAabbMax);

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
            BoxShape boxShape = body.getCollisionShape() instanceof BoxShape box ? box : null;
            if (boxShape != null) boxShape.getHalfExtentsWithMargin(supportHalfExtents);

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
                            boolean intersects = boxShape != null
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
        for (RigidBody body : ragdollParts) {
            body.getWorldTransform(tempTransform);
            body.getCollisionShape().getAabb(tempTransform, supportAabbMin, supportAabbMax);
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
            Transform transform, Vector3f halfExtents,
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

    private boolean collectTerrainGroundContacts() {
        activeGroundSupportBlocks.clear();
        for (PersistentManifold manifold : world.getDispatcher().getInternalManifoldPointer()) {
            RigidBody bodyA = (RigidBody) manifold.getBody0();
            RigidBody bodyB = (RigidBody) manifold.getBody1();
            boolean aIsThis = ragdollPartsSet.contains(bodyA);
            boolean bIsThis = ragdollPartsSet.contains(bodyB);
            if (aIsThis == bIsThis) continue;

            RigidBody terrain = aIsThis ? bodyB : bodyA;
            if (terrain.getInvMass() != 0f || !(terrain.getUserPointer() instanceof BlockPos supportPos)) {
                continue;
            }

            for (int i = 0; i < manifold.getNumContacts(); i++) {
                ManifoldPoint point = manifold.getContactPoint(i);
                if (point.getDistance() > TERRAIN_CONTACT_DISTANCE) continue;
                float supportNormalY = aIsThis
                        ? point.normalWorldOnB.y
                        : -point.normalWorldOnB.y;
                if (supportNormalY > GROUND_NORMAL_MIN_Y) {
                    activeGroundSupportBlocks.add(supportPos);
                    break;
                }
            }
        }
        return !activeGroundSupportBlocks.isEmpty();
    }

    private boolean collisionGeometryReadyForContacts() {
        return currentCollisionGeometry != null
                && currentCollisionGeometry.isValid()
                && physicsWorld.getTickCount() > collisionGeometryAcquiredTick;
    }

    private boolean hasLowVerticalSpeed() {
        for (RigidBody body : ragdollParts) {
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
            if (dx <= COLLISION_RADIUS && dy <= COLLISION_RADIUS && dz <= COLLISION_RADIUS) {
                releaseCurrentCollisionGeometry();
            }
        }

        // Wake up if settled / distance-frozen and torso is near
        if (settled || bodiesFrozen) {
            double dx = changedPos.getX() + 0.5 - cachedTorsoPos.x;
            double dy = changedPos.getY() + 0.5 - cachedTorsoPos.y;
            double dz = changedPos.getZ() + 0.5 - cachedTorsoPos.z;
            if (dx*dx + dy*dy + dz*dz < (COLLISION_RADIUS+1)*(COLLISION_RADIUS+1)) {
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

    /** @deprecated use {@link #onBlockChangedNear} which also invalidates stale cache. */
    @Deprecated
    public void wakeIfNear(BlockPos changedPos) {
        onBlockChangedNear(changedPos);
    }

    /**
     * Wake this ragdoll if an active ragdoll's torso is within 2 blocks.
     * Settled bodies use DISABLE_SIMULATION so active ragdolls would otherwise fall through
     * them. The wake loop now runs BEFORE the physics step, so waking here ensures the
     * settled bodies are back in the broadphase before contacts are resolved.
     * 2-block radius (vs old 3) still stops cascade activation in dense piles while giving
     * enough margin to account for up to ~0.5 blocks of movement per tick.
     */
    /** Returns true if this ragdoll was woken, false if it was already active or out of range. */
    public boolean wakeIfNearRagdoll(Vector3f otherTorsoPos) {
        if (!settled) return false; // distance-frozen bodies are too far to matter
        // Water-settled corpses don't need cascade-wake. Bodies are removed from the world
        // anyway (frozen), so other active ragdolls pass through them visually instead of
        // colliding. A pile of corpses on a pond was previously bouncing each other awake
        // every few ticks — solver was running at full cost for ~25 active bodies just to
        // resolve floating piles. Block changes still wake them via onBlockChangedNear.
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

    // ============================
    // World collision — mirrors MobRagdollPhysics.updateLocalWorldCollision() exactly
    // ============================

    private void updateLocalWorldCollision() {
        Vector3f torsoPos = cachedTorsoPos;
        BlockPos center = new BlockPos(
                (int) Math.floor(torsoPos.x),
                (int) Math.floor(torsoPos.y),
                (int) Math.floor(torsoPos.z));

        if (currentCollisionGeometry != null && !currentCollisionGeometry.isValid()) {
            releaseCurrentCollisionGeometry();
        }

        if (center.equals(lastCollisionCenter) && currentCollisionGeometry != null) {
            if (Math.floorMod(ticksExisted + id, 20) != 0) return;
            long currentSignature = computeTerrainSignature(center);
            if (currentSignature == currentCollisionGeometry.terrainSignature()) return;
            physicsWorld.invalidateCollisionGeometry(currentCollisionGeometry);
            releaseCurrentCollisionGeometry();
        }

        // Try to acquire new geometry first. getOrCreateCollisionGeometry returns null when
        // the per-tick creation budget is exhausted — we keep the old geometry and retry
        // next tick rather than leaving the ragdoll without any floor collision.
        //
        // Per-AABB static bodies (one RigidBody per block face). DbvtBroadphase prunes
        // these efficiently — only AABBs that actually overlap a dynamic part generate
        // a narrowphase pair. A previous attempt to bundle these into one CompoundShape
        // per cache entry was reverted: jbullet's CompoundCollisionAlgorithm iterates
        // all children per pair (no internal BVH), turning broadphase savings into a
        // much larger narrowphase loss.
        ClientJbulletWorld.CollisionGeometryHandle newGeometry =
                physicsWorld.getOrCreateCollisionGeometry(
                        center, COLLISION_RADIUS, this::buildBlockCollisionGeometry);

        if (newGeometry == null) {
            // Rate-limited this tick — keep old geometry, lastCollisionCenter unchanged so
            // we retry next tick (center != lastCollisionCenter will be true again).
            return;
        }

        // Release old AFTER acquiring new, so the ragdoll always has valid floor coverage.
        if (currentCollisionGeometry != null) {
            physicsWorld.releaseCollisionGeometry(currentCollisionGeometry);
        }

        lastCollisionCenter = center;
        currentCollisionGeometry = newGeometry;
        collisionGeometryAcquiredTick = physicsWorld.getTickCount();

        // No wakeUpAndClearContacts() — clearing manifolds destroys floor contacts
        // and causes the settle-sink-bounce cycle. JBullet builds new contacts for
        // the new geometry within 1-2 substeps naturally.
    }

    private ClientJbulletWorld.BuiltBlockCollisionGeometry buildBlockCollisionGeometry(BlockPos pos) {
        List<RigidBody> bodies = new ArrayList<>();
        BlockState state = level.getBlockState(pos);
        int stateId = Block.getId(state);
        if (state.isAir()) {
            return new ClientJbulletWorld.BuiltBlockCollisionGeometry(bodies, stateId);
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty() || isCompletelySurrounded(pos, shape)) {
            return new ClientJbulletWorld.BuiltBlockCollisionGeometry(bodies, stateId);
        }

        for (AABB box : shape.toAabbs()) {
            Vector3f halfExtents = new Vector3f(
                    (float)(box.getXsize() / 2),
                    (float)(box.getYsize() / 2),
                    (float)(box.getZsize() / 2)
            );
            CollisionShape cs = new BoxShape(halfExtents);
            Transform transform = new Transform();
            transform.setIdentity();
            transform.origin.set(
                    (float)(pos.getX() + box.minX + box.getXsize() / 2),
                    (float)(pos.getY() + box.minY + box.getYsize() / 2),
                    (float)(pos.getZ() + box.minZ + box.getZsize() / 2)
            );
            RigidBody body = new RigidBody(new RigidBodyConstructionInfo(
                    0f, new DefaultMotionState(transform), cs, new Vector3f()));
            body.setCollisionFlags(body.getCollisionFlags() | CollisionFlags.STATIC_OBJECT);
            body.setFriction((float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION));
            body.setRestitution(0f);
            body.setUserPointer(pos.immutable());
            world.addRigidBody(body);
            bodies.add(body);
        }
        return new ClientJbulletWorld.BuiltBlockCollisionGeometry(bodies, stateId);
    }

    private long computeTerrainSignature(BlockPos center) {
        long signature = terrainSignatureSeed();
        for (int dx = -COLLISION_RADIUS; dx <= COLLISION_RADIUS; dx++) {
            for (int dy = -COLLISION_RADIUS; dy <= COLLISION_RADIUS; dy++) {
                for (int dz = -COLLISION_RADIUS; dz <= COLLISION_RADIUS; dz++) {
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

    // ============================
    // Physics body creation — unchanged from previous version
    // ============================

    private void createRagdollBodies(SpawnData data) {
        float xRotDeg = MobModelHelper.isHumanoidModelType(modelType) ? 0f : data.xRot;
        if (data.isSwimming) xRotDeg = 90;

        float spawnYOffset = isPlayer ? 1.2f : (modelType == MobModelHelper.ModelType.QUADRUPED ||
                modelType == MobModelHelper.ModelType.CHICKEN ? 0f : 1.2f);
        // Baby humanoids are built at half size, so their torso centre sits ~half as high —
        // spawn them lower or they'd drop in from an adult's chest height.
        if (isBabyHumanoid()) spawnYOffset = 0.6f;

        // Quadruped/chicken pos adjusted again below — keep consistent with factory call
        if (modelType == MobModelHelper.ModelType.QUADRUPED)
            spawnYOffset = 0.7f * data.scale;
        if (modelType == MobModelHelper.ModelType.CHICKEN)
            spawnYOffset = 0.4f * data.scale;
        // Bat/bee are small winged mobs on their own layouts (not quadruped/chicken), so the
        // 1.2 default above is far too high — drop them close to the ground.
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
        } else if (bodyProfile == RagdollBodyFactory.BodyProfile.CHICKEN) {
            spawnYOffset = isBabyChicken() ? 0.27f : 0.54f;
        }

        // Keep X/Z on the entity's exact death origin. The Y offset is not prediction or
        // random displacement: it converts the feet-level entity origin into this body's
        // authored torso/root location.
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
        initialVel.scale((float) RagdollifiedConfig.get(RagdollifiedConfig.INITIAL_VELOCITY_SCALE));

        RagdollBodyFactory.build(world, ragdollParts, ragdollJoints,
                modelType, pos, baseQuat, scale, initialVel, data.capturedPose, bodyProfile,
                isBaby(), isBaby() && babyScalesHead());
        for (RigidBody r : ragdollParts) {
            r.forceActivationState(CollisionObject.DISABLE_DEACTIVATION);
            r.activate(true);
        }
    }

    // ============================
    // Forces — mirrors MobRagdollPhysics exactly
    // ============================

    /**
     * Per-part buoyancy + drag. For each part inside a fluid block, computes how far below
     * the local fluid surface the part center sits and applies an upward velocity change
     * proportional to that submersion. Equilibrium sits at half-submerged: at that depth
     * buoyancy (2g·0.5 = g) exactly cancels gravity (-g). Below that the part rises; above
     * it falls back. Net effect: parts hover at the surface with the heaviest part (torso)
     * resting roughly at the waterline — a settled ragdoll then qualifies for water-surface
     * settle and gets frozen so we stop simulating it.
     *
     * Heavy linear/angular drag (0.7 retention/tick = 30% loss) drains the buoyancy
     * oscillation inside ~0.5s so the ragdoll converges quickly.
     */
    private void applyFluidForces() {
        final float dt = 1f / 20f;
        final float gravity = (float) RagdollifiedConfig.get(RagdollifiedConfig.GRAVITY);

        for (int i = 0; i < ragdollParts.size() && i < 6; i++) {
            if (cachedTransforms[i] == null) continue;
            RigidBody body = ragdollParts.get(i);
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
            // downstream. y-flow ignored — vertical motion is fully owned by buoyancy.
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
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 playerPos = mc.player.position();
        Vec3 playerVel = mc.player.getDeltaMovement();
        if (playerVel.lengthSqr() < 0.01) return;

        float playerSpeed = (float) playerVel.length();
        for (RigidBody part : ragdollParts) {
            part.getMotionState().getWorldTransform(tempTransform);
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

    // ============================
    // Public API
    // ============================

    public void applyImpulse(RagdollPart part, Vector3f impulse) {
        if (part == null || part.index >= ragdollParts.size()) return;
        if (settled || bodiesFrozen) {
            settled = false;
            pendingTerrainValidation = false;
            settledOnLiquid = false;
            settledTicks = 0;
            markSettledPoseDirty();
            unfreezeBodies();
        }
        RigidBody body = ragdollParts.get(part.index);
        body.activate(true);
        Vector3f scaled = new Vector3f(impulse);
        scaled.scale(RagdollifiedConfig.getPartKnockbackMultiplier(part));
        body.applyCentralImpulse(scaled);
        // Any previously sent settle pose predates this push and must be reported again after
        // the body comes to rest. The server also rejects reports with an older revision.
        markSettledPoseDirty();
    }

    /**
     * Apply a server-retained state on the physics thread. Settled transforms are absolute
     * world coordinates, allowing a player who enters later to see the already-resting pose.
     */
    public void applyAuthoritativeState(RagdollTransform[] transforms, int ageTicks, boolean settledPose) {
        ticksExisted = Math.max(ticksExisted, Math.max(0, ageTicks));
        if (!settledPose || transforms == null) return;

        if (bodiesFrozen) unfreezeBodies();
        for (int i = 0; i < ragdollParts.size() && i < transforms.length; i++) {
            RagdollTransform authoritative = transforms[i];
            if (authoritative == null || authoritative.partId != i) continue;

            Transform worldTransform = new Transform();
            worldTransform.setIdentity();
            worldTransform.origin.set(authoritative.position);
            worldTransform.setRotation(authoritative.rotation);

            RigidBody body = ragdollParts.get(i);
            body.setWorldTransform(worldTransform);
            if (body.getMotionState() != null) {
                body.getMotionState().setWorldTransform(worldTransform);
            }
            body.setLinearVelocity(new Vector3f());
            body.setAngularVelocity(new Vector3f());
        }

        updateCachedTransforms();
        updateLocalWorldCollision();

        BlockPos torsoBlock = currentTorsoBlock();
        if (!isSupportAreaLoaded()) {
            physicsWorld.cacheStats.poseDeferred++;
            settled = true;
            settledOnLiquid = false;
            settledTicks = 2;
            pendingTerrainValidation = true;
            settledGroundSupportBlocks.clear();
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
            for (RigidBody body : ragdollParts) {
                body.forceActivationState(CollisionObject.DISABLE_DEACTIVATION);
                body.activate(true);
            }
            return;
        }

        settledTicks = 2;
        settleAtCurrentSupport(atSurface);
        settledPoseReported = true;
    }

    private int lastImpulseRevision = 0;
    public int getLastImpulseRevision() { return lastImpulseRevision; }
    public void acknowledgeImpulseRevision(int revision) {
        if (revision > lastImpulseRevision) lastImpulseRevision = revision;
    }

    private void applyCenteredDeathImpulse(Vec3 impulse) {
        float centerScale = Math.max(0.75f,
                (float) RagdollifiedConfig.get(RagdollifiedConfig.HIT_CENTER_DISTRIBUTION_SCALE));
        for (int i = 0; i < ragdollParts.size() && i < 6; i++) {
            RagdollPart part = RagdollPart.byIndex(i);
            if (part == null) continue;
            RigidBody body = ragdollParts.get(i);
            body.activate(true);
            body.applyCentralImpulse(scaledImpulse(impulse,
                    centerScale * RagdollifiedConfig.getDeathPartKnockbackMultiplier(part)));
        }
    }

    private void applyGlobalVelocityKick(Vec3 velocityKick) {
        Vector3f currentVelocity = new Vector3f();
        Vector3f kick = new Vector3f(
                (float) velocityKick.x,
                (float) velocityKick.y,
                (float) velocityKick.z);
        for (RigidBody body : ragdollParts) {
            body.getLinearVelocity(currentVelocity);
            currentVelocity.add(kick);
            body.setLinearVelocity(currentVelocity);
            body.activate(true);
        }
    }

    private static Vector3f scaledImpulse(Vec3 impulse, float scale) {
        return new Vector3f(
                (float) impulse.x * scale,
                (float) impulse.y * scale,
                (float) impulse.z * scale);
    }

    /**
     * Manual ray test against this ragdoll's cached part positions.
     * Used to detect clicks on settled ragdolls whose bodies are not in the physics world.
     * Returns the closest hit RagdollPart within reach, or null if nothing is close enough.
     */
    public RagdollPart findHitPart(Vector3f from, Vector3f to) {
        // Read from the published snapshot — runs on render thread (click handler),
        // so we can't touch the physics-thread-owned cachedTransforms directly.
        TransformSnapshot snap = publishedSnapshot;
        if (snap == null || snap.destroyed) return null;

        Vector3f rayDir = new Vector3f(to.x - from.x, to.y - from.y, to.z - from.z);
        float rayLen = rayDir.length();
        if (rayLen < 0.001f) return null;
        rayDir.scale(1f / rayLen);

        RagdollPart bestPart = null;
        float bestDist = 0.5f; // max distance from ray to part center (blocks)

        for (int i = 0; i < snap.positions.length && i < 6; i++) {
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
        for (int i = 0; i < ragdollParts.size() && i < 6; i++) {
            ragdollParts.get(i).getMotionState().getWorldTransform(tempTransform);

            if (cachedTransforms[i] != null) {
                // Save current as previous before overwriting (in place — no alloc).
                prevPositions[i].set(cachedTransforms[i].position);
                prevRotations[i].set(cachedTransforms[i].rotation);
                cachedTransforms[i].position.set(tempTransform.origin);
                tempTransform.getRotation(cachedTransforms[i].rotation);
            } else {
                // First call — allocate prev/current pair. After this we mutate in place.
                Vector3f pos = new Vector3f(tempTransform.origin);
                Quat4f rot = tempTransform.getRotation(new Quat4f());
                prevPositions[i] = new Vector3f(pos);
                prevRotations[i] = new Quat4f(rot);
                cachedTransforms[i] = new RagdollTransform(i, pos, rot);
            }
        }
        if (!ragdollParts.isEmpty()) {
            ragdollParts.get(0).getMotionState().getWorldTransform(tempTransform);
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

    /**
     * Build an immutable copy of the current cached transforms and publish it for
     * the render thread to consume. Allocates fresh Vector3f/Quat4f instances so
     * the render thread never sees a vector that's about to be mutated by physics.
     */
    private void publishSnapshot() {
        int count = Math.min(ragdollParts.size(), 6);
        Vector3f[] pos = new Vector3f[count];
        Quat4f[] rot = new Quat4f[count];
        Vector3f[] halfExtents = new Vector3f[count];
        Vector3f[] prev = new Vector3f[count];
        Quat4f[] prevRot = new Quat4f[count];
        for (int i = 0; i < count; i++) {
            if (cachedTransforms[i] == null) continue;
            pos[i] = new Vector3f(cachedTransforms[i].position);
            rot[i] = new Quat4f(cachedTransforms[i].rotation);
            if (ragdollParts.get(i).getCollisionShape() instanceof BoxShape box) {
                halfExtents[i] = new Vector3f(box.getHalfExtentsWithoutMargin(new Vector3f()));
            }
            if (prevPositions[i] != null) {
                prev[i] = new Vector3f(prevPositions[i]);
                prevRot[i] = new Quat4f(prevRotations[i]);
            }
        }
        publishedSnapshot = new TransformSnapshot(
                pos, rot, halfExtents, prev, prevRot,
                new Vector3f(cachedTorsoPos),
                new Vector3f(prevTorsoPos),
                hasPrevTransforms,
                destroyed
        );
    }

    /**
     * Returns a transform interpolated between the previous and current physics tick,
     * using partialTick (0.0 = previous tick, 1.0 = current tick) for smooth rendering
     * at frame rates higher than 20 Hz.
     */
    public RagdollTransform getInterpolatedTransform(RagdollPart part, float partialTick) {
        if (part.index >= ragdollParts.size() || part.index >= 6) return null;
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
        // Publish a destroyed-flagged snapshot immediately so the render thread skips
        // this ragdoll on its next read instead of trying to render the about-to-be-
        // cleared bodies/transforms.
        TransformSnapshot prev = publishedSnapshot;
        if (prev != null) {
            publishedSnapshot = new TransformSnapshot(
                    prev.positions, prev.rotations, prev.halfExtents, prev.prevPositions, prev.prevRotations,
                    prev.cachedTorsoPos, prev.prevTorsoPos, prev.hasPrev, true);
        }

        releaseCurrentCollisionGeometry();

        // Bodies were already removed from the world in freezeBodies() — don't double-remove.
        if (!bodiesFrozen) {
            for (TypedConstraint c : ragdollJoints) world.removeConstraint(c);
            for (RigidBody r : ragdollParts) world.removeRigidBody(r);
        }
        if (!isPlayer) {
            ClientMobTextureCache.evict(originalEntityId);
        }
        // Queue this ragdoll's rebuilt blood textures for release on the render thread.
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.evict(originalEntityId);

        ragdollParts.clear();
        ragdollJoints.clear();
    }

    public boolean hasBody(CollisionObject obj) { return ragdollPartsSet.contains(obj); }

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
        for (RigidBody body : ragdollParts) {
            body.translate(groupPenetrationCorrection);
            body.getWorldTransform(tempTransform);
            if (body.getMotionState() != null) {
                body.getMotionState().setWorldTransform(tempTransform);
            }
            body.getLinearVelocity(scratchVel);
            scratchVel.scale(damping);
            body.setLinearVelocity(scratchVel);
            body.getAngularVelocity(scratchAng);
            scratchAng.scale(damping);
            body.setAngularVelocity(scratchAng);
        }
        groupPenetrationCorrection.set(0f, 0f, 0f);
    }

    public RagdollTransform getTransform(RagdollPart part) {
        if (part.index >= ragdollParts.size() || part.index >= 6) return null;
        return cachedTransforms[part.index];
    }
    public RagdollTransform[] getAllTransforms() { return cachedTransforms; }
    public Vector3f getTorsoPosition() { return cachedTorsoPos; }
    public int getId() { return id; }
    public boolean isDestroyed() { return destroyed; }
    public boolean isSettled() { return settled; }
    public boolean isSettledOnLiquid() { return settledOnLiquid; }
    public boolean isFrozen() { return bodiesFrozen; }

    /** True if any body is moving fast enough to be a valid cascade-wake source (> 0.3 m/s). */
    public boolean isMovingSignificantly() {
        for (RigidBody r : ragdollParts) {
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
    public boolean isBaby() { return isBaby; }
    public boolean isBabyCow() {
        return isBaby && (mobType.contains("cow") || mobType.contains("mooshroom"));
    }
    public boolean isBabyPig() { return isBaby && mobType.contains("pig"); }
    public boolean isBabySheep() { return isBaby && mobType.contains("sheep"); }
    public boolean isBabyChicken() { return isBaby && mobType.contains("chicken"); }
    public boolean isBabyCat() { return isBaby && (mobType.contains("cat") || mobType.contains("ocelot")); }
    public boolean isBabyBee() { return isBaby && mobType.contains("bee"); }
    // Baby humanoid (baby zombie/husk/piglin/zombie-villager, …) — scaled-down body + model.
    public boolean isBabyHumanoid() {
        return isBaby && MobModelHelper.isHumanoidModelType(modelType);
    }
    // Whether this mob's vanilla model enlarges the baby head (vs. a uniform shrink). Mirrors
    // vanilla per-mob behaviour: zombies/husks/piglins/drowned and zombie villagers use
    // HumanoidModel with scaleHead=true (big head); plain villagers and wandering traders use
    // VillagerModel and are scaled uniformly by VillagerRenderer#scale. Only meaningful for a
    // baby humanoid.
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
        return isBabyCow() || isBabyPig() || isBabySheep() || isBabyChicken() || isBabyCat();
    }
    public RagdollBodyFactory.BodyProfile getBodyProfile() {
        if (mobType.contains("cow") || mobType.contains("mooshroom")) return RagdollBodyFactory.BodyProfile.COW;
        if (mobType.contains("pig")) return RagdollBodyFactory.BodyProfile.PIG;
        if (mobType.contains("sheep")) return RagdollBodyFactory.BodyProfile.SHEEP;
        if (mobType.contains("cat") || mobType.contains("ocelot")) return RagdollBodyFactory.BodyProfile.CAT;
        if (mobType.contains("chicken")) return RagdollBodyFactory.BodyProfile.CHICKEN;
        return RagdollBodyFactory.BodyProfile.DEFAULT;
    }
    public String getVillagerType() { return villagerType; }
    public String getVillagerProfession() { return villagerProfession; }
    public int getVillagerLevel() { return villagerLevel; }
    public int getOriginalEntityId() { return originalEntityId; }
    public ClientLevel getLevel() { return level; }
    public ResourceLocation getCachedPlayerSkin() { return cachedPlayerSkin; }
    public boolean isCachedSlim() { return cachedIsSlim; }

    // Corpse feature — per-client one-shot guard so each observer reports this ragdoll's
    // settle at most once. The server de-duplicates reports from multiple observers.
    private boolean corpseSettleReported = false;
    public boolean isCorpseSettleReported() { return corpseSettleReported; }
    public void markCorpseSettleReported() { corpseSettleReported = true; }

    // Generic server-retained pose report used for late area entrants.
    private boolean settledPoseReported = false;
    public boolean isSettledPoseReported() { return settledPoseReported; }
    public void markSettledPoseReported() { settledPoseReported = true; }

    private void markSettledPoseDirty() {
        corpseSettleReported = false;
        settledPoseReported = false;
    }

    // ============================
    // Math helpers (kept locally for interpolation — body/joint creation delegated to RagdollBodyFactory)
    // ============================

}
