package com.raiiiden.ragdollified.mixin;

import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Stops the original dead entity rendering once its ragdoll exists, fire and shadow included.
@Mixin(EntityRenderDispatcher.class)
public class HideDeadEntityMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cancelDeadEntityRender(Entity entity,
                                        double x, double y, double z,
                                        float entityYaw, float partialTicks,
                                        PoseStack poseStack, MultiBufferSource buffer,
                                        int packedLight, CallbackInfo ci) {
        // Hide only once a physics ragdoll exists. A queue entry is not enough — it can be dropped or
        // fail to construct, and vanilla's death render is the safer fallback than an invisible body.
        if (ClientRagdollManager.isEntityExplicitlyHidden(entity.getId())
                || (entity instanceof LivingEntity living
                && living.isDeadOrDying()
                && ClientRagdollManager.hasRagdollFor(entity.getId()))) {
            ci.cancel();
        }
    }
}
