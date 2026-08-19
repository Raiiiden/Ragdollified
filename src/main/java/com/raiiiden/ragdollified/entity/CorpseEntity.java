package com.raiiiden.ragdollified.entity;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.menu.CorpseMenu;
import com.raiiiden.ragdollified.server.PendingCorpse;
import com.raiiiden.ragdollified.server.PendingCorpseStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.UUID;

// Server-authoritative lootable corpse. The inventory is server-side only, while the cosmetic
// snapshot — skin, worn armor, frozen pose — syncs through RENDER_DATA so every client agrees.
public class CorpseEntity extends Entity {

    // Vanilla portion: 36 main/hotbar + 4 armor + 1 offhand. Curio slots (if any) follow.
    public static final int VANILLA_SLOTS = 41;

    // Cosmetic render state, synced to clients. Items themselves are NOT synced.
    private static final EntityDataAccessor<CompoundTag> RENDER_DATA =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.COMPOUND_TAG);

    // Container is VANILLA_SLOTS + curioSlotIds.size(); reassigned in initCorpse / on load.
    private SimpleContainer inventory = new SimpleContainer(VANILLA_SLOTS);
    private final java.util.List<String> curioSlotIds = new java.util.ArrayList<>();
    private int storedXp = 0;
    private UUID ownerUUID;
    // Stable handle assigned at death (see PendingCorpse#corpseId). Used by the Corpse Compass
    // and the retrieve command to refer to this exact corpse. Synced to clients via RENDER_DATA.
    private UUID corpseId;
    private String ownerName = "";
    // The client-side ragdoll entity id this corpse replaces, synced through RENDER_DATA so handoff
    // removes exactly the body that settled into this corpse and never another death's.
    private int ragdollEntityId = -1;

    // Cached, parsed pose for the renderer (client). Rebuilt lazily when RENDER_DATA changes.
    private RagdollTransform[] cachedPose = null;
    private CompoundTag cachedPoseSource = null;
    // Same treatment for the worn-curios snapshot.
    private java.util.List<CuriosCompat.WornCurio> cachedCurios = null;
    private CompoundTag cachedCuriosSource = null;
    private boolean suppressPersistentSync = false;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        this.noPhysics = false;
        // Real (vanilla) entity gravity/collision — NOT the JBullet ragdoll physics. The body
        // falls when its support is removed and floats up in water; see tickPhysics().
        this.setNoGravity(false);
    }

    // Server-side construction

    // Fill in loot and identity at death, server-side. vanillaItems is the 41 player slots,
    // index-aligned; curioStacks and curioIds are parallel and empty without Curios.
    public void initCorpse(UUID owner, UUID corpseId, String name, int ragdollEntityId,
                           java.util.List<ItemStack> vanillaItems,
                           java.util.List<ItemStack> curioStacks, java.util.List<String> curioIds,
                           int xp, ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
        this.ownerUUID = owner;
        this.corpseId = corpseId;
        this.ownerName = name != null ? name : "";
        this.ragdollEntityId = ragdollEntityId;
        this.storedXp = xp;
        this.curioSlotIds.clear();
        this.curioSlotIds.addAll(curioIds);

        this.inventory = new SimpleContainer(VANILLA_SLOTS + curioStacks.size());
        for (int i = 0; i < VANILLA_SLOTS && i < vanillaItems.size(); i++) {
            ItemStack s = vanillaItems.get(i);
            inventory.setItem(i, s == null ? ItemStack.EMPTY : s);
        }
        for (int i = 0; i < curioStacks.size(); i++) {
            ItemStack s = curioStacks.get(i);
            inventory.setItem(VANILLA_SLOTS + i, s == null ? ItemStack.EMPTY : s);
        }
        attachInventoryListener();
        rebuildRenderData(false, null, helmet, chest, legs, boots);
    }

    // Apply the settled ragdoll pose, server-side, with transforms relative to this entity's
    // position. Marks the corpse posed so clients start drawing the frozen body.
    public void applyPose(RagdollTransform[] relativeTransforms) {
        CompoundTag data = getEntityData().get(RENDER_DATA).copy();
        ListTag pose = new ListTag();
        for (int i = 0; i < RagdollTransform.MAX_PARTS; i++) {
            RagdollTransform t = (relativeTransforms != null && i < relativeTransforms.length)
                    ? relativeTransforms[i] : null;
            CompoundTag c = new CompoundTag();
            if (t != null) {
                c.putFloat("px", t.position.x); c.putFloat("py", t.position.y); c.putFloat("pz", t.position.z);
                c.putFloat("qx", t.rotation.x); c.putFloat("qy", t.rotation.y);
                c.putFloat("qz", t.rotation.z); c.putFloat("qw", t.rotation.w);
            }
            pose.add(c);
        }
        data.put("Pose", pose);
        data.putBoolean("Posed", true);
        getEntityData().set(RENDER_DATA, data);
    }

    public void addStoredXp(int amount) {
        if (amount > 0) {
            this.storedXp += amount;
            syncPersistentRecord();
        }
    }

    // Settle-timeout fallback: mark posed with no captured pose so the renderer draws a flat body.
    public void markPosedFlat() {
        CompoundTag data = getRenderData().copy();
        data.putBoolean("Posed", true);
        data.remove("Pose");
        getEntityData().set(RENDER_DATA, data);
    }

    private void rebuildRenderData(boolean posed, @Nullable ListTag pose,
                                   ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
        CompoundTag data = new CompoundTag();
        if (ownerUUID != null) data.putUUID("Owner", ownerUUID);
        if (corpseId != null) data.putUUID("CorpseId", corpseId);
        data.putString("Name", ownerName);
        data.putInt("RagdollId", ragdollEntityId);
        data.putBoolean("Posed", posed);
        if (pose != null) data.put("Pose", pose);
        data.put("Helmet", saveStack(helmet));
        data.put("Chest", saveStack(chest));
        data.put("Legs", saveStack(legs));
        data.put("Boots", saveStack(boots));
        // Captured curios, drawn like armor and likewise fixed at death rather than following looting.
        // Only curios the drop rules handed over are here; anything the player kept was never ours.
        ListTag curios = new ListTag();
        for (int i = 0; i < curioSlotIds.size(); i++) {
            ItemStack stack = inventory.getItem(VANILLA_SLOTS + i);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", curioSlotIds.get(i));
            entry.put("Item", saveStack(stack));
            curios.add(entry);
        }
        data.put("Curios", curios);
        getEntityData().set(RENDER_DATA, data);
    }

    private static CompoundTag saveStack(ItemStack stack) {
        return (stack == null ? ItemStack.EMPTY : stack).save(new CompoundTag());
    }

    // Client-side render accessors

    public CompoundTag getRenderData() { return getEntityData().get(RENDER_DATA); }

    public boolean isPosed() { return getRenderData().getBoolean("Posed"); }

    @Nullable
    public UUID getOwnerUUID() {
        CompoundTag d = getRenderData();
        return d.hasUUID("Owner") ? d.getUUID("Owner") : null;
    }

    // Stable death handle shared with the Corpse Compass and the retrieve command.
    @Nullable
    public UUID getCorpseId() {
        CompoundTag d = getRenderData();
        return d.hasUUID("CorpseId") ? d.getUUID("CorpseId") : corpseId;
    }

    // The physics-ragdoll entity id this corpse replaces, or -1 if unknown.
    public int getRagdollEntityId() {
        CompoundTag d = getRenderData();
        return d.contains("RagdollId") ? d.getInt("RagdollId") : -1;
    }

    public ItemStack getArmor(String key) {
        CompoundTag d = getRenderData();
        return d.contains(key) ? ItemStack.of(d.getCompound(key)) : ItemStack.EMPTY;
    }

    // The curios this corpse wears, parsed once per render-data change since the renderer asks every
    // frame. Slot index and flags are synthesised: each captured stack was visible and non-cosmetic.
    public java.util.List<CuriosCompat.WornCurio> getWornCurios() {
        CompoundTag d = getRenderData();
        if (cachedCurios != null && d.equals(cachedCuriosSource)) return cachedCurios;

        java.util.List<CuriosCompat.WornCurio> out = new java.util.ArrayList<>();
        ListTag list = d.getList("Curios", 10); // 10 = CompoundTag
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack stack = ItemStack.of(entry.getCompound("Item"));
            if (stack.isEmpty()) continue;
            out.add(new CuriosCompat.WornCurio(entry.getString("Id"), 0, false, true, stack));
        }
        cachedCurios = java.util.List.copyOf(out);
        cachedCuriosSource = d.copy();
        return cachedCurios;
    }

    // Parsed pose transforms (entity-relative) for the renderer, or null if not posed.
    @Nullable
    public RagdollTransform[] getCorpsePose() {
        CompoundTag d = getRenderData();
        if (!d.getBoolean("Posed") || !d.contains("Pose")) return null;
        if (cachedPose != null && d.equals(cachedPoseSource)) return cachedPose;
        ListTag pose = d.getList("Pose", 10); // 10 = CompoundTag
        RagdollTransform[] out = new RagdollTransform[RagdollTransform.MAX_PARTS];
        for (int i = 0; i < RagdollTransform.MAX_PARTS && i < pose.size(); i++) {
            CompoundTag c = pose.getCompound(i);
            if (c.isEmpty()) continue;
            out[i] = new RagdollTransform(i,
                    new Vector3f(c.getFloat("px"), c.getFloat("py"), c.getFloat("pz")),
                    new Quat4f(c.getFloat("qx"), c.getFloat("qy"), c.getFloat("qz"), c.getFloat("qw")));
        }
        cachedPose = out;
        cachedPoseSource = d.copy();
        return out;
    }

    public SimpleContainer getInventory() { return inventory; }

    public java.util.List<String> getCurioSlotIds() { return curioSlotIds; }

    public int getCurioCount() { return curioSlotIds.size(); }

    // Entity overrides

    @Override
    protected void defineSynchedData() {
        getEntityData().define(RENDER_DATA, new CompoundTag());
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            NetworkHooks.openScreen(sp,
                    new SimpleMenuProvider(
                            (id, playerInv, p) -> new CorpseMenu(id, playerInv, inventory, curioSlotIds),
                            getDisplayName()),
                    buf -> {
                        buf.writeVarInt(curioSlotIds.size());
                        for (String slotId : curioSlotIds) buf.writeUtf(slotId);
                    });
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public Component getDisplayName() {
        String name = getRenderData().getString("Name");
        return Component.literal((name == null || name.isEmpty()) ? "Corpse" : name + "'s Corpse");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;

        if (corpseId != null && level() instanceof ServerLevel server) {
            PendingCorpseStore store = PendingCorpseStore.get(server.getServer().overworld());
            if (store.removedCorpseIds.contains(corpseId)) {
                discard();
                return;
            }
            // Backfill the index for corpses created by older versions.
            if (!store.materialized.containsKey(corpseId)) syncPersistentRecord();
        }

        tickPhysics();

        // Expiry — drop remaining loot + release stored XP, then discard.
        if (tickCount >= RagdollifiedConfig.getCorpseExpiryTicks()) {
            dropLoot();
            releaseXpAndDiscard();
            return;
        }

        // Fully looted — release any stored XP and remove the body.
        if (inventory.isEmpty()) {
            releaseXpAndDiscard();
        }
    }

    // Ordinary server entity physics rather than jBullet: gravity and move() so a corpse rests, falls
    // and floats. Residual drift is zeroed so a settled body stops sending position updates.
    private void tickPhysics() {
        Vec3 m = getDeltaMovement();

        if (isInWater()) {
            // Buoyancy: rise while submerged, settle once the surface is reached. Heavy water drag.
            double lift = isUnderWater() ? 0.03 : -0.004;
            m = new Vec3(m.x, Math.min(m.y + lift, 0.06), m.z).multiply(0.9, 0.9, 0.9);
        } else if (!isNoGravity()) {
            m = m.add(0.0, -0.04, 0.0);
        }

        setDeltaMovement(m);
        move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());

        m = getDeltaMovement();
        if (onGround()) {
            m = new Vec3(m.x * 0.6, Math.max(m.y, 0.0), m.z * 0.6); // ground friction; don't burrow
        }
        m = m.multiply(0.98, 0.98, 0.98);
        // Snap negligible velocity to zero so a resting corpse is truly static.
        double eps = 1.0e-3;
        m = new Vec3(Math.abs(m.x) < eps ? 0.0 : m.x,
                     Math.abs(m.y) < eps ? 0.0 : m.y,
                     Math.abs(m.z) < eps ? 0.0 : m.z);
        setDeltaMovement(m);
    }

    private void dropLoot() {
        if (level().isClientSide) return;
        Containers.dropContents(level(), this, inventory);
    }

    // Move every stored item and any XP into the target's inventory, dropping overflow at their
    // feet, then remove this corpse. Backs the OP retrieve command.
    public void retrieveInto(ServerPlayer target) {
        if (level().isClientSide) return;
        suppressPersistentSync = true;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            ItemStack give = s.copy();
            if (!target.getInventory().add(give)) target.drop(give, false);
            inventory.setItem(i, ItemStack.EMPTY);
        }
        if (storedXp > 0) {
            target.giveExperiencePoints(storedXp);
            storedXp = 0;
        }
        markPersistentRemoved();
        discard();
    }

    private void releaseXpAndDiscard() {
        if (storedXp > 0 && level() instanceof ServerLevel server) {
            ExperienceOrb.award(server, position(), storedXp);
            storedXp = 0;
        }
        markPersistentRemoved();
        discard();
    }

    // Interaction / physics behavior

    @Override
    public boolean isPickable() { return !isRemoved(); }

    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        // The rendered body can extend past the small interaction box (outstretched limbs);
        // inflate the cull box so it isn't dropped when the torso center leaves the frustum.
        return getBoundingBox().inflate(1.5);
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    protected boolean canRide(Entity vehicle) { return false; }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        // Corpses are looted or expire; they can't be destroyed by damage. Still allow
        // out-of-world (void) removal so a corpse in the void doesn't strand loot forever.
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            dropLoot();
            releaseXpAndDiscard();
            return true;
        }
        return false;
    }

    // Persistence

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) ownerUUID = tag.getUUID("Owner");
        if (tag.hasUUID("CorpseId")) corpseId = tag.getUUID("CorpseId");
        ownerName = tag.getString("Name");
        storedXp = tag.getInt("StoredXp");
        // Curio slot ids first — they determine the container size before items load.
        curioSlotIds.clear();
        ListTag ids = tag.getList("CurioIds", 8); // 8 = StringTag
        for (int i = 0; i < ids.size(); i++) curioSlotIds.add(ids.getString(i));
        inventory = new SimpleContainer(VANILLA_SLOTS + curioSlotIds.size());
        inventory.fromTag(tag.getList("Items", 10));
        attachInventoryListener();
        if (tag.contains("RenderData")) {
            getEntityData().set(RENDER_DATA, tag.getCompound("RenderData"));
        }
        // A corpse persisted while still unposed has lost its settle bookkeeping and would stay
        // invisible forever, so it is posed flat on load and always renders.
        if (!isPosed()) markPosedFlat();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
        if (corpseId != null) tag.putUUID("CorpseId", corpseId);
        tag.putString("Name", ownerName);
        tag.putInt("StoredXp", storedXp);
        tag.put("Items", inventory.createTag());
        ListTag ids = new ListTag();
        for (String slotId : curioSlotIds) ids.add(net.minecraft.nbt.StringTag.valueOf(slotId));
        tag.put("CurioIds", ids);
        tag.put("RenderData", getRenderData().copy());
    }

    private void attachInventoryListener() {
        inventory.addListener(container -> syncPersistentRecord());
    }

    private void syncPersistentRecord() {
        if (suppressPersistentSync || corpseId == null || ownerUUID == null
                || !(level() instanceof ServerLevel server)) return;
        PendingCorpseStore store = PendingCorpseStore.get(server.getServer().overworld());
        if (store.removedCorpseIds.contains(corpseId)) return;

        PendingCorpse record = new PendingCorpse();
        record.owner = ownerUUID;
        record.corpseId = corpseId;
        record.name = ownerName;
        record.dimension = level().dimension();
        record.deathPos = position();
        record.deathEntityId = getRagdollEntityId();
        record.storedXp = storedXp;
        for (int i = 0; i < VANILLA_SLOTS; i++) record.items.add(inventory.getItem(i).copy());
        for (int i = VANILLA_SLOTS; i < inventory.getContainerSize(); i++) {
            record.curioStacks.add(inventory.getItem(i).copy());
        }
        record.curioIds.addAll(curioSlotIds);
        record.helmet = getArmor("Helmet").copy();
        record.chest = getArmor("Chest").copy();
        record.legs = getArmor("Legs").copy();
        record.boots = getArmor("Boots").copy();
        store.materialized.put(corpseId, record);
        store.lastDeaths.putIfAbsent(ownerUUID, corpseId);
        store.setDirty();
    }

    private void markPersistentRemoved() {
        if (corpseId == null || !(level() instanceof ServerLevel server)) return;
        PendingCorpseStore store = PendingCorpseStore.get(server.getServer().overworld());
        store.materialized.remove(corpseId);
        store.removedCorpseIds.add(corpseId);
        store.setDirty();
    }
}
