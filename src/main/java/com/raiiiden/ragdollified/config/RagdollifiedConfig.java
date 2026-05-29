package com.raiiiden.ragdollified.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class RagdollifiedConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue RAGDOLL_LIFETIME;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS;

    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_HEADSHOT;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_BODY;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VANILLA_PROJECTILE;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_VERTICAL_BIAS;
    public static final ForgeConfigSpec.BooleanValue HIT_IMPULSE_DAMAGE_SCALING;
    public static final ForgeConfigSpec.DoubleValue HIT_IMPULSE_DAMAGE_REFERENCE;

    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;

    static {
        BUILDER.push("Ragdoll Settings");

        RAGDOLL_LIFETIME = BUILDER
                .comment("How long ragdolls last before despawning (in ticks). 20 ticks = 1 second. Default: 600 (30 seconds)")
                .defineInRange("ragdollLifetime", 600, 20, 12000);

        MAX_RAGDOLLS = BUILDER
                .comment("Maximum number of ragdolls that can exist at once. Oldest ragdolls are removed when limit is exceeded. Default: 20")
                .defineInRange("maxRagdolls", 20, 1, 100);

        BUILDER.pop();
        BUILDER.comment("Hit Impulse Settings — controls how hard a ragdoll part is whipped on death.").push("hitImpulse");

        HIT_IMPULSE_HEADSHOT = BUILDER
                .comment("Base impulse magnitude for a TACZ headshot. Lower = less violent corpse-whip. Default: 18.0 (was 32.0).")
                .defineInRange("headshot", 8.0, 0.0, 200.0);

        HIT_IMPULSE_BODY = BUILDER
                .comment("Base impulse magnitude for a TACZ body shot. Default: 11.0 (was 20.0).")
                .defineInRange("body", 6.0, 0.0, 200.0);

        HIT_IMPULSE_VANILLA_PROJECTILE = BUILDER
                .comment("Base impulse magnitude for vanilla projectiles (arrows, tridents, snowballs). Default: 8.0 (was 14.0).")
                .defineInRange("vanillaProjectile", 8.0, 0.0, 200.0);

        HIT_IMPULSE_VERTICAL_BIAS = BUILDER
                .comment("Constant upward kick added to every hit so corpses arc instead of slamming flat. Default: 0.3 (was 0.5).")
                .defineInRange("verticalBias", 0.3, 0.0, 5.0);

        HIT_IMPULSE_DAMAGE_SCALING = BUILDER
                .comment("Scale the impulse by sqrt(damage / damageReference) so a pellet barely twitches and a sniper round whips. Disable for flat magnitudes.")
                .define("damageScaling", true);

        HIT_IMPULSE_DAMAGE_REFERENCE = BUILDER
                .comment("Damage value at which the impulse equals the base magnitude (assault-rifle territory). Below = scaled down, above = scaled up. Default: 6.0.")
                .defineInRange("damageReference", 6.0, 0.1, 100.0);

        BUILDER.pop();
        BUILDER.comment("Debug Options").push("debug");

        debugRenderPhysics = BUILDER
                .comment("Render debug boxes around ragdoll physics bodies (for development)")
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

    public static boolean shouldDebugRenderPhysics() {
        return debugRenderPhysics.get();
    }
}