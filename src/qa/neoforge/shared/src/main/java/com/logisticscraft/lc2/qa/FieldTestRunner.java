package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LevelWaterGrid;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end natural-world field test driver ({@code lc2.qa.mode=field}) verifying:
 * <ul>
 *   <li>Phase 1 hardening fixes on natural terrain (waterlogged obstacles, station comparators, oversized stack safety)</li>
 *   <li>Real natural-seed ({@code level-type=minecraft:normal}) shoreline discovery & IC2 mining</li>
 *   <li>Autonomous comparator-driven Smart Port (Boat) + Cargo Station (Drone) transport</li>
 *   <li>Full IC2 Macerator -> Electric Furnace -> Steel Storage Box refining line + vehicle auto-return</li>
 * </ul>
 */
public final class FieldTestRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldTestRunner.class);

    private static final Set<String> FINISHED_INGOTS = Set.of(
            "minecraft:iron_ingot",
            "minecraft:copper_ingot",
            "minecraft:gold_ingot",
            "ic2:tin_ingot",
            "ic2:lead_ingot",
            "ic2:silver_ingot"
    );

    private static final Set<String> RAW_ORES = Set.of(
            "minecraft:raw_iron",
            "minecraft:raw_copper",
            "minecraft:raw_gold",
            "ic2:raw_tin",
            "ic2:raw_lead",
            "ic2:raw_silver"
    );

    private FieldFactoryBuilder.BuiltFactory factory;

    private final Set<UUID> seenBoatTransits = new HashSet<>();
    private final Set<UUID> seenDroneTransits = new HashSet<>();
    private UUID trackedBoatTransit = null;
    private UUID trackedDroneTransit = null;

    private boolean boatLaunchedWithOres = false;
    private boolean boatReturned = false;
    private boolean droneLaunchedWithOres = false;
    private boolean droneReturned = false;
    private boolean sawPortComparatorSignal = false;
    private boolean sawHillComparatorSignal = false;
    private boolean sawBoatActiveTransit = false;
    private boolean sawDroneActiveTransit = false;
    private boolean sawHeavyDroneLaunched = false;

    private int boatOreItemsLoaded = 0;
    private int droneOreItemsLoaded = 0;
    private int advOresMinedCount = 0;
    private int basicOresMinedCount = 0;
    private int basicMinerPipeDepth = 0;
    private int finishedIngotsCount = 0;

    /** Scouts the natural world, runs Phase 1 live checks, and builds the complete IC2+LC2 factory. */
    public FieldFactoryBuilder.BuiltFactory setup(ServerLevel level, QaChecks checks) throws IOException {
        verifyProductionJars(checks);

        FieldWorldScanner.FieldSite site = FieldWorldScanner.findNaturalSite(level);
        recordSiteMeta(site, checks);

        checks.check("field.world.natural_waterway_found",
                site.waterwayDistanceBlocks() >= 55 && site.verifiedWaypoints().size() >= 2,
                "seed=" + site.seed() + ", dist=" + site.waterwayDistanceBlocks()
                        + ", waypoints=" + site.verifiedWaypoints().size()
                        + ", aquaticPlants=" + site.aquaticVegetationNearRoute()
                        + ", biomeA=" + site.biomeA() + ", biomeB=" + site.biomeB());

        factory = FieldFactoryBuilder.build(level, site, checks);

        checks.check("field.world.natural_ores_present_under_miners",
                !factory.initialAdvOrePositions().isEmpty() && !factory.initialBasicOrePositions().isEmpty(),
                "advNaturalOres=" + factory.initialAdvOrePositions().size()
                        + ", basicNaturalOres=" + factory.initialBasicOrePositions().size());

        verifyPhase1Hardening(level, factory, checks);
        return factory;
    }

    /** Returns the constructed factory layout (also used by live client-server interactive mode). */
    public FieldFactoryBuilder.BuiltFactory factory() {
        return factory;
    }

    /** Advances field-test telemetry checks each server tick; returns {@code true} once the full factory loop passes. */
    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        if (tick > 6_500) {
            throw new AssertionError("Field test timeout at tick " + tick
                    + " [advMined=" + advOresMinedCount
                    + ", basicMined=" + basicOresMinedCount
                    + ", pipeDepth=" + basicMinerPipeDepth
                    + ", boatLaunched=" + boatLaunchedWithOres
                    + ", boatReturned=" + boatReturned
                    + ", droneLaunched=" + droneLaunchedWithOres
                    + ", droneReturned=" + droneReturned
                    + ", ingots=" + finishedIngotsCount
                    + ", outBox=" + QaChecks.countItemsAcross(level, factory.steelOutputBoxPos())
                    + ", rch=" + QaChecks.countItemsAcross(level, factory.receivingChestB())
                    + ", portA_eu=" + portEu(level, factory.portA())
                    + ", hill_eu=" + cargoEu(level, factory.hillCargoPos())
                    + ", baseCargo_eu=" + cargoEu(level, factory.baseCargoPos())
                    + "]");
        }

        // Track comparator analog signals firing naturally on real mined ores
        if (!sawPortComparatorSignal) {
            int sig = QaPlatform.analogOutputSignal(level, factory.portA(), factory.site().waterDirA().getClockWise());
            if (sig > 0) {
                sawPortComparatorSignal = true;
                checks.check("field.automation.smart_port_comparator_fired", true, "signal=" + sig + ", tick=" + tick);
            }
        }
        if (!sawHillComparatorSignal) {
            int sig = QaPlatform.analogOutputSignal(level, factory.hillCargoPos(), Direction.EAST);
            if (sig > 0) {
                sawHillComparatorSignal = true;
                checks.check("field.automation.cargo_station_comparator_fired", true, "signal=" + sig + ", tick=" + tick);
            }
        }

        // Track newly dispatched boat & drone transits originating from Outpost A
        var data = LogisticsLevelManager.getData(level);
        for (TransitRecord rec : data.engine().activeTransits()) {
            if (rec.vehicleKind().isBoat() && seenBoatTransits.add(rec.id())) {
                List<ItemStack> cargo = data.getCargo(rec.id());
                int rawOres = countRawOreItems(cargo);
                sawBoatActiveTransit = true;
                LOGGER.info("[LC2-FIELD] Boat dispatched at tick {} (id={}, autoReturn={}, rawOreItems={}, stacks={})",
                        tick, rec.id(), rec.autoReturn(), rawOres, cargo.size());
                if (rawOres > 0) {
                    boatOreItemsLoaded += rawOres;
                    if (!boatLaunchedWithOres) {
                        boatLaunchedWithOres = true;
                        trackedBoatTransit = rec.id();
                        checks.check("field.transit.boat_launched_natural_ores", true,
                                "tick=" + tick + ", rawOreItems=" + rawOres + ", waypoints=" + rec.waypoints().size());
                    }
                }
            } else if (rec.vehicleKind().isDrone() && seenDroneTransits.add(rec.id())) {
                List<ItemStack> cargo = data.getCargo(rec.id());
                int rawOres = countRawOreItems(cargo);
                int totalItems = cargo.stream().mapToInt(ItemStack::getCount).sum();
                sawDroneActiveTransit = true;
                if (rec.vehicleKind().name().contains("HEAVY")) {
                    sawHeavyDroneLaunched = true;
                }
                LOGGER.info("[LC2-FIELD] Drone ({}) dispatched at tick {} (id={}, autoReturn={}, rawOreItems={}, totalItems={})",
                        rec.vehicleKind(), tick, rec.id(), rec.autoReturn(), rawOres, totalItems);
                if (totalItems > 0) {
                    droneOreItemsLoaded += Math.max(rawOres, totalItems);
                    if (!droneLaunchedWithOres) {
                        droneLaunchedWithOres = true;
                        trackedDroneTransit = rec.id();
                        checks.check("field.transit.drone_launched_natural_ores", true,
                                "tick=" + tick + ", kind=" + rec.vehicleKind() + ", items=" + totalItems);
                    }
                }
            }
        }

        // Check boat auto-return completion
        if (trackedBoatTransit != null && !boatReturned
                && data.engine().getTransit(trackedBoatTransit).isEmpty()) {
            SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
            if (portA != null && portA.findDockedBoat(level).isPresent()) {
                boatReturned = true;
                checks.check("field.transit.boat_auto_returned_to_outpost", true,
                        "tick=" + tick + ", totalBoatTrips=" + seenBoatTransits.size());
            }
        }

        // Check drone auto-return completion (requires Base Cargo Station paying return EU from real IC2 grid!)
        if (trackedDroneTransit != null && !droneReturned
                && data.engine().getTransit(trackedDroneTransit).isEmpty()) {
            CargoStationBlockEntity hill = (CargoStationBlockEntity) level.getBlockEntity(factory.hillCargoPos());
            if (hill != null && hill.countReadyDrones() >= 1) {
                droneReturned = true;
                checks.check("field.transit.drone_auto_returned_to_hillside", true,
                        "tick=" + tick + ", readyDrones=" + hill.countReadyDrones()
                                + ", totalDroneTrips=" + seenDroneTransits.size());
            }
        }

        // Periodically inspect real mined natural terrain blocks and refined ingot output
        if (tick % 20 == 0) {
            advOresMinedCount = countRemovedOres(level, factory.initialAdvOrePositions());
            basicOresMinedCount = countRemovedOres(level, factory.initialBasicOrePositions());
            basicMinerPipeDepth = measureMiningPipeDepth(level, factory.basicMinerPos());
            Map<String, Integer> outputCounts = QaChecks.countItemsAcross(level, factory.steelOutputBoxPos());
            finishedIngotsCount = outputCounts.entrySet().stream()
                    .filter(e -> FINISHED_INGOTS.contains(e.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();

            if (tick % 200 == 0) {
                LOGGER.info("[LC2-FIELD] t={} advMined={} basicMined={} pipeDepth={} boat(launch={},ret={}) drone(launch={},ret={}) ingots={} outBox={}",
                        tick, advOresMinedCount, basicOresMinedCount, basicMinerPipeDepth,
                        boatLaunchedWithOres, boatReturned, droneLaunchedWithOres, droneReturned,
                        finishedIngotsCount, outputCounts);
            }
        }

        if (advOresMinedCount >= 1
                && basicOresMinedCount >= 1
                && basicMinerPipeDepth >= 1
                && boatLaunchedWithOres
                && boatReturned
                && droneLaunchedWithOres
                && droneReturned
                && finishedIngotsCount >= 2) {
            Map<String, Integer> finalOut = QaChecks.countItemsAcross(level, factory.steelOutputBoxPos());
            checks.check("field.mining.advanced_miner_mined_natural_ores", advOresMinedCount >= 1,
                    "minedBlocks=" + advOresMinedCount);
            checks.check("field.mining.basic_miner_mined_natural_ores_with_pipes",
                    basicOresMinedCount >= 1 && basicMinerPipeDepth >= 1,
                    "minedBlocks=" + basicOresMinedCount + ", pipeDepth=" + basicMinerPipeDepth);
            checks.check("field.power.all_stations_received_real_ic2_eu",
                    portEu(level, factory.portA()) > 0.0
                            && cargoEu(level, factory.hillCargoPos()) > 0.0
                            && portEu(level, factory.portB()) > 100.0
                            && cargoEu(level, factory.baseCargoPos()) > 0.0,
                    "portAEu=" + Math.round(portEu(level, factory.portA()))
                            + ", hillEu=" + Math.round(cargoEu(level, factory.hillCargoPos()))
                            + ", basePortEu=" + Math.round(portEu(level, factory.portB()))
                            + ", baseCargoEu=" + Math.round(cargoEu(level, factory.baseCargoPos())));
            checks.check("field.refining.finished_ingots_in_steel_storage_box",
                    finishedIngotsCount >= 2,
                    "ingots=" + finishedIngotsCount + ", steelBoxContents=" + finalOut);

            checks.putMeta("advOresMined", Integer.toString(advOresMinedCount));
            checks.putMeta("basicOresMined", Integer.toString(basicOresMinedCount));
            checks.putMeta("basicMinerPipeDepth", Integer.toString(basicMinerPipeDepth));
            checks.putMeta("boatTrips", Integer.toString(seenBoatTransits.size()));
            checks.putMeta("boatOreItemsLoaded", Integer.toString(boatOreItemsLoaded));
            checks.putMeta("droneTrips", Integer.toString(seenDroneTransits.size()));
            checks.putMeta("droneOreItemsLoaded", Integer.toString(droneOreItemsLoaded));
            checks.putMeta("heavyDroneObserved", Boolean.toString(sawHeavyDroneLaunched));
            checks.putMeta("finishedIngots", Integer.toString(finishedIngotsCount));
            checks.putMeta("finalOutputBox", finalOut.toString());
            return true;
        }
        return false;
    }

    private void verifyPhase1Hardening(
            ServerLevel level,
            FieldFactoryBuilder.BuiltFactory built,
            QaChecks checks
    ) {
        // 1. Waterlogged solid blocks (oak fence + stone slab) MUST NOT be navigable water!
        BlockPos probeWater = built.site().berthA();
        BlockState originalWater = level.getBlockState(probeWater);
        LevelWaterGrid grid = LevelWaterGrid.loadedOnly(level);
        checks.check("field.p1.natural_berth_navigable",
                grid.isNavigableWater(probeWater.getX(), probeWater.getY(), probeWater.getZ()));

        level.setBlockAndUpdate(probeWater, Blocks.OAK_FENCE.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        boolean fenceNav = LevelWaterGrid.loadedOnly(level)
                .isNavigableWater(probeWater.getX(), probeWater.getY(), probeWater.getZ());

        level.setBlockAndUpdate(probeWater, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        boolean slabNav = LevelWaterGrid.loadedOnly(level)
                .isNavigableWater(probeWater.getX(), probeWater.getY(), probeWater.getZ());

        level.setBlockAndUpdate(probeWater, originalWater);
        checks.check("field.p1.waterlogged_fence_and_slab_rejected",
                !fenceNav && !slabNav,
                "fenceNav=" + fenceNav + ", slabNav=" + slabNav);

        // 2. SmartPortBlock & CargoStationBlock comparator analog signal (0 empty & 0 for Storage-only, >=1 for Outbound)
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(built.portA());
        CargoStationBlockEntity hill = (CargoStationBlockEntity) level.getBlockEntity(built.hillCargoPos());
        int portEmptySig = QaPlatform.analogOutputSignal(level, built.portA(), built.site().waterDirA().getClockWise());
        int cargoEmptySig = QaPlatform.analogOutputSignal(level, built.hillCargoPos(), Direction.EAST);

        portA.setItem(SmartPortBlockEntity.STORAGE_START, new ItemStack(Items.COBBLESTONE, 1));
        hill.setItem(CargoStationBlockEntity.STORAGE_START, new ItemStack(Items.COBBLESTONE, 1));
        int portStorageOnlySig = QaPlatform.analogOutputSignal(level, built.portA(), built.site().waterDirA().getClockWise());
        int cargoStorageOnlySig = QaPlatform.analogOutputSignal(level, built.hillCargoPos(), Direction.EAST);
        portA.setItem(SmartPortBlockEntity.STORAGE_START, ItemStack.EMPTY);
        hill.setItem(CargoStationBlockEntity.STORAGE_START, ItemStack.EMPTY);

        portA.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        hill.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        int portFilledSig = QaPlatform.analogOutputSignal(level, built.portA(), built.site().waterDirA().getClockWise());
        int cargoFilledSig = QaPlatform.analogOutputSignal(level, built.hillCargoPos(), Direction.EAST);
        portA.setItem(0, ItemStack.EMPTY);
        hill.setItem(0, ItemStack.EMPTY);

        checks.check("field.p1.station_comparator_analog_output",
                level.getBlockState(built.portA()).hasAnalogOutputSignal()
                        && level.getBlockState(built.hillCargoPos()).hasAnalogOutputSignal()
                        && portEmptySig == 0 && cargoEmptySig == 0
                        && portStorageOnlySig == 0 && cargoStorageOnlySig == 0
                        && portFilledSig >= 1 && cargoFilledSig >= 1,
                "port(0/storage=0->" + portFilledSig + "), cargo(0/storage=0->" + cargoFilledSig + ")");

        // 3. Oversized/multi-slot stack safety in loadOutgoingCargo: 2x12 snowballs (maxStack=16) pulled via pulling_upgrade into maxStacks=1
        // On 1.21.1 pulls 12 and leaves 12; on 26.x Transfer API extracts 24 at once, loads 16, and Phase 1 fix returns 8!
        BlockPos tmpPort = built.portA().above(24);
        BlockPos tmpChest = tmpPort.east();
        level.setBlockAndUpdate(tmpPort,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(tmpChest, Blocks.CHEST.defaultBlockState());
        SmartPortBlockEntity tmpPortBe = (SmartPortBlockEntity) level.getBlockEntity(tmpPort);
        ChestBlockEntity tmpChestBe = (ChestBlockEntity) level.getBlockEntity(tmpChest);
        tmpPortBe.setItem(SmartPortBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(com.logisticscraft.lc2.core.LogisticsConstants.UPGRADE_PULLING), 1));
        tmpChestBe.setItem(0, new ItemStack(Items.SNOWBALL, 12));
        tmpChestBe.setItem(1, new ItemStack(Items.SNOWBALL, 12));
        List<ItemStack> loaded = tmpPortBe.loadOutgoingCargo(level, 1);
        int loadedCount = loaded.stream().mapToInt(ItemStack::getCount).sum();
        int preservedRemainder = QaChecks.countItemsAcross(level, tmpPort, tmpChest)
                .getOrDefault("minecraft:snowball", 0);
        tmpPortBe.clearContent();
        tmpChestBe.clearContent();
        level.setBlockAndUpdate(tmpPort, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(tmpChest, Blocks.AIR.defaultBlockState());
        checks.check("field.p1.oversized_stack_zero_item_loss",
                loaded.size() == 1 && loadedCount >= 12 && loadedCount <= 16
                        && loadedCount + preservedRemainder == 24,
                "loaded=" + loadedCount + ", preservedRemainder=" + preservedRemainder);
    }

    private void verifyProductionJars(QaChecks checks) throws IOException {
        Path lc2Jar = ModList.get().getModContainerById("lc2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        Path ic2Jar = ModList.get().getModContainerById("ic2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        String expectedLc2 = System.getProperty("lc2.qa.expectedLc2Jar", lc2Jar.getFileName().toString());
        String expectedIc2 = System.getProperty("lc2.qa.expectedIc2Jar", ic2Jar.getFileName().toString());
        checks.putMeta("lc2Artifact", lc2Jar.getFileName().toString());
        checks.putMeta("ic2Artifact", ic2Jar.getFileName().toString());
        checks.check("field.jar.lc2_loaded_from_mods",
                Files.isSameFile(lc2Jar, Path.of("mods", expectedLc2)), lc2Jar.getFileName().toString());
        checks.check("field.jar.ic2_loaded_from_mods",
                Files.isSameFile(ic2Jar, Path.of("mods", expectedIc2)), ic2Jar.getFileName().toString());
    }

    private void recordSiteMeta(FieldWorldScanner.FieldSite site, QaChecks checks) {
        checks.putMeta("seed", Long.toString(site.seed()));
        checks.putMeta("waterY", Integer.toString(site.waterY()));
        checks.putMeta("portA", posStr(site.portA()));
        checks.putMeta("berthA", posStr(site.berthA()));
        checks.putMeta("advMiner", posStr(site.advMinerPos()));
        checks.putMeta("hillCargo", posStr(site.hillsideCargoPos()));
        checks.putMeta("basicMiner", posStr(site.basicMinerPos()));
        checks.putMeta("portB", posStr(site.portB()));
        checks.putMeta("berthB", posStr(site.berthB()));
        checks.putMeta("waterwayDistance", Integer.toString(site.waterwayDistanceBlocks()));
        checks.putMeta("waterWaypoints", Integer.toString(site.verifiedWaypoints().size()));
        checks.putMeta("aquaticPlantsNearRoute", Integer.toString(site.aquaticVegetationNearRoute()));
        checks.putMeta("biomeA", site.biomeA());
        checks.putMeta("biomeB", site.biomeB());
    }

    private static int countRawOreItems(List<ItemStack> stacks) {
        int sum = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && RAW_ORES.contains(Lc2Native.itemId(s))) {
                sum += s.getCount();
            }
        }
        return sum;
    }

    private static int countRemovedOres(ServerLevel level, List<BlockPos> initialOres) {
        int removed = 0;
        for (BlockPos p : initialOres) {
            if (!FieldWorldScanner.isAnyOre(level.getBlockState(p))) {
                removed++;
            }
        }
        return removed;
    }

    private static int measureMiningPipeDepth(ServerLevel level, BlockPos minerPos) {
        int depth = 0;
        for (int d = 1; d <= 48; d++) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(minerPos.below(d)).getBlock()).toString();
            if ("ic2:mining_pipe".equals(id) || "ic2:mining_pipe_tip".equals(id)) {
                depth = d;
            }
        }
        return depth;
    }

    private static double portEu(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SmartPortBlockEntity be ? be.getStoredEu() : -1.0;
    }

    private static double cargoEu(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CargoStationBlockEntity be ? be.getStoredEu() : -1.0;
    }

    private static String posStr(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
