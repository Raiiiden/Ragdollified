package com.raiiiden.ragdollified.entity;

import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Ragdollified.MODID);

    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE = ENTITIES.register("corpse",
            () -> EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(1.0f, 0.8f)
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .build("corpse"));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}