package com.raiiiden.ragdollified.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMobTextureCache {
    private static final Map<Integer, ResourceLocation> TEXTURE_CACHE = new ConcurrentHashMap<>();

    public static void cacheTexture(int entityId, ResourceLocation texture) {
        TEXTURE_CACHE.put(entityId, texture);
    }

    // Cached texture for a mob. Does not remove: the ragdoll reads this on every
    // getMobTexture call until it stores its own copy. Use evict() when it is destroyed.
    public static ResourceLocation getTextureForDeadMob(int mobEntityId) {
        return TEXTURE_CACHE.get(mobEntityId);
    }

    // Drop a mob's entry. Called from ClientRagdoll.destroy() so the cache never holds
    // textures for ragdolls that are gone.
    public static void evict(int mobEntityId) {
        TEXTURE_CACHE.remove(mobEntityId);
    }

    public static void cleanup() {
        if (TEXTURE_CACHE.size() > 200) {
            TEXTURE_CACHE.clear();
        }
    }

}
