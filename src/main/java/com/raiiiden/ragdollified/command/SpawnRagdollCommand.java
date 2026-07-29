package com.raiiiden.ragdollified.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.network.PacketDistributor;

public class SpawnRagdollCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("spawnragdoll")
                        .requires(source -> source.hasPermission(2))
                        .executes(SpawnRagdollCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (source.getEntity() instanceof ServerPlayer player) {
            RagdollSpawnPacket packet = new RagdollSpawnPacket(
                    player.getId(),
                    true,
                    net.minecraft.world.entity.EntityType.getKey(player.getType()).toString(),
                    1.0f,
                    player.getUUID().toString(),
                    player.getName().getString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getVisualRotationYInDegrees(), player.getXRot(),
                    0, 2.0, 0, // Small upward velocity
                    player.getPose() == Pose.SWIMMING,
                    player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                    player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                    player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                    player.getItemBySlot(EquipmentSlot.FEET).copy()
            );

            ModNetwork.CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), packet);

            source.sendSuccess(
                    () -> Component.literal("Spawned death ragdoll at your position"),
                    true
            );
            return 1;
        }

        source.sendFailure(Component.literal("This command must be run by a player"));
        return 0;
    }
}
