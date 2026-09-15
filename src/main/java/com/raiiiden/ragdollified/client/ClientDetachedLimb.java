package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.physics.BodyProperties;
import com.raiiiden.ragdollified.physics.PhysTransform;
import com.raiiiden.ragdollified.physics.PhysicsBody;
import com.raiiiden.ragdollified.physics.PhysicsShape;
import com.raiiiden.ragdollified.physics.PhysicsWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.UUID;

// One severed limb: a single box that falls and settles, without the ragdoll machinery.
// Not pose-streamed; physics thread only except getSnapshot() and the render accessors.
@OnlyIn(Dist.CLIENT)
public final class ClientDetachedLimb {

    // Radius, in blocks, of the terrain shell kept around a limb. A ragdoll takes three; a limb is a
    // fifth the size and never outruns its floor, so one shell of blocks is enough to land on.
    private static final int COLLISION_RADIUS = 1;
    // Ticks below the motion threshold before the limb is parked. Parking rather than freezing keeps
    // it as an obstacle, so a body that lands on a pile of arms does not sink through them.
    private static final int SETTLE_TICKS = 12;
    private static final float SETTLE_SPEED_SQ = 0.02f;
    private static final float MAX_SPEED = 40f;
    // Matches ClientRagdoll.applyPlayerCollisions, so walking through a body and walking through the
    // arm lying next to it feel like the same thing.
    private static final float PLAYER_PUSH_RADIUS = 1.5f;
    // A box is only tall enough to stand on its end if its long axis is meaningfully longer than its
    // base. Arms and legs are; a head is very nearly a cube and is left alone.
    private static final float TOPPLE_MIN_ASPECT = 1.4f;
    // How near vertical the long axis has to be to count as standing: cos(60 degrees), so anything
    // resting more than 30 degrees off flat. A limb can only rest that steep propped on something -
    // its own end, the body it came off, a block edge - and parked there it stays propped in mid-air
    // once that body is moved.
    private static final float TOPPLE_UPRIGHT_DOT = 0.5f;
    private static final float TOPPLE_SPIN = 3.5f;
    private static final float TOPPLE_LIFT = 0.1f;
    // A limb wedged upright in a one-block hole has nowhere to fall. Past this it is left standing
    // rather than nudged for the rest of its life.
    private static final int MAX_TOPPLE_NUDGES = 4;

    // Everything needed to build a limb, gathered on whichever side took it off.
    public static final class SpawnData {
        public final int limbId;
        public final int sourceEntityId;
        public final RagdollPart part;
        public final MobModelHelper.ModelType modelType;
        public final String mobType;
        public final boolean isPlayer;
        public final boolean isBaby;
        public final float scale;
        @Nullable public final UUID playerUUID;
        public final Vec3 position;
        public final Quat4f rotation;
        public final Vec3 linearVelocity;
        public final Vec3 angularVelocity;
        public final Vector3f halfExtents;
        public final int lifetimeTicks;
        @Nullable public ResourceLocation texture;

        public SpawnData(int limbId, int sourceEntityId, RagdollPart part,
                         MobModelHelper.ModelType modelType, String mobType,
                         boolean isPlayer, boolean isBaby, float scale, @Nullable UUID playerUUID,
                         Vec3 position, Quat4f rotation, Vec3 linearVelocity, Vec3 angularVelocity,
                         Vector3f halfExtents, int lifetimeTicks) {
            this.limbId = limbId;
            this.sourceEntityId = sourceEntityId;
            this.part = part;
            this.modelType = modelType;
            this.mobType = mobType != null ? mobType : "";
            this.isPlayer = isPlayer;
            this.isBaby = isBaby;
            this.scale = scale;
            this.playerUUID = playerUUID;
            this.position = position;
            this.rotation = rotation != null ? rotation : identity();
            this.linearVelocity = linearVelocity != null ? linearVelocity : Vec3.ZERO;
            this.angularVelocity = angularVelocity != null ? angularVelocity : Vec3.ZERO;
            this.halfExtents = halfExtents;
            this.lifetimeTicks = lifetimeTicks;
        }

        private static Quat4f identity() {
            Quat4f q = new Quat4f();
            q.set(0f, 0f, 0f, 1f);
            return q;
        }
    }

    // Immutable per-tick state for the render thread, same contract as a ragdoll's.
    public static final class Snapshot implements RenderPlayback.Segment {
        public final Vector3f position;
        public final Quat4f rotation;
        public final Vector3f prevPosition;
        public final Quat4f prevRotation;
        public final boolean hasPrev;
        public final boolean destroyed;
        // As ClientRagdoll.TransformSnapshot: how many client ticks of motion this carries.
        public final int spanTicks;

        @Override
        public int spanTicks() {
            return spanTicks;
        }

        Snapshot(Vector3f position, Quat4f rotation, Vector3f prevPosition, Quat4f prevRotation,
                 boolean hasPrev, boolean destroyed, int spanTicks) {
            this.spanTicks = spanTicks;
            this.position = position;
            this.rotation = rotation;
            this.prevPosition = prevPosition;
            this.prevRotation = prevRotation;
            this.hasPrev = hasPrev;
            this.destroyed = destroyed;
        }

        public RagdollTransform interpolated(float partialTick) {
            if (!hasPrev) return new RagdollTransform(0, position, rotation);
            float t = Math.max(0f, Math.min(1f, partialTick));
            Vector3f pos = new Vector3f(
                    prevPosition.x + (position.x - prevPosition.x) * t,
                    prevPosition.y + (position.y - prevPosition.y) * t,
                    prevPosition.z + (position.z - prevPosition.z) * t);
            return new RagdollTransform(0, pos, slerp(prevRotation, rotation, t));
        }
    }

    private final int limbId;
    private final int sourceEntityId;
    private final RagdollPart part;
    private final MobModelHelper.ModelType modelType;
    private final String mobType;
    private final boolean isPlayer;
    private final boolean isBaby;
    private final float scale;
    @Nullable private final UUID playerUUID;
    @Nullable private ResourceLocation texture;
    @Nullable private ResourceLocation playerSkin;
    private boolean playerSkinSlim;

    private final ClientPhysicsWorld physicsWorld;
    private final PhysicsWorld world;
    private final ClientLevel level;
    private final PhysicsBody body;
    private final PhysicsShape shape;
    private final Vector3f halfExtents;
    private final float mass;

    private final int lifetime;
    private int ticksExisted;
    private int lowMotionTicks;
    private int toppleNudges;
    private boolean parked;
    private boolean destroyed;

    private BlockPos lastCollisionCenter;
    @Nullable private ClientPhysicsWorld.CollisionGeometryHandle collisionGeometry;

    private final PhysTransform scratchTransform = new PhysTransform();
    private final Vector3f scratchVec = new Vector3f();
    private final Vector3f currentPos = new Vector3f();
    private final Quat4f currentRot = new Quat4f();
    private final Vector3f prevPos = new Vector3f();
    private final Quat4f prevRot = new Quat4f();
    private boolean hasPrev;

    private volatile Snapshot publishedSnapshot;
    // Render-side smoothing, mirroring what ragdolls do so a limb lying beside a body does not
    // shimmer while the body next to it is steady.
    private final Vector3f smoothPos = new Vector3f();
    private final Quat4f smoothRot = new Quat4f();
    private boolean smoothSeeded;
    private long smoothRenderFrame = Long.MIN_VALUE;

    public ClientDetachedLimb(SpawnData data, ClientPhysicsWorld physicsWorld) {
        this.limbId = data.limbId;
        this.sourceEntityId = data.sourceEntityId;
        this.part = data.part;
        this.modelType = data.modelType;
        this.mobType = data.mobType;
        this.isPlayer = data.isPlayer;
        this.isBaby = data.isBaby;
        this.scale = data.scale;
        this.playerUUID = data.playerUUID;
        this.texture = data.texture;
        this.physicsWorld = physicsWorld;
        this.world = physicsWorld.getPhysics();
        this.level = physicsWorld.getLevel();
        this.lifetime = Math.max(20, data.lifetimeTicks);

        Vector3f half = data.halfExtents != null ? data.halfExtents : defaultHalfExtents(part, scale);
        this.halfExtents = new Vector3f(half);
        this.shape = world.createBoxShape(
                Math.max(0.02f, half.x), Math.max(0.02f, half.y), Math.max(0.02f, half.z));

        Vector3f position = new Vector3f(
                (float) data.position.x, (float) data.position.y, (float) data.position.z);
        BodyProperties properties = limbProperties(part, half);
        this.mass = properties.mass;
        PhysicsBody created = world.createDynamicBody(shape, position, data.rotation, properties);
        this.body = created;
        if (created == null) {
            // A native backend out of body slots. Marked dead here rather than left half built, so
            // the manager drops it on its next pass instead of ticking a limb with no body.
            destroyed = true;
            publish();
            return;
        }
        world.addBody(created);
        created.setSleepingAllowed(false);
        created.setLinearVelocity(new Vector3f(
                (float) data.linearVelocity.x, (float) data.linearVelocity.y, (float) data.linearVelocity.z));
        created.setAngularVelocity(new Vector3f(
                (float) data.angularVelocity.x, (float) data.angularVelocity.y, (float) data.angularVelocity.z));
        // A limb is small and often leaves the body fast; without sweeping it, a thrown arm tunnels
        // through the floor it was thrown at.
        created.setCcdMotionThreshold(Math.min(half.x, Math.min(half.y, half.z)));
        created.setCcdSweptSphereRadius(Math.min(half.x, Math.min(half.y, half.z)) * 0.8f);
        created.activate();

        if (isPlayer && playerUUID != null) {
            ClientPlayerSkinCache.Skin resolved = ClientPlayerSkinCache.resolve(playerUUID);
            playerSkin = resolved.texture;
            playerSkinSlim = resolved.slim;
        }

        readTransform();
        prevPos.set(currentPos);
        prevRot.set(currentRot);
        hasPrev = true;
        updateCollisionGeometry();
        publish();
    }

    private static BodyProperties limbProperties(RagdollPart part, Vector3f half) {
        // Mass in the same units the rig uses: roughly a body's worth spread over its volume, so an
        // arm shoved by the same impulse that moves a torso travels about as far as one would.
        float volume = 8f * half.x * half.y * half.z;
        float mass = Math.max(0.4f, volume * 40f);
        BodyProperties properties = new BodyProperties();
        properties.mass = mass;
        properties.friction = (float) RagdollifiedConfig.get(RagdollifiedConfig.FRICTION);
        properties.restitution = 0.05f;
        properties.linearDamping = 0.06f;
        // Loose limbs otherwise spin for their whole life: nothing is braced against them the way a
        // shoulder joint used to be, and a box on a flat floor sheds almost no spin to friction.
        properties.angularDamping = 0.55f;
        return properties;
    }

    static Vector3f defaultHalfExtents(RagdollPart part, float scale) {
        org.joml.Vector3f base = part.getHalfExtents();
        float s = Math.max(0.1f, scale);
        return new Vector3f(base.x * s, base.y * s, base.z * s);
    }

    // Advance one physics tick. Physics thread. Returns false once the limb is finished with.
    public boolean tick() {
        if (destroyed || body == null) return false;
        ticksExisted++;
        if (ticksExisted > lifetime) return false;

        applyPlayerCollisions();

        if (!parked) {
            body.getLinearVelocity(scratchVec);
            float speedSq = scratchVec.lengthSquared();
            // A limb that somehow picks up an absurd velocity (a solver blow-up against terrain
            // built underneath it) is clamped rather than allowed to leave the world.
            if (speedSq > MAX_SPEED * MAX_SPEED) {
                scratchVec.normalize();
                scratchVec.scale(MAX_SPEED);
                body.setLinearVelocity(scratchVec);
                speedSq = MAX_SPEED * MAX_SPEED;
            }
            body.getAngularVelocity(scratchVec);
            boolean still = speedSq < SETTLE_SPEED_SQ && scratchVec.lengthSquared() < SETTLE_SPEED_SQ * 4f;
            lowMotionTicks = still ? lowMotionTicks + 1 : 0;
            if (lowMotionTicks >= SETTLE_TICKS) {
                // Coming to rest standing on end is what a box does and what a limb does not. Tip it
                // before parking, or it is frozen upright for the rest of its life.
                if (tryTopple()) {
                    lowMotionTicks = 0;
                    body.activate();
                    updateCollisionGeometry();
                } else {
                    body.setStatic(true);
                    parked = true;
                    releaseCollisionGeometry();
                }
            } else {
                body.activate();
                updateCollisionGeometry();
            }
        }

        readTransform();
        publish();
        return true;
    }

    // Tip a limb that settled standing on one end, with a little lift; skipped for cube-like parts.
    // Returns true if nudged, so the caller lets it settle again rather than parking it.
    private boolean tryTopple() {
        if (toppleNudges >= MAX_TOPPLE_NUDGES) return false;
        float base = Math.max(halfExtents.x, halfExtents.z);
        if (base <= 0f || halfExtents.y < base * TOPPLE_MIN_ASPECT) return false;

        // The long axis is the box's local Y, rotated into the world by the current orientation.
        float x = currentRot.x, y = currentRot.y, z = currentRot.z, w = currentRot.w;
        float upY = 1f - 2f * (x * x + z * z);
        if (Math.abs(upY) < TOPPLE_UPRIGHT_DOT) return false;

        // Tip it the way it already leans, so a limb propped against a body slides off it rather
        // than being levered into it. The spin axis is up x lean, which carries the top toward the
        // lean; the top is whichever end points up, which depends on which way up it landed.
        float sign = upY < 0f ? -1f : 1f;
        float leanX = 2f * (x * y - w * z) * sign;
        float leanZ = 2f * (y * z + w * x) * sign;
        float leanLength = (float) Math.sqrt(leanX * leanX + leanZ * leanZ);
        float ax, az, length;
        if (leanLength > 0.05f) {
            ax = leanZ;
            az = -leanX;
            length = leanLength;
        } else {
            // Stood dead upright, so no lean to follow: spin about the box's own local X, flattened
            // to horizontal, which is perpendicular to the long axis and lays the limb down.
            ax = 1f - 2f * (y * y + z * z);
            az = 2f * (x * z - w * y);
            length = (float) Math.sqrt(ax * ax + az * az);
            if (length < 1.0e-4f) {
                ax = 1f;
                az = 0f;
                length = 1f;
            }
        }
        ax /= length;
        az /= length;

        toppleNudges++;
        scratchVec.set(ax * TOPPLE_SPIN, 0f, az * TOPPLE_SPIN);
        body.setAngularVelocity(scratchVec);
        scratchVec.set(0f, TOPPLE_LIFT, 0f);
        body.setLinearVelocity(scratchVec);
        return true;
    }

    // Shove from a player walking into the limb, same falloff as ClientRagdoll; wakes parked limbs.
    // Physics thread, from inside tick().
    private void applyPlayerCollisions() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 playerVel = mc.player.getDeltaMovement();
        if (playerVel.lengthSqr() < 0.01) return;

        Vec3 playerPos = mc.player.position();
        float dx = (float) (playerPos.x - currentPos.x);
        float dy = (float) (playerPos.y - currentPos.y);
        float dz = (float) (playerPos.z - currentPos.z);
        float distSq = dx * dx + dy * dy + dz * dz;
        if (distSq >= PLAYER_PUSH_RADIUS * PLAYER_PUSH_RADIUS) return;

        float dist = Math.max(0.1f, (float) Math.sqrt(distSq));
        float playerSpeed = (float) playerVel.length()
                * RagdollifiedConfig.getModelSizeVelocityScale(scale);
        float pushStrength = playerSpeed * 15f * (PLAYER_PUSH_RADIUS - dist) / PLAYER_PUSH_RADIUS;
        if (pushStrength <= 1.0e-4f) return;

        float invDist = pushStrength / dist;
        applyImpulse(new Vector3f(-dx * invDist, -dy * invDist, -dz * invDist));
    }

    private void readTransform() {
        prevPos.set(currentPos);
        prevRot.set(currentRot);
        body.getWorldTransform(scratchTransform);
        currentPos.set(scratchTransform.origin);
        scratchTransform.getRotation(currentRot);
        if (!hasPrev) {
            prevPos.set(currentPos);
            prevRot.set(currentRot);
            hasPrev = true;
        }
    }

    private void publish() {
        int now = ClientRagdollManager.clientTick();
        int span = lastPublishClientTick == Integer.MIN_VALUE
                ? 1
                : Math.max(1, Math.min(MAX_INTERPOLATION_SPAN_TICKS, now - lastPublishClientTick));
        lastPublishClientTick = now;
        publishedSnapshot = new Snapshot(
                new Vector3f(currentPos), new Quat4f(currentRot),
                new Vector3f(prevPos), new Quat4f(prevRot), hasPrev, destroyed, span);
    }

    // Matches ClientRagdoll's ceiling, so a limb and the body it came off stretch the same gap.
    private static final int MAX_INTERPOLATION_SPAN_TICKS = 4;
    private final RenderPlayback<Snapshot> playback = new RenderPlayback<>();
    private int lastPublishClientTick = Integer.MIN_VALUE;

    private void updateCollisionGeometry() {
        BlockPos center = new BlockPos(
                (int) Math.floor(currentPos.x), (int) Math.floor(currentPos.y), (int) Math.floor(currentPos.z));
        if (collisionGeometry != null && !collisionGeometry.isValid()) releaseCollisionGeometry();
        if (center.equals(lastCollisionCenter) && collisionGeometry != null) return;

        ClientPhysicsWorld.CollisionGeometryHandle fresh = physicsWorld.getOrCreateCollisionGeometry(
                center, COLLISION_RADIUS, false,
                pos -> ClientRagdoll.buildBlockCollisionGeometry(physicsWorld, level, world, pos));
        // Rate limited this tick. The old shell is kept and the centre left unrecorded, so the next
        // tick tries again rather than leaving the limb with nothing under it.
        if (fresh == null) return;

        if (collisionGeometry != null) physicsWorld.releaseCollisionGeometry(collisionGeometry);
        collisionGeometry = fresh;
        lastCollisionCenter = center;
    }

    private void releaseCollisionGeometry() {
        if (collisionGeometry == null) return;
        physicsWorld.releaseCollisionGeometry(collisionGeometry);
        collisionGeometry = null;
        lastCollisionCenter = null;
    }

    // Speed change, in blocks per second, a single push passes through untouched. Covers a fist, a
    // player walking into the limb and most light blows.
    private static final float PUSH_KNEE_SPEED = 3.5f;
    // Past the knee only this fraction of the extra speed is kept. Ragdoll impulses are sized for a
    // part jointed to a whole body; a loose arm is a fraction of that mass, so a rifle round that
    // nudges a corpse would otherwise throw the arm twelve blocks a second.
    private static final float PUSH_SOFTNESS = 0.25f;
    // What is taken off the push goes into tumble instead, so a shot limb flips over where it lies
    // rather than just sliding less far. Radians per second per block per second removed.
    private static final float PUSH_EXCESS_TO_SPIN = 1.2f;
    private static final float PUSH_MAX_SPIN = 9f;

    // Push a loose limb, in ragdoll impulse units. Physics thread.
    public void applyImpulse(Vector3f impulse) {
        if (destroyed || body == null) return;
        unpark();
        // A limb that was kicked has earned its nudges back: it may well come to rest on its end
        // again, and it should be tipped over again when it does.
        toppleNudges = 0;
        body.activate();

        float deltaV = impulse.length() / Math.max(0.1f, mass);
        if (deltaV <= PUSH_KNEE_SPEED) {
            body.applyCentralImpulse(impulse);
            return;
        }
        float kept = PUSH_KNEE_SPEED + (deltaV - PUSH_KNEE_SPEED) * PUSH_SOFTNESS;
        Vector3f softened = new Vector3f(impulse);
        softened.scale(kept / deltaV);
        body.applyCentralImpulse(softened);

        // Spin square to both the push and the limb's long axis: end over end, the way a limb struck
        // off its centre turns. The side it was struck on is not known here, so the way round is not.
        Vector3f direction = new Vector3f(impulse);
        direction.normalize();
        float x = currentRot.x, y = currentRot.y, z = currentRot.z, w = currentRot.w;
        Vector3f longAxis = new Vector3f(2f * (x * y - w * z), 1f - 2f * (x * x + z * z), 2f * (y * z + w * x));
        Vector3f axis = new Vector3f();
        axis.cross(direction, longAxis);
        if (axis.lengthSquared() < 1.0e-4f) axis.cross(direction, new Vector3f(0f, 1f, 0f));
        if (axis.lengthSquared() < 1.0e-4f) axis.set(1f, 0f, 0f);
        axis.normalize();
        float spin = Math.min(PUSH_MAX_SPIN, (deltaV - kept) * PUSH_EXCESS_TO_SPIN);
        if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) spin = -spin;
        body.getAngularVelocity(scratchVec);
        scratchVec.scaleAdd(spin, axis, scratchVec);
        body.setAngularVelocity(scratchVec);
    }

    // The world under this limb changed: drop a parked limb's stale terrain and let it fall.
    // Physics thread; the limb counterpart of ClientRagdoll.onBlockChangedNear.
    public void onBlockChangedNear(BlockPos changedPos) {
        if (destroyed || body == null || changedPos == null) return;
        BlockPos centre = lastCollisionCenter;
        if (centre == null) {
            // Parked, so the shell is already gone and only the limb's own position can say whether
            // the change happened near it.
            centre = new BlockPos((int) Math.floor(currentPos.x),
                    (int) Math.floor(currentPos.y), (int) Math.floor(currentPos.z));
        }
        int reach = COLLISION_RADIUS + 1;
        if (Math.abs(centre.getX() - changedPos.getX()) > reach) return;
        if (Math.abs(centre.getY() - changedPos.getY()) > reach) return;
        if (Math.abs(centre.getZ() - changedPos.getZ()) > reach) return;

        releaseCollisionGeometry();
        unpark();
        toppleNudges = 0;
        updateCollisionGeometry();
        body.activate();
    }

    // Put a parked limb back into the simulation, rebuilding the terrain it fell out of.
    private void unpark() {
        if (!parked) return;
        body.setStatic(false);
        parked = false;
        lowMotionTicks = 0;
        // Straight away rather than on the next tick: an unparked limb with no shell under it is a
        // limb falling through the floor until the rate limiter gets around to building one.
        updateCollisionGeometry();
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        publish();
        releaseCollisionGeometry();
        if (body != null) world.destroyBody(body);
    }

    // Render

    // Advance the render-side smoothing once per frame, matching ClientRagdoll's contract.
    public void updateSmoothedRenderState(Snapshot snapshot, float partialTick, long renderFrame) {
        if (snapshot == null || snapshot.destroyed) return;
        if (renderFrame != Long.MIN_VALUE && smoothRenderFrame == renderFrame) return;
        // Same clock as a body's, so a loose arm cannot drift against the corpse it came off.
        playback.offer(snapshot);
        Snapshot playing = playback.advance(System.nanoTime());
        if (playing == null) return;
        RagdollTransform interpolated = playing.interpolated(playback.phase());
        if (!smoothSeeded) {
            smoothPos.set(interpolated.position);
            smoothRot.set(interpolated.rotation);
            smoothSeeded = true;
        } else {
            smoothPos.set(interpolated.position);
            smoothRot.set(interpolated.rotation);
        }
        smoothRenderFrame = renderFrame;
    }

    public RagdollTransform getSmoothedTransform() {
        if (!smoothSeeded) return null;
        return new RagdollTransform(part.index, new Vector3f(smoothPos), new Quat4f(smoothRot));
    }

    private static Quat4f slerp(Quat4f a, Quat4f b, float t) {
        float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        float bx = b.x, by = b.y, bz = b.z, bw = b.w;
        if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw; }
        float s0, s1;
        if (dot > 0.9995f) {
            s0 = 1f - t; s1 = t;
        } else {
            float angle = (float) Math.acos(dot);
            float sin = (float) Math.sin(angle);
            s0 = (float) Math.sin((1f - t) * angle) / sin;
            s1 = (float) Math.sin(t * angle) / sin;
        }
        Quat4f out = new Quat4f(a.x * s0 + bx * s1, a.y * s0 + by * s1, a.z * s0 + bz * s1, a.w * s0 + bw * s1);
        out.normalize();
        return out;
    }

    // False once the limb has come to rest and been parked.
    public boolean isSimulating() { return !destroyed && !parked && body != null; }

    public Snapshot getSnapshot() { return publishedSnapshot; }
    // The limb's collision half extents, for raycasting against it.
    public Vector3f getHalfExtents() { return new Vector3f(halfExtents); }
    public int getLimbId() { return limbId; }
    public int getSourceEntityId() { return sourceEntityId; }
    public RagdollPart getPart() { return part; }
    public MobModelHelper.ModelType getModelType() { return modelType; }
    public String getMobType() { return mobType; }
    public boolean isPlayer() { return isPlayer; }
    public boolean isBaby() { return isBaby; }
    public float getScale() { return scale; }
    @Nullable public UUID getPlayerUUID() { return playerUUID; }
    @Nullable public ResourceLocation getTexture() { return texture; }
    public void setTexture(@Nullable ResourceLocation texture) { this.texture = texture; }
    @Nullable public ResourceLocation getPlayerSkin() { return playerSkin; }
    public boolean isPlayerSkinSlim() { return playerSkinSlim; }
    public boolean isDestroyed() { return destroyed; }
    public int getTicksExisted() { return ticksExisted; }

    static void logBuildFailure(int limbId) {
        Ragdollified.LOGGER.debug("Detached limb {} could not obtain a physics body", limbId);
    }
}
