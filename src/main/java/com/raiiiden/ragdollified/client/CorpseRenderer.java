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

    // The synthetic flat fallback is drawn from the torso pivot at the grounded entity origin.
    // Half its thickness would extend below the floor, so lift that fallback only. Captured poses
    // are already world-collision-correct and must not receive this adjustment.
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
        // pe is only used for the GeckoLib armor proxy below; it may be null when the owner is
        // out of render range (which is exactly when this bug used to show default skin). The
        // skin itself is resolved range-independently via ClientPlayerSkinCache, and the
        // GeckoLib path already falls back to its proxy ArmorStand when pe is null.
        AbstractClientPlayer pe = findPlayer(owner);
        ClientPlayerSkinCache.Skin resolved = ClientPlayerSkinCache.resolve(owner);
        ResourceLocation skin = resolved.texture;
        boolean isSlim = resolved.slim;

        ItemStack helmet = corpse.getArmor("Helmet");
        ItemStack chest  = corpse.getArmor("Chest");
        ItemStack legs   = corpse.getArmor("Legs");
        ItemStack boots  = corpse.getArmor("Boots");

        // The EntityRenderer poseStack is already at the entity origin (camera-relative),
        // and the stored transforms are entity-relative — renderPlayerBody translates to the
        // torso for us, so we pass the transforms directly. distSq=0 -> always draw armor.
        // Only the synthetic timeout/recovery pose needs a ground lift. A captured physics
        // pose is already collision-correct in world space, so lifting it would introduce a
        // visible upward snap during the ragdoll-to-corpse handoff.
        poseStack.pushPose();
        if (usesFallbackPose) poseStack.translate(0.0, GROUND_LIFT, 0.0);
        ClientRagdollRenderer.renderPlayerBody(poseStack, buffer, packedLight, 0.0,
                pose[RagdollPart.TORSO.index], pose[RagdollPart.HEAD.index],
                pose[RagdollPart.LEFT_ARM.index], pose[RagdollPart.RIGHT_ARM.index],
                pose[RagdollPart.LEFT_LEG.index], pose[RagdollPart.RIGHT_LEG.index],
                skin, isSlim, helmet, chest, legs, boots, pe, 0f, -1);
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

    /**
     * Fallback pose, entity-relative — used when no settle pose was reported. The standing
     * humanoid layout (torso-relative offsets mirroring {@code RagdollBodyFactory.buildHumanoid})
     * is pitched back 90° about X so the body lies flat on its back instead of standing straight
     * up. If it ever reads as face-down, flip the sign of the pitch angle below.
     */
    private static RagdollTransform[] buildDefaultPose() {
        Quat4f lie = new Quat4f();
        lie.set(new javax.vecmath.AxisAngle4f(1f, 0f, 0f, -(float) (Math.PI / 2.0)));

        RagdollTransform[] p = new RagdollTransform[6];
        p[RagdollPart.TORSO.index]     = lyingPart(RagdollPart.TORSO.index,      0f,     0f,    0f, lie);
        p[RagdollPart.HEAD.index]      = lyingPart(RagdollPart.HEAD.index,       0f,     0.55f, 0f, lie);
        p[RagdollPart.LEFT_ARM.index]  = lyingPart(RagdollPart.LEFT_ARM.index,  -0.35f, -0.13f, 0f, lie);
        p[RagdollPart.RIGHT_ARM.index] = lyingPart(RagdollPart.RIGHT_ARM.index,  0.35f, -0.13f, 0f, lie);
        p[RagdollPart.LEFT_LEG.index]  = lyingPart(RagdollPart.LEFT_LEG.index,  -0.1f,  -0.75f, 0f, lie);
        p[RagdollPart.RIGHT_LEG.index] = lyingPart(RagdollPart.RIGHT_LEG.index,  0.1f,  -0.75f, 0f, lie);
        return p;
    }

    /** Standing offset (x,y,z) rotated by {@code lie}, carrying the lie rotation onto the part. */
    private static RagdollTransform lyingPart(int index, float x, float y, float z, Quat4f lie) {
        return new RagdollTransform(index, rotate(lie, x, y, z), new Quat4f(lie));
    }

    /**
     * Rotate a vector by a unit quaternion via {@code v' = v + 2w(u×v) + 2u×(u×v)}, u = q.xyz.
     * Done with plain floats on purpose: routing this through a {@code Quat4f(x,y,z,0)} makes the
     * torso's (0,0,0) offset a zero-length quaternion, which vecmath normalizes → divide-by-zero →
     * NaN, collapsing the whole model matrix and rendering the body invisible. This form never
     * normalizes, so a zero vector maps cleanly to (0,0,0).
     */
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
