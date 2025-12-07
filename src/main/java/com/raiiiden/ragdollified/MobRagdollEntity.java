package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.DeathRagdollEndPacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Vector3f;

public class MobRagdollEntity extends Entity {

    private static final EntityDataAccessor<String> MOB_TYPE =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Float> MOB_SCALE =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Integer> ORIGINAL_MOB_ID =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.INT);

    private MobRagdollPhysics physics;
    private int lifetime;
    public int ticksExisted = 0;

    // Store NBT data for mob-specific rendering (skin texture, etc.)
    private CompoundTag mobData = new CompoundTag();

    // Client-side cached texture (looked up once, then stored)
    private ResourceLocation cachedTexture = null;

    private static final EntityDataAccessor<ItemStack> HEAD_SLOT =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> CHEST_SLOT =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> LEGS_SLOT =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> FEET_SLOT =
            SynchedEntityData.defineId(MobRagdollEntity.class, EntityDataSerializers.ITEM_STACK);



    public MobRagdollEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.lifetime = RagdollifiedConfig.getRagdollLifetime();
    }

    public static MobRagdollEntity createFromMob(Level level, LivingEntity mob) {
        Vec3 spawnPos = mob.position();
        MobRagdollEntity ragdoll = new MobRagdollEntity(ModEntities.MOB_RAGDOLL.get(), level);

        // STORE ORIGINAL MOB ID for client-side texture lookup
        ragdoll.entityData.set(ORIGINAL_MOB_ID, mob.getId());

        ragdoll.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        ragdoll.setYRot(mob.getYRot());
        ragdoll.setXRot(mob.getXRot());

        // Store mob type (for model selection)
        ragdoll.entityData.set(MOB_TYPE, EntityType.getKey(mob.getType()).toString());

        // Store scale
        ragdoll.entityData.set(MOB_SCALE, mob.getBbHeight() / 1.8f);

        // Store armor
        ragdoll.entityData.set(HEAD_SLOT, mob.getItemBySlot(EquipmentSlot.HEAD).copy());
        ragdoll.entityData.set(CHEST_SLOT, mob.getItemBySlot(EquipmentSlot.CHEST).copy());
        ragdoll.entityData.set(LEGS_SLOT, mob.getItemBySlot(EquipmentSlot.LEGS).copy());
        ragdoll.entityData.set(FEET_SLOT, mob.getItemBySlot(EquipmentSlot.FEET).copy());

        // Save mob data for texture/variant
        mob.saveWithoutId(ragdoll.mobData);

        if (!level.isClientSide) {
            JbulletWorld world = JbulletWorld.get((net.minecraft.server.level.ServerLevel) level);
            ragdoll.physics = new MobRagdollPhysics(ragdoll, world);

            // Apply velocity after physics bodies are created
            Vector3f mobVel = new Vector3f(
                    (float) mob.getDeltaMovement().x * 8,
                    (float) mob.getDeltaMovement().y * 6,
                    (float) mob.getDeltaMovement().z * 8
            );

            ragdoll.physics.applyInitialVelocity(mobVel);
        }
        return ragdoll;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(MOB_TYPE, "minecraft:zombie");
        this.entityData.define(MOB_SCALE, 1.0f);
        this.entityData.define(ORIGINAL_MOB_ID, -1);

        // Register armor slots
        this.entityData.define(HEAD_SLOT, ItemStack.EMPTY);
        this.entityData.define(CHEST_SLOT, ItemStack.EMPTY);
        this.entityData.define(LEGS_SLOT, ItemStack.EMPTY);
        this.entityData.define(FEET_SLOT, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            ticksExisted++;
            if (ticksExisted >= lifetime) {
                discard();
                return;
            }

            if (physics != null) {
                physics.update();
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (physics != null) physics.destroy();
        if (!level().isClientSide) {
            ModNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new DeathRagdollEndPacket(getId()));
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 4096.0D;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isNoGravity() {
        return false;
    }

    public String getMobType() {
        return entityData.get(MOB_TYPE);
    }

    public float getMobScale() {
        return entityData.get(MOB_SCALE);
    }

    public int getOriginalMobId() {
        return entityData.get(ORIGINAL_MOB_ID);
    }

    public CompoundTag getMobData() {
        return mobData;
    }

    public MobRagdollPhysics getPhysics() {
        return physics;
    }

    public ResourceLocation getCachedTexture() {
        return cachedTexture;
    }

    public void setCachedTexture(ResourceLocation texture) {
        this.cachedTexture = texture;
    }

    public ItemStack getHelmet() { return entityData.get(HEAD_SLOT); }
    public ItemStack getChestplate() { return entityData.get(CHEST_SLOT); }
    public ItemStack getLeggings() { return entityData.get(LEGS_SLOT); }
    public ItemStack getBoots() { return entityData.get(FEET_SLOT); }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("MobType")) entityData.set(MOB_TYPE, tag.getString("MobType"));
        if (tag.contains("MobScale")) entityData.set(MOB_SCALE, tag.getFloat("MobScale"));
        if (tag.contains("OriginalMobId")) entityData.set(ORIGINAL_MOB_ID, tag.getInt("OriginalMobId"));
        ticksExisted = tag.getInt("TicksExisted");
        lifetime = tag.contains("Lifetime") ? tag.getInt("Lifetime") : RagdollifiedConfig.getRagdollLifetime();
        if (tag.contains("MobData")) {
            mobData = tag.getCompound("MobData");
        }

        // Load armor
        if (tag.contains("ArmorHead")) entityData.set(HEAD_SLOT, ItemStack.of(tag.getCompound("ArmorHead")));
        if (tag.contains("ArmorChest")) entityData.set(CHEST_SLOT, ItemStack.of(tag.getCompound("ArmorChest")));
        if (tag.contains("ArmorLegs")) entityData.set(LEGS_SLOT, ItemStack.of(tag.getCompound("ArmorLegs")));
        if (tag.contains("ArmorFeet")) entityData.set(FEET_SLOT, ItemStack.of(tag.getCompound("ArmorFeet")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("MobType", entityData.get(MOB_TYPE));
        tag.putFloat("MobScale", entityData.get(MOB_SCALE));
        tag.putInt("OriginalMobId", entityData.get(ORIGINAL_MOB_ID));
        tag.putInt("TicksExisted", ticksExisted);
        tag.putInt("Lifetime", lifetime);
        tag.put("MobData", mobData);

        // Save armor
        tag.put("ArmorHead", entityData.get(HEAD_SLOT).save(new CompoundTag()));
        tag.put("ArmorChest", entityData.get(CHEST_SLOT).save(new CompoundTag()));
        tag.put("ArmorLegs", entityData.get(LEGS_SLOT).save(new CompoundTag()));
        tag.put("ArmorFeet", entityData.get(FEET_SLOT).save(new CompoundTag()));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}