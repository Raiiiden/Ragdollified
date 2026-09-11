package com.raiiiden.ragdollified.compat;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AccurateHitboxesCompat {

    private AccurateHitboxesCompat() {}

    private static final String MOD_ID = "accuratehitboxes";

    // Volatile because both logical sides reach this: the server resolves a shot on the server
    // thread and the client tracker does the same on its own.
    private static volatile boolean resolved;
    private static volatile boolean available;

    // SharedMemoryBridge.get(UUID): live boxes for an entity being rendered.
    private static Method sharedMemoryGet;
    // DynamicHitboxManager.get(UUID, UUID, long): boxes a client reported for an animated entity.
    private static Method dynamicGet;
    // BlueprintManager.serverBlueprints: boxes captured once per model/pose key.
    private static Map<?, ?> serverBlueprints;
    private static Method cacheKey;
    private static Method baseCacheKey;

    private static Method obbRaycast;
    private static Field obbLocalBox;
    private static Field obbTransform;
    private static Field obbOffset;

    public static boolean isAvailable() {
        resolve();
        return available;
    }

    // Where a shot met a limb: the entry point, and the centre of the limb.
    public static final class PartHit {
        // The surface point, which the impulse should pivot the body around.
        public final Vec3 entry;
        // The struck box's centre, the stabler thing to name the part from.
        public final Vec3 centre;

        PartHit(Vec3 entry, Vec3 centre) {
            this.entry = entry;
            this.centre = centre;
        }
    }

    // First per-part box the segment meets, or null if AccurateHitboxes is absent or nothing is hit.
    public static PartHit hitPart(Entity entity, Vec3 from, Vec3 to) {
        if (entity == null || from == null || to == null) return null;
        resolve();
        if (!available) return null;
        try {
            Object best = null;
            Vec3 bestEntry = null;
            double bestDistance = Double.MAX_VALUE;
            // The same three sources AccurateHitboxes itself consults, in the same order: live
            // boxes first, then what a client last reported, then the static blueprint.
            for (List<?> boxes : candidateBoxes(entity)) {
                for (Object box : boxes) {
                    Object result = obbRaycast.invoke(box, from, to);
                    if (!(result instanceof Optional<?> hit) || hit.isEmpty()) continue;
                    if (!(hit.get() instanceof Vec3 point)) continue;
                    double distance = point.distanceToSqr(from);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = box;
                        bestEntry = point;
                    }
                }
                if (best != null) return new PartHit(bestEntry, centreOf(best));
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("reading hitboxes", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<?>> candidateBoxes(Entity entity) throws ReflectiveOperationException {
        UUID id = entity.getUUID();
        List<List<?>> sources = new java.util.ArrayList<>(3);
        addIfPresent(sources, (List<?>) sharedMemoryGet.invoke(null, id));
        addIfPresent(sources, (List<?>) dynamicGet.invoke(
                null, id, id, entity.level().getGameTime()));

        Object keyed = serverBlueprints.get(cacheKey.invoke(null, entity));
        if (keyed == null) keyed = serverBlueprints.get(baseCacheKey.invoke(null, entity));
        if (keyed instanceof List<?> list) addIfPresent(sources, list);
        return sources;
    }

    private static void addIfPresent(List<List<?>> sources, List<?> boxes) {
        if (boxes != null && !boxes.isEmpty()) sources.add(boxes);
    }

    // A box's centre in world space, inverting AccurateHitboxes' inverseTransform * (world - offset).
    private static Vec3 centreOf(Object box) throws ReflectiveOperationException {
        AABB local = (AABB) obbLocalBox.get(box);
        Matrix4f transform = (Matrix4f) obbTransform.get(box);
        Vec3 offset = (Vec3) obbOffset.get(box);
        Vector3f centre = new Vector3f(
                (float) local.getCenter().x, (float) local.getCenter().y, (float) local.getCenter().z);
        transform.transformPosition(centre);
        return new Vec3(centre.x + offset.x, centre.y + offset.y, centre.z + offset.z);
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModList.get().isLoaded(MOD_ID)) return;
        try {
            Class<?> bridge = Class.forName("net.devra.accuratehitboxes.util.SharedMemoryBridge");
            sharedMemoryGet = bridge.getMethod("get", UUID.class);

            Class<?> dynamic = Class.forName(
                    "net.devra.accuratehitboxes.network.DynamicHitboxManager");
            dynamicGet = dynamic.getMethod("get", UUID.class, UUID.class, long.class);

            Class<?> blueprints = Class.forName(
                    "net.devra.accuratehitboxes.network.BlueprintManager");
            Object map = blueprints.getField("serverBlueprints").get(null);
            serverBlueprints = map instanceof Map<?, ?> m ? m : Collections.emptyMap();

            Class<?> pose = Class.forName("net.devra.accuratehitboxes.util.BlueprintPose");
            cacheKey = pose.getMethod("getCacheKey", Entity.class);
            baseCacheKey = pose.getMethod("getBaseCacheKey", Entity.class);

            Class<?> obb = Class.forName("net.devra.accuratehitboxes.util.OrientedBoundingBox");
            obbRaycast = obb.getMethod("raycast", Vec3.class, Vec3.class);
            obbLocalBox = obb.getField("localBox");
            obbTransform = obb.getField("transform");
            obbOffset = obb.getField("offset");

            available = true;
            Ragdollified.LOGGER.info(
                    "AccurateHitboxes found: shots will be attributed using its per-part hitboxes");
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("looking up its classes", e);
        }
    }

    private static void disable(String phase, Throwable cause) {
        available = false;
        Ragdollified.LOGGER.warn(
                "AccurateHitboxes is loaded but this build cannot use it ({}); falling back to the"
                        + " built-in hit layout", phase, cause);
    }
}
