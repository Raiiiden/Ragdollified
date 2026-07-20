package com.raiiiden.ragdollified.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-persistent registry of {@link PendingCorpse} entries (deaths whose corpse hasn't
 * been spawned yet because the ragdoll is still settling). Stored on the overworld's data
 * storage; each entry records its own dimension so corpses are restored in the right level.
 * This is what makes the "spawn only after settle" flow crash-safe.
 */
public class PendingCorpseStore extends SavedData {

    private static final String NAME = "ragdollified_pending_corpses";

    public final Map<UUID, PendingCorpse> pending = new HashMap<>();
    /** Full loot snapshots for spawned corpses, including those in unloaded chunks. */
    public final Map<UUID, PendingCorpse> materialized = new HashMap<>();
    /** Most recent corpse id per owner; retained after looting so "lastdeath" can report gone. */
    public final Map<UUID, UUID> lastDeaths = new HashMap<>();
    /** Recovered/removed ids whose still-unloaded entity NBT must be discarded when loaded. */
    public final Set<UUID> removedCorpseIds = new HashSet<>();

    /**
     * Per-owner "give a Corpse Compass on next respawn" queue. Recorded at death and consumed
     * on respawn. Kept separate from {@link #pending} because a ragdoll can settle (removing the
     * pending) before the player actually clicks respawn, and we must still hand out the compass.
     * Each value carries the compass target: corpse id, position, dimension and owner name.
     */
    public final Map<UUID, CompoundTag> deathTargets = new HashMap<>();

    public PendingCorpseStore() {}

    public static PendingCorpseStore get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(PendingCorpseStore::load, PendingCorpseStore::new, NAME);
    }

    public static PendingCorpseStore load(CompoundTag tag) {
        PendingCorpseStore store = new PendingCorpseStore();
        ListTag list = tag.getList("Pending", 10); // 10 = CompoundTag
        for (int i = 0; i < list.size(); i++) {
            PendingCorpse p = PendingCorpse.load(list.getCompound(i));
            if (p.owner != null) store.pending.put(p.owner, p);
        }
        ListTag targets = tag.getList("DeathTargets", 10);
        for (int i = 0; i < targets.size(); i++) {
            CompoundTag t = targets.getCompound(i);
            if (t.hasUUID("Owner")) store.deathTargets.put(t.getUUID("Owner"), t);
        }
        ListTag materialized = tag.getList("Materialized", 10);
        for (int i = 0; i < materialized.size(); i++) {
            PendingCorpse p = PendingCorpse.load(materialized.getCompound(i));
            if (p.corpseId != null) store.materialized.put(p.corpseId, p);
        }
        ListTag lastDeaths = tag.getList("LastDeaths", 10);
        for (int i = 0; i < lastDeaths.size(); i++) {
            CompoundTag entry = lastDeaths.getCompound(i);
            if (entry.hasUUID("Owner") && entry.hasUUID("CorpseId")) {
                store.lastDeaths.put(entry.getUUID("Owner"), entry.getUUID("CorpseId"));
            }
        }
        ListTag removed = tag.getList("RemovedCorpseIds", 10);
        for (int i = 0; i < removed.size(); i++) {
            CompoundTag entry = removed.getCompound(i);
            if (entry.hasUUID("CorpseId")) store.removedCorpseIds.add(entry.getUUID("CorpseId"));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PendingCorpse p : pending.values()) list.add(p.save());
        tag.put("Pending", list);
        ListTag targets = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : deathTargets.entrySet()) {
            CompoundTag t = e.getValue().copy();
            t.putUUID("Owner", e.getKey());
            targets.add(t);
        }
        tag.put("DeathTargets", targets);
        ListTag materialized = new ListTag();
        for (PendingCorpse p : this.materialized.values()) materialized.add(p.save());
        tag.put("Materialized", materialized);
        ListTag lastDeaths = new ListTag();
        for (Map.Entry<UUID, UUID> e : this.lastDeaths.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Owner", e.getKey());
            entry.putUUID("CorpseId", e.getValue());
            lastDeaths.add(entry);
        }
        tag.put("LastDeaths", lastDeaths);
        ListTag removed = new ListTag();
        for (UUID id : removedCorpseIds) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("CorpseId", id);
            removed.add(entry);
        }
        tag.put("RemovedCorpseIds", removed);
        return tag;
    }
}
