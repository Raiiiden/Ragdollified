package com.raiiiden.ragdollified.client.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Compatibility helper for Better Blood Overlay (tailorworks.betterbloodoverlay_1_20_1).

public class BetterBloodOverlayCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PKG = "tailorworks.betterbloodoverlay_1_20_1.";

    private static boolean available = false;

    // WoundStore.getWounds(UUID) -> List<ClientWound>
    private static Method getWounds;
    // WoundTextureManager statics
    private static Method create;      // (UUID, WoundRecord, MobProfile) -> ClientWound
    private static Method revealAll;   // (ClientWound)
    private static Method release;     // (ClientWound)
    // BloodOverlayRenderer.computeAlpha(ClientWound) -> float  (package-private)
    private static Method computeAlpha;
    // BloodVisuals.overlayBlood() -> boolean (whether second-skin-layer parts get blood)
    private static Method overlayBlood;
    // ClientWound fields
    private static Field cwData;        // WoundRecord
    private static Field cwProfile;     // MobProfile
    private static Field cwTexLocation; // ResourceLocation
    // WoundRecord.limb() -> Limb
    private static Method wrLimb;
    // MobProfile accessors
    private static Method mpSites;      // () -> Map<Limb, List<WoundSite>>
    private static Method mpTintR, mpTintG, mpTintB;
    // WoundSite.part() -> String
    private static Method wsPart;

    // A ready-to-draw blood decal, free of any BBO types.
    public record Decal(ResourceLocation texture, Set<String> parts,
                        float r, float g, float b, float alpha) {}

    // Raw wound data snapshotted from a live mob (BBO types held opaquely as Object).
    private record Captured(List<Object> records, Object profile) {}

    // Rebuilt, render-ready decals for a ragdoll, plus the ClientWounds to release later.
    private record Built(List<Decal> decals, List<Object> clientWounds) {}

    // Keyed by whatever owns the blood: the source entity id while a ragdoll is the visible body, then
    // the corpse UUID. transferTo re-keys at that handoff so the blood does not blink out.
    private static final Map<Object, Captured> CAPTURED = new ConcurrentHashMap<>();
    private static final Map<Object, Built> BUILT = new ConcurrentHashMap<>();
    // ClientWounds whose owning ragdoll is gone; drained + GL-released on the render thread.
    private static final List<Object> PENDING_RELEASE = new CopyOnWriteArrayList<>();

    public static void initialize() {
        if (!ModList.get().isLoaded("betterbloodoverlay_1_20_1")) {
            LOGGER.info("Better Blood Overlay not found - ragdolls will render without blood");
            return;
        }
        try {
            Class<?> woundStore = Class.forName(PKG + "WoundStore");
            Class<?> clientWound = Class.forName(PKG + "WoundStore$ClientWound");
            Class<?> woundRecord = Class.forName(PKG + "WoundRecord");
            Class<?> mobProfile = Class.forName(PKG + "MobWoundProfiles$MobProfile");
            Class<?> woundSite = Class.forName(PKG + "MobWoundProfiles$WoundSite");
            Class<?> texMgr = Class.forName(PKG + "WoundTextureManager");
            Class<?> renderer = Class.forName(PKG + "BloodOverlayRenderer");
            Class<?> visuals = Class.forName(PKG + "BloodVisuals");

            getWounds = woundStore.getMethod("getWounds", UUID.class);

            create = texMgr.getMethod("create", UUID.class, woundRecord, mobProfile);
            revealAll = texMgr.getMethod("revealAll", clientWound);
            release = texMgr.getMethod("release", clientWound);

            computeAlpha = renderer.getDeclaredMethod("computeAlpha", clientWound);
            computeAlpha.setAccessible(true);

            overlayBlood = visuals.getMethod("overlayBlood");

            cwData = clientWound.getDeclaredField("data");
            cwProfile = clientWound.getDeclaredField("profile");
            cwTexLocation = clientWound.getDeclaredField("texLocation");
            cwData.setAccessible(true);
            cwProfile.setAccessible(true);
            cwTexLocation.setAccessible(true);

            wrLimb = woundRecord.getMethod("limb");
            mpSites = mobProfile.getMethod("sites");
            mpTintR = mobProfile.getMethod("tintR");
            mpTintG = mobProfile.getMethod("tintG");
            mpTintB = mobProfile.getMethod("tintB");
            wsPart = woundSite.getMethod("part");

            available = true;
            LOGGER.info("Better Blood Overlay compatibility initialized successfully");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize Better Blood Overlay compatibility", t);
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    // Whether BBO draws blood on second-skin-layer parts (hat/jacket/sleeves/pants).
    public static boolean isOverlayBloodEnabled() {
        if (!available) return false;
        try {
            return (boolean) overlayBlood.invoke(null);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void capture(Object key, LivingEntity entity) {
        if (!available) return;
        try {
            Object list = getWounds.invoke(null, entity.getUUID());
            if (!(list instanceof List<?> wounds) || wounds.isEmpty()) return;

            List<Object> records = new ArrayList<>(wounds.size());
            Object profile = null;
            for (Object cw : wounds) {
                records.add(cwData.get(cw));
                if (profile == null) profile = cwProfile.get(cw);
            }
            if (profile != null) {
                CAPTURED.put(key, new Captured(records, profile));
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to capture blood for entity {}", entity.getId(), t);
        }
    }

    // True if we have captured or already-built blood for this body
    public static boolean hasBlood(Object key) {
        return available && (CAPTURED.containsKey(key) || BUILT.containsKey(key));
    }

    public static List<Decal> decalsForPart(Object key, String partName) {
        if (!available) return Collections.emptyList();
        Built built = BUILT.get(key);
        if (built == null) {
            built = build(key);
            if (built == null) return Collections.emptyList();
        }
        if (built.decals().isEmpty()) return Collections.emptyList();

        List<Decal> out = null;
        for (Decal d : built.decals()) {
            if (d.parts().contains(partName)) {
                if (out == null) out = new ArrayList<>(2);
                out.add(d);
            }
        }
        return out != null ? out : Collections.emptyList();
    }

    // The raw capture is kept after building: a corpse leaving render range frees its wound textures
    // and must rebuild them later, long after the entity it was captured from is gone.
    private static Built build(Object key) {
        Captured cap = CAPTURED.get(key);
        if (cap == null) return null;

        List<Decal> decals = new ArrayList<>(cap.records().size());
        List<Object> clientWounds = new ArrayList<>(cap.records().size());
        try {
            Object profile = cap.profile();
            float r = (float) mpTintR.invoke(profile);
            float g = (float) mpTintG.invoke(profile);
            float b = (float) mpTintB.invoke(profile);
            Map<?, ?> sites = (Map<?, ?>) mpSites.invoke(profile);

            // Fresh UUID so our rebuilt texture locations never collide with BBO's own live
            // ones (matters for players, whose real wounds may still be registered).
            UUID textureOwner = UUID.randomUUID();

            for (Object record : cap.records()) {
                Object limb = wrLimb.invoke(record);
                Set<String> parts = partsFor(sites.get(limb));
                if (parts.isEmpty()) continue;

                Object cw = create.invoke(null, textureOwner, record, profile);
                revealAll.invoke(null, cw);
                float alpha = (float) computeAlpha.invoke(null, cw);
                ResourceLocation tex = (ResourceLocation) cwTexLocation.get(cw);

                decals.add(new Decal(tex, parts, r, g, b, alpha));
                clientWounds.add(cw);
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to rebuild blood for {}", key, t);
            // Release anything we managed to allocate before the failure.
            for (Object cw : clientWounds) safeRelease(cw);
            Built empty = new Built(Collections.emptyList(), Collections.emptyList());
            BUILT.put(key, empty);
            return empty;
        }

        Built built = new Built(decals, clientWounds);
        BUILT.put(key, built);
        return built;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> partsFor(Object siteList) throws Exception {
        if (!(siteList instanceof List<?> list) || list.isEmpty()) return Collections.emptySet();
        Set<String> parts = new HashSet<>();
        for (Object site : list) {
            parts.add((String) wsPart.invoke(site));
        }
        return parts;
    }

    // Hand a body's blood to a new owner when a settled ragdoll is replaced by its corpse; the ragdoll
    // is destroyed right after and its evict() would otherwise take the blood with it.
    public static void transferTo(Object fromKey, Object toKey) {
        if (!available || fromKey.equals(toKey)) return;
        Captured cap = CAPTURED.remove(fromKey);
        if (cap != null) CAPTURED.put(toKey, cap);
        Built built = BUILT.remove(fromKey);
        if (built != null) BUILT.put(toKey, built);
    }

    // Free the GL wound textures but keep the capture so a body can rebuild on demand. Used when a
    // corpse unloads with its chunk, where holding textures for unbounded corpses would leak.
    public static void releaseTextures(Object key) {
        if (!available) return;
        Built built = BUILT.remove(key);
        if (built != null && !built.clientWounds().isEmpty()) {
            PENDING_RELEASE.addAll(built.clientWounds());
        }
    }

    public static void evict(Object key) {
        if (!available) return;
        CAPTURED.remove(key);
        releaseTextures(key);
    }

    // Drop everything on disconnect: both key spaces are only meaningful within one connection.
    public static void clearAll() {
        if (!available) return;
        CAPTURED.clear();
        for (Built built : BUILT.values()) PENDING_RELEASE.addAll(built.clientWounds());
        BUILT.clear();
    }

    // Release queued wound textures. Must be called on the render thread each frame
    public static void releasePending() {
        if (!available || PENDING_RELEASE.isEmpty()) return;
        List<Object> drained = new ArrayList<>(PENDING_RELEASE);
        PENDING_RELEASE.removeAll(drained);
        for (Object cw : drained) safeRelease(cw);
    }

    private static void safeRelease(Object clientWound) {
        try {
            release.invoke(null, clientWound);
        } catch (Throwable t) {
            LOGGER.debug("Failed to release blood texture", t);
        }
    }
}
