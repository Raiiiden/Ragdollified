package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
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

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RagdollDebugRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();

        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        for (ClientRagdoll ragdoll : ClientRagdollManager.getAll()) {
            if (ragdoll == null) continue;
            // Read from the published snapshot — physics runs on a worker thread, so we
            // can't touch cachedTransforms directly.
            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null || snap.destroyed) continue;

            for (RagdollPart part : RagdollPart.values()) {
                int i = part.index;
                if (i >= snap.positions.length || snap.positions[i] == null) continue;

                javax.vecmath.Vector3f sp = snap.positions[i];
                javax.vecmath.Quat4f sr = snap.rotations[i];
                Vector3f pos = new Vector3f(sp.x, sp.y, sp.z);
                Quaternionf rot = new Quaternionf(sr.x, sr.y, sr.z, sr.w);
                Vector3f halfExtents = getHalfExtents(snap, part);

                drawDebugBox(poseStack, buffer, camera, pos, rot, halfExtents, part.index);
            }
        }

        bufferSource.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static Vector3f getHalfExtents(ClientRagdoll.TransformSnapshot snap, RagdollPart part) {
        int i = part.index;
        if (snap.halfExtents != null && i < snap.halfExtents.length && snap.halfExtents[i] != null) {
            javax.vecmath.Vector3f h = snap.halfExtents[i];
            return new Vector3f(h.x, h.y, h.z);
        }
        return new Vector3f(0.1f, 0.1f, 0.1f);
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
