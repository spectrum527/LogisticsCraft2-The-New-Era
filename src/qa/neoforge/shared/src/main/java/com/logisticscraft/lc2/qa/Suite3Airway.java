package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Suite 3: Full Airway Lifecycle (Cargo Station + Standard 12-slot & Heavy 36-slot Drones + RF Chips).
 * Covers:
 * - Missing RF chip -> NO_RF_CHIP
 * - rf_chip_1 (256m) rejects distance 300 (OUT_OF_RANGE)
 * - rf_chip_2 (512m) accepts 300 and charges 2000/4000 EU and 2/4 batteries (rejects 600)
 * - rf_chip_3 (1024m) accepts 600 and charges 7000/14000 EU and 5/10 batteries
 * - Standard Drone carries max 12 stacks (faster) vs Heavy Drone carries 36 stacks (slower)
 * - 6-bay limit (TARGET_BAY_FULL) + atomic simultaneous launch onto last free bay (1 ok, 1 rejected)
 * - Auto-return target-side payment: arrives at unpowered target -> unloads -> WAITING_RETURN_ENERGY
 *   -> supply EU at target -> automatically pays at target and flies back to origin
 */
public final class Suite3Airway {

    private static final BlockPos ST_ORIGIN = new BlockPos(10, 64, 42);
    private static final BlockPos ST_NEAR = new BlockPos(42, 64, 42);   // dist = 32 (<= 256)
    private static final BlockPos ST_MID = new BlockPos(310, 64, 42);   // dist = 300 (256 < d <= 512)
    private static final BlockPos ST_FAR = new BlockPos(610, 64, 42);   // dist = 600 (512 < d <= 1024)

    private static final BlockPos ORIGIN_CHEST_1 = ST_ORIGIN.west();
    private static final BlockPos ORIGIN_CHEST_2 = ST_ORIGIN.north();
    private static final BlockPos NEAR_CHEST_1 = ST_NEAR.east();
    private static final BlockPos NEAR_CHEST_2 = ST_NEAR.north();

    private enum Stage {
        PLACE_STATIONS,
        SETUP_AND_PRICING,
        WAIT_STD_AND_HEAVY,
        WAIT_AUTORETURN_WAITING,
        WAIT_AUTORETURN_HOME,
        DONE
    }

    private Stage stage = Stage.PLACE_STATIONS;
    private int stageStartTick = 0;

    private UUID stdTransitId;
    private UUID heavyTransitId;
    private int stdLaunchTick;
    private int heavyLaunchTick;
    private int stdFinishTick = -1;
    private int heavyFinishTick = -1;
    private final Map<String, Integer> expectedStdHeavyCargo = new TreeMap<>();

    private UUID autoReturnTransitId;
    private int waitingObservedTick = -1;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case PLACE_STATIONS -> {
                placeStations(level, tick);
                yield false;
            }
            case SETUP_AND_PRICING -> {
                if (tick - stageStartTick < 2) {
                    yield false;
                }
                runPricingAndBayChecks(level, tick, checks);
                yield false;
            }
            case WAIT_STD_AND_HEAVY -> {
                tickStdAndHeavy(level, tick, checks);
                yield false;
            }
            case WAIT_AUTORETURN_WAITING -> {
                tickWaitAutoReturnEnergy(level, tick, checks);
                yield false;
            }
            case WAIT_AUTORETURN_HOME -> {
                tickWaitAutoReturnHome(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void placeStations(ServerLevel level, int tick) {
        // Temporarily force-load mid & far station chunks so BlockEntity.onLoad() runs cleanly
        level.setChunkForced(ST_MID.getX() >> 4, ST_MID.getZ() >> 4, true);
        level.setChunkForced(ST_FAR.getX() >> 4, ST_FAR.getZ() >> 4, true);
        level.getChunk(ST_MID.getX() >> 4, ST_MID.getZ() >> 4);
        level.getChunk(ST_FAR.getX() >> 4, ST_FAR.getZ() >> 4);

        var stationBlock = QaChecks.block("lc2:cargo_station").defaultBlockState();
        level.setBlockAndUpdate(ST_ORIGIN, stationBlock);
        level.setBlockAndUpdate(ST_NEAR, stationBlock);
        level.setBlockAndUpdate(ST_MID, stationBlock);
        level.setBlockAndUpdate(ST_FAR, stationBlock);

        level.setBlockAndUpdate(ORIGIN_CHEST_1, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(ORIGIN_CHEST_2, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(NEAR_CHEST_1, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(NEAR_CHEST_2, Blocks.CHEST.defaultBlockState());

        stage = Stage.SETUP_AND_PRICING;
        stageStartTick = tick;
    }

    private void runPricingAndBayChecks(ServerLevel level, int tick, QaChecks checks) {
        CargoStationBlockEntity origin = (CargoStationBlockEntity) level.getBlockEntity(ST_ORIGIN);
        CargoStationBlockEntity near = (CargoStationBlockEntity) level.getBlockEntity(ST_NEAR);
        CargoStationBlockEntity mid = (CargoStationBlockEntity) level.getBlockEntity(ST_MID);
        CargoStationBlockEntity far = (CargoStationBlockEntity) level.getBlockEntity(ST_FAR);
        checks.check("s3.setup.stations_created",
                origin != null && near != null && mid != null && far != null);
        origin.setStationName("Air Origin");
        near.setStationName("Air Near");
        mid.setStationName("Air Mid 300m");
        far.setStationName("Air Far 600m");

        // 1. Missing RF chip -> NO_RF_CHIP
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        origin.setTargetPos(ST_NEAR);
        origin.addEnergy(5000.0);
        VirtualTransitEngine.DispatchResult noChip = origin.tryDispatch(level, true);
        checks.check("s3.rf.missing_chip_blocked",
                !noChip.isSuccess() && noChip.failure() == DispatchFailure.NO_RF_CHIP,
                "failure=" + noChip.failure());

        // 2. rf_chip_1 (256m) rejects distance 300 -> OUT_OF_RANGE
        origin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT, new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID)));
        origin.setTargetPos(ST_MID);
        VirtualTransitEngine.DispatchResult chip1Range = origin.tryDispatch(level, true);
        checks.check("s3.rf.chip1_rejects_300m",
                !chip1Range.isSuccess() && chip1Range.failure() == DispatchFailure.OUT_OF_RF_RANGE,
                "failure=" + chip1Range.failure());

        // 3. rf_chip_2 (512m) accepts 300m -> exact 2000/4000 EU & 2/4 batteries, rejects 600m!
        origin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT, new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_2_ID)));
        origin.setTargetPos(ST_FAR);
        VirtualTransitEngine.DispatchResult chip2Range = origin.tryDispatch(level, true);
        checks.check("s3.rf.chip2_rejects_600m",
                !chip2Range.isSuccess() && chip2Range.failure() == DispatchFailure.OUT_OF_RF_RANGE,
                "failure=" + chip2Range.failure());

        origin.setTargetPos(ST_MID);
        origin.setAutoReturn(false);

        // Band 2 Standard EU (2000 EU)
        drainAllEuAndBatteries(origin);
        origin.addEnergy(3000.0);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        var r2StdEu = origin.tryDispatch(level, true);
        checks.check("s3.cost.band2_standard_eu_2000",
                r2StdEu.isSuccess() && Math.abs(origin.getStoredEu() - 1000.0) < 1e-6,
                "leftEu=" + origin.getStoredEu());

        // Band 2 Standard Batteries (2 batteries, EU untouched)
        origin.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        origin.setItem(CargoStationBlockEntity.BATTERY_SLOT, batteryStack(5));
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        var r2StdBat = origin.tryDispatch(level, true);
        checks.check("s3.cost.band2_standard_bat_2",
                r2StdBat.isSuccess() && origin.availableSingleUseBatteries() == 3
                        && Math.abs(origin.getStoredEu() - 1000.0) < 1e-6,
                "leftBat=" + origin.availableSingleUseBatteries() + ", leftEu=" + origin.getStoredEu());

        // Band 2 Heavy EU (4000 EU)
        drainAllEuAndBatteries(origin);
        origin.addEnergy(5000.0);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(true));
        var r2HeavyEu = origin.tryDispatch(level, true);
        checks.check("s3.cost.band2_heavy_eu_4000",
                r2HeavyEu.isSuccess() && Math.abs(origin.getStoredEu() - 1000.0) < 1e-6,
                "leftEu=" + origin.getStoredEu());

        // Band 2 Heavy Batteries (4 batteries)
        origin.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        origin.setItem(CargoStationBlockEntity.BATTERY_SLOT, batteryStack(6));
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(true));
        var r2HeavyBat = origin.tryDispatch(level, true);
        checks.check("s3.cost.band2_heavy_bat_4",
                r2HeavyBat.isSuccess() && origin.availableSingleUseBatteries() == 2,
                "leftBat=" + origin.availableSingleUseBatteries());

        // Cancel the 4 pricing test flights to Mid so they don't keep ticking across the world
        for (var r : java.util.List.of(r2StdEu, r2StdBat, r2HeavyEu, r2HeavyBat)) {
            LogisticsLevelManager.cancelAndTakeCargo(level, r.transit().id());
        }

        // 4. rf_chip_3 (1024m) accepts 600m -> exact 7000/14000 EU & 5/10 batteries!
        origin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT, new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_3_ID)));
        origin.setTargetPos(ST_FAR);

        // Band 3 Standard EU (7000 EU)
        drainAllEuAndBatteries(origin);
        origin.addEnergy(8000.0);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        var r3StdEu = origin.tryDispatch(level, true);
        checks.check("s3.cost.band3_standard_eu_7000",
                r3StdEu.isSuccess() && Math.abs(origin.getStoredEu() - 1000.0) < 1e-6,
                "leftEu=" + origin.getStoredEu());

        // Band 3 Standard Batteries (5 batteries)
        origin.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        origin.setItem(CargoStationBlockEntity.BATTERY_SLOT, batteryStack(7));
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        var r3StdBat = origin.tryDispatch(level, true);
        checks.check("s3.cost.band3_standard_bat_5",
                r3StdBat.isSuccess() && origin.availableSingleUseBatteries() == 2,
                "leftBat=" + origin.availableSingleUseBatteries());

        // Band 3 Heavy EU (14000 EU)
        drainAllEuAndBatteries(origin);
        origin.addEnergy(14_400.0);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(true));
        var r3HeavyEu = origin.tryDispatch(level, true);
        checks.check("s3.cost.band3_heavy_eu_14000",
                r3HeavyEu.isSuccess() && Math.abs(origin.getStoredEu() - 400.0) < 1e-6,
                "leftEu=" + origin.getStoredEu());

        // Band 3 Heavy Batteries (10 batteries)
        origin.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        origin.setItem(CargoStationBlockEntity.BATTERY_SLOT, batteryStack(12));
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(true));
        var r3HeavyBat = origin.tryDispatch(level, true);
        checks.check("s3.cost.band3_heavy_bat_10",
                r3HeavyBat.isSuccess() && origin.availableSingleUseBatteries() == 2,
                "leftBat=" + origin.availableSingleUseBatteries());

        for (var r : java.util.List.of(r3StdEu, r3StdBat, r3HeavyEu, r3HeavyBat)) {
            LogisticsLevelManager.cancelAndTakeCargo(level, r.transit().id());
        }

        // Remove temporary Mid/Far stations and unforce their chunks cleanly
        level.setBlockAndUpdate(ST_MID, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(ST_FAR, Blocks.AIR.defaultBlockState());
        level.setChunkForced(ST_MID.getX() >> 4, ST_MID.getZ() >> 4, false);
        level.setChunkForced(ST_FAR.getX() >> 4, ST_FAR.getZ() >> 4, false);

        // 5. 6-Bay limit & atomic simultaneous launch onto last free bay
        origin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT, new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID)));
        origin.setTargetPos(ST_NEAR);
        drainAllEuAndBatteries(origin);
        origin.addEnergy(10_000.0);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);

        for (int b = 0; b < 6; b++) {
            near.setItem(CargoStationBlockEntity.DRONE_BAY_START + b, droneStack(false));
        }
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1, droneStack(false));
        var fullRes = origin.tryDispatch(level, true);
        checks.check("s3.bays.full_station_rejected",
                !fullRes.isSuccess() && fullRes.failure() == DispatchFailure.TARGET_BAY_FULL,
                "failure=" + fullRes.failure());

        // Free EXACTLY 1 bay on target station -> launch 2 simultaneously -> 1 succeeds, 1 rejected atomically!
        near.setItem(CargoStationBlockEntity.DRONE_BAY_START + 5, ItemStack.EMPTY);
        var atomic1 = origin.tryDispatch(level, true);
        var atomic2 = origin.tryDispatch(level, true);
        checks.check("s3.bays.atomic_last_bay_allows_1_rejects_2nd",
                atomic1.isSuccess() && !atomic2.isSuccess() && atomic2.failure() == DispatchFailure.TARGET_BAY_FULL,
                "r1=" + atomic1.isSuccess() + ", r2Failure=" + atomic2.failure());

        // Clean up atomic test transit & empty target bays for Standard vs Heavy test
        LogisticsLevelManager.cancelAndTakeCargo(level, atomic1.transit().id());
        for (int b = 0; b < 6; b++) {
            near.setItem(CargoStationBlockEntity.DRONE_BAY_START + b, ItemStack.EMPTY);
            origin.setItem(CargoStationBlockEntity.DRONE_BAY_START + b, ItemStack.EMPTY);
        }

        // 6. Standard Drone (12 stacks, faster) vs Heavy Drone (36 stacks, slower)
        // Put 48 distinct stacks? Wait: we have 45 distinct items, so put 12 distinct stacks in ORIGIN_CHEST_1 (slot 0..11)
        // and 36 more stacks across ORIGIN_CHEST_1 (slot 12..26 = 15) + ORIGIN_CHEST_2 (slot 0..20 = 21) = 48 total stacks!
        // Plus 4 extra stacks so we prove Heavy Drone caps at EXACTLY 36 stacks (total 52 stacks = 12 + 36 + 4 leftover!)
        expectedStdHeavyCargo.clear();
        for (int i = 0; i < 45; i++) {
            BlockPos chest = i < 27 ? ORIGIN_CHEST_1 : ORIGIN_CHEST_2;
            QaChecks.insertOrFail(level, chest, new ItemStack(QaChecks.DISTINCT_CARGO_ITEMS.get(i), 4));
        }
        // Add 7 more distinct items (total 52 stacks: 27 in CHEST_1 + 25 in CHEST_2)
        var extraItems = java.util.List.of(
                Items.APPLE, Items.BREAD, Items.CARROT, Items.POTATO,
                Items.WHEAT, Items.SUGAR, Items.EGG);
        for (var it : extraItems) {
            QaChecks.insertOrFail(level, ORIGIN_CHEST_2, new ItemStack(it, 4));
        }
        checks.check("s3.cargo.52_stacks_staged",
                QaChecks.countStacksAcross(level, ORIGIN_CHEST_1, ORIGIN_CHEST_2) == 52);

        // Strict 2-zone semantics: install pulling_upgrade on origin & ejector_upgrade on target for >27-slot 48-stack transfer
        origin.setItem(CargoStationBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_PULLING), 1));
        near.setItem(CargoStationBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_EJECTOR), 1));

        // Launch Standard Drone first (bay 0) -> MUST carry exactly 12 stacks!
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));
        var stdRes = origin.tryDispatch(level, true);
        checks.check("s3.drone.standard_launched", stdRes.isSuccess());
        stdTransitId = stdRes.transit().id();
        stdLaunchTick = tick;
        int stdCargoCount = LogisticsLevelManager.getData(level).getCargo(stdTransitId).size();
        for (ItemStack s : LogisticsLevelManager.getData(level).getCargo(stdTransitId)) {
            expectedStdHeavyCargo.merge(Lc2Native.itemId(s), s.getCount(), Integer::sum);
        }
        checks.check("s3.drone.standard_carries_max_12_stacks",
                stdCargoCount == 12 && QaChecks.countStacksAcross(level, ORIGIN_CHEST_1, ORIGIN_CHEST_2) == 40,
                "stdCargo=" + stdCargoCount);

        // Launch Heavy Drone immediately in the same tick (bay 1) -> MUST carry exactly 36 stacks (leaving 4)!
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1, droneStack(true));
        var heavyRes = origin.tryDispatch(level, true);
        checks.check("s3.drone.heavy_launched", heavyRes.isSuccess());
        heavyTransitId = heavyRes.transit().id();
        heavyLaunchTick = tick;
        int heavyCargoCount = LogisticsLevelManager.getData(level).getCargo(heavyTransitId).size();
        for (ItemStack s : LogisticsLevelManager.getData(level).getCargo(heavyTransitId)) {
            expectedStdHeavyCargo.merge(Lc2Native.itemId(s), s.getCount(), Integer::sum);
        }
        checks.check("s3.drone.heavy_carries_max_36_stacks",
                heavyCargoCount == 36 && QaChecks.countStacksAcross(level, ORIGIN_CHEST_1, ORIGIN_CHEST_2) == 4,
                "heavyCargo=" + heavyCargoCount);

        stage = Stage.WAIT_STD_AND_HEAVY;
        stageStartTick = tick;
    }

    private void tickStdAndHeavy(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 300) {
            throw new AssertionError("Timeout waiting for Standard & Heavy drones to arrive at ST_NEAR");
        }
        var engine = LogisticsLevelManager.getData(level).engine();
        if (stdFinishTick < 0 && engine.getTransit(stdTransitId).isEmpty()) {
            stdFinishTick = tick;
        }
        if (heavyFinishTick < 0 && engine.getTransit(heavyTransitId).isEmpty()) {
            heavyFinishTick = tick;
        }
        if (stdFinishTick < 0 || heavyFinishTick < 0) {
            return;
        }

        int stdDur = stdFinishTick - stdLaunchTick;
        int heavyDur = heavyFinishTick - heavyLaunchTick;
        checks.check("s3.drone.standard_faster_than_heavy",
                stdDur < heavyDur,
                "stdTicks=" + stdDur + ", heavyTicks=" + heavyDur);

        Map<String, Integer> delivered = QaChecks.countItemsAcross(level, NEAR_CHEST_1, NEAR_CHEST_2);
        checks.check("s3.drone.48_stacks_delivered_zero_loss",
                expectedStdHeavyCargo.equals(delivered),
                "expectedKinds=" + expectedStdHeavyCargo.size() + ", deliveredKinds=" + delivered.size());

        // 7. Auto-Return Target-Side Payment (WAITING_RETURN_ENERGY -> supply target -> returns home!)
        CargoStationBlockEntity origin = (CargoStationBlockEntity) level.getBlockEntity(ST_ORIGIN);
        CargoStationBlockEntity near = (CargoStationBlockEntity) level.getBlockEntity(ST_NEAR);
        // Clear target bays, upgrades, chests, and ALL energy/batteries at target so Stage 7 verifies direct Storage (27..53) unload
        origin.setItem(CargoStationBlockEntity.UPGRADE_START, ItemStack.EMPTY);
        near.setItem(CargoStationBlockEntity.UPGRADE_START, ItemStack.EMPTY);
        for (int b = 0; b < 6; b++) {
            near.setItem(CargoStationBlockEntity.DRONE_BAY_START + b, ItemStack.EMPTY);
            origin.setItem(CargoStationBlockEntity.DRONE_BAY_START + b, ItemStack.EMPTY);
        }
        for (BlockPos chestPos : java.util.List.of(NEAR_CHEST_1, NEAR_CHEST_2, ORIGIN_CHEST_1, ORIGIN_CHEST_2)) {
            level.setBlockAndUpdate(chestPos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        }
        drainAllEuAndBatteries(near);
        near.setEnergyPriority(EnergyPriority.EU_FIRST);

        // Put 2 specific stacks directly in origin buffer
        origin.setItem(0, new ItemStack(Items.NETHERITE_INGOT, 3));
        origin.setItem(1, new ItemStack(Items.DIAMOND_BLOCK, 5));

        drainAllEuAndBatteries(origin);
        origin.addEnergy(1000.0); // Only enough for outbound 800 EU!
        origin.setAutoReturn(true);
        origin.setEnergyPriority(EnergyPriority.EU_FIRST);
        origin.setTargetPos(ST_NEAR);
        origin.setItem(CargoStationBlockEntity.DRONE_BAY_START, droneStack(false));

        var retLaunch = origin.tryDispatch(level, true);
        checks.check("s3.autoreturn.outbound_pays_only_800_eu",
                retLaunch.isSuccess() && Math.abs(origin.getStoredEu() - 200.0) < 1e-6,
                "originEuLeft=" + origin.getStoredEu());
        autoReturnTransitId = retLaunch.transit().id();
        waitingObservedTick = -1;
        stage = Stage.WAIT_AUTORETURN_WAITING;
        stageStartTick = tick;
    }

    private void tickWaitAutoReturnEnergy(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 250) {
            throw new AssertionError("Timeout waiting for drone to reach WAITING_RETURN_ENERGY at ST_NEAR");
        }
        var recOpt = LogisticsLevelManager.getData(level).engine().getTransit(autoReturnTransitId);
        if (recOpt.isEmpty()) {
            throw new AssertionError("Auto-return drone unexpectedly completed before target had energy!");
        }
        TransitRecord rec = recOpt.get();
        if (rec.phase() != TransitPhase.WAITING_RETURN_ENERGY) {
            return;
        }
        CargoStationBlockEntity near = (CargoStationBlockEntity) level.getBlockEntity(ST_NEAR);
        if (waitingObservedTick < 0) {
            waitingObservedTick = tick;
            Map<String, Integer> unloadedStorage = QaChecks.countStorageItems(near);
            Map<String, Integer> nearOutbound = QaChecks.countOutboundItems(near);
            Map<String, Integer> nearChests = QaChecks.countItemsAcross(level, NEAR_CHEST_1, NEAR_CHEST_2);
            checks.check("s3.autoreturn.unloaded_to_storage_and_waiting_in_bay",
                    unloadedStorage.getOrDefault("minecraft:netherite_ingot", 0) == 3
                            && unloadedStorage.getOrDefault("minecraft:diamond_block", 0) == 5
                            && nearOutbound.isEmpty()
                            && nearChests.isEmpty()
                            && near.getClientBayKind(rec.targetBay()) == CargoStationBlockEntity.BAY_STANDARD,
                    "storage=" + unloadedStorage + ", outbound=" + nearOutbound
                            + ", chests=" + nearChests + ", bayKind=" + near.getClientBayKind(rec.targetBay()));
            return;
        }
        // Hold for 10 ticks with 0 EU to prove it genuinely waits until Target has energy
        if (tick - waitingObservedTick < 10) {
            return;
        }
        checks.check("s3.autoreturn.stays_waiting_without_target_energy",
                rec.phase() == TransitPhase.WAITING_RETURN_ENERGY);

        // Supply Target Station with 1000 EU (return cost = 800 EU -> 200 EU left!)
        near.addEnergy(1000.0);
        stage = Stage.WAIT_AUTORETURN_HOME;
        stageStartTick = tick;
    }

    private void tickWaitAutoReturnHome(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 250) {
            throw new AssertionError("Timeout waiting for drone to return home after Target received return EU");
        }
        var engine = LogisticsLevelManager.getData(level).engine();
        if (engine.getTransit(autoReturnTransitId).isPresent()) {
            return;
        }
        CargoStationBlockEntity origin = (CargoStationBlockEntity) level.getBlockEntity(ST_ORIGIN);
        CargoStationBlockEntity near = (CargoStationBlockEntity) level.getBlockEntity(ST_NEAR);
        checks.check("s3.autoreturn.target_paid_800_eu_and_drone_returned_home",
                Math.abs(near.getStoredEu() - 200.0) < 1e-6 && origin.countReadyDrones() == 1,
                "targetEuLeft=" + near.getStoredEu() + ", originReadyDrones=" + origin.countReadyDrones());
        stage = Stage.DONE;
    }

    private static ItemStack droneStack(boolean heavy) {
        return new ItemStack(QaChecks.item(
                heavy ? LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID : LogisticsConstants.TRANSPORT_DRONE_ID), 1);
    }

    private static ItemStack batteryStack(int count) {
        return new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), count);
    }

    private static void drainAllEuAndBatteries(CargoStationBlockEntity station) {
        station.setItem(CargoStationBlockEntity.BATTERY_SLOT, ItemStack.EMPTY);
        if (station.getStoredEu() > 0.0) {
            station.applyPayment(com.logisticscraft.lc2.core.PaymentPlan.eu(station.getStoredEu()));
        }
    }
}
