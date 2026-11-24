package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 2: Full Waterway Lifecycle (Smart Port + 54-slot Electric Smart Cargo Boat).
 * Covers:
 * - Chest + Shulker Box + IC2 iron_storage_box adjacent containers (>27 stacks = 42 stacks!)
 * - Stone dam -> WATER_DISCONNECTED
 * - BATTERY_FIRST (consumes 2 single_use_battery, EU untouched)
 * - Active transit riding block (isAutonomousTransit==true, canAddPassenger==false, interact==FAIL)
 * - Full 42-stack unload at Port B with 0 lost items
 * - EU_FIRST + autoReturn=true (3000 EU fails, 6000 EU succeeds)
 * - Real redstone trigger + ic2:redstone_inverter_upgrade
 * - Pre-launch seated passenger remains seated throughout autonomous transit
 * - Auto-return back to origin port
 */
public final class Suite2Waterway {

    private static final int WATER_Y = 63;
    private static final BlockPos PORT_A = new BlockPos(7, WATER_Y, 22);
    private static final BlockPos PORT_B = new BlockPos(35, WATER_Y, 22);

    private static final BlockPos A_CHEST = PORT_A.west();
    private static final BlockPos A_SHULKER = PORT_A.north();
    private static final BlockPos A_IC2_BOX = PORT_A.south();

    private static final BlockPos B_CHEST = PORT_B.east();
    private static final BlockPos B_SHULKER = PORT_B.north();
    private static final BlockPos B_IC2_BOX = PORT_B.south();
    private static final BlockPos B_REDSTONE = PORT_B.above();

    private enum Stage {
        SETUP,
        WAIT_LEG1_ARRIVAL,
        WAIT_INVERTER_LAUNCH,
        WAIT_ROUNDTRIP_RETURN,
        DONE
    }

    private Stage stage = Stage.SETUP;
    private int stageStartTick = 0;
    private final Map<String, Integer> expectedCargo = new TreeMap<>();
    private UUID leg1TransitId;
    private UUID leg2TransitId;
    private Mob blockedVisitor;
    private Mob prelaunchRider;
    private Mob secondVisitor;
    private boolean verifiedMidTransitRider = false;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case SETUP -> {
                runSetupAndLeg1(level, tick, checks);
                yield false;
            }
            case WAIT_LEG1_ARRIVAL -> {
                tickLeg1Arrival(level, tick, checks);
                yield false;
            }
            case WAIT_INVERTER_LAUNCH -> {
                tickWaitInverterLaunch(level, tick, checks);
                yield false;
            }
            case WAIT_ROUNDTRIP_RETURN -> {
                tickRoundTripReturn(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void runSetupAndLeg1(ServerLevel level, int tick, QaChecks checks) {
        FakePlayer player = FakePlayerFactory.getMinecraft(level);

        // Build 3-wide canal X=8..34, Z=21..23 at Y=63
        QaChecks.buildCanal(level, 8, 34, 21, 23, WATER_Y);

        // Place Port A (facing EAST) and Port B (facing WEST)
        level.setBlockAndUpdate(PORT_A,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(PORT_B,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.WEST));

        // Adjacent containers at Port A (Chest + Shulker Box + IC2 Iron Storage Box)
        level.setBlockAndUpdate(A_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(A_SHULKER, Blocks.SHULKER_BOX.defaultBlockState());
        level.setBlockAndUpdate(A_IC2_BOX, QaChecks.block("ic2:iron_storage_box").defaultBlockState());

        // Adjacent empty containers at Port B (Chest + Shulker Box + IC2 Iron Storage Box)
        level.setBlockAndUpdate(B_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(B_SHULKER, Blocks.SHULKER_BOX.defaultBlockState());
        level.setBlockAndUpdate(B_IC2_BOX, QaChecks.block("ic2:iron_storage_box").defaultBlockState());

        // Load 42 distinct item stacks (> 27 slots!) across Chest (20) + Shulker (12) + IC2 Box (10)
        expectedCargo.clear();
        for (int i = 0; i < 42; i++) {
            Item item = QaChecks.DISTINCT_CARGO_ITEMS.get(i);
            ItemStack stack = new ItemStack(item, 8);
            BlockPos containerPos = i < 20 ? A_CHEST : (i < 32 ? A_SHULKER : A_IC2_BOX);
            QaChecks.insertOrFail(level, containerPos, stack);
            expectedCargo.merge(Lc2Native.itemId(stack), 8, Integer::sum);
        }
        checks.check("s2.setup.42_stacks_loaded",
                QaChecks.countStacksAcross(level, A_CHEST, A_SHULKER, A_IC2_BOX) == 42,
                "42 stacks across Chest+Shulker+IC2Box");

        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(PORT_A);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PORT_B);
        checks.check("s2.setup.ports_ready", portA != null && portB != null);
        portA.setStationName("Port Alpha");
        portB.setStationName("Port Beta");
        portA.refreshWaterBerth(level);
        portB.refreshWaterBerth(level);
        checks.check("s2.setup.berths_detected",
                portA.getWaterBerthPos() != null && portB.getWaterBerthPos() != null,
                "A=" + portA.getWaterBerthPos() + ", B=" + portB.getWaterBerthPos());

        // Strict 2-zone semantics: install pulling_upgrade on Port A and ejector_upgrade on Port B
        // so 42 stacks (>27 internal slots) pull from adjacent containers and eject cleanly at Port B!
        portA.setItem(SmartPortBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_PULLING), 1));
        portB.setItem(SmartPortBlockEntity.UPGRADE_START + 1,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_EJECTOR), 1));

        // Spawn and dock Electric Cargo Boat at Port A
        boolean docked = portA.spawnAndDockBoat(
                level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get()));
        checks.check("s2.setup.boat_docked", docked && portA.findDockedBoat(level).isPresent());
        portA.setTargetPos(PORT_B);

        // 0. GUI water connectivity MUST report connected BEFORE any dispatch ever runs (regression check!)
        StationSyncPayload initialSync = portA.buildSyncPayload(level);
        StationSyncPayload.TargetInfo initialInfo = initialSync.selectedTargetInfo();
        checks.check("s2.gui.water_connected_before_first_dispatch",
                initialInfo != null && initialInfo.reachableFlag(),
                "info=" + initialInfo);

        // Switching to an isolated dry Smart Port MUST immediately report disconnected (never inherit cached true)
        BlockPos dryPortPos = PORT_A.offset(0, 0, -6);
        level.setBlockAndUpdate(dryPortPos,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.NORTH));
        SmartPortBlockEntity dryPort = (SmartPortBlockEntity) level.getBlockEntity(dryPortPos);
        if (dryPort != null) {
            dryPort.setStationName("Dry Port");
            dryPort.refreshWaterBerth(level);
        }
        portA.setTargetPos(dryPortPos);
        StationSyncPayload drySync = portA.buildSyncPayload(level);
        checks.check("s2.gui.target_switch_dry_port_disconnected",
                drySync.selectedTargetInfo() != null && !drySync.selectedTargetInfo().reachableFlag(),
                "dryInfo=" + drySync.selectedTargetInfo());
        level.setBlockAndUpdate(dryPortPos, Blocks.AIR.defaultBlockState());
        portA.setTargetPos(PORT_B);

        // 1. Stone dam blocking canal -> GUI sync MUST detect blockage immediately on reopen AND dispatch MUST fail
        for (int z = 21; z <= 23; z++) {
            level.setBlockAndUpdate(new BlockPos(20, WATER_Y, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(20, WATER_Y + 1, z), Blocks.STONE.defaultBlockState());
        }
        portA.requestImmediateWaterCheck();
        StationSyncPayload damGuiSync = portA.buildSyncPayload(level);
        checks.check("s2.gui.dam_flips_sync_disconnected_without_dispatch",
                damGuiSync.selectedTargetInfo() != null && !damGuiSync.selectedTargetInfo().reachableFlag(),
                "damInfo=" + damGuiSync.selectedTargetInfo());

        portA.addEnergy(3000.0);
        portA.setAutoReturn(false);
        portA.setEnergyPriority(EnergyPriority.EU_FIRST);
        VirtualTransitEngine.DispatchResult damRes = portA.tryDispatch(level, true);
        checks.check("s2.canal.dam_blocks_dispatch",
                !damRes.isSuccess() && damRes.failure() == DispatchFailure.WATER_DISCONNECTED,
                "failure=" + damRes.failure());

        // Clear stone dam -> GUI reopen MUST immediately flip back to connected AND clear stale WATER_DISCONNECTED status
        for (int z = 21; z <= 23; z++) {
            level.setBlockAndUpdate(new BlockPos(20, WATER_Y, z), Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(20, WATER_Y + 1, z), Blocks.AIR.defaultBlockState());
        }
        portA.requestImmediateWaterCheck();
        StationSyncPayload reopenedGuiSync = portA.buildSyncPayload(level);
        checks.check("s2.gui.reopened_canal_sync_connected_before_dispatch",
                reopenedGuiSync.selectedTargetInfo() != null
                        && reopenedGuiSync.selectedTargetInfo().reachableFlag()
                        && "status.lc2.ready".equals(reopenedGuiSync.statusKey()),
                "reopenedInfo=" + reopenedGuiSync.selectedTargetInfo() + ", status=" + reopenedGuiSync.statusKey());

        // 2. EU_FIRST with autoReturn=true at 3000 EU -> MUST fail (requires 6000 EU round-trip!)
        portA.setAutoReturn(true);
        portA.setEnergyPriority(EnergyPriority.EU_FIRST);
        VirtualTransitEngine.DispatchResult lowEuRes = portA.tryDispatch(level, true);
        checks.check("s2.energy.roundtrip_3000_eu_fails",
                !lowEuRes.isSuccess() && lowEuRes.failure() == DispatchFailure.INSUFFICIENT_ENERGY,
                "failure=" + lowEuRes.failure() + ", eu=" + portA.getStoredEu());

        // 3. BATTERY_FIRST one-way dispatch (consumes 2 ic2:single_use_battery, leaves 3000 EU untouched!)
        portA.setAutoReturn(false);
        portA.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        portA.setItem(SmartPortBlockEntity.BATTERY_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 4));
        double euBefore = portA.getStoredEu();
        VirtualTransitEngine.DispatchResult leg1 = portA.tryDispatch(level, true);
        checks.check("s2.energy.battery_first_dispatch",
                leg1.isSuccess(), "failure=" + leg1.failure());
        checks.check("s2.energy.battery_first_exact_cost",
                portA.availableSingleUseBatteries() == 2 && Math.abs(portA.getStoredEu() - euBefore) < 1e-6,
                "batteriesLeft=" + portA.availableSingleUseBatteries() + ", eu=" + portA.getStoredEu());

        leg1TransitId = leg1.transit().id();
        Entity liveBoat = LogisticsLevelManager.findLiveEntity(level, leg1TransitId);
        checks.check("s2.riding.boat_autonomous_active",
                liveBoat instanceof ElectricCargoBoatEntity boat && boat.isAutonomousTransit());
        ElectricCargoBoatEntity activeBoat = (ElectricCargoBoatEntity) liveBoat;

        // 4. Verify active-transit boarding block: canAddPassenger==false, startRiding==false, interact==FAIL
        blockedVisitor = QaPlatform.spawnPassenger(level, activeBoat.getX(), activeBoat.getY(), activeBoat.getZ());
        boolean canAdd = QaChecks.invokeCanAddPassenger(activeBoat, blockedVisitor);
        boolean startedRiding = blockedVisitor.startRiding(activeBoat);
        InteractionResult interactRes = QaPlatform.interactEntity(activeBoat, player);
        checks.check("s2.riding.blocked_during_active_transit",
                !canAdd && !startedRiding && activeBoat.getPassengers().isEmpty() && interactRes == InteractionResult.FAIL,
                "canAdd=" + canAdd + ", startRiding=" + startedRiding + ", interact=" + interactRes);
        blockedVisitor.discard();

        // Confirm all 42 stacks were pulled out of Port A's 3 adjacent containers into transit cargo
        int transitStacks = LogisticsLevelManager.getData(level).getCargo(leg1TransitId).size();
        checks.check("s2.cargo.54slot_hold_loaded_42_stacks",
                transitStacks == 42 && QaChecks.countStacksAcross(level, A_CHEST, A_SHULKER, A_IC2_BOX) == 0,
                "transitStacks=" + transitStacks);

        stage = Stage.WAIT_LEG1_ARRIVAL;
        stageStartTick = tick;
    }

    private void tickLeg1Arrival(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 250) {
            throw new AssertionError("Timeout waiting for Suite 2 leg 1 boat arrival at Port B");
        }
        var data = LogisticsLevelManager.getData(level);
        if (data.engine().getTransit(leg1TransitId).isPresent()) {
            return;
        }

        // Verify all 42 stacks arrived at Port B's adjacent containers with 0 lost items
        Map<String, Integer> arrived = QaChecks.countItemsAcross(level, B_CHEST, B_SHULKER, B_IC2_BOX);
        checks.check("s2.cargo.42_stacks_delivered_zero_loss",
                expectedCargo.equals(arrived),
                "expectedKinds=" + expectedCargo.size() + ", arrivedKinds=" + arrived.size());

        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PORT_B);
        var dockedAtB = portB.findDockedBoat(level);
        checks.check("s2.leg1.boat_docked_at_port_b",
                dockedAtB.isPresent() && !dockedAtB.get().isAutonomousTransit());

        // Set up Leg 2 from Port B -> Port A with EU_FIRST, autoReturn=true (6000 EU),
        // real redstone + ic2:redstone_inverter_upgrade, AND pre-launch seated passenger!
        portB.setTargetPos(PORT_A);
        portB.setAutoReturn(true);
        portB.setEnergyPriority(EnergyPriority.EU_FIRST);
        portB.addEnergy(3000.0);
        VirtualTransitEngine.DispatchResult bLowEu = portB.tryDispatch(level, true);
        checks.check("s2.leg2.3000_eu_insufficient_for_autoreturn",
                !bLowEu.isSuccess() && bLowEu.failure() == DispatchFailure.INSUFFICIENT_ENERGY);
        portB.addEnergy(3500.0); // Total 6500 EU

        // Verify normal redstone inactive blocks non-manual dispatch
        VirtualTransitEngine.DispatchResult noRedstone = portB.tryDispatch(level, false);
        checks.check("s2.redstone.unpowered_blocks_without_inverter",
                !noRedstone.isSuccess() && noRedstone.failure() == DispatchFailure.REDSTONE_INACTIVE);

        // Install ic2:redstone_inverter_upgrade FIRST (before powering), then place REDSTONE_BLOCK
        // (note: placing REDSTONE_BLOCK before installing the inverter would legitimately trigger immediate dispatch!)
        portB.setItem(SmartPortBlockEntity.UPGRADE_START,
                new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_REDSTONE_INVERTER), 1));
        level.setBlockAndUpdate(B_REDSTONE, Blocks.REDSTONE_BLOCK.defaultBlockState());
        checks.check("s2.redstone.inverter_blocked_when_powered",
                !portB.isRedstoneTriggered(level)
                        && portB.tryDispatch(level, false).failure() == DispatchFailure.REDSTONE_INACTIVE,
                "neighborSignal=" + level.hasNeighborSignal(PORT_B));

        // Seat pre-launch passenger inside docked boat BEFORE launch!
        ElectricCargoBoatEntity boat = dockedAtB.get();
        prelaunchRider = QaPlatform.spawnPassenger(level, boat.getX(), boat.getY(), boat.getZ());
        boolean canAddIdle = QaChecks.invokeCanAddPassenger(boat, prelaunchRider);
        boolean seatedBefore = prelaunchRider.startRiding(boat);
        checks.check("s2.riding.prelaunch_passenger_seated",
                seatedBefore && boat.getPassengers().contains(prelaunchRider),
                "seated=" + seatedBefore + ", canAddIdle=" + canAddIdle
                        + ", autonomous=" + boat.isAutonomousTransit()
                        + ", passengers=" + boat.getPassengers().size()
                        + ", boatY=" + boat.getY() + ", eyeY=" + boat.getEyeY()
                        + ", inWater=" + boat.isInWater() + ", removed=" + boat.isRemoved()
                        + ", riderRemoved=" + prelaunchRider.isRemoved()
                        + ", riderVehicle=" + prelaunchRider.getVehicle());

        // Remove redstone block -> Port B with inverter upgrade becomes triggered and auto-dispatches on tick!
        level.setBlockAndUpdate(B_REDSTONE, Blocks.AIR.defaultBlockState());
        checks.check("s2.redstone.inverter_triggers_when_unpowered", portB.isRedstoneTriggered(level));

        stage = Stage.WAIT_INVERTER_LAUNCH;
        stageStartTick = tick;
    }

    private void tickWaitInverterLaunch(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 60) {
            throw new AssertionError("Timeout waiting for Port B redstone inverter auto-dispatch");
        }
        var data = LogisticsLevelManager.getData(level);
        var active = data.engine().activeTransits().stream()
                .filter(t -> t.vehicleKind().isBoat() && t.originPos().x() == PORT_B.getX())
                .findFirst();
        if (active.isEmpty()) {
            return;
        }
        leg2TransitId = active.get().id();
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PORT_B);
        checks.check("s2.energy.roundtrip_6000_eu_charged",
                Math.abs(portB.getStoredEu() - 500.0) < 1e-6,
                "remainingEu=" + portB.getStoredEu());

        // Remove inverter upgrade so Port B doesn't immediately re-dispatch after boat returns
        portB.setItem(SmartPortBlockEntity.UPGRADE_START, ItemStack.EMPTY);

        // Verify pre-launch passenger is STILL seated and a second passenger is blocked during transit
        Entity live = LogisticsLevelManager.findLiveEntity(level, leg2TransitId);
        checks.check("s2.riding.prelaunch_passenger_kept_on_launch",
                live instanceof ElectricCargoBoatEntity boat
                        && boat.isAutonomousTransit()
                        && boat.getPassengers().contains(prelaunchRider));
        ElectricCargoBoatEntity boat = (ElectricCargoBoatEntity) live;
        secondVisitor = QaPlatform.spawnPassenger(level, boat.getX(), boat.getY(), boat.getZ());
        checks.check("s2.riding.second_passenger_blocked_in_transit",
                !QaChecks.invokeCanAddPassenger(boat, secondVisitor) && !secondVisitor.startRiding(boat));
        secondVisitor.discard();

        stage = Stage.WAIT_ROUNDTRIP_RETURN;
        stageStartTick = tick;
    }

    private void tickRoundTripReturn(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 450) {
            throw new AssertionError("Timeout waiting for Suite 2 auto-return boat to return to Port B");
        }
        var data = LogisticsLevelManager.getData(level);
        var recOpt = data.engine().getTransit(leg2TransitId);
        if (recOpt.isPresent()) {
            if (!verifiedMidTransitRider && tick - stageStartTick >= 15) {
                Entity live = LogisticsLevelManager.findLiveEntity(level, leg2TransitId);
                checks.check("s2.riding.prelaunch_passenger_stays_seated_mid_voyage",
                        live instanceof ElectricCargoBoatEntity boat
                                && boat.isAutonomousTransit()
                                && boat.getPassengers().contains(prelaunchRider)
                                && recOpt.get().hasRider(),
                        "posX=" + (live != null ? live.getX() : -1));
                verifiedMidTransitRider = true;
            }
            return;
        }

        checks.check("s2.riding.verified_mid_transit_rider", verifiedMidTransitRider);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PORT_B);
        var returnedBoat = portB.findDockedBoat(level);
        checks.check("s2.autoreturn.boat_returned_to_origin_port_b",
                returnedBoat.isPresent() && !returnedBoat.get().isAutonomousTransit());

        // Also confirm positive (non-inverted) redstone signal triggers Port A
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(PORT_A);
        level.setBlockAndUpdate(PORT_A.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        boolean highTriggered = portA.isRedstoneTriggered(level);
        level.setBlockAndUpdate(PORT_A.above(), Blocks.AIR.defaultBlockState());
        boolean lowTriggered = portA.isRedstoneTriggered(level);
        checks.check("s2.redstone.normal_signal_behavior", highTriggered && !lowTriggered);

        // Clean up passenger
        if (prelaunchRider != null && !prelaunchRider.isRemoved()) {
            prelaunchRider.stopRiding();
            prelaunchRider.discard();
        }
        stage = Stage.DONE;
    }
}
