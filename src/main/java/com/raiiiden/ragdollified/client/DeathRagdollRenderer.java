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

import java.util.UUID;

public class DeathRagdollRenderer extends EntityRenderer<DeathRagdollEntity> {
    private final PlayerModel<AbstractClientPlayer> normalModel;
    private final PlayerModel<AbstractClientPlayer> slimModel;
    private final HumanoidModel<AbstractClientPlayer> normalArmorInner;
    private final HumanoidModel<AbstractClientPlayer> normalArmorOuter;
    private final HumanoidModel<AbstractClientPlayer> slimArmorInner;
    private final HumanoidModel<AbstractClientPlayer> slimArmorOuter;

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

        renderRagdollPart(poseStack, vertexConsumer, model.body, torso, torso, packedLight, RagdollPart.TORSO);
        renderRagdollPart(poseStack, vertexConsumer, model.head, head, torso, packedLight, RagdollPart.HEAD);
        renderRagdollPart(poseStack, vertexConsumer, model.leftLeg, lleg, torso, packedLight, RagdollPart.LEFT_LEG);
        renderRagdollPart(poseStack, vertexConsumer, model.rightLeg, rleg, torso, packedLight, RagdollPart.RIGHT_LEG);
        renderRagdollPart(poseStack, vertexConsumer, model.leftArm, larm, torso, packedLight, RagdollPart.LEFT_ARM);
        renderRagdollPart(poseStack, vertexConsumer, model.rightArm, rarm, torso, packedLight, RagdollPart.RIGHT_ARM);

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
                    baseModel.head.setPos(0, 0, 0);
                    baseModel.head.xRot = 0;
                    baseModel.head.yRot = 0;
                    baseModel.head.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, head, torso, RagdollPart.HEAD);
                    baseModel.head.visible = false;
                }
                break;
            case CHEST:
                if (torso != null && larm != null && rarm != null) {
                    baseModel.body.visible = true;
                    baseModel.leftArm.visible = false;
                    baseModel.rightArm.visible = false;

                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;

                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO);
                    baseModel.body.visible = false;

                    baseModel.leftArm.visible = true;
                    baseModel.leftArm.setPos(0, 0, 0);
                    baseModel.leftArm.xRot = 0;
                    baseModel.leftArm.yRot = 0;
                    baseModel.leftArm.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, larm, torso, RagdollPart.LEFT_ARM);
                    baseModel.leftArm.visible = false;

                    baseModel.rightArm.visible = true;
                    baseModel.rightArm.setPos(0, 0, 0);
                    baseModel.rightArm.xRot = 0;
                    baseModel.rightArm.yRot = 0;
                    baseModel.rightArm.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rarm, torso, RagdollPart.RIGHT_ARM);
                    baseModel.rightArm.visible = false;
                }
                break;
            case LEGS:
                if (torso != null && lleg != null && rleg != null) {
                    baseModel.body.visible = true;
                    baseModel.leftLeg.visible = false;
                    baseModel.rightLeg.visible = false;

                    baseModel.body.setPos(0, 0, 0);
                    baseModel.body.xRot = 0;
                    baseModel.body.yRot = 0;
                    baseModel.body.zRot = 0;

                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO);
                    baseModel.body.visible = false;

                    baseModel.leftLeg.visible = true;
                    baseModel.leftLeg.setPos(0, 0, 0);
                    baseModel.leftLeg.xRot = 0;
                    baseModel.leftLeg.yRot = 0;
                    baseModel.leftLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    baseModel.rightLeg.setPos(0, 0, 0);
                    baseModel.rightLeg.xRot = 0;
                    baseModel.rightLeg.yRot = 0;
                    baseModel.rightLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG);
                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    baseModel.leftLeg.setPos(0, 0, 0);
                    baseModel.leftLeg.xRot = 0;
                    baseModel.leftLeg.yRot = 0;
                    baseModel.leftLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    baseModel.rightLeg.setPos(0, 0, 0);
                    baseModel.rightLeg.xRot = 0;
                    baseModel.rightLeg.yRot = 0;
                    baseModel.rightLeg.zRot = 0;
                    renderGeckoLibPart(stack, slot, entity, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG);
                    baseModel.rightLeg.visible = false;
                }
                break;
        }
    }

    private void renderGeckoLibPart(ItemStack stack, EquipmentSlot slot, DeathRagdollEntity entity,
                                    PoseStack poseStack, MultiBufferSource buffer, int light,
                                    HumanoidModel<AbstractClientPlayer> baseModel,
                                    RagdollTransform transform, RagdollTransform torso, RagdollPart ragdollPart) {
        if (transform == null) return;

        poseStack.pushPose();

        // Use physics-position-based rendering (same as skin)
        poseStack.translate(
                transform.position.x - torso.position.x,
                transform.position.y - torso.position.y,
                transform.position.z - torso.position.z
        );

        Quaternionf q = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        // Apply centering offsets in model space (1/16 scale) to match skin rendering
        // GeckoLib renders its own geometry so we offset via poseStack instead of setPos
        float centerX = 0, centerY = 0, centerZ = 0;
        switch (ragdollPart) {
            case HEAD:      centerY = 4f / 16f;   break; // head cube center Y=-4, offset +4
            case TORSO:     centerY = -6f / 16f;   break; // body cube center Y=6, offset -6
            case LEFT_ARM:  centerX = -1f / 16f; centerY = -4f / 16f; break;
            case RIGHT_ARM: centerX = 1f / 16f;  centerY = -4f / 16f; break;
            case LEFT_LEG:
            case RIGHT_LEG: centerY = -6f / 16f;   break;
        }
        poseStack.translate(centerX, centerY, centerZ);

        // Now render the GeckoLib armor with these transforms
        GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, baseModel);

        poseStack.popPose();
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
                renderRagdollPart(poseStack, helmetConsumer, outerModel.head, head, torso, light, RagdollPart.HEAD);
                break;
            case CHEST:
                VertexConsumer chestConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, chestConsumer, innerModel.body, torso, torso, light, RagdollPart.TORSO);
                renderRagdollPart(poseStack, chestConsumer, innerModel.leftArm, larm, torso, light, RagdollPart.LEFT_ARM);
                renderRagdollPart(poseStack, chestConsumer, innerModel.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM);
                break;
            case LEGS:
                VertexConsumer legsConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, legsConsumer, innerModel.body, torso, torso, light, RagdollPart.TORSO);
                renderRagdollPart(poseStack, legsConsumer, innerModel.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
                renderRagdollPart(poseStack, legsConsumer, innerModel.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
                break;
            case FEET:
                VertexConsumer bootsConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));
                renderRagdollPart(poseStack, bootsConsumer, outerModel.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG);
                renderRagdollPart(poseStack, bootsConsumer, outerModel.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG);
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
                                   RagdollTransform torso, int light, RagdollPart ragdollPart) {
        if (transform == null) return;

        poseStack.pushPose();

        // Translate to this part's physics world position (relative to torso)
        poseStack.translate(
                transform.position.x - torso.position.x,
                transform.position.y - torso.position.y,
                transform.position.z - torso.position.z
        );

        // Apply physics rotation with Minecraft Y-down coordinate flip
        Quaternionf q = new Quaternionf(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
        q.rotateZ((float) Math.PI);
        poseStack.mulPose(q);

        // Center model geometry on the physics body position
        switch (ragdollPart) {
            case HEAD:      part.setPos(0, 4, 0);    break; // cube(-4,-8,-4, 8,8,8) center Y=-4
            case TORSO:     part.setPos(0, -6, 0);   break; // cube(-4,0,-2, 8,12,4) center Y=6
            case LEFT_ARM:  part.setPos(-1, -4, 0);  break; // cube(-1,-2,-2, 4,12,4) center (1,4,0)
            case RIGHT_ARM: part.setPos(1, -4, 0);   break; // cube(-3,-2,-2, 4,12,4) center (-1,4,0)
            case LEFT_LEG:
            case RIGHT_LEG: part.setPos(0, -6, 0);   break; // cube(-2,0,-2, 4,12,4) center Y=6
        }
        part.xRot = 0;
        part.yRot = 0;
        part.zRot = 0;

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