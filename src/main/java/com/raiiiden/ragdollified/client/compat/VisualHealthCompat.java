package com.raiiiden.ragdollified.client.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Compatibility helper for Visual Health (win.demistorm.visual_health).
//
// Visual Health does not draw wounds as a separate layer the way Better Blood Overlay does: it
// composites them into a copy of the entity's own texture and swaps that texture into the
// RenderType from inside MultiBufferSource.getBuffer, keyed off a thread-local "entity currently
// being rendered" its EntityRenderDispatcher mixin sets. Ragdollified resolves its own texture and
// builds its own RenderTypes, and the source entity is hidden (HideDeadEntityMixin) or gone by the
// time a ragdoll draws, so that swap never fires for us.
//
// So instead of intercepting anything, we ask VH's generator for the composited texture directly
// and render the ragdoll with it. Using the same builder inputs VH uses on a live entity
// (category "composite", the entity itself, its damage tier) means we hit the generator's own
// cache: the usual case reuses the exact texture the mob was already wearing rather than baking a
// new one.
public final class VisualHealthCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PKG = "win.demistorm.visual_health.";

    private static boolean available = false;

    // EntityHealthTracker statics
    private static Method updateTier;   // (LivingEntity) — recomputes the tier from current health
    private static Method getTier;      // (int entityId) -> int
    // DamageRenderCheck.shouldRender(LivingEntity, DamageCheck...) plus its ALL constant, so the
    // user's "damage players / passive mobs / villagers" toggles apply to ragdolls too.
    private static Method shouldRender;
    private static Object allChecks;
    // WoundTextureGenerator.builder() and the builder calls we need.
    private static Method builder;
    private static Method bCategory, bEntity, bDamageTier, bTexture, bComposite, bGenerate;

    // The entity is kept alive for the ragdoll's lifetime on purpose: the generator reads its
    // UUID (wound placement seed) and type (tint) long after the entity left the level. Dropped
    // in evict() when the ragdoll is destroyed.
    private record Captured(LivingEntity entity, int tier) {}

    // Keyed by whatever currently owns the damage: the source entity's id (an Integer) while a
    // physics ragdoll is the visible body, then the corpse entity's UUID once the corpse takes
    // over — same handoff the blood compat does.
    private static final Map<Object, Captured> CAPTURED = new ConcurrentHashMap<>();
    // key -> (base texture -> composited texture). A base texture mapped to itself means VH
    // produced nothing for it, cached so we don't re-enter the generator every frame.
    private static final Map<Object, Map<ResourceLocation, ResourceLocation>> RESOLVED = new ConcurrentHashMap<>();

    private VisualHealthCompat() {}

    public static void initialize() {
        if (!ModList.get().isLoaded("visual_health")) {
            LOGGER.info("Visual Health not found - ragdolls will render without damage textures");
            return;
        }
        try {
            Class<?> tracker = Class.forName(PKG + "client.damagestate.EntityHealthTracker");
            Class<?> renderCheck = Class.forName(PKG + "client.DamageRenderCheck");
            Class<?> damageCheck = Class.forName(PKG + "client.DamageRenderCheck$DamageCheck");
            Class<?> generator = Class.forName(PKG + "client.texture.WoundTextureGenerator");
            Class<?> builderClass = Class.forName(PKG + "client.texture.WoundTextureGenerator$Builder");

            updateTier = tracker.getMethod("updateEntityDamageTier", LivingEntity.class);
            getTier = tracker.getMethod("getDamageTier", int.class);

            shouldRender = renderCheck.getMethod("shouldRender", LivingEntity.class,
                    damageCheck.arrayType());
            allChecks = renderCheck.getField("ALL").get(null);

            builder = generator.getMethod("builder");
            bCategory = builderClass.getMethod("category", String.class);
            bEntity = builderClass.getMethod("entity", LivingEntity.class);
            bDamageTier = builderClass.getMethod("damageTier", int.class);
            bTexture = builderClass.getMethod("texture", ResourceLocation.class);
            bComposite = builderClass.getMethod("composite");
            bGenerate = builderClass.getMethod("generate");

            available = true;
            LOGGER.info("Visual Health compatibility initialized successfully");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize Visual Health compatibility", t);
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    // Snapshot an entity's damage state for the ragdoll about to replace it. Safe to call from
    // every spawn path — a repeat call at the same tier keeps the already-resolved textures.
    public static void capture(Object key, LivingEntity entity) {
        if (!available || entity == null) return;
        try {
            // VH updates the tier from its EntityRenderDispatcher mixin, which we cancel as soon
            // as a ragdoll exists — so the killing blow may never have been folded in. Do it here
            // while the entity still has its final health.
            updateTier.invoke(null, entity);

            if (!(boolean) shouldRender.invoke(null, entity, allChecks)) return;

            int tier = (int) getTier.invoke(null, entity.getId());
            if (tier <= 0) return;

            Captured existing = CAPTURED.get(key);
            if (existing != null && existing.tier() == tier) return;

            CAPTURED.put(key, new Captured(entity, tier));
            RESOLVED.remove(key);
        } catch (Throwable t) {
            LOGGER.debug("Failed to capture Visual Health damage for entity {}", entity.getId(), t);
        }
    }

    // The damaged version of a body's texture, or the texture unchanged when Visual Health is
    // absent, disabled for this entity, or the entity died undamaged. Render thread only — the
    // generator uploads a texture on a cache miss.
    public static ResourceLocation texture(Object key, ResourceLocation base) {
        if (!available || base == null || key == null) return base;
        Captured cap = CAPTURED.get(key);
        if (cap == null) return base;

        Map<ResourceLocation, ResourceLocation> perTexture =
                RESOLVED.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        ResourceLocation known = perTexture.get(base);
        if (known != null) return known;

        ResourceLocation wounded = base;
        try {
            Object b = builder.invoke(null);
            b = bCategory.invoke(b, "composite");
            b = bEntity.invoke(b, cap.entity());
            b = bDamageTier.invoke(b, cap.tier());
            b = bTexture.invoke(b, base);
            b = bComposite.invoke(b);
            ResourceLocation generated = (ResourceLocation) bGenerate.invoke(b);
            if (generated != null) wounded = generated;
        } catch (Throwable t) {
            LOGGER.debug("Failed to build Visual Health texture for {}", key, t);
        }

        perTexture.put(base, wounded);
        return wounded;
    }

    // Hand a body's damage over to a new owner when a settled ragdoll is replaced by its corpse.
    public static void transferTo(Object fromKey, Object toKey) {
        if (!available || fromKey.equals(toKey)) return;
        Captured cap = CAPTURED.remove(fromKey);
        if (cap != null) CAPTURED.put(toKey, cap);
        Map<ResourceLocation, ResourceLocation> resolved = RESOLVED.remove(fromKey);
        if (resolved != null) RESOLVED.put(toKey, resolved);
    }

    // Called when a ragdoll or corpse is gone for good. The generated textures themselves belong
    // to Visual Health's own cache (shared with the live-entity path), so there is nothing to free
    // here — only our entity reference and the per-body lookup. A corpse that merely unloads with
    // its chunk keeps its entry: it costs a reference, and there would be no way to rebuild it.
    public static void evict(Object key) {
        if (!available) return;
        CAPTURED.remove(key);
        RESOLVED.remove(key);
    }

    // Drop everything on disconnect: both key spaces are only meaningful within one connection.
    public static void clearAll() {
        if (!available) return;
        CAPTURED.clear();
        RESOLVED.clear();
    }
}
