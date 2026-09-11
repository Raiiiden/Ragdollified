package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollCamera;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.client.RagdollCollisionTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntPredicate;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// Client-side integration API. Every mutating call is safe from the client or render thread, being
// queued onto the physics worker, and is deliberately local: multiplayer agreement is the caller's.
@OnlyIn(Dist.CLIENT)
public final class RagdollifiedApi {
    private RagdollifiedApi() {}

    // Queue a clean ragdoll at a loaded entity's current pose, ignoring the automatic-death
    // enable list. False for an unsupported model or a duplicate id.
    public static boolean spawn(LivingEntity entity) {
        return ClientRagdollManager.spawnFromEntity(entity);
    }

    // Spawn a controllable ragdoll. Invalid or unsupported entities throw rather than return,
    // so an integration cannot end up holding a handle to a body that never existed.
    public static RagdollHandle spawn(LivingEntity entity, SpawnOptions options) {
        if (entity == null) throw new IllegalArgumentException("entity cannot be null");
        SpawnOptions resolved = options != null ? options : SpawnOptions.DEFAULT;
        if (!ClientRagdollManager.spawnFromEntity(entity, resolved.persistent(), resolved.spawnTransform())) {
            throw new IllegalStateException("Could not queue a ragdoll for entity " + entity.getId());
        }
        if (resolved.hideEntity()) setEntityHidden(entity, true);
        return new RagdollHandle(entity.getId(), resolved.hideEntity());
    }

    // Make a ragdoll thrash, driving joints toward random targets (Jolt only; inert on JBullet).
    // durationTicks <= 0 stops; intervalTicks 3-6 works best. Returns false if there is no such ragdoll.
    public static boolean flail(int entityId, int durationTicks, int intervalTicks,
                                float spreadDegrees, float strength) {
        if (!ClientRagdollManager.hasPendingOrActiveRagdoll(entityId)) return false;
        ClientRagdollManager.requestFlail(entityId, durationTicks, Math.max(1, intervalTicks),
                (float) Math.toRadians(spreadDegrees), strength);
        return true;
    }

    public static boolean flail(Entity entity, int durationTicks, int intervalTicks,
                                float spreadDegrees, float strength) {
        return entity != null && flail(entity.getId(), durationTicks, intervalTicks, spreadDegrees, strength);
    }

    // Stop a flail early and hand the joints back to the rest-pose springs.
    public static boolean stopFlail(int entityId) {
        return flail(entityId, 0, 1, 0f, 0f);
    }

    // Remove an active or queued ragdoll.
    public static boolean remove(int entityId) {
        if (!ClientRagdollManager.hasPendingOrActiveRagdoll(entityId)) return false;
        ClientRagdollManager.requestRemoveRagdoll(entityId);
        return true;
    }

    public static boolean remove(Entity entity) {
        return entity != null && remove(entity.getId());
    }

    // Obtain controls for an automatically spawned or previously queued ragdoll.
    public static Optional<RagdollHandle> getHandle(int entityId) {
        return ClientRagdollManager.hasPendingOrActiveRagdoll(entityId)
                ? Optional.of(new RagdollHandle(entityId, false))
                : Optional.empty();
    }

    public static Optional<RagdollHandle> getHandle(Entity entity) {
        return entity == null ? Optional.empty() : getHandle(entity.getId());
    }

    // Hide an entity's normal renderer on this client. The entity remains fully functional.
    public static void setEntityHidden(Entity entity, boolean hidden) {
        if (entity != null) setEntityHidden(entity.getId(), hidden);
    }

    // Hide an entity's normal renderer by its current client entity id.
    public static void setEntityHidden(int entityId, boolean hidden) {
        ClientRagdollManager.setEntityHidden(entityId, hidden);
    }

    public static boolean isEntityHidden(Entity entity) {
        return entity != null && isEntityHidden(entity.getId());
    }

    public static boolean isEntityHidden(int entityId) {
        return ClientRagdollManager.isEntityExplicitlyHidden(entityId);
    }

    public static boolean attachCamera(int entityId, RagdollPart part, CameraOptions options) {
        return ClientRagdollCamera.attach(entityId, part, options);
    }

    public static void detachCamera() {
        ClientRagdollCamera.detach();
    }

    public static void detachCamera(int entityId) {
        ClientRagdollCamera.detach(entityId);
    }

    // Begin a kinematic drag of one part. Moving a handle never applies an impulse; close() it
    // when finished.
    public static Optional<RagdollDrag> beginDrag(int entityId, RagdollPart part, Vec3 target) {
        return ClientRagdollManager.beginDrag(entityId, part, target)
                ? Optional.of(RagdollDrag.singlePart(entityId, part))
                : Optional.empty();
    }

    public static Optional<RagdollDrag> beginDrag(Entity entity, RagdollPart part, Vec3 target) {
        return entity == null ? Optional.empty() : beginDrag(entity.getId(), part, target);
    }

    // Begin an atomic, no-impulse drag of arbitrary parts. Each map passed to moveTo is applied
    // as one unit before the next physics step.
    public static Optional<RagdollDrag> beginDrag(int entityId, Map<RagdollPart, Vec3> initialTargets) {
        return ClientRagdollManager.beginDrag(entityId, initialTargets)
                ? Optional.of(RagdollDrag.partGroup(entityId))
                : Optional.empty();
    }

    public static Optional<RagdollDrag> beginDrag(Entity entity, Map<RagdollPart, Vec3> initialTargets) {
        return entity == null ? Optional.empty() : beginDrag(entity.getId(), initialTargets);
    }

    // Begin a paired limb drag: ARMS drives both arms, LEGS both legs. Ragdollified owns the
    // raised limb offsets, wake-up, friction, CCD, and torso follow.
    public static Optional<RagdollDrag> beginDrag(int entityId, DragEnd end, DragTarget target) {
        return ClientRagdollManager.beginDrag(entityId, end, target)
                ? Optional.of(RagdollDrag.endGroup(entityId))
                : Optional.empty();
    }

    public static Optional<RagdollDrag> beginDrag(Entity entity, DragEnd end, DragTarget target) {
        return entity == null ? Optional.empty() : beginDrag(entity.getId(), end, target);
    }

    // Return a thread-safe copy of the current visual/physics state, if constructed.
    public static Optional<RagdollState> getState(int entityId) {
        ClientRagdoll ragdoll = ClientRagdollManager.get(entityId);
        if (ragdoll == null) return Optional.empty();
        ClientRagdoll.TransformSnapshot snapshot = ragdoll.getSnapshot();
        if (snapshot == null || snapshot.destroyed) return Optional.empty();
        return Optional.of(RagdollState.from(ragdoll, snapshot));
    }

    public static Optional<RagdollState> getState(Entity entity) {
        return entity == null ? Optional.empty() : getState(entity.getId());
    }

    public static boolean hasRagdoll(int entityId) {
        return ClientRagdollManager.hasRagdollFor(entityId);
    }

    public static boolean hasRagdoll(Entity entity) {
        return entity != null && hasRagdoll(entity.getId());
    }

    // Raycast against the current oriented collision box of every visible ragdoll part.
    public static Optional<RagdollHit> raycast(Vec3 start, Vec3 end) {
        return raycast(start, end, id -> true);
    }

    // Raycast against immutable render snapshots, with the optional filter receiving the source
    // entity id. No Bullet body is ever read from the render thread.
    public static Optional<RagdollHit> raycast(Vec3 start, Vec3 end, IntPredicate entityFilter) {
        if (start == null || end == null) return Optional.empty();
        IntPredicate filter = entityFilter != null ? entityFilter : id -> true;
        Vec3 delta = end.subtract(start);
        double rayLength = delta.length();
        if (rayLength < 1.0e-6) return Optional.empty();

        RagdollHit closest = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (ClientRagdoll ragdoll : ClientRagdollManager.getAll()) {
            if (ragdoll.isDestroyed() || !filter.test(ragdoll.getId())) continue;
            ClientRagdoll.TransformSnapshot snapshot = ragdoll.getSnapshot();
            if (snapshot == null || snapshot.destroyed) continue;
            for (RagdollPart part : RagdollPart.values()) {
                int i = part.index;
                if (i >= snapshot.positions.length || snapshot.positions[i] == null
                        || snapshot.rotations[i] == null || snapshot.halfExtents[i] == null) continue;
                double t = intersectPart(start, delta, snapshot.positions[i], snapshot.rotations[i], snapshot.halfExtents[i]);
                if (t < 0.0 || t > 1.0 || t >= bestT) continue;
                Vec3 position = start.add(delta.scale(t));
                closest = new RagdollHit(ragdoll.getId(), part, position, rayLength * t);
                bestT = t;
            }
        }
        return Optional.ofNullable(closest);
    }

    // Slab-test a world ray against a snapshot box in local space; package-private for RagdollAmputationApi.
    static double intersectPart(Vec3 start, Vec3 delta, javax.vecmath.Vector3f center,
                                        javax.vecmath.Quat4f rotation, javax.vecmath.Vector3f halfExtents) {
        Quaternionf inverse = new Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w).invert();
        Vector3f localStart = inverse.transform(new Vector3f(
                (float) (start.x - center.x), (float) (start.y - center.y), (float) (start.z - center.z)));
        Vector3f localDelta = inverse.transform(new Vector3f((float) delta.x, (float) delta.y, (float) delta.z));
        float[] origin = {localStart.x, localStart.y, localStart.z};
        float[] direction = {localDelta.x, localDelta.y, localDelta.z};
        // The snapshot now carries the resolved collision half extents. It used to carry
        // Bullet's pre-margin size, which is why this had a margin added back on.
        float[] extent = {halfExtents.x, halfExtents.y, halfExtents.z};
        double enter = 0.0, exit = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0e-7f) {
                if (origin[axis] < -extent[axis] || origin[axis] > extent[axis]) return -1.0;
                continue;
            }
            double a = (-extent[axis] - origin[axis]) / direction[axis];
            double b = ( extent[axis] - origin[axis]) / direction[axis];
            if (a > b) { double swap = a; a = b; b = swap; }
            enter = Math.max(enter, a);
            exit = Math.min(exit, b);
            if (enter > exit) return -1.0;
        }
        return enter;
    }

    // Listen for ragdoll contacts, called on the client thread once per contact formed that tick.
    // Detection is off entirely until a listener registers, and resting contact is never reported.
    public static void addCollisionListener(RagdollCollisionListener listener) {
        addCollisionListener(listener, 0.0);
    }

    // As above, dropping contacts closing slower than minImpactSpeed (blocks per second) before they
    // are queued. Prefer a non-zero value: a settling body makes many slow contacts.
    public static void addCollisionListener(RagdollCollisionListener listener, double minImpactSpeed) {
        RagdollCollisionTracker.addListener(listener, minImpactSpeed);
    }

    public static boolean removeCollisionListener(RagdollCollisionListener listener) {
        return RagdollCollisionTracker.removeListener(listener);
    }

    // True while any listener is registered, i.e. while contact detection is doing work.
    public static boolean isCollisionTrackingActive() {
        return RagdollCollisionTracker.isActive();
    }

    // Settle reporting

    // Listen for local player ragdolls settling; each body reports once per client.
    // Every simulating client reports, so validate via RagdollifiedServerApi#isStreamOwner.
    public static void addSettleListener(RagdollSettleListener listener) {
        ClientRagdollManager.addSettleListener(listener);
    }

    public static boolean removeSettleListener(RagdollSettleListener listener) {
        return ClientRagdollManager.removeSettleListener(listener);
    }

    // Settle-report deadline in ticks: an unsettled body reports its current pose just before it.
    // The shortest requested deadline wins, capped by the ragdoll lifetime.
    public static void setSettleDeadlineTicks(int ticks) {
        ClientRagdollManager.setSettleDeadlineTicks(ticks);
    }

    // The player a ragdoll was built from, empty for a mob body or an unknown id.
    public static Optional<UUID> getPlayerUUID(int entityId) {
        ClientRagdoll ragdoll = ClientRagdollManager.get(entityId);
        return ragdoll == null || !ragdoll.isPlayer()
                ? Optional.empty()
                : Optional.ofNullable(ragdoll.getPlayerUUID());
    }

    // Replace a settled player ragdoll with a caller-owned body, moving its visuals onto replacementKey.
    // expectedOwner guards against recycled entity ids; safe to call every tick until it returns true.
    public static boolean handOffPlayerRagdoll(int ragdollEntityId, UUID expectedOwner, UUID replacementKey) {
        return ClientRagdollManager.handOffPlayerRagdoll(ragdollEntityId, expectedOwner, replacementKey);
    }

    // Exempt player ragdolls from the per-player limit while an addon's bodies stand in for them.
    public static void setPlayerRagdollCullingSuppressed(boolean suppressed) {
        ClientRagdollManager.setPlayerRagdollCullingSuppressed(suppressed);
    }

    // Snapshot of known active ragdoll entity ids.
    public static Set<Integer> getRagdollIds() {
        Set<Integer> ids = new TreeSet<>();
        for (ClientRagdoll ragdoll : ClientRagdollManager.getAll()) {
            if (!ragdoll.isDestroyed()) ids.add(ragdoll.getId());
        }
        return Set.copyOf(ids);
    }
}
