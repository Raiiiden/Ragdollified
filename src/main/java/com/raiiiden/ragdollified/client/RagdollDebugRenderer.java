package com.raiiiden.ragdollified.client;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.raiiiden.ragdollified.MobRagdollEntity;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RagdollDebugRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!RagdollifiedConfig.shouldDebugRenderPhysics()) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            PoseStack poseStack = event.getPoseStack();

            // Find all ragdoll entities
            mc.level.entitiesForRendering().forEach(entity -> {
                if (entity instanceof MobRagdollEntity ragdoll) {
                    if (ragdoll.getPhysics() != null) {
                        renderRagdollDebug(ragdoll, poseStack, camera);
                    }
                }
            });
        }
    }

    private static void renderRagdollDebug(MobRagdollEntity ragdoll, PoseStack poseStack, Vec3 camera) {
        if (ragdoll.getPhysics() == null) return;

        var ragdollParts = ragdoll.getPhysics().ragdollParts;

        for (int i = 0; i < ragdollParts.size(); i++) {
            RigidBody body = ragdollParts.get(i);

            // Get body transform
            Transform transform = new Transform();
            body.getMotionState().getWorldTransform(transform);

            Vector3f pos = transform.origin;
            Quat4f rot = transform.getRotation(new Quat4f());

            // Get box size (only works for BoxShape)
            Vector3f halfExtents = new Vector3f();
            if (body.getCollisionShape() instanceof BoxShape boxShape) {
                boxShape.getHalfExtentsWithoutMargin(halfExtents);
            } else {
                halfExtents.set(0.1f, 0.1f, 0.1f); // Fallback
            }

            // Choose color based on body part
            int color = getColorForPart(i);

            // Render the box
            renderBox(poseStack, camera, pos, rot, halfExtents, color);

            // Render part label
            renderPartLabel(poseStack, camera, pos, i);

            // Render velocity vector
            renderVelocityVector(poseStack, camera, body, pos);
        }
    }

    private static int getColorForPart(int partIndex) {
        return switch (partIndex) {
            case 0 -> 0xFF0000FF; // TORSO - Blue
            case 1 -> 0xFFFF0000; // HEAD - Red
            case 2 -> 0xFF00FF00; // LEFT_LEG - Green
            case 3 -> 0xFFFFFF00; // RIGHT_LEG - Yellow
            case 4 -> 0xFFFF00FF; // LEFT_ARM - Magenta
            case 5 -> 0xFF00FFFF; // RIGHT_ARM - Cyan
            default -> 0xFFFFFFFF; // White
        };
    }

    private static void renderBox(PoseStack poseStack, Vec3 camera,
                                  Vector3f pos, Quat4f rotation,
                                  Vector3f halfExtents, int color) {
        poseStack.pushPose();

        // Translate to world position minus camera
        poseStack.translate(
                pos.x - camera.x,
                pos.y - camera.y,
                pos.z - camera.z
        );

        // Apply rotation
        org.joml.Quaternionf jomlRot = new org.joml.Quaternionf(
                rotation.x, rotation.y, rotation.z, rotation.w
        );
        poseStack.mulPose(jomlRot);

        // Extract color components
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        // Setup rendering
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float x = halfExtents.x;
        float y = halfExtents.y;
        float z = halfExtents.z;

        // Draw 12 edges of the box
        // Bottom face
        addLine(buffer, matrix, -x, -y, -z, x, -y, -z, r, g, b, a);
        addLine(buffer, matrix, x, -y, -z, x, -y, z, r, g, b, a);
        addLine(buffer, matrix, x, -y, z, -x, -y, z, r, g, b, a);
        addLine(buffer, matrix, -x, -y, z, -x, -y, -z, r, g, b, a);

        // Top face
        addLine(buffer, matrix, -x, y, -z, x, y, -z, r, g, b, a);
        addLine(buffer, matrix, x, y, -z, x, y, z, r, g, b, a);
        addLine(buffer, matrix, x, y, z, -x, y, z, r, g, b, a);
        addLine(buffer, matrix, -x, y, z, -x, y, -z, r, g, b, a);

        // Vertical edges
        addLine(buffer, matrix, -x, -y, -z, -x, y, -z, r, g, b, a);
        addLine(buffer, matrix, x, -y, -z, x, y, -z, r, g, b, a);
        addLine(buffer, matrix, x, -y, z, x, y, z, r, g, b, a);
        addLine(buffer, matrix, -x, -y, z, -x, y, z, r, g, b, a);

        tesselator.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    private static void renderPartLabel(PoseStack poseStack, Vec3 camera,
                                        Vector3f pos, int partIndex) {
        String label = switch (partIndex) {
            case 0 -> "TORSO";
            case 1 -> "HEAD";
            case 2 -> "L_LEG";
            case 3 -> "R_LEG";
            case 4 -> "L_ARM";
            case 5 -> "R_ARM";
            default -> "UNKNOWN";
        };

        Minecraft mc = Minecraft.getInstance();
        poseStack.pushPose();

        poseStack.translate(
                pos.x - camera.x,
                pos.y - camera.y + 0.5, // Offset above body
                pos.z - camera.z
        );

        // Make label face camera
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        var font = mc.font;
        float x = -font.width(label) / 2f;

        // Draw label with background
        var matrix = poseStack.last().pose();
        font.drawInBatch(label, x, 0, 0xFFFFFF, false, matrix,
                mc.renderBuffers().bufferSource(),
                net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0x80000000, 15728880);

        poseStack.popPose();
    }

    private static void renderVelocityVector(PoseStack poseStack, Vec3 camera,
                                             RigidBody body, Vector3f pos) {
        Vector3f velocity = new Vector3f();
        body.getLinearVelocity(velocity);

        // Only render if velocity is significant
        if (velocity.length() < 0.1f) return;

        // Scale velocity for visibility
        float scale = 0.5f;
        velocity.scale(scale);

        poseStack.pushPose();
        poseStack.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Draw velocity arrow (bright green)
        addLine(buffer, matrix, 0, 0, 0, velocity.x, velocity.y, velocity.z, 0f, 1f, 0f, 1f);

        // Draw arrowhead
        Vector3f normalized = new Vector3f(velocity);
        normalized.normalize();
        normalized.scale(-0.1f);

        Vector3f perpendicular = new Vector3f(-normalized.z, 0, normalized.x);
        perpendicular.normalize();
        perpendicular.scale(0.05f);

        Vector3f arrowTip = new Vector3f(velocity);
        Vector3f arrowLeft = new Vector3f(arrowTip);
        arrowLeft.add(normalized);
        arrowLeft.add(perpendicular);
        Vector3f arrowRight = new Vector3f(arrowTip);
        arrowRight.add(normalized);
        arrowRight.sub(perpendicular);

        addLine(buffer, matrix, arrowTip.x, arrowTip.y, arrowTip.z,
                arrowLeft.x, arrowLeft.y, arrowLeft.z, 0f, 1f, 0f, 1f);
        addLine(buffer, matrix, arrowTip.x, arrowTip.y, arrowTip.z,
                arrowRight.x, arrowRight.y, arrowRight.z, 0f, 1f, 0f, 1f);

        tesselator.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    private static void addLine(BufferBuilder buffer, Matrix4f matrix,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float r, float g, float b, float a) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
    }
}