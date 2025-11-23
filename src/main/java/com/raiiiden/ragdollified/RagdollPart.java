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

    public Vector3f getHalfExtents() {
        switch (this) {
            case HEAD: return new Vector3f(0.125f, 0.125f, 0.125f);
            case TORSO: return new Vector3f(0.25f, 0.35f, 0.15f);
            case LEFT_ARM:
            case RIGHT_ARM: return new Vector3f(0.10f, 0.30f, 0.10f);
            case LEFT_LEG:
            case RIGHT_LEG: return new Vector3f(0.10f, 0.40f, 0.10f);
            default: return new Vector3f(0.1f, 0.1f, 0.1f);
        }
    }
}
