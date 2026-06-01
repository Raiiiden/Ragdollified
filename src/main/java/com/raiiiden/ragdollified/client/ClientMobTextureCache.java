package com.raiiiden.ragdollified.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMobTextureCache {
    private static final Map<Integer, ResourceLocation> TEXTURE_CACHE = new ConcurrentHashMap<>();

    public static void cacheTexture(int entityId, ResourceLocation texture) {
        TEXTURE_CACHE.put(entityId, texture);
    }

    /**
     * Get cached texture for a mob. Does NOT remove — the ragdoll holds a reference
     * to this and will read it on every getMobTexture call until it stores it locally.
     * Call evict() when the ragdoll is destroyed.
     */
    public static ResourceLocation getTextureForDeadMob(int mobEntityId) {
        return TEXTURE_CACHE.get(mobEntityId);
    }

    /**
     * Evict a mob's texture entry. Call this from ClientRagdoll.destroy() so the
     * cache doesn't hold entries for ragdolls that are gone.
     */
    public static void evict(int mobEntityId) {
        TEXTURE_CACHE.remove(mobEntityId);
    }

    public static void cleanup() {
        if (TEXTURE_CACHE.size() > 200) {
            TEXTURE_CACHE.clear();
        }
    }
}