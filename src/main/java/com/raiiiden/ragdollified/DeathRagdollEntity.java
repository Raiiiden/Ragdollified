package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.DeathRagdollEndPacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Vector3f;
import java.util.UUID;

public class DeathRagdollEntity extends Entity {

    // 1. DEFINE DATA ACCESSORS FOR PLAYER INFO
    private static final EntityDataAccessor<String> PLAYER_NAME =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> PLAYER_UUID =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.STRING);

    // 2. DEFINE DATA ACCESSORS FOR ARMOR (This fixes the rendering issue)
    private static final EntityDataAccessor<ItemStack> HEAD_SLOT =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> CHEST_SLOT =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> LEGS_SLOT =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> FEET_SLOT =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.ITEM_STACK);

    private DeathRagdollPhysics physics;
    private int lifetime;
    public int ticksExisted = 0;

    public DeathRagdollEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.lifetime = RagdollifiedConfig.getRagdollLifetime();
        Ragdollified.LOGGER.info("DeathRagdollEntity created with ID: " + this.getId());
    }

    public static DeathRagdollEntity createFromPlayer(Level level, net.minecraft.server.level.ServerPlayer player) {
        Vec3 spawnPos = player.position();

        DeathRagdollEntity ragdoll = new DeathRagdollEntity(ModEntities.DEATH_RAGDOLL.get(), level);
        ragdoll.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        ragdoll.setYRot(player.getYRot());
        ragdoll.setXRot(player.getXRot());

        // Set Player Data
        ragdoll.entityData.set(PLAYER_NAME, player.getName().getString());
        ragdoll.entityData.set(PLAYER_UUID, player.getUUID().toString());

        // Set synced armor data
        ragdoll.entityData.set(HEAD_SLOT, player.getItemBySlot(EquipmentSlot.HEAD).copy());
        ragdoll.entityData.set(CHEST_SLOT, player.getItemBySlot(EquipmentSlot.CHEST).copy());
        ragdoll.entityData.set(LEGS_SLOT, player.getItemBySlot(EquipmentSlot.LEGS).copy());
        ragdoll.entityData.set(FEET_SLOT, player.getItemBySlot(EquipmentSlot.FEET).copy());

        Ragdollified.LOGGER.info("Creating death ragdoll for player: " + player.getName().getString());

        if (!level.isClientSide) {
            JbulletWorld world = JbulletWorld.get((net.minecraft.server.level.ServerLevel) level);
            ragdoll.physics = new DeathRagdollPhysics(ragdoll, world);

            // Apply velocity after physics bodies are created
            Vec3 playerDelta = player.getDeltaMovement();
            Vector3f playerVel = new Vector3f(
                    (float) playerDelta.x * 8,
                    (float) playerDelta.y * 6,
                    (float) playerDelta.z * 8
            );

            // Check if velocity is very small (includes tiny knockback)
            boolean hasLowVelocity = playerDelta.lengthSqr() < 0.5;

            // Apply velocity based on damage source
            net.minecraft.world.damagesource.DamageSource lastDamage = player.getLastDamageSource();
            if (lastDamage != null) {
                String damageType = lastDamage.getMsgId();

                // Handle TACZ bullet damage
                if (damageType.contains("tacz.bullet") && hasLowVelocity) {
                    Vec3 damagePos = lastDamage.getSourcePosition();
                    if (damagePos != null) {
                        Vec3 direction = player.position().subtract(damagePos).normalize();

                        playerVel = new Vector3f(
                                (float) direction.x * 3.0f,
                                (float) direction.y * 4.0f + 2.0f,
                                (float) direction.z * 3.0f
                        );
                    } else {
                        Vec3 lookVec = player.getLookAngle();
                        playerVel = new Vector3f(
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
                        Vec3 direction = player.position().subtract(explosionCenter).normalize();
                        float distance = (float) player.position().distanceTo(explosionCenter);

                        float baseStrength = Math.min(player.getMaxHealth() / 10f, 5f);
                        float distanceFalloff = Math.max(0.5f, 1.0f - (distance / 10f));
                        float explosionStrength = baseStrength * distanceFalloff;

                        playerVel = new Vector3f(
                                (float) direction.x * 10.0f * explosionStrength,
                                (float) direction.y * 8.0f * explosionStrength + 3.0f,
                                (float) direction.z * 10.0f * explosionStrength
                        );
                    }
                }
            }

            ragdoll.physics.applyInitialVelocity(playerVel);
        }
        return ragdoll;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(PLAYER_NAME, "");
        this.entityData.define(PLAYER_UUID, "");

        // 4. REGISTER ARMOR DATA
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
            if (ticksExisted % 20 == 0) {
                // Optional: Reduced log spam
            }
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
        Ragdollified.LOGGER.info("Death ragdoll " + this.getId() + " being removed. Reason: " + reason);
        super.remove(reason);
        if (physics != null) physics.destroy();
        if (!level().isClientSide) ModNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new DeathRagdollEndPacket(getId()));
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 4096.0D;
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean isNoGravity() { return false; }

    public String getPlayerName() {
        return entityData.get(PLAYER_NAME);
    }

    public UUID getPlayerUUID() {
        try {
            return UUID.fromString(entityData.get(PLAYER_UUID));
        } catch (Exception e) {
            return null;
        }
    }

    public DeathRagdollPhysics getPhysics() {
        return physics;
    }

    // The renderer calls these on the client, and now they will have data!
    public ItemStack getHelmet() { return entityData.get(HEAD_SLOT); }
    public ItemStack getChestplate() { return entityData.get(CHEST_SLOT); }
    public ItemStack getLeggings() { return entityData.get(LEGS_SLOT); }
    public ItemStack getBoots() { return entityData.get(FEET_SLOT); }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("PlayerName")) entityData.set(PLAYER_NAME, tag.getString("PlayerName"));
        if (tag.contains("PlayerUUID")) entityData.set(PLAYER_UUID, tag.getString("PlayerUUID"));
        ticksExisted = tag.getInt("TicksExisted");
        lifetime = tag.contains("Lifetime") ? tag.getInt("Lifetime") : RagdollifiedConfig.getRagdollLifetime();

        // Load armor into synced data
        if (tag.contains("ArmorHead")) entityData.set(HEAD_SLOT, ItemStack.of(tag.getCompound("ArmorHead")));
        if (tag.contains("ArmorChest")) entityData.set(CHEST_SLOT, ItemStack.of(tag.getCompound("ArmorChest")));
        if (tag.contains("ArmorLegs")) entityData.set(LEGS_SLOT, ItemStack.of(tag.getCompound("ArmorLegs")));
        if (tag.contains("ArmorFeet")) entityData.set(FEET_SLOT, ItemStack.of(tag.getCompound("ArmorFeet")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("PlayerName", entityData.get(PLAYER_NAME));
        tag.putString("PlayerUUID", entityData.get(PLAYER_UUID));
        tag.putInt("TicksExisted", ticksExisted);
        tag.putInt("Lifetime", lifetime);

        // Save armor from synced data
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