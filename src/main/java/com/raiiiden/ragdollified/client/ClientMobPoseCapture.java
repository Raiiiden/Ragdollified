package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class ClientMobPoseCapture {
    private ClientMobPoseCapture() {
    }

    public static void capturePose(int entityId, EntityModel<?> model) {
        if (model == null) return;

        try {
            MobPoseCapture.MobPose pose = null;

            if (model instanceof HumanoidModel<?> humanoidModel) {
                pose = captureHumanoidPose(humanoidModel);
            } else if (model instanceof CreeperModel<?> creeperModel) {
                pose = captureCreeperPose(creeperModel);
            }

            MobPoseCapture.storePose(entityId, pose);
        } catch (Exception ignored) {
        }
    }

    private static MobPoseCapture.MobPose captureHumanoidPose(HumanoidModel<?> model) {
        Map<RagdollPart, MobPoseCapture.PartPose> poses = new HashMap<>();

        poses.put(RagdollPart.TORSO, capturePartPose(model.body));
        poses.put(RagdollPart.HEAD, capturePartPose(model.head));
        poses.put(RagdollPart.LEFT_ARM, capturePartPose(model.leftArm));
        poses.put(RagdollPart.RIGHT_ARM, capturePartPose(model.rightArm));
        poses.put(RagdollPart.LEFT_LEG, capturePartPose(model.leftLeg));
        poses.put(RagdollPart.RIGHT_LEG, capturePartPose(model.rightLeg));

        return new MobPoseCapture.MobPose(poses);
    }

    private static MobPoseCapture.MobPose captureCreeperPose(CreeperModel<?> model) {
        Map<RagdollPart, MobPoseCapture.PartPose> poses = new HashMap<>();

        ModelPart root = model.root();
        poses.put(RagdollPart.TORSO, capturePartPose(root.getChild("body")));
        poses.put(RagdollPart.HEAD, capturePartPose(root.getChild("head")));
        poses.put(RagdollPart.LEFT_ARM, capturePartPose(root.getChild("left_front_leg")));
        poses.put(RagdollPart.RIGHT_ARM, capturePartPose(root.getChild("right_front_leg")));
        poses.put(RagdollPart.LEFT_LEG, capturePartPose(root.getChild("left_hind_leg")));
        poses.put(RagdollPart.RIGHT_LEG, capturePartPose(root.getChild("right_hind_leg")));

        return new MobPoseCapture.MobPose(poses);
    }

    private static MobPoseCapture.PartPose capturePartPose(ModelPart part) {
        if (part == null) return new MobPoseCapture.PartPose(0, 0, 0);
        return new MobPoseCapture.PartPose(part.xRot, part.yRot, part.zRot);
    }
}
