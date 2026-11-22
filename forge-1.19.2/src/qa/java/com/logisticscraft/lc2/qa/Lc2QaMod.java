package com.logisticscraft.lc2.qa;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QA & real-world field-test mod for LogisticsCraft² (Forge 1.19.2).
 * Strictly isolated in {@code src/qa/} and never packaged into {@code lc2-forge-1.19.2-1.0.0.jar}.
 * <p>
 * Modes activated via {@code -Dlc2.qa.phase}:
 * <ul>
 *   <li>{@code write} / {@code read}: flat-world 7-scenario live-server QA suite ({@code runLc2Qa})</li>
 *   <li>{@code field_server}: natural-seed automated IC2 mining &amp; refining factory + client-server orchestrator ({@code runFieldTest})</li>
 *   <li>{@code field_client}: real Forge 1.19.2 graphical client driver ({@code runFieldTest})</li>
 * </ul>
 */
@Mod(Lc2QaMod.MOD_ID)
public final class Lc2QaMod {

    public static final String MOD_ID = "lc2_qa";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Lc2QaMod() {
        Lc2FieldNetwork.register();
        String phase = System.getProperty("lc2.qa.phase", "").trim().toLowerCase();
        switch (phase) {
            case "write", "read" -> {
                LOGGER.info("[LC2-QA] Registering Forge 1.19.2 live-server QA harness (phase={})", phase);
                MinecraftForge.EVENT_BUS.register(new Lc2QaRunner(phase));
            }
            case "field_server" -> {
                LOGGER.info("[LC2-QA] Registering Forge 1.19.2 natural-world field server runner");
                MinecraftForge.EVENT_BUS.register(new Lc2FieldServerRunner());
            }
            case "field_client" -> {
                LOGGER.info("[LC2-QA] Registering Forge 1.19.2 graphical field client driver");
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> Lc2FieldClientDriver::install);
            }
            default -> LOGGER.info("[LC2-QA] Idle (lc2.qa.phase='{}')", phase);
        }
    }
}
