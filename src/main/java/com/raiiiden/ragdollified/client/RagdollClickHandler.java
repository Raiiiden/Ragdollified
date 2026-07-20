package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollImpulsePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.vecmath.Vector3f;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RagdollClickHandler {

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return;

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(look.scale(5.0));

        Vector3f from = new Vector3f((float) eyePos.x, (float) eyePos.y, (float) eyePos.z);
        Vector3f to = new Vector3f((float) endPos.x, (float) endPos.y, (float) endPos.z);

        // Pure snapshot-based raycast — no jbullet calls from the render thread.
        // findHitPart reads the published TransformSnapshot, so it's race-free.
        // We pick the closest-by-torso ragdoll among those whose findHitPart returned a hit.
        ClientRagdoll hitRagdoll = null;
        RagdollPart hitPart = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ClientRagdoll ragdoll : ClientRagdollManager.getAll()) {
            RagdollPart candidate = ragdoll.findHitPart(from, to);
            if (candidate == null) continue;
            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null) continue;
            double dx = snap.cachedTorsoPos.x - from.x;
            double dy = snap.cachedTorsoPos.y - from.y;
            double dz = snap.cachedTorsoPos.z - from.z;
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                hitRagdoll = ragdoll;
                hitPart = candidate;
            }
        }

        if (hitRagdoll == null || hitPart == null) return;

        // Calculate impulse direction (from player toward hit point)
        Vector3f dir = new Vector3f(
                (float)(endPos.x - eyePos.x),
                (float)(endPos.y - eyePos.y),
                (float)(endPos.z - eyePos.z)
        );
        dir.normalize();

        // Scale by attack cooldown
        float attackCooldown = mc.player.getAttackStrengthScale(0f);
        float baseStrength = 10f * attackCooldown;

        // Extra upward kick for head hits
        if (hitPart == RagdollPart.HEAD) {
            baseStrength *= 1.2f;
            dir.y += 0.2f;
        }

        // Add slight upward component so hits push ragdolls up rather than into the ground
        dir.y += 0.15f;
        dir.normalize();
        dir.scale(baseStrength);

        // On a modded server, wait for its sequenced broadcast so every observer applies
        // simultaneous pushes in exactly the same order. On a vanilla server there is no
        // broadcast, so retain the local-only interaction behavior.
        if (!com.raiiiden.ragdollified.config.RagdollifiedConfig.hasServerSnapshot()) {
            ClientRagdollManager.enqueueImpulse(
                    hitRagdoll.getId(), hitPart.index, dir.x, dir.y, dir.z);
        }

        // Send to server for broadcast to other players (synchronous from main thread).
        try {
            ModNetwork.CHANNEL.sendToServer(new RagdollImpulsePacket(
                    hitRagdoll.getId(), hitPart.index, dir.x, dir.y, dir.z));
        } catch (Exception ignored) {
            // Server might not have the mod — that's fine
        }

        Ragdollified.LOGGER.debug("Client punched ragdoll {} part {}", hitRagdoll.getId(), hitPart);
    }
}
