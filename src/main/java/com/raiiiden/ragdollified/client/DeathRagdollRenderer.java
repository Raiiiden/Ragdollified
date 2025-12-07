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

        // --- THESE ARE THE MISSING LINES ---
        // We must define the variables 'helmet', 'chestplate', etc. before using them.
        // If your DeathRagdollEntity uses different method names, change .getHelmet() to match.
        ItemStack helmet = entity.getHelmet();
        ItemStack chestplate = entity.getChestplate();
        ItemStack leggings = entity.getLeggings();
        ItemStack boots = entity.getBoots();
        // -----------------------------------

        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;

        // Render helmet
        if (!helmet.isEmpty() && helmet.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.HEAD);
            VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            renderRagdollPart(poseStack, vertexConsumer, outerModel.head, head, torso, headoff, light);
        }

        // Render chestplate
        if (!chestplate.isEmpty() && chestplate.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.CHEST);
            VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            // Inner body
            renderRagdollPart(poseStack, innerConsumer, innerModel.body, torso, torso, torsoff, light);
            // Inner arms
            renderRagdollPart(poseStack, innerConsumer, innerModel.leftArm, larm, torso, larmoff, light);
            renderRagdollPart(poseStack, innerConsumer, innerModel.rightArm, rarm, torso, rarmoff, light);
        }

        // Render leggings
        if (!leggings.isEmpty() && leggings.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.LEGS);
            VertexConsumer innerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            // Inner body (for legs part of the armor model)
            renderRagdollPart(poseStack, innerConsumer, innerModel.body, torso, torso, torsoff, light);
            // Inner legs
            renderRagdollPart(poseStack, innerConsumer, innerModel.leftLeg, lleg, torso, llegoff, light);
            renderRagdollPart(poseStack, innerConsumer, innerModel.rightLeg, rleg, torso, rlegoff, light);
        }

        // Render boots
        if (!boots.isEmpty() && boots.getItem() instanceof ArmorItem armorItem) {
            ResourceLocation armorTexture = getArmorTexture(armorItem, EquipmentSlot.FEET);
            VertexConsumer outerConsumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture));

            // Outer legs
            renderRagdollPart(poseStack, outerConsumer, outerModel.leftLeg, lleg, torso, llegoff, light);
            renderRagdollPart(poseStack, outerConsumer, outerModel.rightLeg, rleg, torso, rlegoff, light);
        }
    }

    private ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot) {
        String texturePath = item.getArmorTexture(
                new ItemStack(item),
                null,
                slot,
                null
        );

        if (texturePath != null) {
            // If the texture path already contains "textures/", it's a full path - use it directly
            if (texturePath.contains("textures/")) {
                return new ResourceLocation(texturePath);
            }
            // Otherwise it might be a namespace:path format, extract just the path
            String[] parts = texturePath.split(":");
            String actualPath = parts.length > 1 ? parts[1] : parts[0];
            return new ResourceLocation(actualPath);
        }

        // Fallback: construct the path manually
        String materialName = item.getMaterial().getName();

        // Strip namespace if present (e.g., "fracturepoint:fracturepoint" → "fracturepoint")
        if (materialName.contains(":")) {
            materialName = materialName.split(":")[1];
        }

        String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
        return new ResourceLocation("minecraft", "textures/models/armor/" + materialName + "_" + layer + ".png");
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