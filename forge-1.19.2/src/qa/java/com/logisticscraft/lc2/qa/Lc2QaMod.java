package com.logisticscraft.lc2.qa;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated-server QA test mod for LogisticsCraft² (Forge 1.19.2).
 * Activated automatically when {@code -Dlc2.qa.phase=write} or {@code -Dlc2.qa.phase=read} is passed.
 */
@Mod(Lc2QaMod.MOD_ID)
public final class Lc2QaMod {

    public static final String MOD_ID = "lc2_qa";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Lc2QaMod() {
        String phase = System.getProperty("lc2.qa.phase", "").trim().toLowerCase();
        if ("write".equals(phase) || "read".equals(phase)) {
            LOGGER.info("[LC2-QA] Registering Forge 1.19.2 live-server QA harness (phase={})", phase);
            MinecraftForge.EVENT_BUS.register(new Lc2QaRunner(phase));
        } else {
            LOGGER.info("[LC2-QA] Idle (lc2.qa.phase not set to write/read)");
        }
    }
}
