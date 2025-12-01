package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.DeathRagdollEntity;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
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
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

public class DeathRagdollRenderer extends EntityRenderer<DeathRagdollEntity> {
    private final PlayerModel<AbstractClientPlayer> normalModel;
    private final PlayerModel<AbstractClientPlayer> slimModel;

    // Armor models
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

        // Create armor models
        this.normalArmorInner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.normalArmorOuter = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        this.slimArmorInner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.slimArmorOuter = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));

        Ragdollified.LOGGER.info("DeathRagdollRenderer created!");
    }

    @Override
    public void render(DeathRagdollEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {

        RagdollManager.ClientRagdoll rag = DeathRagdollManager.get(entity.getId());

        if (rag == null || !rag.isActive()) {
            return;
        }

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

        // Entity renderer already translates to entity position, so undo that
        poseStack.translate(-entity.getX(), -entity.getY(), -entity.getZ());

        // Now translate to absolute torso world position from physics
        poseStack.translate(torso.position.x, torso.position.y, torso.position.z);

        // Render player skin
        ResourceLocation skin = getTextureLocation(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityTranslucent(skin));

        renderRagdollPart(poseStack, vertexConsumer, model.body, torso, torso, torsoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.head, head, torso, headoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.leftLeg, lleg, torso, llegoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.rightLeg, rleg, torso, rlegoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.leftArm, larm, torso, larmoff, packedLight);
        renderRagdollPart(poseStack, vertexConsumer, model.rightArm, rarm, torso, rarmoff, packedLight);

        // Render armor
        renderArmor(entity, poseStack, buffer, packedLight, partialTick,
                torso, head, larm, rarm, lleg, rleg, isSlim);

        poseStack.popPose();
    }

    private void renderArmor(DeathRagdollEntity entity, PoseStack poseStack,
                             MultiBufferSource buffer, int light, float partialTick,
                             RagdollTransform torso, RagdollTransform head,
                             RagdollTransform larm, RagdollTransform rarm,
                             RagdollTransform lleg, RagdollTransform rleg,
                             boolean isSlim) {

        // Get armor pieces
        ItemStack helmet = entity.getHelmet();
        ItemStack chestplate = entity.getChestplate();
        ItemStack leggings = entity.getLeggings();
        ItemStack boots = entity.getBoots();

        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;

        // Render helmet
        if (!helmet.isEmpty() && helmet.getItem() instanceof ArmorItem armorItem) {
            renderArmorPiece(poseStack, buffer, light, helmet, armorItem, head, torso,
                    headoff, outerModel.head, EquipmentSlot.HEAD);
        }

        // Render chestplate
        if (!chestplate.isEmpty() && chestplate.getItem() instanceof ArmorItem armorItem) {
            renderArmorPiece(poseStack, buffer, light, chestplate, armorItem, torso, torso,
                    torsoff, innerModel.body, EquipmentSlot.CHEST);
            renderArmorPiece(poseStack, buffer, light, chestplate, armorItem, larm, torso,
                    larmoff, innerModel.leftArm, EquipmentSlot.CHEST);
            renderArmorPiece(poseStack, buffer, light, chestplate, armorItem, rarm, torso,
                    rarmoff, innerModel.rightArm, EquipmentSlot.CHEST);
        }

        // Render leggings
        if (!leggings.isEmpty() && leggings.getItem() instanceof ArmorItem armorItem) {
            renderArmorPiece(poseStack, buffer, light, leggings, armorItem, torso, torso,
                    torsoff, innerModel.body, EquipmentSlot.LEGS);
            renderArmorPiece(poseStack, buffer, light, leggings, armorItem, lleg, torso,
                    llegoff, innerModel.leftLeg, EquipmentSlot.LEGS);
            renderArmorPiece(poseStack, buffer, light, leggings, armorItem, rleg, torso,
                    rlegoff, innerModel.rightLeg, EquipmentSlot.LEGS);
        }

        // Render boots
        if (!boots.isEmpty() && boots.getItem() instanceof ArmorItem armorItem) {
            renderArmorPiece(poseStack, buffer, light, boots, armorItem, lleg, torso,
                    llegoff, outerModel.leftLeg, EquipmentSlot.FEET);
            renderArmorPiece(poseStack, buffer, light, boots, armorItem, rleg, torso,
                    rlegoff, outerModel.rightLeg, EquipmentSlot.FEET);
        }
    }

    private void renderArmorPiece(PoseStack poseStack, MultiBufferSource buffer, int light,
                                  ItemStack stack, ArmorItem armorItem,
                                  RagdollTransform transform, RagdollTransform torso,
                                  Vector3f[] pivot, ModelPart part, EquipmentSlot slot) {
        if (transform == null) return;

        // Get armor texture
        ResourceLocation armorTexture = getArmorTexture(armorItem, slot);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

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

    private ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot) {
        String material = item.getMaterial().getName();
        String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
        return new ResourceLocation("minecraft", "textures/models/armor/" + material + "_" + layer + ".png");
    }

    private void renderRagdollPart(PoseStack poseStack, VertexConsumer vertexConsumer,
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