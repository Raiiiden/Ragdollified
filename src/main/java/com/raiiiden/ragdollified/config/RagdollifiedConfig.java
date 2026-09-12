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
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VERTICAL_LIFT;
    public static final ForgeConfigSpec.BooleanValue HIT_IMPULSE_DAMAGE_SCALING;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_DAMAGE_REFERENCE;
    public static final ForgeConfigSpec.DoubleValue HIT_CENTER_LEEWAY;
    public static final ForgeConfigSpec.DoubleValue HIT_CENTER_DISTRIBUTION_SCALE;
    public static final ForgeConfigSpec.DoubleValue HIT_LIMB_WHIP;
    public static final ForgeConfigSpec.DoubleValue HIT_LIMB_WHIP_MASS_BIAS;
    public static final ForgeConfigSpec.DoubleValue HIT_SPIN_SCALE;
    public static final ForgeConfigSpec.DoubleValue HIT_ATTACKER_SIDE_BIAS;
    public static final ForgeConfigSpec.DoubleValue HIT_GROUND_LEVER;
    public static final ForgeConfigSpec.DoubleValue HIT_AIM_SPREAD;
    public static final ForgeConfigSpec.BooleanValue HIT_LOG_RESOLVED;
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

    // Humanoid joint cones, in degrees, per axis: X pitch, Y twist, Z roll. Six values a joint.
    public static final ForgeConfigSpec.DoubleValue[] NECK_LIMITS = new ForgeConfigSpec.DoubleValue[6];
    public static final ForgeConfigSpec.DoubleValue[] HIP_LIMITS = new ForgeConfigSpec.DoubleValue[6];
    public static final ForgeConfigSpec.DoubleValue[] SHOULDER_LIMITS = new ForgeConfigSpec.DoubleValue[6];

    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_TORSO;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_HEAD;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_LEFT_ARM;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_RIGHT_ARM;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_LEFT_LEG;
    public static final ForgeConfigSpec.DoubleValue PART_WEIGHT_RIGHT_LEG;

    // Reference weights of the humanoid skeleton as authored in RagdollBodyFactory. The configured
    // weight divided by these gives the multiplier, so other skeletons keep their own proportions.
    public static final ForgeConfigSpec.DoubleValue GRAVITY;
    public static final ForgeConfigSpec.DoubleValue MASS_SCALE;
    public static final ForgeConfigSpec.DoubleValue INITIAL_VELOCITY_SCALE;
    public static final ForgeConfigSpec.BooleanValue SCALE_VELOCITY_BY_MODEL_SIZE;
    public static final ForgeConfigSpec.DoubleValue MIN_MODEL_SIZE_VELOCITY_SCALE;
    public static final ForgeConfigSpec.DoubleValue DIRECTIONAL_CARRY_SPEED;
    public static final ForgeConfigSpec.DoubleValue LINEAR_DAMPING;
    public static final ForgeConfigSpec.DoubleValue ANGULAR_DAMPING;
    public static final ForgeConfigSpec.DoubleValue DIFFERENTIAL_DRAG;
    public static final ForgeConfigSpec.DoubleValue JOINT_RELAX_FREQUENCY;
    public static final ForgeConfigSpec.DoubleValue JOINT_RELAX_DAMPING;
    public static final ForgeConfigSpec.DoubleValue JOINT_RELAX_TORQUE;
    public static final ForgeConfigSpec.DoubleValue JOINT_RELAX_AIRBORNE_SCALE;
    public static final ForgeConfigSpec.DoubleValue JOINT_RANGE_SCALE;
    public static final ForgeConfigSpec.BooleanValue FREE_ANGULAR_AXES;
    public static final ForgeConfigSpec.DoubleValue FRICTION;
    public static final ForgeConfigSpec.DoubleValue RESTITUTION;
    public static final ForgeConfigSpec.DoubleValue MAX_LINEAR_SPEED;
    public static final ForgeConfigSpec.DoubleValue MAX_ANGULAR_SPEED;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_RAGDOLLS;
    public static final ForgeConfigSpec.IntValue MAX_SPAWNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue SETTLE_DELAY_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_SPAWN_QUEUE_SIZE;
    public static final ForgeConfigSpec.DoubleValue PHYSICS_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue PLAYER_COLLISION_DISTANCE;

    // CLIENT config (local only, never synced)
    public static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.DoubleValue RENDER_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEATH_CAMERA;
    public static final ForgeConfigSpec.DoubleValue GECKOLIB_ARMOR_RENDER_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ARMOR_RENDER_DISTANCE;
    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;
    private static ForgeConfigSpec.BooleanValue logPhysicsPerf;
    public static final ForgeConfigSpec.ConfigValue<String> PHYSICS_ENGINE;
    public static final ForgeConfigSpec.IntValue PHYSICS_SOLVER_THREADS;

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
                .comment("Maximum ragdolls that can exist at once; the oldest is removed past this.",
                        "Raise it if your hardware can handle more (watch physics= in the perf log).")
                .defineInRange("maxRagdolls", 40, 1, 1000);

        MAX_RAGDOLLS_PER_PLAYER = SERVER_BUILDER
                .comment("Maximum death ragdolls one player can have at once; their oldest is removed past this.",
                        "Addons may bypass this for bodies that must not be removed (like corpses holding loot).")
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
                .comment("Entity ids that never ragdoll, like minecraft:zombie. Use minecraft:player for players.")
                .defineListAllowEmpty(List.of("entityDenylist"),
                        List.of(),
                        value -> value instanceof String);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Hit Impulse Settings").push("hitImpulse");

        HIT_IMPULSE_HEADSHOT = SERVER_BUILDER
                .comment("Base impulse magnitude for a TACZ headshot.")
                .defineInRange("headshot", 6.0, 0.0, 200.0);

        HIT_IMPULSE_BODY = SERVER_BUILDER
                .comment("Base impulse magnitude for a TACZ body shot.")
                .defineInRange("body", 4.0, 0.0, 200.0);

        HIT_IMPULSE_MELEE = SERVER_BUILDER
                .comment("Base impulse magnitude for melee kills.")
                .defineInRange("melee", 10.0, 0.0, 200.0);

        HIT_IMPULSE_VANILLA_PROJECTILE = SERVER_BUILDER
                .comment("Base impulse magnitude for vanilla projectiles.")
                .defineInRange("vanillaProjectile", 8.0, 0.0, 200.0);

        HIT_IMPULSE_EXPLOSION = SERVER_BUILDER
                .comment("Launch speed in blocks/second for a body killed point-blank by an explosion (TNT, creepers, TACZ explosives, etc.).",
                        "Eases down to 60% at 8+ blocks from the blast, independent of the victim's health.",
                        "Higher = bodies thrown further.")
                .defineInRange("explosionLaunchSpeed", 35.0, 0.0, 90.0);

        HIT_IMPULSE_VERTICAL_BIAS = SERVER_BUILDER
                .comment("Flat upward kick added to every hit, in the same units as the impulses above.",
                        "Prefer verticalLift below, which scales with the strength of the hit.")
                .defineInRange("verticalBias", 0.3, 0.0, 5.0);

        HIT_IMPULSE_VERTICAL_LIFT = SERVER_BUILDER.comment(
                        "Upward kick as a fraction of the hit's horizontal strength, added on top of verticalBias.",
                        "Lifts the feet off the ground so bodies tumble instead of sliding. 0 = flat bias only.")
                .defineInRange("verticalLift", 0.55, 0.0, 4.0);

        HIT_IMPULSE_DAMAGE_SCALING = SERVER_BUILDER
                .comment("Scale the impulse by sqrt(damage / damageReference).")
                .define("damageScaling", false);

        HIT_IMPULSE_DAMAGE_REFERENCE = SERVER_BUILDER
                .comment("Damage value at which the impulse equals the base magnitude.")
                .defineInRange("damageReference", 6.0, 0.1, 100.0);

        HIT_AIM_SPREAD = SERVER_BUILDER.comment(
                        "How much hitting off-centre on a body part spins the body, as a multiplier.",
                        "1 = realistic, higher = more exaggerated, 0 = ignore where the hit landed.")
                .defineInRange("aimSpread", 2.0, 0.0, 8.0);

        HIT_ATTACKER_SIDE_BIAS = SERVER_BUILDER.comment(
                        "Extra spin for hits that come in from the side at an angle, even when aimed dead centre.",
                        "Grows with the angle, as a fraction of the part's half-width. 0 = no extra spin.")
                .defineInRange("attackerSideBias", 1.0, 0.0, 1.0);

        HIT_GROUND_LEVER = SERVER_BUILDER.comment(
                        "For the killing hit on a standing body, how much it pivots around the feet instead of",
                        "its centre (0 to 1). Higher makes bodies topple over rather than slide back.")
                .defineInRange("groundLever", 0.7, 0.0, 1.0);

        HIT_CENTER_LEEWAY = SERVER_BUILDER
                .comment("Width of the band down the middle of the chest treated as a dead-centre hit, as a",
                        "fraction of the mob's width. Hits inside it cause no spin. 0 = off (recommended).")
                .defineInRange("centerLeeway", 0.0, 0.0, 0.5);

        HIT_CENTER_DISTRIBUTION_SCALE = SERVER_BUILDER
                .comment("Impulse scale for hits inside the centerLeeway band. Unused while centerLeeway is 0.")
                .defineInRange("centerDistributionScale", 0.85, 0.0, 2.0);

        HIT_LIMB_WHIP = SERVER_BUILDER.comment(
                        "Extra kick for the limb that was hit, as a fraction of the hit, on top of the whole-body push.",
                        "Makes arms snap back and legs kick out. 0 = the body moves as one rigid piece.")
                .defineInRange("limbWhip", 0.45, 0.0, 3.0);
        HIT_LIMB_WHIP_MASS_BIAS = SERVER_BUILDER.comment(
                        "How much stronger the limb whip is on light parts than heavy ones.",
                        "0 = every part equal, 1 = inversely proportional to mass (arms flail, torso barely moves).")
                .defineInRange("limbWhipMassBias", 0.60, 0.0, 2.0);
        HIT_SPIN_SCALE = SERVER_BUILDER.comment(
                        "Multiplier on how much a hit spins the body. 1.0 = realistic, higher = more dramatic.")
                .defineInRange("spinScale", 1.35, 0.0, 5.0);
        HIT_LOG_RESOLVED = SERVER_BUILDER.comment(
                        "Log every killing hit to the server log: the part hit, where it landed and the impulse.",
                        "Useful for debugging hits that look wrong. One line per kill.")
                .define("logResolvedHits", false);

        PART_KNOCKBACK_TORSO = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, fortorso hits.")
                .defineInRange("partMultiplierTorso", 1.0, 0.0, 10.0);
        PART_KNOCKBACK_HEAD = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, forhead hits.")
                .defineInRange("partMultiplierHead", 1.25, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_LEG = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, forleft leg hits.")
                .defineInRange("partMultiplierLeftLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_LEG = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, forright leg hits.")
                .defineInRange("partMultiplierRightLeg", 0.75, 0.0, 10.0);
        PART_KNOCKBACK_LEFT_ARM = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, forleft arm hits.")
                .defineInRange("partMultiplierLeftArm", 1.1, 0.0, 10.0);
        PART_KNOCKBACK_RIGHT_ARM = SERVER_BUILDER.comment("Knockback multiplier, after the ragdoll spawns, forright arm hits.")
                .defineInRange("partMultiplierRightArm", 1.1, 0.0, 10.0);

        DEATH_PART_KNOCKBACK_TORSO = SERVER_BUILDER.comment("Knockback multiplier on the killing blow fortorso hits.")
                .defineInRange("deathPartMultiplierTorso", 2.5, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_HEAD = SERVER_BUILDER.comment("Knockback multiplier on the killing blow forhead hits.")
                .defineInRange("deathPartMultiplierHead", 2.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_LEG = SERVER_BUILDER.comment("Knockback multiplier on the killing blow forleft leg hits.")
                .defineInRange("deathPartMultiplierLeftLeg", 8.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_LEG = SERVER_BUILDER.comment("Knockback multiplier on the killing blow forright leg hits.")
                .defineInRange("deathPartMultiplierRightLeg", 8.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_LEFT_ARM = SERVER_BUILDER.comment("Knockback multiplier on the killing blow forleft arm hits.")
                .defineInRange("deathPartMultiplierLeftArm", 8.0, 0.0, 10.0);
        DEATH_PART_KNOCKBACK_RIGHT_ARM = SERVER_BUILDER.comment("Knockback multiplier on the killing blow forright arm hits.")
                .defineInRange("deathPartMultiplierRightArm", 8.0, 0.0, 10.0);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Humanoid Joint Limits",
                        "How far each humanoid joint can bend, in degrees: pitch (forward/back), twist (about",
                        "the limb) and roll (sideways). Only affects humanoids (players, zombies, villagers, etc.).",
                        "Wider = looser and more fluid; narrower = stiffer. Past ~120 degrees poses look unnatural.")
                .push("humanoidJointLimits");

        JOINT_RANGE_SCALE = SERVER_BUILDER
                .comment("Multiplier on how far every joint can twist and roll, for all mobs (not just humanoids).",
                        "Higher = looser, less stiff corpses. Pitch (forward/back bending) is not affected.")
                .defineInRange("rangeScale", 1.5, 0.25, 4.0);
        FREE_ANGULAR_AXES = SERVER_BUILDER
                .comment("Remove all joint angle limits and let the relaxation springs alone hold the pose.",
                        "Smoother, but allows unnatural poses (like a head turning all the way round).",
                        "Requires jointRelaxation.frequency above 0, or bodies fold flat.")
                .define("freeAngularAxes", false);

        defineJoint(NECK_LIMITS, "neck", "the head on the torso",
                -40, -35, -35,  40, 55, 35);
        // Hips opened on backward pitch, twist and roll; forward pitch stays at a measured 75,
        // since 90 lets legs jack-knife under the body.
        defineJoint(HIP_LIMITS, "hip", "a leg on the torso",
                -55, -35, -45,  75, 35, 45);
        defineJoint(SHOULDER_LIMITS, "shoulder", "an arm on the torso",
                -120, -50, -95, 120, 50, 95);

        SERVER_BUILDER.comment("Joint Relaxation",
                        "A weak spring in every joint that pulls limbs back toward a natural rest pose, so",
                        "corpses settle loosely instead of staying stiff. Jolt engine only; JBullet ignores these.")
                .push("jointRelaxation");
        JOINT_RELAX_FREQUENCY = SERVER_BUILDER
                .comment("Spring strength for arms and legs, in Hz. Higher = limbs return to rest faster and look",
                        "stiffer; lower = looser, more dead-weight swinging. 0 disables relaxation.")
                .defineInRange("frequency", 0.3, 0.0, 20.0);
        JOINT_RELAX_DAMPING = SERVER_BUILDER
                .comment("Spring damping. 1.0 = limbs settle without bouncing; below 1 they swing past and back;",
                        "above 1 they creep in slowly.")
                .defineInRange("damping", 0.5, 0.0, 10.0);
        JOINT_RELAX_TORQUE = SERVER_BUILDER
                .comment("Maximum force a joint spring can pull with, in newton-metres.",
                        "Too high and bodies start posing themselves instead of lying where they fell.")
                .defineInRange("maxTorque", 1.5, 0.0, 100.0);
        JOINT_RELAX_AIRBORNE_SCALE = SERVER_BUILDER
                .comment("How much spring strength remains while a body is falling. 1 = full, 0 = none until it lands.",
                        "Lower lets limbs flail in the air instead of the body falling like a statue.")
                .defineInRange("airborneScale", 0.15, 0.0, 1.0);
        SERVER_BUILDER.pop();

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Per-Body-Part Weight Settings",
                        "Multiplier on each body part's mass (1.0 = default), for every mob. Heavier parts resist",
                        "knockback more; lighter parts flail more. Also multiplied by physics.massScale.")
                .push("partWeightMultipliers");

        PART_WEIGHT_TORSO = SERVER_BUILDER.comment("Weight multiplier for the torso.")
                .defineInRange("torso", 1.0, 0.05, 20.0);
        PART_WEIGHT_HEAD = SERVER_BUILDER.comment("Weight multiplier for the head.")
                .defineInRange("head", 1.0, 0.05, 20.0);
        PART_WEIGHT_LEFT_ARM = SERVER_BUILDER.comment("Weight multiplier for the left arm.")
                .defineInRange("leftArm", 1.0, 0.05, 20.0);
        PART_WEIGHT_RIGHT_ARM = SERVER_BUILDER.comment("Weight multiplier for the right arm.")
                .defineInRange("rightArm", 1.0, 0.05, 20.0);
        PART_WEIGHT_LEFT_LEG = SERVER_BUILDER.comment("Weight multiplier for the left leg.")
                .defineInRange("leftLeg", 1.0, 0.05, 20.0);
        PART_WEIGHT_RIGHT_LEG = SERVER_BUILDER.comment("Weight multiplier for the right leg.")
                .defineInRange("rightLeg", 1.0, 0.05, 20.0);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Physics Settings").push("physics");

        GRAVITY = SERVER_BUILDER.comment("Gravity strength for ragdoll physics.")
                .defineInRange("gravity", 15.00, 0.0, 50.0);
        MASS_SCALE = SERVER_BUILDER.comment("Global multiplier on every ragdoll body's mass.")
                .defineInRange("massScale", 1.0, 0.05, 20.0);
        INITIAL_VELOCITY_SCALE = SERVER_BUILDER
                .comment("Multiplier on the velocity a ragdoll inherits from the entity when it dies.",
                        "1.0 keeps its movement; lower reduces momentum.")
                .defineInRange("initialVelocityScale", 1.0, 0.0, 5.0);
        SCALE_VELOCITY_BY_MODEL_SIZE = SERVER_BUILDER
                .comment("Reduce inherited velocity and knockback for ragdolls smaller than a player, so small",
                        "mobs (chickens, bats, bees) don't get launched. Never boosts larger bodies.")
                .define("scaleVelocityByModelSize", true);
        MIN_MODEL_SIZE_VELOCITY_SCALE = SERVER_BUILDER
                .comment("Lowest multiplier the size reduction above can apply, so tiny ragdolls still react to hits.",
                        "Lower = small mobs fly less; 1.0 disables the reduction.")
                .defineInRange("minModelSizeVelocityScale", 0.25, 0.05, 1.0);
        DIRECTIONAL_CARRY_SPEED = SERVER_BUILDER
                .comment("Minimum speed, in blocks/second, given to a mob that dies standing still, pushed in the",
                        "attacker's look direction. 0 disables it.")
                .defineInRange("directionalCarrySpeed", 0.75, 0.0, 20.0);
        LINEAR_DAMPING = SERVER_BUILDER
                .comment("How quickly ragdolls lose movement speed. 0 = never, 1 = stop almost instantly.",
                        "Higher means bodies slide less.")
                .defineInRange("linearDamping", 0.10, 0.0, 1.0);
        ANGULAR_DAMPING = SERVER_BUILDER
                .comment("How quickly ragdolls lose spin. 0 = never, 1 = stop almost instantly.",
                        "Higher means bodies stop tumbling sooner; too high makes hits barely turn them.")
                .defineInRange("angularDamping", 0.15, 0.0, 1.0);
        DIFFERENTIAL_DRAG = SERVER_BUILDER
                .comment("Air drag split between body parts by shape, so light limbs trail behind the torso while",
                        "falling. 0 = same drag for every part, 1 = realistic, 2 = exaggerated. Fall speed is",
                        "unchanged. Above ~2.5 bodies start to look like they're coming apart.")
                .defineInRange("differentialDrag", 1.0, 0.0, 4.0);
        FRICTION = SERVER_BUILDER
                .comment("Surface friction for ragdoll bodies and the blocks they land on.",
                        "Higher = bodies slide less and piles stick together more.")
                .defineInRange("friction", 0.65, 0.0, 5.0);
        RESTITUTION = SERVER_BUILDER
                .comment("Bounciness on impact. 0 = no bounce, 1 = fully elastic. Keep low to avoid jittery bodies.")
                .defineInRange("restitution", 0.0, 0.0, 2.0);
        MAX_LINEAR_SPEED = SERVER_BUILDER
                .comment("Hard cap on a body's speed in blocks/second, to keep the physics stable.")
                .defineInRange("maxLinearSpeed", 90.0, 1.0, 500.0);
        MAX_ANGULAR_SPEED = SERVER_BUILDER
                .comment("Hard cap on spin rate in radians/second. Prevents bodies from spinning wildly on hard hits.")
                .defineInRange("maxAngularSpeed", 8.0, 0.1, 100.0);
        MAX_ACTIVE_RAGDOLLS = SERVER_BUILDER
                .comment("Max ragdolls simulated at once; past this the oldest are frozen until something wakes them.",
                        "The main performance setting. Raise it if the physics= figure in the perf log has headroom.")
                .defineInRange("maxActiveRagdolls", 40, 1, 1000);
        MAX_SPAWNS_PER_TICK = SERVER_BUILDER
                .comment("Max ragdolls built per tick, so mass kills (like explosions) don't cause a lag spike.",
                        "Extra spawns wait in the queue instead of being dropped.")
                .defineInRange("maxSpawnsPerTick", 15, 1, 200);
        MAX_SPAWN_QUEUE_SIZE = SERVER_BUILDER
                .comment("Max ragdoll spawns waiting to be built. Deaths while the queue is full get no ragdoll.")
                .defineInRange("maxSpawnQueueSize", 60, 1, 2000);
        SETTLE_DELAY_TICKS = SERVER_BUILDER
                .comment("How long a ragdoll must lie still before it freezes, in ticks (20 = one second).",
                        "Frozen bodies cost almost nothing; a longer delay lets bodies finish toppling.")
                .defineInRange("settleDelayTicks", 20, 5, 200);
        PHYSICS_DISTANCE = SERVER_BUILDER.comment("Distance in blocks beyond which ragdoll physics freezes.")
                .defineInRange("physicsDistance", 128.0, 4.0, 512.0);
        PLAYER_COLLISION_DISTANCE = SERVER_BUILDER.comment("Distance in blocks within which player movement pushes ragdolls.")
                .defineInRange("playerCollisionDistance", 12.0, 0.0, 128.0);

        SERVER_BUILDER.pop();
        GAMEPLAY_SPEC = SERVER_BUILDER.build();
        registerGameplayKeys();

        // CLIENT spec
        CLIENT_BUILDER.comment("Render Settings").push("render");

        RENDER_DISTANCE = CLIENT_BUILDER.comment("Distance in blocks beyond which ragdolls do not render.")
                .defineInRange("renderDistance", 128.0, 4.0, 512.0);

        ENABLE_DEATH_CAMERA = CLIENT_BUILDER
                .comment("When you die, follow your own ragdoll with the camera.")
                .define("enableDeathCamera", true);

        GECKOLIB_ARMOR_RENDER_DISTANCE = CLIENT_BUILDER
                .comment("Distance in blocks within which GeckoLib animated armor renders on ragdolls.",
                        "Lower improves performance. Should not exceed renderDistance.")
                .defineInRange("geckolibArmorRenderDistance", 80.0, 4.0, 128.0);
        ARMOR_RENDER_DISTANCE = CLIENT_BUILDER
                .comment("Distance in blocks within which vanilla armor renders on ragdolls.")
                .defineInRange("armorRenderDistance", 100.0, 4.0, 512.0);

        CLIENT_BUILDER.pop();
        CLIENT_BUILDER.comment("Physics Engine").push("physics");

        PHYSICS_ENGINE = CLIENT_BUILDER
                .comment("Physics engine for ragdolls: jolt (default, native and fast) or jbullet (pure Java, slower).",
                        "Jolt falls back to jbullet automatically if it can't load. Applies on the next world load.")
                .define("physicsEngine", "jolt");

        PHYSICS_SOLVER_THREADS = CLIENT_BUILDER
                .comment("Extra worker threads Jolt may use for physics. 0 = pick automatically from CPU count.",
                        "Ignored by jbullet.")
                .defineInRange("physicsSolverThreads", 0, 0, 16);

        CLIENT_BUILDER.pop();
        CLIENT_BUILDER.comment("Debug Options").push("debug");

        debugRenderPhysics = CLIENT_BUILDER
                .comment("Render debug boxes around ragdoll physics bodies.")
                .define("debugRenderPhysics", true);

        logPhysicsPerf = CLIENT_BUILDER
                .comment("Periodically log ragdoll physics performance stats ([Ragdoll Perf]) to the client log.",
                        "Useful for profiling; leave off for normal play.")
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
        register("hitImpulseVerticalLift", HIT_IMPULSE_VERTICAL_LIFT);
        register("hitImpulseDamageScaling", HIT_IMPULSE_DAMAGE_SCALING);
        register("hitImpulseDamageReference", HIT_IMPULSE_DAMAGE_REFERENCE);
        register("hitCenterLeeway", HIT_CENTER_LEEWAY);
        register("hitCenterDistributionScale", HIT_CENTER_DISTRIBUTION_SCALE);
        register("hitLimbWhip", HIT_LIMB_WHIP);
        register("hitLimbWhipMassBias", HIT_LIMB_WHIP_MASS_BIAS);
        register("hitSpinScale", HIT_SPIN_SCALE);
        register("hitAimSpread", HIT_AIM_SPREAD);
        register("hitAttackerSideBias", HIT_ATTACKER_SIDE_BIAS);
        register("hitGroundLever", HIT_GROUND_LEVER);
        String[] jointNames = {"neck", "hip", "shoulder"};
        ForgeConfigSpec.DoubleValue[][] jointValues = {NECK_LIMITS, HIP_LIMITS, SHOULDER_LIMITS};
        String[] jointAxes = {"MinPitch", "MinTwist", "MinRoll", "MaxPitch", "MaxTwist", "MaxRoll"};
        for (int j = 0; j < jointNames.length; j++) {
            for (int i = 0; i < 6; i++) {
                register("joint" + jointNames[j] + jointAxes[i], jointValues[j][i]);
            }
        }
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
        register("differentialDrag", DIFFERENTIAL_DRAG);
        register("jointRelaxFrequency", JOINT_RELAX_FREQUENCY);
        register("jointRelaxDamping", JOINT_RELAX_DAMPING);
        register("jointRelaxTorque", JOINT_RELAX_TORQUE);
        register("jointRelaxAirborneScale", JOINT_RELAX_AIRBORNE_SCALE);
        register("jointRangeScale", JOINT_RANGE_SCALE);
        register("freeAngularAxes", FREE_ANGULAR_AXES);
        register("friction", FRICTION);
        register("restitution", RESTITUTION);
        register("maxLinearSpeed", MAX_LINEAR_SPEED);
        register("maxAngularSpeed", MAX_ANGULAR_SPEED);
        register("maxActiveRagdolls", MAX_ACTIVE_RAGDOLLS);
        register("maxSpawnsPerTick", MAX_SPAWNS_PER_TICK);
        register("settleDelayTicks", SETTLE_DELAY_TICKS);
        register("maxSpawnQueueSize", MAX_SPAWN_QUEUE_SIZE);
        register("physicsDistance", PHYSICS_DISTANCE);
        register("playerCollisionDistance", PLAYER_COLLISION_DISTANCE);
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

    // Per-part weight as a multiplier against the skeleton's authored mass, so one setting means the
    // same thing on every model.
    public static float getPartWeightMultiplier(RagdollPart part) {
        return switch (part) {
            case TORSO -> (float) get(PART_WEIGHT_TORSO);
            case HEAD -> (float) get(PART_WEIGHT_HEAD);
            case LEFT_LEG -> (float) get(PART_WEIGHT_LEFT_LEG);
            case RIGHT_LEG -> (float) get(PART_WEIGHT_RIGHT_LEG);
            case LEFT_ARM -> (float) get(PART_WEIGHT_LEFT_ARM);
            case RIGHT_ARM -> (float) get(PART_WEIGHT_RIGHT_ARM);
        };
    }

    // Six entries in the order the SixDOF constraint wants them: min x/y/z then max x/y/z.
    private static void defineJoint(ForgeConfigSpec.DoubleValue[] out, String name, String what,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        SERVER_BUILDER.comment("Angular limits for the " + name + ", " + what + ".").push(name);
        String[] axes = {"minPitch", "minTwist", "minRoll", "maxPitch", "maxTwist", "maxRoll"};
        double[] defaults = {minX, minY, minZ, maxX, maxY, maxZ};
        for (int i = 0; i < 6; i++) {
            out[i] = SERVER_BUILDER.defineInRange(axes[i], defaults[i], -180.0, 180.0);
        }
        SERVER_BUILDER.pop();
    }

    // A joint's six angular limits as floats: min xyz then max xyz.
    public static float[] getJointLimits(ForgeConfigSpec.DoubleValue[] joint) {
        float[] out = new float[6];
        for (int i = 0; i < 6; i++) out[i] = (float) get(joint[i]);
        // A min above its max is a config the solver cannot honour; swap rather than lock the axis.
        for (int axis = 0; axis < 3; axis++) {
            if (out[axis] > out[axis + 3]) {
                float swap = out[axis];
                out[axis] = out[axis + 3];
                out[axis + 3] = swap;
            }
        }
        return out;
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

    public static String getPhysicsEngine() { return PHYSICS_ENGINE.get(); }

    // 0 means "decide from the CPU count". Deliberately capped low: Jolt's pool competes with the
    // render and main threads, and past three workers a ragdoll pile stops being the bottleneck.
    public static int getPhysicsSolverThreads() {
        int configured = PHYSICS_SOLVER_THREADS.get();
        if (configured > 0) return configured;
        return Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 3));
    }
    public static boolean shouldLogPhysicsPerf() { return logPhysicsPerf.get(); }

}
