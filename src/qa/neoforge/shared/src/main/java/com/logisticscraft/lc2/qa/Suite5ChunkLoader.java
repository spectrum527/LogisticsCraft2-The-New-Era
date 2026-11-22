package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.LogisticsChunkTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Suite 5: Chunk Loading Upgrade ({@code lc2:chunk_loading_upgrade}).
 * Placed far outside spawn chunks at (-160, 64, 160) -> chunk (-10, 10):
 * - Verifies all 9 chunks (3x3) are held ({@code LogisticsChunkTickets.isChunkHeld == true})
 * - Verifies exact 2.0 EU/t standby drain even after releasing test chunk force-load
 * - Verifies immediate release of all 9 tickets when EU drains to 0
 * - Verifies immediate release of all 9 tickets when upgrade is removed while powered
 */
public final class Suite5ChunkLoader {

    private static final BlockPos STATION_POS = new BlockPos(-160, 64, 160);
    private static final int CENTER_CX = STATION_POS.getX() >> 4; // -10
    private static final int CENTER_CZ = STATION_POS.getZ() >> 4; // 10

    private enum Stage {
        FORCE_CHUNK,
        WAIT_CHUNK_TICKING_AND_SETUP,
        VERIFY_SELF_HOLDING_AND_DRAIN,
        WAIT_POWER_DEPLETION,
        WAIT_REPOWER_TICKING,
        VERIFY_REMOVAL_RELEASE,
        DONE
    }

    private Stage stage = Stage.FORCE_CHUNK;
    private int ticksInStage = 0;
    private double euAfterRelease = 0.0;
    private boolean reacquiredVerified = false;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case FORCE_CHUNK -> {
                level.setChunkForced(CENTER_CX, CENTER_CZ, true);
                level.getChunk(CENTER_CX, CENTER_CZ);
                ticksInStage = 0;
                stage = Stage.WAIT_CHUNK_TICKING_AND_SETUP;
                yield false;
            }
            case WAIT_CHUNK_TICKING_AND_SETUP -> {
                ticksInStage++;
                if (!Lc2Native.isChunkEntityTicking(level, CENTER_CX, CENTER_CZ)) {
                    if (ticksInStage > 500) {
                        throw new AssertionError("Timeout waiting for forced chunk (-10, 10) to become entity-ticking");
                    }
                    yield false;
                }
                setupStation(level, checks);
                yield false;
            }
            case VERIFY_SELF_HOLDING_AND_DRAIN -> {
                verifySelfHolding(level, checks);
                yield false;
            }
            case WAIT_POWER_DEPLETION -> {
                waitPowerDepletion(level, checks);
                yield false;
            }
            case WAIT_REPOWER_TICKING -> {
                ticksInStage++;
                if (!Lc2Native.isChunkEntityTicking(level, CENTER_CX, CENTER_CZ)) {
                    if (ticksInStage > 500) {
                        throw new AssertionError("Timeout waiting for re-forced chunk (-10, 10) to tick");
                    }
                    yield false;
                }
                CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(STATION_POS);
                station.addEnergy(100.0);
                reacquiredVerified = false;
                ticksInStage = 0;
                stage = Stage.VERIFY_REMOVAL_RELEASE;
                yield false;
            }
            case VERIFY_REMOVAL_RELEASE -> {
                verifyRemovalRelease(level, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void setupStation(ServerLevel level, QaChecks checks) {
        level.setBlockAndUpdate(STATION_POS, QaChecks.block("lc2:cargo_station").defaultBlockState());
        CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(STATION_POS);
        checks.check("s5.setup.station_placed_outside_spawn", station != null);
        checks.check("s5.tickets.initially_unheld", countHeldIn3x3(level) == 0, "held=0/9");

        // Insert chunk loading upgrade + exactly 40.0 EU (20 ticks of 2.0 EU/t)
        station.setItem(CargoStationBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.CHUNK_LOADING_UPGRADE_ID), 1));
        station.addEnergy(40.0);

        ticksInStage = 0;
        stage = Stage.VERIFY_SELF_HOLDING_AND_DRAIN;
    }

    private void verifySelfHolding(ServerLevel level, QaChecks checks) {
        ticksInStage++;
        CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(STATION_POS);
        if (euAfterRelease == 0.0) {
            if (station.getStoredEu() == 40.0) {
                if (ticksInStage > 20) {
                    throw new AssertionError("Station never ticked in forced chunk (-10, 10), eu=" + station.getStoredEu());
                }
                return;
            }
            // First tick executed: all 9 chunks held, EU drained by exact 2.0 EU/t (40 -> 38)
            checks.check("s5.tickets.all_9_chunks_held",
                    station.isChunkLoaderActive() && countHeldIn3x3(level) == 9,
                    "held=" + countHeldIn3x3(level) + "/9, eu=" + station.getStoredEu());
            checks.check("s5.drain.exact_2eu_per_tick",
                    Math.abs(station.getStoredEu() - 38.0) < 1e-6,
                    "euAfter1Tick=" + station.getStoredEu());
            // Release our temporary test force-load — the station's own 3x3 ticket MUST keep itself ticking!
            level.setChunkForced(CENTER_CX, CENTER_CZ, false);
            euAfterRelease = station.getStoredEu();
            ticksInStage = 0;
            return;
        }
        if (ticksInStage == 5) {
            // 5 more ticks elapsed with NO test force-load -> drained exact 5 * 2.0 = 10.0 EU!
            double expectedEu = euAfterRelease - 10.0;
            checks.check("s5.self_loaded.keeps_ticking_and_draining_2eu_per_tick",
                    station.isChunkLoaderActive()
                            && countHeldIn3x3(level) == 9
                            && Lc2Native.isChunkEntityTicking(level, CENTER_CX, CENTER_CZ)
                            && Math.abs(station.getStoredEu() - expectedEu) < 1e-6,
                    "eu=" + station.getStoredEu() + " (expected " + expectedEu + "), held=9/9");
            ticksInStage = 0;
            stage = Stage.WAIT_POWER_DEPLETION;
        }
    }

    private void waitPowerDepletion(ServerLevel level, QaChecks checks) {
        ticksInStage++;
        if (ticksInStage > 40) {
            throw new AssertionError("Timeout waiting for chunk loader EU depletion release");
        }
        if (countHeldIn3x3(level) > 0) {
            return;
        }
        // Re-force temporarily to inspect station & run removal-while-powered check
        level.setChunkForced(CENTER_CX, CENTER_CZ, true);
        level.getChunk(CENTER_CX, CENTER_CZ);
        CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(STATION_POS);
        checks.check("s5.tickets.released_immediately_on_zero_eu",
                !station.isChunkLoaderActive() && countHeldIn3x3(level) == 0 && station.getStoredEu() < 2.0,
                "held=0/9, eu=" + station.getStoredEu());

        ticksInStage = 0;
        stage = Stage.WAIT_REPOWER_TICKING;
    }

    private void verifyRemovalRelease(ServerLevel level, QaChecks checks) {
        ticksInStage++;
        CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(STATION_POS);
        if (!reacquiredVerified) {
            if (countHeldIn3x3(level) < 9) {
                if (ticksInStage > 20) {
                    throw new AssertionError("Station failed to reacquire 9 chunk tickets when repowered, eu="
                            + station.getStoredEu() + ", held=" + countHeldIn3x3(level));
                }
                return;
            }
            checks.check("s5.tickets.reacquired_when_repowered",
                    station.isChunkLoaderActive() && countHeldIn3x3(level) == 9,
                    "held=" + countHeldIn3x3(level) + "/9, eu=" + station.getStoredEu());
            // Remove chunk loading upgrade while plenty of EU remains (~98 EU)
            station.setItem(CargoStationBlockEntity.UPGRADE_START, ItemStack.EMPTY);
            reacquiredVerified = true;
            ticksInStage = 0;
            return;
        }
        if (ticksInStage == 1) {
            checks.check("s5.tickets.released_immediately_on_upgrade_removal",
                    !station.isChunkLoaderActive() && countHeldIn3x3(level) == 0 && station.getStoredEu() > 90.0,
                    "held=" + countHeldIn3x3(level) + "/9, eu=" + station.getStoredEu());

            // Cleanup
            level.setBlockAndUpdate(STATION_POS, Blocks.AIR.defaultBlockState());
            level.setChunkForced(CENTER_CX, CENTER_CZ, false);
            stage = Stage.DONE;
        }
    }

    private static int countHeldIn3x3(ServerLevel level) {
        int held = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (LogisticsChunkTickets.isChunkHeld(level, new ChunkPos(CENTER_CX + dx, CENTER_CZ + dz))) {
                    held++;
                }
            }
        }
        return held;
    }
}
