package com.raiiiden.ragdollified.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.physics.PhysicsBackends;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Reports which physics engine is actually running client-side, since a silent JBullet fallback
// can otherwise pass for Jolt working.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class PhysicsInfoCommand {

    private PhysicsInfoCommand() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ragdollified")
                        .then(Commands.literal("physics")
                                .executes(PhysicsInfoCommand::report))
                        .then(Commands.literal("motion")
                                .executes(PhysicsInfoCommand::motion))
        );
    }

    // What the nearest ragdoll is doing, part by part. Answers the one question watching a corpse
    // cannot: whether a limb that is not falling is being held still or was never moving.
    private static int motion(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        com.raiiiden.ragdollified.client.ClientRagdoll nearest =
                com.raiiiden.ragdollified.client.ClientRagdollManager.nearestRagdoll(source.getPosition());
        if (nearest == null) {
            source.sendSuccess(() -> Component.literal("Ragdollified: no ragdolls loaded.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        String report = nearest.motionReport();
        source.sendSuccess(() -> Component.literal("Ragdollified motion: " + report)
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String configured = RagdollifiedConfig.getPhysicsEngine();
        String live = PhysicsBackends.resolvedEngine();

        if (live == null) {
            source.sendSuccess(() -> Component.literal(
                    "Ragdollified: no physics world yet — configured engine is '" + configured
                            + "'. It is chosen when the first ragdoll spawns.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        boolean fellBack = !live.equals(configured);
        source.sendSuccess(() -> Component.literal("Ragdollified physics: ")
                .append(Component.literal(live).withStyle(
                        fellBack ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal(fellBack
                                ? "  (configured '" + configured + "', fell back)"
                                : "  (as configured)")
                        .withStyle(ChatFormatting.GRAY)), false);

        if (PhysicsBackends.JOLT.equals(live)) {
            source.sendSuccess(() -> Component.literal(
                            "  solver threads: " + RagdollifiedConfig.getPhysicsSolverThreads())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        String failure = PhysicsBackends.joltFailureReason();
        if (failure != null) {
            source.sendSuccess(() -> Component.literal("  Jolt unavailable: " + failure)
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }
}
