package com.logisticscraft.lc2.qa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live dedicated-server QA harness mod ({@code lc2_qa}) executing all 7 end-to-end verification
 * suites across a two-phase cold restart ({@code write} -> {@code read}) alongside the exact
 * packaged {@code ic2} and {@code lc2} production JARs.
 */
@Mod("lc2_qa")
public final class Lc2QaHarness {

    private static final Logger LOGGER = LoggerFactory.getLogger(Lc2QaHarness.class);

    private final String phase = System.getProperty("lc2.qa.phase", "write");
    private final QaChecks checks = new QaChecks();

    private final Suite1RegistryPower suite1 = new Suite1RegistryPower();
    private final Suite2Waterway suite2 = new Suite2Waterway();
    private final Suite3Airway suite3 = new Suite3Airway();
    private final Suite4UnloadedTransit suite4 = new Suite4UnloadedTransit();
    private final Suite5ChunkLoader suite5 = new Suite5ChunkLoader();
    private final Suite6CollisionAvoidance suite6 = new Suite6CollisionAvoidance();
    private final Suite7Persistence suite7 = new Suite7Persistence();

    private int activeSuite = 1;
    private int ticks = 0;
    private boolean finished = false;

    public Lc2QaHarness() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        checks.putMeta("mcVersion", System.getProperty("lc2.qa.mcVersion", "unknown"));
        try {
            if ("read".equals(phase)) {
                verifyProductionJarsOnRead();
                suite7.startRead(level, checks);
                server.tickRateManager().requestGameToSprint(2_000);
            } else {
                // Force-load local test area chunks cx=0..3, cz=0..3 for Suites 1-3
                QaChecks.setChunkRangeForced(level, 0, 3, 0, 3, true);
                suite1.setup(level, checks);
                server.tickRateManager().requestGameToSprint(6_000);
            }
        } catch (Throwable t) {
            complete(server, t);
        }
    }

    private void verifyProductionJarsOnRead() throws IOException {
        Path lc2Jar = ModList.get().getModContainerById("lc2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        Path ic2Jar = ModList.get().getModContainerById("ic2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        String expectedLc2 = System.getProperty("lc2.qa.expectedLc2Jar", lc2Jar.getFileName().toString());
        String expectedIc2 = System.getProperty("lc2.qa.expectedIc2Jar", ic2Jar.getFileName().toString());
        checks.putMeta("lc2Artifact", lc2Jar.getFileName().toString());
        checks.putMeta("ic2Artifact", ic2Jar.getFileName().toString());
        checks.check("read.jar.lc2_loaded_from_mods",
                Files.isSameFile(lc2Jar, Path.of("mods", expectedLc2)), lc2Jar.getFileName().toString());
        checks.check("read.jar.ic2_loaded_from_mods",
                Files.isSameFile(ic2Jar, Path.of("mods", expectedIc2)), ic2Jar.getFileName().toString());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        ticks++;
        try {
            if ("read".equals(phase)) {
                if (suite7.tickRead(level, ticks, checks)) {
                    checks.check("read.world_saved", server.saveEverything(false, true, true));
                    complete(server, null);
                }
                return;
            }

            if (ticks > 5_500) {
                throw new AssertionError("Global QA write-phase tick budget exceeded at suite " + activeSuite);
            }

            boolean suiteDone = switch (activeSuite) {
                case 1 -> suite1.tick(level, ticks, checks);
                case 2 -> suite2.tick(level, ticks, checks);
                case 3 -> suite3.tick(level, ticks, checks);
                case 4 -> suite4.tick(level, ticks, checks);
                case 5 -> suite5.tick(level, ticks, checks);
                case 6 -> suite6.tick(level, ticks, checks);
                case 7 -> suite7.tickWrite(level, ticks, checks);
                default -> true;
            };

            if (suiteDone) {
                LOGGER.info("[LC2-QA] Completed Suite {} at tick {}", activeSuite, ticks);
                activeSuite++;
                if (activeSuite > 7) {
                    checks.check("write.world_saved", server.saveEverything(false, true, true));
                    complete(server, null);
                }
            }
        } catch (Throwable t) {
            complete(server, t);
        }
    }

    private void complete(MinecraftServer server, Throwable failure) {
        if (finished) {
            return;
        }
        finished = true;
        if (failure != null) {
            LOGGER.error("[LC2-QA] Suite failed in phase {} at tick {}: {}", phase, ticks, failure.toString(), failure);
        } else {
            LOGGER.info("[LC2-QA] Phase {} PASSED all {} checks in {} ticks", phase, checks.passedCount(), ticks);
        }
        try {
            checks.writeReports(phase, ticks, failure);
        } catch (IOException e) {
            LOGGER.error("[LC2-QA] Failed writing QA reports", e);
        } finally {
            server.halt(false);
        }
    }
}
