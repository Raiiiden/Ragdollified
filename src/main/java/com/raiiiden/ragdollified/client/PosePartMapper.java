package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.client.model.BeeModel;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

// Maps live ModelParts to ragdoll bodies per model instance, so swapped-out parts map as they are.
// Lookups: humanoid fields, then mesh child names, then SRG field names.
@OnlyIn(Dist.CLIENT)
public final class PosePartMapper {

    // Mesh names per rig, indexed by RagdollPart.index. Matched normalized ("left_arm" and "leftArm"
    // fold together), first hit wins. Front legs and wings take the arm slots, as the bodies do.
    private static final String[][] HUMANOID_NAMES = {
            {"body"}, {"head"}, {"left_leg"}, {"right_leg"}, {"left_arm"}, {"right_arm"}
    };
    private static final String[][] LEGGED_NAMES = {
            {"body"}, {"head", "head_parts"}, {"left_hind_leg"}, {"right_hind_leg"},
            {"left_front_leg"}, {"right_front_leg"}
    };
    private static final String[][] CHICKEN_NAMES = {
            {"body"}, {"head"}, {"left_leg"}, {"right_leg"}, {"left_wing"}, {"right_wing"}
    };
    private static final String[][] BAT_NAMES = {
            {"body"}, {"head"}, {"left_leg"}, {"right_leg"}, {"left_wing"}, {"right_wing"}
    };
    private static final String[][] BEE_NAMES = {
            {"bone"}, {"head"}, {"back_legs"}, {"middle_legs"}, {"left_wing"}, {"right_wing"}
    };

    // Vanilla rigs that keep their parts in private fields with no root() to walk. The SRG names are
    // what a production jar carries; ObfuscationReflectionHelper maps them back in dev.
    private static final String[] QUADRUPED_SRG = {
            "f_103493_", "f_103492_", "f_170853_", "f_170852_", "f_170855_", "f_170854_"
    };
    private static final String[] CHICKEN_SRG = {
            "f_102382_", "f_102381_", "f_170486_", "f_170485_", "f_170488_", "f_170487_"
    };
    private static final String[] HORSE_SRG = {
            "f_102751_", "f_102752_", "f_170665_", "f_170664_", "f_170642_", "f_170666_"
    };
    // The bee hangs everything off one "bone" part, so only that root needs reflecting.
    private static final String BEE_ROOT_SRG = "f_102206_";

    // Cached per model instance since the reflective walk is costly; weak keys so reloads free models.
    private static final Map<EntityModel<?>, Map<ModelPart, RagdollPart>> CACHE = new WeakHashMap<>();
    // A miss is worth remembering too; an unmappable rig would otherwise be re-walked every frame.
    private static final Map<ModelPart, RagdollPart> NO_MAPPING = Map.of();

    private static boolean warnedReflection = false;

    private PosePartMapper() {
    }

    // Null when this rig has no mapping worth capturing; the caller then leaves the pose alone
    // rather than storing a half-filled one.
    public static Map<ModelPart, RagdollPart> map(EntityModel<?> model, MobModelHelper.ModelType modelType) {
        if (model == null) return null;

        Map<ModelPart, RagdollPart> cached = CACHE.get(model);
        if (cached != null && isStillValid(model, cached)) {
            return cached.isEmpty() ? null : cached;
        }

        Map<ModelPart, RagdollPart> resolved = resolve(model, modelType);
        CACHE.put(model, resolved == null ? NO_MAPPING : resolved);
        return resolved;
    }

    // A model whose parts were replaced leaves a stale mapping; checking one common part catches it.
    private static boolean isStillValid(EntityModel<?> model, Map<ModelPart, RagdollPart> mapping) {
        if (mapping.isEmpty()) return true;
        if (model instanceof HumanoidModel<?> humanoid) {
            return mapping.get(humanoid.body) == RagdollPart.TORSO;
        }
        return true;
    }

    private static Map<ModelPart, RagdollPart> resolve(EntityModel<?> model, MobModelHelper.ModelType modelType) {
        String[][] names = namesFor(modelType);
        if (names == null) return null;

        Map<ModelPart, RagdollPart> mapping = new IdentityHashMap<>();

        // Fields first for humanoids: every humanoid, vanilla or modded, exposes the same six, while
        // the mesh names of a heavily reworked model may not survive.
        if (model instanceof HumanoidModel<?> humanoid) {
            put(mapping, humanoid.body, RagdollPart.TORSO);
            put(mapping, humanoid.head, RagdollPart.HEAD);
            put(mapping, humanoid.leftLeg, RagdollPart.LEFT_LEG);
            put(mapping, humanoid.rightLeg, RagdollPart.RIGHT_LEG);
            put(mapping, humanoid.leftArm, RagdollPart.LEFT_ARM);
            put(mapping, humanoid.rightArm, RagdollPart.RIGHT_ARM);
        }

        Map<String, ModelPart> byName = flatten(model);
        for (int i = 0; i < names.length; i++) {
            RagdollPart part = RagdollPart.byIndex(i);
            if (part == null || mapping.containsValue(part)) continue;
            for (String candidate : names[i]) {
                ModelPart found = byName.get(ModelPartTree.normalize(candidate));
                if (found != null) {
                    put(mapping, found, part);
                    break;
                }
            }
        }

        addReflectedSlots(model, mapping);

        return mapping.isEmpty() ? null : mapping;
    }

    // Whether captured matrices can place bodies directly: only square-baked rigs (humanoid, creeper).
    // Other rigs keep their authored anatomy and take only the animated angles.
    public static boolean supportsTransforms(MobModelHelper.ModelType modelType) {
        return modelType == MobModelHelper.ModelType.CREEPER
                || MobModelHelper.isHumanoidModelType(modelType);
    }

    // Whether this rig is still vanilla geometry. Pack-driven models (FA, EMF) move pivots off-rig,
    // so only their rotations are used. Tested by ModelPart class, not mod id.
    public static boolean hasVanillaGeometry(EntityModel<?> model, Map<ModelPart, RagdollPart> mapping) {
        if (mapping == null) return true;
        for (ModelPart part : mapping.keySet()) {
            if (part.getClass() != ModelPart.class) return false;
        }
        return model == null || model.getClass().getName().startsWith("net.minecraft.");
    }

    private static void put(Map<ModelPart, RagdollPart> mapping, ModelPart part, RagdollPart slot) {
        // One ModelPart can answer to two slots (a bee's leg groups are one part each side); the
        // first slot wins so a single part never reports two different bodies' transforms.
        if (part != null && slot != null && !mapping.containsKey(part) && !mapping.containsValue(slot)) {
            mapping.put(part, slot);
        }
    }

    private static String[][] namesFor(MobModelHelper.ModelType modelType) {
        if (modelType == null) return null;
        switch (modelType) {
            case CHICKEN: return CHICKEN_NAMES;
            case BAT: return BAT_NAMES;
            case BEE: return BEE_NAMES;
            case CREEPER:
            case EQUINE:
            case QUADRUPED:
            case WOLF:
            case FOX:
            case PANDA:
            case GOAT:
            case POLAR_BEAR:
                return LEGGED_NAMES;
            case UNSUPPORTED: return null;
            default:
                // Everything else the factory builds on the humanoid rig, players included.
                return MobModelHelper.isHumanoidModelType(modelType) ? HUMANOID_NAMES : null;
        }
    }

    // Every named descendant of the model root, normalized. Shallower names win: a top-level "head"
    // is the one to capture, not a "head" nested inside some hat submodel.
    private static Map<String, ModelPart> flatten(EntityModel<?> model) {
        Map<String, ModelPart> out = new LinkedHashMap<>();
        ModelPart root = rootOf(model);
        if (root != null) {
            ModelPartTree.forEachNamed(root, (name, part) ->
                    out.putIfAbsent(ModelPartTree.normalize(name), part));
        }
        return out;
    }

    private static ModelPart rootOf(EntityModel<?> model) {
        if (model instanceof HierarchicalModel<?> hierarchical) return hierarchical.root();
        // AgeableListModel has no root, but the bee keeps one part that owns every other.
        if (model instanceof BeeModel<?>) return reflectPart(BeeModel.class, model, BEE_ROOT_SRG);
        return null;
    }

    // The vanilla list models keep their parts private with no root to walk, so any slot the name
    // pass could not fill is read straight off the declaring class.
    private static void addReflectedSlots(EntityModel<?> model, Map<ModelPart, RagdollPart> mapping) {
        if (model instanceof QuadrupedModel<?>) {
            fillFromSrg(QuadrupedModel.class, model, QUADRUPED_SRG, mapping);
        } else if (model instanceof ChickenModel<?>) {
            fillFromSrg(ChickenModel.class, model, CHICKEN_SRG, mapping);
        } else if (model instanceof HorseModel<?>) {
            fillFromSrg(HorseModel.class, model, HORSE_SRG, mapping);
        }
    }

    private static void fillFromSrg(Class<?> declaring, EntityModel<?> model, String[] srgNames,
                                    Map<ModelPart, RagdollPart> mapping) {
        for (int i = 0; i < srgNames.length; i++) {
            RagdollPart part = RagdollPart.byIndex(i);
            if (part == null || mapping.containsValue(part)) continue;
            put(mapping, reflectPart(declaring, model, srgNames[i]), part);
        }
    }

    private static ModelPart reflectPart(Class<?> declaring, Object instance, String srgName) {
        try {
            Field field = ObfuscationReflectionHelper.findField(declaring, srgName);
            Object value = field.get(instance);
            return value instanceof ModelPart part ? part : null;
        } catch (Exception e) {
            if (!warnedReflection) {
                warnedReflection = true;
                Ragdollified.LOGGER.warn("Could not read model part {} off {} - those rigs fall back "
                        + "to their authored spawn pose", srgName, declaring.getSimpleName(), e);
            }
            return null;
        }
    }
}
