package com.raiiiden.ragdollified.config;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

public class RagdollifiedConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue RAGDOLL_LIFETIME;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PLAYER_RAGDOLLS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTITY_DENYLIST;

    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_HEADSHOT;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_BODY;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_MELEE;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VANILLA_PROJECTILE;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VERTICAL_BIAS;
    public static final ForgeConfigSpec.BooleanValue HIT_IMPULSE_DAMAGE_SCALING;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_DAMAGE_REFERENCE;
    public static final ForgeConfigSpec.DoubleValue HIT_CENTER_LEEWAY;
    public static final ForgeConfigSpec.DoubleValue HIT_CENTER_DISTRIBUTION_SCALE;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_TORSO;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_HEAD;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_LEFT_LEG;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_RIGHT_LEG;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_LEFT_ARM;
    public static final ForgeConfigSpec.DoubleValue PART_KNOCKBACK_RIGHT_ARM;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_TORSO;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_HEAD;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_LEFT_LEG;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_RIGHT_LEG;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_LEFT_ARM;
    public static final ForgeConfigSpec.DoubleValue DEATH_PART_KNOCKBACK_RIGHT_ARM;

    public static final ForgeConfigSpec.DoubleValue GRAVITY;
    public static final ForgeConfigSpec.DoubleValue MASS_SCALE;
    public static final ForgeConfigSpec.DoubleValue INITIAL_VELOCITY_SCALE;
    public static final ForgeConfigSpec.DoubleValue LINEAR_DAMPING;
    public static final ForgeConfigSpec.DoubleValue ANGULAR_DAMPING;
    public static final ForgeConfigSpec.DoubleValue FRICTION;
    public static final ForgeConfigSpec.DoubleValue RESTITUTION;
    public static final ForgeConfigSpec.DoubleValue MAX_LINEAR_SPEED;
    public static final ForgeConfigSpec.DoubleValue MAX_FALL_SPEED;
    public static final ForgeConfigSpec.DoubleValue MAX_ANGULAR_SPEED;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_RAGDOLLS;
    public static final ForgeConfigSpec.IntValue MAX_SPAWNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_SPAWN_QUEUE_SIZE;
    public static final ForgeConfigSpec.DoubleValue PHYSICS_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue PLAYER_COLLISION_DISTANCE;

    public static final ForgeConfigSpec.DoubleValue RENDER_DISTANCE;

    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;

    static {
        BUILDER.push("Ragdoll Settings");

        RAGDOLL_LIFETIME = BUILDER
                .comment("How long ragdolls last before despawning, in ticks.")
                .defineInRange("ragdollLifetime", 600, 20, 12000);

        MAX_RAGDOLLS = BUILDER
                .comment("Maximum number of ragdolls that can exist at once.")
                .defineInRange("maxRagdolls", 20, 1, 100);

        ENABLE_RAGDOLLS = BUILDER
                .comment("Master switch for ragdoll spawning.")
                .define("enableRagdolls", true);

        ENABLE_PLAYER_RAGDOLLS = BUILDER
                .comment("Allow player death ragdolls.")
                .define("enablePlayerRagdolls", true);

        ENTITY_DENYLIST = BUILDER
                .comment("Entity registry ids that should never ragdoll. Use minecraft:player for players.")
                .defineListAllowEmpty(List.of("entityDenylist"), List.of(), value -> value instanceof String);

        BUILDER.pop();
        BUILDER.comment("Hit Impulse Settings").push("hitImpulse");

        HIT_IMPULSE_HEADSHOT = BUILDER
                .comment("Base impulse magnitude for a TACZ headshot.")
                .defineInRange("headshot", 8.0, 0.0, 200.0);

        HIT_IMPULSE_BODY = BUILDER
                .comment("Base impulse magnitude for a TACZ body shot.")
                .defineInRange("body", 6.0, 0.0, 200.0);

        HIT_IMPULSE_MELEE = BUILDER
                .comment("Base impulse magnitude for melee kills.")
                .defineInRange("melee", 5.0, 0.0, 200.0);

        HIT_IMPULSE_VANILLA_PROJECTILE = BUILDER
                .comment("Base impulse magnitude for vanilla projectiles.")
                .defineInRange("vanillaProjectile", 8.0, 0.0, 200.0);

        HIT_IMPULSE_VERTICAL_BIAS = BUILDER
                .comment("Constant upward kick added to every hit.")
                .defineInRange("verticalBias", 0.3, 0.0, 5.0);

        HIT_IMPULSE_DAMAGE_SCALING = BUILDER
                .comment("Scale the impulse by sqrt(damage / damageReference).")
                .define("damageScaling", true);

        HIT_IMPULSE_DAMAGE_REFERENCE = BUILDER
                .comment("Damage value at which the impulse equals the base magnitude.")
                .defineInRange("damageReference", 6.0, 0.1, 100.0);

        HIT_CENTER_LEEWAY = BUILDER
                .comment("Horizontal center leeway as a fraction of entity width. Hits inside this band affect the whole body.")
                .defineInRange("centerLeeway", 0.05, 0.0, 0.5);

        HIT_CENTER_DISTRIBUTION_SCALE = BUILDER
                .comment("Scale used when a centered hit distributes impulse to every part.")
                .defineInRange("centerDistributionScale", 0.85, 0.0, 2.0);

        PART_KNOCKBACK_TORSO = BUILDER.comment("Post-spawn ragdoll interaction multiplier for torso hits.")
                .defineInRange("partMultiplierTorso", 1.0, 0.0, 10.0);
        PART_KNOCKBACK_HEAD = BUILDER.comment("Post-spawn ragdoll interaction multiplier for head hits.")
                .defineInRange("partMultiplierHead", 1.25, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_LEG = BUILDER.comment("Post-spawn ragdoll interaction multiplier for left leg hits.")
                .defineInRange("partMultiplierLeftLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_LEG = BUILDER.comment("Post-spawn ragdoll interaction multiplier for right leg hits.")
                .defineInRange("partMultiplierRightLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_ARM = BUILDER.comment("Post-spawn ragdoll interaction multiplier for left arm hits.")
                .defineInRange("partMultiplierLeftArm", 1.1, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_ARM = BUILDER.comment("Post-spawn ragdoll interaction multiplier for right arm hits.")
                .defineInRange("partMultiplierRightArm", 1.1, 0.0, 10.0);

        DEATH_PART_KNOCKBACK_TORSO = BUILDER.comment("Death-time melee/projectile transfer multiplier for torso hits.")
                .defineInRange("deathPartMultiplierTorso", 2.5, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_HEAD = BUILDER.comment("Death-time melee/projectile transfer multiplier for head hits.")
                .defineInRange("deathPartMultiplierHead", 5.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_LEG = BUILDER.comment("Death-time melee/projectile transfer multiplier for left leg hits.")
                .defineInRange("deathPartMultiplierLeftLeg", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_LEG = BUILDER.comment("Death-time melee/projectile transfer multiplier for right leg hits.")
                .defineInRange("deathPartMultiplierRightLeg", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_ARM = BUILDER.comment("Death-time melee/projectile transfer multiplier for left arm hits.")
                .defineInRange("deathPartMultiplierLeftArm", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_ARM = BUILDER.comment("Death-time melee/projectile transfer multiplier for right arm hits.")
                .defineInRange("deathPartMultiplierRightArm", 4.0, 0.0, 10.0);

        BUILDER.pop();
        BUILDER.comment("Physics Settings").push("physics");

        GRAVITY = BUILDER.comment("World gravity used by ragdoll physics. Higher values make ragdolls fall and collapse faster.")
                .defineInRange("gravity", 9.81, 0.0, 50.0);
        MASS_SCALE = BUILDER.comment("Multiplier for rigid body mass. Higher values feel heavier and resist hit impulses more.")
                .defineInRange("massScale", 1.0, 0.05, 20.0);
        INITIAL_VELOCITY_SCALE = BUILDER.comment("Scale applied to inherited entity velocity. Lower values reduce whole-body sliding.")
                .defineInRange("initialVelocityScale", 0.35, 0.0, 5.0);
        LINEAR_DAMPING = BUILDER.defineInRange("linearDamping", 0.10, 0.0, 1.0);
        ANGULAR_DAMPING = BUILDER.defineInRange("angularDamping", 0.90, 0.0, 1.0);
        FRICTION = BUILDER.defineInRange("friction", 0.90, 0.0, 5.0);
        RESTITUTION = BUILDER.defineInRange("restitution", 0.0, 0.0, 2.0);
        MAX_LINEAR_SPEED = BUILDER.defineInRange("maxLinearSpeed", 90.0, 1.0, 500.0);
        MAX_FALL_SPEED = BUILDER.defineInRange("maxFallSpeed", 80.0, 1.0, 500.0);
        MAX_ANGULAR_SPEED = BUILDER.defineInRange("maxAngularSpeed", 8.0, 0.1, 100.0);
        MAX_ACTIVE_RAGDOLLS = BUILDER.defineInRange("maxActiveRagdolls", 25, 1, 100);
        MAX_SPAWNS_PER_TICK = BUILDER.defineInRange("maxSpawnsPerTick", 3, 1, 20);
        MAX_SPAWN_QUEUE_SIZE = BUILDER.defineInRange("maxSpawnQueueSize", 60, 1, 300);
        PHYSICS_DISTANCE = BUILDER.comment("Distance in blocks beyond which ragdoll physics freezes.")
                .defineInRange("physicsDistance", 64.0, 4.0, 512.0);
        PLAYER_COLLISION_DISTANCE = BUILDER.comment("Distance in blocks within which player movement pushes ragdolls.")
                .defineInRange("playerCollisionDistance", 12.0, 0.0, 128.0);

        BUILDER.pop();
        BUILDER.comment("Render Settings").push("render");

        RENDER_DISTANCE = BUILDER.comment("Distance in blocks beyond which ragdolls should not render.")
                .defineInRange("renderDistance", 64.0, 4.0, 512.0);

        BUILDER.pop();
        BUILDER.comment("Debug Options").push("debug");

        debugRenderPhysics = BUILDER
                .comment("Render debug boxes around ragdoll physics bodies.")
                .define("debugRenderPhysics", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "ragdollified-common.toml");
    }

    public static int getRagdollLifetime() {
        return RAGDOLL_LIFETIME.get();
    }

    public static int getMaxRagdolls() {
        return MAX_RAGDOLLS.get();
    }

    public static boolean isRagdollEnabledFor(String entityId, boolean isPlayer) {
        if (!ENABLE_RAGDOLLS.get()) return false;
        String id = isPlayer ? "minecraft:player" : entityId;
        if (isPlayer && !ENABLE_PLAYER_RAGDOLLS.get()) return false;
        for (String denied : ENTITY_DENYLIST.get()) {
            if (denied != null && denied.trim().equalsIgnoreCase(id)) return false;
        }
        return true;
    }

    public static float getPartKnockbackMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> PART_KNOCKBACK_TORSO.get().floatValue();
            case HEAD -> PART_KNOCKBACK_HEAD.get().floatValue();
            case LEFT_LEG -> PART_KNOCKBACK_LEFT_LEG.get().floatValue();
            case RIGHT_LEG -> PART_KNOCKBACK_RIGHT_LEG.get().floatValue();
            case LEFT_ARM -> PART_KNOCKBACK_LEFT_ARM.get().floatValue();
            case RIGHT_ARM -> PART_KNOCKBACK_RIGHT_ARM.get().floatValue();
        };
    }

    public static float getDeathPartKnockbackMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> DEATH_PART_KNOCKBACK_TORSO.get().floatValue();
            case HEAD -> DEATH_PART_KNOCKBACK_HEAD.get().floatValue();
            case LEFT_LEG -> DEATH_PART_KNOCKBACK_LEFT_LEG.get().floatValue();
            case RIGHT_LEG -> DEATH_PART_KNOCKBACK_RIGHT_LEG.get().floatValue();
            case LEFT_ARM -> DEATH_PART_KNOCKBACK_LEFT_ARM.get().floatValue();
            case RIGHT_ARM -> DEATH_PART_KNOCKBACK_RIGHT_ARM.get().floatValue();
        };
    }

    public static boolean shouldDebugRenderPhysics() {
        return debugRenderPhysics.get();
    }
}
