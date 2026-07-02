package com.raiiiden.ragdollified.mixin;

import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents rendering of dead entities that have ragdolls
 */
@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public class HideDeadEntityMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cancelDeadEntityRender(LivingEntity entity, float entityYaw, float partialTicks,
                                        com.mojang.blaze3d.vertex.PoseStack poseStack,
                                        net.minecraft.client.renderer.MultiBufferSource buffer,
                                        int packedLight, CallbackInfo ci) {
        // Gate on the mod's own authoritative "this entity has a ragdoll" record rather than
        // entity.isInvisible(). The invisible flag is network-synced entity data: the server
        // (especially one without this mod) re-syncs it back to false shortly after death,
        // which would un-hide the dying mob and let the vanilla fall-over animation render.
        // processedEntityIds is set on both spawn paths (local death + RagdollSpawnPacket) and
        // isn't touchable by vanilla data sync, so it can't flicker.
        if (entity.isDeadOrDying() && ClientRagdollManager.hasRagdollFor(entity.getId())) {
            ci.cancel(); // Don't render this entity at all (including armor)
        }
    }
}
