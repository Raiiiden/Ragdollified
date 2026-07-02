package com.raiiiden.ragdollified.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
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
        return tag;
    }
}
