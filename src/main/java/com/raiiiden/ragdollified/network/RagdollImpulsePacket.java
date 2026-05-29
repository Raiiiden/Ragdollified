package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Vector3f;
import java.util.function.Supplier;

/**
 * Punch sync packet.
 * Client→Server: "I punched ragdoll X, part Y, impulse Z"
 * Server broadcasts to all OTHER clients so they see the same push.
 */
public class RagdollImpulsePacket {

    private final int ragdollId;
    private final int partIndex;
    private final float impulseX, impulseY, impulseZ;

    public RagdollImpulsePacket(int ragdollId, int partIndex, float impulseX, float impulseY, float impulseZ) {
        this.ragdollId = ragdollId;
        this.partIndex = partIndex;
        this.impulseX = impulseX;
        this.impulseY = impulseY;
        this.impulseZ = impulseZ;
    }

    public static void encode(RagdollImpulsePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.ragdollId);
        buf.writeInt(msg.partIndex);
        buf.writeFloat(msg.impulseX);
        buf.writeFloat(msg.impulseY);
        buf.writeFloat(msg.impulseZ);
    }

    public static RagdollImpulsePacket decode(FriendlyByteBuf buf) {
        return new RagdollImpulsePacket(
                buf.readInt(), buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()
        );
    }

    public static void handle(RagdollImpulsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();

            if (sender != null) {
                // Server received from client — broadcast to all OTHER clients
                ModNetwork.CHANNEL.send(
                        PacketDistributor.ALL.noArg(),
                        msg
                );
            } else {
                // Client received from server — apply impulse locally
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(RagdollImpulsePacket msg) {
        // Enqueue for the physics worker thread — applyImpulse mutates jbullet bodies
        // and would race the physics tick if called from this network/main thread.
        ClientRagdollManager.enqueueImpulse(msg.ragdollId, msg.partIndex,
                msg.impulseX, msg.impulseY, msg.impulseZ);
    }
}
