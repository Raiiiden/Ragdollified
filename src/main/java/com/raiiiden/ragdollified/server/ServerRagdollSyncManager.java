package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.network.RagdollStatePacket;
import com.raiiiden.ragdollified.network.RagdollStreamOwnerPacket;
import com.raiiiden.ragdollified.network.RagdollStreamPacket;
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

// Short-lived server index for cosmetic ragdolls: keeps spawn data for the normal ragdoll
// lifetime only, and delivers it when a player enters the right dimension and area.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public final class ServerRagdollSyncManager {
    private ServerRagdollSyncManager() {}

    private static final Map<Integer, RetainedRagdoll> RETAINED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Integer>> NEARBY_SENT = new HashMap<>();
    private static int lastCleanupTick = Integer.MIN_VALUE;
    private static int lastOwnerElectionTick = Integer.MIN_VALUE;
    // Ownership is re-evaluated promptly when the current simulator leaves the streamed body.
    private static final int OWNER_ELECTION_INTERVAL_TICKS = 20;

    private static final class RetainedRagdoll {
        final int entityId;
        final ResourceKey<Level> dimension;
        final Vec3 deathPosition;
        final int createdTick;
        final int expiresTick;
        final RagdollSpawnPacket spawnPacket;
        final UUID victim;
        volatile RagdollTransform[] settledTransforms;
        volatile int impulseRevision;
        volatile UUID streamOwner;
        volatile Vec3 streamAnchor;
        volatile RagdollTransform[] latestStreamTransforms;
        volatile int ownerInputSequence;
        volatile int ownerInputSampleTick = Integer.MIN_VALUE;
        volatile int streamSequence;
        volatile int streamSampleTick = Integer.MIN_VALUE;

        RetainedRagdoll(LivingEntity entity, RagdollSpawnPacket spawnPacket, int createdTick,
                        int lifetimeTicks) {
            this.entityId = entity.getId();
            this.dimension = entity.level().dimension();
            this.deathPosition = entity.position();
            this.createdTick = createdTick;
            long expiry = (long) createdTick + Math.max(1, lifetimeTicks);
            this.expiresTick = expiry >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) expiry;
            this.spawnPacket = spawnPacket;
            this.victim = spawnPacket.isPlayer() ? entity.getUUID() : null;
        }

        Vec3 anchor() {
            Vec3 streamed = streamAnchor;
            if (settledTransforms == null && streamed != null) return streamed;
            RagdollTransform[] pose = settledTransforms;
            if (pose != null && pose.length > 0 && pose[0] != null) {
                Vector3f position = pose[0].position;
                return new Vec3(position.x, position.y, position.z);
            }
            return deathPosition;
        }
    }

    public static void registerDeath(LivingEntity entity, RagdollSpawnPacket packet) {
        register(entity, packet, RagdollifiedConfig.getRagdollLifetime());
    }

    public static void register(LivingEntity entity, RagdollSpawnPacket packet, int lifetimeTicks) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        int now = server.getTickCount();
        RetainedRagdoll retained = new RetainedRagdoll(entity, packet, now, lifetimeTicks);
        RETAINED.put(entity.getId(), retained);

        electStreamOwner(server, retained);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isNear(player, retained, syncRadius(server))) {
                sendRetained(player, retained, now);
                NEARBY_SENT.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>())
                        .add(retained.entityId);
            }
        }
    }

    // The victim wins for player bodies; otherwise the closest client simulates.
    private static boolean electStreamOwner(MinecraftServer server, RetainedRagdoll retained) {
        double radius = syncRadius(server);
        UUID previous = retained.streamOwner;

        ServerPlayer elected = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isNear(player, retained, radius)) continue;
            // An optional-channel/vanilla client cannot run or publish our physics. Never elect
            // one, otherwise every modded observer waits forever for an initial pose.
            if (!ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) continue;
            if (player.getUUID().equals(retained.victim)) {
                elected = player;
                break;
            }
            double distanceSq = player.position().distanceToSqr(retained.anchor());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                elected = player;
            }
        }

        UUID next = elected != null ? elected.getUUID() : null;
        if (next == null ? previous == null : next.equals(previous)) return false;
        retained.streamOwner = next;
        // Client sequence numbers only order one owner's frames, and a new owner may have a lower
        // counter, so compare against a per-owner baseline while streamSequence stays continuous.
        retained.ownerInputSequence = 0;
        retained.ownerInputSampleTick = Integer.MIN_VALUE;

        if (previous != null) {
            ServerPlayer old = server.getPlayerList().getPlayer(previous);
            if (old != null) sendOwnership(old, retained.entityId, false);
        }
        if (elected != null) {
            sendOwnership(elected, retained.entityId, true);
            RagdollTransform[] latest = retained.latestStreamTransforms;
            if (latest != null && retained.settledTransforms == null) {
                ServerPlayer newOwner = elected;
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> newOwner),
                        new RagdollStreamPacket(retained.entityId, retained.streamSequence,
                                retained.streamSampleTick, copyPose(latest)));
            }
        }
        return true;
    }

    public static boolean isStreamOwner(ServerPlayer player, int entityId) {
        RetainedRagdoll retained = RETAINED.get(entityId);
        return retained != null
                && player.level().dimension().equals(retained.dimension)
                && player.getUUID().equals(retained.streamOwner);
    }

    // Relay one in-flight pose frame to every other nearby client. The server never simulates or
    // corrects it, only checking sender and sanity, so no client can puppet a body it does not own.
    public static void handleStreamFrame(ServerPlayer sender, int entityId,
                                         RagdollTransform[] transforms, int sequence,
                                         int sampleTick, boolean hardSync) {
        RetainedRagdoll retained = RETAINED.get(entityId);
        if (retained == null) return;
        if (!sender.getUUID().equals(retained.streamOwner)) return;
        if (!sender.level().dimension().equals(retained.dimension)) return;
        if (transforms == null || transforms.length == 0 || transforms[0] == null) return;

        double radius = syncRadius(sender.server);
        int relaySequence;
        RagdollTransform[] relayedTransforms;
        synchronized (retained) {
            // Reject stale/replayed input from the current owner, then assign a server-owned
            // sequence that remains monotonic even when ownership changes.
            if (sequence <= retained.ownerInputSequence) return;
            if (retained.ownerInputSampleTick != Integer.MIN_VALUE
                    && sampleTick <= retained.ownerInputSampleTick) return;
            RagdollTransform[] validated = validateAndCopyPose(retained, transforms, radius);
            if (validated == null) return;
            relayedTransforms = validated;
            retained.latestStreamTransforms = validated;
            retained.ownerInputSequence = sequence;
            retained.ownerInputSampleTick = sampleTick;
            retained.streamSequence = retained.streamSequence == Integer.MAX_VALUE
                    ? 1 : retained.streamSequence + 1;
            relaySequence = retained.streamSequence;
            int serverTick = sender.server.getTickCount();
            retained.streamSampleTick = retained.streamSampleTick == Integer.MIN_VALUE
                    ? serverTick : Math.max(serverTick, retained.streamSampleTick + 1);
            Vector3f torso = validated[0].position;
            retained.streamAnchor = new Vec3(torso.x, torso.y, torso.z);
            // A frame after a prior settle means the authoritative body woke. Clear the old
            // terminal pose so the owner's next settle report can become authoritative again.
            retained.settledTransforms = null;
        }

        RagdollStreamPacket frame = new RagdollStreamPacket(
                entityId, relaySequence, retained.streamSampleTick, hardSync, relayedTransforms);
        for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(retained.streamOwner)) continue;
            if (isNear(player, retained, radius)
                    && ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) {
                Set<Integer> sent = NEARBY_SENT.computeIfAbsent(
                        player.getUUID(), ignored -> new HashSet<>());
                if (sent.add(retained.entityId)) {
                    sendRetained(player, retained, sender.server.getTickCount());
                } else {
                    ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), frame);
                }
            }
        }
    }

    // Hand a body over when its owner leaves or the victim returns to range. Observers are told
    // nothing: the stream they play back is continuous across the handover.
    private static void reelectStreamOwners(MinecraftServer server, int now) {
        if (now - lastOwnerElectionTick < OWNER_ELECTION_INTERVAL_TICKS) return;
        lastOwnerElectionTick = now;
        for (RetainedRagdoll retained : RETAINED.values()) {
            electStreamOwner(server, retained);
        }
    }

    private static void sendOwnership(ServerPlayer player, int entityId, boolean owner) {
        if (!ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) return;
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RagdollStreamOwnerPacket(entityId, owner));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 10 != 0) return;

        MinecraftServer server = player.server;
        int now = server.getTickCount();
        cleanup(now);
        reelectStreamOwners(server, now);

        double radius = syncRadius(server);
        Set<Integer> previous = NEARBY_SENT.getOrDefault(player.getUUID(), Set.of());
        Set<Integer> currentlyNear = new HashSet<>();
        for (RetainedRagdoll retained : RETAINED.values()) {
            if (now >= retained.expiresTick || !isNear(player, retained, radius)) continue;
            boolean isOwner = player.getUUID().equals(retained.streamOwner);
            boolean hasOwnerPose = retained.latestStreamTransforms != null
                    || retained.settledTransforms != null;
            if (!isOwner && !hasOwnerPose) continue;
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
        if (!sender.getUUID().equals(retained.streamOwner)) return;

        double radius = syncRadius(sender.server);
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
            retained.latestStreamTransforms = null;
            retained.impulseRevision = Math.max(retained.impulseRevision, revision);
        }
    }

    public static void remove(int entityId) {
        RETAINED.remove(entityId);
        for (Set<Integer> sent : NEARBY_SENT.values()) sent.remove(entityId);
    }

    private static void sendRetained(ServerPlayer player, RetainedRagdoll retained, int now) {
        if (!ModNetwork.CHANNEL.isRemotePresent(player.connection.connection)) return;
        sendOwnership(player, retained.entityId,
                player.getUUID().equals(retained.streamOwner));
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), retained.spawnPacket);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RagdollStatePacket(
                        retained.entityId,
                        copyPose(retained.settledTransforms),
                        retained.impulseRevision,
                        Math.max(0, now - retained.createdTick),
                        retained.settledTransforms != null));
        RagdollTransform[] latest = retained.latestStreamTransforms;
        if (retained.settledTransforms == null && latest != null) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RagdollStreamPacket(
                            retained.entityId, retained.streamSequence,
                            retained.streamSampleTick, copyPose(latest)));
        }
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
        RagdollTransform[] copy = new RagdollTransform[RagdollTransform.MAX_PARTS];
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
        RagdollTransform[] copy = new RagdollTransform[RagdollTransform.MAX_PARTS];
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
        UUID uuid = event.getEntity().getUUID();
        NEARBY_SENT.remove(uuid);
        // Drop ownership immediately rather than waiting for the next election pass, so a
        // body whose owner just quit is re-homed on the very next tick instead of hanging.
        for (RetainedRagdoll retained : RETAINED.values()) {
            if (uuid.equals(retained.streamOwner)) retained.streamOwner = null;
        }
        lastOwnerElectionTick = Integer.MIN_VALUE;
    }
}
