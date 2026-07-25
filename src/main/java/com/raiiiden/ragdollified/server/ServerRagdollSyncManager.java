package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.network.RagdollStatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived server index for cosmetic ragdolls. It retains spawn data only for the normal
 * ragdoll lifetime and delivers it when a player enters the relevant dimension and area.
 */
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public final class ServerRagdollSyncManager {
    private ServerRagdollSyncManager() {}

    private static final Map<Integer, RetainedRagdoll> RETAINED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Integer>> NEARBY_SENT = new HashMap<>();
    private static int lastCleanupTick = Integer.MIN_VALUE;

    private static final class RetainedRagdoll {
        final int entityId;
        final ResourceKey<Level> dimension;
        final Vec3 deathPosition;
        final int createdTick;
        final int expiresTick;
        final RagdollSpawnPacket spawnPacket;
        volatile RagdollTransform[] settledTransforms;
        volatile int impulseRevision;

        RetainedRagdoll(LivingEntity entity, RagdollSpawnPacket spawnPacket, int createdTick) {
            this.entityId = entity.getId();
            this.dimension = entity.level().dimension();
            this.deathPosition = entity.position();
            this.createdTick = createdTick;
            this.expiresTick = createdTick + RagdollifiedConfig.getRagdollLifetime();
            this.spawnPacket = spawnPacket;
        }

        Vec3 anchor() {
            RagdollTransform[] pose = settledTransforms;
            if (pose != null && pose.length > 0 && pose[0] != null) {
                Vector3f position = pose[0].position;
                return new Vec3(position.x, position.y, position.z);
            }
            return deathPosition;
        }
    }

    public static void registerDeath(LivingEntity entity, RagdollSpawnPacket packet) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        int now = server.getTickCount();
        RetainedRagdoll retained = new RetainedRagdoll(entity, packet, now);
        RETAINED.put(entity.getId(), retained);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isNear(player, retained, syncRadius(server))) {
                sendRetained(player, retained, now);
                NEARBY_SENT.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>())
                        .add(retained.entityId);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 10 != 0) return;

        MinecraftServer server = player.server;
        int now = server.getTickCount();
        cleanup(now);

        double radius = syncRadius(server);
        Set<Integer> previous = NEARBY_SENT.getOrDefault(player.getUUID(), Set.of());
        Set<Integer> currentlyNear = new HashSet<>();
        for (RetainedRagdoll retained : RETAINED.values()) {
            if (now >= retained.expiresTick || !isNear(player, retained, radius)) continue;
            currentlyNear.add(retained.entityId);
            if (!previous.contains(retained.entityId)) {
                sendRetained(player, retained, now);
            }
        }
        NEARBY_SENT.put(player.getUUID(), currentlyNear);
    }

    public static void handlePoseReport(ServerPlayer sender, int entityId,
                                        RagdollTransform[] transforms,
                                        int impulseRevision, boolean settled) {
        if (!settled || transforms == null) return;
        RetainedRagdoll retained = RETAINED.get(entityId);
        if (retained == null || retained.impulseRevision > impulseRevision) return;
        if (!sender.level().dimension().equals(retained.dimension)) return;

        double radius = syncRadius(sender.server);
        if (sender.position().distanceToSqr(retained.anchor()) > radius * radius) return;
        RagdollTransform[] validated = validateAndCopyPose(retained, transforms, radius);
        if (validated == null) return;

        // First report after spawn/push wins. Nearby clients already simulate this body, and
        // accepting later independent solutions would make the retained pose oscillate.
        synchronized (retained) {
            if (retained.settledTransforms != null || retained.impulseRevision > impulseRevision) return;
            retained.settledTransforms = validated;
            retained.impulseRevision = impulseRevision;
        }

        int age = Math.max(0, sender.server.getTickCount() - retained.createdTick);
        RagdollStatePacket state =
                new RagdollStatePacket(entityId, validated, impulseRevision, age, true);
        for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
            if (isNear(player, retained, radius)
                    && ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), state);
            }
        }
    }

    public static void invalidateSettledPose(ServerPlayer source, int entityId, int revision) {
        RetainedRagdoll retained = RETAINED.get(entityId);
        if (retained == null || !retained.dimension.equals(source.level().dimension())) return;
        synchronized (retained) {
            retained.settledTransforms = null;
            retained.impulseRevision = Math.max(retained.impulseRevision, revision);
        }
    }

    public static void remove(int entityId) {
        RETAINED.remove(entityId);
        for (Set<Integer> sent : NEARBY_SENT.values()) sent.remove(entityId);
    }

    private static void sendRetained(ServerPlayer player, RetainedRagdoll retained, int now) {
        if (!ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) return;
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), retained.spawnPacket);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RagdollStatePacket(
                        retained.entityId,
                        copyPose(retained.settledTransforms),
                        retained.impulseRevision,
                        Math.max(0, now - retained.createdTick),
                        retained.settledTransforms != null));
    }

    private static boolean isNear(ServerPlayer player, RetainedRagdoll retained, double radius) {
        return player.level().dimension().equals(retained.dimension)
                && player.position().distanceToSqr(retained.anchor()) <= radius * radius;
    }

    private static double syncRadius(MinecraftServer server) {
        double viewRadius = (server.getPlayerList().getViewDistance() + 2) * 16.0;
        return Math.max(viewRadius,
                RagdollifiedConfig.get(RagdollifiedConfig.PHYSICS_DISTANCE));
    }

    private static RagdollTransform[] validateAndCopyPose(RetainedRagdoll retained,
                                                           RagdollTransform[] transforms,
                                                           double radius) {
        RagdollTransform[] copy = new RagdollTransform[6];
        boolean hasTorso = false;
        double maxDistanceSq = Math.max(64.0, radius * 2.0);
        maxDistanceSq *= maxDistanceSq;
        for (int i = 0; i < copy.length; i++) {
            RagdollTransform transform = i < transforms.length ? transforms[i] : null;
            if (transform == null) continue;
            if (transform.partId != i
                    || !finite(transform.position) || !finite(transform.rotation)) return null;
            Vec3 position = new Vec3(
                    transform.position.x, transform.position.y, transform.position.z);
            if (position.distanceToSqr(retained.deathPosition) > maxDistanceSq) return null;
            copy[i] = new RagdollTransform(i, transform.position, transform.rotation);
            if (i == 0) hasTorso = true;
        }
        return hasTorso ? copy : null;
    }

    private static RagdollTransform[] copyPose(RagdollTransform[] transforms) {
        if (transforms == null) return null;
        RagdollTransform[] copy = new RagdollTransform[6];
        for (int i = 0; i < copy.length && i < transforms.length; i++) {
            RagdollTransform transform = transforms[i];
            if (transform != null) {
                copy[i] = new RagdollTransform(i, transform.position, transform.rotation);
            }
        }
        return copy;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z);
    }

    private static boolean finite(Quat4f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y)
                && Float.isFinite(value.z) && Float.isFinite(value.w);
    }

    private static void cleanup(int now) {
        if (lastCleanupTick == now) return;
        lastCleanupTick = now;
        Iterator<Map.Entry<Integer, RetainedRagdoll>> iterator = RETAINED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, RetainedRagdoll> entry = iterator.next();
            if (now >= entry.getValue().expiresTick) {
                int id = entry.getKey();
                iterator.remove();
                for (Set<Integer> sent : NEARBY_SENT.values()) sent.remove(id);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RETAINED.clear();
        NEARBY_SENT.clear();
        lastCleanupTick = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NEARBY_SENT.remove(event.getEntity().getUUID());
    }
}
