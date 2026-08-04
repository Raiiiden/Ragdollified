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

        // Large and medium slimes are replacement/split deaths, not the end of the mob family.
        // Only the size-one child leaves a corpse; this covers MagmaCube as it extends Slime.
        if (entity instanceof net.minecraft.world.entity.monster.Slime slime && slime.getSize() > 1) return;

        boolean isPlayer = entity instanceof ServerPlayer;
        String mobType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        if (!RagdollifiedConfig.isRagdollEnabledFor(mobType, isPlayer)) return;

        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : MobModelHelper.getModelTypeFromEntity(entity);

        if (!isPlayer && modelType == MobModelHelper.ModelType.UNSUPPORTED) {
            Ragdollified.LOGGER.debug("Sending ragdoll candidate for client-side model detection: {}", mobType);
        }

        Vec3 vel = RagdollSpawnState.applyAttackerDirectionFallback(
                entity, event.getSource(), RagdollSpawnState.captureLinearVelocity(entity));

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
        if (entity instanceof net.minecraft.world.entity.animal.Wolf wolf) {
            sheepState = RagdollSpawnPacket.packSheepState(wolf.isTame(), wolf.getCollarColor().getId());
        }
        if (entity instanceof net.minecraft.world.entity.animal.goat.Goat goat) {
            int hornMask = (goat.hasLeftHorn() ? 1 : 0) | (goat.hasRightHorn() ? 2 : 0);
            sheepState = RagdollSpawnPacket.packSheepState(false, hornMask);
        }
        if (entity instanceof net.minecraft.world.entity.animal.Turtle turtle) {
            sheepState = RagdollSpawnPacket.packSheepState(turtle.hasEgg(), 0);
        }
        if (entity instanceof net.minecraft.world.entity.animal.SnowGolem snowGolem) {
            sheepState = RagdollSpawnPacket.packSheepState(snowGolem.hasPumpkin(), 0);
        }
        // Equines multiplex the same compact state: bit0 = donkey/mule chest, bits1-4 =
        // Horse markings id. Species are mutually exclusive with sheep/cats.
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) {
            boolean hasChest = horse instanceof net.minecraft.world.entity.animal.horse.AbstractChestedHorse chested
                    && chested.hasChest();
            int markings = horse instanceof net.minecraft.world.entity.animal.horse.Horse normalHorse
                    ? normalHorse.getMarkings().getId() : 0;
            sheepState = RagdollSpawnPacket.packSheepState(hasChest, markings);
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
        if (entity instanceof net.minecraft.world.entity.monster.Strider strider && strider.isSaddled()) {
            overlayState |= 0x2;
        }
        // The charged-creeper bit is also the equine saddle bit; the mob type disambiguates it.
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse && horse.isSaddled()) {
            overlayState |= 0x1;
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
        // Lever arm for the death impulse, relative to the entity origin. A blow through the
        // centre of mass produces no torque at all, so without this a struck body only ever
        // slides — it never tips over.
        float hitOffsetX = 0f, hitOffsetY = 0f, hitOffsetZ = 0f;
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
                if (hitInfo.hitPos != null) {
                    Vec3 origin = entity.position();
                    hitOffsetX = (float) (hitInfo.hitPos.x - origin.x);
                    hitOffsetY = (float) (hitInfo.hitPos.y - origin.y);
                    hitOffsetZ = (float) (hitInfo.hitPos.z - origin.z);
                }
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
                // Body yaw, not getYRot(). getYRot() is where the entity was *looking*; the
                // model is rendered on yBodyRot (LivingEntityRenderer uses 180 - yBodyRot,
                // which is the formula ClientRagdoll mirrors). The two diverge by up to 50°
                // normally, and by ~180° for anything that died while backpedaling — aiStep
                // flips body yaw when movement and look direction disagree by more than 95°.
                entity.getVisualRotationYInDegrees(), entity.getXRot(),
                vel.x, vel.y, vel.z,
                entity.getPose() == Pose.SWIMMING,
                isBaby,
                entity.getItemBySlot(EquipmentSlot.HEAD).copy(),
                entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
                entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
                entity.getItemBySlot(EquipmentSlot.FEET).copy(),
                sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ,
                hitOffsetX, hitOffsetY, hitOffsetZ,
                overlayState,
                villagerType, villagerProfession, villagerLevel
        );

        ServerRagdollSyncManager.registerDeath(entity, packet);
    }

}
