package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.entity.CargoDroneEntity;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.gui.CargoStationMenu;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.util.LogisticsChunkTickets;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import com.logisticscraft.lc2.forge.world.LogisticsSavedData;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Tick-driven live dedicated-server end-to-end verification suite for LogisticsCraft² (Forge 1.19.2).
 * Runs all 7 verification scenarios across {@code write} and cold-restart {@code read} phases.
 */
final class Lc2QaRunner {

    private static final int SURFACE_Y = -58;
    private static final int WATER_Y = -59;

    private static final Path WRITE_REPORT = Path.of("qa-write.properties");
    private static final Path READ_REPORT = Path.of("qa-read.properties");

    private enum Stage {
        INIT,
        // Scenario 1: Registry, Recipes, IC2 Generator + CableBlockMixin + Real EU + Upgrades
        S1_SETUP,
        S1_WAIT_EU,
        // Scenario 2: Waterway Full Lifecycle
        S2_SETUP_AND_BATTERY_DISPATCH,
        S2_WAIT_BOAT_TO_B,
        S2_INVERTER_HOLD_CHECK,
        S2_WAIT_INVERTER_AUTO_DISPATCH,
        S2_WAIT_ROUND_TRIP_RETURN,
        // Scenario 3: Airway Full Lifecycle
        S3_RF_AND_COST_PROBES,
        S3_WAIT_STANDARD_DRONE_12,
        S3_WAIT_HEAVY_DRONE_36,
        S3_BAYS_AND_AUTO_RETURN_START,
        S3_WAIT_BAY_DRONE_AND_RETURN_ARRIVAL,
        S3_WAIT_UNPOWERED_HOLD,
        S3_WAIT_TARGET_PAID_RETURN,
        // Scenario 4: Unloaded-Chunk Virtual Transit
        S4_SETUP_DISTANT,
        S4_WAIT_CHUNKS_UNLOAD,
        S4_WAIT_UNLOADED_TRANSIT_ARRIVAL,
        S4_WAIT_TARGET_LOAD_UNLOAD,
        // Scenario 5: Chunk Loading Upgrade
        S5_SETUP,
        S5_WAIT_HOLD_10_TICKS,
        S5_WAIT_REMOVE_1_TICK,
        S5_WAIT_DEPLETE_3_TICKS,
        S5_WAIT_DEPLETE_RELEASE_1_TICK,
        // Scenario 6: Multi-Vehicle Collision Avoidance
        S6_SETUP_AND_DISPATCH,
        S6_WAIT_COLLISION_COMPLETION,
        // Scenario 8: 2-Zone Isolation, Swap, NBT Migration, Quick-Move & Items-Before-Vehicle Regression
        S8_ZONE_ISOLATION_SETUP,
        S8_ZONE_ISOLATION_VERIFY,
        S8_ITEMS_BEFORE_VEHICLE_SETUP,
        S8_ITEMS_BEFORE_VEHICLE_PLACE,
        S8_ITEMS_BEFORE_VEHICLE_LAUNCH_CHECK,
        S8_ITEMS_BEFORE_VEHICLE_ARRIVAL,
        // Scenario 7: Cold-Restart Persistence (write phase)
        S7_WRITE_DISPATCH,
        S7_WRITE_MIDFLIGHT_SAVE_AND_HALT,
        // Scenario 7: Cold-Restart Persistence (read phase)
        S7_READ_VERIFY_RELOADED,
        S7_READ_WAIT_COMPLETION,
        DONE
    }

    private final String phase;
    private final Lc2QaAssert qa;
    private Stage stage = Stage.INIT;
    private int stageTicks = 0;
    private boolean finished = false;

    // Scenario 1 refs
    private final BlockPos s1GenPos = new BlockPos(10, SURFACE_Y, 0);
    private final BlockPos s1CablePortPos = new BlockPos(11, SURFACE_Y, 0);
    private final BlockPos s1PortPos = new BlockPos(12, SURFACE_Y, 0);
    private final BlockPos s1CableStationPos = new BlockPos(9, SURFACE_Y, 0);
    private final BlockPos s1StationPos = new BlockPos(8, SURFACE_Y, 0);

    // Scenario 2 refs
    private final BlockPos s2PortAPos = new BlockPos(0, WATER_Y, 30);
    private final BlockPos s2PortBPos = new BlockPos(38, WATER_Y, 30);
    private final List<BlockPos> s2PortAContainers = List.of(
            new BlockPos(-1, WATER_Y, 30),
            new BlockPos(0, WATER_Y, 29),
            new BlockPos(0, WATER_Y, 31)
    );
    private final List<BlockPos> s2PortBContainers = List.of(
            new BlockPos(39, WATER_Y, 30),
            new BlockPos(38, WATER_Y, 29),
            new BlockPos(38, WATER_Y, 31)
    );
    private final BlockPos s2PortBRedstonePos = new BlockPos(38, WATER_Y + 1, 30);
    private UUID s2BoatTransitId;
    private Map<Item, Integer> s2ExpectedCargo;
    private boolean s2RidingBlockedChecked = false;
    private Villager s2PreLaunchRider;
    private UUID s2ReturnBoatTransitId;
    private boolean s2PreLaunchRiderMidChecked = false;

    // Scenario 3 refs
    private final BlockPos s3CargoAPos = new BlockPos(0, SURFACE_Y, 70);
    private final BlockPos s3CargoBPos = new BlockPos(48, SURFACE_Y, 70);
    private final List<BlockPos> s3CargoAContainers = List.of(
            new BlockPos(-1, SURFACE_Y, 70),
            new BlockPos(0, SURFACE_Y, 69)
    );
    private final List<BlockPos> s3CargoBContainers = List.of(
            new BlockPos(49, SURFACE_Y, 70),
            new BlockPos(48, SURFACE_Y, 69)
    );
    private UUID s3StdDroneTransitId;
    private UUID s3HeavyDroneTransitId;
    private final BlockPos s3BayOrig1Pos = new BlockPos(0, SURFACE_Y, 82);
    private final BlockPos s3BayOrig2Pos = new BlockPos(4, SURFACE_Y, 82);
    private final BlockPos s3BayTargetPos = new BlockPos(32, SURFACE_Y, 82);
    private UUID s3BayReservedTransitId;
    private final BlockPos s3RetAPos = new BlockPos(0, SURFACE_Y, 94);
    private final BlockPos s3RetBPos = new BlockPos(32, SURFACE_Y, 94);
    private final BlockPos s3RetBChestPos = new BlockPos(33, SURFACE_Y, 94);
    private UUID s3RetTransitId;

    // Scenario 4 refs
    private final BlockPos s4PortAPos = new BlockPos(0, WATER_Y, 120);
    private final BlockPos s4PortBPos = new BlockPos(352, WATER_Y, 120);
    private final List<BlockPos> s4PortAContainers = List.of(new BlockPos(-1, WATER_Y, 120));
    private final List<BlockPos> s4PortBContainers = List.of(
            new BlockPos(353, WATER_Y, 120),
            new BlockPos(352, WATER_Y, 119)
    );
    private final BlockPos s4StationAPos = new BlockPos(0, SURFACE_Y, 132);
    private final BlockPos s4StationBPos = new BlockPos(352, SURFACE_Y, 132);
    private final BlockPos s4StationAChestPos = new BlockPos(-1, SURFACE_Y, 132);
    private final BlockPos s4StationBChestPos = new BlockPos(353, SURFACE_Y, 132);
    private UUID s4BoatTransitId;
    private UUID s4DroneTransitId;
    private boolean s4BoatObservedUnloaded = false;
    private boolean s4DroneObservedUnloaded = false;
    private final List<ChunkPos> s4HeldTempChunks = new ArrayList<>();

    // Scenario 5 refs
    private final BlockPos s5StationPos = new BlockPos(64, SURFACE_Y, 150);

    // Scenario 6 refs
    private final BlockPos s6PortAPos = new BlockPos(0, WATER_Y, -40);
    private final BlockPos s6PortBPos = new BlockPos(44, WATER_Y, -40);
    private final BlockPos s6PortAChestPos = new BlockPos(0, WATER_Y + 1, -40);
    private final BlockPos s6PortBChestPos = new BlockPos(44, WATER_Y + 1, -40);
    private final BlockPos s6DroneAPos = new BlockPos(0, SURFACE_Y, -60);
    private final BlockPos s6DroneBPos = new BlockPos(48, SURFACE_Y, -60);
    private UUID s6BoatABId;
    private UUID s6BoatBAId;
    private final List<UUID> s6DroneIds = new ArrayList<>();
    private double s6MinBoatDist = Double.POSITIVE_INFINITY;
    private boolean s6BoatsPassedEachOther = false;
    private double s6MinDroneCruiseDist = Double.POSITIVE_INFINITY;

    // Scenario 7 refs
    private final BlockPos s7PortAPos = new BlockPos(-40, WATER_Y, -90);
    private final BlockPos s7PortBPos = new BlockPos(40, WATER_Y, -90);
    private final BlockPos s7PortBChestPos = new BlockPos(41, WATER_Y, -90);
    private final BlockPos s7StationAPos = new BlockPos(-40, SURFACE_Y, -110);
    private final BlockPos s7StationBPos = new BlockPos(40, SURFACE_Y, -110);
    private final BlockPos s7StationBChestPos = new BlockPos(41, SURFACE_Y, -110);
    private UUID s7BoatTransitId;
    private UUID s7DroneTransitId;
    private int s7ExpectedBoatItems = 320;
    private int s7ExpectedDroneItems = 256;

    // Scenario 8 refs: 2-Zone Isolation, Swap, Legacy Migration & Items-Before-Vehicle Regression
    private final BlockPos s8HopperPortPos = new BlockPos(-20, SURFACE_Y + 1, 0);
    private final BlockPos s8HopperTopPos = new BlockPos(-20, SURFACE_Y + 2, 0);
    private final BlockPos s8HopperBottomPos = new BlockPos(-20, SURFACE_Y, 0);
    private final BlockPos s8UpgStationPos = new BlockPos(-28, SURFACE_Y, 0);
    private final BlockPos s8UpgWestChestPos = new BlockPos(-29, SURFACE_Y, 0);
    private final BlockPos s8UpgEastChestPos = new BlockPos(-27, SURFACE_Y, 0);
    private final BlockPos s8UpgNorthChestPos = new BlockPos(-28, SURFACE_Y, -1);
    private final BlockPos s8UpgDummyTargetPos = new BlockPos(-28, SURFACE_Y, 12);

    private final BlockPos s8DroneOrigPos = new BlockPos(-20, SURFACE_Y, -16);
    private final BlockPos s8DroneTargetPos = new BlockPos(4, SURFACE_Y, -16);
    private final BlockPos s8DroneRedstonePos = new BlockPos(-21, SURFACE_Y, -16);
    private final BlockPos s8BoatWaterOrigPos = new BlockPos(-20, WATER_Y, -26);
    private final BlockPos s8BoatWaterTargetPos = new BlockPos(0, WATER_Y, -26);
    private final BlockPos s8BoatWaterRedstonePos = new BlockPos(-21, WATER_Y, -26);
    private final BlockPos s8BoatDockOrigPos = new BlockPos(-20, WATER_Y, 14);
    private final BlockPos s8BoatDockTargetPos = new BlockPos(0, WATER_Y, 14);
    private final BlockPos s8BoatDockRedstonePos = new BlockPos(-21, WATER_Y, 14);
    private UUID s8ItemsFirstDroneId;
    private UUID s8ItemsFirstBoatWaterId;
    private UUID s8ItemsFirstBoatDockId;

    Lc2QaRunner(String phase) {
        this.phase = phase;
        this.qa = new Lc2QaAssert(phase);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        // Keep headless dedicated-server Overworld from hitting vanilla's 300-tick empty-server
        // block-entity/entity pause when no players are connected (does not load any chunks).
        level.resetEmptyTime();

        try {
            step(server, level);
        } catch (Throwable error) {
            finished = true;
            Lc2QaMod.LOGGER.error("[LC2-QA] Unhandled QA failure in stage {}", stage, error);
            Path report = "write".equals(phase) ? WRITE_REPORT : READ_REPORT;
            Path failureTxt = Path.of("qa-" + phase + "-failure.txt");
            qa.writeFailureReport(report, failureTxt, error);
            server.halt(false);
        }
    }

    private void advance(Stage next) {
        this.stage = next;
        this.stageTicks = 0;
    }

    private void step(MinecraftServer server, ServerLevel level) throws Exception {
        stageTicks++;
        switch (stage) {
            case INIT -> {
                // Wait 2 ticks for initial world/power graph setup
                if (stageTicks < 2) {
                    return;
                }
                if ("write".equals(phase)) {
                    level.setDefaultSpawnPos(new BlockPos(0, -60, 0), 0.0F);
                    advance(Stage.S1_SETUP);
                } else {
                    advance(Stage.S7_READ_VERIFY_RELOADED);
                }
            }

            // =========================================================================
            // SCENARIO 1: Mod, Registry, Mixin & Recipe Integrity + Real IC2 EU Flow
            // =========================================================================
            case S1_SETUP -> {
                runScenario1Setup(server, level);
                advance(Stage.S1_WAIT_EU);
            }
            case S1_WAIT_EU -> {
                if (stageTicks < 50) {
                    return;
                }
                runScenario1VerifyEuAndUpgrades(level);
                advance(Stage.S2_SETUP_AND_BATTERY_DISPATCH);
            }

            // =========================================================================
            // SCENARIO 2: Waterway Full Lifecycle (Smart Port + Electric Cargo Boat)
            // =========================================================================
            case S2_SETUP_AND_BATTERY_DISPATCH -> {
                runScenario2SetupAndBatteryDispatch(level);
                advance(Stage.S2_WAIT_BOAT_TO_B);
            }
            case S2_WAIT_BOAT_TO_B -> {
                tickScenario2WaitBoatToB(level);
            }
            case S2_INVERTER_HOLD_CHECK -> {
                if (stageTicks < 4) {
                    return;
                }
                SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s2PortBPos);
                qa.check("s2.inverter_blocks_when_powered",
                        portB != null && portB.findDockedBoat(level) != null && !portB.tryDispatch(level, false),
                        "status=" + (portB == null ? "null" : portB.getStatusKey()));
                qa.checkEquals("s2.inverter_powered_status",
                        DispatchFailure.REDSTONE_INACTIVE.translationKey(), portB.getStatusKey());
                // Remove redstone block -> unpowered + inverter should trigger automatic dispatch!
                level.setBlock(s2PortBRedstonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                advance(Stage.S2_WAIT_INVERTER_AUTO_DISPATCH);
            }
            case S2_WAIT_INVERTER_AUTO_DISPATCH -> {
                tickScenario2WaitInverterAutoDispatch(level);
            }
            case S2_WAIT_ROUND_TRIP_RETURN -> {
                tickScenario2WaitRoundTripReturn(level);
            }

            // =========================================================================
            // SCENARIO 3: Airway Full Lifecycle (Cargo Station + Drones + RF Chips)
            // =========================================================================
            case S3_RF_AND_COST_PROBES -> {
                runScenario3RfCostProbesAndStartStdDrone(level);
                advance(Stage.S3_WAIT_STANDARD_DRONE_12);
            }
            case S3_WAIT_STANDARD_DRONE_12 -> {
                tickScenario3WaitStdDrone(level);
            }
            case S3_WAIT_HEAVY_DRONE_36 -> {
                tickScenario3WaitHeavyDrone(level);
            }
            case S3_BAYS_AND_AUTO_RETURN_START -> {
                runScenario3BaysAndAutoReturnStart(level);
                advance(Stage.S3_WAIT_BAY_DRONE_AND_RETURN_ARRIVAL);
            }
            case S3_WAIT_BAY_DRONE_AND_RETURN_ARRIVAL -> {
                tickScenario3WaitBayAndReturnArrival(level);
            }
            case S3_WAIT_UNPOWERED_HOLD -> {
                if (stageTicks < 20) {
                    return;
                }
                int waiting = LogisticsLevelManager.countWaitingReturnAt(level, s3RetBPos);
                qa.checkEquals("s3.auto_return_still_waiting_unpowered", 1, waiting);
                // Now supply target station with 1000 EU (return cost for dist=32 Standard Drone = 800 EU)
                CargoStationBlockEntity retB = (CargoStationBlockEntity) level.getBlockEntity(s3RetBPos);
                qa.check("s3.retB_present", retB != null, "retB at " + s3RetBPos);
                retB.setStoredEu(1000.0);
                advance(Stage.S3_WAIT_TARGET_PAID_RETURN);
            }
            case S3_WAIT_TARGET_PAID_RETURN -> {
                tickScenario3WaitTargetPaidReturn(level);
            }

            // =========================================================================
            // SCENARIO 4: Unloaded-Chunk Virtual Transit (Create-Train Style)
            // =========================================================================
            case S4_SETUP_DISTANT -> {
                runScenario4SetupDistant(level);
                advance(Stage.S4_WAIT_CHUNKS_UNLOAD);
            }
            case S4_WAIT_CHUNKS_UNLOAD -> {
                tickScenario4WaitChunksUnload(level);
            }
            case S4_WAIT_UNLOADED_TRANSIT_ARRIVAL -> {
                tickScenario4WaitUnloadedArrival(level);
            }
            case S4_WAIT_TARGET_LOAD_UNLOAD -> {
                tickScenario4WaitTargetLoadAndVerify(level);
            }

            // =========================================================================
            // SCENARIO 5: Chunk Loading Upgrade (lc2:chunk_loading_upgrade)
            // =========================================================================
            case S5_SETUP -> {
                runScenario5Setup(level);
                advance(Stage.S5_WAIT_HOLD_10_TICKS);
            }
            case S5_WAIT_HOLD_10_TICKS -> {
                if (stageTicks < 10) {
                    return;
                }
                runScenario5VerifyHoldAndRemove(level);
                advance(Stage.S5_WAIT_REMOVE_1_TICK);
            }
            case S5_WAIT_REMOVE_1_TICK -> {
                runScenario5VerifyRemovedAndStartDeplete(level);
                advance(Stage.S5_WAIT_DEPLETE_3_TICKS);
            }
            case S5_WAIT_DEPLETE_3_TICKS -> {
                if (stageTicks < 3) {
                    return;
                }
                CargoStationBlockEntity st = (CargoStationBlockEntity) level.getBlockEntity(s5StationPos);
                qa.check("s5.held_during_drain", st.isChunkLoaderActive()
                        && LogisticsChunkTickets.heldChunkCount(level) == 9,
                        "active=" + st.isChunkLoaderActive() + ", held=" + LogisticsChunkTickets.heldChunkCount(level));
                qa.checkClose("s5.eu_drained_to_zero", 0.0, st.getStoredEu(), 1e-6);
                advance(Stage.S5_WAIT_DEPLETE_RELEASE_1_TICK);
            }
            case S5_WAIT_DEPLETE_RELEASE_1_TICK -> {
                CargoStationBlockEntity st = (CargoStationBlockEntity) level.getBlockEntity(s5StationPos);
                qa.check("s5.released_on_eu_depletion", !st.isChunkLoaderActive(),
                        "active=" + st.isChunkLoaderActive());
                qa.checkEquals("s5.held_count_zero_after_depletion", 0, LogisticsChunkTickets.heldChunkCount(level));
                ChunkPos center = new ChunkPos(s5StationPos);
                boolean anyHeld = false;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (LogisticsChunkTickets.isChunkHeld(level, new ChunkPos(center.x + dx, center.z + dz))) {
                            anyHeld = true;
                        }
                    }
                }
                qa.check("s5.all_9_tickets_released_on_depletion", !anyHeld, "anyHeld=" + anyHeld);
                advance(Stage.S6_SETUP_AND_DISPATCH);
            }

            // =========================================================================
            // SCENARIO 6: Multi-Vehicle Collision Avoidance
            // =========================================================================
            case S6_SETUP_AND_DISPATCH -> {
                runScenario6SetupAndDispatch(level);
                advance(Stage.S6_WAIT_COLLISION_COMPLETION);
            }
            case S6_WAIT_COLLISION_COMPLETION -> {
                tickScenario6WaitCompletion(level);
            }

            // =========================================================================
            // SCENARIO 8: 2-Zone Isolation, Swap, NBT Migration & Items-Before-Vehicle
            // =========================================================================
            case S8_ZONE_ISOLATION_SETUP -> {
                runScenario8ZoneIsolationSetup(level);
                advance(Stage.S8_ZONE_ISOLATION_VERIFY);
            }
            case S8_ZONE_ISOLATION_VERIFY -> {
                if (stageTicks < 35) {
                    return;
                }
                runScenario8ZoneIsolationVerify(level);
                advance(Stage.S8_ITEMS_BEFORE_VEHICLE_SETUP);
            }
            case S8_ITEMS_BEFORE_VEHICLE_SETUP -> {
                runScenario8ItemsBeforeVehicleSetup(level);
                advance(Stage.S8_ITEMS_BEFORE_VEHICLE_PLACE);
            }
            case S8_ITEMS_BEFORE_VEHICLE_PLACE -> {
                if (stageTicks < 5) {
                    return;
                }
                runScenario8ItemsBeforeVehiclePlace(level);
                advance(Stage.S8_ITEMS_BEFORE_VEHICLE_LAUNCH_CHECK);
            }
            case S8_ITEMS_BEFORE_VEHICLE_LAUNCH_CHECK -> {
                if (stageTicks < 2) {
                    return;
                }
                runScenario8ItemsBeforeVehicleLaunchCheck(level);
                advance(Stage.S8_ITEMS_BEFORE_VEHICLE_ARRIVAL);
            }
            case S8_ITEMS_BEFORE_VEHICLE_ARRIVAL -> {
                tickScenario8ItemsBeforeVehicleArrival(level);
            }

            // =========================================================================
            // SCENARIO 7: Cold-Restart / NBT Persistence (Write Phase)
            // =========================================================================
            case S7_WRITE_DISPATCH -> {
                runScenario7WriteDispatch(level);
                advance(Stage.S7_WRITE_MIDFLIGHT_SAVE_AND_HALT);
            }
            case S7_WRITE_MIDFLIGHT_SAVE_AND_HALT -> {
                if (stageTicks < 15) {
                    return;
                }
                runScenario7WriteSaveAndHalt(server, level);
            }

            // =========================================================================
            // SCENARIO 7: Cold-Restart / NBT Persistence (Read Phase)
            // =========================================================================
            case S7_READ_VERIFY_RELOADED -> {
                runScenario7ReadVerifyReloaded(level);
                advance(Stage.S7_READ_WAIT_COMPLETION);
            }
            case S7_READ_WAIT_COMPLETION -> {
                tickScenario7ReadWaitCompletion(server, level);
            }

            case DONE -> {
                finished = true;
            }
        }
    }

    // -------------------------------------------------------------------------
    // SCENARIO 1 IMPLEMENTATION
    // -------------------------------------------------------------------------

    private void runScenario1Setup(MinecraftServer server, ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 1: Mod, Registry, Mixin, Recipes & IC2 Power ===");
        qa.check("s1.mod_ic2_loaded", ModList.get().isLoaded("ic2"), "ic2 loaded");
        qa.check("s1.mod_lc2_loaded", ModList.get().isLoaded("lc2"), "lc2 loaded");

        // Flat world sanity check
        qa.check("s1.flat_world_bedrock",
                level.getBlockState(new BlockPos(0, -64, 0)).is(Blocks.BEDROCK)
                        && level.getBlockState(new BlockPos(0, SURFACE_Y, 0)).isAir(),
                "bedrock@-64=" + level.getBlockState(new BlockPos(0, -64, 0)) + ", air@-58=" + level.getBlockState(new BlockPos(0, SURFACE_Y, 0)));

        // 2 blocks
        for (String id : List.of("smart_port", "cargo_station")) {
            ResourceLocation rl = Lc2Coords.id(id);
            qa.check("s1.block." + id, ForgeRegistries.BLOCKS.containsKey(rl), rl.toString());
            qa.check("s1.be." + id, ForgeRegistries.BLOCK_ENTITY_TYPES.containsKey(rl), rl.toString());
        }
        // 2 entities
        for (String id : List.of("electric_cargo_boat", "cargo_drone")) {
            ResourceLocation rl = Lc2Coords.id(id);
            qa.check("s1.entity." + id, ForgeRegistries.ENTITY_TYPES.containsKey(rl), rl.toString());
        }
        // 9 items + 9 live crafting recipes
        List<String> allItems = List.of(
                "smart_port", "cargo_station", "electric_cargo_boat",
                "transport_drone", "heavy_transport_drone",
                "rf_chip_1", "rf_chip_2", "rf_chip_3", "chunk_loading_upgrade"
        );
        for (String id : allItems) {
            ResourceLocation rl = Lc2Coords.id(id);
            qa.check("s1.item." + id, ForgeRegistries.ITEMS.containsKey(rl), rl.toString());
            Optional<? extends Recipe<?>> recipeOpt = server.getRecipeManager().byKey(rl);
            qa.check("s1.recipe_exists." + id, recipeOpt.isPresent(), "recipe " + rl);
            Recipe<?> recipe = recipeOpt.get();
            ItemStack result = recipe.getResultItem();
            Item expectedItem = ForgeRegistries.ITEMS.getValue(rl);
            qa.check("s1.recipe_output." + id,
                    !result.isEmpty() && result.getItem() == expectedItem,
                    "result=" + result);
            int nonEmptyIngs = 0;
            for (Ingredient ing : recipe.getIngredients()) {
                if (!ing.isEmpty()) {
                    nonEmptyIngs++;
                    qa.check("s1.recipe_ing_valid." + id + "." + nonEmptyIngs,
                            ing.getItems().length > 0,
                            "ingredient resolved items=" + ing.getItems().length);
                }
            }
            qa.check("s1.recipe_has_ingredients." + id, nonEmptyIngs > 0, "nonEmptyIngs=" + nonEmptyIngs);
        }
        // 3 menus
        for (String id : List.of("smart_port", "cargo_station", "electric_cargo_boat")) {
            ResourceLocation rl = Lc2Coords.id(id);
            qa.check("s1.menu." + id, ForgeRegistries.MENU_TYPES.containsKey(rl), rl.toString());
        }

        // Place IC2 Generator + 2 insulated_copper_cable + Smart Port & Cargo Station
        Block cableBlock = Lc2QaHelpers.requireBlock("ic2:insulated_copper_cable");
        Block genBlock = Lc2QaHelpers.requireBlock("ic2:generator");
        // Stone floor underneath
        for (int x = 7; x <= 13; x++) {
            level.setBlock(new BlockPos(x, SURFACE_Y - 1, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // Place cables with ALL 6 arm properties explicitly FALSE first so ONLY real neighbor updates
        // through CableBlock.updateShape -> CableBlock.connects (with CableBlockMixin!) flip arms to TRUE!
        BlockState disconnectedCable = Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState());
        level.setBlock(s1CablePortPos, disconnectedCable, Block.UPDATE_ALL);
        level.setBlock(s1CableStationPos, disconnectedCable, Block.UPDATE_ALL);
        level.setBlock(s1GenPos, genBlock.defaultBlockState(), Block.UPDATE_ALL);
        SmartPortBlockEntity port = Lc2QaHelpers.placeSmartPort(level, s1PortPos, Direction.SOUTH, "S1-PowerPort");
        CargoStationBlockEntity station = Lc2QaHelpers.placeCargoStation(level, s1StationPos, "S1-PowerStation");

        // Verify CableBlockMixin visual arm connection properties on BOTH cables (and disconnected air faces stay false)!
        BlockState cablePortState = level.getBlockState(s1CablePortPos);
        BlockState cableStationState = level.getBlockState(s1CableStationPos);
        Boolean portArm = Lc2QaHelpers.getCableDirectionProp(cablePortState, Direction.EAST);
        Boolean portAirArm = Lc2QaHelpers.getCableDirectionProp(cablePortState, Direction.NORTH);
        Boolean stationArm = Lc2QaHelpers.getCableDirectionProp(cableStationState, Direction.WEST);
        Boolean stationAirArm = Lc2QaHelpers.getCableDirectionProp(cableStationState, Direction.NORTH);
        Boolean genArmEast = Lc2QaHelpers.getCableDirectionProp(cablePortState, Direction.WEST);
        Boolean genArmWest = Lc2QaHelpers.getCableDirectionProp(cableStationState, Direction.EAST);
        qa.check("s1.mixin_cable_connects_smart_port",
                Boolean.TRUE.equals(portArm) && Boolean.FALSE.equals(portAirArm),
                "cable@11 east=" + portArm + ", north=" + portAirArm + ", state=" + cablePortState);
        qa.check("s1.mixin_cable_connects_cargo_station",
                Boolean.TRUE.equals(stationArm) && Boolean.FALSE.equals(stationAirArm),
                "cable@9 west=" + stationArm + ", north=" + stationAirArm + ", state=" + cableStationState);
        qa.check("s1.cable_connects_generator", Boolean.TRUE.equals(genArmEast) && Boolean.TRUE.equals(genArmWest),
                "cable@11 west=" + genArmEast + ", cable@9 east=" + genArmWest);

        // Verify base capacity = 14400 EU & base packetLimit() = 32 EU/t
        qa.checkClose("s1.port_base_capacity", 14400.0, port.maxCapacityEu(), 1e-6);
        qa.checkClose("s1.port_base_packet_limit", 32.0, port.packetLimit(), 1e-6);
        qa.checkClose("s1.station_base_capacity", 14400.0, station.maxCapacityEu(), 1e-6);
        qa.checkClose("s1.station_base_packet_limit", 32.0, station.packetLimit(), 1e-6);

        // Fuel the IC2 Generator with coal
        boolean fueled = Lc2QaHelpers.insertItemIntoBlock(level, s1GenPos, new ItemStack(Items.COAL, 16));
        qa.check("s1.ic2_generator_fueled", fueled, "inserted 16 coal into ic2:generator");
    }

    private void runScenario1VerifyEuAndUpgrades(ServerLevel level) {
        SmartPortBlockEntity port = (SmartPortBlockEntity) level.getBlockEntity(s1PortPos);
        CargoStationBlockEntity station = (CargoStationBlockEntity) level.getBlockEntity(s1StationPos);
        qa.check("s1.port_received_real_ic2_eu", port != null && port.getStoredEu() > 0.0,
                "port.storedEu=" + (port == null ? "null" : port.getStoredEu()));
        qa.check("s1.station_received_real_ic2_eu", station != null && station.getStoredEu() > 0.0,
                "station.storedEu=" + (station == null ? "null" : station.getStoredEu()));
        qa.metric("s1.port_eu_after_50t", port.getStoredEu());
        qa.metric("s1.station_eu_after_50t", station.getStoredEu());

        // Verify ic2:energy_storage_upgrade -> 24400 EU & ic2:transformer_upgrade -> 128 EU/t
        port.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:energy_storage_upgrade", 1));
        port.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 1, Lc2QaHelpers.itemStack("ic2:transformer_upgrade", 1));
        qa.checkClose("s1.port_upgraded_capacity", 24400.0, port.maxCapacityEu(), 1e-6);
        qa.checkClose("s1.port_upgraded_packet_limit", 128.0, port.packetLimit(), 1e-6);

        station.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:energy_storage_upgrade", 1));
        station.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 1, Lc2QaHelpers.itemStack("ic2:transformer_upgrade", 1));
        qa.checkClose("s1.station_upgraded_capacity", 24400.0, station.maxCapacityEu(), 1e-6);
        qa.checkClose("s1.station_upgraded_packet_limit", 128.0, station.packetLimit(), 1e-6);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 2 IMPLEMENTATION
    // -------------------------------------------------------------------------

    private void runScenario2SetupAndBatteryDispatch(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 2: Waterway Full Lifecycle ===");
        Lc2QaHelpers.buildCanalEastWest(level, 1, 37, WATER_Y, 30);

        SmartPortBlockEntity portA = Lc2QaHelpers.placeSmartPort(level, s2PortAPos, Direction.EAST, "S2-PortA");
        SmartPortBlockEntity portB = Lc2QaHelpers.placeSmartPort(level, s2PortBPos, Direction.WEST, "S2-PortB");
        qa.check("s2.portA_berth_detected", portA.getWaterBerthPos() != null, "berthA=" + portA.getWaterBerthPos());
        qa.check("s2.portB_berth_detected", portB.getWaterBerthPos() != null, "berthB=" + portB.getWaterBerthPos());

        // Place Chest + Shulker Box + IC2 Iron Storage Box adjacent to Port A AND Port B
        Block ironStorageBox = Lc2QaHelpers.requireBlock("ic2:iron_storage_box");
        level.setBlock(s2PortAContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s2PortAContainers.get(1), Blocks.SHULKER_BOX.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s2PortAContainers.get(2), ironStorageBox.defaultBlockState(), Block.UPDATE_ALL);

        level.setBlock(s2PortBContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s2PortBContainers.get(1), Blocks.SHULKER_BOX.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s2PortBContainers.get(2), ironStorageBox.defaultBlockState(), Block.UPDATE_ALL);

        // Load 42 distinct item stacks (> 27 slots!) across Chest (16), Shulker Box (13), Iron Storage Box (13)
        List<ItemStack> all42 = Lc2QaHelpers.distinctStacks(42, 16);
        s2ExpectedCargo = new HashMap<>();
        for (int i = 0; i < 42; i++) {
            ItemStack st = all42.get(i);
            BlockPos dest = i < 16 ? s2PortAContainers.get(0)
                    : (i < 29 ? s2PortAContainers.get(1) : s2PortAContainers.get(2));
            boolean ok = Lc2QaHelpers.insertItemIntoBlock(level, dest, st);
            qa.check("s2.seed_cargo_" + i, ok, "inserted " + st + " into " + dest);
            s2ExpectedCargo.merge(st.getItem(), st.getCount(), Integer::sum);
        }

        // Spawn and dock boat at Port A
        ElectricCargoBoatEntity boat = Lc2QaHelpers.spawnDockedBoatAt(level, portA);
        qa.check("s2.boat_docked_at_portA", portA.findDockedBoat(level) == boat, "docked boat found");

        // Configure Port A -> Port B (install pulling_upgrade on Port A & ejector_upgrade on Port B
        // to exercise 42-stack multi-container adjacent I/O under strict 2-zone rules)
        portA.setTargetPos(s2PortBPos);
        portA.setAutoReturn(false);
        portA.setStoredEu(10000.0);
        portA.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        portA.setItem(SmartPortBlockEntity.SLOT_BATTERY, Lc2QaHelpers.itemStack("ic2:single_use_battery", 4));
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:pulling_upgrade", 1));
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 1, Lc2QaHelpers.itemStack("ic2:ejector_upgrade", 1));

        // 1) Build stone dam -> MUST fail with WATER_DISCONNECTED
        Lc2QaHelpers.setEastWestDam(level, 19, WATER_Y, 30, true);
        boolean damDispatched = portA.tryDispatch(level, true);
        qa.check("s2.stone_dam_blocks_dispatch", !damDispatched, "damDispatched=" + damDispatched);
        qa.checkEquals("s2.stone_dam_failure_reason",
                DispatchFailure.WATER_DISCONNECTED.translationKey(), portA.getStatusKey());

        // 2) Open canal -> BATTERY_FIRST dispatch consumes 2 single-use batteries, EU untouched!
        Lc2QaHelpers.setEastWestDam(level, 19, WATER_Y, 30, false);
        boolean dispatched = portA.tryDispatch(level, true);
        qa.check("s2.battery_first_dispatch_ok", dispatched, "status=" + portA.getStatusKey());
        qa.checkEquals("s2.battery_first_consumed_2", 2, portA.getBatteryCount());
        qa.checkClose("s2.battery_first_eu_untouched", 10000.0, portA.getStoredEu(), 1e-6);

        s2BoatTransitId = boat.getTransitId();
        qa.check("s2.boat_bound_to_transit", s2BoatTransitId != null, "transitId=" + s2BoatTransitId);
        qa.check("s2.boat_is_autonomous_transit", boat.isAutonomousTransit(), "isAutonomousTransit=true");
        qa.checkEquals("s2.boat_loaded_42_stacks", 42, boat.snapshotCargoList().size());

        // Check active-transit riding block immediately!
        Villager intruder = EntityType.VILLAGER.create(level);
        intruder.setPos(boat.getX(), boat.getY(), boat.getZ());
        level.addFreshEntity(intruder);
        boolean intruderBoarded = intruder.startRiding(boat);
        qa.check("s2.riding_blocked_canAddPassenger", !intruderBoarded && boat.getPassengers().isEmpty(),
                "intruderBoarded=" + intruderBoarded + ", passengers=" + boat.getPassengers().size());
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        fakePlayer.setPos(boat.getX(), boat.getY(), boat.getZ());
        boat.interact(fakePlayer, InteractionHand.MAIN_HAND);
        qa.check("s2.riding_blocked_interact", boat.getPassengers().isEmpty(),
                "passengers after interact=" + boat.getPassengers().size());
        intruder.discard();
        s2RidingBlockedChecked = true;
    }

    private void tickScenario2WaitBoatToB(ServerLevel level) {
        if (stageTicks > 400) {
            throw new AssertionError("Scenario 2 boat A->B timed out after 400 ticks");
        }
        if (LogisticsLevelManager.isTransitActive(level, s2BoatTransitId)) {
            return;
        }
        qa.check("s2.riding_blocked_verified", s2RidingBlockedChecked, "verified during active transit");
        // Boat arrived at Port B! Verify all 42 distinct stacks (672 items) unloaded into Port B's containers with 0 lost items!
        Map<Item, Integer> delivered = Lc2QaHelpers.countItemsInBlocks(level, s2PortBContainers);
        qa.checkEquals("s2.delivered_total_items", 42 * 16, Lc2QaHelpers.totalItemCount(delivered));
        qa.checkEquals("s2.delivered_distinct_stacks", 42, delivered.size());
        qa.check("s2.delivered_exact_cargo_match", delivered.equals(s2ExpectedCargo),
                "matched 42 distinct item types across Chest + Shulker Box + IC2 Iron Storage Box");

        // Confirm items spilled across >1 container (since 42 stacks > 27-slot single container),
        // exercising both Shulker Box (NORTH) and IC2 Iron Storage Box (SOUTH) on unload!
        int chestItems = Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countItemsInBlocks(level, List.of(s2PortBContainers.get(0))));
        int shulkerItems = Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countItemsInBlocks(level, List.of(s2PortBContainers.get(1))));
        int ironBoxItems = Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countItemsInBlocks(level, List.of(s2PortBContainers.get(2))));
        int nonEmptyContainers = (chestItems > 0 ? 1 : 0) + (shulkerItems > 0 ? 1 : 0) + (ironBoxItems > 0 ? 1 : 0);
        qa.check("s2.multi_container_unload_gt27", nonEmptyContainers >= 2 && shulkerItems > 0 && ironBoxItems > 0,
                "chest=" + chestItems + ", shulker=" + shulkerItems + ", ironStorageBox=" + ironBoxItems);

        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s2PortBPos);
        ElectricCargoBoatEntity dockedBoat = portB.findDockedBoat(level);
        qa.check("s2.boat_docked_at_portB", dockedBoat != null && !dockedBoat.isAutonomousTransit(),
                "dockedBoat=" + dockedBoat);
        qa.checkEquals("s2.boat_hold_empty_after_unload", 0, dockedBoat.snapshotCargoList().size());

        // Clear Port B containers so return leg doesn't reload them
        for (BlockPos cp : s2PortBContainers) {
            Lc2QaHelpers.clearContainerBlock(level, cp);
        }

        // Now test EU_FIRST + autoReturn=true: 3000 EU fails, 6000 EU succeeds + redstone + inverter + pre-launch rider!
        portB.setTargetPos(s2PortAPos);
        portB.setAutoReturn(true);
        portB.setEnergyPriority(EnergyPriority.EU_FIRST);
        portB.setItem(SmartPortBlockEntity.SLOT_BATTERY, ItemStack.EMPTY);
        portB.setStoredEu(3000.0);

        boolean fail3000 = portB.tryDispatch(level, true);
        qa.check("s2.roundtrip_3000eu_fails", !fail3000, "fail3000=" + fail3000);
        qa.checkEquals("s2.roundtrip_3000eu_reason",
                DispatchFailure.INSUFFICIENT_ENERGY.translationKey(), portB.getStatusKey());

        // Supply 7000 EU (enough for 6000 EU round-trip)
        portB.setStoredEu(7000.0);

        // Verify normal redstone (no inverter, unpowered -> REDSTONE_INACTIVE)
        boolean unpoweredFail = portB.tryDispatch(level, false);
        qa.check("s2.normal_redstone_unpowered_blocked", !unpoweredFail, "unpoweredFail=" + unpoweredFail);
        qa.checkEquals("s2.normal_redstone_unpowered_reason",
                DispatchFailure.REDSTONE_INACTIVE.translationKey(), portB.getStatusKey());

        // Seat a pre-launch passenger BEFORE launch while boat is docked & idle!
        s2PreLaunchRider = EntityType.VILLAGER.create(level);
        s2PreLaunchRider.setPos(dockedBoat.getX(), dockedBoat.getY(), dockedBoat.getZ());
        level.addFreshEntity(s2PreLaunchRider);
        boolean preSeated = s2PreLaunchRider.startRiding(dockedBoat);
        qa.check("s2.prelaunch_passenger_boarded", preSeated && dockedBoat.getPassengers().contains(s2PreLaunchRider),
                "preSeated=" + preSeated);

        // Install redstone block (powered) + ic2:redstone_inverter_upgrade -> MUST NOT dispatch while powered!
        level.setBlock(s2PortBRedstonePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:redstone_inverter_upgrade", 1));
        advance(Stage.S2_INVERTER_HOLD_CHECK);
    }

    private void tickScenario2WaitInverterAutoDispatch(ServerLevel level) {
        if (stageTicks > 25) {
            throw new AssertionError("Scenario 2 inverted redstone auto-dispatch did not trigger within 25 ticks");
        }
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s2PortBPos);
        // Look for an active boat transit originating from Port B
        BlockPos3i portBCore = Lc2Coords.toCore(s2PortBPos);
        for (TransitRecord rec : LogisticsLevelManager.activeTransits(level)) {
            if (rec.vehicleKind().isBoat() && rec.originPos().equals(portBCore)) {
                s2ReturnBoatTransitId = rec.id();
                break;
            }
        }
        if (s2ReturnBoatTransitId == null) {
            return;
        }
        // Remove inverter upgrade now so Port B won't immediately re-dispatch after auto-return docks!
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, ItemStack.EMPTY);

        qa.check("s2.inverter_auto_dispatched_unpowered", true, "transitId=" + s2ReturnBoatTransitId);
        qa.checkClose("s2.roundtrip_consumed_6000eu", 1000.0, portB.getStoredEu(), 1e-6);

        // Verify pre-launch passenger STILL seated right after launch!
        ElectricCargoBoatEntity liveBoat = findBoatByTransit(level, s2ReturnBoatTransitId);
        qa.check("s2.prelaunch_rider_stays_seated_at_launch",
                liveBoat != null && liveBoat.isAutonomousTransit()
                        && liveBoat.getPassengers().contains(s2PreLaunchRider)
                        && liveBoat.getControllingPassenger() == null,
                "boat=" + liveBoat + ", passengers=" + (liveBoat == null ? "null" : liveBoat.getPassengers().size()));
        advance(Stage.S2_WAIT_ROUND_TRIP_RETURN);
    }

    private void tickScenario2WaitRoundTripReturn(ServerLevel level) {
        if (stageTicks > 650) {
            throw new AssertionError("Scenario 2 boat round-trip timed out after 650 ticks");
        }
        if (LogisticsLevelManager.isTransitActive(level, s2ReturnBoatTransitId)) {
            if (!s2PreLaunchRiderMidChecked && stageTicks == 15) {
                ElectricCargoBoatEntity liveBoat = findBoatByTransit(level, s2ReturnBoatTransitId);
                qa.check("s2.prelaunch_rider_stays_seated_mid_transit",
                        liveBoat != null && liveBoat.isAutonomousTransit()
                                && liveBoat.getPassengers().contains(s2PreLaunchRider)
                                && s2PreLaunchRider.getVehicle() == liveBoat,
                        "mid-transit rider vehicle=" + s2PreLaunchRider.getVehicle());
                s2PreLaunchRiderMidChecked = true;
            }
            return;
        }
        qa.check("s2.prelaunch_rider_mid_check_ran", s2PreLaunchRiderMidChecked, "mid-transit rider check ran");
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s2PortBPos);
        ElectricCargoBoatEntity returnedBoat = portB.findDockedBoat(level);
        qa.check("s2.auto_return_boat_docked_back_at_origin",
                returnedBoat != null && !returnedBoat.isAutonomousTransit(),
                "returnedBoat=" + returnedBoat);
        if (s2PreLaunchRider != null && !s2PreLaunchRider.isRemoved()) {
            s2PreLaunchRider.stopRiding();
            s2PreLaunchRider.discard();
        }
        advance(Stage.S3_RF_AND_COST_PROBES);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 3 IMPLEMENTATION
    // -------------------------------------------------------------------------

    private void runScenario3RfCostProbesAndStartStdDrone(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 3: Airway Full Lifecycle ===");
        BlockPos rfOrigPos = new BlockPos(-100, SURFACE_Y, 60);
        BlockPos rf300Pos = new BlockPos(50, SURFACE_Y, -90);     // Manhattan dist = 150 + 150 = 300
        BlockPos rf600Pos = new BlockPos(200, SURFACE_Y, -240);   // Manhattan dist = 300 + 300 = 600

        CargoStationBlockEntity rfOrig = Lc2QaHelpers.placeCargoStation(level, rfOrigPos, "S3-RfOrig");
        Lc2QaHelpers.placeCargoStation(level, rf300Pos, "S3-Rf300");
        Lc2QaHelpers.placeCargoStation(level, rf600Pos, "S3-Rf600");

        // 1) Missing RF chip -> NO_RF_CHIP
        rfOrig.setTargetPos(rf300Pos);
        rfOrig.setAutoReturn(false);
        rfOrig.setStoredEu(14400.0);
        rfOrig.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        qa.check("s3.no_rf_chip_blocked", !rfOrig.tryDispatch(level, true), "status=" + rfOrig.getStatusKey());
        qa.checkEquals("s3.no_rf_chip_reason", DispatchFailure.NO_RF_CHIP.translationKey(), rfOrig.getStatusKey());

        // 2) rf_chip_1 (256m) rejects distance 300 -> OUT_OF_RF_RANGE
        rfOrig.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        qa.checkEquals("s3.rf_chip_1_tier", RfChipTier.TIER_1, rfOrig.getRfChipTier());
        qa.check("s3.rf_chip_1_rejects_300", !rfOrig.tryDispatch(level, true), "status=" + rfOrig.getStatusKey());
        qa.checkEquals("s3.rf_chip_1_rejects_300_reason", DispatchFailure.OUT_OF_RF_RANGE.translationKey(), rfOrig.getStatusKey());

        // 3) rf_chip_2 (512m) accepts distance 300: 2000/4000 EU and 2/4 batteries
        rfOrig.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_2", 1));
        probeDroneCost(level, rfOrig, rf300Pos, "lc2:transport_drone", EnergyPriority.EU_FIRST, 10000.0, 0, 2000.0, 0, "s3.rf2_300_std_eu");
        probeDroneCost(level, rfOrig, rf300Pos, "lc2:heavy_transport_drone", EnergyPriority.EU_FIRST, 10000.0, 0, 4000.0, 0, "s3.rf2_300_heavy_eu");
        probeDroneCost(level, rfOrig, rf300Pos, "lc2:transport_drone", EnergyPriority.BATTERY_FIRST, 10000.0, 10, 0.0, 2, "s3.rf2_300_std_bat");
        probeDroneCost(level, rfOrig, rf300Pos, "lc2:heavy_transport_drone", EnergyPriority.BATTERY_FIRST, 10000.0, 10, 0.0, 4, "s3.rf2_300_heavy_bat");

        // 4) rf_chip_2 rejects distance 600 -> OUT_OF_RF_RANGE
        rfOrig.setTargetPos(rf600Pos);
        rfOrig.setStoredEu(14400.0);
        rfOrig.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        qa.check("s3.rf_chip_2_rejects_600", !rfOrig.tryDispatch(level, true), "status=" + rfOrig.getStatusKey());
        qa.checkEquals("s3.rf_chip_2_rejects_600_reason", DispatchFailure.OUT_OF_RF_RANGE.translationKey(), rfOrig.getStatusKey());

        // 5) rf_chip_3 (1024m) accepts distance 600: 7000/14000 EU and 5/10 batteries
        rfOrig.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_3", 1));
        probeDroneCost(level, rfOrig, rf600Pos, "lc2:transport_drone", EnergyPriority.EU_FIRST, 14400.0, 0, 7000.0, 0, "s3.rf3_600_std_eu");
        probeDroneCost(level, rfOrig, rf600Pos, "lc2:heavy_transport_drone", EnergyPriority.EU_FIRST, 14400.0, 0, 14000.0, 0, "s3.rf3_600_heavy_eu");
        probeDroneCost(level, rfOrig, rf600Pos, "lc2:transport_drone", EnergyPriority.BATTERY_FIRST, 14400.0, 16, 0.0, 5, "s3.rf3_600_std_bat");
        probeDroneCost(level, rfOrig, rf600Pos, "lc2:heavy_transport_drone", EnergyPriority.BATTERY_FIRST, 14400.0, 16, 0.0, 10, "s3.rf3_600_heavy_bat");

        // 6) Standard Drone (12 stacks, faster 0.80) vs Heavy Drone (36 stacks, slower 0.50)
        CargoStationBlockEntity cargoA = Lc2QaHelpers.placeCargoStation(level, s3CargoAPos, "S3-CargoA");
        CargoStationBlockEntity cargoB = Lc2QaHelpers.placeCargoStation(level, s3CargoBPos, "S3-CargoB");
        level.setBlock(s3CargoAContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s3CargoAContainers.get(1), Blocks.SHULKER_BOX.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s3CargoBContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s3CargoBContainers.get(1), Blocks.SHULKER_BOX.defaultBlockState(), Block.UPDATE_ALL);

        // Seed 40 distinct stacks (x8 = 320 items) adjacent to CargoA
        List<ItemStack> stacks40 = Lc2QaHelpers.distinctStacks(40, 8);
        for (int i = 0; i < 40; i++) {
            BlockPos dest = i < 20 ? s3CargoAContainers.get(0) : s3CargoAContainers.get(1);
            qa.check("s3.std_seed_" + i, Lc2QaHelpers.insertItemIntoBlock(level, dest, stacks40.get(i)), "seed");
        }

        cargoA.setTargetPos(s3CargoBPos);
        cargoA.setAutoReturn(false);
        cargoA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        cargoA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        cargoA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:pulling_upgrade", 1));
        cargoA.setStoredEu(5000.0);
        cargoB.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:ejector_upgrade", 1));

        qa.check("s3.std_drone_dispatch_ok", cargoA.tryDispatch(level, true), "status=" + cargoA.getStatusKey());
        TransitRecord stdRec = findLatestTransitFrom(level, s3CargoAPos, VehicleKind.STANDARD_DRONE);
        qa.check("s3.std_drone_record_found", stdRec != null, "stdRec=" + stdRec);
        s3StdDroneTransitId = stdRec.id();
        qa.checkClose("s3.std_drone_speed_080", 0.80, stdRec.baseSpeed(), 1e-6);
        List<ItemStack> stdCargo = LogisticsLevelManager.data(level).getTransitCargo(s3StdDroneTransitId);
        qa.checkEquals("s3.std_drone_max_12_stacks", 12, stdCargo.size());
    }

    private void probeDroneCost(
            ServerLevel level,
            CargoStationBlockEntity station,
            BlockPos targetPos,
            String droneItemId,
            EnergyPriority priority,
            double startEu,
            int startBats,
            double expectedEuSpent,
            int expectedBatsSpent,
            String keyPrefix
    ) {
        station.setTargetPos(targetPos);
        station.setAutoReturn(false);
        station.setEnergyPriority(priority);
        station.setStoredEu(startEu);
        station.setItem(CargoStationBlockEntity.SLOT_BATTERY,
                startBats > 0 ? Lc2QaHelpers.itemStack("ic2:single_use_battery", startBats) : ItemStack.EMPTY);
        station.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack(droneItemId, 1));

        Set<UUID> before = new HashSet<>();
        for (TransitRecord r : LogisticsLevelManager.activeTransits(level)) {
            before.add(r.id());
        }
        boolean ok = station.tryDispatch(level, true);
        qa.check(keyPrefix + ".dispatched", ok, "status=" + station.getStatusKey());
        qa.checkClose(keyPrefix + ".eu_after", startEu - expectedEuSpent, station.getStoredEu(), 1e-6);
        qa.checkEquals(keyPrefix + ".bats_after", startBats - expectedBatsSpent, station.getBatteryCount());

        // Clean up probe transit & spawned entity so target bays & activeTransits remain clean
        UUID created = null;
        for (TransitRecord r : LogisticsLevelManager.activeTransits(level)) {
            if (!before.contains(r.id())) {
                created = r.id();
                break;
            }
        }
        qa.check(keyPrefix + ".transit_created", created != null, "created=" + created);
        LogisticsLevelManager.cancelTransitAndDrop(level, created, Vec3.ZERO, false);
        AABB box = AABB.ofSize(Vec3.atCenterOf(station.getBlockPos()), 12.0, 24.0, 12.0);
        for (CargoDroneEntity drone : level.getEntitiesOfClass(CargoDroneEntity.class, box)) {
            if (created.equals(drone.getTransitId())) {
                drone.discard();
            }
        }
    }

    private void tickScenario3WaitStdDrone(ServerLevel level) {
        if (stageTicks > 250) {
            throw new AssertionError("Scenario 3 Standard Drone flight timed out");
        }
        if (LogisticsLevelManager.isTransitActive(level, s3StdDroneTransitId)) {
            return;
        }
        Map<Item, Integer> deliveredStd = Lc2QaHelpers.countItemsInBlocks(level, s3CargoBContainers);
        qa.checkEquals("s3.std_drone_delivered_12_stacks", 12, deliveredStd.size());
        qa.checkEquals("s3.std_drone_delivered_96_items", 12 * 8, Lc2QaHelpers.totalItemCount(deliveredStd));

        // Reset containers & CargoA Outbound (pulling_upgrade pulled leftover stacks into Outbound while Std Drone flew)
        CargoStationBlockEntity cargoA = (CargoStationBlockEntity) level.getBlockEntity(s3CargoAPos);
        for (int s = CargoStationBlockEntity.SLOT_OUTBOUND_START; s < CargoStationBlockEntity.SLOT_OUTBOUND_COUNT; s++) {
            cargoA.setItem(s, ItemStack.EMPTY);
        }
        for (BlockPos p : s3CargoAContainers) {
            Lc2QaHelpers.clearContainerBlock(level, p);
        }
        for (BlockPos p : s3CargoBContainers) {
            Lc2QaHelpers.clearContainerBlock(level, p);
        }
        List<ItemStack> stacks40 = Lc2QaHelpers.distinctStacks(40, 8);
        for (int i = 0; i < 40; i++) {
            BlockPos dest = i < 20 ? s3CargoAContainers.get(0) : s3CargoAContainers.get(1);
            Lc2QaHelpers.insertItemIntoBlock(level, dest, stacks40.get(i));
        }
        cargoA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:heavy_transport_drone", 1));
        cargoA.setStoredEu(5000.0);
        qa.check("s3.heavy_drone_dispatch_ok", cargoA.tryDispatch(level, true), "status=" + cargoA.getStatusKey());
        TransitRecord heavyRec = findLatestTransitFrom(level, s3CargoAPos, VehicleKind.HEAVY_DRONE);
        qa.check("s3.heavy_drone_record_found", heavyRec != null, "heavyRec=" + heavyRec);
        s3HeavyDroneTransitId = heavyRec.id();
        qa.checkClose("s3.heavy_drone_speed_050", 0.50, heavyRec.baseSpeed(), 1e-6);
        qa.check("s3.std_faster_than_heavy", 0.80 > heavyRec.baseSpeed(), "0.80 > " + heavyRec.baseSpeed());
        List<ItemStack> heavyCargo = LogisticsLevelManager.data(level).getTransitCargo(s3HeavyDroneTransitId);
        qa.checkEquals("s3.heavy_drone_carries_36_stacks", 36, heavyCargo.size());
        advance(Stage.S3_WAIT_HEAVY_DRONE_36);
    }

    private void tickScenario3WaitHeavyDrone(ServerLevel level) {
        if (stageTicks > 300) {
            throw new AssertionError("Scenario 3 Heavy Drone flight timed out");
        }
        if (LogisticsLevelManager.isTransitActive(level, s3HeavyDroneTransitId)) {
            return;
        }
        Map<Item, Integer> deliveredHeavy = Lc2QaHelpers.countItemsInBlocks(level, s3CargoBContainers);
        qa.checkEquals("s3.heavy_drone_delivered_36_stacks", 36, deliveredHeavy.size());
        qa.checkEquals("s3.heavy_drone_delivered_288_items", 36 * 8, Lc2QaHelpers.totalItemCount(deliveredHeavy));
        advance(Stage.S3_BAYS_AND_AUTO_RETURN_START);
    }

    private void runScenario3BaysAndAutoReturnStart(ServerLevel level) {
        // 7) 6-Bay Limit & Atomic Simultaneous Reservation
        CargoStationBlockEntity orig1 = Lc2QaHelpers.placeCargoStation(level, s3BayOrig1Pos, "S3-BayOrig1");
        CargoStationBlockEntity orig2 = Lc2QaHelpers.placeCargoStation(level, s3BayOrig2Pos, "S3-BayOrig2");
        CargoStationBlockEntity target = Lc2QaHelpers.placeCargoStation(level, s3BayTargetPos, "S3-BayTarget");

        // Fill all 6 bays at target
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            target.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + bay, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        }
        orig1.setTargetPos(s3BayTargetPos);
        orig1.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        orig1.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        orig1.setStoredEu(5000.0);

        orig2.setTargetPos(s3BayTargetPos);
        orig2.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        orig2.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        orig2.setStoredEu(5000.0);

        qa.check("s3.full_6_bays_rejects", !orig1.tryDispatch(level, true), "status=" + orig1.getStatusKey());
        qa.checkEquals("s3.full_6_bays_reason", DispatchFailure.TARGET_BAY_FULL.translationKey(), orig1.getStatusKey());

        // Free exactly 1 bay (bay 5) -> two simultaneous dispatches MUST atomically allow 1st and reject 2nd!
        target.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 5, ItemStack.EMPTY);
        boolean firstOk = orig1.tryDispatch(level, true);
        boolean secondOk = orig2.tryDispatch(level, true);
        qa.check("s3.atomic_bay_first_succeeds", firstOk, "firstOk=" + firstOk);
        qa.check("s3.atomic_bay_second_rejected", !secondOk, "secondOk=" + secondOk + ", status2=" + orig2.getStatusKey());
        qa.checkEquals("s3.atomic_bay_second_reason", DispatchFailure.TARGET_BAY_FULL.translationKey(), orig2.getStatusKey());
        TransitRecord bayRec = findLatestTransitFrom(level, s3BayOrig1Pos, VehicleKind.STANDARD_DRONE);
        s3BayReservedTransitId = bayRec.id();

        // 8) Auto-Return Target-Side Payment
        CargoStationBlockEntity retA = Lc2QaHelpers.placeCargoStation(level, s3RetAPos, "S3-RetA");
        CargoStationBlockEntity retB = Lc2QaHelpers.placeCargoStation(level, s3RetBPos, "S3-RetB");
        level.setBlock(s3RetBChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        retA.setTargetPos(s3RetBPos);
        retA.setAutoReturn(true);
        retA.setEnergyPriority(EnergyPriority.EU_FIRST);
        retA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        retA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        retA.setStoredEu(2000.0);
        // Load 3 stacks into retA internal outgoing buffer
        List<ItemStack> retCargo = Lc2QaHelpers.distinctStacks(3, 10);
        for (int i = 0; i < 3; i++) {
            retA.setItem(i, retCargo.get(i));
        }
        // Leave retB completely unpowered (0 EU, 0 batteries)
        retB.setStoredEu(0.0);
        retB.setItem(CargoStationBlockEntity.SLOT_BATTERY, ItemStack.EMPTY);

        qa.check("s3.auto_return_outbound_dispatch_ok", retA.tryDispatch(level, true), "status=" + retA.getStatusKey());
        // Origin MUST pay ONLY outbound 800 EU (not 1600 EU)!
        qa.checkClose("s3.auto_return_origin_paid_outbound_only", 1200.0, retA.getStoredEu(), 1e-6);
        TransitRecord retRec = findLatestTransitFrom(level, s3RetAPos, VehicleKind.STANDARD_DRONE);
        qa.check("s3.auto_return_record_found", retRec != null, "retRec=" + retRec);
        s3RetTransitId = retRec.id();
    }

    private void tickScenario3WaitBayAndReturnArrival(ServerLevel level) {
        if (stageTicks > 250) {
            throw new AssertionError("Scenario 3 bay-reserved or auto-return outbound timed out");
        }
        boolean bayDone = !LogisticsLevelManager.isTransitActive(level, s3BayReservedTransitId);
        Optional<TransitRecord> retOpt = LogisticsLevelManager.engine(level).getTransit(s3RetTransitId);
        boolean retWaiting = retOpt.isPresent() && retOpt.get().phase() == TransitPhase.WAITING_RETURN_ENERGY;
        if (!bayDone || !retWaiting) {
            return;
        }
        CargoStationBlockEntity bayTarget = (CargoStationBlockEntity) level.getBlockEntity(s3BayTargetPos);
        qa.checkEquals("s3.bay_target_now_6_full", 0b111111, bayTarget.getDockedBayMask());

        // retB has NO ejector_upgrade -> cargo MUST unload strictly into retB's Storage (27..53) and NOT touch adjacent chest!
        CargoStationBlockEntity retB = (CargoStationBlockEntity) level.getBlockEntity(s3RetBPos);
        Map<Item, Integer> unloadedStorageAtRetB = Lc2QaHelpers.countStationStorage(retB);
        Map<Item, Integer> unloadedOutboundAtRetB = Lc2QaHelpers.countStationOutbound(retB);
        Map<Item, Integer> adjacentChestAtRetB = Lc2QaHelpers.countItemsInBlocks(level, List.of(s3RetBChestPos));
        qa.checkEquals("s3.auto_return_cargo_unloaded_to_storage", 30, Lc2QaHelpers.totalItemCount(unloadedStorageAtRetB));
        qa.checkEquals("s3.auto_return_outbound_remained_empty", 0, Lc2QaHelpers.totalItemCount(unloadedOutboundAtRetB));
        qa.checkEquals("s3.auto_return_no_ejector_chest_untouched", 0, Lc2QaHelpers.totalItemCount(adjacentChestAtRetB));
        qa.checkEquals("s3.auto_return_waiting_count", 1, LogisticsLevelManager.countWaitingReturnAt(level, s3RetBPos));
        advance(Stage.S3_WAIT_UNPOWERED_HOLD);
    }

    private void tickScenario3WaitTargetPaidReturn(ServerLevel level) {
        if (stageTicks > 250) {
            throw new AssertionError("Scenario 3 target-paid auto-return timed out");
        }
        if (LogisticsLevelManager.isTransitActive(level, s3RetTransitId)) {
            return;
        }
        CargoStationBlockEntity retA = (CargoStationBlockEntity) level.getBlockEntity(s3RetAPos);
        CargoStationBlockEntity retB = (CargoStationBlockEntity) level.getBlockEntity(s3RetBPos);
        qa.checkClose("s3.auto_return_target_paid_800eu", 200.0, retB.getStoredEu(), 1e-6);
        qa.check("s3.auto_return_drone_redocked_at_origin", retA.findReadyDroneBay() >= 0,
                "origin dockedMask=" + Integer.toBinaryString(retA.getDockedBayMask()));
        qa.checkEquals("s3.auto_return_waiting_count_zero", 0, LogisticsLevelManager.countWaitingReturnAt(level, s3RetBPos));
        advance(Stage.S4_SETUP_DISTANT);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 4 IMPLEMENTATION: UNLOADED-CHUNK VIRTUAL TRANSIT
    // -------------------------------------------------------------------------

    private void runScenario4SetupDistant(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 4: Unloaded-Chunk Virtual Transit (X >= 320) ===");
        Lc2QaHelpers.buildCanalEastWest(level, 1, 351, WATER_Y, 120);

        SmartPortBlockEntity portA = Lc2QaHelpers.placeSmartPort(level, s4PortAPos, Direction.EAST, "S4-UnloadedPortA");
        SmartPortBlockEntity portB = Lc2QaHelpers.placeSmartPort(level, s4PortBPos, Direction.WEST, "S4-UnloadedPortB");
        level.setBlock(s4PortAContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s4PortBContainers.get(0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s4PortBContainers.get(1), Blocks.SHULKER_BOX.defaultBlockState(), Block.UPDATE_ALL);

        // Pre-cache water route while canal chunks are present so dispatch doesn't re-probe intermediate chunks
        var route = LogisticsLevelManager.resolveWaterRoute(level, portA.getWaterBerthPos(), portB.getWaterBerthPos());
        qa.check("s4.distant_water_route_connected", route.connected(), "waypoints=" + route.waypoints().size());

        // Setup Boat + 20 stacks + 4 overclockers at Port A
        Lc2QaHelpers.spawnDockedBoatAt(level, portA);
        List<ItemStack> boat20 = Lc2QaHelpers.distinctStacks(20, 16);
        for (ItemStack st : boat20) {
            qa.check("s4.seed_boat_cargo", Lc2QaHelpers.insertItemIntoBlock(level, s4PortAContainers.get(0), st), "seed " + st);
        }
        portA.setTargetPos(s4PortBPos);
        portA.setAutoReturn(false);
        portA.setStoredEu(10000.0);
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:overclocker_upgrade", 4));
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 1, Lc2QaHelpers.itemStack("ic2:pulling_upgrade", 1));
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:ejector_upgrade", 1));

        // Setup Cargo Stations at X=0 -> X=352 (dist = 352 <= 512 -> rf_chip_2)
        CargoStationBlockEntity stA = Lc2QaHelpers.placeCargoStation(level, s4StationAPos, "S4-UnloadedStA");
        Lc2QaHelpers.placeCargoStation(level, s4StationBPos, "S4-UnloadedStB");
        level.setBlock(s4StationAChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s4StationBChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        List<ItemStack> drone10 = Lc2QaHelpers.distinctStacksOffset(20, 10, 16);
        for (ItemStack st : drone10) {
            qa.check("s4.seed_drone_cargo", Lc2QaHelpers.insertItemIntoBlock(level, s4StationAChestPos, st), "seed " + st);
        }
        stA.setTargetPos(s4StationBPos);
        stA.setAutoReturn(false);
        stA.setStoredEu(10000.0);
        stA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_2", 1));
        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        stA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("ic2:overclocker_upgrade", 4));
        stA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 1, Lc2QaHelpers.itemStack("ic2:pulling_upgrade", 1));
    }

    private void tickScenario4WaitChunksUnload(ServerLevel level) {
        // Wait until intermediate chunks (chunkX 15..21) AND target chunk (chunkX 22) at chunkZ 7 and 8 are completely unloaded!
        boolean allUnloaded = true;
        int stillLoaded = 0;
        for (int cx = 15; cx <= 22; cx++) {
            for (int cz = 7; cz <= 8; cz++) {
                if (!Lc2QaHelpers.isChunkCompletelyUnloaded(level, cx, cz)) {
                    allUnloaded = false;
                    stillLoaded++;
                }
            }
        }
        if (!allUnloaded) {
            if (stageTicks % 40 == 0) {
                Lc2QaMod.LOGGER.info("[LC2-QA] S4 waiting for distant chunks to unload (tick={}, stillLoaded={})", stageTicks, stillLoaded);
            }
            if (stageTicks > 400) {
                throw new AssertionError("Scenario 4 distant chunks (cx=15..22, cz=7..8) failed to unload after 400 ticks; stillLoaded=" + stillLoaded);
            }
            return;
        }
        qa.check("s4.distant_chunks_genuinely_unloaded_before_dispatch", true,
                "cx=15..22, cz=7..8 completely unloaded after " + stageTicks + " ticks");

        // Now dispatch BOTH Boat AND Drone while intermediate & target chunks are UNLOADED!
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(s4PortAPos);
        CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(s4StationAPos);
        qa.check("s4.unloaded_boat_dispatch_ok", portA.tryDispatch(level, true), "status=" + portA.getStatusKey());
        qa.check("s4.unloaded_drone_dispatch_ok", stA.tryDispatch(level, true), "status=" + stA.getStatusKey());

        TransitRecord boatRec = findLatestTransitFrom(level, s4PortAPos, VehicleKind.ELECTRIC_CARGO_BOAT);
        TransitRecord droneRec = findLatestTransitFrom(level, s4StationAPos, VehicleKind.STANDARD_DRONE);
        qa.check("s4.transits_created", boatRec != null && droneRec != null,
                "boat=" + boatRec + ", drone=" + droneRec);
        s4BoatTransitId = boatRec.id();
        s4DroneTransitId = droneRec.id();
        advance(Stage.S4_WAIT_UNLOADED_TRANSIT_ARRIVAL);
    }

    private void tickScenario4WaitUnloadedArrival(ServerLevel level) {
        if (stageTicks > 1200) {
            throw new AssertionError("Scenario 4 unloaded-chunk virtual transit timed out after 1200 ticks");
        }
        VirtualTransitEngine engine = LogisticsLevelManager.engine(level);
        Optional<TransitRecord> boatOpt = engine.getTransit(s4BoatTransitId);
        Optional<TransitRecord> droneOpt = engine.getTransit(s4DroneTransitId);
        qa.check("s4.transits_still_tracked_while_target_unloaded", boatOpt.isPresent() && droneOpt.isPresent(),
                "boatPresent=" + boatOpt.isPresent() + ", dronePresent=" + droneOpt.isPresent());
        TransitRecord boatRec = boatOpt.get();
        TransitRecord droneRec = droneOpt.get();

        // Check mid-flight unloaded-chunk virtual simulation!
        if (!s4BoatObservedUnloaded && boatRec.currentPos().x() >= 256.0 && boatRec.currentPos().x() <= 304.0) {
            int cx = boatRec.currentPos().chunkX();
            int cz = boatRec.currentPos().chunkZ();
            boolean unloaded = Lc2QaHelpers.isChunkCompletelyUnloaded(level, cx, cz);
            qa.check("s4.boat_intermediate_chunk_unloaded_during_transit",
                    unloaded && !boatRec.physicalEntityLoaded(),
                    "chunk=(" + cx + "," + cz + ") unloaded=" + unloaded + ", entityLoaded=" + boatRec.physicalEntityLoaded());
            s4BoatObservedUnloaded = true;
        }
        if (!s4DroneObservedUnloaded && droneRec.currentPos().x() >= 256.0 && droneRec.currentPos().x() <= 304.0) {
            int cx = droneRec.currentPos().chunkX();
            int cz = droneRec.currentPos().chunkZ();
            boolean unloaded = Lc2QaHelpers.isChunkCompletelyUnloaded(level, cx, cz);
            qa.check("s4.drone_intermediate_chunk_unloaded_during_transit",
                    unloaded && !droneRec.physicalEntityLoaded(),
                    "chunk=(" + cx + "," + cz + ") unloaded=" + unloaded + ", entityLoaded=" + droneRec.physicalEntityLoaded());
            s4DroneObservedUnloaded = true;
        }

        if (boatRec.phase() != TransitPhase.ARRIVED_UNLOADING || droneRec.phase() != TransitPhase.ARRIVED_UNLOADING) {
            return;
        }

        qa.check("s4.both_observed_unloaded_intermediate_transit",
                s4BoatObservedUnloaded && s4DroneObservedUnloaded,
                "boatObserved=" + s4BoatObservedUnloaded + ", droneObserved=" + s4DroneObservedUnloaded);
        qa.check("s4.target_chunks_still_unloaded_at_arrival",
                Lc2QaHelpers.isChunkCompletelyUnloaded(level, 22, 7)
                        && Lc2QaHelpers.isChunkCompletelyUnloaded(level, 22, 8),
                "target chunks (22,7) & (22,8) remained unloaded during virtual transit");
        qa.check("s4.pending_arrivals_queued",
                !engine.pendingArrivalsAt(Lc2Coords.toCore(s4PortBPos)).isEmpty()
                        && !engine.pendingArrivalsAt(Lc2Coords.toCore(s4StationBPos)).isEmpty(),
                "boatPending=" + engine.pendingArrivalsAt(Lc2Coords.toCore(s4PortBPos)).size()
                        + ", dronePending=" + engine.pendingArrivalsAt(Lc2Coords.toCore(s4StationBPos)).size());

        // Now load target chunks via temporary QA tickets so pending arrivals unload cleanly!
        s4HeldTempChunks.clear();
        for (int cx = 21; cx <= 23; cx++) {
            for (int cz = 7; cz <= 8; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                Lc2QaHelpers.qaHoldChunk(level, cp);
                s4HeldTempChunks.add(cp);
            }
        }
        advance(Stage.S4_WAIT_TARGET_LOAD_UNLOAD);
    }

    private void tickScenario4WaitTargetLoadAndVerify(ServerLevel level) {
        if (stageTicks > 60) {
            throw new AssertionError("Scenario 4 pending arrivals did not finish unloading after target chunks loaded");
        }
        if (LogisticsLevelManager.isTransitActive(level, s4BoatTransitId)
                || LogisticsLevelManager.isTransitActive(level, s4DroneTransitId)) {
            return;
        }
        // portB has ejector_upgrade -> delivered into adjacent containers; stB has NO ejector -> delivered to Storage (27..53)!
        Map<Item, Integer> boatDelivered = Lc2QaHelpers.countItemsInBlocks(level, s4PortBContainers);
        CargoStationBlockEntity stB = (CargoStationBlockEntity) level.getBlockEntity(s4StationBPos);
        Map<Item, Integer> droneDeliveredStorage = Lc2QaHelpers.countStationStorage(stB);
        qa.checkEquals("s4.unloaded_boat_delivered_all_320_items", 20 * 16, Lc2QaHelpers.totalItemCount(boatDelivered));
        qa.checkEquals("s4.unloaded_boat_delivered_20_stacks", 20, boatDelivered.size());
        qa.checkEquals("s4.unloaded_drone_delivered_all_160_items_storage", 10 * 16, Lc2QaHelpers.totalItemCount(droneDeliveredStorage));
        qa.checkEquals("s4.unloaded_drone_delivered_10_stacks_storage", 10, droneDeliveredStorage.size());

        // Release temporary QA chunk tickets
        for (ChunkPos cp : s4HeldTempChunks) {
            Lc2QaHelpers.qaReleaseChunk(level, cp);
        }
        s4HeldTempChunks.clear();
        advance(Stage.S5_SETUP);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 5 IMPLEMENTATION: CHUNK LOADING UPGRADE
    // -------------------------------------------------------------------------

    private void runScenario5Setup(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 5: Chunk Loading Upgrade (lc2:chunk_loading_upgrade) ===");
        CargoStationBlockEntity st = Lc2QaHelpers.placeCargoStation(level, s5StationPos, "S5-ChunkLoader");
        qa.checkEquals("s5.initial_held_chunks_zero", 0, LogisticsChunkTickets.heldChunkCount(level));
        qa.check("s5.initial_inactive", !st.isChunkLoaderActive(), "inactive before upgrade");

        st.setStoredEu(100.0);
        st.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("lc2:chunk_loading_upgrade", 1));
    }

    private void runScenario5VerifyHoldAndRemove(ServerLevel level) {
        CargoStationBlockEntity st = (CargoStationBlockEntity) level.getBlockEntity(s5StationPos);
        qa.check("s5.chunk_loader_active", st.isChunkLoaderActive(),
                "active=" + st.isChunkLoaderActive() + ", eu=" + st.getStoredEu()
                        + ", held=" + LogisticsChunkTickets.heldChunkCount(level));
        qa.checkEquals("s5.held_chunk_count_9", 9, LogisticsChunkTickets.heldChunkCount(level));
        ChunkPos center = new ChunkPos(s5StationPos);
        int heldIn3x3 = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (LogisticsChunkTickets.isChunkHeld(level, new ChunkPos(center.x + dx, center.z + dz))) {
                    heldIn3x3++;
                }
            }
        }
        qa.checkEquals("s5.all_9_chunks_in_3x3_held", 9, heldIn3x3);
        qa.checkClose("s5.standby_drain_2eu_per_tick", 80.0, st.getStoredEu(), 1e-6);

        // Remove upgrade -> must release all 9 tickets on next tick
        st.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, ItemStack.EMPTY);
    }

    private void runScenario5VerifyRemovedAndStartDeplete(ServerLevel level) {
        CargoStationBlockEntity st = (CargoStationBlockEntity) level.getBlockEntity(s5StationPos);
        qa.check("s5.released_on_upgrade_removal", !st.isChunkLoaderActive(), "active=" + st.isChunkLoaderActive());
        qa.checkEquals("s5.held_count_zero_after_removal", 0, LogisticsChunkTickets.heldChunkCount(level));

        // Re-insert upgrade with only 6.0 EU (enough for exactly 3 ticks, then releases on tick 4!)
        st.setStoredEu(6.0);
        st.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("lc2:chunk_loading_upgrade", 1));
    }

    // -------------------------------------------------------------------------
    // SCENARIO 6 IMPLEMENTATION: MULTI-VEHICLE COLLISION AVOIDANCE
    // -------------------------------------------------------------------------

    private void runScenario6SetupAndDispatch(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 6: Multi-Vehicle Collision Avoidance ===");
        // 1) Opposing boats on shared 3-wide waterway
        Lc2QaHelpers.buildCanalEastWest(level, 1, 43, WATER_Y, -40);
        SmartPortBlockEntity portA = Lc2QaHelpers.placeSmartPort(level, s6PortAPos, Direction.EAST, "S6-ColPortA");
        SmartPortBlockEntity portB = Lc2QaHelpers.placeSmartPort(level, s6PortBPos, Direction.WEST, "S6-ColPortB");
        level.setBlock(s6PortAChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s6PortBChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        Lc2QaHelpers.spawnDockedBoatAt(level, portA);
        Lc2QaHelpers.spawnDockedBoatAt(level, portB);

        portA.setTargetPos(s6PortBPos);
        portA.setAutoReturn(false);
        portA.setStoredEu(10000.0);
        for (int i = 0; i < 5; i++) {
            portA.setItem(i, new ItemStack(Items.IRON_INGOT, 16));
        }

        portB.setTargetPos(s6PortAPos);
        portB.setAutoReturn(false);
        portB.setStoredEu(10000.0);
        for (int i = 0; i < 5; i++) {
            portB.setItem(i, new ItemStack(Items.GOLD_INGOT, 16));
        }

        // Simultaneous opposing boat dispatch!
        qa.check("s6.boat_AB_dispatch", portA.tryDispatch(level, true), "statusA=" + portA.getStatusKey());
        qa.check("s6.boat_BA_dispatch", portB.tryDispatch(level, true), "statusB=" + portB.getStatusKey());
        s6BoatABId = findLatestTransitFrom(level, s6PortAPos, VehicleKind.ELECTRIC_CARGO_BOAT).id();
        s6BoatBAId = findLatestTransitFrom(level, s6PortBPos, VehicleKind.ELECTRIC_CARGO_BOAT).id();

        // 2) Opposing drones between Stations A <-> B (2 each way = 4 simultaneous drones!)
        CargoStationBlockEntity dA = Lc2QaHelpers.placeCargoStation(level, s6DroneAPos, "S6-ColDroneA");
        CargoStationBlockEntity dB = Lc2QaHelpers.placeCargoStation(level, s6DroneBPos, "S6-ColDroneB");
        dA.setTargetPos(s6DroneBPos);
        dA.setAutoReturn(false);
        dA.setStoredEu(10000.0);
        dA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        dA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        dA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 1, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));

        dB.setTargetPos(s6DroneAPos);
        dB.setAutoReturn(false);
        dB.setStoredEu(10000.0);
        dB.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        dB.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        dB.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 1, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));

        s6DroneIds.clear();
        Set<UUID> seen = new HashSet<>();
        for (TransitRecord r : LogisticsLevelManager.activeTransits(level)) {
            seen.add(r.id());
        }
        qa.check("s6.drone_AB_1_dispatch", dA.tryDispatch(level, true), "dA1=" + dA.getStatusKey());
        qa.check("s6.drone_BA_1_dispatch", dB.tryDispatch(level, true), "dB1=" + dB.getStatusKey());
        qa.check("s6.drone_AB_2_dispatch", dA.tryDispatch(level, true), "dA2=" + dA.getStatusKey());
        qa.check("s6.drone_BA_2_dispatch", dB.tryDispatch(level, true), "dB2=" + dB.getStatusKey());
        for (TransitRecord r : LogisticsLevelManager.activeTransits(level)) {
            if (!seen.contains(r.id()) && r.vehicleKind().isDrone()) {
                s6DroneIds.add(r.id());
            }
        }
        qa.checkEquals("s6.four_opposing_drones_active", 4, s6DroneIds.size());
    }

    private void tickScenario6WaitCompletion(ServerLevel level) {
        if (stageTicks > 400) {
            throw new AssertionError("Scenario 6 opposing vehicles deadlocked/timed out after 400 ticks");
        }
        VirtualTransitEngine engine = LogisticsLevelManager.engine(level);

        // Measure boat separation every tick while both boats are moving
        Optional<TransitRecord> bAB = engine.getTransit(s6BoatABId);
        Optional<TransitRecord> bBA = engine.getTransit(s6BoatBAId);
        if (bAB.isPresent() && bBA.isPresent()
                && bAB.get().phase().isMoving() && bBA.get().phase().isMoving()) {
            Vec3d p1 = bAB.get().currentPos();
            Vec3d p2 = bBA.get().currentPos();
            double horizDist = Math.hypot(p1.x() - p2.x(), p1.z() - p2.z());
            s6MinBoatDist = Math.min(s6MinBoatDist, horizDist);
            if (p1.x() > p2.x()) {
                s6BoatsPassedEachOther = true;
            }
        }

        // Measure drone 3D separation while cruising above stations
        List<TransitRecord> activeDrones = new ArrayList<>();
        for (UUID id : s6DroneIds) {
            engine.getTransit(id).ifPresent(r -> {
                if (r.phase().isMoving() && r.currentPos().y() > SURFACE_Y + 8.0) {
                    activeDrones.add(r);
                }
            });
        }
        for (int i = 0; i < activeDrones.size(); i++) {
            for (int j = i + 1; j < activeDrones.size(); j++) {
                double d = activeDrones.get(i).currentPos().distanceTo(activeDrones.get(j).currentPos());
                s6MinDroneCruiseDist = Math.min(s6MinDroneCruiseDist, d);
            }
        }

        boolean anyActive = bAB.isPresent() || bBA.isPresent();
        for (UUID id : s6DroneIds) {
            if (engine.getTransit(id).isPresent()) {
                anyActive = true;
                break;
            }
        }
        if (anyActive) {
            return;
        }

        qa.metric("s6.min_boat_passing_dist", String.format("%.3f", s6MinBoatDist));
        qa.metric("s6.min_drone_cruise_dist", String.format("%.3f", s6MinDroneCruiseDist));
        qa.check("s6.opposing_boats_passed_no_overlap", s6BoatsPassedEachOther && s6MinBoatDist >= 0.40,
                "passed=" + s6BoatsPassedEachOther + ", minBoatDist=" + String.format("%.3f", s6MinBoatDist));
        qa.check("s6.opposing_drones_no_overlap", s6MinDroneCruiseDist >= 1.50,
                "minDroneCruiseDist=" + String.format("%.3f", s6MinDroneCruiseDist));

        // Verify cargo arrived cleanly into opposite ports' Storage zones (27..53), leaving Outbound empty!
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(s6PortAPos);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s6PortBPos);
        Map<Item, Integer> atPortBStorage = Lc2QaHelpers.countStationStorage(portB);
        Map<Item, Integer> atPortAStorage = Lc2QaHelpers.countStationStorage(portA);
        qa.checkEquals("s6.boat_AB_delivered_iron_storage", 80, atPortBStorage.getOrDefault(Items.IRON_INGOT, 0));
        qa.checkEquals("s6.boat_BA_delivered_gold_storage", 80, atPortAStorage.getOrDefault(Items.GOLD_INGOT, 0));
        qa.checkEquals("s6.portA_outbound_empty", 0, Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationOutbound(portA)));
        qa.checkEquals("s6.portB_outbound_empty", 0, Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationOutbound(portB)));

        // Verify all 4 opposing drones docked cleanly (2 at A, 2 at B)
        CargoStationBlockEntity dA = (CargoStationBlockEntity) level.getBlockEntity(s6DroneAPos);
        CargoStationBlockEntity dB = (CargoStationBlockEntity) level.getBlockEntity(s6DroneBPos);
        qa.checkEquals("s6.drones_docked_at_A", 2, Integer.bitCount(dA.getDockedBayMask()));
        qa.checkEquals("s6.drones_docked_at_B", 2, Integer.bitCount(dB.getDockedBayMask()));
        advance(Stage.S8_ZONE_ISOLATION_SETUP);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 8 IMPLEMENTATION: 2-ZONE ISOLATION, SWAP, MIGRATION & ITEMS-FIRST
    // -------------------------------------------------------------------------

    private void runScenario8ZoneIsolationSetup(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 8: 2-Zone Isolation, Swap, NBT Migration & Items-First ===");
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);

        // --- 8A: External Hopper & ForgeCapabilities.ITEM_HANDLER Zone Isolation ---
        SmartPortBlockEntity hPort = Lc2QaHelpers.placeSmartPort(level, s8HopperPortPos, Direction.SOUTH, "S8-HopperPort");
        // Seed Battery slot (54) and 1 Outbound stack (slot 0) + 1 Storage stack (slot 27)
        hPort.setItem(SmartPortBlockEntity.SLOT_BATTERY, Lc2QaHelpers.itemStack("ic2:single_use_battery", 8));
        hPort.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.COBBLESTONE, 16));
        hPort.setItem(SmartPortBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.GOLD_NUGGET, 4));

        // Verify ForgeCapabilities.ITEM_HANDLER across all 6 faces + null strictly enforces 2-zone rules!
        for (Direction side : new Direction[]{null, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            IItemHandler handler = hPort.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            qa.check("s8.cap_present_" + side, handler != null, "side=" + side);
            // Slot 0 (Outbound): NEVER extractable externally!
            ItemStack stealOutbound = handler.extractItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, 1, true);
            qa.check("s8.cap_cannot_extract_outbound_" + side, stealOutbound.isEmpty(), "extracted=" + stealOutbound);
            // Slot 27 (Storage): NEVER insertable externally!
            ItemStack pushStorage = handler.insertItem(SmartPortBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.STONE, 1), true);
            qa.checkEquals("s8.cap_cannot_insert_storage_" + side, 1, pushStorage.getCount());
            // Battery slot (index 54 in automatedSlots): NEVER extractable externally!
            ItemStack stealBat = handler.extractItem(SmartPortBlockEntity.SLOT_BATTERY, 1, true);
            qa.check("s8.cap_cannot_extract_battery_" + side, stealBat.isEmpty(), "extracted=" + stealBat);
            // Slot 27 (Storage): extractable externally!
            ItemStack pullStorage = handler.extractItem(SmartPortBlockEntity.SLOT_STORAGE_START, 1, true);
            qa.check("s8.cap_can_extract_storage_" + side, !pullStorage.isEmpty() && pullStorage.is(Items.GOLD_NUGGET),
                    "extracted=" + pullStorage);
        }

        // Fill ALL remaining Outbound slots (1..26) with 1 full stack each so Outbound is completely full (27/27)
        // except slot 0 has 16 cobblestone; then place Hoppers above (pushing 32 cobblestone + 8 dirt) and below!
        for (int i = 1; i < SmartPortBlockEntity.SLOT_OUTBOUND_COUNT; i++) {
            hPort.setItem(i, new ItemStack(Items.STONE, 64));
        }
        level.setBlock(s8HopperTopPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), Block.UPDATE_ALL);
        level.setBlock(s8HopperBottomPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), Block.UPDATE_ALL);
        // Push 4 cobblestone (merges into Outbound slot 0) + 4 dirt (cannot fit in full Outbound -> MUST NOT spill into Storage!)
        Lc2QaHelpers.insertItemIntoBlock(level, s8HopperTopPos, new ItemStack(Items.COBBLESTONE, 4));
        Lc2QaHelpers.insertItemIntoBlock(level, s8HopperTopPos, new ItemStack(Items.DIRT, 4));

        // --- 8B: Ejector / Pulling Upgrade Zone Isolation ---
        CargoStationBlockEntity upgSt = Lc2QaHelpers.placeCargoStation(level, s8UpgStationPos, "S8-UpgradeStation");
        Lc2QaHelpers.placeCargoStation(level, s8UpgDummyTargetPos, "S8-UpgradeTarget");
        level.setBlock(s8UpgWestChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s8UpgEastChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s8UpgNorthChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        Lc2QaHelpers.insertItemIntoBlock(level, s8UpgWestChestPos, new ItemStack(Items.IRON_INGOT, 12));
        Lc2QaHelpers.insertItemIntoBlock(level, s8UpgNorthChestPos, new ItemStack(Items.GOLD_INGOT, 12));
        upgSt.setItem(CargoStationBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.EMERALD, 6));
        upgSt.setItem(CargoStationBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.DIAMOND, 10));

        ItemStack pullWest = Lc2FieldHelpers.configureUpgradeFace(level, fakePlayer, s8UpgStationPos, "ic2:pulling_upgrade", Direction.WEST);
        ItemStack ejectEast = Lc2FieldHelpers.configureUpgradeFace(level, fakePlayer, s8UpgStationPos, "ic2:ejector_upgrade", Direction.EAST);
        upgSt.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, pullWest);
        upgSt.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 1, ejectEast);

        // --- 8C: Atomic Swap Button (swapCargoZones), Comparator, Menu Quick-Move & Legacy v1 NBT Migration ---
        BlockPos swapPos = new BlockPos(-34, SURFACE_Y, 0);
        SmartPortBlockEntity swapPort = Lc2QaHelpers.placeSmartPort(level, swapPos, Direction.SOUTH, "S8-SwapPort");
        // Storage-only -> Comparator MUST be 0!
        swapPort.setItem(SmartPortBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.DIAMOND, 16));
        swapPort.setItem(SmartPortBlockEntity.SLOT_STORAGE_START + 13, new ItemStack(Items.EMERALD, 8));
        swapPort.setItem(SmartPortBlockEntity.SLOT_STORAGE_START + 26, new ItemStack(Items.NETHERITE_INGOT, 4));
        qa.checkEquals("s8.comparator_zero_when_only_storage_filled", 0, swapPort.getCargoComparatorSignal());

        // Add distinct Outbound items -> Comparator > 0!
        swapPort.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.RAW_IRON, 20));
        swapPort.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START + 13, new ItemStack(Items.RAW_COPPER, 12));
        qa.check("s8.comparator_nonzero_when_outbound_filled", swapPort.getCargoComparatorSignal() > 0,
                "signal=" + swapPort.getCargoComparatorSignal());

        // Atomic Swap!
        swapPort.swapCargoZones();
        qa.checkEquals("s8.swap_status_key", "status.lc2.swapped", swapPort.getStatusKey());
        qa.check("s8.swap_slot_0_to_27",
                swapPort.getItem(0).is(Items.DIAMOND) && swapPort.getItem(0).getCount() == 16
                        && swapPort.getItem(27).is(Items.RAW_IRON) && swapPort.getItem(27).getCount() == 20,
                "slot0=" + swapPort.getItem(0) + ", slot27=" + swapPort.getItem(27));
        qa.check("s8.swap_slot_13_to_40",
                swapPort.getItem(13).is(Items.EMERALD) && swapPort.getItem(13).getCount() == 8
                        && swapPort.getItem(40).is(Items.RAW_COPPER) && swapPort.getItem(40).getCount() == 12,
                "slot13=" + swapPort.getItem(13) + ", slot40=" + swapPort.getItem(40));
        qa.check("s8.swap_slot_26_to_53",
                swapPort.getItem(26).is(Items.NETHERITE_INGOT) && swapPort.getItem(26).getCount() == 4
                        && swapPort.getItem(53).isEmpty(),
                "slot26=" + swapPort.getItem(26) + ", slot53=" + swapPort.getItem(53));

        // Verify Menu Quick-Move on SmartPortMenu & CargoStationMenu moves normal items ONLY to Outbound (0..26)!
        swapPort.clearContent();
        fakePlayer.getInventory().clearContent();
        fakePlayer.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, 10));
        SmartPortMenu spMenu = new SmartPortMenu(1, fakePlayer.getInventory(), swapPort);
        qa.checkEquals("s8.smart_port_menu_total_slots", 95, spMenu.slots.size());
        // Player hotbar slot 0 is at menu index 59 + 27 = 86
        spMenu.quickMoveStack(fakePlayer, 86);
        qa.checkEquals("s8.smart_port_quick_move_to_outbound", 10,
                Lc2QaHelpers.countStationOutbound(swapPort).getOrDefault(Items.COPPER_INGOT, 0));
        qa.checkEquals("s8.smart_port_quick_move_never_storage", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationStorage(swapPort)));
        // Fill all 27 Outbound slots -> quick-move of another item MUST NOT spill into Storage!
        for (int i = 0; i < 27; i++) {
            swapPort.setItem(i, new ItemStack(Items.STONE, 64));
        }
        fakePlayer.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
        spMenu.quickMoveStack(fakePlayer, 86);
        qa.checkEquals("s8.smart_port_quick_move_refuses_storage_when_outbound_full", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationStorage(swapPort)));
        qa.checkEquals("s8.smart_port_quick_move_left_in_player_inv", 5,
                fakePlayer.getInventory().getItem(0).getCount());

        // Same Menu Quick-Move check for CargoStationMenu (102 total slots, hotbar slot 0 at index 66 + 27 = 93)
        BlockPos qmStationPos = new BlockPos(-36, SURFACE_Y, 0);
        CargoStationBlockEntity qmStation = Lc2QaHelpers.placeCargoStation(level, qmStationPos, "S8-QmStation");
        fakePlayer.getInventory().clearContent();
        fakePlayer.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 14));
        CargoStationMenu csMenu = new CargoStationMenu(2, fakePlayer.getInventory(), qmStation);
        qa.checkEquals("s8.cargo_station_menu_total_slots", 102, csMenu.slots.size());
        csMenu.quickMoveStack(fakePlayer, 93);
        qa.checkEquals("s8.cargo_station_quick_move_to_outbound", 14,
                Lc2QaHelpers.countStationOutbound(qmStation).getOrDefault(Items.IRON_INGOT, 0));
        qa.checkEquals("s8.cargo_station_quick_move_never_storage", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationStorage(qmStation)));
        fakePlayer.getInventory().clearContent();

        // Verify Legacy v1 NBT Migration (LayoutVersion absent -> clean remap with 0 lost items)!
        verifyLegacyNbtMigration(level);
    }

    private void verifyLegacyNbtMigration(ServerLevel level) {
        // 1) Legacy SmartPort (v1: 32 slots -> 0..26 cargo, 27 battery, 28..31 upgrades)
        NonNullList<ItemStack> legacyPortItems = NonNullList.withSize(32, ItemStack.EMPTY);
        legacyPortItems.set(0, new ItemStack(Items.IRON_INGOT, 11));
        legacyPortItems.set(26, new ItemStack(Items.GOLD_INGOT, 7));
        legacyPortItems.set(27, Lc2QaHelpers.itemStack("ic2:single_use_battery", 12));
        legacyPortItems.set(28, Lc2QaHelpers.itemStack("ic2:overclocker_upgrade", 2));
        legacyPortItems.set(31, Lc2QaHelpers.itemStack("lc2:chunk_loading_upgrade", 1));
        CompoundTag legacyPortTag = new CompoundTag();
        ContainerHelper.saveAllItems(legacyPortTag, legacyPortItems);
        legacyPortTag.putDouble("StoredEu", 4200.0);
        legacyPortTag.putString("StationName", "Legacy Port");

        SmartPortBlockEntity migratedPort = new SmartPortBlockEntity(
                new BlockPos(-38, SURFACE_Y, 0),
                Lc2Registries.SMART_PORT_BLOCK.get().defaultBlockState()
        );
        migratedPort.load(legacyPortTag);
        qa.check("s8.migrate_port_outbound_0",
                migratedPort.getItem(0).is(Items.IRON_INGOT) && migratedPort.getItem(0).getCount() == 11,
                "slot0=" + migratedPort.getItem(0));
        qa.check("s8.migrate_port_outbound_26",
                migratedPort.getItem(26).is(Items.GOLD_INGOT) && migratedPort.getItem(26).getCount() == 7,
                "slot26=" + migratedPort.getItem(26));
        qa.checkEquals("s8.migrate_port_battery_54", 12, migratedPort.getBatteryCount());
        qa.check("s8.migrate_port_upgrade_55",
                "ic2:overclocker_upgrade".equals(Lc2Coords.itemId(migratedPort.getItem(55)))
                        && migratedPort.getItem(55).getCount() == 2,
                "slot55=" + migratedPort.getItem(55));
        qa.check("s8.migrate_port_upgrade_58",
                "lc2:chunk_loading_upgrade".equals(Lc2Coords.itemId(migratedPort.getItem(58))),
                "slot58=" + migratedPort.getItem(58));
        qa.checkEquals("s8.migrate_port_storage_empty", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationStorage(migratedPort)));
        CompoundTag savedPortV2 = migratedPort.saveWithoutMetadata();
        qa.checkEquals("s8.migrate_port_saves_layout_v2", 2, savedPortV2.getInt("LayoutVersion"));

        // 2) Legacy CargoStation (v1: 30 slots -> 0..17 cargo, 18 RF chip, 19..24 drones, 25 battery, 26..29 upgrades)
        NonNullList<ItemStack> legacyStationItems = NonNullList.withSize(30, ItemStack.EMPTY);
        legacyStationItems.set(0, new ItemStack(Items.COPPER_INGOT, 9));
        legacyStationItems.set(17, new ItemStack(Items.DIAMOND, 5));
        legacyStationItems.set(18, Lc2QaHelpers.itemStack("lc2:rf_chip_2", 1));
        legacyStationItems.set(19, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        legacyStationItems.set(24, Lc2QaHelpers.itemStack("lc2:heavy_transport_drone", 1));
        legacyStationItems.set(25, Lc2QaHelpers.itemStack("ic2:single_use_battery", 6));
        legacyStationItems.set(26, Lc2QaHelpers.itemStack("ic2:transformer_upgrade", 1));
        legacyStationItems.set(29, Lc2QaHelpers.itemStack("ic2:energy_storage_upgrade", 1));
        CompoundTag legacyStationTag = new CompoundTag();
        ContainerHelper.saveAllItems(legacyStationTag, legacyStationItems);
        legacyStationTag.putDouble("StoredEu", 5500.0);
        legacyStationTag.putString("StationName", "Legacy Station");

        CargoStationBlockEntity migratedStation = new CargoStationBlockEntity(
                new BlockPos(-40, SURFACE_Y, 0),
                Lc2Registries.CARGO_STATION_BLOCK.get().defaultBlockState()
        );
        migratedStation.load(legacyStationTag);
        qa.check("s8.migrate_station_outbound_0",
                migratedStation.getItem(0).is(Items.COPPER_INGOT) && migratedStation.getItem(0).getCount() == 9,
                "slot0=" + migratedStation.getItem(0));
        qa.check("s8.migrate_station_outbound_17",
                migratedStation.getItem(17).is(Items.DIAMOND) && migratedStation.getItem(17).getCount() == 5,
                "slot17=" + migratedStation.getItem(17));
        qa.checkEquals("s8.migrate_station_rf_54", RfChipTier.TIER_2, migratedStation.getRfChipTier());
        qa.checkEquals("s8.migrate_station_bay0_55", VehicleKind.STANDARD_DRONE, migratedStation.getBayDroneKind(0));
        qa.checkEquals("s8.migrate_station_bay5_60", VehicleKind.HEAVY_DRONE, migratedStation.getBayDroneKind(5));
        qa.checkEquals("s8.migrate_station_battery_61", 6, migratedStation.getBatteryCount());
        qa.check("s8.migrate_station_upgrade_62",
                "ic2:transformer_upgrade".equals(Lc2Coords.itemId(migratedStation.getItem(62))),
                "slot62=" + migratedStation.getItem(62));
        qa.check("s8.migrate_station_upgrade_65",
                "ic2:energy_storage_upgrade".equals(Lc2Coords.itemId(migratedStation.getItem(65))),
                "slot65=" + migratedStation.getItem(65));
        CompoundTag savedStationV2 = migratedStation.saveWithoutMetadata();
        qa.checkEquals("s8.migrate_station_saves_layout_v2", 2, savedStationV2.getInt("LayoutVersion"));
    }

    private void runScenario8ZoneIsolationVerify(ServerLevel level) {
        // --- Verify 8A: Hopper push -> Outbound ONLY; Hopper pull -> Storage ONLY ---
        SmartPortBlockEntity hPort = (SmartPortBlockEntity) level.getBlockEntity(s8HopperPortPos);
        Map<Item, Integer> topHopper = Lc2QaHelpers.countItemsInBlocks(level, List.of(s8HopperTopPos));
        Map<Item, Integer> bottomHopper = Lc2QaHelpers.countItemsInBlocks(level, List.of(s8HopperBottomPos));
        Map<Item, Integer> portOutbound = Lc2QaHelpers.countStationOutbound(hPort);
        Map<Item, Integer> portStorage = Lc2QaHelpers.countStationStorage(hPort);

        // Top hopper pushed 4 cobblestone into Outbound slot 0 (16 -> 20), and 4 dirt stayed blocked in top hopper!
        qa.checkEquals("s8.hopper_pushed_cobble_to_outbound", 20, portOutbound.getOrDefault(Items.COBBLESTONE, 0));
        qa.checkEquals("s8.hopper_dirt_blocked_from_storage", 4, topHopper.getOrDefault(Items.DIRT, 0));
        qa.checkEquals("s8.hopper_never_spilled_dirt_into_storage", 0, portStorage.getOrDefault(Items.DIRT, 0));
        // Bottom hopper extracted ONLY the 4 gold nuggets from Storage, NEVER stealing cobblestone/stone from Outbound or batteries!
        qa.checkEquals("s8.hopper_extracted_storage_gold_nuggets", 4, bottomHopper.getOrDefault(Items.GOLD_NUGGET, 0));
        qa.checkEquals("s8.hopper_never_stole_outbound_cobble", 0, bottomHopper.getOrDefault(Items.COBBLESTONE, 0));
        qa.checkEquals("s8.hopper_never_stole_outbound_stone", 0, bottomHopper.getOrDefault(Items.STONE, 0));
        qa.checkEquals("s8.hopper_never_stole_battery", 8, hPort.getBatteryCount());
        qa.checkEquals("s8.storage_drained_cleanly", 0, Lc2QaHelpers.totalItemCount(portStorage));

        // --- Verify 8B: Pulling upgrade -> Outbound ONLY; Ejector upgrade -> Storage ONLY ---
        CargoStationBlockEntity upgSt = (CargoStationBlockEntity) level.getBlockEntity(s8UpgStationPos);
        Map<Item, Integer> westChest = Lc2QaHelpers.countItemsInBlocks(level, List.of(s8UpgWestChestPos));
        Map<Item, Integer> eastChest = Lc2QaHelpers.countItemsInBlocks(level, List.of(s8UpgEastChestPos));
        Map<Item, Integer> northChest = Lc2QaHelpers.countItemsInBlocks(level, List.of(s8UpgNorthChestPos));
        Map<Item, Integer> upgOutbound = Lc2QaHelpers.countStationOutbound(upgSt);
        Map<Item, Integer> upgStorage = Lc2QaHelpers.countStationStorage(upgSt);

        // pulling_upgrade(WEST) pulled all 12 iron_ingot strictly into Outbound (NOT Storage!) and ignored NORTH chest!
        qa.checkEquals("s8.pulling_pulled_iron_to_outbound", 12, upgOutbound.getOrDefault(Items.IRON_INGOT, 0));
        qa.checkEquals("s8.pulling_west_chest_emptied", 0, Lc2QaHelpers.totalItemCount(westChest));
        qa.checkEquals("s8.pulling_ignored_unconfigured_north", 12, northChest.getOrDefault(Items.GOLD_INGOT, 0));
        qa.checkEquals("s8.pulling_never_touched_storage", 0, upgStorage.getOrDefault(Items.IRON_INGOT, 0));
        // ejector_upgrade(EAST) pushed all 10 diamond strictly from Storage into EAST chest and NEVER touched Outbound!
        qa.checkEquals("s8.ejector_pushed_storage_diamonds_to_east", 10, eastChest.getOrDefault(Items.DIAMOND, 0));
        qa.checkEquals("s8.ejector_storage_emptied", 0, Lc2QaHelpers.totalItemCount(upgStorage));
        qa.checkEquals("s8.ejector_never_touched_outbound_emerald", 6, upgOutbound.getOrDefault(Items.EMERALD, 0));
        qa.checkEquals("s8.ejector_never_ejected_outbound_to_east", 0, eastChest.getOrDefault(Items.EMERALD, 0));

        // --- Verify 8B-2: Vehicle Launch NEVER ships items from Storage (27..53) & NEVER pulls chest without pulling_upgrade! ---
        upgSt.clearContent();
        Lc2QaHelpers.clearContainerBlock(level, s8UpgWestChestPos);
        Lc2QaHelpers.clearContainerBlock(level, s8UpgEastChestPos);
        Lc2QaHelpers.insertItemIntoBlock(level, s8UpgWestChestPos, new ItemStack(Items.REDSTONE, 16));
        upgSt.setItem(CargoStationBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.COPPER_INGOT, 24));
        upgSt.setTargetPos(s8UpgDummyTargetPos);
        upgSt.setAutoReturn(false);
        upgSt.setStoredEu(5000.0);
        upgSt.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        upgSt.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));
        qa.check("s8.vehicle_storage_isolation_dispatched", upgSt.tryDispatch(level, true), "status=" + upgSt.getStatusKey());
        TransitRecord isoRec = findLatestTransitFrom(level, s8UpgStationPos, VehicleKind.STANDARD_DRONE);
        qa.check("s8.vehicle_storage_isolation_record", isoRec != null, "isoRec=" + isoRec);
        List<ItemStack> isoCargo = LogisticsLevelManager.data(level).getTransitCargo(isoRec.id());
        qa.checkEquals("s8.vehicle_loaded_zero_from_storage_or_chest", 0, isoCargo.size());
        qa.checkEquals("s8.storage_copper_still_untouched", 24,
                Lc2QaHelpers.countStationStorage(upgSt).getOrDefault(Items.COPPER_INGOT, 0));
        qa.checkEquals("s8.adjacent_chest_redstone_untouched_without_pulling", 16,
                Lc2QaHelpers.countItemsInBlocks(level, List.of(s8UpgWestChestPos)).getOrDefault(Items.REDSTONE, 0));
        // Clean up probe drone flight
        LogisticsLevelManager.cancelTransitAndDrop(level, isoRec.id(), Vec3.ZERO, false);
        for (CargoDroneEntity drone : level.getEntitiesOfClass(
                CargoDroneEntity.class, AABB.ofSize(Vec3.atCenterOf(s8UpgStationPos), 12.0, 24.0, 12.0))) {
            if (isoRec.id().equals(drone.getTransitId())) {
                drone.discard();
            }
        }
    }

    private void runScenario8ItemsBeforeVehicleSetup(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 8B: Items-Before-Drone & Items-Before-Boat Regression ===");

        // 1) Items FIRST -> Drone SECOND: put items in Outbound + redstone ON while 0 drones parked!
        CargoStationBlockEntity dOrig = Lc2QaHelpers.placeCargoStation(level, s8DroneOrigPos, "S8-ItemsFirstDroneA");
        Lc2QaHelpers.placeCargoStation(level, s8DroneTargetPos, "S8-ItemsFirstDroneB");
        dOrig.setTargetPos(s8DroneTargetPos);
        dOrig.setAutoReturn(false);
        dOrig.setStoredEu(5000.0);
        dOrig.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        dOrig.setItem(CargoStationBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.IRON_INGOT, 32));
        dOrig.setItem(CargoStationBlockEntity.SLOT_OUTBOUND_START + 1, new ItemStack(Items.GOLD_INGOT, 16));
        level.setBlock(s8DroneRedstonePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

        // 2) Items FIRST -> Boat SECOND (placed in water ~3.0m from berth, testing BOAT_DOCK_RADIUS = 3.5!)
        Lc2QaHelpers.buildCanalEastWest(level, -19, -1, WATER_Y, -26);
        SmartPortBlockEntity bOrig = Lc2QaHelpers.placeSmartPort(level, s8BoatWaterOrigPos, Direction.EAST, "S8-ItemsFirstBoatWaterA");
        Lc2QaHelpers.placeSmartPort(level, s8BoatWaterTargetPos, Direction.WEST, "S8-ItemsFirstBoatWaterB");
        bOrig.setTargetPos(s8BoatWaterTargetPos);
        bOrig.setAutoReturn(false);
        bOrig.setStoredEu(10000.0);
        bOrig.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.COPPER_INGOT, 40));
        bOrig.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START + 1, new ItemStack(Items.COAL, 24));
        level.setBlock(s8BoatWaterRedstonePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

        // 3) Items FIRST -> Boat SECOND via right-clicking port (spawnAndDockBoat -> dispatchCooldown = 0!)
        Lc2QaHelpers.buildCanalEastWest(level, -19, -1, WATER_Y, 14);
        SmartPortBlockEntity bDockOrig = Lc2QaHelpers.placeSmartPort(level, s8BoatDockOrigPos, Direction.EAST, "S8-ItemsFirstBoatDockA");
        Lc2QaHelpers.placeSmartPort(level, s8BoatDockTargetPos, Direction.WEST, "S8-ItemsFirstBoatDockB");
        bDockOrig.setTargetPos(s8BoatDockTargetPos);
        bDockOrig.setAutoReturn(false);
        bDockOrig.setStoredEu(10000.0);
        bDockOrig.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.DIAMOND, 18));
        level.setBlock(s8BoatDockRedstonePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
    }

    private void runScenario8ItemsBeforeVehiclePlace(ServerLevel level) {
        CargoStationBlockEntity dOrig = (CargoStationBlockEntity) level.getBlockEntity(s8DroneOrigPos);
        SmartPortBlockEntity bOrig = (SmartPortBlockEntity) level.getBlockEntity(s8BoatWaterOrigPos);
        SmartPortBlockEntity bDockOrig = (SmartPortBlockEntity) level.getBlockEntity(s8BoatDockOrigPos);

        // Verify ALL THREE stations tried dispatching while redstone ON + items present and recorded NO_VEHICLE!
        qa.checkEquals("s8.items_first_drone_status_no_vehicle",
                DispatchFailure.NO_VEHICLE.translationKey(), dOrig.getStatusKey());
        qa.checkEquals("s8.items_first_boat_water_status_no_vehicle",
                DispatchFailure.NO_VEHICLE.translationKey(), bOrig.getStatusKey());
        qa.checkEquals("s8.items_first_boat_dock_status_no_vehicle",
                DispatchFailure.NO_VEHICLE.translationKey(), bDockOrig.getStatusKey());
        qa.check("s8.items_first_no_premature_transits",
                findLatestTransitFrom(level, s8DroneOrigPos, VehicleKind.STANDARD_DRONE) == null
                        && findLatestTransitFrom(level, s8BoatWaterOrigPos, VehicleKind.ELECTRIC_CARGO_BOAT) == null
                        && findLatestTransitFrom(level, s8BoatDockOrigPos, VehicleKind.ELECTRIC_CARGO_BOAT) == null,
                "0 active transits before placing vehicles");

        // Now place the Drone into bay 55 (SLOT_DRONE_BAY_START) -> MUST reset dispatchCooldown=0 & launch immediately!
        dOrig.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:transport_drone", 1));

        // Now place the Boat in water ~3.0 blocks away from berth center (x = berthX + 3.0) -> tests BOAT_DOCK_RADIUS=3.5 & immediate reset!
        var berth = bOrig.refreshWaterBerth(level);
        var surf = berth.waterSurfaceCenter();
        ElectricCargoBoatEntity waterBoat = new ElectricCargoBoatEntity(Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
        waterBoat.setPos(surf.x() + 3.0, surf.y(), surf.z());
        qa.check("s8.items_first_boat_spawned_at_3m", level.addFreshEntity(waterBoat),
                "spawned at offset +3.0m from berth " + berth);

        // Now place the second Boat via SmartPortBlockEntity.spawnAndDockBoat -> MUST reset dispatchCooldown=0 & launch immediately!
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        ItemStack boatItem = Lc2QaHelpers.itemStack("lc2:electric_cargo_boat", 1);
        qa.check("s8.items_first_boat_spawn_and_dock_ok", bDockOrig.spawnAndDockBoat(level, fakePlayer, boatItem),
                "spawnAndDockBoat succeeded");
    }

    private void runScenario8ItemsBeforeVehicleLaunchCheck(ServerLevel level) {
        // Within 2 ticks (proving cooldown was immediately reset to 0 instead of waiting 20 ticks!), ALL 3 MUST have launched!
        TransitRecord dRec = findLatestTransitFrom(level, s8DroneOrigPos, VehicleKind.STANDARD_DRONE);
        TransitRecord bWaterRec = findLatestTransitFrom(level, s8BoatWaterOrigPos, VehicleKind.ELECTRIC_CARGO_BOAT);
        TransitRecord bDockRec = findLatestTransitFrom(level, s8BoatDockOrigPos, VehicleKind.ELECTRIC_CARGO_BOAT);

        qa.check("s8.items_first_drone_launched_immediately", dRec != null, "dRec=" + dRec);
        qa.check("s8.items_first_boat_water_3m_launched_immediately", bWaterRec != null, "bWaterRec=" + bWaterRec);
        qa.check("s8.items_first_boat_dock_launched_immediately", bDockRec != null, "bDockRec=" + bDockRec);

        s8ItemsFirstDroneId = dRec.id();
        s8ItemsFirstBoatWaterId = bWaterRec.id();
        s8ItemsFirstBoatDockId = bDockRec.id();

        // Remove redstone blocks cleanly so stations don't re-trigger after test
        level.setBlock(s8DroneRedstonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s8BoatWaterRedstonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(s8BoatDockRedstonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private void tickScenario8ItemsBeforeVehicleArrival(ServerLevel level) {
        if (stageTicks > 250) {
            throw new AssertionError("Scenario 8 items-before-vehicle deliveries timed out after 250 ticks");
        }
        if (LogisticsLevelManager.isTransitActive(level, s8ItemsFirstDroneId)
                || LogisticsLevelManager.isTransitActive(level, s8ItemsFirstBoatWaterId)
                || LogisticsLevelManager.isTransitActive(level, s8ItemsFirstBoatDockId)) {
            return;
        }

        // Verify ALL THREE delivered cleanly into target Storage (27..53) while target Outbound (0..26) stays EMPTY!
        CargoStationBlockEntity dTarget = (CargoStationBlockEntity) level.getBlockEntity(s8DroneTargetPos);
        Map<Item, Integer> dStorage = Lc2QaHelpers.countStationStorage(dTarget);
        qa.checkEquals("s8.items_first_drone_delivered_iron_storage", 32, dStorage.getOrDefault(Items.IRON_INGOT, 0));
        qa.checkEquals("s8.items_first_drone_delivered_gold_storage", 16, dStorage.getOrDefault(Items.GOLD_INGOT, 0));
        qa.checkEquals("s8.items_first_drone_target_outbound_empty", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationOutbound(dTarget)));
        qa.checkEquals("s8.items_first_drone_target_comparator_zero", 0, dTarget.getCargoComparatorSignal());

        SmartPortBlockEntity bWaterTarget = (SmartPortBlockEntity) level.getBlockEntity(s8BoatWaterTargetPos);
        Map<Item, Integer> bwStorage = Lc2QaHelpers.countStationStorage(bWaterTarget);
        qa.checkEquals("s8.items_first_boat_water_delivered_copper_storage", 40, bwStorage.getOrDefault(Items.COPPER_INGOT, 0));
        qa.checkEquals("s8.items_first_boat_water_delivered_coal_storage", 24, bwStorage.getOrDefault(Items.COAL, 0));
        qa.checkEquals("s8.items_first_boat_water_target_outbound_empty", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationOutbound(bWaterTarget)));
        qa.checkEquals("s8.items_first_boat_water_target_comparator_zero", 0, bWaterTarget.getCargoComparatorSignal());

        SmartPortBlockEntity bDockTarget = (SmartPortBlockEntity) level.getBlockEntity(s8BoatDockTargetPos);
        Map<Item, Integer> bdStorage = Lc2QaHelpers.countStationStorage(bDockTarget);
        qa.checkEquals("s8.items_first_boat_dock_delivered_diamond_storage", 18, bdStorage.getOrDefault(Items.DIAMOND, 0));
        qa.checkEquals("s8.items_first_boat_dock_target_outbound_empty", 0,
                Lc2QaHelpers.totalItemCount(Lc2QaHelpers.countStationOutbound(bDockTarget)));

        advance(Stage.S7_WRITE_DISPATCH);
    }

    // -------------------------------------------------------------------------
    // SCENARIO 7 IMPLEMENTATION: COLD-RESTART / NBT PERSISTENCE
    // -------------------------------------------------------------------------

    private void runScenario7WriteDispatch(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 7 (Write Phase): Cold-Restart / NBT Persistence ===");
        // 1) Mid-flight Boat over 80-block canal
        Lc2QaHelpers.buildCanalEastWest(level, -39, 39, WATER_Y, -90);
        SmartPortBlockEntity portA = Lc2QaHelpers.placeSmartPort(level, s7PortAPos, Direction.EAST, "S7-PersistPortA");
        Lc2QaHelpers.placeSmartPort(level, s7PortBPos, Direction.WEST, "S7-PersistPortB");
        level.setBlock(s7PortBChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Lc2QaHelpers.spawnDockedBoatAt(level, portA);

        portA.setTargetPos(s7PortBPos);
        portA.setAutoReturn(false);
        portA.setStoredEu(10000.0);
        List<ItemStack> boatCargo = Lc2QaHelpers.distinctStacks(10, 32); // 320 items
        for (int i = 0; i < boatCargo.size(); i++) {
            portA.setItem(i, boatCargo.get(i));
        }

        // 2) Mid-flight Heavy Drone with autoReturn=true
        CargoStationBlockEntity stA = Lc2QaHelpers.placeCargoStation(level, s7StationAPos, "S7-PersistStA");
        CargoStationBlockEntity stB = Lc2QaHelpers.placeCargoStation(level, s7StationBPos, "S7-PersistStB");
        level.setBlock(s7StationBChestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        stB.setStoredEu(5000.0); // enough to pay Heavy Drone return leg (1600 EU) after reload!

        stA.setTargetPos(s7StationBPos);
        stA.setAutoReturn(true);
        stA.setStoredEu(10000.0);
        stA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack("lc2:rf_chip_1", 1));
        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("lc2:heavy_transport_drone", 1));
        List<ItemStack> droneCargo = Lc2QaHelpers.distinctStacksOffset(12, 8, 32); // 256 items
        for (int i = 0; i < droneCargo.size(); i++) {
            stA.setItem(i, droneCargo.get(i));
        }

        qa.check("s7.write_boat_dispatched", portA.tryDispatch(level, true), "portA=" + portA.getStatusKey());
        qa.check("s7.write_drone_dispatched", stA.tryDispatch(level, true), "stA=" + stA.getStatusKey());
        s7BoatTransitId = findLatestTransitFrom(level, s7PortAPos, VehicleKind.ELECTRIC_CARGO_BOAT).id();
        s7DroneTransitId = findLatestTransitFrom(level, s7StationAPos, VehicleKind.HEAVY_DRONE).id();
    }

    private void runScenario7WriteSaveAndHalt(MinecraftServer server, ServerLevel level) throws Exception {
        LogisticsSavedData data = LogisticsLevelManager.data(level);
        Optional<TransitRecord> boatOpt = data.transitEngine().getTransit(s7BoatTransitId);
        Optional<TransitRecord> droneOpt = data.transitEngine().getTransit(s7DroneTransitId);
        qa.check("s7.write_boat_midflight",
                boatOpt.isPresent() && boatOpt.get().phase() == TransitPhase.EN_ROUTE_OUTBOUND,
                "boat=" + boatOpt.map(r -> r.phase() + "@" + r.currentPos()).orElse("missing"));
        qa.check("s7.write_drone_midflight",
                droneOpt.isPresent() && droneOpt.get().phase() == TransitPhase.EN_ROUTE_OUTBOUND,
                "drone=" + droneOpt.map(r -> r.phase() + "@" + r.currentPos()).orElse("missing"));
        qa.checkEquals("s7.write_boat_cargo_stacks", 10, data.getTransitCargo(s7BoatTransitId).size());
        qa.checkEquals("s7.write_drone_cargo_stacks", 8, data.getTransitCargo(s7DroneTransitId).size());

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("persist.boat.id", s7BoatTransitId.toString());
        extra.put("persist.drone.id", s7DroneTransitId.toString());
        extra.put("persist.boat.expectedItems", Integer.toString(s7ExpectedBoatItems));
        extra.put("persist.drone.expectedItems", Integer.toString(s7ExpectedDroneItems));
        extra.put("persist.boat.savedX", Double.toString(boatOpt.get().currentPos().x()));
        extra.put("persist.drone.savedX", Double.toString(droneOpt.get().currentPos().x()));

        // Force flush world + SavedData cleanly before halting!
        data.setDirty();
        server.saveEverything(false, true, true);
        qa.writePassedReport(WRITE_REPORT, extra);
        Lc2QaMod.LOGGER.info("[LC2-QA] Write phase COMPLETE ({} checks PASSED). Halting server for cold restart...",
                qa.passedCount());
        finished = true;
        advance(Stage.DONE);
        server.halt(false);
    }

    private void runScenario7ReadVerifyReloaded(ServerLevel level) throws Exception {
        Lc2QaMod.LOGGER.info("[LC2-QA] === Scenario 7 (Read Phase): Cold-Restart Verification ===");
        qa.check("s7.read_write_report_exists", Files.isRegularFile(WRITE_REPORT), WRITE_REPORT.toString());
        Properties prev = new Properties();
        try (Reader r = Files.newBufferedReader(WRITE_REPORT)) {
            prev.load(r);
        }
        s7BoatTransitId = UUID.fromString(prev.getProperty("persist.boat.id"));
        s7DroneTransitId = UUID.fromString(prev.getProperty("persist.drone.id"));
        s7ExpectedBoatItems = Integer.parseInt(prev.getProperty("persist.boat.expectedItems"));
        s7ExpectedDroneItems = Integer.parseInt(prev.getProperty("persist.drone.expectedItems"));

        LogisticsSavedData data = LogisticsLevelManager.data(level);
        StationRegistry reg = data.stationRegistry();
        qa.check("s7.read_stations_reloaded",
                reg.get(Lc2Coords.toCore(s7PortAPos)).isPresent()
                        && reg.get(Lc2Coords.toCore(s7PortBPos)).isPresent()
                        && reg.get(Lc2Coords.toCore(s7StationAPos)).isPresent()
                        && reg.get(Lc2Coords.toCore(s7StationBPos)).isPresent(),
                "total reloaded stations=" + reg.snapshot().size());

        Optional<TransitRecord> boatOpt = data.transitEngine().getTransit(s7BoatTransitId);
        Optional<TransitRecord> droneOpt = data.transitEngine().getTransit(s7DroneTransitId);
        qa.check("s7.read_boat_transit_survived_cold_restart",
                boatOpt.isPresent() && boatOpt.get().phase() == TransitPhase.EN_ROUTE_OUTBOUND,
                "boat=" + boatOpt.map(r -> r.phase() + "@" + r.currentPos()).orElse("missing"));
        qa.check("s7.read_drone_transit_survived_cold_restart",
                droneOpt.isPresent() && droneOpt.get().phase() == TransitPhase.EN_ROUTE_OUTBOUND,
                "drone=" + droneOpt.map(r -> r.phase() + "@" + r.currentPos()).orElse("missing"));

        int boatCargoCount = data.getTransitCargo(s7BoatTransitId).stream().mapToInt(ItemStack::getCount).sum();
        int droneCargoCount = data.getTransitCargo(s7DroneTransitId).stream().mapToInt(ItemStack::getCount).sum();
        qa.checkEquals("s7.read_boat_cargo_intact_after_restart", s7ExpectedBoatItems, boatCargoCount);
        qa.checkEquals("s7.read_drone_cargo_intact_after_restart", s7ExpectedDroneItems, droneCargoCount);
    }

    private void tickScenario7ReadWaitCompletion(MinecraftServer server, ServerLevel level) throws Exception {
        if (stageTicks > 650) {
            throw new AssertionError("Scenario 7 persisted transits did not complete within 650 ticks after cold restart");
        }
        if (LogisticsLevelManager.isTransitActive(level, s7BoatTransitId)
                || LogisticsLevelManager.isTransitActive(level, s7DroneTransitId)) {
            return;
        }
        // Verify 100% cargo delivered cleanly into target Storage (27..53) & zero duplicates!
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(s7PortBPos);
        CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(s7StationAPos);
        CargoStationBlockEntity stB = (CargoStationBlockEntity) level.getBlockEntity(s7StationBPos);
        Map<Item, Integer> boatDelivered = Lc2QaHelpers.countStationStorage(portB);
        Map<Item, Integer> droneDelivered = Lc2QaHelpers.countStationStorage(stB);
        qa.checkEquals("s7.read_boat_delivered_all_items", s7ExpectedBoatItems, Lc2QaHelpers.totalItemCount(boatDelivered));
        qa.checkEquals("s7.read_drone_delivered_all_items", s7ExpectedDroneItems, Lc2QaHelpers.totalItemCount(droneDelivered));

        qa.check("s7.read_boat_docked_at_target", portB.findDockedBoat(level) != null, "boat docked cleanly");
        AABB canalBox = new AABB(-45, WATER_Y - 2, -95, 45, WATER_Y + 4, -85);
        int boatCount = level.getEntitiesOfClass(ElectricCargoBoatEntity.class, canalBox).size();
        qa.checkEquals("s7.read_no_duplicate_boats_after_restart", 1, boatCount);

        qa.check("s7.read_heavy_drone_auto_returned_to_origin",
                stA.findReadyDroneBay() >= 0 && stA.getBayDroneKind(stA.findReadyDroneBay()) == VehicleKind.HEAVY_DRONE,
                "stA dockedMask=" + Integer.toBinaryString(stA.getDockedBayMask()));
        qa.checkClose("s7.read_target_paid_heavy_return_1600eu", 3400.0, stB.getStoredEu(), 1e-6);

        qa.writePassedReport(READ_REPORT, Map.of());
        Lc2QaMod.LOGGER.info("[LC2-QA] Read phase COMPLETE ({} checks PASSED). Halting server...", qa.passedCount());
        finished = true;
        advance(Stage.DONE);
        server.halt(false);
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private static TransitRecord findLatestTransitFrom(ServerLevel level, BlockPos origin, VehicleKind kind) {
        BlockPos3i core = Lc2Coords.toCore(origin);
        TransitRecord found = null;
        for (TransitRecord rec : LogisticsLevelManager.activeTransits(level)) {
            if (rec.originPos().equals(core) && rec.vehicleKind() == kind) {
                found = rec;
            }
        }
        return found;
    }

    private static ElectricCargoBoatEntity findBoatByTransit(ServerLevel level, UUID transitId) {
        AABB wide = new AABB(-60, WATER_Y - 5, -120, 400, WATER_Y + 10, 200);
        for (ElectricCargoBoatEntity boat : level.getEntitiesOfClass(ElectricCargoBoatEntity.class, wide)) {
            if (transitId.equals(boat.getTransitId())) {
                return boat;
            }
        }
        return null;
    }
}
