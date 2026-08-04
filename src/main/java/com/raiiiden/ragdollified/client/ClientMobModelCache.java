package com.raiiiden.ragdollified.client;

import net.minecraft.client.model.HumanoidModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Custom humanoid models supplied by optional client compatibility adapters. They are remembered
// while the entity is alive so its ragdoll can use the same geometry after the entity is gone.
//
// Keyed by entity type id, not entity id: an EntityRenderer holds one model instance shared by
// every mob of that type, so one entry serves them all and survives any individual death.
public final class ClientMobModelCache {
    private static final Map<String, HumanoidModel<?>> MODEL_CACHE = new ConcurrentHashMap<>();

    private ClientMobModelCache() {}

    // Vanilla mobs are deliberately excluded by the caller, so entries here are always a mod's own
    // geometry — see ClientRagdollRenderer#humanoidModelFor for why.
    public static void cacheModel(String mobType, HumanoidModel<?> model) {
        // Refresh after resource reloads, which rebuild renderers without necessarily unloading
        // the world. Keeping the first instance would leave ragdolls on a stale model tree.
        MODEL_CACHE.put(mobType, model);
    }

    public static HumanoidModel<?> getModel(String mobType) {
        return MODEL_CACHE.get(mobType);
    }

    // Renderers are rebuilt on world reload, so the instances here go stale with them.
    public static void clear() {
        MODEL_CACHE.clear();
    }
}
