package com.raiiiden.ragdollified.api;

// Client-local options applied when spawning a ragdoll through the public API.
public final class SpawnOptions {
    public static final SpawnOptions DEFAULT = builder().build();

    private final boolean hideEntity;
    private final boolean persistent;
    private final RagdollSpawnTransform spawnTransform;

    private SpawnOptions(boolean hideEntity, boolean persistent, RagdollSpawnTransform spawnTransform) {
        this.hideEntity = hideEntity;
        this.persistent = persistent;
        this.spawnTransform = spawnTransform;
    }

    public boolean hideEntity() { return hideEntity; }
    public boolean persistent() { return persistent; }
    // Optional server-authoritative transform. Null uses the loaded entity's current transform.
    public RagdollSpawnTransform spawnTransform() { return spawnTransform; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean hideEntity;
        private boolean persistent;
        private RagdollSpawnTransform spawnTransform;

        public Builder hideEntity(boolean value) { hideEntity = value; return this; }
        public Builder persistent(boolean value) { persistent = value; return this; }
        public Builder spawnTransform(RagdollSpawnTransform value) { spawnTransform = value; return this; }
        public SpawnOptions build() { return new SpawnOptions(hideEntity, persistent, spawnTransform); }
    }
}
