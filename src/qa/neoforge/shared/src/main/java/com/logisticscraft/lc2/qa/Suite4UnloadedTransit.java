package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 4: Unloaded-Chunk Virtual Transit (Create-Train Style).
 * Places Distant Target Port at X=356 (chunk 22) and Distant Target Station at X=372 (chunk 23),
 * lets intermediate and distant target chunks genuinely unload with simulation-distance=2,
 * dispatches BOTH a Boat and a Drone across the unloaded gap, proves intermediate chunks remain
 * unloaded and entities dematerialize while virtual transit completes, then loads the distant
 * target chunks and confirms 100% cargo delivery with 0 lost items.
 */
public final class Suite4UnloadedTransit {

    private static final int WATER_Y = 63;
    private static final BlockPos ORIGIN_PORT = new BlockPos(16, WATER_Y, 72);
    private static final BlockPos DISTANT_PORT = new BlockPos(356, WATER_Y, 72);
    private static final BlockPos DISTANT_PORT_CHEST = DISTANT_PORT.east();

    private static final BlockPos ORIGIN_STATION = new BlockPos(16, 64, 80);
    private static final BlockPos DISTANT_STATION = new BlockPos(372, 64, 80);
    private static final BlockPos DISTANT_STATION_CHEST = DISTANT_STATION.east();

    private static final int DISTANT_PORT_CX = DISTANT_PORT.getX() >> 4;       // 22
    private static final int DISTANT_PORT_CZ = DISTANT_PORT.getZ() >> 4;       // 4
    private static final int DISTANT_STATION_CX = DISTANT_STATION.getX() >> 4; // 23
    private static final int DISTANT_STATION_CZ = DISTANT_STATION.getZ() >> 4; // 5

    private enum Stage {
        SETUP,
        WAIT_ONLOAD_THEN_UNFORCE,
        WAIT_CHUNKS_UNLOAD,
        TRACK_VIRTUAL_TRANSIT,
        WAIT_TARGET_DELIVERY,
        DONE
    }

    private Stage stage = Stage.SETUP;
    private int stageStartTick = 0;

    private UUID boatTransitId;
    private UUID droneTransitId;
    private final Map<String, Integer> expectedBoatCargo = new TreeMap<>();
    private final Map<String, Integer> expectedDroneCargo = new TreeMap<>();

    private boolean observedBoatUnloadedProgress = false;
    private boolean observedDroneUnloadedProgress = false;
    private double lastBoatUnloadedX = -1.0;
    private double lastDroneUnloadedX = -1.0;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case SETUP -> {
                setupCorridor(level, tick, checks);
                yield false;
            }
            case WAIT_ONLOAD_THEN_UNFORCE -> {
                if (tick - stageStartTick >= 2) {
                    // Release distant target force-loads so distant + intermediate chunks genuinely unload!
                    level.setChunkForced(DISTANT_PORT_CX, DISTANT_PORT_CZ, false);
                    level.setChunkForced(DISTANT_STATION_CX, DISTANT_STATION_CZ, false);
                    stage = Stage.WAIT_CHUNKS_UNLOAD;
                    stageStartTick = tick;
                }
                yield false;
            }
            case WAIT_CHUNKS_UNLOAD -> {
                waitUnloadAndDispatch(level, tick, checks);
                yield false;
            }
            case TRACK_VIRTUAL_TRANSIT -> {
                trackVirtualTransit(level, tick, checks);
                yield false;
            }
            case WAIT_TARGET_DELIVERY -> {
                waitTargetDelivery(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void setupCorridor(ServerLevel level, int tick, QaChecks checks) {
        // Force-load origin chunks (cx 0..2, cz 4..5) AND briefly force distant endpoint chunks for 2 ticks so onLoad() fires
        QaChecks.setChunkRangeForced(level, 0, 2, 4, 5, true);
        level.setChunkForced(DISTANT_PORT_CX, DISTANT_PORT_CZ, true);
        level.setChunkForced(DISTANT_STATION_CX, DISTANT_STATION_CZ, true);
        level.getChunk(DISTANT_PORT_CX, DISTANT_PORT_CZ);
        level.getChunk(DISTANT_STATION_CX, DISTANT_STATION_CZ);

        // Build straight 1-wide water canal from X=17..355 at Z=72, Y=63
        QaChecks.buildCanal(level, 17, 355, 72, 72, WATER_Y);

        level.setBlockAndUpdate(ORIGIN_PORT,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(DISTANT_PORT,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(DISTANT_PORT_CHEST, Blocks.CHEST.defaultBlockState());

        var stationState = QaChecks.block("lc2:cargo_station").defaultBlockState();
        level.setBlockAndUpdate(ORIGIN_STATION, stationState);
        level.setBlockAndUpdate(DISTANT_STATION, stationState);
        level.setBlockAndUpdate(DISTANT_STATION_CHEST, Blocks.CHEST.defaultBlockState());

        SmartPortBlockEntity originPort = (SmartPortBlockEntity) level.getBlockEntity(ORIGIN_PORT);
        SmartPortBlockEntity distantPort = (SmartPortBlockEntity) level.getBlockEntity(DISTANT_PORT);
        CargoStationBlockEntity originStation = (CargoStationBlockEntity) level.getBlockEntity(ORIGIN_STATION);
        CargoStationBlockEntity distantStation = (CargoStationBlockEntity) level.getBlockEntity(DISTANT_STATION);

        checks.check("s4.setup.endpoints_ready",
                originPort != null && distantPort != null && originStation != null && distantStation != null);

        originPort.setStationName("Unloaded Origin Port");
        distantPort.setStationName("Unloaded Distant Port");
        originPort.refreshWaterBerth(level);
        distantPort.refreshWaterBerth(level);
        checks.check("s4.setup.berths_registered",
                originPort.getWaterBerthPos() != null && distantPort.getWaterBerthPos() != null);

        originStation.setStationName("Unloaded Origin Air");
        distantStation.setStationName("Unloaded Distant Air");

        // Dock boat at Origin Port & load 10 stacks into Origin Port buffer
        var player = FakePlayerFactory.getMinecraft(level);
        checks.check("s4.setup.boat_docked", originPort.spawnAndDockBoat(
                level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())));
        expectedBoatCargo.clear();
        for (int i = 0; i < 10; i++) {
            ItemStack s = new ItemStack(QaChecks.DISTINCT_CARGO_ITEMS.get(i), 12);
            originPort.setItem(i, s);
            expectedBoatCargo.merge(Lc2Native.itemId(s), 12, Integer::sum);
        }
        originPort.setTargetPos(DISTANT_PORT);
        originPort.setAutoReturn(false);
        originPort.setEnergyPriority(EnergyPriority.EU_FIRST);
        originPort.addEnergy(5000.0);

        // Load 6 stacks + rf_chip_2 (dist = 356 <= 512) + Standard Drone at Origin Station
        expectedDroneCargo.clear();
        for (int i = 0; i < 6; i++) {
            ItemStack s = new ItemStack(QaChecks.DISTINCT_CARGO_ITEMS.get(20 + i), 9);
            originStation.setItem(i, s);
            expectedDroneCargo.merge(Lc2Native.itemId(s), 9, Integer::sum);
        }
        originStation.setItem(CargoStationBlockEntity.RF_CHIP_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_2_ID), 1));
        originStation.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        originStation.setTargetPos(DISTANT_STATION);
        originStation.setAutoReturn(false);
        originStation.setEnergyPriority(EnergyPriority.EU_FIRST);
        originStation.addEnergy(5000.0);

        // Let onLoad() register distant endpoints for 2 ticks, then unforce and wait for chunks to unload
        stage = Stage.WAIT_ONLOAD_THEN_UNFORCE;
        stageStartTick = tick;
    }

    private void waitUnloadAndDispatch(ServerLevel level, int tick, QaChecks checks) {
        boolean midBoatUnloaded = !Lc2Native.isChunkEntityTicking(level, 12, 4) && !level.hasChunk(12, 4);
        boolean midDroneUnloaded = !Lc2Native.isChunkEntityTicking(level, 12, 5) && !level.hasChunk(12, 5);
        boolean targetPortUnloaded = !Lc2Native.isChunkEntityTicking(level, DISTANT_PORT_CX, DISTANT_PORT_CZ)
                && !level.hasChunk(DISTANT_PORT_CX, DISTANT_PORT_CZ);
        boolean targetStationUnloaded = !Lc2Native.isChunkEntityTicking(level, DISTANT_STATION_CX, DISTANT_STATION_CZ)
                && !level.hasChunk(DISTANT_STATION_CX, DISTANT_STATION_CZ);

        if (!(midBoatUnloaded && midDroneUnloaded && targetPortUnloaded && targetStationUnloaded)) {
            if (tick - stageStartTick > 200) {
                throw new AssertionError("Distant chunks did not unload in time: midBoat=" + midBoatUnloaded
                        + ", midDrone=" + midDroneUnloaded + ", targetPort=" + targetPortUnloaded
                        + ", targetStation=" + targetStationUnloaded);
            }
            return;
        }

        checks.check("s4.unloaded.intermediate_and_target_genuinely_unloaded_before_dispatch",
                true, "cx=12,22,23 all unloaded");

        SmartPortBlockEntity originPort = (SmartPortBlockEntity) level.getBlockEntity(ORIGIN_PORT);
        CargoStationBlockEntity originStation = (CargoStationBlockEntity) level.getBlockEntity(ORIGIN_STATION);

        var boatRes = originPort.tryDispatch(level, true);
        checks.check("s4.dispatch.distant_boat_launched", boatRes.isSuccess(), "failure=" + boatRes.failure());
        boatTransitId = boatRes.transit().id();

        var droneRes = originStation.tryDispatch(level, true);
        checks.check("s4.dispatch.distant_drone_launched", droneRes.isSuccess(), "failure=" + droneRes.failure());
        droneTransitId = droneRes.transit().id();

        stage = Stage.TRACK_VIRTUAL_TRANSIT;
        stageStartTick = tick;
    }

    private void trackVirtualTransit(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 1500) {
            throw new AssertionError("Timeout waiting for unloaded-chunk boat & drone virtual transit");
        }
        var data = LogisticsLevelManager.getData(level);
        var engine = data.engine();

        TransitRecord boat = engine.getTransit(boatTransitId).orElse(null);
        TransitRecord drone = engine.getTransit(droneTransitId).orElse(null);
        if (boat == null || drone == null) {
            throw new AssertionError("Unexpected premature completion before target chunks loaded: boat="
                    + (boat != null) + ", drone=" + (drone != null));
        }

        // Verify mid-transit virtual progress across genuinely UNLOADED intermediate chunks (X in [140..300])
        if (!observedBoatUnloadedProgress && boat.phase() == TransitPhase.EN_ROUTE_OUTBOUND) {
            double bx = boat.currentPos().x();
            int bcx = boat.currentPos().chunkX();
            int bcz = boat.currentPos().chunkZ();
            if (bx >= 140.0 && bx <= 300.0) {
                boolean chunkUnloaded = !Lc2Native.isChunkEntityTicking(level, bcx, bcz) && !level.hasChunk(bcx, bcz);
                boolean dematerialized = LogisticsLevelManager.findLiveEntity(level, boatTransitId) == null;
                if (chunkUnloaded && dematerialized) {
                    if (lastBoatUnloadedX < 0.0) {
                        lastBoatUnloadedX = bx;
                    } else if (bx > lastBoatUnloadedX + 5.0) {
                        observedBoatUnloadedProgress = true;
                        checks.check("s4.virtual.boat_advances_in_unloaded_chunks", true,
                                "x=" + String.format("%.1f->%.1f", lastBoatUnloadedX, bx) + " chunk=(" + bcx + "," + bcz + ")");
                    }
                }
            }
        }

        if (!observedDroneUnloadedProgress && drone.phase() == TransitPhase.EN_ROUTE_OUTBOUND) {
            double dx = drone.currentPos().x();
            int dcx = drone.currentPos().chunkX();
            int dcz = drone.currentPos().chunkZ();
            if (dx >= 140.0 && dx <= 300.0) {
                boolean chunkUnloaded = !Lc2Native.isChunkEntityTicking(level, dcx, dcz) && !level.hasChunk(dcx, dcz);
                boolean dematerialized = LogisticsLevelManager.findLiveEntity(level, droneTransitId) == null;
                if (chunkUnloaded && dematerialized) {
                    if (lastDroneUnloadedX < 0.0) {
                        lastDroneUnloadedX = dx;
                    } else if (dx > lastDroneUnloadedX + 8.0) {
                        observedDroneUnloadedProgress = true;
                        checks.check("s4.virtual.drone_advances_in_unloaded_chunks", true,
                                "x=" + String.format("%.1f->%.1f", lastDroneUnloadedX, dx) + " chunk=(" + dcx + "," + dcz + ")");
                    }
                }
            }
        }

        // Wait until BOTH arrive at their still-unloaded distant targets (ARRIVED_UNLOADING)
        if (boat.phase() == TransitPhase.ARRIVED_UNLOADING && drone.phase() == TransitPhase.ARRIVED_UNLOADING) {
            checks.check("s4.virtual.observed_boat_unloaded_transit", observedBoatUnloadedProgress);
            checks.check("s4.virtual.observed_drone_unloaded_transit", observedDroneUnloadedProgress);
            checks.check("s4.virtual.queued_pending_arrivals_while_target_unloaded",
                    !Lc2Native.isChunkEntityTicking(level, DISTANT_PORT_CX, DISTANT_PORT_CZ)
                            && !Lc2Native.isChunkEntityTicking(level, DISTANT_STATION_CX, DISTANT_STATION_CZ)
                            && !engine.pendingArrivalsAt(LogisticsSavedData.toPos3i(DISTANT_PORT)).isEmpty()
                            && !engine.pendingArrivalsAt(LogisticsSavedData.toPos3i(DISTANT_STATION)).isEmpty(),
                    "both queued cleanly in ARRIVED_UNLOADING");

            // Now load distant target chunks so both unload and complete!
            level.setChunkForced(DISTANT_PORT_CX, DISTANT_PORT_CZ, true);
            level.setChunkForced(DISTANT_STATION_CX, DISTANT_STATION_CZ, true);
            level.getChunk(DISTANT_PORT_CX, DISTANT_PORT_CZ);
            level.getChunk(DISTANT_STATION_CX, DISTANT_STATION_CZ);

            stage = Stage.WAIT_TARGET_DELIVERY;
            stageStartTick = tick;
        }
    }

    private void waitTargetDelivery(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 100) {
            throw new AssertionError("Timeout waiting for distant target unload after loading chunks");
        }
        var engine = LogisticsLevelManager.getData(level).engine();
        if (engine.getTransit(boatTransitId).isPresent() || engine.getTransit(droneTransitId).isPresent()) {
            return;
        }

        SmartPortBlockEntity distantPort = (SmartPortBlockEntity) level.getBlockEntity(DISTANT_PORT);
        CargoStationBlockEntity distantStation = (CargoStationBlockEntity) level.getBlockEntity(DISTANT_STATION);
        Map<String, Integer> boatDelivered = QaChecks.countStorageItems(distantPort);
        Map<String, Integer> droneDelivered = QaChecks.countStorageItems(distantStation);
        checks.check("s4.delivery.distant_boat_100pct_cargo_in_storage",
                expectedBoatCargo.equals(boatDelivered)
                        && QaChecks.countOutboundItems(distantPort).isEmpty()
                        && QaChecks.countItemsAcross(level, DISTANT_PORT_CHEST).isEmpty(),
                "expected=" + expectedBoatCargo.size() + ", storage=" + boatDelivered.size());
        checks.check("s4.delivery.distant_drone_100pct_cargo_in_storage",
                expectedDroneCargo.equals(droneDelivered)
                        && QaChecks.countOutboundItems(distantStation).isEmpty()
                        && QaChecks.countItemsAcross(level, DISTANT_STATION_CHEST).isEmpty(),
                "expected=" + expectedDroneCargo.size() + ", storage=" + droneDelivered.size());
        checks.check("s4.delivery.distant_vehicles_docked",
                distantPort.findDockedBoat(level).isPresent() && distantStation.countReadyDrones() == 1);

        // Clean up forced chunks
        level.setChunkForced(DISTANT_PORT_CX, DISTANT_PORT_CZ, false);
        level.setChunkForced(DISTANT_STATION_CX, DISTANT_STATION_CZ, false);
        QaChecks.setChunkRangeForced(level, 0, 2, 4, 5, false);
        stage = Stage.DONE;
    }
}
