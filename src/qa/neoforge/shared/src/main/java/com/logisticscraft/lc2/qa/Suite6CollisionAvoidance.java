package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 6: Multi-Vehicle Collision Avoidance.
 * Simultaneously dispatches:
 * - Opposing Electric Cargo Boats on a shared 3-wide waterway (A->B and B->A)
 * - 4 concurrent opposing Drones (2 A->B and 2 B->A) between Cargo Stations
 * Verifies lateral starboard separation / stratified altitude separation, zero overlap, zero deadlock,
 * and 100% delivery of all opposing cargos.
 */
public final class Suite6CollisionAvoidance {

    private static final int WATER_Y = 63;
    private static final BlockPos PORT_WEST = new BlockPos(10, WATER_Y, -32);
    private static final BlockPos PORT_EAST = new BlockPos(40, WATER_Y, -32);
    private static final BlockPos PORT_WEST_CHEST = PORT_WEST.west();
    private static final BlockPos PORT_EAST_CHEST = PORT_EAST.east();

    private static final BlockPos AIR_WEST = new BlockPos(10, 64, -24);
    private static final BlockPos AIR_EAST = new BlockPos(40, 64, -24);
    private static final BlockPos AIR_WEST_CHEST = AIR_WEST.west();
    private static final BlockPos AIR_EAST_CHEST = AIR_EAST.east();

    private enum Stage {
        PLACE_STATIONS,
        SETUP_AND_LAUNCH,
        TRACK_COLLISION_AVOIDANCE,
        DONE
    }

    private Stage stage = Stage.PLACE_STATIONS;
    private int startTick = 0;

    private UUID boatWestToEast;
    private UUID boatEastToWest;
    private final List<UUID> droneIds = new ArrayList<>();

    private double minBoatDistance = Double.MAX_VALUE;
    private boolean boatsPassedEachOther = false;
    private double minOpposingDroneCruiseDist = Double.MAX_VALUE;
    private boolean dronesPassedEachOther = false;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case PLACE_STATIONS -> {
                placeStations(level, tick);
                yield false;
            }
            case SETUP_AND_LAUNCH -> {
                if (tick - startTick < 2) {
                    yield false;
                }
                setupAndLaunch(level, tick, checks);
                yield false;
            }
            case TRACK_COLLISION_AVOIDANCE -> {
                trackAvoidance(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void placeStations(ServerLevel level, int tick) {
        QaChecks.setChunkRangeForced(level, 0, 3, -3, -1, true);

        // Shared 3-wide waterway X=11..39, Z=-33..-31
        QaChecks.buildCanal(level, 11, 39, -33, -31, WATER_Y);
        level.setBlockAndUpdate(PORT_WEST,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(PORT_EAST,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(PORT_WEST_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(PORT_EAST_CHEST, Blocks.CHEST.defaultBlockState());

        // Opposing Cargo Stations
        var stationState = QaChecks.block("lc2:cargo_station").defaultBlockState();
        level.setBlockAndUpdate(AIR_WEST, stationState);
        level.setBlockAndUpdate(AIR_EAST, stationState);
        level.setBlockAndUpdate(AIR_WEST_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(AIR_EAST_CHEST, Blocks.CHEST.defaultBlockState());

        startTick = tick;
        stage = Stage.SETUP_AND_LAUNCH;
    }

    private void setupAndLaunch(ServerLevel level, int tick, QaChecks checks) {
        SmartPortBlockEntity portW = (SmartPortBlockEntity) level.getBlockEntity(PORT_WEST);
        SmartPortBlockEntity portE = (SmartPortBlockEntity) level.getBlockEntity(PORT_EAST);
        portW.refreshWaterBerth(level);
        portE.refreshWaterBerth(level);
        var player = FakePlayerFactory.getMinecraft(level);
        checks.check("s6.boats.both_docked",
                portW.spawnAndDockBoat(level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get()))
                        && portE.spawnAndDockBoat(level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())));

        portW.setTargetPos(PORT_EAST);
        portE.setTargetPos(PORT_WEST);
        portW.setAutoReturn(false);
        portE.setAutoReturn(false);
        portW.setEnergyPriority(EnergyPriority.EU_FIRST);
        portE.setEnergyPriority(EnergyPriority.EU_FIRST);
        portW.addEnergy(5000.0);
        portE.addEnergy(5000.0);
        portW.setItem(0, new ItemStack(Items.IRON_BLOCK, 10));
        portE.setItem(0, new ItemStack(Items.GOLD_BLOCK, 10));

        CargoStationBlockEntity airW = (CargoStationBlockEntity) level.getBlockEntity(AIR_WEST);
        CargoStationBlockEntity airE = (CargoStationBlockEntity) level.getBlockEntity(AIR_EAST);
        for (CargoStationBlockEntity st : List.of(airW, airE)) {
            st.setItem(CargoStationBlockEntity.RF_CHIP_SLOT,
                    new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID), 1));
            st.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                    new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
            st.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1,
                    new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
            st.setAutoReturn(false);
            st.setEnergyPriority(EnergyPriority.EU_FIRST);
            st.addEnergy(5000.0);
        }
        airW.setTargetPos(AIR_EAST);
        airE.setTargetPos(AIR_WEST);
        airW.setItem(0, new ItemStack(Items.REDSTONE_BLOCK, 16));
        airE.setItem(0, new ItemStack(Items.LAPIS_BLOCK, 16));

        // Simultaneously dispatch opposing boats AND 4 opposing drones in the EXACT same tick!
        var bw = portW.tryDispatch(level, true);
        var be = portE.tryDispatch(level, true);
        checks.check("s6.boats.opposing_launched_simultaneously", bw.isSuccess() && be.isSuccess());
        boatWestToEast = bw.transit().id();
        boatEastToWest = be.transit().id();

        droneIds.clear();
        var dw1 = airW.tryDispatch(level, true);
        var de1 = airE.tryDispatch(level, true);
        var dw2 = airW.tryDispatch(level, true);
        var de2 = airE.tryDispatch(level, true);
        checks.check("s6.drones.4_opposing_launched_simultaneously",
                dw1.isSuccess() && de1.isSuccess() && dw2.isSuccess() && de2.isSuccess());
        droneIds.add(dw1.transit().id());
        droneIds.add(de1.transit().id());
        droneIds.add(dw2.transit().id());
        droneIds.add(de2.transit().id());

        // Verify opposing drones were assigned strictly distinct cruise altitudes!
        double yW1 = dw1.transit().waypoints().get(1).y();
        double yE1 = de1.transit().waypoints().get(1).y();
        double yW2 = dw2.transit().waypoints().get(1).y();
        double yE2 = de2.transit().waypoints().get(1).y();
        checks.check("s6.drones.distinct_stratified_cruise_altitudes",
                Math.abs(yW1 - yE1) >= 1.0 && Math.abs(yW2 - yE2) >= 1.0
                        && Math.abs(yW1 - yW2) >= 1.0 && Math.abs(yE1 - yE2) >= 1.0,
                String.format("yW1=%.1f yE1=%.1f yW2=%.1f yE2=%.1f", yW1, yE1, yW2, yE2));

        startTick = tick;
        stage = Stage.TRACK_COLLISION_AVOIDANCE;
    }

    private void trackAvoidance(ServerLevel level, int tick, QaChecks checks) {
        if (tick - startTick > 250) {
            throw new AssertionError("Timeout/deadlock in Suite 6 collision avoidance!");
        }
        var engine = LogisticsLevelManager.getData(level).engine();

        // Monitor opposing boats separation
        TransitRecord bw = engine.getTransit(boatWestToEast).orElse(null);
        TransitRecord be = engine.getTransit(boatEastToWest).orElse(null);
        if (bw != null && be != null) {
            double dist = bw.currentPos().horizontalDistanceTo(be.currentPos());
            minBoatDistance = Math.min(minBoatDistance, dist);
            if (bw.currentPos().x() > be.currentPos().x()) {
                boatsPassedEachOther = true;
            }
        }

        // Monitor opposing drones 3D separation during horizontal cruise
        List<TransitRecord> activeDrones = new ArrayList<>();
        for (UUID id : droneIds) {
            engine.getTransit(id).ifPresent(activeDrones::add);
        }
        for (int i = 0; i < activeDrones.size(); i++) {
            for (int j = i + 1; j < activeDrones.size(); j++) {
                TransitRecord a = activeDrones.get(i);
                TransitRecord b = activeDrones.get(j);
                boolean opposite = !a.originPos().equals(b.originPos());
                if (opposite && a.currentPos().x() > 14.0 && a.currentPos().x() < 36.0
                        && b.currentPos().x() > 14.0 && b.currentPos().x() < 36.0) {
                    double d3 = a.currentPos().distanceTo(b.currentPos());
                    minOpposingDroneCruiseDist = Math.min(minOpposingDroneCruiseDist, d3);
                    if (a.originPos().x() < b.originPos().x() && a.currentPos().x() > b.currentPos().x()) {
                        dronesPassedEachOther = true;
                    }
                }
            }
        }

        boolean allDone = bw == null && be == null && activeDrones.isEmpty();
        if (!allDone) {
            return;
        }

        checks.check("s6.boats.passed_without_overlap_or_deadlock",
                boatsPassedEachOther && minBoatDistance >= 1.0,
                "minBoatDist=" + String.format("%.2f", minBoatDistance));
        checks.check("s6.drones.passed_without_overlap_or_deadlock",
                dronesPassedEachOther && minOpposingDroneCruiseDist >= 2.0,
                "minDroneDist=" + String.format("%.2f", minOpposingDroneCruiseDist));

        // Verify all opposing cargos arrived strictly in opposite stations' Storage (27..53), leaving Outbound (0..26) clean!
        SmartPortBlockEntity portW = (SmartPortBlockEntity) level.getBlockEntity(PORT_WEST);
        SmartPortBlockEntity portE = (SmartPortBlockEntity) level.getBlockEntity(PORT_EAST);
        CargoStationBlockEntity airW = (CargoStationBlockEntity) level.getBlockEntity(AIR_WEST);
        CargoStationBlockEntity airE = (CargoStationBlockEntity) level.getBlockEntity(AIR_EAST);
        var portEastCargo = QaChecks.countStorageItems(portE);
        var portWestCargo = QaChecks.countStorageItems(portW);
        var airEastCargo = QaChecks.countStorageItems(airE);
        var airWestCargo = QaChecks.countStorageItems(airW);
        checks.check("s6.delivery.opposing_boats_cargo_intact_in_storage",
                portEastCargo.getOrDefault("minecraft:iron_block", 0) == 10
                        && portWestCargo.getOrDefault("minecraft:gold_block", 0) == 10
                        && QaChecks.countOutboundItems(portE).isEmpty()
                        && QaChecks.countOutboundItems(portW).isEmpty(),
                "east=" + portEastCargo + ", west=" + portWestCargo);
        checks.check("s6.delivery.opposing_drones_cargo_intact_in_storage",
                airEastCargo.getOrDefault("minecraft:redstone_block", 0) == 16
                        && airWestCargo.getOrDefault("minecraft:lapis_block", 0) == 16
                        && QaChecks.countOutboundItems(airE).isEmpty()
                        && QaChecks.countOutboundItems(airW).isEmpty(),
                "east=" + airEastCargo + ", west=" + airWestCargo);
        checks.check("s6.drones.all_4_parked_at_opposite_stations",
                airW.countReadyDrones() == 2 && airE.countReadyDrones() == 2);

        stage = Stage.DONE;
    }
}
