package com.logisticscraft.lc2.forge;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.client.Lc2Client;
import com.logisticscraft.lc2.forge.compat.Lc2TopCompat;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.19.2 entry point for LogisticsCraft²: The New Era ({@code lc2}).
 */
@Mod(LogisticsConstants.MOD_ID)
public final class Lc2Mod {

    public static final Logger LOGGER = LoggerFactory.getLogger(LogisticsConstants.MOD_ID);

    public Lc2Mod(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        Lc2Registries.register(modBus);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(Lc2TopCompat::onInterModEnqueue);

        MinecraftForge.EVENT_BUS.register(LogisticsLevelManager.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            Lc2Client.install(modBus);
        }
        LOGGER.info("Transitera (Forge 1.19.2) initialized.");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(Lc2Network::register);
    }
}
