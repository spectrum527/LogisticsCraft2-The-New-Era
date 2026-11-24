package com.logisticscraft.lc2;

import com.logisticscraft.lc2.client.Lc2ClientSetup;
import com.logisticscraft.lc2.compat.Lc2TopCompat;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.network.Lc2Network;
import com.logisticscraft.lc2.platform.LogisticsChunkTickets;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main NeoForge entrypoint for LogisticsCraft²: The New Era ({@code lc2}).
 */
@Mod(LogisticsConstants.MOD_ID)
public final class Lc2Mod {

    private static final Logger LOGGER = LoggerFactory.getLogger(Lc2Mod.class);

    public Lc2Mod(IEventBus modBus, Dist side) {
        Lc2Registries.install(modBus);
        LogisticsChunkTickets.install(modBus);
        Lc2Registries.registerCapabilities(modBus);
        Lc2Network.install(modBus, side);
        Lc2TopCompat.install(modBus);
        LogisticsLevelManager.install(NeoForge.EVENT_BUS);

        if (side == Dist.CLIENT) {
            ClientEntry.install(modBus);
        }

        LOGGER.info("LogisticsCraft²: The New Era initialized (side={})", side);
    }

    private static final class ClientEntry {
        private static void install(IEventBus modBus) {
            Lc2ClientSetup.install(modBus);
        }
    }
}
