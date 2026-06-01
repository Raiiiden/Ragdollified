package com.raiiiden.ragdollified.mixin;

import com.raiiiden.ragdollified.duck.CameraDuck;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;

@Mixin(Camera.class)
public class CameraMixin implements CameraDuck {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;

    @Override
    public void ragdollified$copyRotation(Quaternionf q) {
        if (q == null) return;
        this.rotation.set(q);
        this.forwards.set(0f, 0f, 1f).rotate(q);
        this.up.set(0f, 1f, 0f).rotate(q);
        this.left.set(1f, 0f, 0f).rotate(q);
    }
}