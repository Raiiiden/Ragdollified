package com.raiiiden.ragdollified;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Ragdollified.MODID)
public class Ragdollified {
    public static final String MODID = "ragdollified";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ragdollified() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);

        LOGGER.info("Ragdollified initialized!");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
        });
    }
}