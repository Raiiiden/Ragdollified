package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.dynamics.RigidBody;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.api.RagdollCollision;
import com.raiiiden.ragdollified.api.RagdollCollisionListener;
import com.raiiiden.ragdollified.api.RagdollCollisionType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.vecmath.Vector3f;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

// Contact reporting for the collision API, dormant until a listener registers: nothing is sampled,
// walked or queued until then, and when active it only re-reads the manifolds the solver built.
@OnlyIn(Dist.CLIENT)
public final class RagdollCollisionTracker {

    private RagdollCollisionTracker() {}

    private record Registration(RagdollCollisionListener listener, double minImpactSpeed) {}

    private static final CopyOnWriteArrayList<Registration> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean active;
    private static volatile double lowestThreshold = Double.POSITIVE_INFINITY;

    // Physics worker fills this, the client thread drains it. Bounded so events collected while
    // nothing is draining them cannot grow without limit.
    private static final int MAX_QUEUED = 512;
    private static final ConcurrentLinkedQueue<RagdollCollision> PENDING = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<RagdollCollision> DISPATCH_BATCH = new ArrayDeque<>();

    private static final Vector3f contactPoint = new Vector3f();
    private static final Vector3f velocityA = new Vector3f();
    private static final Vector3f velocityB = new Vector3f();
    private static final Vector3f relative = new Vector3f();

    public static void addListener(RagdollCollisionListener listener, double minImpactSpeed) {
        if (listener == null) return;
        LISTENERS.add(new Registration(listener, Math.max(0.0, minImpactSpeed)));
        refresh();
    }

    public static boolean removeListener(RagdollCollisionListener listener) {
        boolean removed = LISTENERS.removeIf(r -> r.listener() == listener);
        if (removed) refresh();
        return removed;
    }

    public static boolean isActive() { return active; }

    public static int listenerCount() { return LISTENERS.size(); }

    private static void refresh() {
        double lowest = Double.POSITIVE_INFINITY;
        for (Registration r : LISTENERS) lowest = Math.min(lowest, r.minImpactSpeed());
        lowestThreshold = lowest;
        active = !LISTENERS.isEmpty();
        if (!active) PENDING.clear();
    }

    // Physics worker, before the step: record what each part is carrying into it.
    public static void captureVelocities(Collection<ClientRagdoll> ragdolls) {
        if (!active) return;
        for (ClientRagdoll ragdoll : ragdolls) {
            if (!ragdoll.isDestroyed()) ragdoll.captureImpactSample();
        }
    }

    // Physics worker, after the step: turn every newly formed contact into a queued event.
    public static void collect(ClientJbulletWorld physicsWorld,
                               IdentityHashMap<RigidBody, ClientRagdoll> bodyOwners) {
        if (!active) return;
        List<PersistentManifold> manifolds = physicsWorld.getDispatcher().getInternalManifoldPointer();
        for (PersistentManifold manifold : manifolds) {
            int numContacts = manifold.getNumContacts();
            if (numContacts == 0) continue;
            RigidBody a = (RigidBody) manifold.getBody0();
            RigidBody b = (RigidBody) manifold.getBody1();
            ClientRagdoll ownerA = bodyOwners.get(a);
            ClientRagdoll ownerB = bodyOwners.get(b);
            // Joint jitter between two parts of the same body is not a collision worth reporting.
            if (ownerA == null && ownerB == null) continue;
            if (ownerA != null && ownerA == ownerB) continue;

            for (int i = 0; i < numContacts; i++) {
                ManifoldPoint point = manifold.getContactPoint(i);
                if (point.getLifeTime() > 1) continue;
                if (ownerA != null) report(ownerA, a, ownerB, b, point, false);
                if (ownerB != null) report(ownerB, b, ownerA, a, point, true);
            }
        }
    }

    private static void report(ClientRagdoll owner, RigidBody body,
                               ClientRagdoll otherOwner, RigidBody other,
                               ManifoldPoint point, boolean flipNormal) {
        int partIndex = owner.partIndexOf(body);
        RagdollPart part = RagdollPart.byIndex(partIndex);
        if (part == null) return;

        contactPoint.set(point.positionWorldOnB);
        if (!owner.readImpactVelocity(partIndex, contactPoint, velocityA)) velocityA.set(0f, 0f, 0f);
        if (otherOwner == null || !otherOwner.readImpactVelocity(
                otherOwner.partIndexOf(other), contactPoint, velocityB)) {
            velocityB.set(0f, 0f, 0f);
        }
        relative.sub(velocityA, velocityB);

        float nx = point.normalWorldOnB.x, ny = point.normalWorldOnB.y, nz = point.normalWorldOnB.z;
        if (flipNormal) { nx = -nx; ny = -ny; nz = -nz; }
        double impactSpeed = Math.abs(relative.x * nx + relative.y * ny + relative.z * nz);
        if (impactSpeed < lowestThreshold) return;

        RagdollCollisionType type;
        BlockPos blockPos = null;
        int otherEntityId = -1;
        RagdollPart otherPart = null;
        if (otherOwner != null) {
            type = RagdollCollisionType.RAGDOLL;
            otherEntityId = otherOwner.getId();
            otherPart = RagdollPart.byIndex(otherOwner.partIndexOf(other));
        } else if (other.getUserPointer() instanceof BlockPos pos) {
            type = RagdollCollisionType.TERRAIN;
            blockPos = pos;
        } else {
            type = RagdollCollisionType.OTHER;
        }

        enqueue(new RagdollCollision(
                owner.getId(), part, type,
                new Vec3(contactPoint.x, contactPoint.y, contactPoint.z),
                new Vec3(nx, ny, nz),
                impactSpeed, relative.length(),
                point.appliedImpulse, Math.max(0.0, -point.getDistance()),
                blockPos, otherEntityId, otherPart));
    }

    private static void enqueue(RagdollCollision collision) {
        if (PENDING.size() >= MAX_QUEUED) PENDING.poll();
        PENDING.add(collision);
    }

    // Client thread. Drained into a local batch first so a listener that adds or removes another
    // listener cannot change who sees the events already collected for this tick.
    public static void dispatchPending() {
        if (PENDING.isEmpty()) return;
        RagdollCollision collision;
        while ((collision = PENDING.poll()) != null) DISPATCH_BATCH.add(collision);
        for (RagdollCollision queued : DISPATCH_BATCH) {
            for (Registration registration : LISTENERS) {
                if (queued.impactSpeed() < registration.minImpactSpeed()) continue;
                try {
                    registration.listener().onRagdollCollision(queued);
                } catch (Throwable t) {
                    Ragdollified.LOGGER.error("Ragdoll collision listener threw", t);
                }
            }
        }
        DISPATCH_BATCH.clear();
    }

    public static void clear() {
        PENDING.clear();
    }
}
