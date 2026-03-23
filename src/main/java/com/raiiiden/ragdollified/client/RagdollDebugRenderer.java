package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RagdollDebugRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Only render when F3+B hitboxes are enabled — same as prototype
        if (!Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) return;

        // Use AFTER_ENTITIES like prototype does
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partial = mc.getFrameTime();

        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());

        RenderSystem.disableCull();

        // Read from client-side DeathRagdollManager (same pattern as prototype RagdollManager)
        for (RagdollManager.ClientRagdoll rag : DeathRagdollManager.getAll()) {
            if (rag == null || !rag.isActive()) continue;

            for (Map.Entry<RagdollPart, RagdollTransform> entry : rag.getAllPartsInterpolated(partial).entrySet()) {
                RagdollPart part = entry.getKey();
                RagdollTransform t = entry.getValue();
                if (t == null) continue;

                Vector3f pos = new Vector3f(t.position.x, t.position.y, t.position.z);
                Quaternionf rot = new Quaternionf(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w);

                drawDebugBox(poseStack, buffer, camera, pos, rot, getHalfExtentsForPart(part), part.index);
            }
        }

        bufferSource.endBatch(RenderType.lines());
        RenderSystem.enableCull();
    }

    /** Half-extents match the physics body sizes in DeathRagdollPhysics */
    private static Vector3f getHalfExtentsForPart(RagdollPart part) {
        return switch (part) {
            case HEAD      -> new Vector3f(0.2f,  0.2f,  0.2f);
            case TORSO     -> new Vector3f(0.25f, 0.4f,  0.15f);
            case LEFT_ARM,
                 RIGHT_ARM -> new Vector3f(0.1f,  0.35f, 0.1f);
            case LEFT_LEG,
                 RIGHT_LEG -> new Vector3f(0.15f, 0.45f, 0.15f);
        };
    }

    private static void drawDebugBox(PoseStack poseStack, VertexConsumer buffer, Vec3 camPos,
                                     Vector3f pos, Quaternionf rotation, Vector3f halfExtents, int colorIndex) {
        poseStack.pushPose();
        poseStack.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
        poseStack.mulPose(rotation);

        float[][] colors = {
                {0f, 0f, 1f},   // TORSO  - Blue
                {1f, 0f, 0f},   // HEAD   - Red
                {0f, 1f, 0f},   // L_LEG  - Green
                {1f, 1f, 0f},   // R_LEG  - Yellow
                {1f, 0f, 1f},   // L_ARM  - Magenta
                {0f, 1f, 1f},   // R_ARM  - Cyan
        };
        float[] c = colors[colorIndex % colors.length];

        Vector3f[] corners = new Vector3f[]{
                new Vector3f(-halfExtents.x, -halfExtents.y, -halfExtents.z),
                new Vector3f(-halfExtents.x, -halfExtents.y,  halfExtents.z),
                new Vector3f(-halfExtents.x,  halfExtents.y, -halfExtents.z),
                new Vector3f(-halfExtents.x,  halfExtents.y,  halfExtents.z),
                new Vector3f( halfExtents.x, -halfExtents.y, -halfExtents.z),
                new Vector3f( halfExtents.x, -halfExtents.y,  halfExtents.z),
                new Vector3f( halfExtents.x,  halfExtents.y, -halfExtents.z),
                new Vector3f( halfExtents.x,  halfExtents.y,  halfExtents.z)
        };

        int[][] edges = {
                {0,1},{0,2},{1,3},{2,3},
                {4,5},{4,6},{5,7},{6,7},
                {0,4},{1,5},{2,6},{3,7}
        };

        var matrix = poseStack.last().pose();
        for (int[] e : edges) {
            Vector3f a = corners[e[0]];
            Vector3f b = corners[e[1]];
            buffer.vertex(matrix, a.x, a.y, a.z).color(c[0], c[1], c[2], 1f).normal(0, 1, 0).endVertex();
            buffer.vertex(matrix, b.x, b.y, b.z).color(c[0], c[1], c[2], 1f).normal(0, 1, 0).endVertex();
        }

        poseStack.popPose();
    }
}