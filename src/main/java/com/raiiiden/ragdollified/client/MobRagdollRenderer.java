package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import net.minecraft.client.model.*;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MobRagdollRenderer extends EntityRenderer<MobRagdollEntity> {
    // Different model types for different mob proportions
    private final HumanoidModel<?> standardHumanoidModel;  // Zombies, husks, piglins, etc.
    private final SkeletonModel<?> skeletonModel;          // Skeletons (64x32, thin)
    private final IllagerModel<?> illagerModel;            // Pillagers, vindicators, etc. (big head)
    private final DrownedModel<?> drownedModel;            // Drowned (different arms)
    private final CreeperModel<?> creeperModel;            // Creepers

    // Armor models
    private final HumanoidModel<?> armorInner;
    private final HumanoidModel<?> armorOuter;
    // Change all four quadruped fields to ModelPart instead
    private final ModelPart cowRoot;
    private final ModelPart sheepRoot;
    private final ModelPart pigRoot;
    private final ModelPart chickenRoot;

    // Standard pivot offsets
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

        try {
            // Standard humanoid model (64x64)
            LayerDefinition standardHumanoidDef = LayerDefinition.create(
                    HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64
            );
            this.standardHumanoidModel = new HumanoidModel<>(standardHumanoidDef.bakeRoot());

            // Skeleton model (64x32, thin limbs)
            LayerDefinition skeletonDef = SkeletonModel.createBodyLayer();
            this.skeletonModel = new SkeletonModel<>(skeletonDef.bakeRoot());

            // Illager model (big head, different proportions)
            LayerDefinition illagerDef = IllagerModel.createBodyLayer();
            this.illagerModel = new IllagerModel<>(illagerDef.bakeRoot());

            // Drowned model (different arms for holding items)
            LayerDefinition drownedDef = DrownedModel.createBodyLayer(CubeDeformation.NONE);
            this.drownedModel = new DrownedModel<>(drownedDef.bakeRoot());

            // Creeper model
            LayerDefinition creeperDef = CreeperModel.createBodyLayer(CubeDeformation.NONE);
            this.creeperModel = new CreeperModel<>(creeperDef.bakeRoot());

            // Quadruped animal models
            LayerDefinition cowDef = net.minecraft.client.model.CowModel.createBodyLayer();
            this.cowRoot = cowDef.bakeRoot();

            LayerDefinition sheepDef = net.minecraft.client.model.SheepModel.createBodyLayer();
            this.sheepRoot = sheepDef.bakeRoot();

            LayerDefinition pigDef = net.minecraft.client.model.PigModel.createBodyLayer(CubeDeformation.NONE);
            this.pigRoot = pigDef.bakeRoot();

            LayerDefinition chickenDef = ChickenModel.createBodyLayer();
            this.chickenRoot = chickenDef.bakeRoot();

            Ragdollified.LOGGER.info("Successfully created all ragdoll renderer models");
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to create ragdoll renderer models", e);
            throw new RuntimeException("Could not initialize ragdoll renderer", e);
        }

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

        if (entity.getCapturedPose() == null && entity.tickCount < 20) {
            MobPoseCapture.MobPose capturedPose = MobPoseCapture.getPose(entity.getOriginalMobId());
            if (capturedPose != null) {
                entity.setCapturedPose(capturedPose);
            }
        }

        String mobType = entity.getMobType();
        MobModelHelper.ModelType modelType = MobModelHelper.getModelTypeFromMobType(mobType);

        switch (modelType) {
            case CREEPER:
                renderCreeper(entity, rag, partialTick, poseStack, buffer, packedLight);
                break;
            case QUADRUPED:
                renderQuadruped(entity, rag, partialTick, poseStack, buffer, packedLight,
                        getQuadrupedRoot(mobType));
                break;
            case CHICKEN:
                renderChicken(entity, rag, partialTick, poseStack, buffer, packedLight);
                break;
            case ILLAGER:
                renderIllager(entity, rag, partialTick, poseStack, buffer, packedLight);
                break;
            case HUMANOID_SKELETON:
                renderHumanoid(entity, rag, partialTick, poseStack, buffer, packedLight, skeletonModel);
                break;
            case HUMANOID_DROWNED:
                renderHumanoid(entity, rag, partialTick, poseStack, buffer, packedLight, drownedModel);
                break;
            case HUMANOID_STANDARD:
            default:
                renderHumanoid(entity, rag, partialTick, poseStack, buffer, packedLight, standardHumanoidModel);
                break;
        }
    }

    /**
     * Render illagers (they have a different model hierarchy)
     */
    private void renderIllager(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
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

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Get illager model parts from the root
        ModelPart root = illagerModel.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");

        // Hide the "arms" (crossed arms) part and show individual arms
        try {
            ModelPart arms = root.getChild("arms");
            arms.visible = false;
        } catch (Exception e) {
            // arms part might not exist for some models
        }
        leftArm.visible = true;
        rightArm.visible = true;

        // Make sure all child parts are visible (nose, ears, etc.)
        makeAllChildrenVisible(headPart);
        makeAllChildrenVisible(body);
        makeAllChildrenVisible(leftArm);
        makeAllChildrenVisible(rightArm);
        makeAllChildrenVisible(leftLeg);
        makeAllChildrenVisible(rightLeg);

        // Render body parts
        renderHumanoidPart(poseStack, vertexConsumer, body, torso, torso, torsoff, light, entity, RagdollPart.TORSO);
        renderHumanoidPart(poseStack, vertexConsumer, headPart, head, torso, headoff, light, entity, RagdollPart.HEAD);
        renderHumanoidPart(poseStack, vertexConsumer, leftLeg, lleg, torso, llegoff, light, entity, RagdollPart.LEFT_LEG);
        renderHumanoidPart(poseStack, vertexConsumer, rightLeg, rleg, torso, rlegoff, light, entity, RagdollPart.RIGHT_LEG);
        renderHumanoidPart(poseStack, vertexConsumer, leftArm, larm, torso, larmoff, light, entity, RagdollPart.LEFT_ARM);
        renderHumanoidPart(poseStack, vertexConsumer, rightArm, rarm, torso, rarmoff, light, entity, RagdollPart.RIGHT_ARM);

        // Render armor
        renderArmor(entity, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);

        poseStack.popPose();
    }

    /**
     * Universal humanoid renderer - works with any HumanoidModel
     */
    private void renderHumanoid(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                                float partialTick, PoseStack poseStack,
                                MultiBufferSource buffer, int light, HumanoidModel<?> model) {

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

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Make sure all child parts are visible (for decorations like piglin ears/nose)
        makeAllChildrenVisible(model.head);
        makeAllChildrenVisible(model.body);
        makeAllChildrenVisible(model.leftArm);
        makeAllChildrenVisible(model.rightArm);
        makeAllChildrenVisible(model.leftLeg);
        makeAllChildrenVisible(model.rightLeg);

        // Render body parts
        renderHumanoidPart(poseStack, vertexConsumer, model.body, torso, torso, torsoff, light, entity, RagdollPart.TORSO);
        renderHumanoidPart(poseStack, vertexConsumer, model.head, head, torso, headoff, light, entity, RagdollPart.HEAD);
        renderHumanoidPart(poseStack, vertexConsumer, model.leftLeg, lleg, torso, llegoff, light, entity, RagdollPart.LEFT_LEG);
        renderHumanoidPart(poseStack, vertexConsumer, model.rightLeg, rleg, torso, rlegoff, light, entity, RagdollPart.RIGHT_LEG);
        renderHumanoidPart(poseStack, vertexConsumer, model.leftArm, larm, torso, larmoff, light, entity, RagdollPart.LEFT_ARM);
        renderHumanoidPart(poseStack, vertexConsumer, model.rightArm, rarm, torso, rarmoff, light, entity, RagdollPart.RIGHT_ARM);

        // Render armor
        renderArmor(entity, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);

        poseStack.popPose();
    }

    private ModelPart getQuadrupedRoot(String mobType) {
        if (mobType.contains("cow") || mobType.contains("mooshroom")) return cowRoot;
        if (mobType.contains("sheep")) return sheepRoot;
        if (mobType.contains("pig")) return pigRoot;
        return cowRoot;
    }

    private void renderQuadruped(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                                 float partialTick, PoseStack poseStack,
                                 MultiBufferSource buffer, int light, ModelPart root) {

        RagdollTransform torso      = rag.getPartInterpolated(RagdollPart.TORSO,     partialTick);
        RagdollTransform head       = rag.getPartInterpolated(RagdollPart.HEAD,      partialTick);
        RagdollTransform leftFrontT = rag.getPartInterpolated(RagdollPart.LEFT_ARM,  partialTick);
        RagdollTransform rightFrontT= rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform leftHindT  = rag.getPartInterpolated(RagdollPart.LEFT_LEG,  partialTick);
        RagdollTransform rightHindT = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");

        makeAllChildrenVisible(body);
        makeAllChildrenVisible(headPart);
        makeAllChildrenVisible(leftHind);
        makeAllChildrenVisible(rightHind);
        makeAllChildrenVisible(leftFront);
        makeAllChildrenVisible(rightFront);

        // Centering offsets per animal — computed from cube geometry centers
        // so the model visuals are centered on each physics body position.
        // Body has xRot=PI/2 because QuadrupedModel defines body vertically then rotates it horizontal.
        String mobType = entity.getMobType();
        float bodyY, bodyZ, headY, headZ, legY;
        if (mobType.contains("cow") || mobType.contains("mooshroom")) {
            // Body cube(-6,-10,-7, 12,18,10) center after xRot: (0,2,-1)
            bodyY = -2; bodyZ = 1;
            // Head cube(-4,-4,-6, 8,8,6) center: (0,0,-3)
            headY = 0; headZ = 3;
            // Legs cube(-2,0,-2, 4,12,4) center: (0,6,0)
            legY = -6;
        } else if (mobType.contains("pig")) {
            // Body cube(-5,-10,-7, 10,16,8) center after xRot: (0,3,-2)
            bodyY = -3; bodyZ = 2;
            // Head cube(-4,-4,-8, 8,8,8) center: (0,0,-4)
            headY = 0; headZ = 4;
            // Legs cube(-2,0,-2, 4,6,4) center: (0,3,0)
            legY = -3;
        } else {
            // Sheep: Body cube(-4,-10,-7, 8,16,6) center after xRot: (0,4,-2)
            bodyY = -4; bodyZ = 2;
            // Head cube(-3,-4,-6, 6,6,8) center: (0,-1,-2)
            headY = 1; headZ = 2;
            // Legs cube(-2,0,-2, 4,12,4) center: (0,6,0)
            legY = -6;
        }

        float halfPI = (float) (Math.PI / 2);

        renderAnimalPart(poseStack, vertexConsumer, body,       torso,       torso, 0, bodyY, bodyZ, halfPI, light);
        renderAnimalPart(poseStack, vertexConsumer, headPart,   head,        torso, 0, headY, headZ, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, leftHind,   leftHindT,   torso, 0, legY, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightHind,  rightHindT,  torso, 0, legY, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, leftFront,  leftFrontT,  torso, 0, legY, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightFront, rightFrontT, torso, 0, legY, 0, 0, light);

        poseStack.popPose();
    }

    private void renderChicken(MobRagdollEntity entity, RagdollManager.ClientRagdoll rag,
                               float partialTick, PoseStack poseStack,
                               MultiBufferSource buffer, int light) {

        RagdollTransform torso  = rag.getPartInterpolated(RagdollPart.TORSO,     partialTick);
        RagdollTransform head   = rag.getPartInterpolated(RagdollPart.HEAD,      partialTick);
        RagdollTransform lWing  = rag.getPartInterpolated(RagdollPart.LEFT_ARM,  partialTick);
        RagdollTransform rWing  = rag.getPartInterpolated(RagdollPart.RIGHT_ARM, partialTick);
        RagdollTransform lLeg   = rag.getPartInterpolated(RagdollPart.LEFT_LEG,  partialTick);
        RagdollTransform rLeg   = rag.getPartInterpolated(RagdollPart.RIGHT_LEG, partialTick);

        if (torso == null) return;

        poseStack.pushPose();
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        ModelPart root = chickenRoot;
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart beak       = root.getChild("beak");
        ModelPart redThing   = root.getChild("red_thing");
        ModelPart leftLeg    = root.getChild("left_leg");
        ModelPart rightLeg   = root.getChild("right_leg");
        ModelPart leftWing   = root.getChild("left_wing");
        ModelPart rightWing  = root.getChild("right_wing");

        makeAllChildrenVisible(body);
        makeAllChildrenVisible(headPart);

        float halfPI = (float) (Math.PI / 2);

        // Body cube(-3,-4,-3, 6,8,6) center (0,0,0) — perfectly centered, xRot=PI/2
        renderAnimalPart(poseStack, vertexConsumer, body,       torso, torso, 0, 0, 0, halfPI, light);

        // Head group: all use head centering so beak/wattle stay attached
        // Head cube(-2,-6,-2, 4,6,3) center (0,-3,-0.5)
        renderAnimalPart(poseStack, vertexConsumer, headPart,   head,  torso, 0, 3, 0.5f, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, beak,       head,  torso, 0, 3, 0.5f, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, redThing,   head,  torso, 0, 3, 0.5f, 0, light);

        // Legs cube(-1,0,-3, 3,5,3) center (0.5,2.5,-1.5)
        renderAnimalPart(poseStack, vertexConsumer, leftLeg,    lLeg,  torso, -0.5f, -2.5f, 1.5f, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightLeg,   rLeg,  torso, -0.5f, -2.5f, 1.5f, 0, light);

        // Wings: left cube(0,0,-3, 1,4,6) center (0.5,2,0); right cube(-1,0,-3, 1,4,6) center (-0.5,2,0)
        renderAnimalPart(poseStack, vertexConsumer, leftWing,   lWing, torso, -0.5f, -2, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightWing,  rWing, torso, 0.5f, -2, 0, 0, light);

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

        ModelPart root = creeperModel.root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");

        // Body and head use humanoid-style rendering (relative to torso) — works well for creeper proportions
        renderHumanoidPart(poseStack, vertexConsumer, body,     torso, torso, torsoff, light, entity, RagdollPart.TORSO);
        renderHumanoidPart(poseStack, vertexConsumer, headPart, head,  torso, headoff, light, entity, RagdollPart.HEAD);

        // Legs use animal-style rendering (physics world position) for correct placement at corners
        // Creeper legs cube(-2,0,-2, 4,6,4) center (0,3,0)
        renderAnimalPart(poseStack, vertexConsumer, leftHind,   leftHindT,   torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightHind,  rightHindT,  torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, leftFront,  leftFrontT,  torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vertexConsumer, rightFront, rightFrontT, torso, 0, -3, 0, 0, light);

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

        // Helmet
        if (!helmet.isEmpty()) {
            Item item = helmet.getItem();
            if (GeckoLibArmorHelper.isGeckoLibArmor(item)) {
                renderGeckoLibArmorPiece(helmet, EquipmentSlot.HEAD, entity, poseStack, buffer, light,
                        torso, head, larm, rarm, lleg, rleg);
            } else if (item instanceof ArmorItem armorItem) {
                ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.HEAD);
                VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderHumanoidPart(poseStack, vertexConsumer, armorOuter.head, head, torso, headoff, light, entity, RagdollPart.HEAD);
            }
        }

        // Chestplate
        if (!chestplate.isEmpty()) {
            Item item = chestplate.getItem();
            if (GeckoLibArmorHelper.isGeckoLibArmor(item)) {
                renderGeckoLibArmorPiece(chestplate, EquipmentSlot.CHEST, entity, poseStack, buffer, light,
                        torso, head, larm, rarm, lleg, rleg);
            } else if (item instanceof ArmorItem armorItem) {
                ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.CHEST);
                VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderHumanoidPart(poseStack, innerConsumer, armorInner.body, torso, torso, torsoff, light, entity, RagdollPart.TORSO);
                renderHumanoidPart(poseStack, innerConsumer, armorInner.leftArm, larm, torso, larmoff, light, entity, RagdollPart.LEFT_ARM);
                renderHumanoidPart(poseStack, innerConsumer, armorInner.rightArm, rarm, torso, rarmoff, light, entity, RagdollPart.RIGHT_ARM);
            }
        }

        // Leggings
        if (!leggings.isEmpty()) {
            Item item = leggings.getItem();
            if (GeckoLibArmorHelper.isGeckoLibArmor(item)) {
                renderGeckoLibArmorPiece(leggings, EquipmentSlot.LEGS, entity, poseStack, buffer, light,
                        torso, head, larm, rarm, lleg, rleg);
            } else if (item instanceof ArmorItem armorItem) {
                ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.LEGS);
                VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderHumanoidPart(poseStack, innerConsumer, armorInner.body, torso, torso, torsoff, light, entity, RagdollPart.TORSO);
                renderHumanoidPart(poseStack, innerConsumer, armorInner.leftLeg, lleg, torso, llegoff, light, entity, RagdollPart.LEFT_LEG);
                renderHumanoidPart(poseStack, innerConsumer, armorInner.rightLeg, rleg, torso, rlegoff, light, entity, RagdollPart.RIGHT_LEG);
            }
        }

        // Boots
        if (!boots.isEmpty()) {
            Item item = boots.getItem();
            if (GeckoLibArmorHelper.isGeckoLibArmor(item)) {
                renderGeckoLibArmorPiece(boots, EquipmentSlot.FEET, entity, poseStack, buffer, light,
                        torso, head, larm, rarm, lleg, rleg);
            } else if (item instanceof ArmorItem armorItem) {
                ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.FEET);
                VertexConsumer outerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderHumanoidPart(poseStack, outerConsumer, armorOuter.leftLeg, lleg, torso, llegoff, light, entity, RagdollPart.LEFT_LEG);
                renderHumanoidPart(poseStack, outerConsumer, armorOuter.rightLeg, rleg, torso, rlegoff, light, entity, RagdollPart.RIGHT_LEG);
            }
        }
    }

    private void renderGeckoLibArmorPiece(ItemStack stack, EquipmentSlot slot, MobRagdollEntity entity,
                                          PoseStack poseStack, MultiBufferSource buffer, int light,
                                          RagdollTransform torso, RagdollTransform head,
                                          RagdollTransform larm, RagdollTransform rarm,
                                          RagdollTransform lleg, RagdollTransform rleg) {
        HumanoidModel<?> baseModel = armorInner;
        baseModel.setAllVisible(false);
        baseModel.young = false;
        baseModel.crouching = false;
        baseModel.riding = false;

        switch (slot) {
            case HEAD:
                if (head != null) {
                    baseModel.head.visible = true;
                    baseModel.head.setPos(0, 0, 0);
                    baseModel.head.xRot = 0;
                    baseModel.head.yRot = 0;
                    baseModel.head.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, head, torso, headoff);
                    baseModel.head.visible = false;
                }
                break;
            case CHEST:
                if (torso != null && larm != null && rarm != null) {
                    // Body
                    baseModel.body.visible = true;
                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, torsoff);
                    baseModel.body.visible = false;

                    // Left Arm
                    baseModel.leftArm.visible = true;
                    baseModel.leftArm.setPos(4, 4, 0);
                    baseModel.leftArm.xRot = 0;
                    baseModel.leftArm.yRot = 0;
                    baseModel.leftArm.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, larm, torso, larmoff);
                    baseModel.leftArm.visible = false;

                    // Right Arm
                    baseModel.rightArm.visible = true;
                    baseModel.rightArm.setPos(-4, 4, 0);
                    baseModel.rightArm.xRot = 0;
                    baseModel.rightArm.yRot = 0;
                    baseModel.rightArm.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rarm, torso, rarmoff);
                    baseModel.rightArm.visible = false;
                }
                break;
            case LEGS:
                if (torso != null && lleg != null && rleg != null) {
                    // Body
                    baseModel.body.visible = true;
                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, torsoff);
                    baseModel.body.visible = false;

                    // Left Leg
                    baseModel.leftLeg.visible = true;
                    baseModel.leftLeg.setPos(1.9F, 5.5F, 0);
                    baseModel.leftLeg.xRot = 0;
                    baseModel.leftLeg.yRot = 0;
                    baseModel.leftLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, lleg, torso, llegoff);
                    baseModel.leftLeg.visible = false;

                    // Right Leg
                    baseModel.rightLeg.visible = true;
                    baseModel.rightLeg.setPos(-1.9F, 5.5F, 0);
                    baseModel.rightLeg.xRot = 0;
                    baseModel.rightLeg.yRot = 0;
                    baseModel.rightLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rleg, torso, rlegoff);
                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    baseModel.rightLeg.visible = true;

                    baseModel.leftLeg.setPos(1.9F, 12, 0);
                    baseModel.rightLeg.setPos(-1.9F, 12, 0);

                    // Apply relative rotations for boots
                    Quaternionf torsoRot = new Quaternionf(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);

                    Quaternionf llegRot = new Quaternionf(lleg.rotation.x, lleg.rotation.y, lleg.rotation.z, lleg.rotation.w);
                    Quaternionf llegRelative = new Quaternionf(torsoRot).conjugate().mul(llegRot);
                    Vector3f llegAngles = llegRelative.getEulerAnglesXYZ(new Vector3f());
                    baseModel.leftLeg.xRot = -llegAngles.x;
                    baseModel.leftLeg.yRot = -llegAngles.y;
                    baseModel.leftLeg.zRot = llegAngles.z;

                    Quaternionf rlegRot = new Quaternionf(rleg.rotation.x, rleg.rotation.y, rleg.rotation.z, rleg.rotation.w);
                    Quaternionf rlegRelative = new Quaternionf(torsoRot).conjugate().mul(rlegRot);
                    Vector3f rlegAngles = rlegRelative.getEulerAnglesXYZ(new Vector3f());
                    baseModel.rightLeg.xRot = -rlegAngles.x;
                    baseModel.rightLeg.yRot = -rlegAngles.y;
                    baseModel.rightLeg.zRot = rlegAngles.z;

                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, torsoff);

                    baseModel.leftLeg.visible = false;
                    baseModel.rightLeg.visible = false;
                }
                break;
        }
    }

    private void renderGeckoLibPart(ItemStack stack, EquipmentSlot slot, MobRagdollEntity entity,
                                    PoseStack poseStack, MultiBufferSource buffer, int light,
                                    HumanoidModel<?> baseModel,
                                    RagdollTransform transform, RagdollTransform torso, Vector3f[] pivot) {
        if (transform == null) return;

        poseStack.pushPose();

        Quaternionf torsoRot = new Quaternionf(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);
        Vector3f rotatedPivot = new Vector3f(pivot[1]);
        torsoRot.transform(rotatedPivot);

        Quaternionf q = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);

        poseStack.translate(-rotatedPivot.x, -rotatedPivot.y, -rotatedPivot.z);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, baseModel);

        poseStack.popPose();
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
                if (texturePath.contains(":") && !texturePath.contains("textures/")) {
                    String[] parts = texturePath.split(":", 2);
                    if (parts.length == 2) {
                        String namespace = parts[0];
                        String materialName = parts[1];
                        String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
                        return new ResourceLocation(namespace, "textures/models/armor/" + materialName + "_" + layer + ".png");
                    }
                }
                return new ResourceLocation(texturePath);
            } catch (Exception e) {
                Ragdollified.LOGGER.warn("Failed to parse armor texture path: {}", texturePath, e);
            }
        }

        String materialName = item.getMaterial().getName();
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

    /**
     * Renders an animal/non-humanoid model part using its physics world position.
     * Unlike renderHumanoidPart which keeps everything relative to the torso origin,
     * this translates to each part's actual physics position so limbs track correctly.
     *
     * @param setPosX/Y/Z  Centering offset in model pixels — shifts the cube geometry
     *                     so its visual center aligns with the physics body center.
     * @param defaultXRot  Default model rotation (e.g. PI/2 for horizontal body parts).
     */
    private void renderAnimalPart(PoseStack poseStack, VertexConsumer vc,
                                   ModelPart part, RagdollTransform transform,
                                   RagdollTransform torso,
                                   float setPosX, float setPosY, float setPosZ,
                                   float defaultXRot, int light) {
        if (transform == null) return;

        poseStack.pushPose();

        // Translate from torso world position to this part's physics world position
        poseStack.translate(
                transform.position.x - torso.position.x,
                transform.position.y - torso.position.y,
                transform.position.z - torso.position.z
        );

        // Apply physics rotation with Minecraft Y-down coordinate flip
        Quaternionf q = new Quaternionf(
                transform.rotation.x, transform.rotation.y,
                transform.rotation.z, transform.rotation.w
        );
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        // Center the model geometry on the physics body and apply default model rotation
        part.setPos(setPosX, setPosY, setPosZ);
        part.xRot = defaultXRot;
        part.yRot = 0;
        part.zRot = 0;

        part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private void renderHumanoidPart(PoseStack poseStack, VertexConsumer vertexConsumer,
                                    ModelPart part, RagdollTransform transform,
                                    RagdollTransform torso, Vector3f[] pivot, int light,
                                    MobRagdollEntity entity, RagdollPart ragdollPart) {
        if (transform == null) return;

        poseStack.pushPose();

        // Translate to this part's physics world position (relative to torso)
        poseStack.translate(
                transform.position.x - torso.position.x,
                transform.position.y - torso.position.y,
                transform.position.z - torso.position.z
        );

        // Apply physics rotation with Minecraft Y-down coordinate flip
        Quaternionf q = new Quaternionf(
                transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w
        );
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        // Center model geometry on the physics body position
        // Offsets computed from each humanoid cube's center point
        switch (ragdollPart) {
            case HEAD:    part.setPos(0, 4, 0);    break; // cube(-4,-8,-4, 8,8,8) center Y=-4
            case TORSO:   part.setPos(0, -6, 0);   break; // cube(-4,0,-2, 8,12,4) center Y=6
            case LEFT_ARM:  part.setPos(-1, -4, 0); break; // cube(-1,-2,-2, 4,12,4) center (1,4,0)
            case RIGHT_ARM: part.setPos(1, -4, 0);  break; // cube(-3,-2,-2, 4,12,4) center (-1,4,0)
            case LEFT_LEG:
            case RIGHT_LEG: part.setPos(0, -6, 0);  break; // cube(-2,0,-2, 4,12,4) center Y=6
        }
        part.xRot = 0;
        part.yRot = 0;
        part.zRot = 0;

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
        ResourceLocation fallback = getFallbackTexture(mobType);

        entity.setCachedTexture(fallback);
        return fallback;
    }

    private ResourceLocation getFallbackTexture(String mobType) {
        // Zombies and variants
        if (mobType.contains("zombie") && !mobType.contains("piglin")) {
            if (mobType.contains("husk")) return new ResourceLocation("minecraft", "textures/entity/zombie/husk.png");
            if (mobType.contains("drowned")) return new ResourceLocation("minecraft", "textures/entity/zombie/drowned.png");
            if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/zombie_villager/zombie_villager.png");
            return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        }

        // Skeletons and variants
        if (mobType.contains("skeleton")) {
            if (mobType.contains("wither")) return new ResourceLocation("minecraft", "textures/entity/skeleton/wither_skeleton.png");
            if (mobType.contains("stray")) return new ResourceLocation("minecraft", "textures/entity/skeleton/stray.png");
            return new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        }

        // Piglins and variants
        if (mobType.contains("piglin")) {
            if (mobType.contains("brute")) return new ResourceLocation("minecraft", "textures/entity/piglin/piglin_brute.png");
            if (mobType.contains("zombified")) return new ResourceLocation("minecraft", "textures/entity/piglin/zombified_piglin.png");
            return new ResourceLocation("minecraft", "textures/entity/piglin/piglin.png");
        }

        // Illagers
        if (mobType.contains("pillager")) return new ResourceLocation("minecraft", "textures/entity/illager/pillager.png");
        if (mobType.contains("vindicator")) return new ResourceLocation("minecraft", "textures/entity/illager/vindicator.png");
        if (mobType.contains("evoker")) return new ResourceLocation("minecraft", "textures/entity/illager/evoker.png");
        if (mobType.contains("illusioner")) return new ResourceLocation("minecraft", "textures/entity/illager/illusioner.png");

        // Villagers
        if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/villager/villager.png");
        if (mobType.contains("wandering_trader")) return new ResourceLocation("minecraft", "textures/entity/wandering_trader.png");

        // Creeper
        if (mobType.contains("creeper")) return new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");

        if (mobType.contains("cow"))      return new ResourceLocation("minecraft", "textures/entity/cow/cow.png");
        if (mobType.contains("mooshroom"))return new ResourceLocation("minecraft", "textures/entity/cow/mooshroom.png");
        if (mobType.contains("sheep"))    return new ResourceLocation("minecraft", "textures/entity/sheep/sheep.png");
        if (mobType.contains("pig"))      return new ResourceLocation("minecraft", "textures/entity/pig/pig.png");
        if (mobType.contains("chicken"))  return new ResourceLocation("minecraft", "textures/entity/chicken.png");

        // Default fallback
        return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    }

    private void makeAllChildrenVisible(ModelPart part) {
        part.visible = true;
        // Iterate through all parts in the hierarchy and make them visible
        part.getAllParts().forEach(p -> p.visible = true);
    }
}