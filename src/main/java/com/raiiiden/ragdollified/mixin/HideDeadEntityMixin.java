package com.raiiiden.ragdollified.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Creeper;
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
        // Check if entity is dead and invisible (our marker for ragdoll active)
        if (entity.isDeadOrDying() && entity.isInvisible()) {
            // Only cancel for entities that have ragdolls
            if (entity instanceof Player ||
                    entity instanceof Zombie ||
                    entity instanceof Skeleton ||
                    entity instanceof Creeper) {
                ci.cancel(); // Don't render this entity at all (including armor)
            }
        }
    }
}