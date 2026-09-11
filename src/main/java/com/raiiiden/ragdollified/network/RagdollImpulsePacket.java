package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.api.RagdollImpulse;
import com.raiiiden.ragdollified.api.RagdollifiedServerApi;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

// Punch sync: client reports ragdoll, part, and impulse; the server rebroadcasts it to every
// other client so they all see the same push.
public class RagdollImpulsePacket {

    private final int ragdollId;
    private final int partIndex;
    private final float impulseX, impulseY, impulseZ;
    private final int revision;
    private final boolean apply;
    // World-space impact point, the body's pivot for the impulse; NaN means use the part centre.
    private final double impactX, impactY, impactZ;
    // Attack damage behind the blow. Carried for addons that react to how hard something was hit
    // rather than merely that it was; the core does not read it.
    private final float sourceDamage;

    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();

    // Max plausible click rate per client; pushes past it are dropped as scripted.
    private static final int MAX_IMPULSES_PER_SECOND = 20;
    private static final java.util.Map<java.util.UUID, long[]> RATE_LIMIT =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ) {
        this(ragdollId, partIndex, impulseX, impulseY, impulseZ, 0, true,
                Double.NaN, Double.NaN, Double.NaN, 0f);
    }

    public RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ,
                                Vec3 impactPoint, float sourceDamage) {
        this(ragdollId, partIndex, impulseX, impulseY, impulseZ, 0, true,
                impactPoint == null ? Double.NaN : impactPoint.x,
                impactPoint == null ? Double.NaN : impactPoint.y,
                impactPoint == null ? Double.NaN : impactPoint.z,
                sourceDamage);
    }

    private RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ,
                                 int revision, boolean apply,
                                 double impactX, double impactY, double impactZ, float sourceDamage) {
        this.ragdollId = ragdollId;
        this.partIndex = partIndex;
        this.impulseX = impulseX;
        this.impulseY = impulseY;
        this.impulseZ = impulseZ;
        this.revision = revision;
        this.apply = apply;
        this.impactX = impactX;
        this.impactY = impactY;
        this.impactZ = impactZ;
        this.sourceDamage = sourceDamage;
    }

    public static void encode(RagdollImpulsePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.ragdollId);
        buf.writeInt(msg.partIndex);
        buf.writeFloat(msg.impulseX);
        buf.writeFloat(msg.impulseY);
        buf.writeFloat(msg.impulseZ);
        buf.writeInt(msg.revision);
        buf.writeBoolean(msg.apply);
        buf.writeDouble(msg.impactX);
        buf.writeDouble(msg.impactY);
        buf.writeDouble(msg.impactZ);
        buf.writeFloat(msg.sourceDamage);
    }

    public static RagdollImpulsePacket decode(FriendlyByteBuf buf) {
        int ragdollId = buf.readInt();
        int partIndex = buf.readInt();
        float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        int revision = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
        boolean apply = buf.readableBytes() > 0 ? buf.readBoolean() : true;
        double ix = Double.NaN, iy = Double.NaN, iz = Double.NaN;
        float damage = 0f;
        if (buf.readableBytes() >= Double.BYTES * 3) {
            ix = buf.readDouble();
            iy = buf.readDouble();
            iz = buf.readDouble();
        }
        if (buf.readableBytes() >= Float.BYTES) damage = buf.readFloat();
        return new RagdollImpulsePacket(ragdollId, partIndex, x, y, z, revision, apply, ix, iy, iz, damage);
    }

    public static void handle(RagdollImpulsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();

            if (sender != null) {
                handleServer(sender, msg);
            } else {
                // Client received from server: apply impulse locally
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleServer(ServerPlayer sender, RagdollImpulsePacket msg) {
        RagdollPart part = RagdollPart.byIndex(msg.partIndex);
        if (part == null
                || !Float.isFinite(msg.impulseX) || !Float.isFinite(msg.impulseY)
                || !Float.isFinite(msg.impulseZ)) return;
        double magnitudeSq = msg.impulseX * msg.impulseX + msg.impulseY * msg.impulseY
                + msg.impulseZ * msg.impulseZ;
        if (magnitudeSq > 50.0 * 50.0) return;
        if (!Float.isFinite(msg.sourceDamage) || msg.sourceDamage < 0f) return;

        Vec3 impactPoint = resolveImpact(msg);
        // Reject pushes claimed on bodies far from the sender (desync or crafted packet).
        if (impactPoint != null) {
            double reach = Math.max(16.0, RagdollifiedServerApi.getPhysicsDistance());
            if (sender.position().distanceToSqr(impactPoint) > reach * reach) return;
        }
        if (!allow(sender)) return;

        int revision = SERVER_SEQUENCE.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
        ServerRagdollSyncManager.invalidateSettledPose(sender, msg.ragdollId, revision);
        // Addons holding a settled pose for this body need the new ordering so they can discard
        // a candidate this push invalidates. See RagdollifiedServerApi#addImpulseListener.
        RagdollifiedServerApi.dispatchImpulse(sender, new RagdollImpulse(
                msg.ragdollId, part,
                new Vec3(msg.impulseX, msg.impulseY, msg.impulseZ),
                impactPoint, msg.sourceDamage, revision));

        RagdollImpulsePacket ordered = new RagdollImpulsePacket(msg.ragdollId, msg.partIndex,
                msg.impulseX, msg.impulseY, msg.impulseZ, revision, true,
                msg.impactX, msg.impactY, msg.impactZ, msg.sourceDamage);

        // Every modded client, sender included, applies the server-ordered packet once, giving one order.
        // Sent only to players within physics distance of the body.
        if (impactPoint != null) {
            double radius = Math.max(32.0, RagdollifiedServerApi.getPhysicsDistance());
            ModNetwork.CHANNEL.send(PacketDistributor.NEAR.with(
                    () -> new PacketDistributor.TargetPoint(
                            impactPoint.x, impactPoint.y, impactPoint.z, radius,
                            sender.level().dimension())), ordered);
            return;
        }
        // No impact point means no radius to send inside, so the old behaviour stands.
        for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ordered);
        }
    }

    private static Vec3 resolveImpact(RagdollImpulsePacket msg) {
        if (!Double.isFinite(msg.impactX) || !Double.isFinite(msg.impactY) || !Double.isFinite(msg.impactZ)) {
            return null;
        }
        return new Vec3(msg.impactX, msg.impactY, msg.impactZ);
    }

    private static boolean allow(ServerPlayer sender) {
        long now = System.currentTimeMillis();
        long[] window = RATE_LIMIT.computeIfAbsent(sender.getUUID(), id -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] >= 1000L) {
                window[0] = now;
                window[1] = 0;
            }
            if (window[1] >= MAX_IMPULSES_PER_SECOND) return false;
            window[1]++;
            return true;
        }
    }

    // Drop rate-limit bookkeeping for a player who has left. Called on logout.
    public static void forget(java.util.UUID playerId) {
        RATE_LIMIT.remove(playerId);
    }

    private static void handleClient(RagdollImpulsePacket msg) {
        // Enqueue for the physics worker thread; applyImpulse mutates physics bodies
        // and would race the physics tick if called from this network/main thread.
        ClientRagdollManager.enqueueImpulse(msg.ragdollId, msg.partIndex,
                msg.impulseX, msg.impulseY, msg.impulseZ, msg.revision, msg.apply,
                msg.impactX, msg.impactY, msg.impactZ);
    }
}
