package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.network.RagdollSpawnPacket;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.server.ServerRagdollSyncManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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

        Vec3 vel = RagdollSpawnState.captureLinearVelocity(entity);

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
        // Cats reuse the same byte layout (bit0 = a boolean flag, bits1-4 = a dye colour): here
        // bit0 = tamed (whether to draw the collar) and bits1-4 = the collar colour. The client
        // reads them back through the same wasSheared()/dyeColorId accessors for the cat path.
        if (entity instanceof net.minecraft.world.entity.animal.Cat cat) {
            sheepState = RagdollSpawnPacket.packSheepState(cat.isTame(), cat.getCollarColor().getId());
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
        Vec3 explosionKick = RagdollSpawnState.captureExplosionVelocityKick(entity, event.getSource());
        if (explosionKick != null) {
            hitPartIndex = (byte) RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX;
            hitImpulseX = (float) explosionKick.x;
            hitImpulseY = (float) explosionKick.y;
            hitImpulseZ = (float) explosionKick.z;
        } else if (hitInfo != null) {
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

        ServerRagdollSyncManager.registerDeath(entity, packet);
    }

}
