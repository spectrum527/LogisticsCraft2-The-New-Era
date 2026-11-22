package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 8: Comprehensive v1.1.0 2-Zone Outbound/Storage, Swap, Migration & Items-Before-Vehicle Regression Suite.
 * <ol>
 *   <li>Items first -&gt; Drone second (immediate dispatch on drone placement, arrives in Target Storage 27..53)</li>
 *   <li>Items first -&gt; Boat second (both direct water placement at 2.9m radius and spawnAndDockBoat, immediate dispatch, arrives in Target Storage 27..53)</li>
 *   <li>External Hopper / WorldlyContainer zone isolation (push ONLY Outbound 0..26 + battery; extract ONLY Storage 27..53)</li>
 *   <li>IC2 Pulling / Ejector upgrade zone isolation, strict vehicle loading (NEVER ships Storage; NEVER pulls chests without pulling_upgrade), Outbound-only comparator</li>
 *   <li>Atomic {@code swapCargoZones()}, Outbound-only Menu quick-move, and legacy v1 -&gt; v2 NBT slot migration (0 lost items)</li>
 * </ol>
 */
public final class Suite8TwoZoneAndVehicleFix {

    private static final int WATER_Y = 63;

    // Scenario A: Items first -> Drone second
    private static final BlockPos DRONE_ORIGIN = new BlockPos(68, 64, 10);
    private static final BlockPos DRONE_REDSTONE = DRONE_ORIGIN.north();
    private static final BlockPos DRONE_TARGET = new BlockPos(88, 64, 10);

    // Scenario B: Items first -> Boat second
    private static final BlockPos BOAT_ORIGIN = new BlockPos(68, WATER_Y, 19);
    private static final BlockPos BOAT_REDSTONE = BOAT_ORIGIN.above();
    private static final BlockPos BOAT_TARGET = new BlockPos(88, WATER_Y, 19);

    // Scenario C: Real Vanilla Hopper & WorldlyContainer Zone Isolation
    private static final BlockPos HOPPER_PORT = new BlockPos(68, 66, 30);
    private static final BlockPos HOPPER_PORT_IN = HOPPER_PORT.above();
    private static final BlockPos HOPPER_PORT_OUT = HOPPER_PORT.below();
    private static final BlockPos HOPPER_CARGO = new BlockPos(74, 66, 30);
    private static final BlockPos HOPPER_CARGO_IN = HOPPER_CARGO.above();
    private static final BlockPos HOPPER_CARGO_OUT = HOPPER_CARGO.below();

    // Scenario D: Pulling & Ejector Upgrade Zone Isolation
    private static final BlockPos UPG_STATION = new BlockPos(80, 65, 30);
    private static final BlockPos PULL_CHEST = UPG_STATION.west();
    private static final BlockPos EJECT_CHEST = UPG_STATION.east();

    // Scenario E: Swap + Quick-Move + Legacy v1->v2 NBT Migration
    private static final BlockPos SWAP_PORT = new BlockPos(86, 65, 30);
    private static final BlockPos SWAP_CARGO = new BlockPos(90, 65, 30);

    private enum Stage {
        FORCE_CHUNKS,
        WAIT_CHUNKS_AND_PLACE,
        VERIFY_NO_VEHICLE_THEN_ADD_VEHICLES,
        VERIFY_IMMEDIATE_LAUNCH,
        WAIT_DELIVERIES_AND_HOPPERS,
        TEST_SPAWN_AND_DOCK_SECOND,
        VERIFY_SPAWN_AND_DOCK_IMMEDIATE,
        DONE
    }

    private Stage stage = Stage.FORCE_CHUNKS;
    private int stageStartTick = 0;

    private UUID droneTransitId;
    private UUID boatTransitId;
    private UUID boat2TransitId;
    private int vehicleAddedTick = -1;
    private int boat2AddedTick = -1;
    private int hoppersStartTick = -1;
    private int upgradeStartTick = -1;
    private boolean hoppersChecked = false;
    private boolean upgradesChecked = false;
    private boolean droneDelivered = false;
    private boolean boatDelivered = false;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case FORCE_CHUNKS -> {
                QaChecks.setChunkRangeForced(level, 4, 6, 0, 2, true);
                stage = Stage.WAIT_CHUNKS_AND_PLACE;
                stageStartTick = tick;
                yield false;
            }
            case WAIT_CHUNKS_AND_PLACE -> {
                if (!Lc2Native.isChunkEntityTicking(level, 4, 0)
                        || !Lc2Native.isChunkEntityTicking(level, 5, 1)
                        || !Lc2Native.isChunkEntityTicking(level, 5, 2)) {
                    if (tick - stageStartTick > 500) {
                        throw new AssertionError("Timeout waiting for Suite 8 chunks to become entity-ticking");
                    }
                    yield false;
                }
                setupAllScenarios(level, tick, checks);
                yield false;
            }
            case VERIFY_NO_VEHICLE_THEN_ADD_VEHICLES -> {
                tickVerifyNoVehicleThenAdd(level, tick, checks);
                yield false;
            }
            case VERIFY_IMMEDIATE_LAUNCH -> {
                tickVerifyImmediateLaunch(level, tick, checks);
                yield false;
            }
            case WAIT_DELIVERIES_AND_HOPPERS -> {
                tickDeliveriesHoppersAndUpgrades(level, tick, checks);
                yield false;
            }
            case TEST_SPAWN_AND_DOCK_SECOND -> {
                tickSpawnAndDockSecond(level, tick, checks);
                yield false;
            }
            case VERIFY_SPAWN_AND_DOCK_IMMEDIATE -> {
                tickVerifySpawnAndDockImmediate(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void setupAllScenarios(ServerLevel level, int tick, QaChecks checks) {
        FakePlayer player = FakePlayerFactory.getMinecraft(level);

        // --- Scenario A: Items First -> Drone Second ---
        var cargoState = QaChecks.block("lc2:cargo_station").defaultBlockState();
        level.setBlockAndUpdate(DRONE_ORIGIN, cargoState);
        level.setBlockAndUpdate(DRONE_TARGET, cargoState);

        CargoStationBlockEntity droneOrigin = (CargoStationBlockEntity) level.getBlockEntity(DRONE_ORIGIN);
        CargoStationBlockEntity droneTarget = (CargoStationBlockEntity) level.getBlockEntity(DRONE_TARGET);
        droneOrigin.setStationName("S8 Drone Origin");
        droneTarget.setStationName("S8 Drone Target");
        droneOrigin.setTargetPos(DRONE_TARGET);
        droneOrigin.setAutoReturn(false);
        droneOrigin.setEnergyPriority(EnergyPriority.EU_FIRST);
        droneOrigin.addEnergy(5_000.0);
        droneOrigin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID), 1));
        // Put items into Outbound FIRST while 0 drones are in bays!
        droneOrigin.setItem(0, new ItemStack(Items.DIAMOND, 12));
        droneOrigin.setItem(1, new ItemStack(Items.GOLD_INGOT, 16));
        // Turn constant redstone ON before any drone exists
        level.setBlockAndUpdate(DRONE_REDSTONE, Blocks.REDSTONE_BLOCK.defaultBlockState());

        // --- Scenario B: Items First -> Boat Second ---
        QaChecks.buildCanal(level, 69, 87, 18, 20, WATER_Y);
        level.setBlockAndUpdate(BOAT_ORIGIN,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(BOAT_TARGET,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.WEST));

        SmartPortBlockEntity boatOrigin = (SmartPortBlockEntity) level.getBlockEntity(BOAT_ORIGIN);
        SmartPortBlockEntity boatTarget = (SmartPortBlockEntity) level.getBlockEntity(BOAT_TARGET);
        boatOrigin.setStationName("S8 Boat Origin");
        boatTarget.setStationName("S8 Boat Target");
        boatOrigin.refreshWaterBerth(level);
        boatTarget.refreshWaterBerth(level);
        boatOrigin.setTargetPos(BOAT_TARGET);
        boatOrigin.setAutoReturn(false);
        boatOrigin.setEnergyPriority(EnergyPriority.EU_FIRST);
        boatOrigin.addEnergy(10_000.0);
        // Put items into Outbound FIRST while 0 boats are docked!
        boatOrigin.setItem(0, new ItemStack(Items.EMERALD, 14));
        boatOrigin.setItem(1, new ItemStack(Items.IRON_INGOT, 10));
        // Turn constant redstone ON before any boat exists
        level.setBlockAndUpdate(BOAT_REDSTONE, Blocks.REDSTONE_BLOCK.defaultBlockState());

        // --- Scenario C: External Hopper & WorldlyContainer Zone Isolation ---
        setupHopperPair(level, HOPPER_PORT, HOPPER_PORT_IN, HOPPER_PORT_OUT, true, checks);
        setupHopperPair(level, HOPPER_CARGO, HOPPER_CARGO_IN, HOPPER_CARGO_OUT, false, checks);
        hoppersStartTick = tick;

        // --- Scenario D: Ejector / Pulling Upgrade Isolation + Comparator + Strict Vehicle Loading ---
        level.setBlockAndUpdate(UPG_STATION, cargoState);
        level.setBlockAndUpdate(PULL_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(EJECT_CHEST, Blocks.CHEST.defaultBlockState());
        CargoStationBlockEntity upgStation = (CargoStationBlockEntity) level.getBlockEntity(UPG_STATION);
        ChestBlockEntity pullChest = (ChestBlockEntity) level.getBlockEntity(PULL_CHEST);
        ChestBlockEntity ejectChest = (ChestBlockEntity) level.getBlockEntity(EJECT_CHEST);

        // Stage D1: NO upgrades installed -> Storage item must give comparator=0 AND loadOutgoingCargo must return empty!
        pullChest.setItem(0, new ItemStack(Items.LAPIS_LAZULI, 6));
        upgStation.setItem(CargoStationBlockEntity.STORAGE_START, new ItemStack(Items.REDSTONE, 9));
        int storageOnlyComp = upgStation.computeCargoComparatorSignal();
        List<ItemStack> noPullLoaded = upgStation.loadOutgoingCargo(level, 12);
        checks.check("s8.zone.storage_never_triggers_comparator_or_ships_without_puller",
                storageOnlyComp == 0
                        && noPullLoaded.isEmpty()
                        && QaChecks.countItemsAcross(level, PULL_CHEST).getOrDefault("minecraft:lapis_lazuli", 0) == 6
                        && QaChecks.countStorageItems(upgStation).getOrDefault("minecraft:redstone", 0) == 9,
                "comp=" + storageOnlyComp + ", loaded=" + noPullLoaded.size());

        // Stage D2: Install routed pulling_upgrade (WEST) and routed ejector_upgrade (EAST)
        ItemStack pullUpg = FieldFactoryBuilder.routeUpgrade(
                level, player, UPG_STATION, LogisticsConstants.UPGRADE_PULLING, Direction.WEST, "s8_pull_west", checks);
        ItemStack ejectUpg = FieldFactoryBuilder.routeUpgrade(
                level, player, UPG_STATION, LogisticsConstants.UPGRADE_EJECTOR, Direction.EAST, "s8_eject_east", checks);
        upgStation.setItem(CargoStationBlockEntity.UPGRADE_START, pullUpg);
        upgStation.setItem(CargoStationBlockEntity.UPGRADE_START + 1, ejectUpg);
        upgradeStartTick = tick;

        // --- Scenario E: Atomic Swap + Menu Quick-Move Outbound-Only + Legacy v1->v2 NBT Migration ---
        runSwapQuickMoveAndMigrationChecks(level, player, checks);

        stage = Stage.VERIFY_NO_VEHICLE_THEN_ADD_VEHICLES;
        stageStartTick = tick;
    }

    private void setupHopperPair(
            ServerLevel level,
            BlockPos stationPos,
            BlockPos inHopperPos,
            BlockPos outHopperPos,
            boolean isPort,
            QaChecks checks
    ) {
        String tag = isPort ? "smart_port" : "cargo_station";
        if (isPort) {
            level.setBlockAndUpdate(stationPos,
                    QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.NORTH));
        } else {
            level.setBlockAndUpdate(stationPos, QaChecks.block("lc2:cargo_station").defaultBlockState());
        }
        level.setBlockAndUpdate(inHopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(outHopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));

        AbstractLogisticsStationBlockEntity station =
                (AbstractLogisticsStationBlockEntity) level.getBlockEntity(stationPos);
        HopperBlockEntity inHopper = (HopperBlockEntity) level.getBlockEntity(inHopperPos);

        // Direct WorldlyContainer contract checks
        ItemStack stone = new ItemStack(Items.STONE, 1);
        ItemStack battery = new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 1);
        int batSlot = station.batterySlotIndex();
        int upgSlot = station.upgradeSlotStart();
        boolean faceContractOk = station.getSlotsForFace(Direction.UP).length == AbstractLogisticsStationBlockEntity.TOTAL_CARGO_SLOTS + 1
                && station.canPlaceItemThroughFace(0, stone, Direction.UP)
                && station.canPlaceItemThroughFace(26, stone, Direction.UP)
                && !station.canPlaceItemThroughFace(27, stone, Direction.UP)
                && !station.canPlaceItemThroughFace(53, stone, Direction.UP)
                && station.canPlaceItemThroughFace(batSlot, battery, Direction.UP)
                && !station.canPlaceItemThroughFace(batSlot, stone, Direction.UP)
                && !station.canPlaceItemThroughFace(upgSlot, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 1), Direction.UP)
                && !station.canTakeItemThroughFace(0, stone, Direction.DOWN)
                && !station.canTakeItemThroughFace(26, stone, Direction.DOWN)
                && station.canTakeItemThroughFace(27, stone, Direction.DOWN)
                && station.canTakeItemThroughFace(53, stone, Direction.DOWN)
                && !station.canTakeItemThroughFace(batSlot, battery, Direction.DOWN);
        checks.check("s8.worldly." + tag + "_strict_2zone_contract", faceContractOk);

        // Seed Outbound[0]=5 IRON_BLOCK, Storage[27]=7 GOLD_BLOCK, Battery=3 single_use_battery, InputHopper=8 COPPER_INGOT
        station.setItem(AbstractLogisticsStationBlockEntity.OUTBOUND_START, new ItemStack(Items.IRON_BLOCK, 5));
        station.setItem(AbstractLogisticsStationBlockEntity.STORAGE_START, new ItemStack(Items.GOLD_BLOCK, 7));
        station.setItem(batSlot, new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 3));
        inHopper.setItem(0, new ItemStack(Items.COPPER_INGOT, 8));
    }

    private void runSwapQuickMoveAndMigrationChecks(ServerLevel level, FakePlayer player, QaChecks checks) {
        level.setBlockAndUpdate(SWAP_PORT,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(SWAP_CARGO, QaChecks.block("lc2:cargo_station").defaultBlockState());

        SmartPortBlockEntity port = (SmartPortBlockEntity) level.getBlockEntity(SWAP_PORT);
        CargoStationBlockEntity cargo = (CargoStationBlockEntity) level.getBlockEntity(SWAP_CARGO);

        // 1. Atomic Swap check on SmartPort
        port.setItem(0, new ItemStack(Items.DIAMOND, 10));
        port.setItem(26, new ItemStack(Items.EMERALD, 11));
        port.setItem(27, new ItemStack(Items.COAL, 20));
        port.setItem(53, new ItemStack(Items.CHARCOAL, 21));
        port.setItem(SmartPortBlockEntity.BATTERY_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 4));
        port.swapCargoZones();
        checks.check("s8.swap.atomic_27_slot_pair_exchange",
                port.getItem(0).is(Items.COAL) && port.getItem(0).getCount() == 20
                        && port.getItem(26).is(Items.CHARCOAL) && port.getItem(26).getCount() == 21
                        && port.getItem(27).is(Items.DIAMOND) && port.getItem(27).getCount() == 10
                        && port.getItem(53).is(Items.EMERALD) && port.getItem(53).getCount() == 11
                        && port.availableSingleUseBatteries() == 4
                        && "status.lc2.swapped".equals(port.getStatusKey()),
                "status=" + port.getStatusKey());

        // 2. Menu Quick-Move check: player inventory normal items move ONLY into Outbound (0..26), NEVER Storage (27..53)!
        port.clearContent();
        cargo.clearContent();
        player.getInventory().clearContent();
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 16));
        SmartPortMenu portMenu = new SmartPortMenu(201, player.getInventory(), port);
        portMenu.quickMoveStack(player, SmartPortMenu.MACHINE_SLOTS); // player inv slot 0 (inv index 9)
        boolean portMovedToOutbound = QaChecks.countOutboundItems(port).getOrDefault("minecraft:cobblestone", 0) == 16
                && QaChecks.countStorageItems(port).isEmpty();

        // Fill all 27 Outbound slots with 1x OAK_LOG -> shift-clicking 16x DIRT MUST NOT spill into Storage (27..53)!
        for (int i = 0; i < AbstractLogisticsStationBlockEntity.OUTBOUND_SLOTS; i++) {
            port.setItem(i, new ItemStack(Items.OAK_LOG, 1));
        }
        player.getInventory().setItem(9, new ItemStack(Items.DIRT, 16));
        portMenu.quickMoveStack(player, SmartPortMenu.MACHINE_SLOTS);
        boolean portNeverSpilledToStorage = QaChecks.countStorageItems(port).isEmpty()
                && player.getInventory().getItem(9).is(Items.DIRT)
                && player.getInventory().getItem(9).getCount() == 16;

        // Same quick-move check on CargoStationMenu
        player.getInventory().clearContent();
        player.getInventory().setItem(9, new ItemStack(Items.SAND, 12));
        CargoStationMenu cargoMenu = new CargoStationMenu(202, player.getInventory(), cargo);
        cargoMenu.quickMoveStack(player, CargoStationMenu.MACHINE_SLOTS);
        boolean cargoMovedToOutbound = QaChecks.countOutboundItems(cargo).getOrDefault("minecraft:sand", 0) == 12
                && QaChecks.countStorageItems(cargo).isEmpty();
        checks.check("s8.menu.quick_move_strictly_outbound_only",
                portMovedToOutbound && portNeverSpilledToStorage && cargoMovedToOutbound,
                "port=" + portMovedToOutbound + ", noSpill=" + portNeverSpilledToStorage + ", cargo=" + cargoMovedToOutbound);
        player.getInventory().clearContent();

        // 3. Legacy v1 -> v2 NBT Migration check (SmartPort 32 slots -> 59 slots; CargoStation 30 slots -> 66 slots)
        List<ItemStack> legacyPortItems = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            legacyPortItems.add(ItemStack.EMPTY);
        }
        legacyPortItems.set(0, new ItemStack(Items.RAW_IRON, 17));
        legacyPortItems.set(26, new ItemStack(Items.RAW_GOLD, 19));
        legacyPortItems.set(27, new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 7));
        legacyPortItems.set(28, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_ENERGY_STORAGE), 1));
        legacyPortItems.set(31, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 2));
        CompoundTag legacyPortTag = new CompoundTag();
        legacyPortTag.putDouble("eu", 22_000.0);
        legacyPortTag.putString("name", "Legacy v1 Port");
        legacyPortTag.putLong("overflowMask", 4L); // old v1 key — must be ignored cleanly
        QaPlatform.loadLegacyV1Station(level, port, legacyPortItems, legacyPortTag);
        checks.check("s8.migration.v1_smart_port_zero_item_loss",
                port.getItem(0).is(Items.RAW_IRON) && port.getItem(0).getCount() == 17
                        && port.getItem(26).is(Items.RAW_GOLD) && port.getItem(26).getCount() == 19
                        && QaChecks.countStorageItems(port).isEmpty()
                        && port.availableSingleUseBatteries() == 7
                        && LogisticsConstants.UPGRADE_ENERGY_STORAGE.equals(Lc2Native.itemId(port.getItem(SmartPortBlockEntity.UPGRADE_START)))
                        && LogisticsConstants.UPGRADE_OVERCLOCKER.equals(Lc2Native.itemId(port.getItem(SmartPortBlockEntity.UPGRADE_START + 3)))
                        && Math.abs(port.getStoredEu() - 22_000.0) < 1e-6
                        && "Legacy v1 Port".equals(port.getStationName()),
                "eu=" + port.getStoredEu() + ", bat=" + port.availableSingleUseBatteries());

        List<ItemStack> legacyCargoItems = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            legacyCargoItems.add(ItemStack.EMPTY);
        }
        legacyCargoItems.set(0, new ItemStack(Items.RAW_COPPER, 23));
        legacyCargoItems.set(17, new ItemStack(Items.NETHERITE_SCRAP, 5));
        legacyCargoItems.set(18, new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_2_ID), 1));
        legacyCargoItems.set(19, new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        legacyCargoItems.set(24, new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
        legacyCargoItems.set(25, new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 9));
        legacyCargoItems.set(26, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_ENERGY_STORAGE), 1));
        legacyCargoItems.set(29, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_PULLING), 1));
        CompoundTag legacyCargoTag = new CompoundTag();
        legacyCargoTag.putDouble("eu", 21_500.0);
        legacyCargoTag.putString("name", "Legacy v1 Station");
        QaPlatform.loadLegacyV1Station(level, cargo, legacyCargoItems, legacyCargoTag);
        checks.check("s8.migration.v1_cargo_station_zero_item_loss",
                cargo.getItem(0).is(Items.RAW_COPPER) && cargo.getItem(0).getCount() == 23
                        && cargo.getItem(17).is(Items.NETHERITE_SCRAP) && cargo.getItem(17).getCount() == 5
                        && QaChecks.countStorageItems(cargo).isEmpty()
                        && cargo.installedRfChip() == RfChipTier.TIER_2
                        && cargo.countReadyDrones() == 2
                        && LogisticsConstants.TRANSPORT_DRONE_ID.equals(Lc2Native.itemId(cargo.getItem(CargoStationBlockEntity.DRONE_BAY_START)))
                        && LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID.equals(Lc2Native.itemId(cargo.getItem(CargoStationBlockEntity.DRONE_BAY_START + 5)))
                        && cargo.availableSingleUseBatteries() == 9
                        && LogisticsConstants.UPGRADE_ENERGY_STORAGE.equals(Lc2Native.itemId(cargo.getItem(CargoStationBlockEntity.UPGRADE_START)))
                        && LogisticsConstants.UPGRADE_PULLING.equals(Lc2Native.itemId(cargo.getItem(CargoStationBlockEntity.UPGRADE_START + 3)))
                        && Math.abs(cargo.getStoredEu() - 21_500.0) < 1e-6
                        && "Legacy v1 Station".equals(cargo.getStationName()),
                "eu=" + cargo.getStoredEu() + ", rf=" + cargo.installedRfChip() + ", drones=" + cargo.countReadyDrones());
    }

    private void tickVerifyNoVehicleThenAdd(ServerLevel level, int tick, QaChecks checks) {
        // Wait 5 ticks so both stations hit NO_VEHICLE under active redstone and enter 40-tick cooldown!
        if (tick - stageStartTick < 5) {
            return;
        }
        CargoStationBlockEntity droneOrigin = (CargoStationBlockEntity) level.getBlockEntity(DRONE_ORIGIN);
        SmartPortBlockEntity boatOrigin = (SmartPortBlockEntity) level.getBlockEntity(BOAT_ORIGIN);

        checks.check("s8.items_before_drone.initially_blocked_no_vehicle",
                DispatchFailure.NO_VEHICLE.translationKey().equals(droneOrigin.getStatusKey())
                        && QaChecks.countOutboundItems(droneOrigin).getOrDefault("minecraft:diamond", 0) == 12,
                "status=" + droneOrigin.getStatusKey());
        checks.check("s8.items_before_boat.initially_blocked_no_vehicle",
                DispatchFailure.NO_VEHICLE.translationKey().equals(boatOrigin.getStatusKey())
                        && QaChecks.countOutboundItems(boatOrigin).getOrDefault("minecraft:emerald", 0) == 14,
                "status=" + boatOrigin.getStatusKey());

        // NOW add drone into bay 55 (should IMMEDIATELY reset dispatchCooldown=0!)
        droneOrigin.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));

        // NOW place Electric Cargo Boat directly into water 2.9 blocks from berthCenter
        // (tests widened 3.5-block radius AND !hadDockedBoat -> resetDispatchCooldown()!)
        Vec3d berthCenter = boatOrigin.getWaterBerthPos().waterSurfaceCenter();
        ElectricCargoBoatEntity placedBoat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
        placedBoat.setPos(berthCenter.x() + 2.9, berthCenter.y(), berthCenter.z());
        placedBoat.setYRot(Direction.EAST.toYRot());
        checks.check("s8.items_before_boat.placed_in_water_at_2_9m", level.addFreshEntity(placedBoat));

        vehicleAddedTick = tick;
        stage = Stage.VERIFY_IMMEDIATE_LAUNCH;
    }

    private void tickVerifyImmediateLaunch(ServerLevel level, int tick, QaChecks checks) {
        var engine = LogisticsLevelManager.getData(level).engine();
        if (droneTransitId == null) {
            engine.activeTransits().stream()
                    .filter(t -> t.vehicleKind().isDrone() && t.originPos().x() == DRONE_ORIGIN.getX()
                            && t.originPos().z() == DRONE_ORIGIN.getZ())
                    .findFirst()
                    .ifPresent(t -> droneTransitId = t.id());
        }
        if (boatTransitId == null) {
            engine.activeTransits().stream()
                    .filter(t -> t.vehicleKind().isBoat() && t.originPos().x() == BOAT_ORIGIN.getX()
                            && t.originPos().z() == BOAT_ORIGIN.getZ())
                    .findFirst()
                    .ifPresent(t -> boatTransitId = t.id());
        }

        if (droneTransitId != null && boatTransitId != null) {
            int delay = tick - vehicleAddedTick;
            checks.check("s8.items_before_vehicle.both_launched_immediately_without_cooldown_stall",
                    delay <= 3,
                    "launchDelayTicks=" + delay + " (cooldown bypass verified!)");
            stage = Stage.WAIT_DELIVERIES_AND_HOPPERS;
            stageStartTick = tick;
            return;
        }
        if (tick - vehicleAddedTick > 5) {
            throw new AssertionError("Items-before-vehicle failed immediate dispatch! delay="
                    + (tick - vehicleAddedTick) + ", droneLaunched=" + (droneTransitId != null)
                    + ", boatLaunched=" + (boatTransitId != null));
        }
    }

    private void tickDeliveriesHoppersAndUpgrades(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 300) {
            throw new AssertionError("Timeout in Suite 8 deliveries/hoppers/upgrades: droneDelivered="
                    + droneDelivered + ", boatDelivered=" + boatDelivered
                    + ", hoppersChecked=" + hoppersChecked + ", upgradesChecked=" + upgradesChecked);
        }

        var engine = LogisticsLevelManager.getData(level).engine();
        if (!droneDelivered && engine.getTransit(droneTransitId).isEmpty()) {
            CargoStationBlockEntity droneTarget = (CargoStationBlockEntity) level.getBlockEntity(DRONE_TARGET);
            Map<String, Integer> storage = QaChecks.countStorageItems(droneTarget);
            checks.check("s8.items_before_drone.delivered_to_target_storage_27_53",
                    storage.getOrDefault("minecraft:diamond", 0) == 12
                            && storage.getOrDefault("minecraft:gold_ingot", 0) == 16
                            && QaChecks.countOutboundItems(droneTarget).isEmpty(),
                    "targetStorage=" + storage + ", targetOutbound=" + QaChecks.countOutboundItems(droneTarget));
            droneDelivered = true;
        }

        if (!boatDelivered && engine.getTransit(boatTransitId).isEmpty()) {
            SmartPortBlockEntity boatTarget = (SmartPortBlockEntity) level.getBlockEntity(BOAT_TARGET);
            Map<String, Integer> storage = QaChecks.countStorageItems(boatTarget);
            checks.check("s8.items_before_boat.delivered_to_target_storage_27_53",
                    storage.getOrDefault("minecraft:emerald", 0) == 14
                            && storage.getOrDefault("minecraft:iron_ingot", 0) == 10
                            && QaChecks.countOutboundItems(boatTarget).isEmpty(),
                    "targetStorage=" + storage + ", targetOutbound=" + QaChecks.countOutboundItems(boatTarget));
            boatDelivered = true;
        }

        // Check Upgrade Zone Isolation after 25 ticks (interval <= 10 ticks, budget 4/t -> 2 cycles = 8 items -> 3 cycles = 12)
        if (!upgradesChecked && tick - upgradeStartTick >= 35) {
            CargoStationBlockEntity upgStation = (CargoStationBlockEntity) level.getBlockEntity(UPG_STATION);
            Map<String, Integer> outbound = QaChecks.countOutboundItems(upgStation);
            Map<String, Integer> storage = QaChecks.countStorageItems(upgStation);
            Map<String, Integer> pullChestItems = QaChecks.countItemsAcross(level, PULL_CHEST);
            Map<String, Integer> ejectChestItems = QaChecks.countItemsAcross(level, EJECT_CHEST);
            int outboundComp = upgStation.computeCargoComparatorSignal();
            checks.check("s8.upgrades.pulling_to_outbound_and_ejector_from_storage_isolated",
                    outbound.getOrDefault("minecraft:lapis_lazuli", 0) == 6
                            && storage.isEmpty()
                            && pullChestItems.isEmpty()
                            && ejectChestItems.getOrDefault("minecraft:redstone", 0) == 9
                            && !ejectChestItems.containsKey("minecraft:lapis_lazuli")
                            && outboundComp >= 1,
                    "outbound=" + outbound + ", storage=" + storage + ", ejectChest=" + ejectChestItems
                            + ", comp=" + outboundComp);

            // Prove loadOutgoingCargo drains Outbound + pulling chest (with pulling_upgrade), NEVER Storage!
            level.setBlockAndUpdate(EJECT_CHEST, Blocks.AIR.defaultBlockState()); // remove eject chest so quartz stays in Storage
            upgStation.setItem(CargoStationBlockEntity.STORAGE_START, new ItemStack(Items.QUARTZ, 4));
            ((ChestBlockEntity) level.getBlockEntity(PULL_CHEST)).setItem(0, new ItemStack(Items.AMETHYST_SHARD, 3));
            List<ItemStack> loaded = upgStation.loadOutgoingCargo(level, 12);
            int loadedLapis = loaded.stream().filter(s -> s.is(Items.LAPIS_LAZULI)).mapToInt(ItemStack::getCount).sum();
            int loadedAmethyst = loaded.stream().filter(s -> s.is(Items.AMETHYST_SHARD)).mapToInt(ItemStack::getCount).sum();
            int loadedQuartz = loaded.stream().filter(s -> s.is(Items.QUARTZ)).mapToInt(ItemStack::getCount).sum();
            checks.check("s8.upgrades.vehicle_loading_never_ships_storage",
                    loadedLapis == 6 && loadedAmethyst == 3 && loadedQuartz == 0
                            && QaChecks.countStorageItems(upgStation).getOrDefault("minecraft:quartz", 0) == 4,
                    "lapis=" + loadedLapis + ", amethyst=" + loadedAmethyst + ", quartzLoaded=" + loadedQuartz);
            upgradesChecked = true;
        }

        // Check Real Vanilla Hopper Zone Isolation after 85 ticks (8 items * 8t/item = 64t + margin)
        if (!hoppersChecked && tick - hoppersStartTick >= 85) {
            verifyHopperIsolation(level, HOPPER_PORT, HOPPER_PORT_IN, HOPPER_PORT_OUT, "smart_port", checks);
            verifyHopperIsolation(level, HOPPER_CARGO, HOPPER_CARGO_IN, HOPPER_CARGO_OUT, "cargo_station", checks);
            hoppersChecked = true;
        }

        if (droneDelivered && boatDelivered && upgradesChecked && hoppersChecked) {
            // Prepare spawnAndDockBoat items-before-boat test!
            SmartPortBlockEntity boatOrigin = (SmartPortBlockEntity) level.getBlockEntity(BOAT_ORIGIN);
            boatOrigin.setItem(0, new ItemStack(Items.NETHERITE_INGOT, 4));
            stage = Stage.TEST_SPAWN_AND_DOCK_SECOND;
            stageStartTick = tick;
        }
    }

    private void verifyHopperIsolation(
            ServerLevel level,
            BlockPos stationPos,
            BlockPos inHopperPos,
            BlockPos outHopperPos,
            String tag,
            QaChecks checks
    ) {
        AbstractLogisticsStationBlockEntity station =
                (AbstractLogisticsStationBlockEntity) level.getBlockEntity(stationPos);
        Map<String, Integer> outbound = QaChecks.countOutboundItems(station);
        Map<String, Integer> storage = QaChecks.countStorageItems(station);
        Map<String, Integer> inItems = QaChecks.countItemsAcross(level, inHopperPos);
        Map<String, Integer> outItems = QaChecks.countItemsAcross(level, outHopperPos);
        checks.check("s8.hopper." + tag + "_push_outbound_only_extract_storage_only",
                inItems.isEmpty()
                        && outbound.getOrDefault("minecraft:copper_ingot", 0) == 8
                        && outbound.getOrDefault("minecraft:iron_block", 0) == 5
                        && storage.isEmpty()
                        && outItems.getOrDefault("minecraft:gold_block", 0) == 7
                        && !outItems.containsKey("minecraft:iron_block")
                        && !outItems.containsKey("minecraft:copper_ingot")
                        && !outItems.containsKey(LogisticsConstants.SINGLE_USE_BATTERY_ID)
                        && station.availableSingleUseBatteries() == 3,
                "outbound=" + outbound + ", storage=" + storage + ", outHopper=" + outItems
                        + ", batteries=" + station.availableSingleUseBatteries());
    }

    private void tickSpawnAndDockSecond(ServerLevel level, int tick, QaChecks checks) {
        // Wait 5 ticks with constant redstone ON & items in Outbound -> NO_VEHICLE cooldown active
        if (tick - stageStartTick < 5) {
            return;
        }
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        SmartPortBlockEntity boatOrigin = (SmartPortBlockEntity) level.getBlockEntity(BOAT_ORIGIN);
        boolean docked = boatOrigin.spawnAndDockBoat(
                level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get()));
        checks.check("s8.items_before_boat.spawn_and_dock_after_items", docked);
        boat2AddedTick = tick;
        stage = Stage.VERIFY_SPAWN_AND_DOCK_IMMEDIATE;
    }

    private void tickVerifySpawnAndDockImmediate(ServerLevel level, int tick, QaChecks checks) {
        var engine = LogisticsLevelManager.getData(level).engine();
        if (boat2TransitId == null) {
            engine.activeTransits().stream()
                    .filter(t -> t.vehicleKind().isBoat() && t.originPos().x() == BOAT_ORIGIN.getX()
                            && t.originPos().z() == BOAT_ORIGIN.getZ())
                    .findFirst()
                    .ifPresent(t -> boat2TransitId = t.id());
            if (boat2TransitId != null) {
                int delay = tick - boat2AddedTick;
                checks.check("s8.items_before_boat.spawn_and_dock_launches_immediately",
                        delay <= 3,
                        "launchDelayTicks=" + delay);
                // Cancel the second boat flight cleanly and clean up Suite 8 redstone & forced chunks
                LogisticsLevelManager.cancelAndTakeCargo(level, boat2TransitId);
                level.setBlockAndUpdate(DRONE_REDSTONE, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(BOAT_REDSTONE, Blocks.AIR.defaultBlockState());
                QaChecks.setChunkRangeForced(level, 4, 6, 0, 2, false);
                stage = Stage.DONE;
                return;
            }
            if (tick - boat2AddedTick > 5) {
                throw new AssertionError("spawnAndDockBoat after items failed immediate dispatch!");
            }
        }
    }
}
