package com.logisticscraft.lc2.qa;

import com.industrialage.ia2.power.EuBuffer;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.world.LevelWaterGrid;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Natural-world automated IC2 mining & refining production-line field test + real graphical
 * client-server orchestrator for Forge 1.19.2 ({@code runFieldTest}).
 */
final class Lc2FieldServerRunner {

    private static final Path READY_MARKER = Path.of("field-server-ready.properties");
    private static final Path SERVER_REPORT = Path.of("field-server-report.properties");
    private static final Path SERVER_FAILURE = Path.of("field-server-failure.txt");

    private static volatile Lc2FieldServerRunner activeInstance;

    private enum Stage {
        INIT,
        SCOUT_NATURAL_SHORELINES,
        BUILD_FACTORIES,
        WAIT_AUTOMATED_PRODUCTION,
        WAIT_CLIENT_JOIN,
        C1_AUDIT_ASSETS,
        C2_SHOT_OUTPOST_HARBOR,
        C3_GUI_SMART_PORT,
        C4_GUI_CARGO_STATION,
        C5_BOAT_BLOCKED_DISPATCH,
        C5_BOAT_BLOCKED_TEST,
        C5_WAIT_BOAT_RETURN_FOR_RIDE,
        C6_BOAT_BOARD_DOCKED,
        C6_BOAT_LAUNCH_AND_RIDE,
        C6_BOAT_RIDE_CAPTURE,
        C6_BOAT_RIDE_ARRIVAL,
        C7_SHOT_CARGO_STATION_DRONES,
        C8_SHOT_REFINING_BASE,
        C9_FINISH_CLIENT,
        DONE
    }

    private record ShoreCandidate(
            BlockPos portPos,
            BlockPos berthPos,
            Direction waterDir,
            int shallowMetalOres,
            int deepMetalOres,
            int avgGroundY
    ) {
    }

    private final Lc2QaAssert qa = new Lc2QaAssert("field_server");
    private final boolean waitForClient;
    private Stage stage = Stage.INIT;
    private int stageTicks = 0;
    private boolean finished = false;

    private final List<ChunkPos> heldTempChunks = new ArrayList<>();

    // Scouted natural world coordinates
    private int waterY = 62;
    private ShoreCandidate shoreA;
    private ShoreCandidate shoreB;
    private WaterPathResult scoutedWaterRoute;
    private int waterwayPlantCount = 0;

    // Outpost A coordinates
    private BlockPos portAPos;
    private BlockPos portACompPos;
    private BlockPos minerBox1Pos;
    private BlockPos advMinerPos;
    private BlockPos portACablePos;
    private BlockPos stationAPos;
    private BlockPos stationACompPos;
    private BlockPos minerBox2Pos;
    private BlockPos minerPos;
    private BlockPos stationACablePos;

    // Refining Base B coordinates
    private BlockPos portBPos;
    private BlockPos stationBPos;
    private BlockPos receivingChestPos;
    private BlockPos maceratorPos;
    private BlockPos crushedBufferPos;
    private BlockPos furnacePos;
    private BlockPos steelBoxPos;
    private BlockPos baseCablePos;

    // Natural underground ore snapshot beneath Outpost A to prove genuine mining
    private final Set<BlockPos> initialAdvMinerOres = new HashSet<>();
    private final Set<BlockPos> initialClassicMinerOres = new HashSet<>();

    // Phase 2 live production telemetry
    private boolean observedAdvMinerMined = false;
    private boolean observedClassicMinerMined = false;
    private boolean observedPortAComparatorFired = false;
    private boolean observedStationAComparatorFired = false;
    private boolean observedBoatOutbound = false;
    private boolean observedBoatAutoReturned = false;
    private boolean observedDroneOutbound = false;
    private boolean observedDroneAutoReturned = false;
    private boolean observedCrushedOre = false;
    private boolean observedFinishedIngots = false;
    private int totalMinedNaturalOreBlocks = 0;
    private int totalFinishedIngots = 0;
    private int boatDispatchesSeen = 0;
    private int droneDispatchesSeen = 0;
    private final Set<UUID> seenBoatTransits = new HashSet<>();
    private final Set<UUID> seenDroneTransits = new HashSet<>();

    // Phase 3 client-server coordination
    private int nextStepId = 1;
    private int awaitingStepId = -1;
    private volatile Lc2FieldNetwork.C2SFieldAck lastAck = null;
    private UUID blockedBoatTransitId = null;
    private UUID riddenBoatTransitId = null;
    private int riddenBoatEntityId = -1;
    private Vec3 rideStartPos = null;
    private boolean rideMidCaptured = false;
    private boolean rideCameraSet = false;

    Lc2FieldServerRunner() {
        this.waitForClient = Boolean.parseBoolean(System.getProperty("lc2.field.waitForClient", "true"));
        activeInstance = this;
    }

    static void onClientAck(Lc2FieldNetwork.C2SFieldAck ack) {
        Lc2FieldServerRunner inst = activeInstance;
        if (inst != null) {
            Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Received client ACK step={} ok={} summary={}",
                    ack.stepId(), ack.success(), ack.summary());
            inst.lastAck = ack;
        }
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
        level.resetEmptyTime();
        // Keep bright noon sunlight & clear weather for consistent production & crisp screenshots
        if (level.getGameTime() % 40 == 0) {
            level.setDayTime(6000L);
            level.setWeatherParameters(100000, 0, false, false);
        }

        try {
            step(server, level);
        } catch (Throwable t) {
            finished = true;
            Lc2QaMod.LOGGER.error("[LC2-FIELD-SERVER] Failure in stage {}", stage, t);
            qa.writeFailureReport(SERVER_REPORT, SERVER_FAILURE, t);
            server.halt(false);
        }
    }

    private void advance(Stage next) {
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Stage {} -> {}", this.stage, next);
        this.stage = next;
        this.stageTicks = 0;
        this.awaitingStepId = -1;
        this.lastAck = null;
    }

    private void step(MinecraftServer server, ServerLevel level) throws Exception {
        stageTicks++;
        switch (stage) {
            case INIT -> {
                if (stageTicks < 5) {
                    return;
                }
                level.setDayTime(6000L);
                level.setWeatherParameters(100000, 0, false, false);
                qa.metric("seed", level.getSeed());
                advance(Stage.SCOUT_NATURAL_SHORELINES);
            }

            case SCOUT_NATURAL_SHORELINES -> {
                scoutNaturalWorld(level);
                advance(Stage.BUILD_FACTORIES);
            }

            case BUILD_FACTORIES -> {
                buildOutpostAndRefinery(level);
                advance(Stage.WAIT_AUTOMATED_PRODUCTION);
            }

            case WAIT_AUTOMATED_PRODUCTION -> {
                tickAutomatedProduction(server, level);
            }

            case WAIT_CLIENT_JOIN -> {
                ServerPlayer player = findConnectedPlayer(server);
                if (player != null && stageTicks >= 20) {
                    preparePlayerForFieldTour(level, player);
                    qa.check("p3.client_connected", true, "player=" + player.getGameProfile().getName());
                    advance(Stage.C1_AUDIT_ASSETS);
                } else if (stageTicks > 2400) {
                    throw new AssertionError("Timed out waiting for real Forge 1.19.2 graphical client to join");
                }
            }

            case C1_AUDIT_ASSETS -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    sendDirective(player, "AUDIT_ASSETS", BlockPos.ZERO, -1, "");
                } else if (consumeAck("p3.client_asset_audit")) {
                    advance(Stage.C2_SHOT_OUTPOST_HARBOR);
                }
            }

            case C2_SHOT_OUTPOST_HARBOR -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    // Phase 2 already verified autonomous Comparator-triggered dispatches; decouple the return
                    // repeater right at portA.relative(lA) so the docked boat stays calmly parked for Phase 3
                    // harbor screenshots, SmartPortScreen GUI tests, and controlled client riding tests!
                    Direction f = shoreA.waterDir();
                    Direction l = f.getCounterClockWise();
                    level.setBlock(portAPos.relative(l), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                    if (portA != null) {
                        if (portA.findDockedBoat(level) == null) {
                            Lc2QaHelpers.spawnDockedBoatAt(level, portA);
                        }
                        portA.setStatusKey("status.trera.ready");
                    }
                    // Scenic elevated angle from over the natural water looking back at Outpost Harbor A, Boat, Cables & Advanced Miner!
                    Direction r = f.getClockWise();
                    Vec3 target = Vec3.atCenterOf(portAPos).add(r.getStepX() * 1.0, 0.2, r.getStepZ() * 1.0);
                    Vec3 cam = Vec3.atCenterOf(portAPos)
                            .add(f.getStepX() * 8.0 + r.getStepX() * 2.5, 4.5, f.getStepZ() * 8.0 + r.getStepZ() * 2.5);
                    teleportLook(level, player, cam, target);
                    sendDirective(player, "CAPTURE_SCREENSHOT", BlockPos.ZERO, -1, "01_outpost_harbor_boat_miner.png");
                } else if (consumeAck("p3.shot_outpost_harbor")) {
                    advance(Stage.C3_GUI_SMART_PORT);
                }
            }

            case C3_GUI_SMART_PORT -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                    if (portA != null) {
                        if (portA.findDockedBoat(level) == null) {
                            Lc2QaHelpers.spawnDockedBoatAt(level, portA);
                        }
                        // Populate distinct Outbound (0..2) & Storage (27..28) stacks for live 2-zone GUI + Swap test!
                        portA.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.RAW_COPPER, 24));
                        portA.setItem(SmartPortBlockEntity.SLOT_OUTBOUND_START + 1, new ItemStack(Items.RAW_IRON, 16));
                        portA.setItem(SmartPortBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.COPPER_INGOT, 18));
                        portA.setItem(SmartPortBlockEntity.SLOT_STORAGE_START + 1, new ItemStack(Items.IRON_INGOT, 12));
                        portA.setStatusKey("status.trera.ready");
                    }
                    Direction f = shoreA.waterDir();
                    Vec3 cam = Vec3.atCenterOf(portAPos).add(f.getStepX() * 2.2, 0.6, f.getStepZ() * 2.2);
                    teleportLook(level, player, cam, Vec3.atCenterOf(portAPos));
                    sendDirective(player, "OPEN_AND_TEST_SMART_PORT", portAPos, -1, "02_smart_port_screen.png");
                } else if (consumeAck("p3.smart_port_screen_interactive")) {
                    SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                    qa.check("p3.server_smart_port_restored", portA != null && "Outpost Harbor A".equals(portA.getStationName()),
                            "name=" + (portA == null ? "null" : portA.getStationName()));
                    qa.check("p3.server_smart_port_zones_restored_after_double_swap",
                            portA != null && portA.getItem(SmartPortBlockEntity.SLOT_OUTBOUND_START).is(Items.RAW_COPPER)
                                    && portA.getItem(SmartPortBlockEntity.SLOT_STORAGE_START).is(Items.COPPER_INGOT),
                            "slot0=" + (portA == null ? "null" : portA.getItem(0))
                                    + ", slot27=" + (portA == null ? "null" : portA.getItem(27)));
                    advance(Stage.C4_GUI_CARGO_STATION);
                }
            }

            case C4_GUI_CARGO_STATION -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    // Decouple stationA's comparator return repeater so drones stay parked during live GUI & Swap test!
                    Direction f = shoreA.waterDir();
                    Direction r = f.getClockWise();
                    level.setBlock(stationAPos.below().relative(r), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(stationAPos);
                    if (stA != null) {
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("trera:transport_drone", 1));
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 1, Lc2QaHelpers.itemStack("trera:heavy_transport_drone", 1));
                        stA.setItem(CargoStationBlockEntity.SLOT_OUTBOUND_START, new ItemStack(Items.RAW_IRON, 20));
                        stA.setItem(CargoStationBlockEntity.SLOT_OUTBOUND_START + 1, new ItemStack(Items.RAW_GOLD, 12));
                        stA.setItem(CargoStationBlockEntity.SLOT_STORAGE_START, new ItemStack(Items.IRON_INGOT, 14));
                        stA.setItem(CargoStationBlockEntity.SLOT_STORAGE_START + 1, new ItemStack(Items.GOLD_INGOT, 8));
                        stA.setStatusKey("status.trera.ready");
                    }
                    Vec3 cam = Vec3.atCenterOf(stationAPos).add(f.getStepX() * 2.2, 0.6, f.getStepZ() * 2.2);
                    teleportLook(level, player, cam, Vec3.atCenterOf(stationAPos));
                    sendDirective(player, "OPEN_AND_TEST_CARGO_STATION", stationAPos, -1, "03_cargo_station_screen.png");
                } else if (consumeAck("p3.cargo_station_screen_interactive")) {
                    CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(stationAPos);
                    qa.check("p3.server_cargo_station_restored", stA != null && "Outpost Airfield A".equals(stA.getStationName()),
                            "name=" + (stA == null ? "null" : stA.getStationName()));
                    qa.check("p3.server_cargo_station_zones_restored_after_double_swap",
                            stA != null && stA.getItem(CargoStationBlockEntity.SLOT_OUTBOUND_START).is(Items.RAW_IRON)
                                    && stA.getItem(CargoStationBlockEntity.SLOT_STORAGE_START).is(Items.IRON_INGOT),
                            "slot0=" + (stA == null ? "null" : stA.getItem(0))
                                    + ", slot27=" + (stA == null ? "null" : stA.getItem(27)));
                    advance(Stage.C5_BOAT_BLOCKED_DISPATCH);
                }
            }

            case C5_BOAT_BLOCKED_DISPATCH -> {
                ServerPlayer player = requirePlayer(server);
                SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                qa.check("p3.boat_test_portA_present", portA != null, "portA=" + portAPos);
                if (portA.getStoredEu() < 8000.0) {
                    portA.setStoredEu(12000.0);
                }
                ElectricCargoBoatEntity boat = portA.findDockedBoat(level);
                if (boat == null) {
                    boat = Lc2QaHelpers.spawnDockedBoatAt(level, portA);
                }
                portA.setAutoReturn(true);
                portA.setTargetPos(portBPos);
                boolean launched = portA.tryDispatch(level, true);
                qa.check("p3.boat_blocked_test_dispatched", launched, "status=" + portA.getStatusKey());
                blockedBoatTransitId = boat.getTransitId();
                qa.check("p3.boat_blocked_transit_id", blockedBoatTransitId != null, "transitId=" + blockedBoatTransitId);
                advance(Stage.C5_BOAT_BLOCKED_TEST);
            }

            case C5_BOAT_BLOCKED_TEST -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    if (stageTicks < 6) {
                        return;
                    }
                    ElectricCargoBoatEntity boat = findBoatByTransit(level, blockedBoatTransitId);
                    if (boat == null) {
                        if (stageTicks > 100) {
                            throw new AssertionError("Moving boat disappeared during blocked-boarding test");
                        }
                        return;
                    }
                    Vec3 bPos = boat.position();
                    teleportLook(level, player, bPos.add(0.0, 1.5, 2.0), bPos.add(0.0, 0.3, 0.0));
                    sendDirective(player, "TEST_BLOCKED_BOAT_BOARDING", BlockPos.ZERO, boat.getId(), "");
                } else if (consumeAck("p3.boat_active_transit_boarding_blocked")) {
                    qa.check("p3.server_player_not_mounted_moving_boat", player.getVehicle() == null,
                            "vehicle=" + player.getVehicle());
                    advance(Stage.C5_WAIT_BOAT_RETURN_FOR_RIDE);
                }
            }

            case C5_WAIT_BOAT_RETURN_FOR_RIDE -> {
                // Wait for the previous boat transit to finish OR dock cleanly at Outpost Harbor A
                if (!LogisticsLevelManager.isTransitActive(level, blockedBoatTransitId)) {
                    SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                    ElectricCargoBoatEntity docked = portA != null ? portA.findDockedBoat(level) : null;
                    if (docked != null && !docked.isAutonomousTransit()) {
                        advance(Stage.C6_BOAT_BOARD_DOCKED);
                        return;
                    }
                }
                if (stageTicks > 900) {
                    throw new AssertionError("Timed out waiting for boat to return to Outpost Harbor A for riding test");
                }
            }

            case C6_BOAT_BOARD_DOCKED -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                    ElectricCargoBoatEntity boat = portA != null ? portA.findDockedBoat(level) : null;
                    qa.check("p3.docked_boat_ready_for_ride", boat != null, "portA=" + portAPos);
                    riddenBoatEntityId = boat.getId();
                    Vec3 bPos = boat.position();
                    teleportLook(level, player, bPos.add(0.0, 0.8, 1.8), bPos.add(0.0, 0.3, 0.0));
                    sendDirective(player, "BOARD_DOCKED_BOAT", BlockPos.ZERO, boat.getId(), "");
                } else if (consumeAck("p3.client_boarded_docked_boat")) {
                    qa.check("p3.server_player_seated_prelaunch",
                            player.getVehicle() instanceof ElectricCargoBoatEntity b && b.getId() == riddenBoatEntityId,
                            "vehicle=" + player.getVehicle());
                    advance(Stage.C6_BOAT_LAUNCH_AND_RIDE);
                }
            }

            case C6_BOAT_LAUNCH_AND_RIDE -> {
                ServerPlayer player = requirePlayer(server);
                SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
                ElectricCargoBoatEntity boat = (ElectricCargoBoatEntity) player.getVehicle();
                qa.check("p3.still_seated_at_launch", boat != null, "vehicle=" + player.getVehicle());
                if (portA.getStoredEu() < 8000.0) {
                    portA.setStoredEu(12000.0);
                }
                portA.setAutoReturn(false); // One-way scenic ferry to Base B so player arrives & steps onto Base B!
                portA.setTargetPos(portBPos);
                boolean launched = portA.tryDispatch(level, true);
                qa.check("p3.player_ferry_boat_launched", launched, "status=" + portA.getStatusKey());
                riddenBoatTransitId = boat.getTransitId();
                qa.check("p3.ridden_boat_actually_dispatched",
                        boat.isAutonomousTransit() && riddenBoatTransitId != null,
                        "boatId=" + boat.getId() + ", autonomous=" + boat.isAutonomousTransit() + ", transitId=" + riddenBoatTransitId);
                rideStartPos = player.position();
                rideMidCaptured = false;
                rideCameraSet = false;
                advance(Stage.C6_BOAT_RIDE_CAPTURE);
            }

            case C6_BOAT_RIDE_CAPTURE -> {
                ServerPlayer player = requirePlayer(server);
                if (!rideCameraSet && stageTicks >= 10) {
                    if (awaitingStepId < 0) {
                        sendDirective(player, "SET_CAMERA", BlockPos.ZERO, -1, "THIRD_PERSON_BACK");
                    } else if (consumeAck("p3.boat_ride_camera_third_person")) {
                        rideCameraSet = true;
                    }
                    return;
                }
                if (rideCameraSet && !rideMidCaptured) {
                    double traveled = player.position().distanceTo(rideStartPos);
                    if (traveled >= 18.0 || stageTicks >= 70) {
                        if (awaitingStepId < 0) {
                            qa.check("p3.server_player_riding_mid_river",
                                    player.getVehicle() instanceof ElectricCargoBoatEntity b && b.isAutonomousTransit(),
                                    "traveled=" + String.format("%.1f", traveled) + "m, pos=" + player.blockPosition());
                            sendDirective(player, "CAPTURE_SCREENSHOT", BlockPos.ZERO, -1,
                                    "04_player_riding_boat_natural_water.png");
                        } else if (consumeAck("p3.shot_player_riding_boat")) {
                            rideMidCaptured = true;
                            advance(Stage.C6_BOAT_RIDE_ARRIVAL);
                        }
                    }
                }
                if (stageTicks > 600) {
                    throw new AssertionError("Timed out capturing mid-voyage boat riding screenshot");
                }
            }

            case C6_BOAT_RIDE_ARRIVAL -> {
                ServerPlayer player = requirePlayer(server);
                if (!LogisticsLevelManager.isTransitActive(level, riddenBoatTransitId)) {
                    double totalDist = player.position().distanceTo(rideStartPos);
                    double distToBaseB = player.position().distanceTo(Vec3.atCenterOf(shoreB.berthPos()));
                    qa.check("p3.player_arrived_at_baseB_by_boat",
                            totalDist >= 40.0 && distToBaseB <= 12.0,
                            String.format("totalDist=%.1fm, distToBaseB=%.1fm, pos=%s",
                                    totalDist, distToBaseB, player.blockPosition()));
                    qa.metric("p3.boatRideDistanceBlocks", String.format("%.1f", totalDist));
                    if (player.isPassenger()) {
                        player.stopRiding();
                    }
                    if (awaitingStepId < 0) {
                        sendDirective(player, "SET_CAMERA", BlockPos.ZERO, -1, "FIRST_PERSON");
                    } else if (consumeAck("p3.camera_restored_first_person")) {
                        advance(Stage.C7_SHOT_CARGO_STATION_DRONES);
                    }
                    return;
                }
                if (stageTicks > 900) {
                    throw new AssertionError("Timed out waiting for ridden boat to arrive at Base B");
                }
            }

            case C7_SHOT_CARGO_STATION_DRONES -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    // Ensure Outpost Cargo Station A has parked roof drones AND launch 1 Standard + 1 Heavy Drone
                    // so BOTH parked roof drones and live airborne spinning-rotor drones appear in frame!
                    CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(stationAPos);
                    if (stA != null) {
                        if (stA.getStoredEu() < 8000.0) {
                            stA.setStoredEu(12000.0);
                        }
                        // Put 2 extra drones in bays 2 & 3 so 2 launch and 2 stay parked on the roof!
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("trera:transport_drone", 1));
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 1, Lc2QaHelpers.itemStack("trera:heavy_transport_drone", 1));
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 2, Lc2QaHelpers.itemStack("trera:transport_drone", 1));
                        stA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 3, Lc2QaHelpers.itemStack("trera:heavy_transport_drone", 1));
                        stA.setAutoReturn(true);
                        stA.setTargetPos(stationBPos);
                        stA.tryDispatch(level, true);
                        stA.tryDispatch(level, true);
                    }
                    Direction f = shoreA.waterDir();
                    Direction r = f.getClockWise();
                    // Frame Cargo Station A (bright parked roof drones) + IC2 Cable + Classic Miner + both climbing drones!
                    Vec3 target = Vec3.atCenterOf(stationAPos).add(0.0, 2.2, 0.0);
                    Vec3 cam = Vec3.atCenterOf(stationAPos)
                            .add(f.getStepX() * 7.5 + r.getStepX() * 3.8, 3.8, f.getStepZ() * 7.5 + r.getStepZ() * 3.8);
                    teleportLook(level, player, cam, target);
                    sendDirective(player, "CAPTURE_SCREENSHOT", BlockPos.ZERO, -1, "05_cargo_station_drones.png");
                } else if (consumeAck("p3.shot_cargo_station_drones")) {
                    advance(Stage.C8_SHOT_REFINING_BASE);
                }
            }

            case C8_SHOT_REFINING_BASE -> {
                ServerPlayer player = requirePlayer(server);
                if (awaitingStepId < 0) {
                    // Scenic elevated angle showcasing Base Smart Port B, Base Cargo Station B, IC2 Cables/BatBox/Generators,
                    // Receiving Chest, Macerator, Crushed Buffer, Electric Furnace, and Final Steel Storage Box!
                    Direction f = shoreB.waterDir();
                    Direction r = f.getClockWise();
                    Vec3 target = Vec3.atCenterOf(maceratorPos).add(0.0, 0.3, 0.0);
                    Vec3 cam = Vec3.atCenterOf(maceratorPos)
                            .add(f.getStepX() * 6.5 + r.getStepX() * 5.5, 5.2, f.getStepZ() * 6.5 + r.getStepZ() * 5.5);
                    teleportLook(level, player, cam, target);
                    sendDirective(player, "CAPTURE_SCREENSHOT", BlockPos.ZERO, -1, "06_refining_base_ic2_line.png");
                } else if (consumeAck("p3.shot_refining_base")) {
                    advance(Stage.C9_FINISH_CLIENT);
                }
            }

            case C9_FINISH_CLIENT -> {
                ServerPlayer player = findConnectedPlayer(server);
                if (awaitingStepId < 0 && player != null) {
                    sendDirective(player, "FINISH_AND_DISCONNECT", BlockPos.ZERO, -1, "");
                } else if (awaitingStepId > 0 && consumeAck("p3.client_clean_disconnect")) {
                    writeFinalServerReport(level);
                    advance(Stage.DONE);
                    Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] ALL Phase 2 + Phase 3 checks PASSED ({} checks). Halting server...",
                            qa.passedCount());
                    server.halt(false);
                }
            }

            case DONE -> {
                finished = true;
            }
        }
    }

    // =========================================================================
    // PHASE 2A: SCOUT GENUINE NATURAL SHORELINES & UNDERGROUND ORES
    // =========================================================================

    private void scoutNaturalWorld(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] === Scouting natural world (seed={}) ===", level.getSeed());
        this.waterY = level.getSeaLevel() - 1;
        qa.metric("waterY", this.waterY);

        // Load natural chunks in [-10..10] chunk ring (320x320 blocks) around spawn
        int radiusChunks = 10;
        for (int cx = -radiusChunks; cx <= radiusChunks; cx++) {
            for (int cz = -radiusChunks; cz <= radiusChunks; cz++) {
                level.getChunk(cx, cz);
            }
        }

        LevelWaterGrid grid = new LevelWaterGrid(level, false, 0);

        // Verify Phase 1 LevelWaterGrid fix on a genuine deep natural water column!
        boolean verifiedSubmergedRejection = false;
        outerWaterCheck:
        for (int x = -140; x <= 140; x += 2) {
            for (int z = -140; z <= 140; z += 2) {
                if (grid.isBoatPassable(x, waterY, z) && grid.isNavigableWater(x, waterY - 1, z)) {
                    qa.check("p1.water_grid_surface_passable", grid.isBoatPassable(x, waterY, z),
                            "surface=(" + x + "," + waterY + "," + z + ")");
                    qa.check("p1.water_grid_submerged_rejected", !grid.isBoatPassable(x, waterY - 1, z),
                            "submerged=(" + x + "," + (waterY - 1) + "," + z + ")");
                    verifiedSubmergedRejection = true;
                    break outerWaterCheck;
                }
            }
        }
        qa.check("p1.natural_deep_water_found", verifiedSubmergedRejection, "checked natural water column at Y=" + waterY);

        // Scan natural shorelines on a 4-block stride for speed & diversity
        List<ShoreCandidate> candidates = new ArrayList<>();
        int scanRange = radiusChunks * 16 - 20;
        for (int x = -scanRange; x <= scanRange; x += 4) {
            for (int z = -scanRange; z <= scanRange; z += 4) {
                BlockPos groundPos = new BlockPos(x, waterY, z);
                BlockState groundState = level.getBlockState(groundPos);
                if (!groundState.getFluidState().isEmpty()
                        || groundState.getCollisionShape(level, groundPos).isEmpty()
                        || groundState.is(Blocks.ICE)
                        || groundState.is(Blocks.PACKED_ICE)
                        || groundState.is(Blocks.BLUE_ICE)) {
                    continue;
                }
                BlockPos portPos = groundPos.above();
                if (!level.getBlockState(portPos).getCollisionShape(level, portPos).isEmpty()
                        || !level.getBlockState(portPos.above()).getCollisionShape(level, portPos.above()).isEmpty()) {
                    continue;
                }
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos berth = groundPos.relative(dir);
                    if (!grid.isBoatPassable(berth.getX(), berth.getY(), berth.getZ())) {
                        continue;
                    }
                    // Require real open water (at least 3 adjacent navigable surface blocks around berth)
                    int openNeighbors = 0;
                    for (Direction nd : Direction.Plane.HORIZONTAL) {
                        BlockPos nb = berth.relative(nd);
                        if (grid.isBoatPassable(nb.getX(), nb.getY(), nb.getZ())) {
                            openNeighbors++;
                        }
                    }
                    BlockPos front2 = berth.relative(dir);
                    if (openNeighbors < 3 || !grid.isBoatPassable(front2.getX(), front2.getY(), front2.getZ())) {
                        continue;
                    }
                    // Check natural land behind shoreline (no underwater cliffs or sky-high mountains)
                    Direction back = dir.getOpposite();
                    int sumY = 0;
                    int samples = 0;
                    int waterBehind = 0;
                    for (int db = 2; db <= 8; db += 2) {
                        for (int ds = -4; ds <= 4; ds += 2) {
                            BlockPos p = groundPos.relative(back, db).relative(dir.getClockWise(), ds);
                            int hy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                            if (hy <= waterY + 1 && !level.getBlockState(new BlockPos(p.getX(), waterY, p.getZ())).getFluidState().isEmpty()) {
                                waterBehind++;
                            }
                            sumY += hy;
                            samples++;
                        }
                    }
                    int avgY = sumY / Math.max(1, samples);
                    if (waterBehind > 4 || avgY < waterY + 1 || avgY > waterY + 12) {
                        continue;
                    }
                    // Score real natural metal ores directly underneath the outpost site!
                    BlockPos oreCenter = groundPos.relative(back, 3);
                    int shallowMetal = countNaturalMetalOres(level, oreCenter, 24, waterY - 2, 4);
                    int deepMetal = countNaturalMetalOres(level, oreCenter, -48, 23, 6);
                    candidates.add(new ShoreCandidate(portPos, berth, dir, shallowMetal, deepMetal, avgY));
                    break;
                }
            }
        }

        qa.check("p2.natural_shoreline_candidates_found", candidates.size() >= 2,
                "candidates=" + candidates.size());
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Found {} natural shoreline candidates", candidates.size());

        // Sort Shore A candidates by shallow + deep natural metal ore richness so real miners hit ores quickly!
        List<ShoreCandidate> sortedA = new ArrayList<>(candidates);
        sortedA.sort(Comparator.comparingInt((ShoreCandidate c) -> c.shallowMetalOres() * 4 + c.deepMetalOres()).reversed());

        // Pair top Shore A candidates with Shore B candidates 55..145 blocks away on the SAME continuous natural water body
        ShoreCandidate bestA = null;
        ShoreCandidate bestB = null;
        WaterPathResult bestRoute = null;
        int bestPlants = -1;
        double bestScore = -1.0;

        int maxA = Math.min(sortedA.size(), 30);
        for (int i = 0; i < maxA; i++) {
            ShoreCandidate a = sortedA.get(i);
            if (a.shallowMetalOres() + a.deepMetalOres() < 8) {
                continue;
            }
            int checkedB = 0;
            for (ShoreCandidate b : candidates) {
                double dist = Math.hypot(a.berthPos().getX() - b.berthPos().getX(),
                        a.berthPos().getZ() - b.berthPos().getZ());
                if (dist < 58.0 || dist > 142.0) {
                    continue;
                }
                if (++checkedB > 18) {
                    break;
                }
                WaterPathResult route = WaterPathfinder.findPath(
                        grid,
                        Lc2Coords.toCore(a.berthPos()),
                        Lc2Coords.toCore(b.berthPos()),
                        24_000,
                        false
                );
                if (!route.connected()) {
                    continue;
                }
                int plants = countWaterPlantsAlongPath(level, route.gridPath());
                int bends = Math.max(0, route.waypoints().size() - 2);
                double score = a.shallowMetalOres() * 8.0 + a.deepMetalOres() * 1.5
                        + Math.min(plants, 40) * 3.0 + Math.min(bends, 8) * 10.0;
                if (score > bestScore) {
                    bestScore = score;
                    bestA = a;
                    bestB = b;
                    bestRoute = route;
                    bestPlants = plants;
                }
            }
            if (bestA != null && bestPlants > 0 && bestA.shallowMetalOres() >= 6) {
                break;
            }
        }

        qa.check("p2.natural_connected_shores_found", bestA != null && bestB != null && bestRoute != null,
                bestA == null ? "none found" : ("A=" + bestA.portPos() + " -> B=" + bestB.portPos()
                        + ", waypoints=" + bestRoute.waypoints().size() + ", waterPlants=" + bestPlants));

        this.shoreA = bestA;
        this.shoreB = bestB;
        this.scoutedWaterRoute = bestRoute;
        this.waterwayPlantCount = bestPlants;

        double straightDist = Math.hypot(shoreA.berthPos().getX() - shoreB.berthPos().getX(),
                shoreA.berthPos().getZ() - shoreB.berthPos().getZ());
        String biomeA = level.getBiome(shoreA.portPos()).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        String biomeB = level.getBiome(shoreB.portPos()).unwrapKey().map(k -> k.location().toString()).orElse("unknown");

        qa.check("p2.waterway_distance_in_50_150", straightDist >= 50.0 && straightDist <= 150.0,
                String.format("straightDist=%.1f, rawBlocks=%d, waypoints=%d",
                        straightDist, bestRoute.gridPath().size(), bestRoute.waypoints().size()));

        qa.metric("shoreA.pos", shoreA.portPos().toShortString());
        qa.metric("shoreA.berth", shoreA.berthPos().toShortString());
        qa.metric("shoreA.facing", shoreA.waterDir().getName());
        qa.metric("shoreA.biome", biomeA);
        qa.metric("shoreA.naturalShallowMetalOres", shoreA.shallowMetalOres());
        qa.metric("shoreA.naturalDeepMetalOres", shoreA.deepMetalOres());
        qa.metric("shoreB.pos", shoreB.portPos().toShortString());
        qa.metric("shoreB.berth", shoreB.berthPos().toShortString());
        qa.metric("shoreB.facing", shoreB.waterDir().getName());
        qa.metric("shoreB.biome", biomeB);
        qa.metric("waterway.straightDistance", String.format("%.1f", straightDist));
        qa.metric("waterway.rawWaterBlocks", bestRoute.gridPath().size());
        qa.metric("waterway.waypoints", bestRoute.waypoints().size());
        qa.metric("waterway.naturalSeagrassKelp", waterwayPlantCount);

        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] LOCKED NATURAL SITES (seed={}): ShoreA={} ({}, ores={}+{}) -> ShoreB={} ({}), dist={}m, waypoints={}, seagrass/kelp={}",
                level.getSeed(), shoreA.portPos(), biomeA, shoreA.shallowMetalOres(), shoreA.deepMetalOres(),
                shoreB.portPos(), biomeB, String.format("%.1f", straightDist),
                bestRoute.waypoints().size(), waterwayPlantCount);
    }

    private static int countNaturalMetalOres(ServerLevel level, BlockPos center, int minY, int maxY, int radius) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    if (Lc2FieldHelpers.NATURAL_METAL_ORE_BLOCKS.contains(Lc2FieldHelpers.blockId(level.getBlockState(m).getBlock()))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int countWaterPlantsAlongPath(ServerLevel level, List<BlockPos3i> rawBlocks) {
        Set<Long> visited = new HashSet<>();
        int plants = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < rawBlocks.size(); i += 2) {
            BlockPos3i b = rawBlocks.get(i);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    long colKey = (((long) (b.x() + dx)) << 32) ^ ((b.z() + dz) & 0xffffffffL);
                    if (!visited.add(colKey)) {
                        continue;
                    }
                    for (int y = b.y(); y >= b.y() - 10; y--) {
                        m.set(b.x() + dx, y, b.z() + dz);
                        BlockState st = level.getBlockState(m);
                        if (Lc2FieldHelpers.isNaturalWaterPlant(st)) {
                            plants++;
                            break;
                        }
                    }
                }
            }
        }
        return plants;
    }

    // =========================================================================
    // PHASE 2B: BUILD MINING OUTPOST A & MAIN REFINING BASE B
    // =========================================================================

    private void buildOutpostAndRefinery(ServerLevel level) {
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] === Building Mining Outpost A & Main Refining Base B ===");
        FakePlayer setupPlayer = FakePlayerFactory.getMinecraft(level);

        // Hold temporary chunks around both sites during construction (and keep waterway clear)
        holdAreaTemp(level, shoreA.portPos(), 2);
        holdAreaTemp(level, shoreB.portPos(), 2);

        Block cableBlock = Lc2QaHelpers.requireBlock("mhera:insulated_copper_cable");
        Block genBlock = Lc2QaHelpers.requireBlock("mhera:generator");
        Block batboxBlock = Lc2QaHelpers.requireBlock("mhera:batbox");
        Block advMinerBlock = Lc2QaHelpers.requireBlock("mhera:advanced_miner");
        Block minerBlock = Lc2QaHelpers.requireBlock("mhera:miner");
        Block ironBoxBlock = Lc2QaHelpers.requireBlock("mhera:iron_storage_box");
        Block steelBoxBlock = Lc2QaHelpers.requireBlock("mhera:steel_storage_box");
        Block maceratorBlock = Lc2QaHelpers.requireBlock("mhera:macerator");
        Block furnaceBlock = Lc2QaHelpers.requireBlock("mhera:electric_furnace");

        // ---------------------------------------------------------------------
        // 1. MINING OUTPOST A (Natural Shore A)
        // ---------------------------------------------------------------------
        Direction fA = shoreA.waterDir();
        Direction bA = fA.getOpposite();
        Direction rA = fA.getClockWise();
        Direction lA = fA.getCounterClockWise();

        // Compact natural dock pad right at portAPos (preserve surrounding natural banks & trees!)
        this.portAPos = shoreA.portPos();
        prepareCleanWorkPad(level, portAPos, fA, 4, 4, 5);

        // 100% above-waterline flat Comparator loop at Y = waterY + 1 on backDir + leftDir!
        this.portACompPos = Lc2FieldHelpers.buildFlatAboveWaterComparatorLoop(level, portAPos, fA);

        // Iron Storage Box A1 directly under Outpost Smart Port A (Y = waterY, solid block immune to water!),
        // fed by 2 Hoppers from ic2:advanced_miner at rightA * 2 (ZERO redstone near the hoppers!).
        this.minerBox1Pos = portAPos.below();
        BlockPos hopper1Pos = minerBox1Pos.relative(rA);
        BlockPos hopper2Pos = hopper1Pos.relative(rA);
        this.advMinerPos = portAPos.relative(rA, 2);
        level.setBlock(minerBox1Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hopper1Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hopper2Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(minerBox1Pos, ironBoxBlock.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(
                hopper1Pos,
                Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING, lA),
                Block.UPDATE_ALL
        );
        level.setBlock(
                hopper2Pos,
                Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING, lA),
                Block.UPDATE_ALL
        );

        // Place IC2 cable bus BEFORE portA & advMiner (with all arms FALSE) so placing portA & advMiner
        // triggers real CableBlock.updateShape -> CableBlockMixin visual connections!
        // Single visible cable at portAPos.relative(rA) powers BOTH Outpost Smart Port A (lA) AND Advanced Miner (rA)!
        this.portACablePos = portAPos.relative(rA);
        BlockPos cableA1Back = portACablePos.relative(bA);
        BlockPos batboxA1Pos = cableA1Back.relative(bA);
        level.setBlock(cableA1Back.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(batboxA1Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(portACablePos, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(cableA1Back, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(batboxA1Pos, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, fA), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, batboxA1Pos, genBlock, bA, rA, Direction.UP);

        // Also place a second BatBox + Generators feeding cableA1Back from rightA so Smart Port A + Advanced Miner
        // both receive plenty of real IC2 LV EU simultaneously!
        BlockPos batboxA1Extra = cableA1Back.relative(rA);
        level.setBlock(batboxA1Extra.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(batboxA1Extra, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, lA), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, batboxA1Extra, genBlock, rA, Direction.UP);

        // Outpost Smart Port A on natural riverbank (Y = waterY + 1)
        SmartPortBlockEntity portA = Lc2QaHelpers.placeSmartPort(level, portAPos, fA, "Outpost Harbor A");
        qa.check("p2.portA_on_natural_shore", portA.getBlockPos().getY() == waterY + 1,
                "portA=" + portAPos + ", waterY=" + waterY);
        qa.check("p2.portA_berth_detected",
                portA.getWaterBerthPos() != null && portA.getWaterBerthPos().equals(Lc2Coords.toCore(shoreA.berthPos())),
                "detected=" + portA.getWaterBerthPos() + ", expected=" + shoreA.berthPos());

        // Verify Phase 1 Comparator signal = 0 when cargo buffer empty (even with upgrades installed!)
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("trera:chunk_loading_upgrade", 1));
        ItemStack portAPull = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, portAPos, "mhera:pulling_upgrade", Direction.DOWN);
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 1, portAPull);
        portA.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 2, Lc2QaHelpers.itemStack("mhera:overclocker_upgrade", 2));
        portA.setItem(SmartPortBlockEntity.SLOT_BATTERY, Lc2QaHelpers.itemStack("mhera:single_use_battery", 8));
        qa.checkEquals("p1.portA_comparator_zero_when_cargo_empty", 0,
                level.getBlockState(portAPos).getAnalogOutputSignal(level, portAPos));

        // Place Real IC2 Advanced Miner right beside the cable atop Hopper 2!
        level.setBlock(advMinerPos, Lc2FieldHelpers.withFacingIfPresent(advMinerBlock, fA), Block.UPDATE_ALL);

        // Equip IC2 Advanced Miner with OV Scanner (ic2:advanced_scanner)
        boolean advScannerInserted = Lc2FieldHelpers.insertIntoMachineMenuSlot(
                level, advMinerPos, setupPlayer, Lc2QaHelpers.itemStack("mhera:advanced_scanner", 1));
        qa.check("p2.adv_miner_equipped_scanner", advScannerInserted,
                Lc2FieldHelpers.describeMachineSlots(level, advMinerPos, setupPlayer));

        Boolean portACableArm = Lc2QaHelpers.getCableDirectionProp(level.getBlockState(portACablePos), lA);
        qa.check("p2.outpost_cable_connected_to_portA", Boolean.TRUE.equals(portACableArm),
                "cable@" + portACablePos + " arm(" + lA + ")=" + portACableArm);

        // Spawn docked Electric Smart Cargo Boat right at Outpost Harbor A!
        Lc2QaHelpers.spawnDockedBoatAt(level, portA);

        // ---------------------------------------------------------------------
        // 2. OUTPOST CARGO STATION A + CLASSIC IC2 MINER (Dry Ore-Rich Hillside)
        // ---------------------------------------------------------------------
        // Place Cargo Station A on the nearby dry hillside (Y >= waterY + 2) so ic2:miner on leftA sits directly
        // over natural dry ore-bearing ground (verified at portAPos + back*8 + left*5 for seed 20261121!).
        BlockPos hillCandidate = portAPos.relative(bA, 8).relative(lA, 4);
        int hillY = Math.max(waterY + 2,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hillCandidate.getX(), hillCandidate.getZ()));
        this.stationAPos = new BlockPos(hillCandidate.getX(), hillY, hillCandidate.getZ());
        prepareCleanWorkPad(level, stationAPos, fA, 4, 4, 4);

        // Dry hillside Comparator loop (Y >= waterY + 2, zero river water anywhere near it!)
        this.stationACompPos = Lc2FieldHelpers.buildComparatorAutomationLoop(level, stationAPos, fA);
        // Place cable BEFORE stationA so CableBlock.updateShape -> CableBlockMixin connects cleanly!
        this.stationACablePos = stationAPos.relative(rA);
        BlockPos cableA2Mid = stationACablePos.relative(rA);
        BlockPos batboxA2Pos = cableA2Mid.relative(rA);
        level.setBlock(batboxA2Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stationACablePos, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(cableA2Mid, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(batboxA2Pos, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, lA), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, batboxA2Pos, genBlock, rA, bA, Direction.UP);

        CargoStationBlockEntity stationA = Lc2QaHelpers.placeCargoStation(level, stationAPos, "Outpost Airfield A");

        // Equip Cargo Station A: RF Chip + Standard & Heavy Drones + Chunk Loader + Pulling + Overclockers
        int distAir = Math.abs(stationAPos.getX() - shoreB.portPos().getX())
                + Math.abs(stationAPos.getY() - shoreB.portPos().getY())
                + Math.abs(stationAPos.getZ() - shoreB.portPos().getZ());
        String rfChipId = distAir <= 240 ? "trera:rf_chip_1" : (distAir <= 500 ? "trera:rf_chip_2" : "trera:rf_chip_3");
        stationA.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack(rfChipId, 1));
        stationA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START, Lc2QaHelpers.itemStack("trera:transport_drone", 1));
        stationA.setItem(CargoStationBlockEntity.SLOT_DRONE_BAY_START + 1, Lc2QaHelpers.itemStack("trera:heavy_transport_drone", 1));
        stationA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("trera:chunk_loading_upgrade", 1));
        ItemStack stAPull = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, stationAPos, "mhera:pulling_upgrade", lA);
        stationA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 1, stAPull);
        stationA.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 2, Lc2QaHelpers.itemStack("mhera:overclocker_upgrade", 2));
        stationA.setItem(CargoStationBlockEntity.SLOT_BATTERY, Lc2QaHelpers.itemStack("mhera:single_use_battery", 8));

        // Verify Phase 1 Comparator signal = 0 when cargo buffer is empty (even with RF chip + 2 drones + upgrades!)
        qa.checkEquals("p1.stationA_comparator_zero_when_cargo_empty", 0,
                level.getBlockState(stationAPos).getAnalogOutputSignal(level, stationAPos));

        // Real IC2 Classic Miner directly adjacent on leftA of Outpost Cargo Station A!
        // Outpost Cargo Station A's ic2:pulling_upgrade pulls mined ores straight from the Miner's WorldInventory
        // IItemHandler (preserving drill/scanner/pipes!) -> triggers Comparator -> launches Standard & Heavy Drones!
        this.minerPos = stationAPos.relative(lA);
        this.minerBox2Pos = minerPos.relative(lA);
        level.setBlock(minerBox2Pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(minerBox2Pos, ironBoxBlock.defaultBlockState(), Block.UPDATE_ALL);
        // Ensure natural dirt directly below minerPos if work-pad had placed stone bricks
        if (level.getBlockState(minerPos.below()).is(Blocks.STONE_BRICKS)
                || level.getBlockState(minerPos.below()).is(Blocks.COBBLESTONE)) {
            level.setBlock(minerPos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos minerCablePos = minerPos.relative(bA);
        BlockPos minerBatboxPos = minerCablePos.relative(bA);
        level.setBlock(minerCablePos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(minerBatboxPos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(minerCablePos, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(minerBatboxPos, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, fA), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, minerBatboxPos, genBlock, bA, lA, Direction.UP);
        level.setBlock(minerPos, Lc2FieldHelpers.withFacingIfPresent(minerBlock, fA), Block.UPDATE_ALL);
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Classic Miner at {} | below1={} below2={} below3={} below4={}",
                minerPos,
                level.getBlockState(minerPos.below(1)).getBlock(),
                level.getBlockState(minerPos.below(2)).getBlock(),
                level.getBlockState(minerPos.below(3)).getBlock(),
                level.getBlockState(minerPos.below(4)).getBlock());

        // Equip classic IC2 Miner with Diamond Drill, OV Scanner, and Mining Pipes!
        boolean drillOk = Lc2FieldHelpers.insertIntoMachineMenuSlot(
                level, minerPos, setupPlayer, Lc2QaHelpers.itemStack("mhera:diamond_drill", 1));
        boolean scanOk = Lc2FieldHelpers.insertIntoMachineMenuSlot(
                level, minerPos, setupPlayer, Lc2QaHelpers.itemStack("mhera:advanced_scanner", 1));
        boolean pipeOk = Lc2FieldHelpers.insertIntoMachineMenuSlot(
                level, minerPos, setupPlayer, Lc2QaHelpers.itemStack("mhera:mining_pipe", 64));
        qa.check("p2.classic_miner_equipped", drillOk && scanOk && pipeOk,
                "drill=" + drillOk + ", scanner=" + scanOk + ", pipes=" + pipeOk
                        + " | " + Lc2FieldHelpers.describeMachineSlots(level, minerPos, setupPlayer));

        Boolean stationACableArm = Lc2QaHelpers.getCableDirectionProp(level.getBlockState(stationACablePos), lA);
        qa.check("p2.outpost_cable_connected_to_stationA", Boolean.TRUE.equals(stationACableArm),
                "cable@" + stationACablePos + " arm(" + lA + ")=" + stationACableArm);

        // Snapshot genuine natural underground ore blocks underneath both miners BEFORE mining begins!
        snapshotUndergroundOres(level, advMinerPos, 16, -60, advMinerPos.getY() - 1, initialAdvMinerOres);
        snapshotUndergroundOres(level, minerPos, 5, -60, minerPos.getY() - 1, initialClassicMinerOres);
        qa.metric("outpostA.initialAdvMinerOres", initialAdvMinerOres.size());
        qa.metric("outpostA.initialClassicMinerOres", initialClassicMinerOres.size());
        qa.check("p2.natural_ores_present_under_miners",
                !initialAdvMinerOres.isEmpty() && !initialClassicMinerOres.isEmpty(),
                "advMinerOres=" + initialAdvMinerOres.size() + ", classicMinerOres=" + initialClassicMinerOres.size());

        // ---------------------------------------------------------------------
        // 3. MAIN REFINING BASE B (Natural Shore B — Full IC2 Ore-Processing Line!)
        // ---------------------------------------------------------------------
        Direction fB = shoreB.waterDir();
        Direction bB = fB.getOpposite();
        Direction rB = fB.getClockWise();
        Direction lB = fB.getCounterClockWise();

        this.portBPos = shoreB.portPos();
        prepareCleanWorkPad(level, portBPos, fB, 7, 5, 4);

        this.receivingChestPos = portBPos.relative(bB);
        this.stationBPos = receivingChestPos.relative(lB);
        this.maceratorPos = receivingChestPos.relative(bB);
        this.crushedBufferPos = maceratorPos.relative(bB);
        this.furnacePos = crushedBufferPos.relative(bB);
        this.steelBoxPos = furnacePos.relative(bB);

        // Place Base B cable bus FIRST with all arms FALSE so placing Base Smart Port B, Base Cargo Station B,
        // Macerator, and Electric Furnace triggers real CableBlock.updateShape -> CableBlockMixin connections!
        this.baseCablePos = portBPos.relative(rB);
        BlockPos c1 = receivingChestPos.relative(rB);
        BlockPos c2 = maceratorPos.relative(rB);
        BlockPos c3 = crushedBufferPos.relative(rB);
        BlockPos c4 = furnacePos.relative(rB);
        for (BlockPos cp : List.of(baseCablePos, c1, c2, c3, c4)) {
            level.setBlock(cp, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        }
        BlockPos baseBat1 = c1.relative(rB);
        BlockPos baseBat2 = c4.relative(rB);
        level.setBlock(baseBat1, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, lB), Block.UPDATE_ALL);
        level.setBlock(baseBat2, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, lB), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, baseBat1, genBlock, rB, fB, Direction.UP);
        placeGeneratorCluster(level, setupPlayer, baseBat2, genBlock, rB, bB, Direction.UP);

        BlockPos stBCable = stationBPos.relative(lB);
        BlockPos stBBatbox = stBCable.relative(lB);
        level.setBlock(stBCable.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stBBatbox.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stBCable, Lc2QaHelpers.withAllCableArmsFalse(cableBlock.defaultBlockState()), Block.UPDATE_ALL);
        level.setBlock(stBBatbox, Lc2FieldHelpers.withFacingIfPresent(batboxBlock, rB), Block.UPDATE_ALL);
        placeGeneratorCluster(level, setupPlayer, stBBatbox, genBlock, lB, bB, Direction.UP);

        // Base Smart Port B on natural Shore B (Y = waterY + 1)
        SmartPortBlockEntity portB = Lc2QaHelpers.placeSmartPort(level, portBPos, fB, "Refinery Dock B");
        qa.check("p2.portB_berth_detected",
                portB.getWaterBerthPos() != null && portB.getWaterBerthPos().equals(Lc2Coords.toCore(shoreB.berthPos())),
                "detected=" + portB.getWaterBerthPos() + ", expected=" + shoreB.berthPos());
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("trera:chunk_loading_upgrade", 1));
        portB.setItem(SmartPortBlockEntity.SLOT_UPGRADE_START + 1,
                Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, portBPos, "mhera:ejector_upgrade", bB));

        // Receiving Chest (Iron Storage Box — holds 54+ stacks cleanly!)
        level.setBlock(receivingChestPos, ironBoxBlock.defaultBlockState(), Block.UPDATE_ALL);

        // Base Cargo Station B (directly adjacent to Receiving Chest on leftB!)
        CargoStationBlockEntity stationB = Lc2QaHelpers.placeCargoStation(level, stationBPos, "Refinery Pad B");
        stationB.setItem(CargoStationBlockEntity.SLOT_RF_CHIP, Lc2QaHelpers.itemStack(rfChipId, 1));
        stationB.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START, Lc2QaHelpers.itemStack("trera:chunk_loading_upgrade", 1));
        stationB.setItem(CargoStationBlockEntity.SLOT_UPGRADE_START + 1,
                Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, stationBPos, "mhera:ejector_upgrade", rB));

        // IC2 Ore-Processing Production Line:
        // Receiving Box -> ic2:macerator -> Crushed Ore Buffer Chest -> ic2:electric_furnace -> ic2:steel_storage_box!
        level.setBlock(maceratorPos, Lc2FieldHelpers.withFacingIfPresent(maceratorBlock, rB), Block.UPDATE_ALL);
        level.setBlock(crushedBufferPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(furnacePos, Lc2FieldHelpers.withFacingIfPresent(furnaceBlock, rB), Block.UPDATE_ALL);
        level.setBlock(steelBoxPos, steelBoxBlock.defaultBlockState(), Block.UPDATE_ALL);

        // Equip IC2 Macerator: pulling_upgrade(fB = Receiving Box) + ejector_upgrade(bB = Crushed Buffer)
        // + overclocker_upgrade x5 (LV-safe 21 EU/t, 50 ticks/item!) + energy_storage_upgrade x1!
        ItemStack macPull = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, maceratorPos, "mhera:pulling_upgrade", fB);
        ItemStack macEject = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, maceratorPos, "mhera:ejector_upgrade", bB);
        boolean macOk1 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, maceratorPos, setupPlayer, macPull);
        boolean macOk2 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, maceratorPos, setupPlayer, macEject);
        boolean macOk3 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, maceratorPos, setupPlayer,
                Lc2QaHelpers.itemStack("mhera:overclocker_upgrade", 5));
        boolean macOk4 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, maceratorPos, setupPlayer,
                Lc2QaHelpers.itemStack("mhera:energy_storage_upgrade", 1));
        qa.check("p2.macerator_equipped", macOk1 && macOk2 && macOk3 && macOk4,
                "pull=" + macOk1 + ", eject=" + macOk2 + ", oc=" + macOk3 + ", es=" + macOk4
                        + " | " + Lc2FieldHelpers.describeMachineSlots(level, maceratorPos, setupPlayer));

        // Equip IC2 Electric Furnace: pulling_upgrade(fB = Crushed Buffer) + ejector_upgrade(bB = Steel Storage Box)
        // + overclocker_upgrade x4 (LV-safe 20 EU/t, 24 ticks/ingot!) + energy_storage_upgrade x1!
        ItemStack furPull = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, furnacePos, "mhera:pulling_upgrade", fB);
        ItemStack furEject = Lc2FieldHelpers.configureUpgradeFace(level, setupPlayer, furnacePos, "mhera:ejector_upgrade", bB);
        boolean furOk1 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, furnacePos, setupPlayer, furPull);
        boolean furOk2 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, furnacePos, setupPlayer, furEject);
        boolean furOk3 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, furnacePos, setupPlayer,
                Lc2QaHelpers.itemStack("mhera:overclocker_upgrade", 4));
        boolean furOk4 = Lc2FieldHelpers.insertIntoMachineMenuSlot(level, furnacePos, setupPlayer,
                Lc2QaHelpers.itemStack("mhera:energy_storage_upgrade", 1));
        qa.check("p2.electric_furnace_equipped", furOk1 && furOk2 && furOk3 && furOk4,
                "pull=" + furOk1 + ", eject=" + furOk2 + ", oc=" + furOk3 + ", es=" + furOk4
                        + " | " + Lc2FieldHelpers.describeMachineSlots(level, furnacePos, setupPlayer));

        Boolean portBCableArm = Lc2QaHelpers.getCableDirectionProp(level.getBlockState(baseCablePos), lB);
        Boolean stBCableArm = Lc2QaHelpers.getCableDirectionProp(level.getBlockState(stBCable), rB);
        qa.check("p2.baseB_cables_connected", Boolean.TRUE.equals(portBCableArm) && Boolean.TRUE.equals(stBCableArm),
                "portBArm=" + portBCableArm + ", stBArm=" + stBCableArm);

        // Link Outpost A -> Refining Base B with Auto-Return enabled!
        portA.setTargetPos(portBPos);
        portA.setAutoReturn(true);
        portA.setEnergyPriority(EnergyPriority.EU_FIRST);

        stationA.setTargetPos(stationBPos);
        stationA.setAutoReturn(true);
        stationA.setEnergyPriority(EnergyPriority.EU_FIRST);

        // Give each station initial BatBox-equivalent buffer seed? NO — let REAL IC2 Generators + BatBoxes charge them!
        // We DO pre-seed BatBoxes? Generators produce 10 EU/t each (3 generators = 30 EU/t -> ~6000 EU in 200 ticks = 10s)!
        // Let's log EU every 100 ticks so we prove 100% REAL IC2 Generator -> BatBox -> Insulated Copper Cable -> LC2 EU flow!

        qa.metric("outpostA.portPos", portAPos.toShortString());
        qa.metric("outpostA.stationPos", stationAPos.toShortString());
        qa.metric("outpostA.advMinerPos", advMinerPos.toShortString());
        qa.metric("outpostA.classicMinerPos", minerPos.toShortString());
        qa.metric("baseB.portPos", portBPos.toShortString());
        qa.metric("baseB.stationPos", stationBPos.toShortString());
        qa.metric("baseB.maceratorPos", maceratorPos.toShortString());
        qa.metric("baseB.electricFurnacePos", furnacePos.toShortString());
        qa.metric("baseB.steelStorageBoxPos", steelBoxPos.toShortString());
    }

    private BlockPos findDryHillsideForClassicMiner(
            ServerLevel level,
            BlockPos portPos,
            BlockPos advPos,
            Direction front
    ) {
        Direction back = front.getOpposite();
        Direction left = front.getCounterClockWise();
        Direction right = front.getClockWise();
        BlockPos bestPos = null;
        int bestScore = Integer.MIN_VALUE;

        for (int db = 10; db <= 22; db += 2) {
            for (int ds = -8; ds <= 8; ds += 2) {
                BlockPos col = portPos.relative(back, db).relative(right, ds);
                int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
                int stY = Math.max(waterY + 3, groundY);
                if (stY > waterY + 18) {
                    continue;
                }
                BlockPos stCandidate = new BlockPos(col.getX(), stY, col.getZ());
                BlockPos mCandidate = stCandidate.relative(left);
                // Keep classic miner outside advanced miner's immediate overlap so both mine distinct deposits!
                double distFromAdv = Math.hypot(mCandidate.getX() - advPos.getX(), mCandidate.getZ() - advPos.getZ());
                if (distFromAdv < 12.0) {
                    continue;
                }
                // Check dry drill shaft (ZERO water/lava directly below minerPos!)
                boolean shaftDry = true;
                for (int y = stY - 1; y >= 5; y--) {
                    BlockPos p = new BlockPos(mCandidate.getX(), y, mCandidate.getZ());
                    if (!level.getBlockState(p).getFluidState().isEmpty()) {
                        shaftDry = false;
                        break;
                    }
                }
                if (!shaftDry) {
                    continue;
                }
                int shallowMetal = countNaturalMetalOres(level, mCandidate, 20, stY - 2, 4);
                int deepMetal = countNaturalMetalOres(level, mCandidate, 0, 19, 4);
                int score = shallowMetal * 10 + deepMetal * 2 - db;
                if (score > bestScore) {
                    bestScore = score;
                    bestPos = stCandidate;
                }
            }
        }
        if (bestPos != null) {
            Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Selected dry hillside Cargo Station A at {} (score={})", bestPos, bestScore);
            return bestPos;
        }
        // Fallback: safe elevated hillside pad
        BlockPos fallback = portPos.relative(back, 14).relative(left, 6);
        int fy = Math.max(waterY + 3, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fallback.getX(), fallback.getZ()));
        return new BlockPos(fallback.getX(), fy, fallback.getZ());
    }

    private void prepareCleanWorkPad(
            ServerLevel level,
            BlockPos anchor,
            Direction front,
            int backExtent,
            int leftExtent,
            int rightExtent
    ) {
        Direction back = front.getOpposite();
        Direction left = front.getCounterClockWise();
        Direction right = front.getClockWise();
        for (int db = 0; db <= backExtent; db++) {
            for (int ds = -leftExtent; ds <= rightExtent; ds++) {
                BlockPos base = anchor.relative(back, db).relative(right, ds);
                // Solid floor at Y-1 (fill down up to 3 blocks if slope dips, never touching water in front)
                for (int dy = -1; dy >= -3; dy--) {
                    BlockPos floorPos = base.offset(0, dy, 0);
                    if (level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty()) {
                        level.setBlock(floorPos,
                                dy == -1 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                }
                // Clear headroom Y..Y+5 so machines, drones, and screenshots have clean sightlines
                for (int dy = 0; dy <= 5; dy++) {
                    BlockPos clearPos = base.offset(0, dy, 0);
                    if (!level.getBlockState(clearPos).isAir()) {
                        level.setBlock(clearPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static void placeGeneratorCluster(
            ServerLevel level,
            FakePlayer player,
            BlockPos batboxPos,
            Block genBlock,
            Direction... sides
    ) {
        for (Direction side : sides) {
            BlockPos gp = batboxPos.relative(side);
            if (side != Direction.UP) {
                level.setBlock(gp.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(gp, genBlock.defaultBlockState(), Block.UPDATE_ALL);
            boolean fueled = Lc2QaHelpers.insertItemIntoBlock(level, gp, new ItemStack(Items.COAL, 64));
            if (!fueled) {
                Lc2FieldHelpers.insertIntoMachineMenuSlot(level, gp, player, new ItemStack(Items.COAL, 64));
            }
        }
    }

    private static void snapshotUndergroundOres(
            ServerLevel level,
            BlockPos origin,
            int radius,
            int minY,
            int maxY,
            Set<BlockPos> out
    ) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    String id = Lc2FieldHelpers.blockId(level.getBlockState(m).getBlock());
                    if (Lc2FieldHelpers.NATURAL_ANY_ORE_BLOCKS.contains(id)) {
                        out.add(m.immutable());
                    }
                }
            }
        }
    }

    private static int countRemovedOres(ServerLevel level, Set<BlockPos> initialOres) {
        int removed = 0;
        for (BlockPos pos : initialOres) {
            String id = Lc2FieldHelpers.blockId(level.getBlockState(pos).getBlock());
            if (!Lc2FieldHelpers.NATURAL_ANY_ORE_BLOCKS.contains(id)) {
                removed++;
            }
        }
        return removed;
    }

    // =========================================================================
    // PHASE 2C: MONITOR FULL AUTONOMOUS PRODUCTION LINE END-TO-END!
    // =========================================================================

    private void tickAutomatedProduction(MinecraftServer server, ServerLevel level) throws Exception {
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
        CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(stationAPos);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(portBPos);
        CargoStationBlockEntity stB = (CargoStationBlockEntity) level.getBlockEntity(stationBPos);

        // Track comparator signals & active transits every tick!
        if (portA != null && portA.getCargoComparatorSignal() > 0 && Lc2FieldHelpers.isComparatorPowered(level, portACompPos)) {
            observedPortAComparatorFired = true;
        }
        if (stA != null && stA.getCargoComparatorSignal() > 0 && Lc2FieldHelpers.isComparatorPowered(level, stationACompPos)) {
            observedStationAComparatorFired = true;
        }

        for (TransitRecord rec : LogisticsLevelManager.activeTransits(level)) {
            if (rec.vehicleKind().isBoat() && rec.originPos().equals(Lc2Coords.toCore(portAPos))) {
                if (seenBoatTransits.add(rec.id())) {
                    boatDispatchesSeen++;
                    Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Boat #{} auto-dispatched across natural waterway! (phase={})",
                            boatDispatchesSeen, rec.phase());
                }
                observedBoatOutbound = true;
            }
            if (rec.vehicleKind().isDrone() && rec.originPos().equals(Lc2Coords.toCore(stationAPos))) {
                if (seenDroneTransits.add(rec.id())) {
                    droneDispatchesSeen++;
                    Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Drone #{} ({}) auto-dispatched over natural terrain! (phase={})",
                            droneDispatchesSeen, rec.vehicleKind(), rec.phase());
                }
                observedDroneOutbound = true;
            }
        }

        // Check auto-return completion once outbound transits finish!
        if (observedBoatOutbound && !observedBoatAutoReturned && portA != null) {
            boolean anyBoatActive = false;
            for (UUID id : seenBoatTransits) {
                if (LogisticsLevelManager.isTransitActive(level, id)) {
                    anyBoatActive = true;
                    break;
                }
            }
            if (!anyBoatActive && portA.findDockedBoat(level) != null) {
                observedBoatAutoReturned = true;
                Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Electric Smart Cargo Boat auto-returned & docked at Outpost Harbor A!");
            }
        }
        if (observedDroneOutbound && !observedDroneAutoReturned && stA != null) {
            boolean anyDroneActive = false;
            for (UUID id : seenDroneTransits) {
                if (LogisticsLevelManager.isTransitActive(level, id)) {
                    anyDroneActive = true;
                    break;
                }
            }
            if (!anyDroneActive && Integer.bitCount(stA.getDockedBayMask()) >= 2) {
                observedDroneAutoReturned = true;
                Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Cargo Drone(s) paid return EU at Base B & auto-returned to Outpost Airfield A!");
            }
        }

        // Periodic detailed telemetry + ore/ingot inspection every 40 ticks
        if (stageTicks % 40 == 0) {
            int advMined = countRemovedOres(level, initialAdvMinerOres);
            int classicMined = countRemovedOres(level, initialClassicMinerOres);
            if (advMined > 0) {
                observedAdvMinerMined = true;
            }
            if (classicMined > 0) {
                observedClassicMinerMined = true;
            }
            totalMinedNaturalOreBlocks = advMined + classicMined;

            Map<Item, Integer> box1Items = Lc2QaHelpers.countItemsInBlocks(level, List.of(minerBox1Pos));
            Map<Item, Integer> box2Items = Lc2QaHelpers.countItemsInBlocks(level, List.of(minerBox2Pos));
            Map<Item, Integer> recvItems = Lc2QaHelpers.countItemsInBlocks(level, List.of(receivingChestPos));
            Map<Item, Integer> crushedItems = Lc2QaHelpers.countItemsInBlocks(level, List.of(crushedBufferPos));
            Map<Item, Integer> finalItems = Lc2QaHelpers.countItemsInBlocks(level, List.of(steelBoxPos));

            int crushedCount = Lc2FieldHelpers.countMatchingItems(crushedItems, Lc2FieldHelpers.CRUSHED_ORE_ITEMS);
            if (crushedCount > 0) {
                observedCrushedOre = true;
            }
            int ingotCount = Lc2FieldHelpers.countMatchingItems(finalItems, Lc2FieldHelpers.FINISHED_INGOT_ITEMS);
            if (ingotCount > 0) {
                observedFinishedIngots = true;
                observedCrushedOre = true; // smelted from crushed ore!
            }
            totalFinishedIngots = ingotCount;

            double advEu = readEu(level, advMinerPos);
            double minerEu = readEu(level, minerPos);
            double macEu = readEu(level, maceratorPos);
            double furEu = readEu(level, furnacePos);

            Lc2QaMod.LOGGER.info(
                    "[LC2-FIELD-SERVER] t={} | Mined(adv={},classic={}) | EU(portA={},stA={},portB={},stB={},adv={},miner={},mac={},fur={}) | "
                            + "Box1={} Box2={} Recv={} Crushed={} FinalIngots={} | Boat(out={},ret={}) Drone(out={},ret={}) Comp(port={},st={}) "
                            + "| Sig(portA={}/{}/rs={},stA={}/{}/rs={})",
                    stageTicks, advMined, classicMined,
                    fmt(portA == null ? 0 : portA.getStoredEu()),
                    fmt(stA == null ? 0 : stA.getStoredEu()),
                    fmt(portB == null ? 0 : portB.getStoredEu()),
                    fmt(stB == null ? 0 : stB.getStoredEu()),
                    fmt(advEu), fmt(minerEu), fmt(macEu), fmt(furEu),
                    Lc2QaHelpers.totalItemCount(box1Items),
                    Lc2QaHelpers.totalItemCount(box2Items),
                    Lc2QaHelpers.totalItemCount(recvItems),
                    crushedCount, ingotCount,
                    observedBoatOutbound, observedBoatAutoReturned,
                    observedDroneOutbound, observedDroneAutoReturned,
                    observedPortAComparatorFired, observedStationAComparatorFired,
                    portA == null ? "null" : portA.getStatusKey(),
                    portA == null ? -1 : portA.getCargoComparatorSignal(),
                    level.hasNeighborSignal(portAPos),
                    stA == null ? "null" : stA.getStatusKey(),
                    stA == null ? -1 : stA.getCargoComparatorSignal(),
                    level.hasNeighborSignal(stationAPos)
            );

            // Check if all automated production-line milestones succeeded!
            boolean allDone = observedAdvMinerMined
                    && observedClassicMinerMined
                    && observedPortAComparatorFired
                    && observedStationAComparatorFired
                    && observedBoatOutbound
                    && observedBoatAutoReturned
                    && observedDroneOutbound
                    && observedDroneAutoReturned
                    && observedCrushedOre
                    && observedFinishedIngots;

            if (allDone) {
                verifyPhase2AndOpenForClient(server, level, advMined, classicMined, finalItems);
                return;
            }
        }

        // Timeout diagnostic after 2200 ticks
        if (stageTicks >= 2200) {
            FakePlayer diagPlayer = FakePlayerFactory.getMinecraft(level);
            Lc2QaMod.LOGGER.error("[LC2-FIELD-SERVER] Production-line timeout diagnostics:");
            Lc2QaMod.LOGGER.error("  advMiner slots: {}", Lc2FieldHelpers.describeMachineSlots(level, advMinerPos, diagPlayer));
            Lc2QaMod.LOGGER.error("  classicMiner slots: {}", Lc2FieldHelpers.describeMachineSlots(level, minerPos, diagPlayer));
            Lc2QaMod.LOGGER.error("  macerator slots: {}", Lc2FieldHelpers.describeMachineSlots(level, maceratorPos, diagPlayer));
            Lc2QaMod.LOGGER.error("  furnace slots: {}", Lc2FieldHelpers.describeMachineSlots(level, furnacePos, diagPlayer));
            Lc2QaMod.LOGGER.error("  box1={}, box2={}, recv={}, crushed={}, final={}",
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(minerBox1Pos)),
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(minerBox2Pos)),
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(receivingChestPos)),
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(crushedBufferPos)),
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(steelBoxPos)));
            verifyPhase2AndOpenForClient(
                    server,
                    level,
                    countRemovedOres(level, initialAdvMinerOres),
                    countRemovedOres(level, initialClassicMinerOres),
                    Lc2QaHelpers.countItemsInBlocks(level, List.of(steelBoxPos))
            );
        }
    }

    private void verifyPhase2AndOpenForClient(
            MinecraftServer server,
            ServerLevel level,
            int advMined,
            int classicMined,
            Map<Item, Integer> finalItems
    ) throws Exception {
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(portAPos);
        CargoStationBlockEntity stA = (CargoStationBlockEntity) level.getBlockEntity(stationAPos);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(portBPos);
        CargoStationBlockEntity stB = (CargoStationBlockEntity) level.getBlockEntity(stationBPos);

        qa.check("p2.adv_miner_mined_natural_ores", observedAdvMinerMined && advMined > 0,
                "advMinerRemovedNaturalOres=" + advMined);
        qa.check("p2.classic_miner_mined_natural_ores", observedClassicMinerMined && classicMined > 0,
                "classicMinerRemovedNaturalOres=" + classicMined);
        qa.check("p2.portA_comparator_triggered_dispatch", observedPortAComparatorFired,
                "portA comparator fired");
        qa.check("p2.stationA_comparator_triggered_dispatch", observedStationAComparatorFired,
                "stationA comparator fired");
        qa.check("p2.boat_dispatched_across_natural_waterway", observedBoatOutbound && boatDispatchesSeen >= 1,
                "boatDispatches=" + boatDispatchesSeen);
        qa.check("p2.boat_auto_returned", observedBoatAutoReturned, "boat auto-returned to Outpost Harbor A");
        qa.check("p2.drone_dispatched_over_natural_terrain", observedDroneOutbound && droneDispatchesSeen >= 1,
                "droneDispatches=" + droneDispatchesSeen);
        qa.check("p2.drone_auto_returned", observedDroneAutoReturned, "drone paid return EU at Base B & returned");
        qa.check("p2.macerator_crushed_ores", observedCrushedOre, "macerator produced crushed ores");
        qa.check("p2.electric_furnace_smelted_ingots", observedFinishedIngots && totalFinishedIngots > 0,
                "finalSteelBoxIngots=" + totalFinishedIngots + ", contents=" + formatItemMap(finalItems));
        qa.check("p2.chunk_loaders_active",
                portA != null && portA.isChunkLoaderActive()
                        && stA != null && stA.isChunkLoaderActive()
                        && portB != null && portB.isChunkLoaderActive()
                        && stB != null && stB.isChunkLoaderActive(),
                "portA=" + (portA != null && portA.isChunkLoaderActive())
                        + ", stA=" + (stA != null && stA.isChunkLoaderActive())
                        + ", portB=" + (portB != null && portB.isChunkLoaderActive())
                        + ", stB=" + (stB != null && stB.isChunkLoaderActive()));

        qa.metric("p2.productionTicks", stageTicks);
        qa.metric("p2.advMinerMinedOres", advMined);
        qa.metric("p2.classicMinerMinedOres", classicMined);
        qa.metric("p2.totalMinedNaturalOres", totalMinedNaturalOreBlocks);
        qa.metric("p2.boatDispatches", boatDispatchesSeen);
        qa.metric("p2.droneDispatches", droneDispatchesSeen);
        qa.metric("p2.finalSteelBoxIngots", totalFinishedIngots);
        qa.metric("p2.finalSteelBoxContents", formatItemMap(finalItems));

        // Release temporary QA chunk tickets now that lc2:chunk_loading_upgrade is verified active
        for (ChunkPos cp : heldTempChunks) {
            Lc2QaHelpers.qaReleaseChunk(level, cp);
        }
        heldTempChunks.clear();

        if (!waitForClient) {
            writeFinalServerReport(level);
            advance(Stage.DONE);
            server.halt(false);
            return;
        }

        // Write readiness marker so run_field_test.py launches the real Forge 1.19.2 Graphical Client!
        Properties ready = new Properties();
        ready.setProperty("ready", "true");
        ready.setProperty("port", Integer.toString(server.getPort()));
        ready.setProperty("seed", Long.toString(level.getSeed()));
        ready.setProperty("portA", portAPos.toShortString());
        ready.setProperty("stationA", stationAPos.toShortString());
        ready.setProperty("portB", portBPos.toShortString());
        ready.setProperty("stationB", stationBPos.toShortString());
        ready.setProperty("finalIngots", Integer.toString(totalFinishedIngots));
        try (Writer w = Files.newBufferedWriter(READY_MARKER)) {
            ready.store(w, "LC2 Forge 1.19.2 Field Server Ready for Graphical Client");
        }
        Lc2QaMod.LOGGER.info("[LC2-FIELD-SERVER] Phase 2 PASSED! Ready for real graphical client on port {}",
                server.getPort());
        advance(Stage.WAIT_CLIENT_JOIN);
    }

    // =========================================================================
    // PHASE 3 HELPERS (Client-Server Coordination & Telemetry)
    // =========================================================================

    private void preparePlayerForFieldTour(ServerLevel level, ServerPlayer player) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
    }

    private void teleportLook(ServerLevel level, ServerPlayer player, Vec3 camPos, Vec3 lookTarget) {
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // Eye height in creative standing is +1.62 above feet
        double feetY = camPos.y - 1.62;
        float[] angles = Lc2FieldHelpers.lookAngles(camPos, lookTarget);
        player.teleportTo(level, camPos.x, feetY, camPos.z, angles[0], angles[1]);
        player.setYHeadRot(angles[0]);
    }

    private void sendDirective(ServerPlayer player, String action, BlockPos pos, int entityId, String arg) {
        int id = nextStepId++;
        this.awaitingStepId = id;
        this.lastAck = null;
        Lc2FieldNetwork.sendToClient(player, new Lc2FieldNetwork.S2CFieldDirective(id, action, pos, entityId, arg));
    }

    private boolean consumeAck(String checkKey) {
        Lc2FieldNetwork.C2SFieldAck ack = this.lastAck;
        if (ack != null && ack.stepId() == this.awaitingStepId) {
            qa.check(checkKey, ack.success(), ack.summary());
            this.awaitingStepId = -1;
            this.lastAck = null;
            return true;
        }
        if (stageTicks > 600) {
            throw new AssertionError("Timed out waiting for client ACK on " + checkKey + " (step=" + awaitingStepId + ")");
        }
        return false;
    }

    private static ServerPlayer findConnectedPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    private static ServerPlayer requirePlayer(MinecraftServer server) {
        ServerPlayer p = findConnectedPlayer(server);
        if (p == null) {
            throw new AssertionError("Graphical client disconnected unexpectedly during Phase 3");
        }
        return p;
    }

    private static ElectricCargoBoatEntity findBoatByTransit(ServerLevel level, UUID transitId) {
        if (transitId == null) {
            return null;
        }
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof ElectricCargoBoatEntity boat && transitId.equals(boat.getTransitId())) {
                return boat;
            }
        }
        return null;
    }

    private void holdAreaTemp(ServerLevel level, BlockPos center, int radiusChunks) {
        ChunkPos base = new ChunkPos(center);
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                ChunkPos cp = new ChunkPos(base.x + dx, base.z + dz);
                Lc2QaHelpers.qaHoldChunk(level, cp);
                heldTempChunks.add(cp);
            }
        }
    }

    private static double readEu(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EuBuffer buf) {
            return buf.bufferedEu().stored();
        }
        return -1.0;
    }

    private static String fmt(double d) {
        return String.format("%.0f", d);
    }

    private static String formatItemMap(Map<Item, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Lc2FieldHelpers.itemId(e.getKey())).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private void writeFinalServerReport(ServerLevel level) throws Exception {
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("seed", Long.toString(level.getSeed()));
        extras.put("shoreA", shoreA.portPos().toShortString());
        extras.put("shoreB", shoreB.portPos().toShortString());
        qa.writePassedReport(SERVER_REPORT, extras);
    }
}
