package com.logisticscraft.lc2.qa;

import com.industrialage.ia2.gameplay.IndustrialMachine;
import com.industrialage.ia2.world.MachineAutomation;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.platform.Lc2ItemAccess;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Constructs the real-world automated IC2 + LC2 Mining Outpost A, Hillside Airfield, and
 * Main Refining Base B on top of genuine natural terrain without planting any fake ores.
 */
public final class FieldFactoryBuilder {

    private FieldFactoryBuilder() {
    }

    /** Complete layout of all constructed blocks & tracked natural ore snapshots for telemetry and client tours. */
    public record BuiltFactory(
            FieldWorldScanner.FieldSite site,
            BlockPos portA,
            BlockPos bufferChestA,
            BlockPos advMinerPos,
            BlockPos cableTouchingPortA,
            BlockPos comparatorPortA,
            BlockPos hillCargoPos,
            BlockPos basicMinerPos,
            BlockPos cableTouchingHillCargo,
            BlockPos comparatorHillCargo,
            BlockPos portB,
            BlockPos receivingChestB,
            BlockPos baseCargoPos,
            BlockPos maceratorPos,
            BlockPos electricFurnacePos,
            BlockPos steelOutputBoxPos,
            BlockPos cableTouchingBaseCargo,
            List<BlockPos> initialAdvOrePositions,
            List<BlockPos> initialBasicOrePositions
    ) {
    }

    /** Builds the complete natural-world factory and returns all tracked positions and initial natural ore snapshots. */
    public static BuiltFactory build(ServerLevel level, FieldWorldScanner.FieldSite site, QaChecks checks) {
        FakePlayer builder = FakePlayerFactory.getMinecraft(level);

        // Force-load the region covering Outpost A, Hillside, Waterway, and Base B during setup
        int minCx = Math.min(Math.min(site.portA().getX(), site.hillsideCargoPos().getX()), site.portB().getX()) >> 4;
        int maxCx = Math.max(Math.max(site.portA().getX(), site.hillsideCargoPos().getX()), site.portB().getX()) >> 4;
        int minCz = Math.min(Math.min(site.portA().getZ(), site.hillsideCargoPos().getZ()), site.portB().getZ()) >> 4;
        int maxCz = Math.max(Math.max(site.portA().getZ(), site.hillsideCargoPos().getZ()), site.portB().getZ()) >> 4;
        QaChecks.setChunkRangeForced(level, minCx - 1, maxCx + 1, minCz - 1, maxCz + 1, true);

        // Snapshot genuine natural ore block positions BEFORE placing miners
        List<BlockPos> initialAdvOres = snapshotMetallicOres(level, site.advMinerPos(), 8, 22);
        List<BlockPos> initialBasicOres = snapshotAnyOres(level, site.basicMinerPos(), 3, 30);

        // =========================================================================
        // 1. OUTPOST HARBOR (Natural Shore A): Smart Port + Buffer Chest + Advanced Miner + IC2 Grid + Comparator
        // =========================================================================
        BlockPos portA = site.portA();
        Direction waterDirA = site.waterDirA();
        Direction backA = waterDirA.getOpposite();
        Direction rightA = waterDirA.getClockWise();
        Direction leftA = waterDirA.getCounterClockWise();

        BlockPos bufferChestA = portA.relative(backA);
        BlockPos advMinerPos = portA.relative(backA, 2);

        // Gently prepare only the immediate 1-block ground pads (never digging ores below!)
        preparePad(level, portA, 3);
        preparePad(level, bufferChestA, 3);
        preparePad(level, advMinerPos, 3);

        // Place Smart Port A on natural shoreline at Y = waterY + 1 facing water
        level.setBlockAndUpdate(portA,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, waterDirA));
        level.setBlockAndUpdate(bufferChestA, Blocks.CHEST.defaultBlockState());
        placeHorizontal(level, advMinerPos, "ic2:advanced_miner", waterDirA);

        // Power Grid A1 on leftA flank: copper cables touching BOTH Smart Port A and Advanced Miner
        BlockPos cableA0 = portA.relative(leftA);
        BlockPos cableA1 = bufferChestA.relative(leftA);
        BlockPos cableA2 = advMinerPos.relative(leftA);
        BlockPos batBoxA = cableA1.relative(leftA);
        BlockPos genA1 = cableA0.relative(leftA);
        BlockPos genA2 = cableA2.relative(leftA);
        BlockPos genA3 = batBoxA.relative(leftA);
        BlockPos genA4 = batBoxA.above();

        for (BlockPos p : List.of(cableA0, cableA1, cableA2, batBoxA, genA1, genA2, genA3)) {
            preparePad(level, p, 3);
        }
        prepareHeadroomOnly(level, genA4, 2);

        placeFacing(level, batBoxA, "ic2:batbox", rightA);
        placeGeneratorWithCoal(level, genA1, waterDirA, 64);
        placeGeneratorWithCoal(level, genA2, waterDirA, 64);
        placeGeneratorWithCoal(level, genA3, waterDirA, 64);
        placeGeneratorWithCoal(level, genA4, waterDirA, 64);
        QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", cableA0);
        QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", cableA1);
        QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", cableA2);

        // Verify CableBlockMixin connected copper cable visually to Smart Port A
        checks.check("field.cable.outpost_port_connected",
                cableConnectedToward(level.getBlockState(cableA0), rightA),
                "state=" + level.getBlockState(cableA0));

        // Equip ic2:advanced_miner via capability API: slot 0 = advanced_scanner, slots 1..3 = iron/copper/tin raw ore whitelist
        QaChecks.insertOrFail(level, advMinerPos, new ItemStack(QaChecks.item("ic2:advanced_scanner"), 1));
        QaChecks.insertOrFail(level, advMinerPos, new ItemStack(Items.RAW_IRON, 1));
        QaChecks.insertOrFail(level, advMinerPos, new ItemStack(Items.RAW_COPPER, 1));
        QaChecks.insertOrFail(level, advMinerPos, new ItemStack(QaChecks.item("ic2:raw_tin"), 1));

        // Configure Outpost Smart Port A: name, target, autoReturn, upgrades, boat
        SmartPortBlockEntity portABe = (SmartPortBlockEntity) level.getBlockEntity(portA);
        checks.check("field.outpost.port_a_ready", portABe != null);
        portABe.setStationName("Outpost Harbor");
        portABe.setAutoReturn(true);
        portABe.setEnergyPriority(EnergyPriority.EU_FIRST);
        portABe.refreshWaterBerth(level);
        checks.check("field.outpost.port_a_berth_detected",
                portABe.getWaterBerthPos() != null && portABe.getWaterBerthPos().y() == site.waterY(),
                "berthA=" + portABe.getWaterBerthPos());

        ItemStack portAPuller = routeUpgrade(level, builder, bufferChestA, "ic2:pulling_upgrade", backA, "portA_pull", checks);
        portABe.setItem(SmartPortBlockEntity.UPGRADE_START, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_CHUNK_LOADING), 1));
        portABe.setItem(SmartPortBlockEntity.UPGRADE_START + 1, portAPuller);
        portABe.setItem(SmartPortBlockEntity.UPGRADE_START + 2, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 2));

        boolean boatDocked = portABe.spawnAndDockBoat(
                level, builder, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get()));
        checks.check("field.outpost.boat_docked_natural_water",
                boatDocked && portABe.findDockedBoat(level).isPresent(),
                "berthA=" + site.berthA());

        // Vanilla Redstone Comparator reading Outpost Smart Port A -> Repeater -> Solid-supported bridge to portA.above()
        // (kept strictly on rightA flank so Advanced Miner at backA*2 receives ZERO redstone signal!)
        BlockPos compA = portA.relative(rightA);
        BlockPos repA = portA.relative(rightA, 2);
        BlockPos pillarA = portA.relative(rightA, 3);
        for (BlockPos p : List.of(compA, repA, pillarA)) {
            preparePad(level, p, 4);
        }
        level.setBlockAndUpdate(compA, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, leftA)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE));
        level.setBlockAndUpdate(repA, Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.FACING, leftA));
        level.setBlockAndUpdate(pillarA, Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(repA.above(), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(compA.above(), Blocks.SMOOTH_STONE.defaultBlockState());
        for (BlockPos wire : List.of(pillarA.above(), repA.above(2), compA.above(2), portA.above())) {
            level.setBlockAndUpdate(wire, Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        checks.check("field.outpost.adv_miner_unpowered_redstone",
                !level.hasNeighborSignal(advMinerPos),
                "advMinerPos=" + advMinerPos);

        // =========================================================================
        // 2. OUTPOST HILLSIDE AIRFIELD: Cargo Station + Basic Miner + RF Chip + 2 Drones + IC2 Grid + Comparator
        // =========================================================================
        BlockPos hillCargoPos = site.hillsideCargoPos();
        BlockPos basicMinerPos = site.basicMinerPos();
        Direction minerDir = site.basicMinerDirFromStation(); // NORTH

        // Clear sky above Cargo Station roof pads + prepare work footprint without touching below basicMinerPos!
        preparePad(level, hillCargoPos, 18);
        prepareHeadroomOnly(level, basicMinerPos, 6);
        level.setBlockAndUpdate(hillCargoPos, QaChecks.block("lc2:cargo_station").defaultBlockState());
        placeHorizontal(level, basicMinerPos, "ic2:miner", Direction.SOUTH);

        // Power Grid A2 on WEST flank: copper cables connecting generators + batbox to BOTH Cargo Station and Miner
        BlockPos hillCable0 = hillCargoPos.west();
        BlockPos hillCable1 = basicMinerPos.west();
        BlockPos hillBatBox = hillCable0.west();
        BlockPos hillGen1 = hillCable1.west();
        BlockPos hillGen2 = hillBatBox.west();
        BlockPos hillGen3 = hillBatBox.south();
        BlockPos hillGen4 = hillBatBox.above();

        for (BlockPos p : List.of(hillCable0, hillCable1, hillBatBox, hillGen1, hillGen2, hillGen3)) {
            preparePad(level, p, 4);
        }
        prepareHeadroomOnly(level, hillGen4, 2);
        placeFacing(level, hillBatBox, "ic2:batbox", Direction.EAST);
        placeGeneratorWithCoal(level, hillGen1, Direction.SOUTH, 64);
        placeGeneratorWithCoal(level, hillGen2, Direction.SOUTH, 64);
        placeGeneratorWithCoal(level, hillGen3, Direction.SOUTH, 64);
        placeGeneratorWithCoal(level, hillGen4, Direction.SOUTH, 64);
        QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", hillCable0);
        QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", hillCable1);

        checks.check("field.cable.hillside_cargo_connected",
                cableConnectedToward(level.getBlockState(hillCable0), Direction.EAST),
                "state=" + level.getBlockState(hillCable0));

        // Equip ic2:miner via capability API: diamond_drill (slot 0), advanced_scanner (slot 1), 64x mining_pipe (slot 2)
        QaChecks.insertOrFail(level, basicMinerPos, new ItemStack(QaChecks.item("ic2:diamond_drill"), 1));
        QaChecks.insertOrFail(level, basicMinerPos, new ItemStack(QaChecks.item("ic2:advanced_scanner"), 1));
        QaChecks.insertOrFail(level, basicMinerPos, new ItemStack(QaChecks.item("ic2:mining_pipe"), 64));

        // Vanilla Redstone Comparator reading Hillside Cargo Station on EAST -> flat SOUTH loop back to Cargo Station
        // (keeps Cargo Station roof 100% open sky AND keeps redstone 2 blocks away from ic2:miner!)
        BlockPos compHill = hillCargoPos.east();
        BlockPos repHill = hillCargoPos.east(2);
        List<BlockPos> hillWirePath = List.of(
                hillCargoPos.east(3),
                hillCargoPos.east(3).south(),
                hillCargoPos.east(3).south(2),
                hillCargoPos.east(2).south(2),
                hillCargoPos.east(1).south(2),
                hillCargoPos.south(2),
                hillCargoPos.south()
        );
        preparePad(level, compHill, 3);
        preparePad(level, repHill, 3);
        for (BlockPos wp : hillWirePath) {
            preparePad(level, wp, 3);
        }
        level.setBlockAndUpdate(compHill, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, Direction.WEST)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE));
        level.setBlockAndUpdate(repHill, Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.FACING, Direction.WEST));
        for (BlockPos wp : hillWirePath) {
            level.setBlockAndUpdate(wp, Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        checks.check("field.outpost.basic_miner_unpowered_redstone",
                !level.hasNeighborSignal(basicMinerPos),
                "basicMinerPos=" + basicMinerPos);

        // =========================================================================
        // 3. MAIN REFINING BASE B (Natural Shore B): Smart Port + Receiving Chest + Cargo Station
        //    -> IC2 Macerator -> IC2 Electric Furnace -> Final Output IC2 Steel Storage Box + IC2 EU Grid
        // =========================================================================
        BlockPos portB = site.portB();
        Direction waterDirB = site.waterDirB();
        Direction backB = waterDirB.getOpposite();
        Direction flow = site.baseFlowDir();
        Direction oppFlow = flow.getOpposite();

        BlockPos rchPos = portB.relative(backB);
        BlockPos baseCargoPos = rchPos.relative(oppFlow);
        BlockPos macPos = rchPos.relative(flow);
        BlockPos efurPos = macPos.relative(flow);
        BlockPos outBoxPos = efurPos.relative(flow);

        preparePad(level, portB, 4);
        preparePad(level, rchPos, 4);
        preparePad(level, baseCargoPos, 18); // clear sky above drone landing pads!
        preparePad(level, macPos, 4);
        preparePad(level, efurPos, 4);
        preparePad(level, outBoxPos, 4);

        level.setBlockAndUpdate(portB,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, waterDirB));
        level.setBlockAndUpdate(rchPos, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(baseCargoPos, QaChecks.block("lc2:cargo_station").defaultBlockState());
        placeHorizontal(level, macPos, "ic2:macerator", waterDirB);
        placeHorizontal(level, efurPos, "ic2:electric_furnace", waterDirB);
        level.setBlockAndUpdate(outBoxPos, QaChecks.block("ic2:steel_storage_box").defaultBlockState());

        // Power Grid B: rear copper cable bus + connected spur around Base Cargo Station to Base Smart Port B + 2x BatBox + 6x coal Generators
        BlockPos bCablePort = portB.relative(oppFlow);
        BlockPos bCableSpur1 = bCablePort.relative(oppFlow);
        BlockPos bCableSpur2 = baseCargoPos.relative(oppFlow);
        BlockPos bCableSpur3 = bCableSpur2.relative(backB);
        BlockPos bCableCargo = baseCargoPos.relative(backB);
        BlockPos bCableRch = rchPos.relative(backB);
        BlockPos bCableMac = macPos.relative(backB);
        BlockPos bCableEfur = efurPos.relative(backB);
        BlockPos bBat1 = bCableCargo.relative(backB);
        BlockPos bBat2 = bCableMac.relative(backB);

        List<BlockPos> bGenerators = List.of(
                bBat1.relative(oppFlow),
                bBat1.relative(backB),
                bBat1.above(),
                bBat2.relative(flow),
                bBat2.relative(backB),
                bBat2.above()
        );

        for (BlockPos p : List.of(
                bCablePort, bCableSpur1, bCableSpur2, bCableSpur3,
                bCableCargo, bCableRch, bCableMac, bCableEfur, bBat1, bBat2)) {
            preparePad(level, p, 4);
        }
        for (BlockPos g : bGenerators) {
            if (g.getY() == bBat1.getY()) {
                preparePad(level, g, 4);
            } else {
                prepareHeadroomOnly(level, g, 2);
            }
        }

        placeFacing(level, bBat1, "ic2:batbox", waterDirB);
        placeFacing(level, bBat2, "ic2:batbox", waterDirB);
        for (BlockPos g : bGenerators) {
            placeGeneratorWithCoal(level, g, waterDirB, 64);
        }
        for (BlockPos c : List.of(
                bCablePort, bCableSpur1, bCableSpur2, bCableSpur3,
                bCableCargo, bCableRch, bCableMac, bCableEfur)) {
            QaChecks.survivalPlace(level, builder, "ic2:insulated_copper_cable", c);
        }

        checks.check("field.cable.base_port_connected",
                cableConnectedToward(level.getBlockState(bCablePort), flow),
                "state=" + level.getBlockState(bCablePort));
        checks.check("field.cable.base_cargo_connected",
                cableConnectedToward(level.getBlockState(bCableCargo), waterDirB),
                "state=" + level.getBlockState(bCableCargo));

        // Configure Base Smart Port B & Base Cargo Station B
        SmartPortBlockEntity portBBe = (SmartPortBlockEntity) level.getBlockEntity(portB);
        CargoStationBlockEntity baseCargoBe = (CargoStationBlockEntity) level.getBlockEntity(baseCargoPos);
        checks.check("field.base.stations_ready", portBBe != null && baseCargoBe != null);

        portBBe.setStationName("Refinery Pier");
        portBBe.refreshWaterBerth(level);
        checks.check("field.base.port_b_berth_detected",
                portBBe.getWaterBerthPos() != null && portBBe.getWaterBerthPos().y() == site.waterY(),
                "berthB=" + portBBe.getWaterBerthPos());
        portBBe.setItem(SmartPortBlockEntity.UPGRADE_START, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_CHUNK_LOADING), 1));
        portBBe.setItem(SmartPortBlockEntity.UPGRADE_START + 1,
                routeUpgrade(level, builder, rchPos, "ic2:ejector_upgrade", backB, "portB_eject", checks));

        baseCargoBe.setStationName("Refinery Airpad");
        baseCargoBe.setEnergyPriority(EnergyPriority.EU_FIRST);
        baseCargoBe.setItem(CargoStationBlockEntity.UPGRADE_START, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_CHUNK_LOADING), 1));
        baseCargoBe.setItem(CargoStationBlockEntity.UPGRADE_START + 1,
                routeUpgrade(level, builder, rchPos, "ic2:ejector_upgrade", flow, "baseCargo_eject", checks));

        // Link Outpost Smart Port A -> Base Smart Port B
        portABe.setTargetPos(portB);

        // Configure Hillside Outpost Cargo Station -> Base Cargo Station B
        CargoStationBlockEntity hillCargoBe = (CargoStationBlockEntity) level.getBlockEntity(hillCargoPos);
        checks.check("field.outpost.hill_cargo_ready", hillCargoBe != null);
        hillCargoBe.setStationName("Ridge Airfield");
        hillCargoBe.setTargetPos(baseCargoPos);
        hillCargoBe.setAutoReturn(true);
        hillCargoBe.setEnergyPriority(EnergyPriority.EU_FIRST);

        int droneDist = Math.abs(hillCargoPos.getX() - baseCargoPos.getX())
                + Math.abs(hillCargoPos.getY() - baseCargoPos.getY())
                + Math.abs(hillCargoPos.getZ() - baseCargoPos.getZ());
        String rfChipId = droneDist <= 256 ? LogisticsConstants.RF_CHIP_1_ID
                : (droneDist <= 512 ? LogisticsConstants.RF_CHIP_2_ID : LogisticsConstants.RF_CHIP_3_ID);
        hillCargoBe.setItem(CargoStationBlockEntity.RF_CHIP_SLOT, new ItemStack(QaChecks.item(rfChipId), 1));
        hillCargoBe.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        hillCargoBe.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1,
                new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
        hillCargoBe.setItem(CargoStationBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_CHUNK_LOADING), 1));
        hillCargoBe.setItem(CargoStationBlockEntity.UPGRADE_START + 1,
                routeUpgrade(level, builder, hillCargoPos, "ic2:pulling_upgrade", minerDir, "hillCargo_pull", checks));
        hillCargoBe.setItem(CargoStationBlockEntity.UPGRADE_START + 2,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 2));

        // Configure IC2 Macerator (slot 3=pulling<-rch, slot 4=ejector->efur, slot 5=3x overclocker)
        IndustrialMachine macBe = (IndustrialMachine) level.getBlockEntity(macPos);
        IndustrialMachine efurBe = (IndustrialMachine) level.getBlockEntity(efurPos);
        checks.check("field.base.refining_machines_ready", macBe != null && efurBe != null);

        macBe.setItem(3, routeUpgrade(level, builder, macPos, "ic2:pulling_upgrade", oppFlow, "mac_pull", checks));
        macBe.setItem(4, routeUpgrade(level, builder, macPos, "ic2:ejector_upgrade", flow, "mac_eject", checks));
        macBe.setItem(5, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 3));

        // Configure IC2 Electric Furnace (slot 3=ejector->steel_storage_box, slot 4=2x overclocker)
        efurBe.setItem(3, routeUpgrade(level, builder, efurPos, "ic2:ejector_upgrade", flow, "efur_eject", checks));
        efurBe.setItem(4, new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_OVERCLOCKER), 2));

        return new BuiltFactory(
                site,
                portA,
                bufferChestA,
                advMinerPos,
                cableA0,
                compA,
                hillCargoPos,
                basicMinerPos,
                hillCable0,
                compHill,
                portB,
                rchPos,
                baseCargoPos,
                macPos,
                efurPos,
                outBoxPos,
                bCableCargo,
                initialAdvOres,
                initialBasicOres
        );
    }

    /** Configures a side-routed IC2 upgrade using real FakePlayer shift-right-click interaction and verifies it. */
    public static ItemStack routeUpgrade(
            ServerLevel level,
            FakePlayer player,
            BlockPos refPos,
            String upgradeItemId,
            Direction targetFace,
            String checkTag,
            QaChecks checks
    ) {
        ItemStack stack = new ItemStack(QaChecks.item(upgradeItemId), 1);
        player.setPos(refPos.getX() + 0.5, refPos.getY() + 1.5, refPos.getZ() + 0.5);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(refPos), targetFace, refPos, false);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        List<Direction> faces = MachineAutomation.faces(stack);
        checks.check("field.upgrade.routed." + checkTag,
                faces.equals(List.of(targetFace)),
                upgradeItemId + " -> " + faces);
        return stack;
    }

    private static void placeHorizontal(ServerLevel level, BlockPos pos, String blockId, Direction facing) {
        BlockState state = QaChecks.block(blockId).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        level.setBlockAndUpdate(pos, state);
    }

    private static void placeFacing(ServerLevel level, BlockPos pos, String blockId, Direction facing) {
        BlockState state = QaChecks.block(blockId).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        level.setBlockAndUpdate(pos, state);
    }

    private static void placeGeneratorWithCoal(ServerLevel level, BlockPos pos, Direction facing, int coalCount) {
        placeHorizontal(level, pos, "ic2:generator", facing);
        if (level.getBlockEntity(pos) instanceof IndustrialMachine gen) {
            gen.setItem(0, new ItemStack(Items.COAL, coalCount));
        }
    }

    private static void preparePad(ServerLevel level, BlockPos pos, int clearHeight) {
        BlockPos below = pos.below();
        if (level.getBlockState(below).isAir() || !level.getBlockState(below).getFluidState().isEmpty()) {
            level.setBlockAndUpdate(below, Blocks.COBBLESTONE.defaultBlockState());
        }
        prepareHeadroomOnly(level, pos, clearHeight);
    }

    private static void prepareHeadroomOnly(ServerLevel level, BlockPos pos, int clearHeight) {
        for (int dy = 0; dy < clearHeight; dy++) {
            BlockPos p = pos.above(dy);
            if (!level.getBlockState(p).isAir()) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean cableConnectedToward(BlockState cableState, Direction dir) {
        return switch (dir) {
            case NORTH -> cableState.hasProperty(BlockStateProperties.NORTH) && cableState.getValue(BlockStateProperties.NORTH);
            case SOUTH -> cableState.hasProperty(BlockStateProperties.SOUTH) && cableState.getValue(BlockStateProperties.SOUTH);
            case EAST -> cableState.hasProperty(BlockStateProperties.EAST) && cableState.getValue(BlockStateProperties.EAST);
            case WEST -> cableState.hasProperty(BlockStateProperties.WEST) && cableState.getValue(BlockStateProperties.WEST);
            case UP -> cableState.hasProperty(BlockStateProperties.UP) && cableState.getValue(BlockStateProperties.UP);
            case DOWN -> cableState.hasProperty(BlockStateProperties.DOWN) && cableState.getValue(BlockStateProperties.DOWN);
        };
    }

    private static List<BlockPos> snapshotMetallicOres(ServerLevel level, BlockPos origin, int radius, int maxDepth) {
        List<BlockPos> ores = new ArrayList<>();
        int floorY = Math.max(Lc2Native.minBuildY(level) + 4, origin.getY() - maxDepth);
        for (int y = origin.getY() - 1; y >= floorY; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    if (FieldWorldScanner.isSmeltableMetallicOre(level.getBlockState(p))) {
                        ores.add(p);
                    }
                }
            }
        }
        return ores;
    }

    private static List<BlockPos> snapshotAnyOres(ServerLevel level, BlockPos origin, int radius, int maxDepth) {
        List<BlockPos> ores = new ArrayList<>();
        int floorY = Math.max(Lc2Native.minBuildY(level) + 4, origin.getY() - maxDepth);
        for (int y = origin.getY() - 1; y >= floorY; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    if (FieldWorldScanner.isAnyOre(level.getBlockState(p))) {
                        ores.add(p);
                    }
                }
            }
        }
        return ores;
    }
}
