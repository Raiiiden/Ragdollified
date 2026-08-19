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

// Draws a CorpseEntity in its frozen ragdoll pose through ClientRagdollRenderer.renderPlayerBody, so
// body and armor match a live ragdoll. Nothing renders until posed; the physics ragdoll stands in.
public class CorpseRenderer extends EntityRenderer<CorpseEntity> {

    // The synthetic flat fallback is drawn from the torso pivot at the grounded origin, so half its
    // thickness would sink below the floor. Captured poses are already correct and are not lifted.
    private static final float GROUND_LIFT = 0.2f;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(CorpseEntity corpse, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (!corpse.isPosed()) return;

        RagdollTransform[] pose = corpse.getCorpsePose();
        boolean usesFallbackPose = pose == null;
        if (usesFallbackPose) pose = buildDefaultPose();

        UUID owner = corpse.getOwnerUUID();
        // pe only feeds the GeckoLib armor proxy and may be null with the owner out of range. The skin
        // resolves range-independently through ClientPlayerSkinCache, and GeckoLib has its own fallback.
        AbstractClientPlayer pe = findPlayer(owner);
        ClientPlayerSkinCache.Skin resolved = ClientPlayerSkinCache.resolve(owner);
        ResourceLocation skin = resolved.texture;
        boolean isSlim = resolved.slim;

        ItemStack helmet = corpse.getArmor("Helmet");
        ItemStack chest  = corpse.getArmor("Chest");
        ItemStack legs   = corpse.getArmor("Legs");
        ItemStack boots  = corpse.getArmor("Boots");

        // The pose stack is already at the entity origin and the transforms are entity-relative, so they
        // pass straight through. Only the synthetic fallback is ground-lifted; a real pose would snap.
        poseStack.pushPose();
        if (usesFallbackPose) poseStack.translate(0.0, GROUND_LIFT, 0.0);
        // The corpse's own UUID keys the blood and damage captured from the ragdoll it replaced, so the
        // body keeps the wounds it died with instead of going clean at the handoff.
        ClientRagdollRenderer.renderPlayerBody(poseStack, buffer, packedLight, 0.0,
                pose[RagdollPart.TORSO.index], pose[RagdollPart.HEAD.index],
                pose[RagdollPart.LEFT_ARM.index], pose[RagdollPart.RIGHT_ARM.index],
                pose[RagdollPart.LEFT_LEG.index], pose[RagdollPart.RIGHT_LEG.index],
                skin, isSlim, helmet, chest, legs, boots, pe, 0f, corpse.getUUID(),
                corpse.getWornCurios());
        poseStack.popPose();

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

    // Entity-relative fallback pose when no settle was reported: the standing humanoid layout from
    // buildHumanoid, pitched back 90 degrees so the body lies on its back.
    private static RagdollTransform[] buildDefaultPose() {
        Quat4f lie = new Quat4f();
        lie.set(new javax.vecmath.AxisAngle4f(1f, 0f, 0f, -(float) (Math.PI / 2.0)));

        RagdollTransform[] p = new RagdollTransform[RagdollTransform.MAX_PARTS];
        p[RagdollPart.TORSO.index]     = lyingPart(RagdollPart.TORSO.index,      0f,     0f,    0f, lie);
        p[RagdollPart.HEAD.index]      = lyingPart(RagdollPart.HEAD.index,       0f,     0.55f, 0f, lie);
        p[RagdollPart.LEFT_ARM.index]  = lyingPart(RagdollPart.LEFT_ARM.index,  -0.35f, -0.13f, 0f, lie);
        p[RagdollPart.RIGHT_ARM.index] = lyingPart(RagdollPart.RIGHT_ARM.index,  0.35f, -0.13f, 0f, lie);
        p[RagdollPart.LEFT_LEG.index]  = lyingPart(RagdollPart.LEFT_LEG.index,  -0.1f,  -0.75f, 0f, lie);
        p[RagdollPart.RIGHT_LEG.index] = lyingPart(RagdollPart.RIGHT_LEG.index,  0.1f,  -0.75f, 0f, lie);
        return p;
    }

    // Standing offset (x,y,z) rotated by lie, carrying the lie rotation onto the part.
    private static RagdollTransform lyingPart(int index, float x, float y, float z, Quat4f lie) {
        return new RagdollTransform(index, rotate(lie, x, y, z), new Quat4f(lie));
    }

    // Rotate a vector by a unit quaternion in plain floats: going through Quat4f turns the torso's
    // zero offset into a zero-length quaternion, which vecmath normalizes into NaN and hides the body.
    private static Vector3f rotate(Quat4f q, float x, float y, float z) {
        float ux = q.x, uy = q.y, uz = q.z, w = q.w;
        float tx = 2f * (uy * z - uz * y);
        float ty = 2f * (uz * x - ux * z);
        float tz = 2f * (ux * y - uy * x);
        float rx = x + w * tx + (uy * tz - uz * ty);
        float ry = y + w * ty + (uz * tx - ux * tz);
        float rz = z + w * tz + (ux * ty - uy * tx);
        return new Vector3f(rx, ry, rz);
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity entity) {
        return DefaultPlayerSkin.getDefaultSkin();
    }
}
