package com.raiiiden.ragdollified.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of mob textures for ragdolls
 * Stores the last rendered texture for each entity
 */
public class ClientMobTextureCache {
    // entityId -> last texture used
    private static final Map<Integer, ResourceLocation> TEXTURE_CACHE = new ConcurrentHashMap<>();

    /**
     * Store texture for an entity (called during rendering)
     */
    public static void cacheTexture(int entityId, ResourceLocation texture) {
        TEXTURE_CACHE.put(entityId, texture);
    }

    /**
     * Get cached texture for a mob that just died
     * Returns null if not cached
     */
    public static ResourceLocation getTextureForDeadMob(int mobEntityId) {
        return TEXTURE_CACHE.remove(mobEntityId); // Remove after use
    }

    /**
     * Cleanup old entries
     */
    public static void cleanup() {
        if (TEXTURE_CACHE.size() > 200) {
            TEXTURE_CACHE.clear();
        }
    }
}