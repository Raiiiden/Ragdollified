package com.raiiiden.ragdollified;

import com.mojang.logging.LogUtils;
import com.raiiiden.ragdollified.client.compat.ETFCompatibilityHelper;
import com.raiiiden.ragdollified.command.SpawnRagdollCommand;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.client.DeathRagdollRenderer;
import com.raiiiden.ragdollified.client.MobRagdollRenderer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Ragdollified.MODID)
public class Ragdollified {
    public static final String MODID = "ragdollified";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ragdollified() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITIES.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        RagdollifiedConfig.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            EntityRenderers.register(ModEntities.DEATH_RAGDOLL.get(), DeathRagdollRenderer::new);
            EntityRenderers.register(ModEntities.MOB_RAGDOLL.get(), MobRagdollRenderer::new);
            ETFCompatibilityHelper.initialize();
        });
    }
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SpawnRagdollCommand.register(event.getDispatcher());
    }
}