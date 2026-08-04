package com.raiiiden.ragdollified.server;

import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.RagdollTransform;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Loot captured at a player's death, held until their ragdoll settles and a CorpseEntity spawns
// at the rest position. Persisted through PendingCorpseStore so a crash in the pre-settle window
// cannot lose the inventory: on the next start any leftover is spawned flat at the death spot.
public class PendingCorpse {

    public UUID owner;
    public String name = "";
    // Stable handle for this death, generated at capture and threaded through to the CorpseEntity
    // and the Corpse Compass, so the compass and retrieve command can name exactly this corpse
    // without depending on the entity's later-assigned UUID.
    public UUID corpseId;
    public ResourceKey<Level> dimension;
    public Vec3 deathPos = Vec3.ZERO;
    // The dying player's entity id at death, which equals the client ragdoll's originalEntityId
    // because the spawn packet keys off entity.getId(). Carrying it to clients makes each corpse
    // hand off from exactly the ragdoll it replaced, so a stale corpse cannot cull a newer one.
    public int deathEntityId = -1;
    public final List<ItemStack> items = new ArrayList<>();       // 41 vanilla slots (index-aligned)
    public final List<ItemStack> curioStacks = new ArrayList<>(); // parallel with curioIds
    public final List<String> curioIds = new ArrayList<>();
    public ItemStack helmet = ItemStack.EMPTY;
    public ItemStack chest = ItemStack.EMPTY;
    public ItemStack legs = ItemStack.EMPTY;
    public ItemStack boots = ItemStack.EMPTY;
    public int storedXp = 0;
    // Latest server-issued impulse sequence incorporated by an acceptable settle report.
    public int impulseRevision = 0;

    // Server tick by which, if no settle has arrived, the corpse is spawned flat. In-memory only.
    public transient long deadlineTick;
    // Validated settle candidate, briefly delayed so in-flight pushes can invalidate it.
    public transient Vec3 settleOrigin;
    public transient RagdollTransform[] settleTransforms;
    public transient int settleRevision = -1;
    public transient long settleReadyTick;

    public void invalidateSettleCandidate() {
        settleOrigin = null;
        settleTransforms = null;
        settleRevision = -1;
        settleReadyTick = 0L;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (owner != null) t.putUUID("Owner", owner);
        if (corpseId != null) t.putUUID("CorpseId", corpseId);
        t.putString("Name", name);
        if (dimension != null) t.putString("Dim", dimension.location().toString());
        t.putDouble("Dx", deathPos.x);
        t.putDouble("Dy", deathPos.y);
        t.putDouble("Dz", deathPos.z);
        t.putInt("StoredXp", storedXp);
        t.putInt("DeathEntityId", deathEntityId);
        t.putInt("ImpulseRevision", impulseRevision);
        t.put("Items", saveStacks(items));
        t.put("CurioStacks", saveStacks(curioStacks));
        ListTag ids = new ListTag();
        for (String id : curioIds) ids.add(StringTag.valueOf(id));
        t.put("CurioIds", ids);
        t.put("Helmet", helmet.save(new CompoundTag()));
        t.put("Chest", chest.save(new CompoundTag()));
        t.put("Legs", legs.save(new CompoundTag()));
        t.put("Boots", boots.save(new CompoundTag()));
        return t;
    }

    public static PendingCorpse load(CompoundTag t) {
        PendingCorpse p = new PendingCorpse();
        if (t.hasUUID("Owner")) p.owner = t.getUUID("Owner");
        if (t.hasUUID("CorpseId")) p.corpseId = t.getUUID("CorpseId");
        p.name = t.getString("Name");
        if (t.contains("Dim")) {
            p.dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("Dim")));
        }
        p.deathPos = new Vec3(t.getDouble("Dx"), t.getDouble("Dy"), t.getDouble("Dz"));
        p.storedXp = t.getInt("StoredXp");
        p.deathEntityId = t.contains("DeathEntityId") ? t.getInt("DeathEntityId") : -1;
        p.impulseRevision = t.getInt("ImpulseRevision");
        ListTag ids = t.getList("CurioIds", 8); // 8 = StringTag
        for (int i = 0; i < ids.size(); i++) p.curioIds.add(ids.getString(i));
        loadStacks(t.getList("Items", 10), p.items, CorpseEntity.VANILLA_SLOTS);
        loadStacks(t.getList("CurioStacks", 10), p.curioStacks, p.curioIds.size());
        p.helmet = ItemStack.of(t.getCompound("Helmet"));
        p.chest = ItemStack.of(t.getCompound("Chest"));
        p.legs = ItemStack.of(t.getCompound("Legs"));
        p.boots = ItemStack.of(t.getCompound("Boots"));
        return p;
    }

    public PendingCorpse copy() {
        return load(save());
    }

    private static ListTag saveStacks(List<ItemStack> list) {
        ListTag t = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            CompoundTag c = s.save(new CompoundTag());
            c.putInt("Slot", i);
            t.add(c);
        }
        return t;
    }

    private static void loadStacks(ListTag t, List<ItemStack> out, int size) {
        out.clear();
        for (int i = 0; i < size; i++) out.add(ItemStack.EMPTY);
        for (int i = 0; i < t.size(); i++) {
            CompoundTag c = t.getCompound(i);
            int slot = c.getInt("Slot");
            if (slot >= 0 && slot < size) out.set(slot, ItemStack.of(c));
        }
    }
}
