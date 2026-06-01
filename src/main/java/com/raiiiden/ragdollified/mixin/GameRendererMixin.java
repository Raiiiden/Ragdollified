package com.raiiiden.ragdollified.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.duck.CameraDuck;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Camera mainCamera;

    private boolean rdeath$active = false;

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void rdeath$afterCameraSetup(float partialTick, long nanoTime,
                                         PoseStack poseStack, CallbackInfo ci) {
        Minecraft mc = minecraft;
        if (mc.player == null)                                 { rdeath$active = false; return; }
        if (!RagdollifiedConfig.ENABLE_PLAYER_RAGDOLLS.get()) { rdeath$active = false; return; }
        if (!RagdollifiedConfig.ENABLE_DEATH_CAMERA.get())    { rdeath$active = false; return; }
        if (!mc.player.isDeadOrDying())                        { rdeath$active = false; return; }

        ClientRagdoll rag = ClientRagdollManager.get(mc.player.getId());
        if (rag == null || rag.isDestroyed()) { rdeath$active = false; return; }

        ClientRagdoll.TransformSnapshot snap = rag.getSnapshot();
        if (snap == null || snap.destroyed)   { rdeath$active = false; return; }

        RagdollTransform head = rag.getSmoothedTransform(RagdollPart.HEAD);
        if (head == null) {
            head = snap.getInterpolatedTransform(RagdollPart.HEAD, partialTick);
            if (head == null) return;
        }

        rdeath$active = true;

        Quaternionf rot = new Quaternionf(
                head.rotation.x, head.rotation.y,
                head.rotation.z, head.rotation.w);

        Vector3f look = rot.transform(new Vector3f(0f, 0f, -1f));
        float yaw   = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, -look.y))));

        Vector3f eyeOffset = rot.transform(new Vector3f(0f, 0.05f, -0.6f));

        double camX = head.position.x + eyeOffset.x;
        double camY = head.position.y + eyeOffset.y;
        double camZ = head.position.z + eyeOffset.z;

        if (mc.level != null) {
            float[] offsets = {0f, 0.1f, -0.1f};
            boolean blocked = false;
            outer:
            for (float ox : offsets) for (float oy : offsets) for (float oz : offsets) {
                BlockPos bp = new BlockPos((int) Math.floor(camX + ox), (int) Math.floor(camY + oy), (int) Math.floor(camZ + oz));
                net.minecraft.world.level.block.state.BlockState bs = mc.level.getBlockState(bp);
                if (!bs.isAir() && !bs.getCollisionShape(mc.level, bp).isEmpty()) {
                    blocked = true;
                    break outer;
                }
            }
            if (blocked) {
                for (float t = 0.1f; t <= 1.0f; t += 0.05f) {
                    double tx = head.position.x + eyeOffset.x * (1f - t);
                    double ty = head.position.y + eyeOffset.y * (1f - t);
                    double tz = head.position.z + eyeOffset.z * (1f - t);
                    boolean clear = true;
                    for (float ox : offsets) for (float oy : offsets) for (float oz : offsets) {
                        BlockPos tb = new BlockPos((int) Math.floor(tx + ox), (int) Math.floor(ty + oy), (int) Math.floor(tz + oz));
                        net.minecraft.world.level.block.state.BlockState ts = mc.level.getBlockState(tb);
                        if (!ts.isAir() && !ts.getCollisionShape(mc.level, tb).isEmpty()) { clear = false; break; }
                    }
                    if (clear) { camX = tx; camY = ty; camZ = tz; break; }
                    if (t + 0.05f > 1.0f) { camX = head.position.x; camY = head.position.y; camZ = head.position.z; }
                }
            }
        }

        ((CameraAccessor) mainCamera).invokerSetPosition(camX, camY, camZ);
        ((CameraAccessor) mainCamera).invokerSetRotation(yaw, pitch);
        ((CameraDuck) mainCamera).ragdollified$copyRotation(rot);
    }
}