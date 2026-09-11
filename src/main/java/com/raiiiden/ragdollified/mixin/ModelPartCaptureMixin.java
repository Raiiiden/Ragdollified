package com.raiiiden.ragdollified.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.client.PoseCaptureSession;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Reads the pose stack as each part is placed, the only place its final drawn frame exists.
// Costs one static field read when no capture is running.
@Mixin(ModelPart.class)
public class ModelPartCaptureMixin {

    @Inject(method = "translateAndRotate", at = @At("TAIL"))
    private void ragdollified$captureDrawnFrame(PoseStack poseStack, CallbackInfo ci) {
        if (PoseCaptureSession.isActive()) {
            PoseCaptureSession.record((ModelPart) (Object) this, poseStack);
        }
    }
}
