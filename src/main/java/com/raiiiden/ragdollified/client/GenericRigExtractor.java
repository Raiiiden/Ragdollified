package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.GenericRig;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Measures a ragdoll skeleton off a mob's model: each cube-bearing part becomes a box hanging off
// its nearest cube-bearing ancestor. Uses the initial pose and ignores rotation.
@OnlyIn(Dist.CLIENT)
public final class GenericRigExtractor {

    private GenericRigExtractor() {
    }

    // A measured rig plus the parts it came from, index-aligned so body and mesh always agree.
    public record Rig(GenericRig rig, List<ModelPart> renderParts) {
    }

    // Keyed by entity type rather than model class: two types can share a model class and still be
    // drawn at different sizes, and the type is what the ragdoll has to hand at spawn anyway.
    private static final Map<EntityType<?>, Rig> CACHE = new ConcurrentHashMap<>();
    // A type whose model could not be measured. Held separately so the walk is not retried for every
    // death of a mob that has already been ruled out.
    private static final Set<EntityType<?>> FAILED = ConcurrentHashMap.newKeySet();

    // Minimum half extent in model pixels; half a pixel keeps 1-pixel cubes. Real filtering is in select().
    private static final float MIN_HALF_EXTENT = 0.5f;
    // Submodels can nest arbitrarily deep and a resource pack authored one is untrusted data.
    private static final int MAX_DEPTH = 16;

    // The rig for this entity's model, measured once per entity type. Null if it has none.
    @Nullable
    public static Rig get(LivingEntity entity) {
        if (entity == null) return null;
        EntityType<?> type = entity.getType();
        Rig cached = CACHE.get(type);
        if (cached != null) return cached;
        if (FAILED.contains(type)) return null;

        Rig built;
        try {
            built = measure(entity);
        } catch (Exception e) {
            Ragdollified.LOGGER.warn("Could not measure a generic ragdoll rig for {}", type, e);
            built = null;
        }
        if (built == null) {
            FAILED.add(type);
            return null;
        }
        CACHE.put(type, built);
        Ragdollified.LOGGER.info("Measured a {}-part generic ragdoll rig for {}",
                built.rig().size(), type);
        return built;
    }

    // True if a rig can be measured for this entity (turns UNSUPPORTED into GENERIC).
    public static boolean canExtract(LivingEntity entity) {
        return get(entity) != null;
    }

    // The rig already cached for this entity type, or null. Never measures,
    // since the entity may be gone by draw time.
    @Nullable
    public static Rig byMobType(String mobType) {
        if (mobType == null) return null;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(mobType);
        if (id == null) return null;
        EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id);
        return type == null ? null : CACHE.get(type);
    }

    // Models are reloaded on a resource reload, which invalidates every ModelPart held here.
    public static void clear() {
        CACHE.clear();
        FAILED.clear();
    }

    @Nullable
    private static Rig measure(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) return null;
        EntityModel<?> model = living.getModel();
        if (model == null) return null;

        List<Candidate> candidates = new ArrayList<>();
        Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Root root : roots(model)) {
            // The root's own pivot is part of the chain: a model that hangs everything off a root
            // offset to the feet would otherwise measure every part 24 pixels out.
            PartPose pose = root.part().getInitialPose();
            walk(root.name(), root.part(), null, pose.x, pose.y, pose.z, candidates, visited, 0);
        }
        if (candidates.isEmpty()) return null;

        List<Candidate> kept = select(candidates);
        if (kept.size() < 2) return null;

        return assemble(kept);
    }

    // Depth-first, accumulating pivots; cubeless container parts become no body but pass children up.
    private static void walk(String name, ModelPart part, @Nullable Candidate parent,
                             float px, float py, float pz,
                             List<Candidate> out, Set<ModelPart> visited, int depth) {
        if (depth >= MAX_DEPTH || !visited.add(part)) return;

        Candidate self = parent;
        if (part.visible) {
            org.joml.Vector3f half = ClientRagdollRenderer.cubeBoxHalfExtents(part);
            if (half.x >= MIN_HALF_EXTENT || half.y >= MIN_HALF_EXTENT || half.z >= MIN_HALF_EXTENT) {
                org.joml.Vector3f centre = ClientRagdollRenderer.cubeBoxCenter(part);
                self = new Candidate(name, part, parent,
                        px + centre.x, py + centre.y, pz + centre.z,
                        half.x, half.y, half.z, px, py, pz);
                out.add(self);
            }
        }

        for (Map.Entry<String, ModelPart> child : ModelPartTree.childrenOf(part).entrySet()) {
            PartPose pose = child.getValue().getInitialPose();
            walk(child.getKey(), child.getValue(), self,
                    px + pose.x, py + pose.y, pz + pose.z, out, visited, depth + 1);
        }
    }

    // Keep the torso and the largest parts under it, up to what a ragdoll can carry. Biggest-first
    // because a limb the player can see move is worth a solver slot and a 1-pixel horn is not.
    private static List<Candidate> select(List<Candidate> candidates) {
        Candidate torso = candidates.stream()
                .max(Comparator.comparingDouble(Candidate::volume))
                .orElseThrow();

        List<Candidate> rest = new ArrayList<>(candidates);
        rest.remove(torso);
        rest.sort(Comparator.comparingDouble(Candidate::volume).reversed());

        List<Candidate> kept = new ArrayList<>();
        kept.add(torso);
        for (Candidate c : rest) {
            if (kept.size() >= RagdollTransform.MAX_PARTS) break;
            kept.add(c);
        }
        // Back into tree order, torso first. A part must be built after the one it hangs from, and
        // the ancestor walk below relies on a parent always having the lower index.
        List<Candidate> ordered = new ArrayList<>();
        ordered.add(torso);
        for (Candidate c : candidates) {
            if (c != torso && kept.contains(c)) ordered.add(c);
        }
        return ordered;
    }

    private static Rig assemble(List<Candidate> kept) {
        Candidate torso = kept.get(0);
        Map<Candidate, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < kept.size(); i++) index.put(kept.get(i), i);

        List<GenericRig.Part> parts = new ArrayList<>(kept.size());
        List<ModelPart> renderParts = new ArrayList<>(kept.size());

        for (int i = 0; i < kept.size(); i++) {
            Candidate c = kept.get(i);
            int parentIndex = -1;
            if (i > 0) {
                // Nearest ancestor that survived selection; the torso catches everything else, so a
                // part whose whole chain was dropped still hangs off the body rather than floating.
                Integer found = null;
                for (Candidate a = c.parent; a != null; a = a.parent) {
                    Integer ai = index.get(a);
                    if (ai != null && ai < i) { found = ai; break; }
                }
                parentIndex = found != null ? found : 0;
            }

            parts.add(new GenericRig.Part(c.name, parentIndex,
                    toLocalX(c.cx, torso.cx), toLocalY(c.cy, torso.cy), toLocalZ(c.cz, torso.cz),
                    c.hx / 16f, c.hy / 16f, c.hz / 16f,
                    toLocalX(c.px, torso.cx), toLocalY(c.py, torso.cy), toLocalZ(c.pz, torso.cz)));
            renderParts.add(c.part);
        }

        return new Rig(new GenericRig(parts, spawnYOffset(torso.cy)), List.copyOf(renderParts));
    }

    // Model pixels to the factory frame: flip X and Y (the renderer's scale(-1,-1,1)), 16 pixels per block.
    private static float toLocalX(float pixels, float originPixels) { return -(pixels - originPixels) / 16f; }

    private static float toLocalY(float pixels, float originPixels) { return -(pixels - originPixels) / 16f; }

    private static float toLocalZ(float pixels, float originPixels) { return (pixels - originPixels) / 16f; }

    // Where the torso rides above the feet. The 1.501 is LivingEntityRenderer's own translate, which
    // is what puts model y=24 on the ground; measured down from there, a torso at model y lands here.
    private static float spawnYOffset(float torsoPixelY) {
        return 1.501f - torsoPixelY / 16f;
    }

    private record Root(String name, ModelPart part) {
    }

    // Where a model keeps its parts. HierarchicalModel says so outright; everything else (the
    // Humanoid and Ageable trees, and most modded models) holds them in fields.
    private static List<Root> roots(EntityModel<?> model) {
        List<Root> roots = new ArrayList<>();
        if (model instanceof HierarchicalModel<?> hierarchical) {
            roots.add(new Root("root", hierarchical.root()));
            return roots;
        }
        Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> cls = model.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != ModelPart.class) continue;
                try {
                    f.setAccessible(true);
                    ModelPart part = (ModelPart) f.get(model);
                    if (part != null && seen.add(part)) roots.add(new Root(f.getName(), part));
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // A field the module system will not open is one part missing, not a failed rig.
                }
            }
        }
        // Fields hold both the roots and the parts hanging off them, so a naive list double-counts
        // every child. Anything reachable from another field is dropped; the walk finds it anyway.
        Set<ModelPart> descendants = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Root root : roots) collectDescendants(root.part(), descendants, 0);
        roots.removeIf(root -> descendants.contains(root.part()));
        return roots;
    }

    private static void collectDescendants(ModelPart part, Set<ModelPart> out, int depth) {
        if (depth >= MAX_DEPTH) return;
        for (ModelPart child : ModelPartTree.childrenOf(part).values()) {
            if (out.add(child)) collectDescendants(child, out, depth + 1);
        }
    }

    // One part on the way to becoming a body, still in model pixels.
    private static final class Candidate {
        final String name;
        final ModelPart part;
        final Candidate parent;
        final float cx, cy, cz;
        final float hx, hy, hz;
        final float px, py, pz;

        Candidate(String name, ModelPart part, Candidate parent,
                  float cx, float cy, float cz, float hx, float hy, float hz,
                  float px, float py, float pz) {
            this.name = name;
            this.part = part;
            this.parent = parent;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.px = px; this.py = py; this.pz = pz;
        }

        float volume() {
            return hx * hy * hz;
        }
    }
}
