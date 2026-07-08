package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

@Mod.EventBusSubscriber(modid = Ragdollified.MODID)
public class PhysicsHooks {

    // HIGHEST so the ragdoll's worn armor is read into the spawn packet BEFORE CorpseManager
    // (LOWEST) clears the player's inventory into the corpse — otherwise the ragdoll spawns bare.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        boolean isPlayer = entity instanceof ServerPlayer;
        String mobType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(mobType, isPlayer)) return;

        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : MobModelHelper.getModelTypeFromEntity(entity);

        if (!isPlayer && modelType == MobModelHelper.ModelType.UNSUPPORTED) {
            Ragdollified.LOGGER.debug("Sending ragdoll candidate for client-side model detection: {}", mobType);
        }

        if (isPlayer || MobModelHelper.isSupportedModelType(modelType)) {
            entity.setInvisible(true);
            entity.clearFire();
        }
        if (isPlayer) {
            entity.setCustomNameVisible(false);
        }

        Vec3 vel = calculateDeathVelocity(entity, event.getSource());

        float scale = isPlayer ? 1.0f : entity.getBbHeight() / 1.8f;
        // Use LivingEntity.isBaby() rather than an AgeableMob check: zombies, husks and
        // piglins are Monsters (not AgeableMob) but still override isBaby(), so the
        // instanceof check missed every baby zombie — they spawned adult-sized ragdolls.
        boolean isBaby = entity.isBaby();

        // Sheep need wool-state captured at the moment of death so client renderers can
        // draw the fur layer with the correct dye color (or skip it if the sheep had been
        // sheared). For non-sheep mobs the byte is just zero — clients ignore it.
        byte sheepState = 0;
        if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep) {
            sheepState = RagdollSpawnPacket.packSheepState(sheep.isSheared(), sheep.getColor().getId());
        }

        // Generic overlay-state bits for mobs whose corpse needs an extra layer based on
        // a single boolean (charged creeper → energy swirl, saddled pig → saddle, …).
        // bit 0 = creeper.isPowered(), bit 1 = pig.isSaddled(). Reserved bits 2-7.
        byte overlayState = 0;
        if (entity instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.isPowered()) {
            overlayState |= 0x1;
        }
        if (entity instanceof net.minecraft.world.entity.animal.Pig pig && pig.isSaddled()) {
            overlayState |= 0x2;
        }

        // Villager / zombie villager profession state. Captured as registry-key strings
        // so mod-added biomes/professions ride along without an id remap. Empty for
        // non-villager mobs (the renderer skips the profession overlay in that case).
        String villagerType = "";
        String villagerProfession = "";
        byte villagerLevel = 0;
        if (entity instanceof net.minecraft.world.entity.npc.VillagerDataHolder vdh) {
            net.minecraft.world.entity.npc.VillagerData vd = vdh.getVillagerData();
            if (vd != null) {
                net.minecraft.resources.ResourceLocation typeKey =
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE.getKey(vd.getType());
                net.minecraft.resources.ResourceLocation profKey =
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(vd.getProfession());
                villagerType = typeKey != null ? typeKey.toString() : "";
                villagerProfession = profKey != null ? profKey.toString() : "";
                villagerLevel = (byte) Math.max(0, Math.min(127, vd.getLevel()));
            }
        }

        // Pull any directional hit captured by ServerRagdollHitTracker (TACZ Pre +
        // vanilla LivingHurtEvent). Resolve part + impulse here on the server so every
        // client sees the same kick — no per-client tracker race.
        ServerRagdollHitTracker.HitInfo hitInfo = ServerRagdollHitTracker.consume(entity.getId());
        byte hitPartIndex = -1;
        float hitImpulseX = 0f, hitImpulseY = 0f, hitImpulseZ = 0f;
        if (hitInfo != null) {
            Vec3 impulse = RagdollHitMapper.computeImpulse(
                    hitInfo.direction, hitInfo.isHeadShot, hitInfo.isTaczBullet, hitInfo.isMelee, hitInfo.damage);
            if (impulse != null) {
                RagdollPart part = RagdollHitMapper.map(entity, hitInfo.hitPos, hitInfo.direction, hitInfo.isHeadShot);
                hitPartIndex = RagdollHitMapper.isCenteredHit(entity, hitInfo.hitPos, hitInfo.isHeadShot, part)
                        ? (byte) RagdollHitMapper.CENTER_HIT_PART_INDEX
                        : (byte) part.index;
                hitImpulseX = (float) impulse.x;
                hitImpulseY = (float) impulse.y;
                hitImpulseZ = (float) impulse.z;
            }
        }

        RagdollSpawnPacket packet = new RagdollSpawnPacket(
                entity.getId(),
                isPlayer,
                mobType,
                modelType,
                scale,
                isPlayer ? entity.getUUID().toString() : "",
                isPlayer ? entity.getName().getString() : "",
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getYRot(), entity.getXRot(),
                vel.x, vel.y, vel.z,
                entity.getPose() == Pose.SWIMMING,
                isBaby,
                entity.getItemBySlot(EquipmentSlot.HEAD).copy(),
                entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
                entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
                entity.getItemBySlot(EquipmentSlot.FEET).copy(),
                sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ,
                overlayState,
                villagerType, villagerProfession, villagerLevel
        );

        ModNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    private static Vec3 calculateDeathVelocity(LivingEntity entity, net.minecraft.world.damagesource.DamageSource damageSource) {
        Vec3 delta = entity.getDeltaMovement();
        Vec3 vel = new Vec3(delta.x * 8, delta.y * 6, delta.z * 8);

        boolean hasLowVelocity = delta.lengthSqr() < 0.5;

        if (damageSource != null) {
            String damageType = damageSource.getMsgId();

            if (damageType.contains("tacz.bullet") && hasLowVelocity) {
                Vec3 damagePos = damageSource.getSourcePosition();
                if (damagePos != null) {
                    Vec3 direction = entity.position().subtract(damagePos).normalize();
                    vel = new Vec3(direction.x * 3.0, direction.y * 4.0 + 2.0, direction.z * 3.0);
                } else {
                    Vec3 lookVec = entity.getLookAngle();
                    vel = new Vec3(lookVec.x * 5.0, 2.0, lookVec.z * 5.0);
                }
            }

            if (damageType.contains("explosion")) {
                Vec3 explosionCenter = damageSource.getSourcePosition();
                if (explosionCenter != null) {
                    Vec3 direction = entity.position().subtract(explosionCenter).normalize();
                    float distance = (float) entity.position().distanceTo(explosionCenter);
                    float baseStrength = Math.min(entity.getMaxHealth() / 10f, 5f);
                    float distanceFalloff = Math.max(0.5f, 1.0f - (distance / 10f));
                    float explosionStrength = baseStrength * distanceFalloff;
                    double impulse = com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_EXPLOSION.get();
                    vel = new Vec3(
                            direction.x * impulse * explosionStrength,
                            direction.y * impulse * 0.8 * explosionStrength + 3.0,
                            direction.z * impulse * explosionStrength
                    );
                }
            }
        }

        return vel;
    }
}
