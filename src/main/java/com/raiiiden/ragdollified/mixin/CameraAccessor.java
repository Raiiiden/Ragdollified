package com.raiiiden.ragdollified.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("move")
    void invokerMove(double x, double y, double z);

    @Invoker("setPosition")
    void invokerSetPosition(double x, double y, double z);

    @Invoker("setRotation")
    void invokerSetRotation(float yRot, float xRot);
}