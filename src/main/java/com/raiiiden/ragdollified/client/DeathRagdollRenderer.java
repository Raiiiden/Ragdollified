package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.DeathRagdollEntity;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

public class DeathRagdollRenderer extends EntityRenderer<DeathRagdollEntity> {
    private final PlayerModel<AbstractClientPlayer> normalModel;
    private final PlayerModel<AbstractClientPlayer> slimModel;
    private final HumanoidModel<AbstractClientPlayer> normalArmorInner;
    private final HumanoidModel<AbstractClientPlayer> normalArmorOuter;
    private final HumanoidModel<AbstractClientPlayer> slimArmorInner;
    private final HumanoidModel<AbstractClientPlayer> slimArmorOuter;

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

    public DeathRagdollRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.normalModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.normalArmorInner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.normalArmorOuter = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        this.slimArmorInner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.slimArmorOuter = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));

        Ragdollified.LOGGER.info("DeathRagdollRenderer created with " +
                (GeckoLibArmorHelper.isGeckoLibAvailable() ? "GeckoLib" : "vanilla only") + " support");
    }

    @Override
    public void render(DeathRagdollEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        RagdollManager.ClientRagdoll rag = DeathRagdollManager.get(entity.getId());
        if (rag == null || !rag.isActive()) return;

        boolean isSlim = isSlimModel(entity);
        PlayerModel<AbstractClientPlayer> model = isSlim ? slimModel : normalModel;

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

        ResourceLocation skin = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityTranslucent(skin));

        renderRagdollPart(poseStack, vertexConsumer, model.body, torso, torso, torsoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.head, head, torso, headoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.leftLeg, lleg, torso, llegoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.rightLeg, rleg, torso, rlegoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.leftArm, larm, torso, larmoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.rightArm, rarm, torso, rarmoff, packedLight);

        renderArmor(entity, poseStack, buffer, packedLight, partialTick, torso, head, larm, rarm, lleg, rleg, isSlim);

        poseStack.popPose();
    }

    private void renderArmor(DeathRagdollEntity entity, PoseStack poseStack, MultiBufferSource buffer, int light, float partialTick,
                             RagdollTransform torso, RagdollTransform head, RagdollTransform larm, RagdollTransform rarm,
                             RagdollTransform lleg, RagdollTransform rleg, boolean isSlim) {
        renderArmorPiece(entity.getHelmet(), EquipmentSlot.HEAD, entity, poseStack, buffer, light, partialTick, torso, head, larm, rarm, lleg, rleg, isSlim);
        renderArmorPiece(entity.getChestplate(), EquipmentSlot.CHEST, entity, poseStack, buffer, light, partialTick, torso, head, larm, rarm, lleg, rleg, isSlim);
        renderArmorPiece(entity.getLeggings(), EquipmentSlot.LEGS, entity, poseStack, buffer, light, partialTick, torso, head, larm, rarm, lleg, rleg, isSlim);
        renderArmorPiece(entity.getBoots(), EquipmentSlot.FEET, entity, poseStack, buffer, light, partialTick, torso, head, larm, rarm, lleg, rleg, isSlim);
    }

    private void renderArmorPiece(ItemStack stack, EquipmentSlot slot, DeathRagdollEntity entity, PoseStack poseStack, MultiBufferSource buffer,
                                  int light, float partialTick, RagdollTransform torso, RagdollTransform head, RagdollTransform larm,
                                  RagdollTransform rarm, RagdollTransform lleg, RagdollTransform rleg, boolean isSlim) {
        if (stack.isEmpty()) return;
        Item item = stack.getItem();

        if (GeckoLibArmorHelper.isGeckoLibArmor(item)) {
            renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim);
        } else if (item instanceof ArmorItem armorItem) {
            renderVanillaArmor(stack, armorItem, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim);
        }
    }

    private void renderGeckoLibArmor(ItemStack stack, EquipmentSlot slot, DeathRagdollEntity entity, PoseStack poseStack,
                                     MultiBufferSource buffer, int light, RagdollTransform torso, RagdollTransform head,
                                     RagdollTransform larm, RagdollTransform rarm, RagdollTransform lleg, RagdollTransform rleg, boolean isSlim) {
        HumanoidModel<AbstractClientPlayer> baseModel = isSlim ? slimArmorInner : normalArmorInner;

        baseModel.setAllVisible(false);
        baseModel.young = false;
        baseModel.crouching = false;
        baseModel.riding = false;

        // For GeckoLib, we need to NOT double-transform
        // We'll set the model part rotations to zero and only use PoseStack transforms
        switch (slot) {
            case HEAD:
                if (head != null) {
                    baseModel.head.visible = true;
                    // Reset rotation - we'll apply it via PoseStack only
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
                    baseModel.body.visible = true;
                    baseModel.leftArm.visible = false;  // Render arms separately
                    baseModel.rightArm.visible = false;

                    // Reset body
                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;

                    // Render body from torso position
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, torsoff);

                    baseModel.body.visible = false;

                    // Now render left arm separately from its own position
                    baseModel.leftArm.visible = true;
                    baseModel.leftArm.setPos(4, 4, 0);
                    baseModel.leftArm.xRot = 0;
                    baseModel.leftArm.yRot = 0;
                    baseModel.leftArm.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, larm, torso, larmoff);
                    baseModel.leftArm.visible = false;

                    // Now render right arm separately from its own position
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
                    // --- 1. Render Body (Attached to Torso, No Offset) ---
                    baseModel.body.visible = true;
                    baseModel.leftLeg.visible = false;
                    baseModel.rightLeg.visible = false;

                    // Reset body model transforms
                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;

                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, torsoff);
                    baseModel.body.visible = false;

                    baseModel.leftLeg.visible = true;

                    baseModel.leftLeg.setPos(1.9F, 5.5F, 0);
                    baseModel.leftLeg.xRot = 0;
                    baseModel.leftLeg.yRot = 0;
                    baseModel.leftLeg.zRot = 0;

                    poseStack.pushPose();
                    poseStack.translate(0, 0F, 0);

                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, lleg, torso, llegoff);
                    poseStack.popPose();

                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;

                    baseModel.rightLeg.setPos(-1.9F, 5.5F, 0);
                    baseModel.rightLeg.xRot = 0;
                    baseModel.rightLeg.yRot = 0;
                    baseModel.rightLeg.zRot = 0;

                    poseStack.pushPose();
                    poseStack.translate(0, 0F, 0);

                    // Use 'rleg' transform so the armor follows the physics leg
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rleg, torso, rlegoff);
                    poseStack.popPose();

                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    baseModel.rightLeg.visible = true;

                    baseModel.leftLeg.setPos(1.9F, 12, 0);
                    baseModel.leftLeg.xRot = 0;
                    baseModel.leftLeg.yRot = 0;
                    baseModel.leftLeg.zRot = 0;

                    baseModel.rightLeg.setPos(-1.9F, 12, 0);
                    baseModel.rightLeg.xRot = 0;
                    baseModel.rightLeg.yRot = 0;
                    baseModel.rightLeg.zRot = 0;

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

    private void renderGeckoLibPart(ItemStack stack, EquipmentSlot slot, DeathRagdollEntity entity,
                                    PoseStack poseStack, MultiBufferSource buffer, int light,
                                    HumanoidModel<AbstractClientPlayer> baseModel,
                                    RagdollTransform transform, RagdollTransform torso, Vector3f[] pivot) {
        if (transform == null) return;

        poseStack.pushPose();

        // Apply the same transforms we use for vanilla armor
        Quaternionf torsoRot = new Quaternionf(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);
        Vector3f rotatedPivot = new Vector3f(pivot[1]);
        torsoRot.transform(rotatedPivot);

        Quaternionf q = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);

        poseStack.translate(-rotatedPivot.x, -rotatedPivot.y, -rotatedPivot.z);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        // Now render the GeckoLib armor with these transforms
        GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, baseModel);

        poseStack.popPose();
    }

    private void applyRagdollToModelPart(ModelPart part, RagdollTransform transform, RagdollTransform torso, Vector3f[] pivot) {
        if (transform == null) {
            part.setPos(pivot[0].x, pivot[0].y, pivot[0].z);
            part.xRot = part.yRot = part.zRot = 0;
            return;
        }

        Quaternionf torsoRot = new Quaternionf(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);
        Quaternionf partRot = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);

        Quaternionf relativeRot = new Quaternionf(torsoRot).conjugate().mul(partRot);
        Vector3f angles = relativeRot.getEulerAnglesXYZ(new Vector3f());

        part.xRot = -angles.x;
        part.yRot = -angles.y;
        part.zRot = angles.z;
        part.setPos(pivot[0].x, pivot[0].y, pivot[0].z);
    }

    private void renderVanillaArmor(ItemStack stack, ArmorItem armorItem, EquipmentSlot slot, PoseStack poseStack, MultiBufferSource buffer,
                                    int light, RagdollTransform torso, RagdollTransform head, RagdollTransform larm, RagdollTransform rarm,
                                    RagdollTransform lleg, RagdollTransform rleg, boolean isSlim) {
        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;
        ResourceLocation armorTexture = getArmorTexture(armorItem, slot);

        switch (slot) {
            case HEAD:
                VertexConsumer helmetConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, helmetConsumer, outerModel.head, head, torso, headoff, light);
                break;
            case CHEST:
                VertexConsumer chestConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, chestConsumer, innerModel.body, torso, torso, torsoff, light);
                renderRagdollPart(poseStack, chestConsumer, innerModel.leftArm, larm, torso, larmoff, light);
                renderRagdollPart(poseStack, chestConsumer, innerModel.rightArm, rarm, torso, rarmoff, light);
                break;
            case LEGS:
                VertexConsumer legsConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, legsConsumer, innerModel.body, torso, torso, torsoff, light);
                renderRagdollPart(poseStack, legsConsumer, innerModel.leftLeg, lleg, torso, llegoff, light);
                renderRagdollPart(poseStack, legsConsumer, innerModel.rightLeg, rleg, torso, rlegoff, light);
                break;
            case FEET:
                VertexConsumer bootsConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, bootsConsumer, outerModel.leftLeg, lleg, torso, llegoff, light);
                renderRagdollPart(poseStack, bootsConsumer, outerModel.rightLeg, rleg, torso, rlegoff, light);
                break;
        }
    }

    private ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot) {
        try {
            String texturePath = item.getArmorTexture(new ItemStack(item), null, slot, null);
            if (texturePath != null && !texturePath.isEmpty()) {
                try {
                    return new ResourceLocation(texturePath);
                } catch (Exception e) {
                }
            }

            String materialName = item.getMaterial().getName();
            if (materialName.contains(":")) {
                materialName = materialName.substring(materialName.lastIndexOf(":") + 1);
            }

            materialName = switch (materialName.toLowerCase()) {
                case "leather" -> "leather";
                case "chainmail", "chain" -> "chainmail";
                case "iron" -> "iron";
                case "gold", "golden" -> "gold";
                case "diamond" -> "diamond";
                case "netherite" -> "netherite";
                default -> materialName;
            };

            String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
            return new ResourceLocation("minecraft", "textures/models/armor/" + materialName + "_" + layer + ".png");
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to get armor texture for " + item.getDescriptionId(), e);
            return new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1.png");
        }
    }

    private void renderRagdollPart(PoseStack poseStack, VertexConsumer vertexConsumer, ModelPart part, RagdollTransform transform,
                                   RagdollTransform torso, Vector3f[] pivot, int light) {
        if (transform == null) return;

        part.setPos(pivot[0].x, pivot[0].y, pivot[0].z);
        poseStack.pushPose();

        Quaternionf torsoRot = new Quaternionf(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);
        Vector3f rotatedPivot = new Vector3f(pivot[1]);
        torsoRot.transform(rotatedPivot);

        Quaternionf q = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);

        poseStack.translate(-rotatedPivot.x, -rotatedPivot.y, -rotatedPivot.z);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        part.render(poseStack, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private boolean isSlimModel(DeathRagdollEntity entity) {
        UUID playerUUID = entity.getPlayerUUID();
        if (playerUUID != null) {
            for (AbstractClientPlayer p : Minecraft.getInstance().level.players()) {
                if (p.getUUID().equals(playerUUID)) {
                    return p.getModelName().equals("slim");
                }
            }
            return DefaultPlayerSkin.getSkinModelName(playerUUID).equals("slim");
        }
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(DeathRagdollEntity entity) {
        UUID playerUUID = entity.getPlayerUUID();
        if (playerUUID != null) {
            for (AbstractClientPlayer p : Minecraft.getInstance().level.players()) {
                if (p.getUUID().equals(playerUUID)) {
                    return p.getSkinTextureLocation();
                }
            }
            return DefaultPlayerSkin.getDefaultSkin(playerUUID);
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }
}