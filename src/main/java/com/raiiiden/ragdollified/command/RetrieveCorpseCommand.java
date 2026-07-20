package com.raiiiden.ragdollified.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.raiiiden.ragdollified.server.CorpseManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /ragdollified retrievecorpse <corpseId> [player]} — OP failsafe (permission level 2) to
 * recover a corpse's contents when it's otherwise unreachable. Gives the items (+ stored XP) to the
 * target player (default = command runner) and erases the corpse. This is the command the Corpse
 * Compass locator screen copies to the clipboard.
 */
public class RetrieveCorpseCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ragdollified")
                        .then(Commands.literal("retrievecorpse")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("corpseId", UuidArgument.uuid())
                                        .executes(ctx -> run(ctx, false))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> run(ctx, true))))
                                .then(Commands.argument("owner", EntityArgument.player())
                                        .then(Commands.literal("lastdeath")
                                                .executes(RetrieveCorpseCommand::runLastDeath)))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean explicitTarget) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        UUID corpseId = UuidArgument.getUuid(ctx, "corpseId");
        ServerPlayer target = explicitTarget
                ? EntityArgument.getPlayer(ctx, "player")
                : source.getPlayerOrException();

        boolean ok = CorpseManager.retrieveByCorpseId(source.getServer(), corpseId, target);
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                    "Retrieved corpse " + corpseId + " to " + target.getName().getString()), true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "No recoverable corpse found with id " + corpseId + "."));
        return 0;
    }

    private static int runLastDeath(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer owner = EntityArgument.getPlayer(ctx, "owner");
        boolean ok = CorpseManager.retrieveLastDeath(source.getServer(), owner.getUUID(), owner);
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                    "Retrieved " + owner.getName().getString() + "'s last unlooted corpse"), true);
            return 1;
        }
        source.sendFailure(Component.literal(
                owner.getName().getString() + " has no recoverable last-death corpse."));
        return 0;
    }
}
