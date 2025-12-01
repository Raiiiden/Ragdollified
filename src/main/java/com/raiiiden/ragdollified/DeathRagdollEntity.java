package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.DeathRagdollEndPacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

import javax.vecmath.Vector3f;
import java.util.UUID;

public class DeathRagdollEntity extends Entity {

    private static final EntityDataAccessor<String> PLAYER_NAME =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> PLAYER_UUID =
            SynchedEntityData.defineId(DeathRagdollEntity.class, EntityDataSerializers.STRING);

    // Store armor as ItemStacks
    private ItemStack helmet = ItemStack.EMPTY;
    private ItemStack chestplate = ItemStack.EMPTY;
    private ItemStack leggings = ItemStack.EMPTY;
    private ItemStack boots = ItemStack.EMPTY;

    private DeathRagdollPhysics physics;
    private int lifetime;
    public int ticksExisted = 0;

    public DeathRagdollEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.lifetime = RagdollifiedConfig.getRagdollLifetime();
        Ragdollified.LOGGER.info("DeathRagdollEntity created with ID: " + this.getId());
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

    public static DeathRagdollEntity createFromPlayer(Level level, net.minecraft.server.level.ServerPlayer player) {
        Vec3 safe = findSafeSpawn(level, player.position());
        DeathRagdollEntity ragdoll = new DeathRagdollEntity(ModEntities.DEATH_RAGDOLL.get(), level);
        ragdoll.setPos(safe.x, safe.y, safe.z);
        ragdoll.setYRot(player.getYRot());
        ragdoll.setXRot(player.getXRot());
        ragdoll.entityData.set(PLAYER_NAME, player.getName().getString());
        ragdoll.entityData.set(PLAYER_UUID, player.getUUID().toString());

        // Copy armor from player
        ragdoll.helmet = player.getItemBySlot(EquipmentSlot.HEAD).copy();
        ragdoll.chestplate = player.getItemBySlot(EquipmentSlot.CHEST).copy();
        ragdoll.leggings = player.getItemBySlot(EquipmentSlot.LEGS).copy();
        ragdoll.boots = player.getItemBySlot(EquipmentSlot.FEET).copy();

        Ragdollified.LOGGER.info("Creating death ragdoll for player: " + player.getName().getString());

        if (!level.isClientSide) {
            JbulletWorld world = JbulletWorld.get((net.minecraft.server.level.ServerLevel) level);
            ragdoll.physics = new DeathRagdollPhysics(ragdoll, world);

            Vector3f playerVel = new Vector3f(
                    (float) player.getDeltaMovement().x,
                    (float) player.getDeltaMovement().y,
                    (float) player.getDeltaMovement().z);
            playerVel.scale(1f);

            float speed = playerVel.length();
            if (speed > 8f) playerVel.scale(8f / speed);

            ragdoll.physics.applyInitialVelocity(playerVel);
        }
        return ragdoll;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(PLAYER_NAME, "");
        this.entityData.define(PLAYER_UUID, "");
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            ticksExisted++;
            if (ticksExisted % 20 == 0) {
                Ragdollified.LOGGER.info(
                        "Death ragdoll " + this.getId() +
                                " alive for " + (ticksExisted / 20) +
                                " seconds. Lifetime: " + (lifetime / 20) + "s"
                );
            }
            if (ticksExisted >= lifetime) {
                Ragdollified.LOGGER.info(
                        "Death ragdoll " + this.getId() + " reached lifetime, removing"
                );
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
        Ragdollified.LOGGER.info("Death ragdoll " + this.getId() + " being removed. Reason: " + reason + ". Ticks existed: " + ticksExisted);
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

    // Armor getters
    public ItemStack getHelmet() { return helmet; }
    public ItemStack getChestplate() { return chestplate; }
    public ItemStack getLeggings() { return leggings; }
    public ItemStack getBoots() { return boots; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("PlayerName")) entityData.set(PLAYER_NAME, tag.getString("PlayerName"));
        if (tag.contains("PlayerUUID")) entityData.set(PLAYER_UUID, tag.getString("PlayerUUID"));
        ticksExisted = tag.getInt("TicksExisted");
        lifetime = tag.contains("Lifetime") ? tag.getInt("Lifetime") : RagdollifiedConfig.getRagdollLifetime();

        // Load armor
        if (tag.contains("Armor", 9)) { // 9 = ListTag
            ListTag armorList = tag.getList("Armor", 10); // 10 = CompoundTag
            if (armorList.size() >= 4) {
                helmet = ItemStack.of(armorList.getCompound(0));
                chestplate = ItemStack.of(armorList.getCompound(1));
                leggings = ItemStack.of(armorList.getCompound(2));
                boots = ItemStack.of(armorList.getCompound(3));
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("PlayerName", entityData.get(PLAYER_NAME));
        tag.putString("PlayerUUID", entityData.get(PLAYER_UUID));
        tag.putInt("TicksExisted", ticksExisted);
        tag.putInt("Lifetime", lifetime);

        // Save armor
        ListTag armorList = new ListTag();
        armorList.add(helmet.save(new CompoundTag()));
        armorList.add(chestplate.save(new CompoundTag()));
        armorList.add(leggings.save(new CompoundTag()));
        armorList.add(boots.save(new CompoundTag()));
        tag.put("Armor", armorList);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}