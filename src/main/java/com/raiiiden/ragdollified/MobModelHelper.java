package com.raiiiden.ragdollified;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Helper to detect what type of model a mob uses
 */
public class MobModelHelper {

    public enum ModelType {
        HUMANOID_STANDARD,  // Standard humanoid (64x64) - zombies, husks, piglins, etc.
        HUMANOID_SKELETON,  // Skeleton model (64x32, thin limbs) - skeletons, strays, wither skeletons
        HUMANOID_DROWNED,   // Drowned model (different arm position)
        ILLAGER,            // Illager model (big head) - pillagers, vindicators, evokers, zombie villagers
        CREEPER,            // Creeper quadruped
        UNSUPPORTED         // Don't create ragdoll
    }

    /**
     * SERVER-SIDE: Check if entity should have a ragdoll based on type
     * Simple type check - fast and works on server
     */
    public static boolean shouldHaveRagdoll(LivingEntity entity) {
        // Never ragdoll players (handled separately)
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }

        // Creepers use special model
        if (entity instanceof Creeper) {
            return true;
        }

        // Skeletons and variants
        if (entity instanceof AbstractSkeleton) {
            return true;
        }

        // Zombies and variants (including drowned, husk)
        if (entity instanceof Zombie) {
            return true;
        }

        // Illagers (pillagers, vindicators, evokers, illusioners)
        if (entity instanceof AbstractIllager) {
            return true;
        }

        // Piglins and variants
        if (entity instanceof Piglin || entity instanceof ZombifiedPiglin) {
            return true;
        }

        // Villagers
        if (entity instanceof AbstractVillager) {
            return true;
        }

        // Check if it's a PathfinderMob (fallback for other mobs)
        if (entity instanceof net.minecraft.world.entity.PathfinderMob) {
            return true;
        }

        return false;
    }

    /**
     * Determine which model type to use based on the mob type string
     * Used by the renderer to select the appropriate model
     */
    public static ModelType getModelTypeFromMobType(String mobType) {
        // Skeleton variants - use skeleton model (64x32, thin)
        if (mobType.contains("skeleton") || mobType.contains("stray")) {
            return ModelType.HUMANOID_SKELETON;
        }

        // Drowned - use drowned model (different arm positions)
        if (mobType.contains("drowned")) {
            return ModelType.HUMANOID_DROWNED;
        }

        // Illagers and villagers - use illager model (big head/nose)
        // This includes: pillagers, vindicators, evokers, illusioners, zombie villagers, villagers, wandering traders
        if (mobType.contains("pillager") ||
                mobType.contains("vindicator") ||
                mobType.contains("evoker") ||
                mobType.contains("illusioner") ||
                mobType.contains("zombie_villager") ||
                mobType.contains("villager") ||
                mobType.contains("wandering_trader")) {
            return ModelType.ILLAGER;
        }

        // Creeper - special quadruped
        if (mobType.contains("creeper")) {
            return ModelType.CREEPER;
        }

        // Everything else uses standard humanoid
        // This includes: zombie, husk, piglin, zombified_piglin, piglin_brute, villager, wandering_trader
        return ModelType.HUMANOID_STANDARD;
    }

    /**
     * Determine model type from entity class (fallback method)
     */
    public static ModelType getModelTypeFromEntity(LivingEntity entity) {
        // Skeletons (including stray, wither skeleton)
        if (entity instanceof AbstractSkeleton) {
            return ModelType.HUMANOID_SKELETON;
        }

        // Drowned
        if (entity instanceof Drowned) {
            return ModelType.HUMANOID_DROWNED;
        }

        // Illagers (pillager, vindicator, evoker, illusioner)
        if (entity instanceof AbstractIllager) {
            return ModelType.ILLAGER;
        }

        // Zombie villagers use illager model
        if (entity instanceof ZombieVillager) {
            return ModelType.ILLAGER;
        }

        // Villagers and wandering traders use illager-like model
        if (entity instanceof AbstractVillager) {
            return ModelType.ILLAGER;
        }

        // Creeper
        if (entity instanceof Creeper) {
            return ModelType.CREEPER;
        }

        // Standard humanoid for everything else
        return ModelType.HUMANOID_STANDARD;
    }

    /**
     * CLIENT-SIDE: Detect actual model type by checking the renderer
     * Most accurate - checks the actual model class used by Minecraft
     */
    @OnlyIn(Dist.CLIENT)
    public static ModelType getActualModelType(LivingEntity entity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            if (!(renderer instanceof LivingEntityRenderer)) {
                return ModelType.UNSUPPORTED;
            }

            EntityModel<?> model = ((LivingEntityRenderer<?, ?>) renderer).getModel();

            // Check if it's an IllagerModel
            if (model instanceof IllagerModel) {
                return ModelType.ILLAGER;
            }

            // Check if it's a CreeperModel
            if (model instanceof net.minecraft.client.model.CreeperModel) {
                return ModelType.CREEPER;
            }

            // Check if it's a SkeletonModel
            if (model instanceof net.minecraft.client.model.SkeletonModel) {
                return ModelType.HUMANOID_SKELETON;
            }

            // Check if it's a DrownedModel
            if (model instanceof net.minecraft.client.model.DrownedModel) {
                return ModelType.HUMANOID_DROWNED;
            }

            // Check if it's any HumanoidModel variant
            if (model instanceof HumanoidModel) {
                return ModelType.HUMANOID_STANDARD;
            }

            return ModelType.UNSUPPORTED;

        } catch (Exception e) {
            return ModelType.UNSUPPORTED;
        }
    }

    /**
     * CLIENT-SIDE: Quick check if entity uses any humanoid-like model
     */
    @OnlyIn(Dist.CLIENT)
    public static boolean isHumanoidLike(LivingEntity entity) {
        ModelType type = getActualModelType(entity);
        return type == ModelType.HUMANOID_STANDARD ||
                type == ModelType.HUMANOID_SKELETON ||
                type == ModelType.HUMANOID_DROWNED ||
                type == ModelType.ILLAGER;
    }

    /**
     * Get a human-readable description of the model type
     */
    public static String getModelTypeDescription(ModelType type) {
        return switch (type) {
            case HUMANOID_STANDARD -> "Standard Humanoid (64x64)";
            case HUMANOID_SKELETON -> "Skeleton Model (64x32, thin)";
            case HUMANOID_DROWNED -> "Drowned Model (modified arms)";
            case ILLAGER -> "Illager Model (big head)";
            case CREEPER -> "Creeper Quadruped";
            case UNSUPPORTED -> "Unsupported";
        };
    }
}