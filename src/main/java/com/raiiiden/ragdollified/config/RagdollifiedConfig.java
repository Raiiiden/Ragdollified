package com.raiiiden.ragdollified.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class RagdollifiedConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue RAGDOLL_LIFETIME;
    public static final ForgeConfigSpec.IntValue MAX_RAGDOLLS;

    private static ForgeConfigSpec.BooleanValue debugRenderPhysics;

    static {
        BUILDER.push("Ragdoll Settings");

        RAGDOLL_LIFETIME = BUILDER
                .comment("How long ragdolls last before despawning (in ticks). 20 ticks = 1 second. Default: 600 (30 seconds)")
                .defineInRange("ragdollLifetime", 600, 20, 12000);

        MAX_RAGDOLLS = BUILDER
                .comment("Maximum number of ragdolls that can exist at once. Oldest ragdolls are removed when limit is exceeded. Default: 10")
                .defineInRange("maxRagdolls", 10, 1, 100);

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