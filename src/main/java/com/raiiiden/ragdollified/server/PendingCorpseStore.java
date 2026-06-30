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
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PendingCorpse p : pending.values()) list.add(p.save());
        tag.put("Pending", list);
        return tag;
    }
}
