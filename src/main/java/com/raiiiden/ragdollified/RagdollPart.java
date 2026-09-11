package com.raiiiden.ragdollified;

import org.joml.Vector3f;

public enum RagdollPart {
    TORSO(0),
    HEAD(1),
    LEFT_LEG(2),
    RIGHT_LEG(3),
    LEFT_ARM(4),
    RIGHT_ARM(5);

    public final int index;
    RagdollPart(int i){ this.index = i; }
    public static RagdollPart byIndex(int i){
        for (RagdollPart p : values()) if (p.index == i) return p;
        return null;
    }

    // Amputation support. The torso is the root every other part is jointed to, so it is the one
    // part that can never come off: severing it would leave the skeleton with no body to hang from.
    public boolean isSeverable() { return this != TORSO; }

    // Parts are carried across the wire and stored per entity as a bitmask keyed by index, which is
    // stable for the six-part humanoid rig these helpers are for.
    public int bit() { return 1 << index; }

    public static int maskOf(RagdollPart... parts) {
        int mask = 0;
        if (parts != null) for (RagdollPart part : parts) if (part != null) mask |= part.bit();
        return mask;
    }

    public static boolean maskContains(int mask, RagdollPart part) {
        return part != null && (mask & part.bit()) != 0;
    }

    public static java.util.EnumSet<RagdollPart> fromMask(int mask) {
        java.util.EnumSet<RagdollPart> parts = java.util.EnumSet.noneOf(RagdollPart.class);
        for (RagdollPart part : values()) if ((mask & part.bit()) != 0) parts.add(part);
        return parts;
    }

    public static int maskOf(java.util.Collection<RagdollPart> parts) {
        int mask = 0;
        if (parts != null) for (RagdollPart part : parts) if (part != null) mask |= part.bit();
        return mask;
    }

    // Every part of the humanoid rig that can be taken off, as a mask.
    public static final int ALL_SEVERABLE_MASK =
            HEAD.bit() | LEFT_ARM.bit() | RIGHT_ARM.bit() | LEFT_LEG.bit() | RIGHT_LEG.bit();

    // Vanilla humanoid cubes in blocks; must agree with RagdollBodyFactory and LimbAnchors.
    public Vector3f getHalfExtents() {
        switch (this) {
            case HEAD: return new Vector3f(0.25f, 0.25f, 0.25f);
            case TORSO: return new Vector3f(0.25f, 0.375f, 0.15f);
            case LEFT_ARM:
            case RIGHT_ARM:
            case LEFT_LEG:
            case RIGHT_LEG: return new Vector3f(0.125f, 0.375f, 0.125f);
            default: return new Vector3f(0.1f, 0.1f, 0.1f);
        }
    }
}
