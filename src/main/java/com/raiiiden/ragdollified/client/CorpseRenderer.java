package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.UUID;

/**
 * Renders a {@link CorpseEntity} as the frozen ragdoll pose. Reuses
 * {@link ClientRagdollRenderer#renderPlayerBody} so the body + armor look identical to a
 * live ragdoll (including the leather/modded-armor fixes). Nothing is drawn until the
 * corpse is posed (until then the client physics ragdoll is the visual).
 */
public class CorpseRenderer extends EntityRenderer<CorpseEntity> {

    // Default standing pose for the settle-timeout case (no captured pose). Rotation is
    // identity — same as a freshly-spawned ragdoll (RagdollBodyFactory.buildHumanoid passes
    // baseQuat=identity); the renderer's own rotateZ(π) handles the upright orientation.
    private static final Quat4f IDENTITY = new Quat4f(0f, 0f, 0f, 1f);

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(CorpseEntity corpse, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (!corpse.isPosed()) return;

        RagdollTransform[] pose = corpse.getCorpsePose();
        if (pose == null) pose = buildDefaultPose();

        UUID owner = corpse.getOwnerUUID();
        AbstractClientPlayer pe = findPlayer(owner);
        ResourceLocation skin;
        boolean isSlim;
        if (pe != null) {
            skin = pe.getSkinTextureLocation();
            isSlim = "slim".equals(pe.getModelName());
        } else if (owner != null) {
            skin = DefaultPlayerSkin.getDefaultSkin(owner);
            isSlim = DefaultPlayerSkin.getSkinModelName(owner).equals("slim");
        } else {
            skin = DefaultPlayerSkin.getDefaultSkin();
            isSlim = false;
        }

        ItemStack helmet = corpse.getArmor("Helmet");
        ItemStack chest  = corpse.getArmor("Chest");
        ItemStack legs   = corpse.getArmor("Legs");
        ItemStack boots  = corpse.getArmor("Boots");

        // The EntityRenderer poseStack is already at the entity origin (camera-relative),
        // and the stored transforms are entity-relative — renderPlayerBody translates to the
        // torso for us, so we pass the transforms directly. distSq=0 -> always draw armor.
        ClientRagdollRenderer.renderPlayerBody(poseStack, buffer, packedLight, 0.0,
                pose[RagdollPart.TORSO.index], pose[RagdollPart.HEAD.index],
                pose[RagdollPart.LEFT_ARM.index], pose[RagdollPart.RIGHT_ARM.index],
                pose[RagdollPart.LEFT_LEG.index], pose[RagdollPart.RIGHT_LEG.index],
                skin, isSlim, helmet, chest, legs, boots, pe, 0f);

        super.render(corpse, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static AbstractClientPlayer findPlayer(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.getUUID().equals(uuid)) return p;
        }
        return null;
    }

    /**
     * Standing humanoid pose, entity-relative — used when no settle pose was reported.
     * Offsets mirror {@code RagdollBodyFactory.buildHumanoid} (torso-relative spawn layout)
     * with identity rotation, so it renders like a freshly-spawned upright ragdoll.
     */
    private static RagdollTransform[] buildDefaultPose() {
        RagdollTransform[] p = new RagdollTransform[6];
        p[RagdollPart.TORSO.index]     = new RagdollTransform(RagdollPart.TORSO.index,     new Vector3f(0f, 0f, 0f),         new Quat4f(IDENTITY));
        p[RagdollPart.HEAD.index]      = new RagdollTransform(RagdollPart.HEAD.index,      new Vector3f(0f, 0.55f, 0f),      new Quat4f(IDENTITY));
        p[RagdollPart.LEFT_ARM.index]  = new RagdollTransform(RagdollPart.LEFT_ARM.index,  new Vector3f(-0.35f, -0.13f, 0f), new Quat4f(IDENTITY));
        p[RagdollPart.RIGHT_ARM.index] = new RagdollTransform(RagdollPart.RIGHT_ARM.index, new Vector3f(0.35f, -0.13f, 0f),  new Quat4f(IDENTITY));
        p[RagdollPart.LEFT_LEG.index]  = new RagdollTransform(RagdollPart.LEFT_LEG.index,  new Vector3f(-0.1f, -0.75f, 0f),  new Quat4f(IDENTITY));
        p[RagdollPart.RIGHT_LEG.index] = new RagdollTransform(RagdollPart.RIGHT_LEG.index, new Vector3f(0.1f, -0.75f, 0f),   new Quat4f(IDENTITY));
        return p;
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity entity) {
        return DefaultPlayerSkin.getDefaultSkin();
    }
}
