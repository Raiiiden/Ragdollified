package com.raiiiden.ragdollified.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.raiiiden.ragdollified.DeathRagdollEntity;
import com.raiiiden.ragdollified.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SpawnRagdollCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("spawnragdoll")
                        .requires(source -> source.hasPermission(2)) // OP level 2
                        .executes(SpawnRagdollCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (source.getEntity() instanceof ServerPlayer player) {
            // Create ragdoll from player's current state
            DeathRagdollEntity ragdoll = DeathRagdollEntity.createFromPlayer(
                    player.level(),
                    player
            );

            player.level().addFreshEntity(ragdoll);

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