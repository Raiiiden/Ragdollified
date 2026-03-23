package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollRaycastPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RagdollClickHandler {

    /**
     * Fire a raycast packet to the server whenever the player attacks (left-click).
     * The server will check if the ray hits any ragdoll physics body and apply an impulse.
     */
    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return; // GUI open

        Vec3 start = mc.player.getEyePosition();
        Vec3 look   = mc.player.getLookAngle();
        Vec3 end    = start.add(look.scale(5.0)); // 5-block reach

        ModNetwork.CHANNEL.sendToServer(new RagdollRaycastPacket(start, end));
    }
}