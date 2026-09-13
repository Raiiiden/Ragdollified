package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.LimbAnchors;
import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.LimbSpawnPacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollSeverPacket;
import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import javax.vecmath.Quat4f;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class RagdollifiedServerApi {
    private RagdollifiedServerApi() {}

    private static final CopyOnWriteArrayList<RagdollImpulseListener> IMPULSE_LISTENERS =
            new CopyOnWriteArrayList<>();

    public static void startPlayerRagdoll(ServerPlayer player, Vec3 position,
                                          float bodyYaw, float pitch, int lifetimeTicks) {
        startPlayerRagdoll(player, position, bodyYaw, pitch, Vec3.ZERO, lifetimeTicks);
    }

    // As above, launching the body at velocity in blocks per second (null for none).
    public static void startPlayerRagdoll(ServerPlayer player, Vec3 position, float bodyYaw,
                                          float pitch, @Nullable Vec3 velocity, int lifetimeTicks) {
        if (player == null || position == null) return;
        Vec3 launch = velocity == null ? Vec3.ZERO : velocity;
        RagdollSpawnPacket packet = new RagdollSpawnPacket(
                player.getId(),
                true,
                net.minecraft.world.entity.EntityType.getKey(player.getType()).toString(),
                1.0f,
                player.getUUID().toString(),
                player.getName().getString(),
                position.x, position.y, position.z,
                bodyYaw, pitch,
                launch.x, launch.y, launch.z,
                player.getPose() == Pose.SWIMMING,
                player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                player.getItemBySlot(EquipmentSlot.FEET).copy());
        ServerRagdollSyncManager.registerLive(player, packet, lifetimeTicks);
    }

    public static void stopRagdoll(int entityId) {
        ServerRagdollSyncManager.remove(entityId);
    }

    // True when this player is the client chosen to simulate and stream this body;
    // gate world-authoritative actions on it.
    public static boolean isStreamOwner(ServerPlayer player, int ragdollEntityId) {
        return player != null && ServerRagdollSyncManager.isStreamOwner(player, ragdollEntityId);
    }

    // Radius, in blocks, inside which clients actually simulate a ragdoll. Outside it a body is
    // frozen and reports nothing, so it is the right scale for "close enough to have seen this".
    public static double getPhysicsDistance() {
        return RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE);
    }

    // Listen for validated pushes, called on the server thread just before broadcast.
    // Each carries a monotonic revision that later settle reports also carry.
    public static void addImpulseListener(RagdollImpulseListener listener) {
        if (listener != null) IMPULSE_LISTENERS.addIfAbsent(listener);
    }

    public static boolean removeImpulseListener(RagdollImpulseListener listener) {
        return listener != null && IMPULSE_LISTENERS.remove(listener);
    }

    // Internal: called by the impulse packet handler once an ordering has been assigned.
    public static void dispatchImpulse(ServerPlayer sender, RagdollImpulse impulse) {
        for (RagdollImpulseListener listener : IMPULSE_LISTENERS) {
            try {
                listener.onRagdollImpulse(sender, impulse);
            } catch (Throwable t) {
                Ragdollified.LOGGER.error(
                        "Impulse listener {} failed", listener.getClass().getName(), t);
            }
        }
    }

    // Amputation

    private static final CopyOnWriteArrayList<SeveredPartProvider> SEVERED_PART_PROVIDERS =
            new CopyOnWriteArrayList<>();
    // Limb ids are only meaningful inside one server session, and every client keys its loose limbs
    // by them, so one counter here is what keeps two clients talking about the same arm.
    private static final AtomicInteger LIMB_IDS = new AtomicInteger();

    // Register a source of parts an entity already lost, consulted at death; see SeveredPartProvider.
    public static void addSeveredPartProvider(SeveredPartProvider provider) {
        if (provider != null) SEVERED_PART_PROVIDERS.addIfAbsent(provider);
    }

    public static boolean removeSeveredPartProvider(SeveredPartProvider provider) {
        return provider != null && SEVERED_PART_PROVIDERS.remove(provider);
    }

    // Internal: called from the death hook. Every provider is asked and the answers OR-ed, so two
    // addons tracking different injuries both get their say.
    public static int resolveSeveredPartMask(LivingEntity entity) {
        if (SEVERED_PART_PROVIDERS.isEmpty() || entity == null) return 0;
        int mask = 0;
        for (SeveredPartProvider provider : SEVERED_PART_PROVIDERS) {
            try {
                mask |= provider.severedPartMask(entity);
            } catch (Throwable t) {
                Ragdollified.LOGGER.error(
                        "Severed part provider {} failed", provider.getClass().getName(), t);
            }
        }
        // The torso is the root everything else hangs from, so a provider naming it is asking for a
        // body with no body. Masked out here rather than trusted and crashed on.
        return mask & RagdollPart.ALL_SEVERABLE_MASK;
    }

    // Whether a rig can lose limbs at all; only the humanoid families can.
    public static boolean supportsAmputation(LivingEntity entity) {
        if (entity == null) return false;
        if (entity instanceof net.minecraft.world.entity.player.Player) return true;
        return MobModelHelper.isHumanoidModelType(MobModelHelper.getModelTypeFromEntity(entity));
    }

    // Take a limb off a living entity and set it loose on every nearby client; the entity is untouched.
    // extraImpulse is in blocks/s or null. Returns the limb id, or -1 if there's no such part.
    public static int spawnDetachedLimb(LivingEntity entity, RagdollPart part,
                                        @Nullable Vec3 extraImpulse, int lifetimeTicks) {
        if (entity == null || part == null || !part.isSeverable()) return -1;
        if (!(entity.level() instanceof ServerLevel level)) return -1;
        if (!supportsAmputation(entity)) return -1;

        Vec3 anchor = LimbAnchors.worldAnchor(entity, part);
        org.joml.Quaternionf base = LimbAnchors.baseRotation(entity);
        Quat4f rotation = new Quat4f(base.x, base.y, base.z, base.w);
        Vec3 velocity = entity.getDeltaMovement().add(extraImpulse == null ? Vec3.ZERO : extraImpulse);
        // A limb that leaves the body spinning reads as torn off; one that leaves it flat reads as
        // dropped. Derived from the push so a light knock does not send it cartwheeling.
        double spin = Math.min(6.0, velocity.length() * 1.5);
        Vec3 angular = new Vec3(
                (entity.getRandom().nextDouble() - 0.5) * spin,
                (entity.getRandom().nextDouble() - 0.5) * spin,
                (entity.getRandom().nextDouble() - 0.5) * spin);

        int limbId = nextLimbId();
        sendNear(level, anchor, new LimbSpawnPacket(
                limbId, entity.getId(), part, anchor, rotation, velocity, angular,
                Math.max(20, lifetimeTicks)));
        return limbId;
    }

    // Take a part off an existing ragdoll; each client uses its own simulated pose for the limb.
    // near decides who gets told; extraImpulse may be null. Returns the limb id.
    public static int severRagdollPart(ServerLevel level, Vec3 near, int ragdollEntityId,
                                       RagdollPart part, @Nullable Vec3 extraImpulse, int lifetimeTicks) {
        if (level == null || part == null || !part.isSeverable()) return -1;
        Vec3 push = extraImpulse == null ? Vec3.ZERO : extraImpulse;
        int limbId = nextLimbId();
        // Recorded against the body the server keeps for late arrivals, so a player who turns up
        // after the fact sees the same one-armed corpse everyone else does.
        ServerRagdollSyncManager.markSevered(ragdollEntityId, part);
        sendNear(level, near, new RagdollSeverPacket(
                ragdollEntityId, part, limbId, Math.max(20, lifetimeTicks),
                (float) push.x, (float) push.y, (float) push.z));
        return limbId;
    }

    // A fresh limb id, for callers building their own packets.
    public static int nextLimbId() {
        return LIMB_IDS.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
    }

    // Only clients close enough to be simulating anything there can act on these, so the physics
    // distance is both the right radius and a real saving over a dimension-wide send.
    private static void sendNear(ServerLevel level, Vec3 position, Object packet) {
        double radius = Math.max(32.0, getPhysicsDistance());
        ModNetwork.CHANNEL.send(PacketDistributor.NEAR.with(
                () -> new PacketDistributor.TargetPoint(
                        position.x, position.y, position.z, radius, level.dimension())), packet);
    }
}
