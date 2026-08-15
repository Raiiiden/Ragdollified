package com.raiiiden.ragdollified.api;

import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

public final class RagdollifiedServerApi {
    private RagdollifiedServerApi() {}

    public static void startPlayerRagdoll(ServerPlayer player, Vec3 position,
                                          float bodyYaw, float pitch, int lifetimeTicks) {
        if (player == null || position == null) return;
        RagdollSpawnPacket packet = new RagdollSpawnPacket(
                player.getId(),
                true,
                net.minecraft.world.entity.EntityType.getKey(player.getType()).toString(),
                1.0f,
                player.getUUID().toString(),
                player.getName().getString(),
                position.x, position.y, position.z,
                bodyYaw, pitch,
                0.0, 0.0, 0.0,
                player.getPose() == Pose.SWIMMING,
                player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                player.getItemBySlot(EquipmentSlot.FEET).copy());
        ServerRagdollSyncManager.register(player, packet, lifetimeTicks);
    }

    public static void stopRagdoll(int entityId) {
        ServerRagdollSyncManager.remove(entityId);
    }
}
