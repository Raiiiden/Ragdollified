package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

// Part lookup by mesh child name, not Java field name: fields are reobfuscated in a production jar,
// while mesh names live in the LayerDefinition and read the same in both environments.
@OnlyIn(Dist.CLIENT)
public final class ModelPartTree {

    // ModelPart.children is private, so ObfuscationReflectionHelper maps the SRG name back in dev and
    // one constant covers both runtimes, as the renderer already does for the cubes list.
    private static final String CHILDREN_SRG = "f_104213_";

    // CEM packs can nest submodels arbitrarily deep, and the .jem is untrusted data. Bound the
    // walk so a malformed or cyclic tree cannot hang the render thread.
    private static final int MAX_DEPTH = 16;

    private static boolean warnedChildren = false;

    private ModelPartTree() {
    }

    public static Map<String, ModelPart> childrenOf(ModelPart part) {
        try {
            Map<String, ModelPart> children = net.minecraftforge.fml.util.ObfuscationReflectionHelper
                    .getPrivateValue(ModelPart.class, part, CHILDREN_SRG);
            return children == null ? Collections.emptyMap() : children;
        } catch (Exception e) {
            if (!warnedChildren) {
                warnedChildren = true;
                Ragdollified.LOGGER.warn("Could not access ModelPart children via reflection — "
                        + "part lookup by mesh name is disabled for this session", e);
            }
            return Collections.emptyMap();
        }
    }

    // Every descendant of root, with the mesh name it was registered under. The root itself is
    // not visited because it has no name of its own.
    public static void forEachNamed(ModelPart root, BiConsumer<String, ModelPart> visitor) {
        if (root == null) return;
        walk(root, visitor, 0);
    }

    private static void walk(ModelPart part, BiConsumer<String, ModelPart> visitor, int depth) {
        if (depth >= MAX_DEPTH) return;
        for (Map.Entry<String, ModelPart> entry : childrenOf(part).entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
            walk(entry.getValue(), visitor, depth + 1);
        }
    }

    // Mesh names use snake_case ("left_arm") where Java fields use camelCase ("leftArm").
    // Folding both to the same key lets one name set serve both lookups.
    public static String normalize(String name) {
        return name.toLowerCase().replace("_", "");
    }
}
