package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.LimbAnchors;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientDetachedLimb;
import com.raiiiden.ragdollified.client.ClientDetachedLimbManager;
import com.raiiiden.ragdollified.client.ClientMobModelHelper;
import com.raiiiden.ragdollified.client.ClientMobTextureCache;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.MobModelHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.EnumSet;
import java.util.Set;

// Client-side amputation, local to this client; use RagdollifiedServerApi for changes all players see.
// Only humanoid rigs can lose limbs.
@OnlyIn(Dist.CLIENT)
public final class RagdollAmputationApi {

    private RagdollAmputationApi() {}

    // Client-local limb ids count down from -2, server ids count up; -1 means 'no limb'.
    private static final java.util.concurrent.atomic.AtomicInteger LOCAL_LIMB_IDS =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private static int nextLocalLimbId() {
        return -LOCAL_LIMB_IDS.updateAndGet(v -> v == Integer.MAX_VALUE ? 2 : v + 1);
    }

    // Whether a rig has severable parts at all.
    public static boolean supportsAmputation(LivingEntity entity) {
        if (entity == null) return false;
        if (entity instanceof Player) return true;
        return MobModelHelper.isHumanoidModelType(ClientMobModelHelper.getActualModelType(entity));
    }

    public static boolean supportsAmputation(int ragdollEntityId) {
        ClientRagdoll ragdoll = ClientRagdollManager.get(ragdollEntityId);
        return ragdoll != null
                && (ragdoll.isPlayer() || MobModelHelper.isHumanoidModelType(ragdoll.getModelType()));
    }

    // Take a part off a ragdoll on this client, keeping its pose and motion plus extraImpulse.
    // Queued for the physics worker; returns the limb id, or -1 if no body or the part is gone.
    public static int severPart(int ragdollEntityId, RagdollPart part,
                                @Nullable Vec3 extraImpulse, int limbLifetimeTicks) {
        if (part == null || !part.isSeverable()) return -1;
        ClientRagdoll ragdoll = ClientRagdollManager.get(ragdollEntityId);
        if (ragdoll == null || ragdoll.isDestroyed() || ragdoll.isPartSevered(part)) return -1;

        Vec3 push = extraImpulse == null ? Vec3.ZERO : extraImpulse;
        int limbId = nextLocalLimbId();
        ClientRagdollManager.enqueueSever(new ClientRagdollManager.SeverRequest(
                ragdollEntityId, part.index, limbId, Math.max(20, limbLifetimeTicks),
                (float) push.x, (float) push.y, (float) push.z));
        return limbId;
    }

    // Take a limb off a living entity on this client; the entity itself is untouched.
    // Returns the limb id, or -1 if this entity has no such part.
    public static int spawnLimb(LivingEntity entity, RagdollPart part,
                                @Nullable Vec3 extraImpulse, int lifetimeTicks) {
        if (entity == null || part == null || !part.isSeverable()) return -1;
        if (!supportsAmputation(entity)) return -1;

        boolean isPlayer = entity instanceof Player;
        Vec3 anchor = LimbAnchors.worldAnchor(entity, part);
        org.joml.Quaternionf base = LimbAnchors.baseRotation(entity);
        org.joml.Vector3f half = LimbAnchors.halfExtents(part, entity.isBaby());
        Vec3 velocity = entity.getDeltaMovement().add(extraImpulse == null ? Vec3.ZERO : extraImpulse);

        int limbId = nextLocalLimbId();
        ClientDetachedLimb.SpawnData data = new ClientDetachedLimb.SpawnData(
                limbId, entity.getId(), part,
                isPlayer ? MobModelHelper.ModelType.HUMANOID_STANDARD
                         : ClientMobModelHelper.getActualModelType(entity),
                net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString(),
                isPlayer, entity.isBaby(),
                isPlayer ? 1.0f : entity.getBbHeight() / 1.8f,
                isPlayer ? entity.getUUID() : null,
                anchor, new Quat4f(base.x, base.y, base.z, base.w),
                velocity, Vec3.ZERO,
                new Vector3f(half.x, half.y, half.z),
                Math.max(20, lifetimeTicks));
        if (!isPlayer) data.texture = ClientMobTextureCache.getTextureForDeadMob(entity.getId());
        return ClientDetachedLimbManager.enqueueSpawn(data) ? limbId : -1;
    }

    // Which parts a ragdoll on this client is currently missing.
    public static Set<RagdollPart> getSeveredParts(int ragdollEntityId) {
        ClientRagdoll ragdoll = ClientRagdollManager.get(ragdollEntityId);
        if (ragdoll == null) return EnumSet.noneOf(RagdollPart.class);
        return RagdollPart.fromMask(ragdoll.getSeveredPartMask());
    }

    public static boolean isPartSevered(int ragdollEntityId, RagdollPart part) {
        ClientRagdoll ragdoll = ClientRagdollManager.get(ragdollEntityId);
        return ragdoll != null && ragdoll.isPartSevered(part);
    }

    // Raycast against every loose limb on this client, like RagdollifiedApi#raycast does for parts.
    // Safe from the client or render thread; returns the nearest hit limb, or empty.
    public static java.util.Optional<LimbHit> raycastLimbs(Vec3 start, Vec3 end) {
        if (start == null || end == null) return java.util.Optional.empty();
        Vec3 delta = end.subtract(start);
        double rayLength = delta.length();
        if (rayLength < 1.0e-6) return java.util.Optional.empty();

        LimbHit closest = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (ClientDetachedLimb limb : ClientDetachedLimbManager.getAll()) {
            if (limb.isDestroyed()) continue;
            ClientDetachedLimb.Snapshot snapshot = limb.getSnapshot();
            if (snapshot == null || snapshot.destroyed) continue;
            double t = RagdollifiedApi.intersectPart(
                    start, delta, snapshot.position, snapshot.rotation, limb.getHalfExtents());
            if (t < 0.0 || t > 1.0 || t >= bestT) continue;
            closest = new LimbHit(limb.getLimbId(), limb.getPart(), start.add(delta.scale(t)),
                    rayLength * t);
            bestT = t;
        }
        return java.util.Optional.ofNullable(closest);
    }

    // Push a loose limb. Queued for the physics worker; safe from any thread.
    public static void pushLimb(int limbId, Vec3 impulse) {
        if (impulse == null) return;
        ClientDetachedLimbManager.enqueueImpulse(
                limbId, (float) impulse.x, (float) impulse.y, (float) impulse.z);
    }

    // Remove a loose limb early. Queued for the physics worker.
    public static void removeLimb(int limbId) {
        ClientDetachedLimbManager.enqueueRemove(limbId);
    }

    public static boolean hasLimb(int limbId) {
        return ClientDetachedLimbManager.get(limbId) != null;
    }

    public static int getLimbCount() {
        return ClientDetachedLimbManager.getCount();
    }

    // Cap on loose limbs kept at once, oldest removed first; addons may set it from their own config.
    public static void setMaxLimbs(int limit) {
        ClientDetachedLimbManager.setMaxLimbs(limit);
    }

    public static int getMaxLimbs() {
        return ClientDetachedLimbManager.getMaxLimbs();
    }
}
