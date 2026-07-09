package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.npc.AbstractVillager;

public class MobModelHelper {
    public enum ModelType {
        HUMANOID_STANDARD,
        HUMANOID_SKELETON,
        HUMANOID_DROWNED,
        ILLAGER,
        CREEPER,
        QUADRUPED,
        CHICKEN,
        UNSUPPORTED,
        // Appended so existing ordinals (used as the network wire value in
        // RagdollSpawnPacket) stay stable. New winged mobs whose anatomy doesn't fit the
        // humanoid or quadruped skeletons get their own layouts.
        BAT,
        BEE
    }

    public static boolean shouldHaveRagdoll(LivingEntity entity) {
        boolean isPlayer = entity instanceof net.minecraft.world.entity.player.Player;
        String entityId = isPlayer ? "minecraft:player" : EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(entityId, isPlayer)) return false;
        if (isPlayer) return true;
        return getModelTypeFromEntity(entity) != ModelType.UNSUPPORTED;
    }

    public static boolean isSupportedModelType(ModelType type) {
        return type != null && type != ModelType.UNSUPPORTED;
    }

    public static boolean isHumanoidModelType(ModelType type) {
        return type == ModelType.HUMANOID_STANDARD ||
                type == ModelType.HUMANOID_SKELETON ||
                type == ModelType.HUMANOID_DROWNED ||
                type == ModelType.ILLAGER;
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
        if (mobType.contains("zombie") ||
                mobType.contains("husk") ||
                mobType.contains("piglin")) {
            return ModelType.HUMANOID_STANDARD;
        }
        if (mobType.contains("creeper")) {
            return ModelType.CREEPER;
        }
        if (mobType.contains("cow") || mobType.contains("mooshroom")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("sheep")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("pig")) {
            return ModelType.QUADRUPED;
        }
        // Cat + ocelot share the OcelotModel (a four-legged animal), so they ride the
        // quadruped skeleton with their own CAT body profile.
        if (mobType.contains("cat") || mobType.contains("ocelot")) {
            return ModelType.QUADRUPED;
        }
        if (mobType.contains("chicken")) {
            return ModelType.CHICKEN;
        }
        if (mobType.contains("bat")) {
            return ModelType.BAT;
        }
        if (mobType.contains("bee")) {
            return ModelType.BEE;
        }

        return ModelType.UNSUPPORTED;
    }

    public static ModelType getModelTypeFromEntity(LivingEntity entity) {
        if (entity instanceof AbstractSkeleton) return ModelType.HUMANOID_SKELETON;
        if (entity instanceof Drowned) return ModelType.HUMANOID_DROWNED;
        if (entity instanceof AbstractIllager) return ModelType.ILLAGER;
        if (entity instanceof ZombieVillager) return ModelType.ILLAGER;
        if (entity instanceof AbstractVillager) return ModelType.ILLAGER;
        if (entity instanceof Zombie) return ModelType.HUMANOID_STANDARD;
        if (entity instanceof Piglin || entity instanceof ZombifiedPiglin) return ModelType.HUMANOID_STANDARD;
        if (entity instanceof Creeper) return ModelType.CREEPER;
        if (entity instanceof net.minecraft.world.entity.animal.Chicken) return ModelType.CHICKEN;
        if (entity instanceof Cow || entity instanceof Sheep || entity instanceof Pig) return ModelType.QUADRUPED;
        if (entity instanceof net.minecraft.world.entity.animal.Cat
                || entity instanceof net.minecraft.world.entity.animal.Ocelot) return ModelType.QUADRUPED;
        if (entity instanceof net.minecraft.world.entity.ambient.Bat) return ModelType.BAT;
        if (entity instanceof net.minecraft.world.entity.animal.Bee) return ModelType.BEE;

        if (entity instanceof Monster && isLikelyHumanoidByClass(entity)) {
            return ModelType.HUMANOID_STANDARD;
        }

        return ModelType.UNSUPPORTED;
    }

    private static boolean isLikelyHumanoidByClass(LivingEntity entity) {
        Class<?> cls = entity.getClass();
        while (cls != null && cls != Object.class) {
            String name = cls.getSimpleName().toLowerCase();
            if (name.contains("unit")
                    || name.contains("soldier")
                    || name.contains("guard")
                    || name.contains("bandit")
                    || name.contains("operative")
                    || name.contains("pmc")) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
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
            case BAT -> "Bat";
            case BEE -> "Bee";
            case UNSUPPORTED -> "Unsupported";
        };
    }
}
