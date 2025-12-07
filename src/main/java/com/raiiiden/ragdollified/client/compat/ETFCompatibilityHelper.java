package com.raiiiden.ragdollified.client.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Compatibility helper for Entity Texture Features (ETF) mod
 * Uses reflection to avoid hard dependency
 */
public class ETFCompatibilityHelper {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String ETF_API_CLASS = "traben.entity_texture_features.ETFApi";

    private static boolean isETFLoaded = false;
    private static Method getCurrentVariantMethod = null;

    public static void initialize() {
        if (!ModList.get().isLoaded("entity_texture_features")) {
            LOGGER.info("Entity Texture Features not found - using default textures only");
            return;
        }

        try {
            Class<?> etfApiClass = Class.forName(ETF_API_CLASS);

            // Get the method: getCurrentETFVariantTextureOfEntity(Entity, ResourceLocation)
            getCurrentVariantMethod = etfApiClass.getMethod(
                    "getCurrentETFVariantTextureOfEntity",
                    Entity.class,
                    ResourceLocation.class
            );

            isETFLoaded = true;
            LOGGER.info("ETF compatibility initialized successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize ETF compatibility", e);
            isETFLoaded = false;
        }
    }

    /**
     * Get the ETF variant texture for an entity, or return the default if ETF is not available
     */
    public static ResourceLocation getVariantTexture(Entity entity, ResourceLocation defaultTexture) {
        if (!isETFLoaded || getCurrentVariantMethod == null) {
            return defaultTexture;
        }

        try {
            Object result = getCurrentVariantMethod.invoke(null, entity, defaultTexture);
            if (result instanceof ResourceLocation) {
                return (ResourceLocation) result;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get ETF variant texture for entity {}", entity.getId(), e);
        }

        return defaultTexture;
    }

    public static boolean isInitialized() {
        return isETFLoaded;
    }
}