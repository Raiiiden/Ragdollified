package com.raiiiden.ragdollified;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class MobModelHelper {

    public enum ModelType {
        HUMANOID_STANDARD,
        HUMANOID_SKELETON,
        HUMANOID_DROWNED,
        ILLAGER,
        CREEPER,
        QUADRUPED,
        CHICKEN,
        UNSUPPORTED
    }

    public static boolean shouldHaveRagdoll(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player) return false;
        if (entity instanceof Creeper) return true;
        if (entity instanceof AbstractSkeleton) return true;
        if (entity instanceof Zombie) return true;
        if (entity instanceof AbstractIllager) return true;
        if (entity instanceof Piglin || entity instanceof ZombifiedPiglin) return true;
        if (entity instanceof AbstractVillager) return true;

        // Animals
        if (entity instanceof Cow) return true;
        if (entity instanceof Sheep) return true;
        if (entity instanceof Pig) return true;
        if (entity instanceof net.minecraft.world.entity.animal.Chicken) return true;

        if (entity instanceof net.minecraft.world.entity.PathfinderMob) return true;

        return false;
    }

    public static ModelType getModelTypeFromMobType(String mobType) {
        if (mobType.contains("skeleton") || mobType.contains("stray")) {
            return ModelType.HUMANOID_SKELETON;
        }
        if (mobType.contains("drowned")) {
            return ModelType.HUMANOID_DROWNED;
        }
        if (mobType.contains("pillager") ||
                mobType.contains("vindicator") ||
                mobType.contains("evoker") ||
                mobType.contains("illusioner") ||
                mobType.contains("zombie_villager") ||
                mobType.contains("villager") ||
                mobType.contains("wandering_trader")) {
            return ModelType.ILLAGER;
        }
        if (mobType.contains("creeper")) {
            return ModelType.CREEPER;
        }

        // Quadrupeds
        if (mobType.contains("cow") || mobType.contains("mooshroom")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("sheep")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("pig")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("chicken")) {
            return ModelType.CHICKEN;
        }

        return ModelType.HUMANOID_STANDARD;
    }

    public static ModelType getModelTypeFromEntity(LivingEntity entity) {
        if (entity instanceof AbstractSkeleton) return ModelType.HUMANOID_SKELETON;
        if (entity instanceof Drowned) return ModelType.HUMANOID_DROWNED;
        if (entity instanceof AbstractIllager) return ModelType.ILLAGER;
        if (entity instanceof ZombieVillager) return ModelType.ILLAGER;
        if (entity instanceof AbstractVillager) return ModelType.ILLAGER;
        if (entity instanceof Creeper) return ModelType.CREEPER;
        if (entity instanceof net.minecraft.world.entity.animal.Chicken) return ModelType.CHICKEN;
        if (entity instanceof Cow || entity instanceof Sheep || entity instanceof Pig) return ModelType.QUADRUPED;
        return ModelType.HUMANOID_STANDARD;
    }

    @OnlyIn(Dist.CLIENT)
    public static ModelType getActualModelType(LivingEntity entity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            if (!(renderer instanceof LivingEntityRenderer)) {
                return ModelType.UNSUPPORTED;
            }

            EntityModel<?> model = ((LivingEntityRenderer<?, ?>) renderer).getModel();

            if (model instanceof IllagerModel) return ModelType.ILLAGER;
            if (model instanceof net.minecraft.client.model.CreeperModel) return ModelType.CREEPER;
            if (model instanceof net.minecraft.client.model.SkeletonModel) return ModelType.HUMANOID_SKELETON;
            if (model instanceof net.minecraft.client.model.DrownedModel) return ModelType.HUMANOID_DROWNED;
            if (model instanceof ChickenModel) return ModelType.CHICKEN;
            if (model instanceof QuadrupedModel) return ModelType.QUADRUPED;
            if (model instanceof HumanoidModel) return ModelType.HUMANOID_STANDARD;

            return ModelType.UNSUPPORTED;

        } catch (Exception e) {
            return ModelType.UNSUPPORTED;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static boolean isHumanoidLike(LivingEntity entity) {
        ModelType type = getActualModelType(entity);
        return type == ModelType.HUMANOID_STANDARD ||
                type == ModelType.HUMANOID_SKELETON ||
                type == ModelType.HUMANOID_DROWNED ||
                type == ModelType.ILLAGER;
    }

    public static String getModelTypeDescription(ModelType type) {
        return switch (type) {
            case HUMANOID_STANDARD -> "Standard Humanoid (64x64)";
            case HUMANOID_SKELETON -> "Skeleton Model (64x32, thin)";
            case HUMANOID_DROWNED -> "Drowned Model (modified arms)";
            case ILLAGER -> "Illager Model (big head)";
            case CREEPER -> "Creeper Quadruped";
            case QUADRUPED -> "Quadruped Animal";
            case CHICKEN -> "Chicken";
            case UNSUPPORTED -> "Unsupported";
        };
    }
}