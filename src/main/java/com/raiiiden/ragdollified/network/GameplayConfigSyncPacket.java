package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Server-to-client runtime snapshot of the server-authoritative COMMON gameplay config
public final class GameplayConfigSyncPacket {
    private final CompoundTag snapshot;

    public GameplayConfigSyncPacket(CompoundTag snapshot) {
        this.snapshot = snapshot == null ? new CompoundTag() : snapshot.copy();
    }

    public static void encode(GameplayConfigSyncPacket message, FriendlyByteBuf buffer) {
        buffer.writeNbt(message.snapshot);
    }

    public static GameplayConfigSyncPacket decode(FriendlyByteBuf buffer) {
        CompoundTag snapshot = buffer.readNbt();
        return new GameplayConfigSyncPacket(snapshot);
    }

    public static void handle(GameplayConfigSyncPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RagdollifiedConfig.applyServerSnapshot(message.snapshot)));
        context.get().setPacketHandled(true);
    }
}
