package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.MobRagdollEntity;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MobRagdollRenderer extends EntityRenderer<MobRagdollEntity> {
    // VANILLA MODELS - Created fresh from LayerDefinitions to avoid EMF modifications
    private final ZombieModel<?> vanillaZombieModel;
    private final SkeletonModel<?> vanillaSkeletonModel;
    private final CreeperModel<?> vanillaCreeperModel;

    // Armor models for humanoid mobs
    private final HumanoidModel<?> armorInner;
    private final HumanoidModel<?> armorOuter;

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

        // CREATE VANILLA MODELS DIRECTLY FROM LAYER DEFINITIONS
        // This bypasses any EMF modifications
        try {
            // Zombie/Skeleton use HumanoidModel.createMesh (they extend HumanoidModel)
            LayerDefinition humanoidDef = LayerDefinition.create(
                    HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64
            );

            // Creeper has its own createBodyLayer that requires CubeDeformation
            LayerDefinition creeperDef = CreeperModel.createBodyLayer(CubeDeformation.NONE);

            // Create models from definitions (pure vanilla, no EMF)
            this.vanillaZombieModel = new ZombieModel<>(humanoidDef.bakeRoot());
            this.vanillaSkeletonModel = new SkeletonModel<>(humanoidDef.bakeRoot());
            this.vanillaCreeperModel = new CreeperModel<>(creeperDef.bakeRoot());

            Ragdollified.LOGGER.info("Successfully created vanilla models for ragdolls (bypassing EMF)");
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to create vanilla models directly", e);
            throw new RuntimeException("Could not initialize ragdoll renderer", e);
        }

        // Create armor models for humanoid mobs
        this.armorInner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.armorOuter = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
    }

    @Override
    public void render(MobRagdollEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {

        RagdollManager.ClientRagdoll rag = DeathRagdollManager.get(entity.getId());

        if (rag == null || !rag.isActive()) {
            return;
        }

        String mobType = entity.getMobType();

        if (mobType.contains("zombie")) {
            renderZombie(entity, rag, partialTick, poseStack, buffer, packedLight);
        } else if (mobType.contains("skeleton")) {
            renderSkeleton(entity, rag, partialTick, poseStack, buffer, packedLight);
        } else if (mobType.contains("creeper")) {
            renderCreeper(entity, rag, partialTick, poseStack, buffer, packedLight);
        }
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

        // Render mob skin
        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Use vanilla model (never touched by EMF)
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.head, head, torso, headoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.leftLeg, lleg, torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.rightLeg, rleg, torso, rlegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.leftArm, larm, torso, larmoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaZombieModel.rightArm, rarm, torso, rarmoff, light);

        // Render armor
        renderArmor(entity, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);

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

        // Render mob skin
        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Use vanilla model (never touched by EMF)
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.head, head, torso, headoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.leftLeg, lleg, torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.rightLeg, rleg, torso, rlegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.leftArm, larm, torso, larmoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, vanillaSkeletonModel.rightArm, rarm, torso, rarmoff, light);

        // Render armor
        renderArmor(entity, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);

        poseStack.popPose();
    }

    private void renderCreeper(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                               float partialTick, PoseStack poseStack,
                               MultiBufferSource buffer, int light) {

        RagdollTransform torso = rag.getPartInterpolated(RagdollPart.TORSO, partialTick);
        RagdollTransform head  = rag.getPartInterpolated(RagdollPart.HEAD, partialTick);

        RagdollTransform leftFrontT  = rag.getPartInterpolated(RagdollPart.LEFT_ARM, partialTick);
        RagdollTransform rightFrontT = rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform leftHindT   = rag.getPartInterpolated(RagdollPart.LEFT_LEG, partialTick);
        RagdollTransform rightHindT  = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Use vanilla model (never touched by EMF)
        ModelPart root = vanillaCreeperModel.root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");

        renderHumanoidPart(poseStack, vertexConsumer, body, torso, torso, torsoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, headPart, head, torso, headoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, leftHind,  leftHindT,  torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, rightHind, rightHindT, torso, rlegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, leftFront,  leftFrontT,  torso, llegoff, light);
        renderHumanoidPart(poseStack, vertexConsumer, rightFront, rightFrontT, torso, rlegoff, light);

        poseStack.popPose();
    }

    private void renderArmor(MobRagdollEntity entity, PoseStack poseStack,
                             MultiBufferSource buffer, int light,
                             RagdollTransform torso, RagdollTransform head,
                             RagdollTransform larm, RagdollTransform rarm,
                             RagdollTransform lleg, RagdollTransform rleg) {

        ItemStack helmet = entity.getHelmet();
        ItemStack chestplate = entity.getChestplate();
        ItemStack leggings = entity.getLeggings();
        ItemStack boots = entity.getBoots();

        // Render helmet
        if (!helmet.isEmpty() && helmet.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.HEAD);
            VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
            renderHumanoidPart(poseStack, vertexConsumer, armorOuter.head, head, torso, headoff, light);
        }

        // Render chestplate
        if (!chestplate.isEmpty() && chestplate.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.CHEST);
            VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            renderHumanoidPart(poseStack, innerConsumer, armorInner.body, torso, torso, torsoff, light);
            renderHumanoidPart(poseStack, innerConsumer, armorInner.leftArm, larm, torso, larmoff, light);
            renderHumanoidPart(poseStack, innerConsumer, armorInner.rightArm, rarm, torso, rarmoff, light);
        }

        // Render leggings
        if (!leggings.isEmpty() && leggings.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.LEGS);
            VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            renderHumanoidPart(poseStack, innerConsumer, armorInner.body, torso, torso, torsoff, light);
            renderHumanoidPart(poseStack, innerConsumer, armorInner.leftLeg, lleg, torso, llegoff, light);
            renderHumanoidPart(poseStack, innerConsumer, armorInner.rightLeg, rleg, torso, rlegoff, light);
        }

        // Render boots
        if (!boots.isEmpty() && boots.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.FEET);
            VertexConsumer outerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            renderHumanoidPart(poseStack, outerConsumer, armorOuter.leftLeg, lleg, torso, llegoff, light);
            renderHumanoidPart(poseStack, outerConsumer, armorOuter.rightLeg, rleg, torso, rlegoff, light);
        }
    }

    private ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot) {
        String texturePath = item.getArmorTexture(
                new ItemStack(item),
                null,
                slot,
                null
        );

        if (texturePath != null && !texturePath.isEmpty()) {
            try {
                // If it's "modid:material_name" format - convert to texture path
                if (texturePath.contains(":") && !texturePath.contains("textures/")) {
                    String[] parts = texturePath.split(":", 2);
                    if (parts.length == 2) {
                        String namespace = parts[0];
                        String materialName = parts[1];
                        String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
                        return new ResourceLocation(namespace, "textures/models/armor/" + materialName + "_" + layer + ".png");
                    }
                }
                // Otherwise assume it's a full texture path
                return new ResourceLocation(texturePath);
            } catch (Exception e) {
                // Fall through to fallback on error
                Ragdollified.LOGGER.warn("Failed to parse armor texture path: {}", texturePath, e);
            }
        }

        // Fallback: construct the path manually
        String materialName = item.getMaterial().getName();

        // Parse namespace and path
        String namespace = "minecraft";
        String path = materialName;

        if (materialName.contains(":")) {
            String[] parts = materialName.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        }

        String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
        return new ResourceLocation(namespace, "textures/models/armor/" + path + "_" + layer + ".png");
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
        ResourceLocation cached = entity.getCachedTexture();
        if (cached != null) {
            return cached;
        }

        ResourceLocation textureFromCache = ClientMobTextureCache.getTextureForDeadMob(
                entity.getOriginalMobId()
        );

        if (textureFromCache != null) {
            entity.setCachedTexture(textureFromCache);
            return textureFromCache;
        }

        String mobType = entity.getMobType();
        ResourceLocation fallback;
        if (mobType.contains("zombie")) {
            fallback = new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        } else if (mobType.contains("skeleton")) {
            fallback = new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        } else if (mobType.contains("creeper")) {
            fallback = new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");
        } else {
            fallback = new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        }

        entity.setCachedTexture(fallback);
        return fallback;
    }
}