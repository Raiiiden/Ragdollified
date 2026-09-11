package com.raiiiden.ragdollified.api;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientPlayerSkinCache;
import com.raiiiden.ragdollified.client.ClientRagdollRenderer;
import com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat;
import com.raiiiden.ragdollified.client.compat.VisualHealthCompat;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

// Draws a humanoid ragdoll body from outside Ragdollified, identical to a live ragdoll.
@OnlyIn(Dist.CLIENT)
public final class RagdollRenderApi {
    private RagdollRenderApi() {}

    // Draw a player body at entity-relative part transforms indexed by RagdollPart#index.
    // Skin resolves from playerUUID; visualKey keeps handed-off wounds, null draws a clean body.
    public static void renderPlayerBody(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                        RagdollTransform[] pose, @Nullable UUID playerUUID,
                                        ItemStack helmet, ItemStack chestplate,
                                        ItemStack leggings, ItemStack boots,
                                        @Nullable Object visualKey,
                                        @Nullable List<CuriosCompat.WornCurio> curios) {
        if (pose == null) return;
        ClientPlayerSkinCache.Skin skin = ClientPlayerSkinCache.resolve(playerUUID);
        // The live player only feeds the GeckoLib armor proxy and may legitimately be absent; the
        // armor renderer has its own fallback for that.
        AbstractClientPlayer owner = findPlayer(playerUUID);
        ClientRagdollRenderer.renderPlayerBody(poseStack, buffer, packedLight, 0.0,
                at(pose, RagdollPart.TORSO), at(pose, RagdollPart.HEAD),
                at(pose, RagdollPart.LEFT_ARM), at(pose, RagdollPart.RIGHT_ARM),
                at(pose, RagdollPart.LEFT_LEG), at(pose, RagdollPart.RIGHT_LEG),
                skin.texture, skin.slim, helmet, chestplate, leggings, boots,
                owner, 0f, visualKey, curios);
    }

    // Free a visual key's GPU textures but keep the capture, so it can rebuild later.
    // Call when a body leaves render range or unloads.
    public static void releaseCachedTextures(@Nullable Object visualKey) {
        if (visualKey == null) return;
        BetterBloodOverlayCompat.releaseTextures(visualKey);
    }

    // Drop a visual key's captures outright. Only for a body that is gone for good; anything
    // that can come back should use releaseCachedTextures instead, or it returns clean.
    public static void forgetVisuals(@Nullable Object visualKey) {
        if (visualKey == null) return;
        BetterBloodOverlayCompat.releaseTextures(visualKey);
        VisualHealthCompat.evict(visualKey);
    }

    private static RagdollTransform at(RagdollTransform[] pose, RagdollPart part) {
        int i = part.index;
        return i < pose.length ? pose[i] : null;
    }

    @Nullable
    private static AbstractClientPlayer findPlayer(@Nullable UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player.getUUID().equals(uuid)) return player;
        }
        return null;
    }
}
