package com.raiiiden.ragdollified.config;

import com.raiiiden.ragdollified.RagdollPart;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

public class RagdollifiedConfig {

    // ============================
    // SERVER config (synced to clients on join)
    // ============================
    public static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SERVER_SPEC;

    public static final ForgeConfigSpec.IntValue RAGDOLL_LIFETIME;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAGDOLLS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PLAYER_RAGDOLLS;
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

    // Corpse feature — requires the mod on both server and client (or singleplayer).
    public static final ForgeConfigSpec.BooleanValue ENABLE_CORPSES;
    public static final ForgeConfigSpec.IntValue CORPSE_EXPIRY_TICKS;
    public static final ForgeConfigSpec.IntValue CORPSE_SETTLE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.BooleanValue CORPSE_STORE_XP;

    // ============================
    // CLIENT config (local only, never synced)
    // ============================
    public static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.DoubleValue RENDER_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEATH_CAMERA;
    public static final ForgeConfigSpec.DoubleValue GECKOLIB_ARMOR_RENDER_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ARMOR_RENDER_DISTANCE;
    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;
    private static ForgeConfigSpec.BooleanValue logPhysicsPerf;

    public static double getArmorRenderDistanceSq() {
        double d = ARMOR_RENDER_DISTANCE.get();
        return d * d;
    }

    public static double getGeckoLibArmorRenderDistanceSq() {
        double d = GECKOLIB_ARMOR_RENDER_DISTANCE.get();
        return d * d;
    }

    static {
        // ============================
        // SERVER spec
        // ============================
        SERVER_BUILDER.push("Ragdoll Settings");

        RAGDOLL_LIFETIME = SERVER_BUILDER
                .comment("How long ragdolls last before despawning, in ticks.")
                .defineInRange("ragdollLifetime", 600, 20, 12000);

        MAX_RAGDOLLS = SERVER_BUILDER
                .comment("Maximum number of ragdolls that can exist at once.")
                .defineInRange("maxRagdolls", 20, 1, 100);

        ENABLE_RAGDOLLS = SERVER_BUILDER
                .comment("Master switch for ragdoll spawning.")
                .define("enableRagdolls", true);

        ENABLE_PLAYER_RAGDOLLS = SERVER_BUILDER
                .comment("Allow player death ragdolls.")
                .define("enablePlayerRagdolls", true);

        ENTITY_DENYLIST = SERVER_BUILDER
                .comment("Entity registry ids that should never ragdoll. Use minecraft:player for players.")
                .defineListAllowEmpty(List.of("entityDenylist"), List.of(), value -> value instanceof String);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.comment("Hit Impulse Settings").push("hitImpulse");

        HIT_IMPULSE_HEADSHOT = SERVER_BUILDER
                .comment("Base impulse magnitude for a TACZ headshot.")
                .defineInRange("headshot", 8.0, 0.0, 200.0);

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
                .defineInRange("explosion", 60.0, 0.0, 200.0);

        HIT_IMPULSE_VERTICAL_BIAS = SERVER_BUILDER
                .comment("Constant upward kick added to every hit.")
                .defineInRange("verticalBias", 0.3, 0.0, 5.0);

        HIT_IMPULSE_DAMAGE_SCALING = SERVER_BUILDER
                .comment("Scale the impulse by sqrt(damage / damageReference).")
                .define("damageScaling", true);

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
        SERVER_BUILDER.comment("Physics Settings").push("physics");

        GRAVITY = SERVER_BUILDER.comment("World gravity used by ragdoll physics.")
                .defineInRange("gravity", 9.81, 0.0, 50.0);
        MASS_SCALE = SERVER_BUILDER.comment("Multiplier for rigid body mass.")
                .defineInRange("massScale", 1.0, 0.05, 20.0);
        INITIAL_VELOCITY_SCALE = SERVER_BUILDER.comment("Scale applied to inherited entity velocity.")
                .defineInRange("initialVelocityScale", 0.35, 0.0, 5.0);
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
                .defineInRange("friction", 0.90, 0.0, 5.0);
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
                .defineInRange("maxActiveRagdolls", 25, 1, 100);
        MAX_SPAWNS_PER_TICK = SERVER_BUILDER
                .comment("Max ragdoll bodies constructed per tick, so a mass kill (e.g. an explosion) doesn't spike one frame.")
                .defineInRange("maxSpawnsPerTick", 3, 1, 20);
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
                .define("enableCorpses", false);

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

        SERVER_BUILDER.pop();
        SERVER_SPEC = SERVER_BUILDER.build();

        // ============================
        // CLIENT spec
        // ============================
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
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, "ragdollified-server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, "ragdollified-client.toml");
    }

    public static int getRagdollLifetime() { return RAGDOLL_LIFETIME.get(); }
    public static int getMaxRagdolls() { return MAX_RAGDOLLS.get(); }

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

    public static boolean shouldDebugRenderPhysics() { return debugRenderPhysics.get(); }
    public static boolean shouldLogPhysicsPerf() { return logPhysicsPerf.get(); }

    // Read defensively: this SERVER value is queried from the client tick loop, which also
    // runs at the main menu (no config loaded) and on vanilla servers (config never synced).
    // ForgeConfigSpec#get() throws if the data isn't loaded yet, so gate on isLoaded().
    public static boolean isCorpseEnabled() { return SERVER_SPEC.isLoaded() && ENABLE_CORPSES.get(); }
    public static int getCorpseExpiryTicks() { return CORPSE_EXPIRY_TICKS.get(); }
    public static int getCorpseSettleTimeoutTicks() { return CORPSE_SETTLE_TIMEOUT_TICKS.get(); }
    public static boolean shouldStoreCorpseXp() { return CORPSE_STORE_XP.get(); }
}