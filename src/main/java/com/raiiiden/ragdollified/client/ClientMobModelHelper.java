package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientMobModelHelper {
    private static final Set<String> HUMANOID_PART_NAMES = Set.of(
            "head", "body", "rightarm", "leftarm", "rightleg", "leftleg"
    );

    private ClientMobModelHelper() {
    }

    public static MobModelHelper.ModelType getActualModelType(LivingEntity entity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            if (!(renderer instanceof LivingEntityRenderer)) {
                return MobModelHelper.ModelType.UNSUPPORTED;
            }

            EntityModel<?> model = ((LivingEntityRenderer<?, ?>) renderer).getModel();

            if (model instanceof IllagerModel) return MobModelHelper.ModelType.ILLAGER;
            if (model instanceof net.minecraft.client.model.CreeperModel) return MobModelHelper.ModelType.CREEPER;
            if (model instanceof net.minecraft.client.model.SkeletonModel) return MobModelHelper.ModelType.HUMANOID_SKELETON;
            if (model instanceof net.minecraft.client.model.DrownedModel) return MobModelHelper.ModelType.HUMANOID_DROWNED;
            if (model instanceof ChickenModel) return MobModelHelper.ModelType.CHICKEN;
            if (model instanceof QuadrupedModel) return MobModelHelper.ModelType.QUADRUPED;
            if (model instanceof HumanoidModel) return MobModelHelper.ModelType.HUMANOID_STANDARD;

            if (model instanceof net.minecraft.client.model.HierarchicalModel && hasHumanoidParts(model)) {
                return MobModelHelper.ModelType.HUMANOID_STANDARD;
            }

            return MobModelHelper.ModelType.UNSUPPORTED;
        } catch (Exception e) {
            return MobModelHelper.ModelType.UNSUPPORTED;
        }
    }

    private static boolean hasHumanoidParts(EntityModel<?> model) {
        Set<String> found = new HashSet<>();

        Class<?> cls = model.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != ModelPart.class) continue;
                String n = f.getName().toLowerCase().replace("_", "");
                if (HUMANOID_PART_NAMES.contains(n)) found.add(n);
            }
            cls = cls.getSuperclass();
        }

        if (found.containsAll(HUMANOID_PART_NAMES)) return true;

        for (Method m : model.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != ModelPart.class) continue;
            String n = m.getName().toLowerCase();
            if (n.startsWith("get")) n = n.substring(3);
            n = n.replace("_", "");
            if (HUMANOID_PART_NAMES.contains(n)) found.add(n);
        }

        return found.containsAll(HUMANOID_PART_NAMES);
    }

    public static boolean isHumanoidLike(LivingEntity entity) {
        return MobModelHelper.isHumanoidModelType(getActualModelType(entity));
    }
}
