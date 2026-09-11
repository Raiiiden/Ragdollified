package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.math.Axis;

import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class ClientMobPoseCapture {

    private ClientMobPoseCapture() {
    }

    // Open a capture for an entity about to be drawn. The pose stack is still at the entity origin
    // here, which is the frame every captured part is measured against.
    public static void beginRenderCapture(LivingEntity entity, MobModelHelper.ModelType modelType,
                                          EntityModel<?> model, PoseStack poseStack) {
        Map<ModelPart, RagdollPart> mapping = PosePartMapper.map(model, modelType);
        if (mapping == null) return;
        PoseCaptureSession.begin(entity.getId(), mapping, poseStack, usePlacement(model, mapping, modelType));
    }

    // Pose an entity not drawn recently by rendering its model into a discarding vertex consumer,
    // keeping resource pack and animation mod changes. Returns true when a pose was stored.
    public static boolean captureNow(LivingEntity entity, MobModelHelper.ModelType modelType) {
        // A swimming body is pitched by the renderer rather than by its parts, and the spawn path
        // already lays that case down flat; capturing it here would fight that.
        if (entity.getPose() == Pose.SWIMMING) return false;

        try {
            EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) return false;

            EntityModel<?> model = living.getModel();
            Map<ModelPart, RagdollPart> mapping = PosePartMapper.map(model, modelType);
            if (mapping == null) return false;

            poseModel(model, entity);

            PoseStack poseStack = new PoseStack();
            // LivingEntityRenderer's pre-model transforms: body yaw, model-space flip, model-origin drop.
            // Renderer-level scaling is left out.
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - entity.getVisualRotationYInDegrees()));
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0.0f, -1.501f, 0.0f);

            // The session's reference frame is the entity origin, which is where an untouched pose
            // stack already sits; the prefix above rides on the stack the model is rendered with.
            PoseCaptureSession.begin(entity.getId(), mapping, new PoseStack(),
                    usePlacement(model, mapping, modelType));
            try {
                model.renderToBuffer(poseStack, DiscardingVertexConsumer.INSTANCE,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
            } finally {
                PoseCaptureSession.commit();
            }
            return MobPoseCapture.getPose(entity.getId()) != null;
        } catch (Exception e) {
            // A third-party model that will not render outside a real frame must not stop the
            // ragdoll; the spawn path falls back to the authored pose.
            PoseCaptureSession.abort();
            return false;
        }
    }

    // Whether a drawn part may place a body too: only on square rigs with vanilla geometry.
    // See PosePartMapper.hasVanillaGeometry.
    private static boolean usePlacement(EntityModel<?> model, Map<ModelPart, RagdollPart> mapping,
                                        MobModelHelper.ModelType modelType) {
        return PosePartMapper.supportsTransforms(modelType)
                && PosePartMapper.hasVanillaGeometry(model, mapping);
    }

    @SuppressWarnings("unchecked")
    private static <T extends LivingEntity> void poseModel(EntityModel<?> model, T entity) {
        // The same arguments LivingEntityRenderer passes, read whole rather than interpolated: this
        // runs on a death tick, where the tick values are the ones that matter.
        float bodyYaw = entity.getVisualRotationYInDegrees();
        float headYaw = Mth.wrapDegrees(entity.getYHeadRot() - bodyYaw);
        ((EntityModel<T>) model).prepareMobModel(entity, entity.walkAnimation.position(),
                entity.walkAnimation.speed(), 1.0f);
        ((EntityModel<T>) model).setupAnim(entity, entity.walkAnimation.position(),
                entity.walkAnimation.speed(), entity.tickCount, headYaw, entity.getXRot());
    }

    // Drives the model's real render path while emitting nothing.
    private static final class DiscardingVertexConsumer implements VertexConsumer {
        static final DiscardingVertexConsumer INSTANCE = new DiscardingVertexConsumer();

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
