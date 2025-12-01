package com.raiiiden.ragdollified;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Ragdollified.MODID);

    public static final RegistryObject<EntityType<DeathRagdollEntity>> DEATH_RAGDOLL =
            ENTITIES.register(
                    "death_ragdoll",
                    () -> EntityType.Builder.<DeathRagdollEntity>of(DeathRagdollEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .updateInterval(1)          // ← every tick
                            .setShouldReceiveVelocityUpdates(true) // ← send velocity
                            .build("death_ragdoll")
            );
    public static final RegistryObject<EntityType<MobRagdollEntity>> MOB_RAGDOLL = ENTITIES.register("mob_ragdoll",
            () -> EntityType.Builder.<MobRagdollEntity>of(MobRagdollEntity::new, MobCategory.MISC)
                    .sized(1.0f, 2.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("mob_ragdoll"));
}