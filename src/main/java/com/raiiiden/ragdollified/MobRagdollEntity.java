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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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

    private MobRagdollPhysics physics;
    private int lifetime;
    public int ticksExisted = 0;

    // Store NBT data for mob-specific rendering (skin texture, etc.)
    private CompoundTag mobData = new CompoundTag();

    public MobRagdollEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.lifetime = RagdollifiedConfig.getRagdollLifetime();
    }

    public static MobRagdollEntity createFromMob(Level level, LivingEntity mob) {
        Vec3 safe = findSafeSpawn(level, mob.position());
        MobRagdollEntity ragdoll = new MobRagdollEntity(ModEntities.MOB_RAGDOLL.get(), level);
        ragdoll.setPos(safe.x, safe.y, safe.z);
        ragdoll.setYRot(mob.getYRot());
        ragdoll.setXRot(mob.getXRot());

        // Store mob type (for model selection)
        ragdoll.entityData.set(MOB_TYPE, EntityType.getKey(mob.getType()).toString());

        // Store scale
        ragdoll.entityData.set(MOB_SCALE, mob.getBbHeight() / 1.8f); // Normalize to player height

        // Save mob data for texture/variant
        mob.saveWithoutId(ragdoll.mobData);

        if (!level.isClientSide) {
            JbulletWorld world = JbulletWorld.get((net.minecraft.server.level.ServerLevel) level);
            ragdoll.physics = new MobRagdollPhysics(ragdoll, world);

            Vector3f mobVel = new Vector3f(
                    (float) mob.getDeltaMovement().x,
                    (float) mob.getDeltaMovement().y,
                    (float) mob.getDeltaMovement().z);
            mobVel.scale(1f);

            float speed = mobVel.length();
            if (speed > 8f) mobVel.scale(8f / speed);

            ragdoll.physics.applyInitialVelocity(mobVel);
        }
        return ragdoll;
    }
    private static Vec3 findSafeSpawn(Level level, Vec3 center) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int y = 0; y < 8; y++) {
            m.set(center.x, center.y + y, center.z);
            BlockState state = level.getBlockState(m);
            BlockState above = level.getBlockState(m.above());
            BlockState above2 = level.getBlockState(m.above(2));

            if (state.isAir() && above.isAir() && above2.isAir()) {
                return new Vec3(center.x, center.y + y + 0.5, center.z);
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    m.set(center.x + dx, center.y + dy, center.z + dz);
                    if (level.getBlockState(m).isAir() &&
                            level.getBlockState(m.above()).isAir() &&
                            level.getBlockState(m.above(2)).isAir()) {
                        return new Vec3(m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5);
                    }
                }
            }
        }

        return center.add(0, 4, 0);
    }


    @Override
    protected void defineSynchedData() {
        this.entityData.define(MOB_TYPE, "minecraft:zombie");
        this.entityData.define(MOB_SCALE, 1.0f);
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
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean isNoGravity() { return false; }

    public String getMobType() {
        return entityData.get(MOB_TYPE);
    }

    public float getMobScale() {
        return entityData.get(MOB_SCALE);
    }

    public CompoundTag getMobData() {
        return mobData;
    }

    public MobRagdollPhysics getPhysics() {
        return physics;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("MobType")) entityData.set(MOB_TYPE, tag.getString("MobType"));
        if (tag.contains("MobScale")) entityData.set(MOB_SCALE, tag.getFloat("MobScale"));
        ticksExisted = tag.getInt("TicksExisted");
        lifetime = tag.contains("Lifetime") ? tag.getInt("Lifetime") : RagdollifiedConfig.getRagdollLifetime();
        if (tag.contains("MobData")) {
            mobData = tag.getCompound("MobData");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("MobType", entityData.get(MOB_TYPE));
        tag.putFloat("MobScale", entityData.get(MOB_SCALE));
        tag.putInt("TicksExisted", ticksExisted);
        tag.putInt("Lifetime", lifetime);
        tag.put("MobData", mobData);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}