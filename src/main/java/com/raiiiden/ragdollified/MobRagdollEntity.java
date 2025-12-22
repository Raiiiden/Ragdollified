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
import net.minecraft.world.damagesource.DamageSource;
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

import javax.annotation.Nullable;
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

    // Store captured pose data
    private MobPoseCapture.MobPose capturedPose = null;

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

    public static MobRagdollEntity createFromMob(Level level, LivingEntity mob, @Nullable DamageSource lastDamage, float damageAmount) {
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

        // Try to get captured pose from client (if available)
        if (level.isClientSide) {
            ragdoll.capturedPose = MobPoseCapture.getAndRemovePose(mob.getId());
        }

        if (!level.isClientSide) {
            JbulletWorld world = JbulletWorld.get((net.minecraft.server.level.ServerLevel) level);
            ragdoll.physics = new MobRagdollPhysics(ragdoll, world);

            // Apply velocity after physics bodies are created
            Vec3 mobDelta = mob.getDeltaMovement();
            Vector3f mobVel = new Vector3f(
                    (float) mobDelta.x * 8,
                    (float) mobDelta.y * 6,
                    (float) mobDelta.z * 8
            );

            // Check if velocity is very small (includes tiny knockback)
            boolean hasLowVelocity = mobDelta.lengthSqr() < 0.5;

            // Apply velocity based on damage source
            if (lastDamage != null) {
                String damageType = lastDamage.getMsgId();

                // Handle TACZ bullet damage
                if (damageType.contains("tacz.bullet") && hasLowVelocity) {
                    Vec3 damagePos = lastDamage.getSourcePosition();
                    if (damagePos != null) {
                        Vec3 direction = mob.position().subtract(damagePos).normalize();

                        mobVel = new Vector3f(
                                (float) direction.x * 3.0f,
                                (float) direction.y * 4.0f + 2.0f,
                                (float) direction.z * 3.0f
                        );
                    } else {
                        Vec3 lookVec = mob.getLookAngle();
                        mobVel = new Vector3f(
                                (float) lookVec.x * 5.0f,
                                2.0f,
                                (float) lookVec.z * 5.0f
                        );
                    }
                }

                // Handle explosion damage
                if (damageType.contains("explosion")) {
                    Vec3 explosionCenter = lastDamage.getSourcePosition();
                    if (explosionCenter != null) {
                        Vec3 direction = mob.position().subtract(explosionCenter).normalize();
                        float distance = (float) mob.position().distanceTo(explosionCenter);

                        float baseStrength = Math.min(damageAmount / 10f, 5f);
                        float distanceFalloff = Math.max(0.5f, 1.0f - (distance / 10f));
                        float explosionStrength = baseStrength * distanceFalloff;

                        mobVel = new Vector3f(
                                (float) direction.x * 10.0f * explosionStrength,
                                (float) direction.y * 8.0f * explosionStrength + 3.0f,
                                (float) direction.z * 10.0f * explosionStrength
                        );
                    }
                }
            }

            ragdoll.physics.applyInitialVelocity(mobVel);
        }
        return ragdoll;
    }

    public MobPoseCapture.MobPose getCapturedPose() {
        return capturedPose;
    }

    public void setCapturedPose(MobPoseCapture.MobPose pose) {
        this.capturedPose = pose;
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