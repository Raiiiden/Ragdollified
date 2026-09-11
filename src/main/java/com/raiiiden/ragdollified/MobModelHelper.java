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
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

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
        // Appended so existing ordinals, which are the network wire value, stay stable. Winged mobs that
        // fit neither the humanoid nor quadruped skeleton get their own layouts.
        BAT,
        BEE,
        // Appended to preserve every existing network ordinal.
        WITCH,
        EQUINE,
        // Appended to preserve every existing network ordinal.
        WOLF,
        FOX,
        // Appended to preserve every existing network ordinal.
        PANDA,
        IRON_GOLEM,
        // Appended to preserve every existing network ordinal.
        GOAT,
        POLAR_BEAR,
        TURTLE,
        ENDERMAN,
        // Appended to preserve every existing network ordinal.
        CAMEL,
        LLAMA,
        RABBIT,
        FROG,
        // Appended to preserve all packet ordinals already in use.
        HOGLIN,
        SNIFFER,
        RAVAGER,
        PHANTOM,
        PARROT,
        SLIME,
        MAGMA_CUBE,
        // Appended to preserve all earlier network ordinals.
        SILVERFISH,
        ENDERMITE,
        ALLAY,
        STRIDER,
        SNOW_GOLEM,
        BLAZE,
        SPIDER,
        // Appended to preserve all earlier network ordinals.
        SHULKER,
        GHAST,
        VEX,
        WARDEN,
        // Appended to preserve all earlier network ordinals. Guardians, the aquatic mobs and the two
        // bosses were the last vanilla families with no ragdoll at all.
        GUARDIAN,
        SQUID,
        DOLPHIN,
        AXOLOTL,
        // One type for every small fish: cod, salmon, tropical fish, pufferfish and tadpole all reduce
        // to a body plus a tail, and their differing dimensions ride on the BodyProfile.
        FISH,
        WITHER,
        ENDER_DRAGON,
        // No authored skeleton: the rig is measured at runtime by GenericRigExtractor.
        // What a modded mob gets instead of UNSUPPORTED (no ragdoll).
        GENERIC
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
                type == ModelType.ILLAGER ||
                type == ModelType.WITCH ||
                type == ModelType.ENDERMAN;
    }

    // The path half of an entity id, since the keyword tests are authored against vanilla paths and a
    // raw contains() would let a mod's namespace decide what a mob looks like.
    public static String mobPath(String mobType) {
        int colon = mobType.indexOf(':');
        return colon >= 0 ? mobType.substring(colon + 1) : mobType;
    }

    public static ModelType getModelTypeFromMobType(String mobType) {
        String path = mobPath(mobType);
        // These must precede the broad skeleton/zombie tests below.
        if (mobType.contains("skeleton_horse") || mobType.contains("zombie_horse") ||
                mobType.endsWith(":horse") || mobType.endsWith(":donkey") ||
                mobType.endsWith(":mule")) {
            return ModelType.EQUINE;
        }
        if (mobType.contains("witch")) {
            return ModelType.WITCH;
        }
        if (mobType.endsWith(":wolf")) return ModelType.WOLF;
        if (mobType.endsWith(":fox")) return ModelType.FOX;
        if (mobType.endsWith(":panda")) return ModelType.PANDA;
        if (mobType.endsWith(":iron_golem")) return ModelType.IRON_GOLEM;
        if (mobType.endsWith(":goat")) return ModelType.GOAT;
        if (mobType.endsWith(":polar_bear")) return ModelType.POLAR_BEAR;
        if (mobType.endsWith(":turtle")) return ModelType.TURTLE;
        if (mobType.endsWith(":enderman")) return ModelType.ENDERMAN;
        if (mobType.endsWith(":camel")) return ModelType.CAMEL;
        if (mobType.endsWith(":llama") || mobType.endsWith(":trader_llama")) return ModelType.LLAMA;
        if (mobType.endsWith(":rabbit")) return ModelType.RABBIT;
        if (mobType.endsWith(":frog")) return ModelType.FROG;
        if (mobType.endsWith(":hoglin") || mobType.endsWith(":zoglin")) return ModelType.HOGLIN;
        if (mobType.endsWith(":sniffer")) return ModelType.SNIFFER;
        if (mobType.endsWith(":ravager")) return ModelType.RAVAGER;
        if (mobType.endsWith(":phantom")) return ModelType.PHANTOM;
        if (mobType.endsWith(":parrot")) return ModelType.PARROT;
        if (mobType.endsWith(":magma_cube")) return ModelType.MAGMA_CUBE;
        if (mobType.endsWith(":slime")) return ModelType.SLIME;
        if (mobType.endsWith(":silverfish")) return ModelType.SILVERFISH;
        if (mobType.endsWith(":endermite")) return ModelType.ENDERMITE;
        if (mobType.endsWith(":allay")) return ModelType.ALLAY;
        if (mobType.endsWith(":strider")) return ModelType.STRIDER;
        if (mobType.endsWith(":snow_golem")) return ModelType.SNOW_GOLEM;
        if (mobType.endsWith(":blaze")) return ModelType.BLAZE;
        if (mobType.endsWith(":spider") || mobType.endsWith(":cave_spider")) return ModelType.SPIDER;
        if (mobType.endsWith(":shulker")) return ModelType.SHULKER;
        if (mobType.endsWith(":ghast")) return ModelType.GHAST;
        if (mobType.endsWith(":vex")) return ModelType.VEX;
        if (mobType.endsWith(":warden")) return ModelType.WARDEN;
        if (mobType.endsWith(":guardian") || mobType.endsWith(":elder_guardian")) return ModelType.GUARDIAN;
        if (mobType.endsWith(":squid") || mobType.endsWith(":glow_squid")) return ModelType.SQUID;
        if (mobType.endsWith(":dolphin")) return ModelType.DOLPHIN;
        if (mobType.endsWith(":axolotl")) return ModelType.AXOLOTL;
        if (mobType.endsWith(":cod") || mobType.endsWith(":salmon") || mobType.endsWith(":tropical_fish")
                || mobType.endsWith(":pufferfish") || mobType.endsWith(":tadpole")) return ModelType.FISH;
        if (mobType.endsWith(":wither")) return ModelType.WITHER;
        if (mobType.endsWith(":ender_dragon")) return ModelType.ENDER_DRAGON;
        if (path.contains("skeleton") || path.contains("stray")) {
            return ModelType.HUMANOID_SKELETON;
        }
        if (path.contains("drowned")) {
            return ModelType.HUMANOID_DROWNED;
        }
        if (path.contains("pillager") ||
                path.contains("vindicator") ||
                path.contains("evoker") ||
                path.contains("illusioner") ||
                path.contains("zombie_villager") ||
                path.contains("villager") ||
                path.contains("wandering_trader")) {
            return ModelType.ILLAGER;
        }
        if (path.contains("zombie") ||
                path.contains("husk") ||
                path.contains("piglin")) {
            return ModelType.HUMANOID_STANDARD;
        }
        if (path.contains("creeper")) {
            return ModelType.CREEPER;
        }
        if (path.contains("cow") || path.contains("mooshroom")) {
            return ModelType.QUADRUPED;
        }
        if (path.contains("sheep")) {
            return ModelType.QUADRUPED;
        }
        if (path.contains("pig")) {
            return ModelType.QUADRUPED;
        }
        // Cat + ocelot share the OcelotModel (a four-legged animal), so they ride the
        // quadruped skeleton with their own CAT body profile.
        if (path.contains("cat") || path.contains("ocelot")) {
            return ModelType.QUADRUPED;
        }
        if (path.contains("chicken")) {
            return ModelType.CHICKEN;
        }
        if (path.contains("bat")) {
            return ModelType.BAT;
        }
        if (path.contains("bee")) {
            return ModelType.BEE;
        }

        // Nothing matched: a modded humanoid lands here and the client resolves it from its real model
        // class, which this string-only server-side view cannot see.
        return ModelType.UNSUPPORTED;
    }

    public static ModelType getModelTypeFromEntity(LivingEntity entity) {
        // Camel and llama inherit horse base classes but have unrelated model trees.
        if (entity instanceof net.minecraft.world.entity.animal.camel.Camel) return ModelType.CAMEL;
        if (entity instanceof net.minecraft.world.entity.animal.horse.Llama) return ModelType.LLAMA;
        // AbstractHorse also includes llamas, whose tall-necked LlamaModel is not compatible
        // with the HorseModel layout authored here.
        if (entity instanceof AbstractHorse
                && !(entity instanceof net.minecraft.world.entity.animal.horse.Llama)) return ModelType.EQUINE;
        if (entity instanceof Witch) return ModelType.WITCH;
        if (entity instanceof net.minecraft.world.entity.animal.Wolf) return ModelType.WOLF;
        if (entity instanceof net.minecraft.world.entity.animal.Fox) return ModelType.FOX;
        if (entity instanceof net.minecraft.world.entity.animal.Panda) return ModelType.PANDA;
        if (entity instanceof net.minecraft.world.entity.animal.IronGolem) return ModelType.IRON_GOLEM;
        if (entity instanceof net.minecraft.world.entity.animal.goat.Goat) return ModelType.GOAT;
        if (entity instanceof net.minecraft.world.entity.animal.PolarBear) return ModelType.POLAR_BEAR;
        if (entity instanceof net.minecraft.world.entity.animal.Turtle) return ModelType.TURTLE;
        if (entity instanceof net.minecraft.world.entity.monster.EnderMan) return ModelType.ENDERMAN;
        if (entity instanceof net.minecraft.world.entity.animal.Rabbit) return ModelType.RABBIT;
        if (entity instanceof net.minecraft.world.entity.animal.frog.Frog) return ModelType.FROG;
        if (entity instanceof net.minecraft.world.entity.monster.hoglin.Hoglin
                || entity instanceof net.minecraft.world.entity.monster.Zoglin) return ModelType.HOGLIN;
        if (entity instanceof net.minecraft.world.entity.animal.sniffer.Sniffer) return ModelType.SNIFFER;
        if (entity instanceof net.minecraft.world.entity.monster.Ravager) return ModelType.RAVAGER;
        if (entity instanceof net.minecraft.world.entity.monster.Phantom) return ModelType.PHANTOM;
        if (entity instanceof net.minecraft.world.entity.animal.Parrot) return ModelType.PARROT;
        // MagmaCube extends Slime, so the specific check must stay first.
        if (entity instanceof net.minecraft.world.entity.monster.MagmaCube) return ModelType.MAGMA_CUBE;
        if (entity instanceof net.minecraft.world.entity.monster.Slime) return ModelType.SLIME;
        if (entity instanceof net.minecraft.world.entity.monster.Silverfish) return ModelType.SILVERFISH;
        if (entity instanceof net.minecraft.world.entity.monster.Endermite) return ModelType.ENDERMITE;
        if (entity instanceof net.minecraft.world.entity.animal.allay.Allay) return ModelType.ALLAY;
        if (entity instanceof net.minecraft.world.entity.monster.Strider) return ModelType.STRIDER;
        if (entity instanceof net.minecraft.world.entity.animal.SnowGolem) return ModelType.SNOW_GOLEM;
        if (entity instanceof net.minecraft.world.entity.monster.Blaze) return ModelType.BLAZE;
        if (entity instanceof net.minecraft.world.entity.monster.Spider) return ModelType.SPIDER;
        if (entity instanceof net.minecraft.world.entity.monster.Shulker) return ModelType.SHULKER;
        if (entity instanceof net.minecraft.world.entity.monster.Ghast) return ModelType.GHAST;
        if (entity instanceof net.minecraft.world.entity.monster.Vex) return ModelType.VEX;
        if (entity instanceof net.minecraft.world.entity.monster.warden.Warden) return ModelType.WARDEN;
        // Guardian must be matched here: its class name contains "guard", which the humanoid keyword
        // heuristic below used to claim, giving guardians a humanoid ragdoll.
        if (entity instanceof net.minecraft.world.entity.monster.Guardian) return ModelType.GUARDIAN;
        if (entity instanceof net.minecraft.world.entity.animal.Squid) return ModelType.SQUID;
        if (entity instanceof net.minecraft.world.entity.animal.Dolphin) return ModelType.DOLPHIN;
        if (entity instanceof net.minecraft.world.entity.animal.axolotl.Axolotl) return ModelType.AXOLOTL;
        // Tadpole is not an AbstractFish, so it needs its own test alongside the fish base class.
        if (entity instanceof net.minecraft.world.entity.animal.AbstractFish
                || entity instanceof net.minecraft.world.entity.animal.frog.Tadpole) return ModelType.FISH;
        if (entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss) return ModelType.WITHER;
        if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) return ModelType.ENDER_DRAGON;
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
            // "guardian" contains "guard" but is a fish-shaped mob, not a humanoid one. Vanilla
            // guardians never reach here any more, but modded guardian-alikes still would.
            if (name.contains("guardian")) return false;
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
            case WITCH -> "Witch (villager body)";
            case EQUINE -> "Horse / Donkey / Mule";
            case WOLF -> "Wolf";
            case FOX -> "Fox";
            case PANDA -> "Panda";
            case IRON_GOLEM -> "Iron Golem";
            case GOAT -> "Goat";
            case POLAR_BEAR -> "Polar Bear";
            case TURTLE -> "Turtle";
            case ENDERMAN -> "Enderman";
            case CAMEL -> "Camel";
            case LLAMA -> "Llama";
            case RABBIT -> "Rabbit";
            case FROG -> "Frog";
            case HOGLIN -> "Hoglin / Zoglin";
            case SNIFFER -> "Sniffer";
            case RAVAGER -> "Ravager";
            case PHANTOM -> "Phantom";
            case PARROT -> "Parrot";
            case SLIME -> "Small Slime";
            case MAGMA_CUBE -> "Small Magma Cube";
            case GUARDIAN -> "Guardian / Elder Guardian";
            case SQUID -> "Squid / Glow Squid";
            case DOLPHIN -> "Dolphin";
            case AXOLOTL -> "Axolotl";
            case FISH -> "Small Fish (cod / salmon / tropical / puffer / tadpole)";
            case WITHER -> "Wither";
            case ENDER_DRAGON -> "Ender Dragon";
            case GENERIC -> "Generic (measured from model)";
            case SILVERFISH -> "Silverfish";
            case ENDERMITE -> "Endermite";
            case ALLAY -> "Allay";
            case STRIDER -> "Strider";
            case SNOW_GOLEM -> "Snow Golem";
            case BLAZE -> "Blaze";
            case SPIDER -> "Spider / Cave Spider";
            case SHULKER -> "Shulker";
            case GHAST -> "Ghast";
            case VEX -> "Vex";
            case WARDEN -> "Warden";
            case UNSUPPORTED -> "Unsupported";
        };
    }
}
