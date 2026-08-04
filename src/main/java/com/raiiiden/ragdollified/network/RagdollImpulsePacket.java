package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.server.PendingCorpse;
import com.raiiiden.ragdollified.server.PendingCorpseStore;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Vector3f;
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
    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();

    public RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ) {
        this(ragdollId, partIndex, impulseX, impulseY, impulseZ, 0, true);
    }

    private RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ,
                                 int revision, boolean apply) {
        this.ragdollId = ragdollId;
        this.partIndex = partIndex;
        this.impulseX = impulseX;
        this.impulseY = impulseY;
        this.impulseZ = impulseZ;
        this.revision = revision;
        this.apply = apply;
    }

    public static void encode(RagdollImpulsePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.ragdollId);
        buf.writeInt(msg.partIndex);
        buf.writeFloat(msg.impulseX);
        buf.writeFloat(msg.impulseY);
        buf.writeFloat(msg.impulseZ);
        buf.writeInt(msg.revision);
        buf.writeBoolean(msg.apply);
    }

    public static RagdollImpulsePacket decode(FriendlyByteBuf buf) {
        int ragdollId = buf.readInt();
        int partIndex = buf.readInt();
        float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        int revision = buf.readableBytes() >= Integer.BYTES ? buf.readInt() : 0;
        boolean apply = buf.readableBytes() > 0 ? buf.readBoolean() : true;
        return new RagdollImpulsePacket(ragdollId, partIndex, x, y, z, revision, apply);
    }

    public static void handle(RagdollImpulsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();

            if (sender != null) {
                handleServer(sender, msg);
            } else {
                // Client received from server — apply impulse locally
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleServer(ServerPlayer sender, RagdollImpulsePacket msg) {
        if (RagdollPart.byIndex(msg.partIndex) == null
                || !Float.isFinite(msg.impulseX) || !Float.isFinite(msg.impulseY)
                || !Float.isFinite(msg.impulseZ)) return;
        double magnitudeSq = msg.impulseX * msg.impulseX + msg.impulseY * msg.impulseY
                + msg.impulseZ * msg.impulseZ;
        if (magnitudeSq > 50.0 * 50.0) return;

        int revision = SERVER_SEQUENCE.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
        ServerRagdollSyncManager.invalidateSettledPose(sender, msg.ragdollId, revision);
        PendingCorpseStore store = PendingCorpseStore.get(sender.server.overworld());
        for (PendingCorpse pending : store.pending.values()) {
            if (pending.deathEntityId == msg.ragdollId && pending.dimension != null
                    && pending.dimension.equals(sender.level().dimension())) {
                pending.impulseRevision = revision;
                pending.invalidateSettleCandidate();
                store.setDirty();
                break;
            }
        }

        // Every modded client, including the sender, applies the server-ordered packet once.
        // This gives concurrent pushes one identical total order on every physics simulation.
        for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RagdollImpulsePacket(msg.ragdollId, msg.partIndex,
                            msg.impulseX, msg.impulseY, msg.impulseZ, revision, true));
        }
    }

    private static void handleClient(RagdollImpulsePacket msg) {
        // Enqueue for the physics worker thread — applyImpulse mutates jbullet bodies
        // and would race the physics tick if called from this network/main thread.
        ClientRagdollManager.enqueueImpulse(msg.ragdollId, msg.partIndex,
                msg.impulseX, msg.impulseY, msg.impulseZ, msg.revision, msg.apply);
    }
}
