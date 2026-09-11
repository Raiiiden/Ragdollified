package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

// Records each tracked part's drawn frame, relative to the entity origin, while an entity renders
// (RenderLivingEvent.Pre to Post), with all renderer and mod transforms applied.
@OnlyIn(Dist.CLIENT)
public final class PoseCaptureSession {

    private static Map<ModelPart, RagdollPart> mapping;
    private static Matrix4f inverseReference;
    private static Map<RagdollPart, MobPoseCapture.PartTransform> captured;
    private static int entityId = -1;
    private static boolean useTransforms;

    private PoseCaptureSession() {
    }

    public static void begin(int id, Map<ModelPart, RagdollPart> parts, PoseStack poseStack,
                             boolean transforms) {
        if (parts == null || parts.isEmpty() || poseStack == null) {
            clear();
            return;
        }
        entityId = id;
        mapping = parts;
        useTransforms = transforms;
        // Inverted once per entity rather than per part: this is the frame every part is measured
        // against, and a model can place dozens of them.
        inverseReference = new Matrix4f(poseStack.last().pose()).invert();
        captured = new EnumMap<>(RagdollPart.class);
    }

    // Called by the ModelPart mixin for every part drawn, so the miss path is one field read.
    // First placement wins; later layers (like item-in-hand) re-place parts on a moved stack.
    public static void record(ModelPart part, PoseStack poseStack) {
        Map<ModelPart, RagdollPart> parts = mapping;
        if (parts == null || !useTransforms) return;

        RagdollPart slot = parts.get(part);
        if (slot == null || captured.containsKey(slot)) return;

        Matrix4f relative = new Matrix4f(inverseReference).mul(poseStack.last().pose());
        // Record the cube centre and size, not the pivot, matching where the renderer draws the part.
        org.joml.Vector3f cubeCentre = ClientRagdollRenderer.cubeBoxCenter(part);
        org.joml.Vector3f cubeHalf = ClientRagdollRenderer.cubeBoxHalfExtents(part);
        MobPoseCapture.PartTransform transform = MobPoseCapture.PartTransform.fromRelative(relative,
                cubeCentre.x / 16f, cubeCentre.y / 16f, cubeCentre.z / 16f,
                cubeHalf.x / 16f, cubeHalf.y / 16f, cubeHalf.z / 16f);
        // A frame that read back degenerate is dropped rather than stored, so the slot falls back
        // to its authored placement and a later part in the same draw can still fill it.
        if (transform != null) captured.put(slot, transform);
    }

    // Store what was drawn. The Euler angles go along for the ride as the fallback for a part the
    // model never placed this frame: one hidden by a layer, or skipped by an animation mod.
    public static void commit() {
        if (mapping == null) {
            clear();
            return;
        }

        Map<RagdollPart, MobPoseCapture.PartPose> poses = new EnumMap<>(RagdollPart.class);
        for (Map.Entry<ModelPart, RagdollPart> entry : mapping.entrySet()) {
            ModelPart part = entry.getKey();
            // Only the animated delta, not the absolute angle, since rigs may be baked at an angle.
            PartPose rest = part.getInitialPose();
            poses.put(entry.getValue(), new MobPoseCapture.PartPose(
                    part.xRot - rest.xRot, part.yRot - rest.yRot, part.zRot - rest.zRot));
        }

        if (!captured.isEmpty() || !poses.isEmpty()) {
            MobPoseCapture.storePose(entityId, new MobPoseCapture.MobPose(poses, captured, null));
        }
        clear();
    }

    public static void abort() {
        clear();
    }

    public static boolean isActive() {
        return mapping != null;
    }

    private static void clear() {
        mapping = null;
        inverseReference = null;
        captured = null;
        entityId = -1;
        useTransforms = false;
    }
}
