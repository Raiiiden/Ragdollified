package com.raiiiden.ragdollified;

import com.mojang.logging.LogUtils;
import com.raiiiden.ragdollified.client.compat.ETFCompatibilityHelper;
import com.raiiiden.ragdollified.command.SpawnRagdollCommand;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

@Mod(Ragdollified.MODID)
public class Ragdollified {
    public static final String MODID = "ragdollified";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ragdollified() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        MinecraftForge.EVENT_BUS.register(this);
        com.raiiiden.ragdollified.entity.ModEntities.register(modEventBus);
        com.raiiiden.ragdollified.item.ModItems.register(modEventBus);
        com.raiiiden.ragdollified.menu.ModMenus.register(modEventBus);
        ServerRagdollHitTracker.registerOptionalTaczHandler(MinecraftForge.EVENT_BUS);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.raiiiden.ragdollified.client.RagdollHitTracker.registerOptionalTaczHandler(MinecraftForge.EVENT_BUS));
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::onConfigReload);
        RagdollifiedConfig.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ETFCompatibilityHelper.initialize();
            com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.initialize();
            com.raiiiden.ragdollified.client.compat.VisualHealthCompat.initialize();
            com.raiiiden.ragdollified.client.compat.CuriosRenderCompat.initialize();
        });
    }

    private void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != RagdollifiedConfig.GAMEPLAY_SPEC) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            com.raiiiden.ragdollified.config.GameplayConfigSyncEvents.sendToAll(server);
        }
    }

    // NOTE: instance (non-static) on purpose. This mod subscribes to the Forge bus via
    // MinecraftForge.EVENT_BUS.register(this) in the constructor, and EventBus#register(Object)
    // only binds NON-static @SubscribeEvent methods (static ones require register(Class)). As a
    // static method this never fired, so no command here was ever registered.
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SpawnRagdollCommand.register(event.getDispatcher());
        com.raiiiden.ragdollified.command.RetrieveCorpseCommand.register(event.getDispatcher());
    }
}
