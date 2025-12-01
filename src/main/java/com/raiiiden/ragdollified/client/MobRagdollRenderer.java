package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.MobRagdollEntity;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Creeper;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MobRagdollRenderer extends EntityRenderer<MobRagdollEntity> {
    // Use wildcard types to avoid generic bound issues
    private final ZombieModel<?> zombieModel;
    private final SkeletonModel<?> skeletonModel;
    private final CreeperModel<?> creeperModel;

    // Use same offsets as player for humanoid mobs
    private static final Vector3f[] headoff = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, -6.0f/16, 0.0f)
    };
    private static final Vector3f[] torsoff = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, -6.0f/16, 0.0f)
    };
    private static final Vector3f[] larmoff = new Vector3f[]{
            new Vector3f(3.8F, 4.0f, 0.0f), new Vector3f(1F/16, -7.0f/16, 0.0f)
    };
    private static final Vector3f[] rarmoff = new Vector3f[]{
            new Vector3f(-3.8F, 4.0f, 0.0f), new Vector3f(-1F/16, -7.0f/16, 0.0f)
    };
    private static final Vector3f[] llegoff = new Vector3f[]{
            new Vector3f(1.9f, 5.5f, 0.0f), new Vector3f(0.0f, 0f/16, 0.0f)
    };
    private static final Vector3f[] rlegoff = new Vector3f[]{
            new Vector3f(-1.9f, 5.5f, 0.0f), new Vector3f(0.00f, 0f/16, 0.0f),
    };

    public MobRagdollRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.zombieModel = new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE));
        this.skeletonModel = new SkeletonModel<>(context.bakeLayer(ModelLayers.SKELETON));
        this.creeperModel = new CreeperModel<>(context.bakeLayer(ModelLayers.CREEPER));
        Ragdollified.LOGGER.info("MobRagdollRenderer created!");
    }

    @Override
    public void render(MobRagdollEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {

        RagdollManager.ClientRagdoll rag = DeathRagdollManager.get(entity.getId());

        if (rag == null || !rag.isActive()) {
            return;
        }

        String mobType = entity.getMobType();

        // Route to appropriate render method based on mob type
        if (mobType.contains("zombie")) {
            renderZombie(entity, rag, partialTick, poseStack, buffer, packedLight);
        } else if (mobType.contains("skeleton")) {
            renderSkeleton(entity, rag, partialTick, poseStack, buffer, packedLight);
        } else if (mobType.contains("creeper")) {
            renderCreeper(entity, rag, partialTick, poseStack, buffer, packedLight);
        }
        // Add more mob types here
    }

    private void renderZombie(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                              float partialTick, PoseStack poseStack,
                              MultiBufferSource buffer, int light) {

        RagdollTransform torso = rag.getPartInterpolated(RagdollPart.TORSO, partialTick);
        RagdollTransform head = rag.getPartInterpolated(RagdollPart.HEAD, partialTick);
        RagdollTransform larm = rag.getPartInterpolated(RagdollPart.LEFT_ARM, partialTick);
        RagdollTransform rarm = rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform lleg = rag.getPartInterpolated(RagdollPart.LEFT_LEG, partialTick);
        RagdollTransform rleg = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.head, head, torso, headoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.leftLeg, lleg, torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.rightLeg, rleg, torso, rlegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.leftArm, larm, torso, larmoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, zombieModel.rightArm, rarm, torso, rarmoff, light);

        poseStack.popPose();
    }

    private void renderSkeleton(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                                float partialTick, PoseStack poseStack,
                                MultiBufferSource buffer, int light) {

        RagdollTransform torso = rag.getPartInterpolated(RagdollPart.TORSO, partialTick);
        RagdollTransform head = rag.getPartInterpolated(RagdollPart.HEAD, partialTick);
        RagdollTransform larm = rag.getPartInterpolated(RagdollPart.LEFT_ARM, partialTick);
        RagdollTransform rarm = rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform lleg = rag.getPartInterpolated(RagdollPart.LEFT_LEG, partialTick);
        RagdollTransform rleg = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.head, head, torso, headoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.leftLeg, lleg, torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.rightLeg, rleg, torso, rlegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.leftArm, larm, torso, larmoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, skeletonModel.rightArm, rarm, torso, rarmoff, light);

        poseStack.popPose();
    }

    private void renderCreeper(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                               float partialTick, PoseStack poseStack,
                               MultiBufferSource buffer, int light) {

        RagdollTransform torso = rag.getPartInterpolated(RagdollPart.TORSO, partialTick);
        RagdollTransform head  = rag.getPartInterpolated(RagdollPart.HEAD, partialTick);

        // FOUR leg transforms
        RagdollTransform leftFrontT  = rag.getPartInterpolated(RagdollPart.LEFT_ARM, partialTick);
        RagdollTransform rightFrontT = rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform leftHindT   = rag.getPartInterpolated(RagdollPart.LEFT_LEG, partialTick);
        RagdollTransform rightHindT  = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        ModelPart root = creeperModel.root();

        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");

        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");

        // Body
        renderHumanoidPart(poseStack, vertexConsumer, body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, headPart, head, torso, headoff, light);

        // Back legs
        renderHumanoidPart(poseStack, vertexConsumer, leftHind,  leftHindT,  torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, rightHind, rightHindT, torso, rlegoff, light);

        // Front legs
        renderHumanoidPart(poseStack, vertexConsumer, leftFront,  leftFrontT,  torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, rightFront, rightFrontT, torso, rlegoff, light);

        poseStack.popPose();
    }




    private void renderHumanoidPart(PoseStack poseStack, VertexConsumer vertexConsumer,
                                    ModelPart part, RagdollTransform transform,
                                    RagdollTransform torso, Vector3f[] pivot, int light) {
        if (transform == null) return;

        part.setPos(pivot[0].x, pivot[0].y, pivot[0].z);
        poseStack.pushPose();

        Quaternionf torsoRot = new Quaternionf(
                torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w
        );

        Vector3f rotatedPivot = new Vector3f(pivot[1]);
        torsoRot.transform(rotatedPivot);

        Quaternionf q = new Quaternionf(
                transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w
        );

        poseStack.translate(-rotatedPivot.x, -rotatedPivot.y, -rotatedPivot.z);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        part.render(poseStack, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(MobRagdollEntity entity) {
        String mobType = entity.getMobType();

        if (mobType.contains("zombie")) {
            return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        } else if (mobType.contains("skeleton")) {
            return new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        } else if (mobType.contains("creeper")) {
            return new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");
        }

        return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    }
}