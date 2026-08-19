package com.raiiiden.ragdollified.config;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

public class RagdollifiedConfig {

    // GAMEPLAY COMMON config. The server sends a runtime snapshot on join; client-side
    // gameplay reads resolve through that snapshot while connected to a modded server.
    public static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec GAMEPLAY_SPEC;

    public static final ForgeConfigSpec.IntValue RAGDOLL_LIFETIME;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PLAYER_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_MOB_RAGDOLLS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTITY_DENYLIST;

    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_HEADSHOT;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_BODY;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_MELEE;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VANILLA_PROJECTILE;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_EXPLOSION;
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

    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_TORSO;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_HEAD;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_LEFT_ARM;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_RIGHT_ARM;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_LEFT_LEG;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_RIGHT_LEG;

    // Reference weights of the humanoid skeleton as authored in RagdollBodyFactory. The configured
    // weight divided by these gives the multiplier, so other skeletons keep their own proportions.
    public static final float REFERENCE_WEIGHT_TORSO = 8f;
    public static final float REFERENCE_WEIGHT_HEAD = 4f;
    public static final float REFERENCE_WEIGHT_ARM = 4f;
    public static final float REFERENCE_WEIGHT_LEG = 6f;

    public static final ForgeConfigSpec.DoubleValue GRAVITY;
    public static final ForgeConfigSpec.DoubleValue MASS_SCALE;
    public static final ForgeConfigSpec.DoubleValue INITIAL_VELOCITY_SCALE;
    public static final ForgeConfigSpec.BooleanValue SCALE_VELOCITY_BY_MODEL_SIZE;
    public static final ForgeConfigSpec.DoubleValue MIN_MODEL_SIZE_VELOCITY_SCALE;
    public static final ForgeConfigSpec.DoubleValue DIRECTIONAL_CARRY_SPEED;
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

    // Corpse feature — requires the mod on both server and client (or singleplayer).
    public static final ForgeConfigSpec.BooleanValue ENABLE_CORPSES;
    public static final ForgeConfigSpec.IntValue CORPSE_EXPIRY_TICKS;
    public static final ForgeConfigSpec.IntValue CORPSE_SETTLE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.BooleanValue CORPSE_STORE_XP;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CORPSE_COMPASS;

    // CLIENT config (local only, never synced)
    public static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.DoubleValue RENDER_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEATH_CAMERA;
    public static final ForgeConfigSpec.DoubleValue GECKOLIB_ARMOR_RENDER_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ARMOR_RENDER_DISTANCE;
    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;
    private static ForgeConfigSpec.BooleanValue logPhysicsPerf;

    private static final Map<ForgeConfigSpec.ConfigValue<?>, String> GAMEPLAY_KEYS = new IdentityHashMap<>();
    private static volatile CompoundTag remoteGameplaySnapshot;

    public static double getArmorRenderDistanceSq() {
        double d = ARMOR_RENDER_DISTANCE.get();
        return d * d;
    }

    public static double getGeckoLibArmorRenderDistanceSq() {
        double d = GECKOLIB_ARMOR_RENDER_DISTANCE.get();
        return d * d;
    }

    static {
        // SERVER spec
        SERVER_BUILDER.push("Ragdoll Settings");

        RAGDOLL_LIFETIME = SERVER_BUILDER
                .comment("How long ragdolls last before despawning, in ticks.")
                .defineInRange("ragdollLifetime", 600, 20, 12000);

        MAX_RAGDOLLS = SERVER_BUILDER
                .comment("Maximum number of ragdolls that can exist at once.")
                .defineInRange("maxRagdolls", 40, 1, 100);

        MAX_RAGDOLLS_PER_PLAYER = SERVER_BUILDER
                .comment("Maximum number of a single player's death ragdolls that can exist at once. When a player",
                        "dies past this many times in quick succession, their oldest ragdoll is removed to make room.",
                        "Ignored while corpses are enabled so a loot-bound ragdoll is never culled before handoff.",
                        "Materialized corpses are separate entities and never count toward this limit.")
                .defineInRange("maxRagdollsPerPlayer", 3, 1, 20);

        ENABLE_RAGDOLLS = SERVER_BUILDER
                .comment("Master switch for ragdoll spawning.")
                .define("enableRagdolls", true);

        ENABLE_PLAYER_RAGDOLLS = SERVER_BUILDER
                .comment("Allow player death ragdolls.")
                .define("enablePlayerRagdolls", true);

        ENABLE_MOB_RAGDOLLS = SERVER_BUILDER
                .comment("Allow non-player (mob) death ragdolls.")
                .define("enableMobRagdolls", true);

        ENTITY_DENYLIST = SERVER_BUILDER
                .comment("Entity registry ids that should never ragdoll. Use minecraft:player for players.",
                        "Entries here never spawn ragdolls, even when an authored model exists.")
                .defineListAllowEmpty(List.of("entityDenylist"),
                        List.of(),
                        value -> value instanceof String);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Hit Impulse Settings").push("hitImpulse");

        HIT_IMPULSE_HEADSHOT = SERVER_BUILDER
                .comment("Base impulse magnitude for a TACZ headshot.")
                .defineInRange("headshot", 10.0, 0.0, 200.0);

        HIT_IMPULSE_BODY = SERVER_BUILDER
                .comment("Base impulse magnitude for a TACZ body shot.")
                .defineInRange("body", 6.0, 0.0, 200.0);

        HIT_IMPULSE_MELEE = SERVER_BUILDER
                .comment("Base impulse magnitude for melee kills.")
                .defineInRange("melee", 5.0, 0.0, 200.0);

        HIT_IMPULSE_VANILLA_PROJECTILE = SERVER_BUILDER
                .comment("Base impulse magnitude for vanilla projectiles.")
                .defineInRange("vanillaProjectile", 8.0, 0.0, 200.0);

        HIT_IMPULSE_EXPLOSION = SERVER_BUILDER
                .comment("Base impulse magnitude for explosion kills (TNT, creepers, TACZ explosives, etc.).",
                        "The applied force still scales with the victim's max health and distance from the blast;",
                        "this is the overall multiplier. Higher = bodies are thrown further.")
                .defineInRange("explosion", 40.0, 0.0, 200.0);

        HIT_IMPULSE_VERTICAL_BIAS = SERVER_BUILDER
                .comment("Constant upward kick added to every hit.")
                .defineInRange("verticalBias", 0.3, 0.0, 5.0);

        HIT_IMPULSE_DAMAGE_SCALING = SERVER_BUILDER
                .comment("Scale the impulse by sqrt(damage / damageReference).")
                .define("damageScaling", false);

        HIT_IMPULSE_DAMAGE_REFERENCE = SERVER_BUILDER
                .comment("Damage value at which the impulse equals the base magnitude.")
                .defineInRange("damageReference", 6.0, 0.1, 100.0);

        HIT_CENTER_LEEWAY = SERVER_BUILDER
                .comment("Horizontal center leeway as a fraction of entity width.")
                .defineInRange("centerLeeway", 0.05, 0.0, 0.5);

        HIT_CENTER_DISTRIBUTION_SCALE = SERVER_BUILDER
                .comment("Scale used when a centered hit distributes impulse to every part.")
                .defineInRange("centerDistributionScale", 0.85, 0.0, 2.0);

        PART_KNOCKBACK_TORSO = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for torso hits.")
                .defineInRange("partMultiplierTorso", 1.0, 0.0, 10.0);
        PART_KNOCKBACK_HEAD = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for head hits.")
                .defineInRange("partMultiplierHead", 1.25, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_LEG = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for left leg hits.")
                .defineInRange("partMultiplierLeftLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_LEG = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for right leg hits.")
                .defineInRange("partMultiplierRightLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_ARM = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for left arm hits.")
                .defineInRange("partMultiplierLeftArm", 1.1, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_ARM = SERVER_BUILDER.comment("Post-spawn ragdoll interaction multiplier for right arm hits.")
                .defineInRange("partMultiplierRightArm", 1.1, 0.0, 10.0);

        DEATH_PART_KNOCKBACK_TORSO = SERVER_BUILDER.comment("Death-time multiplier for torso hits.")
                .defineInRange("deathPartMultiplierTorso", 2.5, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_HEAD = SERVER_BUILDER.comment("Death-time multiplier for head hits.")
                .defineInRange("deathPartMultiplierHead", 5.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_LEG = SERVER_BUILDER.comment("Death-time multiplier for left leg hits.")
                .defineInRange("deathPartMultiplierLeftLeg", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_LEG = SERVER_BUILDER.comment("Death-time multiplier for right leg hits.")
                .defineInRange("deathPartMultiplierRightLeg", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_ARM = SERVER_BUILDER.comment("Death-time multiplier for left arm hits.")
                .defineInRange("deathPartMultiplierLeftArm", 4.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_ARM = SERVER_BUILDER.comment("Death-time multiplier for right arm hits.")
                .defineInRange("deathPartMultiplierRightArm", 4.0, 0.0, 10.0);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Per-Body-Part Weight Settings",
                        "Mass of each ragdoll body part. Heavier parts resist knockback and pull the",
                        "body toward them as it falls; lighter parts flail more. The defaults are the",
                        "humanoid skeleton's authored weights, so leaving them alone changes nothing.",
                        "Non-humanoid skeletons (quadrupeds, creeper, bat, bee) have their own authored",
                        "weights and are scaled by the same ratio you set here rather than being",
                        "overwritten, so a cow stays cow-shaped in mass distribution.",
                        "These are multiplied by physics.massScale, which remains a global scalar.")
                .push("partWeights");

        // These must stay equal to REFERENCE_WEIGHT_*: the multiplier is config/reference, so a mismatch
        // silently rescales every skeleton, costing knockback and planting quadrupeds on light legs.
        PART_WEIGHT_TORSO = SERVER_BUILDER.comment("Weight of the torso.")
                .defineInRange("torso", REFERENCE_WEIGHT_TORSO, 0.1, 200.0);
        PART_WEIGHT_HEAD = SERVER_BUILDER.comment("Weight of the head.")
                .defineInRange("head", REFERENCE_WEIGHT_HEAD, 0.1, 200.0);
        PART_WEIGHT_LEFT_ARM = SERVER_BUILDER.comment("Weight of the left arm.")
                .defineInRange("leftArm", REFERENCE_WEIGHT_ARM, 0.1, 200.0);
        PART_WEIGHT_RIGHT_ARM = SERVER_BUILDER.comment("Weight of the right arm.")
                .defineInRange("rightArm", REFERENCE_WEIGHT_ARM, 0.1, 200.0);
        PART_WEIGHT_LEFT_LEG = SERVER_BUILDER.comment("Weight of the left leg.")
                .defineInRange("leftLeg", REFERENCE_WEIGHT_LEG, 0.1, 200.0);
        PART_WEIGHT_RIGHT_LEG = SERVER_BUILDER.comment("Weight of the right leg.")
                .defineInRange("rightLeg", REFERENCE_WEIGHT_LEG, 0.1, 200.0);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Physics Settings").push("physics");

        GRAVITY = SERVER_BUILDER.comment("World gravity used by ragdoll physics.")
                .defineInRange("gravity", 20.00, 0.0, 50.0);
        MASS_SCALE = SERVER_BUILDER.comment("Multiplier for rigid body mass.")
                .defineInRange("massScale", 1.0, 0.05, 20.0);
        INITIAL_VELOCITY_SCALE = SERVER_BUILDER
                .comment("Scale applied after inheriting the entity's exact death-time velocity.",
                        "1.0 preserves its movement; lower values intentionally reduce momentum.")
                .defineInRange("initialVelocityScale", 1.0, 0.0, 5.0);
        SCALE_VELOCITY_BY_MODEL_SIZE = SERVER_BUILDER
                .comment("Damp inherited velocity and hit knockback on ragdolls smaller than a player.",
                        "Impulses are a fixed magnitude, so a light body (chicken, bat, bee, rabbit) picks up far",
                        "more speed from the same hit than a humanoid and gets launched. Bodies at player size or",
                        "larger are never affected — this only reduces, never boosts.")
                .define("scaleVelocityByModelSize", true);
        MIN_MODEL_SIZE_VELOCITY_SCALE = SERVER_BUILDER
                .comment("Floor for the size damping above, so the very smallest ragdolls still react to hits.",
                        "The multiplier is the body's height relative to a player (1.8 blocks), clamped to this",
                        "minimum. Lower = smaller mobs fly less; 1.0 disables the damping entirely.")
                .defineInRange("minModelSizeVelocityScale", 0.35, 0.05, 1.0);
        DIRECTIONAL_CARRY_SPEED = SERVER_BUILDER
                .comment("Minimum horizontal speed given to a nearly stationary death in the attacker's look direction,",
                        "in blocks per second. Set to 0 to disable directional death carry.")
                .defineInRange("directionalCarrySpeed", 0.75, 0.0, 20.0);
        LINEAR_DAMPING = SERVER_BUILDER
                .comment("Per-tick linear (movement) velocity damping. 0 = none, 1 = bodies stop almost instantly.",
                        "Higher makes ragdolls bleed off momentum faster so they don't slide as far.")
                .defineInRange("linearDamping", 0.10, 0.0, 1.0);
        ANGULAR_DAMPING = SERVER_BUILDER
                .comment("Per-tick angular (spin) velocity damping. 0 = none, 1 = spin stops almost instantly.",
                        "Higher makes ragdolls stop tumbling/rotating faster.")
                .defineInRange("angularDamping", 0.90, 0.0, 1.0);
        FRICTION = SERVER_BUILDER
                .comment("Surface friction between ragdoll bodies and the world. Higher = less sliding on the ground.")
                .defineInRange("friction", 2.0, 0.0, 5.0);
        RESTITUTION = SERVER_BUILDER
                .comment("Bounciness on impact. 0 = no bounce, 1 = fully elastic. Keep low to avoid jittery bodies.")
                .defineInRange("restitution", 0.0, 0.0, 2.0);
        MAX_LINEAR_SPEED = SERVER_BUILDER
                .comment("Hard cap on a body's overall speed in blocks/second. Limits the solver to keep it stable.")
                .defineInRange("maxLinearSpeed", 90.0, 1.0, 500.0);
        MAX_FALL_SPEED = SERVER_BUILDER
                .comment("Hard cap on downward fall speed in blocks/second (terminal velocity for ragdolls).")
                .defineInRange("maxFallSpeed", 80.0, 1.0, 500.0);
        MAX_ANGULAR_SPEED = SERVER_BUILDER
                .comment("Hard cap on spin rate in radians/second. Prevents bodies from spinning wildly on hard hits.")
                .defineInRange("maxAngularSpeed", 8.0, 0.1, 100.0);
        MAX_ACTIVE_RAGDOLLS = SERVER_BUILDER
                .comment("Max ragdolls actively simulated at once. Past this the oldest active bodies are force-settled",
                        "(they keep rendering and can be woken again) to protect tick time during pile-ups.")
                .defineInRange("maxActiveRagdolls", 40, 1, 100);
        MAX_SPAWNS_PER_TICK = SERVER_BUILDER
                .comment("Max ragdoll bodies constructed per tick, so a mass kill (e.g. an explosion) doesn't spike one frame.")
                .defineInRange("maxSpawnsPerTick", 15, 1, 20);
        MAX_SPAWN_QUEUE_SIZE = SERVER_BUILDER
                .comment("Max ragdoll spawns waiting to be constructed. Deaths beyond this while the queue is full are skipped.")
                .defineInRange("maxSpawnQueueSize", 60, 1, 300);
        PHYSICS_DISTANCE = SERVER_BUILDER.comment("Distance in blocks beyond which ragdoll physics freezes.")
                .defineInRange("physicsDistance", 128.0, 4.0, 512.0);
        PLAYER_COLLISION_DISTANCE = SERVER_BUILDER.comment("Distance in blocks within which player movement pushes ragdolls.")
                .defineInRange("playerCollisionDistance", 12.0, 0.0, 128.0);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Corpse Settings").push("corpse");

        ENABLE_CORPSES = SERVER_BUILDER
                .comment("When a player dies, freeze their ragdoll into a lootable corpse holding their inventory.",
                        "Requires the mod on both the server and client (or singleplayer). Ignored on vanilla servers.")
                .define("enableCorpses", true);

        CORPSE_EXPIRY_TICKS = SERVER_BUILDER
                .comment("How long a corpse lasts before expiring, in ticks. On expiry it drops its remaining items.",
                        "Default 24000 = 1 Minecraft day.")
                .defineInRange("corpseExpiryTicks", 24000, 1200, 2_400_000);

        CORPSE_SETTLE_TIMEOUT_TICKS = SERVER_BUILDER
                .comment("Max ticks to wait for the client to report its ragdoll settling before the corpse is",
                        "frozen flat at the death position anyway. Safety net so inventory is never stuck. Default 600 = 30s.")
                .defineInRange("corpseSettleTimeoutTicks", 600, 20, 6000);

        CORPSE_STORE_XP = SERVER_BUILDER
                .comment("Store the experience that would have dropped inside the corpse and return it when looted.")
                .define("corpseStoreXp", true);

        ENABLE_CORPSE_COMPASS = SERVER_BUILDER
                .comment("Give the player a Corpse Compass when they respawn that points to their most recent corpse.",
                        "Right-clicking it opens a locator screen. Only applies while corpses are enabled.")
                .define("enableCorpseCompass", true);

        SERVER_BUILDER.pop();
        GAMEPLAY_SPEC = SERVER_BUILDER.build();
        registerGameplayKeys();

        // CLIENT spec
        CLIENT_BUILDER.comment("Render Settings").push("render");

        RENDER_DISTANCE = CLIENT_BUILDER.comment("Distance in blocks beyond which ragdolls do not render.")
                .defineInRange("renderDistance", 128.0, 4.0, 512.0);

        ENABLE_DEATH_CAMERA = CLIENT_BUILDER
                .comment("Switch for death camera.")
                .define("enableDeathCamera", true);

        GECKOLIB_ARMOR_RENDER_DISTANCE = CLIENT_BUILDER
                .comment("Distance in blocks within which GeckoLib animated armor renders on ragdolls. " +
                        "Lower values improve performance. Must be less than or equal to renderDistance.")
                .defineInRange("geckolibArmorRenderDistance", 80.0, 4.0, 128.0);
        ARMOR_RENDER_DISTANCE = CLIENT_BUILDER
                .comment("Distance in blocks within which vanilla armor renders on ragdolls.")
                .defineInRange("armorRenderDistance", 100.0, 4.0, 512.0);

        CLIENT_BUILDER.pop();
        CLIENT_BUILDER.comment("Debug Options").push("debug");

        debugRenderPhysics = CLIENT_BUILDER
                .comment("Render debug boxes around ragdoll physics bodies.")
                .define("debugRenderPhysics", true);

        logPhysicsPerf = CLIENT_BUILDER
                .comment("Periodically log ragdoll physics performance stats ([Ragdoll Perf]) to the client log.",
                        "Useful for profiling tick cost; leave off for normal play to keep the log clean.")
                .define("logPhysicsPerf", false);

        CLIENT_BUILDER.pop();
        CLIENT_SPEC = CLIENT_BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GAMEPLAY_SPEC, "ragdollified-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, "ragdollified-client.toml");
    }

    private static void registerGameplayKeys() {
        register("ragdollLifetime", RAGDOLL_LIFETIME);
        register("maxRagdolls", MAX_RAGDOLLS);
        register("maxRagdollsPerPlayer", MAX_RAGDOLLS_PER_PLAYER);
        register("enableRagdolls", ENABLE_RAGDOLLS);
        register("enablePlayerRagdolls", ENABLE_PLAYER_RAGDOLLS);
        register("enableMobRagdolls", ENABLE_MOB_RAGDOLLS);
        register("entityDenylist", ENTITY_DENYLIST);
        register("hitImpulseHeadshot", HIT_IMPULSE_HEADSHOT);
        register("hitImpulseBody", HIT_IMPULSE_BODY);
        register("hitImpulseMelee", HIT_IMPULSE_MELEE);
        register("hitImpulseVanillaProjectile", HIT_IMPULSE_VANILLA_PROJECTILE);
        register("hitImpulseExplosion", HIT_IMPULSE_EXPLOSION);
        register("hitImpulseVerticalBias", HIT_IMPULSE_VERTICAL_BIAS);
        register("hitImpulseDamageScaling", HIT_IMPULSE_DAMAGE_SCALING);
        register("hitImpulseDamageReference", HIT_IMPULSE_DAMAGE_REFERENCE);
        register("hitCenterLeeway", HIT_CENTER_LEEWAY);
        register("hitCenterDistributionScale", HIT_CENTER_DISTRIBUTION_SCALE);
        register("partKnockbackTorso", PART_KNOCKBACK_TORSO);
        register("partKnockbackHead", PART_KNOCKBACK_HEAD);
        register("partKnockbackLeftLeg", PART_KNOCKBACK_LEFT_LEG);
        register("partKnockbackRightLeg", PART_KNOCKBACK_RIGHT_LEG);
        register("partKnockbackLeftArm", PART_KNOCKBACK_LEFT_ARM);
        register("partKnockbackRightArm", PART_KNOCKBACK_RIGHT_ARM);
        register("deathPartKnockbackTorso", DEATH_PART_KNOCKBACK_TORSO);
        register("deathPartKnockbackHead", DEATH_PART_KNOCKBACK_HEAD);
        register("deathPartKnockbackLeftLeg", DEATH_PART_KNOCKBACK_LEFT_LEG);
        register("deathPartKnockbackRightLeg", DEATH_PART_KNOCKBACK_RIGHT_LEG);
        register("deathPartKnockbackLeftArm", DEATH_PART_KNOCKBACK_LEFT_ARM);
        register("deathPartKnockbackRightArm", DEATH_PART_KNOCKBACK_RIGHT_ARM);
        register("gravity", GRAVITY);
        register("massScale", MASS_SCALE);
        register("initialVelocityScale", INITIAL_VELOCITY_SCALE);
        register("scaleVelocityByModelSize", SCALE_VELOCITY_BY_MODEL_SIZE);
        register("minModelSizeVelocityScale", MIN_MODEL_SIZE_VELOCITY_SCALE);
        register("directionalCarrySpeed", DIRECTIONAL_CARRY_SPEED);
        register("linearDamping", LINEAR_DAMPING);
        register("angularDamping", ANGULAR_DAMPING);
        register("friction", FRICTION);
        register("restitution", RESTITUTION);
        register("maxLinearSpeed", MAX_LINEAR_SPEED);
        register("maxFallSpeed", MAX_FALL_SPEED);
        register("maxAngularSpeed", MAX_ANGULAR_SPEED);
        register("maxActiveRagdolls", MAX_ACTIVE_RAGDOLLS);
        register("maxSpawnsPerTick", MAX_SPAWNS_PER_TICK);
        register("maxSpawnQueueSize", MAX_SPAWN_QUEUE_SIZE);
        register("physicsDistance", PHYSICS_DISTANCE);
        register("playerCollisionDistance", PLAYER_COLLISION_DISTANCE);
        register("enableCorpses", ENABLE_CORPSES);
        register("corpseExpiryTicks", CORPSE_EXPIRY_TICKS);
        register("corpseSettleTimeoutTicks", CORPSE_SETTLE_TIMEOUT_TICKS);
        register("corpseStoreXp", CORPSE_STORE_XP);
        register("enableCorpseCompass", ENABLE_CORPSE_COMPASS);
        register("partWeightTorso", PART_WEIGHT_TORSO);
        register("partWeightHead", PART_WEIGHT_HEAD);
        register("partWeightLeftArm", PART_WEIGHT_LEFT_ARM);
        register("partWeightRightArm", PART_WEIGHT_RIGHT_ARM);
        register("partWeightLeftLeg", PART_WEIGHT_LEFT_LEG);
        register("partWeightRightLeg", PART_WEIGHT_RIGHT_LEG);
    }

    private static void register(String key, ForgeConfigSpec.ConfigValue<?> value) {
        GAMEPLAY_KEYS.put(value, key);
    }

    // Capture the server's current COMMON gameplay values for transmission to a client.
    public static CompoundTag createGameplaySnapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 1);
        for (Map.Entry<ForgeConfigSpec.ConfigValue<?>, String> entry : GAMEPLAY_KEYS.entrySet()) {
            Object value = entry.getKey().get();
            String key = entry.getValue();
            if (value instanceof Boolean b) tag.putBoolean(key, b);
            else if (value instanceof Integer i) tag.putInt(key, i);
            else if (value instanceof Double d) tag.putDouble(key, d);
            else if (value instanceof List<?> list) {
                ListTag strings = new ListTag();
                for (Object item : list) if (item instanceof String s) strings.add(StringTag.valueOf(s));
                tag.put(key, strings);
            }
        }
        return tag;
    }

    // Install a server-owned runtime snapshot without modifying the client's TOML file.
    public static void applyServerSnapshot(CompoundTag snapshot) {
        remoteGameplaySnapshot = snapshot == null ? null : snapshot.copy();
    }

    public static void clearServerSnapshot() {
        remoteGameplaySnapshot = null;
    }

    public static boolean hasServerSnapshot() {
        return remoteGameplaySnapshot != null;
    }

    public static int get(ForgeConfigSpec.IntValue value) {
        CompoundTag snapshot = remoteGameplaySnapshot;
        String key = GAMEPLAY_KEYS.get(value);
        return snapshot != null && key != null && snapshot.contains(key, Tag.TAG_INT)
                ? snapshot.getInt(key) : value.get();
    }

    public static double get(ForgeConfigSpec.DoubleValue value) {
        CompoundTag snapshot = remoteGameplaySnapshot;
        String key = GAMEPLAY_KEYS.get(value);
        return snapshot != null && key != null && snapshot.contains(key, Tag.TAG_DOUBLE)
                ? snapshot.getDouble(key) : value.get();
    }

    public static boolean get(ForgeConfigSpec.BooleanValue value) {
        CompoundTag snapshot = remoteGameplaySnapshot;
        String key = GAMEPLAY_KEYS.get(value);
        return snapshot != null && key != null && snapshot.contains(key, Tag.TAG_BYTE)
                ? snapshot.getBoolean(key) : value.get();
    }

    private static List<? extends String> getStringList(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        CompoundTag snapshot = remoteGameplaySnapshot;
        String key = GAMEPLAY_KEYS.get(value);
        if (snapshot == null || key == null || !snapshot.contains(key, Tag.TAG_LIST)) return value.get();
        ListTag list = snapshot.getList(key, Tag.TAG_STRING);
        java.util.ArrayList<String> result = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        return result;
    }

    public static int getRagdollLifetime() { return get(RAGDOLL_LIFETIME); }
    public static int getMaxRagdolls() { return get(MAX_RAGDOLLS); }

    public static int getMaxRagdollsPerPlayer() {
        return get(MAX_RAGDOLLS_PER_PLAYER);
    }

    public static boolean isRagdollEnabledFor(String entityId, boolean isPlayer) {
        if (!get(ENABLE_RAGDOLLS)) return false;
        String id = isPlayer ? "minecraft:player" : entityId;
        if (isPlayer && !get(ENABLE_PLAYER_RAGDOLLS)) return false;
        if (!isPlayer && !get(ENABLE_MOB_RAGDOLLS)) return false;
        for (String denied : getStringList(ENTITY_DENYLIST)) {
            if (denied != null && denied.trim().equalsIgnoreCase(id)) return false;
        }
        return true;
    }

    //Per-part weight as a multiplier against the skeleton's authored mass, so every model

    public static float getPartWeightMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> (float) (get(PART_WEIGHT_TORSO) / REFERENCE_WEIGHT_TORSO);
            case HEAD -> (float) (get(PART_WEIGHT_HEAD) / REFERENCE_WEIGHT_HEAD);
            case LEFT_LEG -> (float) (get(PART_WEIGHT_LEFT_LEG) / REFERENCE_WEIGHT_LEG);
            case RIGHT_LEG -> (float) (get(PART_WEIGHT_RIGHT_LEG) / REFERENCE_WEIGHT_LEG);
            case LEFT_ARM -> (float) (get(PART_WEIGHT_LEFT_ARM) / REFERENCE_WEIGHT_ARM);
            case RIGHT_ARM -> (float) (get(PART_WEIGHT_RIGHT_ARM) / REFERENCE_WEIGHT_ARM);
        };
    }

    public static float getPartKnockbackMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> (float) get(PART_KNOCKBACK_TORSO);
            case HEAD -> (float) get(PART_KNOCKBACK_HEAD);
            case LEFT_LEG -> (float) get(PART_KNOCKBACK_LEFT_LEG);
            case RIGHT_LEG -> (float) get(PART_KNOCKBACK_RIGHT_LEG);
            case LEFT_ARM -> (float) get(PART_KNOCKBACK_LEFT_ARM);
            case RIGHT_ARM -> (float) get(PART_KNOCKBACK_RIGHT_ARM);
        };
    }

    public static float getDeathPartKnockbackMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> (float) get(DEATH_PART_KNOCKBACK_TORSO);
            case HEAD -> (float) get(DEATH_PART_KNOCKBACK_HEAD);
            case LEFT_LEG -> (float) get(DEATH_PART_KNOCKBACK_LEFT_LEG);
            case RIGHT_LEG -> (float) get(DEATH_PART_KNOCKBACK_RIGHT_LEG);
            case LEFT_ARM -> (float) get(DEATH_PART_KNOCKBACK_LEFT_ARM);
            case RIGHT_ARM -> (float) get(DEATH_PART_KNOCKBACK_RIGHT_ARM);
        };
    }

    // Velocity damping for undersized bodies, bodyScale being bbHeight / 1.8. Impulses are fixed
    // magnitudes and speed is J/m, so a light part flies off far too fast. Clamped at 1.0: it only reduces.
    public static float getModelSizeVelocityScale(float bodyScale) {
        if (!get(SCALE_VELOCITY_BY_MODEL_SIZE) || bodyScale >= 1.0f) return 1.0f;
        return Math.max((float) get(MIN_MODEL_SIZE_VELOCITY_SCALE), bodyScale);
    }

    public static boolean shouldDebugRenderPhysics() { return debugRenderPhysics.get(); }
    public static boolean shouldLogPhysicsPerf() { return logPhysicsPerf.get(); }

    public static boolean isCorpseEnabled() { return get(ENABLE_CORPSES); }
    public static int getCorpseExpiryTicks() { return get(CORPSE_EXPIRY_TICKS); }
    public static int getCorpseSettleTimeoutTicks() { return get(CORPSE_SETTLE_TIMEOUT_TICKS); }
    public static boolean shouldStoreCorpseXp() { return get(CORPSE_STORE_XP); }
    public static boolean isCorpseCompassEnabled() { return isCorpseEnabled() && get(ENABLE_CORPSE_COMPASS); }
}
