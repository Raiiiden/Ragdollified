package com.raiiiden.ragdollified.client;

import net.minecraft.client.model.HumanoidModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Custom humanoid models from optional compat adapters, remembered while the entity lives so its
// ragdoll keeps the geometry. Keyed by entity type, since one model instance serves the type.
public final class ClientMobModelCache {
    private static final Map<String, HumanoidModel<?>> MODEL_CACHE = new ConcurrentHashMap<>();

    private ClientMobModelCache() {}

    // Vanilla mobs are deliberately excluded by the caller, so entries here are always a mod's own
    // geometry: see ClientRagdollRenderer#humanoidModelFor for why.
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
