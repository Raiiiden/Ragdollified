package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Owners simulate; observers play back the owner's poses.
public class RagdollStreamOwnerPacket {
    private final int entityId;
    private final boolean owner;

    public RagdollStreamOwnerPacket(int entityId, boolean owner) {
        this.entityId = entityId;
        this.owner = owner;
    }

    public static void encode(RagdollStreamOwnerPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeBoolean(msg.owner);
    }

    public static RagdollStreamOwnerPacket decode(FriendlyByteBuf buf) {
        return new RagdollStreamOwnerPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(RagdollStreamOwnerPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientRagdollManager.enqueueStreamOwnership(msg.entityId, msg.owner)));
        context.setPacketHandled(true);
    }
}
