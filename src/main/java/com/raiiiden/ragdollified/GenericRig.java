package com.raiiiden.ragdollified;

import java.util.List;

// A ragdoll skeleton measured off a mob's own model, for mobs with no hand-authored rig.
// Plain floats in blocks, factory local frame (X/Y negated vs model pixels); see GenericRigExtractor.
public final class GenericRig {

    // One box. parentIndex is always below this part's index (-1 for the torso); o is the offset from
    // the torso box centre, h the half extents, j the joint anchor (the part's pivot, not box centre).
    public record Part(String name, int parentIndex,
                       float ox, float oy, float oz,
                       float hx, float hy, float hz,
                       float jx, float jy, float jz) {

        public float volume() {
            return hx * hy * hz;
        }
    }

    public final List<Part> parts;

    // Torso box height above the entity's feet, in blocks, computed from the model.
    public final float spawnYOffset;

    public GenericRig(List<Part> parts, float spawnYOffset) {
        this.parts = List.copyOf(parts);
        this.spawnYOffset = spawnYOffset;
    }

    public int size() {
        return parts.size();
    }
}
