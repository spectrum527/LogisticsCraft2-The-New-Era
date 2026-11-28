package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.PaymentPlan;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.TransitSnapshot;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.core.WaterGrid;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTransitAndCollisionTest {

    private static final int SEA_Y = 62;

    @Test
    void unloadedChunkBoatRoundTripDematerializesAdvancesAndCompletesCleanly() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i originPort = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i originBerth = new BlockPos3i(1, SEA_Y, 0);
        BlockPos3i targetPort = new BlockPos3i(80, SEA_Y, 0);
        BlockPos3i targetBerth = new BlockPos3i(79, SEA_Y, 0);

        registry.register(originPort, StationType.SMART_PORT, "West Dock", originBerth);
        registry.register(targetPort, StationType.SMART_PORT, "East Dock", targetBerth);

        WaterGrid sea = flatChannel(0, 80, -3, 3);
        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        var dispatch = engine.dispatchBoat(new VirtualTransitEngine.BoatDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                originPort,
                targetPort,
                true, // round-trip -> 6000 EU prepaid
                true,
                true,
                false,
                UpgradeSummary.EMPTY,
                6_000.0,
                0,
                EnergyPriority.EU_FIRST,
                sea,
                null
        ));

        assertTrue(dispatch.isSuccess());
        assertTrue(dispatch.payment().isEu());
        assertEquals(6_000.0, dispatch.payment().euToSpend(), 1e-9);
        assertTrue(dispatch.transit().returnPrepaid());

        // Only origin chunk (cx=0) is loaded initially; middle chunks (1..3) and target chunk (cx=4) are unloaded
        Set<Integer> loadedChunkXs = new HashSet<>(Set.of(0));
        AtomicBoolean targetUnloaded = new AtomicBoolean(false);

        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return loadedChunkXs.contains(chunkX);
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                targetUnloaded.set(true);
                return true;
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                throw new AssertionError("Prepaid boat return must NOT charge target station!");
            }

            @Override
            public WaterGrid waterGrid() {
                return sea;
            }
        };

        boolean sawMaterializedInOrigin = false;
        boolean sawDematerializedInMiddle = false;

        // Tick until boat reaches target berth in unloaded chunk
        for (int t = 0; t < 400; t++) {
            var report = engine.tick(bridge);
            if (!report.toMaterialize().isEmpty()) {
                sawMaterializedInOrigin = true;
            }
            if (!report.toDematerialize().isEmpty()) {
                sawDematerializedInMiddle = true;
            }
            if (dispatch.transit().phase() == TransitPhase.ARRIVED_UNLOADING) {
                break;
            }
        }

        assertTrue(sawMaterializedInOrigin, "Boat should materialize while in loaded origin chunk");
        assertTrue(sawDematerializedInMiddle, "Boat should cleanly dematerialize on entering unloaded middle chunks");
        assertEquals(TransitPhase.ARRIVED_UNLOADING, dispatch.transit().phase());
        assertFalse(targetUnloaded.get(), "Unloading must wait while target chunk is unloaded");
        assertEquals(1, engine.pendingArrivalsAt(targetPort).size());

        // Now load target chunk -> boat immediately unloads and departs on prepaid return leg!
        loadedChunkXs.add(targetPort.chunkX());
        var arrivalTick = engine.tick(bridge);
        assertTrue(targetUnloaded.get(), "Target should unload as soon as target chunk loads");
        assertEquals(1, arrivalTick.returnDeparted().size());
        assertEquals(TransitPhase.EN_ROUTE_RETURN, dispatch.transit().phase());

        // Unload origin chunk before boat gets back to verify unloaded-origin redock queueing
        loadedChunkXs.remove(originPort.chunkX());
        for (int t = 0; t < 400; t++) {
            engine.tick(bridge);
            if (engine.pendingArrivalsAt(originPort).size() == 1) {
                break;
            }
        }
        assertEquals(1, engine.pendingArrivalsAt(originPort).size(),
                "Returning boat should wait cleanly in pendingArrivals when origin chunk is unloaded");
        assertEquals(1, engine.activeTransits().size());

        // Reload origin chunk -> completes and docks cleanly
        loadedChunkXs.add(originPort.chunkX());
        var finishReport = engine.tick(bridge);
        assertEquals(1, finishReport.completed().size());
        assertEquals(TransitPhase.COMPLETED, finishReport.completed().get(0).phase());
        assertTrue(engine.activeTransits().isEmpty());
        assertTrue(engine.pendingArrivalsAt(originPort).isEmpty());
    }

    @Test
    void droneOutboundAndTargetPaidAutoReturnWaitsForEnergyAndFreeOriginBay() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(60, 64, 0); // Manhattan distance = 60 -> Band 1 (800 EU / 1 bat)

        registry.register(origin, StationType.CARGO_STATION, "Alpha Hub", null);
        registry.register(target, StationType.CARGO_STATION, "Beta Outpost", null);
        registry.setDockedDrones(origin, 1); // 1 drone leaving from bay 0
        registry.setDockedDrones(target, 0);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        var dispatch = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                VehicleKind.STANDARD_DRONE,
                origin,
                0,
                target,
                true, // autoReturn: target pays return!
                true,
                RfChipTier.TIER_1,
                UpgradeSummary.EMPTY,
                800.0, // exact 1-leg cost only (autoReturn does NOT double origin cost!)
                0,
                EnergyPriority.EU_FIRST,
                null
        ));

        assertTrue(dispatch.isSuccess());
        assertEquals(800.0, dispatch.payment().euToSpend(), 1e-9);
        assertFalse(dispatch.transit().returnPrepaid());
        // Origin bay released on takeoff (caller updates docked count to 0)
        registry.setDockedDrones(origin, 0);
        // Target bay atomically reserved (5 free bays left out of 6)
        assertEquals(5, registry.freeBays(target));

        double outboundCruiseY = dispatch.transit().waypoints().get(1).y();

        AtomicBoolean targetHasEnergy = new AtomicBoolean(false);
        AtomicReference<EnergyCost> billedReturnCost = new AtomicReference<>();

        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                return true;
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                billedReturnCost.set(returnCost);
                return targetHasEnergy.get();
            }
        };

        // Advance outbound flight until arrival and unloading -> enters WAITING_RETURN_ENERGY
        for (int t = 0; t < 200; t++) {
            engine.tick(bridge);
            if (dispatch.transit().phase() == TransitPhase.WAITING_RETURN_ENERGY) {
                break;
            }
        }
        assertEquals(TransitPhase.WAITING_RETURN_ENERGY, dispatch.transit().phase());
        assertEquals(new EnergyCost(800.0, 1), billedReturnCost.get());
        // While waiting at target, target bay remains reserved and origin bay is NOT leaked
        assertEquals(5, registry.freeBays(target));
        assertEquals(6, registry.freeBays(origin));

        // Tick 5 more ticks without target energy -> stays safely waiting
        for (int t = 0; t < 5; t++) {
            engine.tick(bridge);
        }
        assertEquals(TransitPhase.WAITING_RETURN_ENERGY, dispatch.transit().phase());
        assertTrue(dispatch.transit().ticksWaiting() >= 5);

        // Now give target energy, BUT fill all 6 bays at origin -> must NOT debit target energy or launch!
        registry.setDockedDrones(origin, 6);
        billedReturnCost.set(null);
        targetHasEnergy.set(true);
        engine.tick(bridge);
        assertEquals(TransitPhase.WAITING_RETURN_ENERGY, dispatch.transit().phase());
        assertEquals(null, billedReturnCost.get(), "Must check free origin bay BEFORE debiting target energy");

        // Free 1 bay at origin -> target pays return cost, target bay freed, origin bay reserved, drone departs!
        registry.setDockedDrones(origin, 5);
        var returnTick = engine.tick(bridge);
        assertEquals(1, returnTick.returnDeparted().size());
        assertEquals(TransitPhase.EN_ROUTE_RETURN, dispatch.transit().phase());
        assertEquals(new EnergyCost(800.0, 1), billedReturnCost.get());
        assertEquals(6, registry.freeBays(target), "Target bay released on return takeoff");
        assertEquals(0, registry.freeBays(origin), "Origin return bay atomically reserved");

        // Verify return route uses opposite altitude band from outbound route
        double returnCruiseY = dispatch.transit().waypoints().get(1).y();
        assertTrue(Math.abs(returnCruiseY - outboundCruiseY) >= 4.0,
                "Return cruise altitude must use opposite directionBand");

        // Complete return flight
        VirtualTransitEngine.TickReport finalReport = null;
        for (int t = 0; t < 200; t++) {
            finalReport = engine.tick(bridge);
            if (!finalReport.completed().isEmpty()) {
                break;
            }
        }
        assertNotNull(finalReport);
        assertEquals(1, finalReport.completed().size());
        assertEquals(TransitPhase.COMPLETED, finalReport.completed().get(0).phase());
        assertTrue(engine.activeTransits().isEmpty());
        // Origin bay reservation released on completion
        assertEquals(1, registry.freeBays(origin));
    }

    @Test
    void opposingBoatsInWideWaterwayUseStarboardOffsetAndPassSmoothly() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i west = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i east = new BlockPos3i(30, SEA_Y, 0);
        WaterGrid wideChannel = flatChannel(0, 30, -2, 2); // 5 blocks wide

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        TransitRecord eastbound = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                west, east, -1, -1, false, false,
                List.of(new BlockPos3i(1, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(29, SEA_Y, 0).waterSurfaceCenter()),
                0.35, false, 0, 0
        );
        TransitRecord westbound = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                east, west, -1, -1, false, false,
                List.of(new BlockPos3i(29, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(1, SEA_Y, 0).waterSurfaceCenter()),
                0.35, false, 0, 0
        );
        engine.registerTransit(eastbound);
        engine.registerTransit(westbound);

        VirtualTransitEngine.TransitWorldBridge bridge = alwaysLoadedBridge(wideChannel);

        double minPassingDist = Double.MAX_VALUE;
        int completedCount = 0;
        for (int t = 0; t < 200; t++) {
            var report = engine.tick(bridge);
            completedCount += report.completed().size();
            if (eastbound.phase().isMoving() && westbound.phase().isMoving()) {
                double d = eastbound.currentPos().horizontalDistanceTo(westbound.currentPos());
                minPassingDist = Math.min(minPassingDist, d);
            }
            if (completedCount == 2) {
                break;
            }
        }

        assertEquals(2, completedCount, "Both boats in wide channel must complete");
        assertTrue(minPassingDist >= 1.4,
                "Opposing boats in wide waterway should pass with starboard lateral separation >= 1.4, got " + minPassingDist);
    }

    @Test
    void opposingBoatsInNarrowOneBlockCanalYieldWithoutDeadlock() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i west = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i east = new BlockPos3i(20, SEA_Y, 0);
        WaterGrid narrowCanal = flatChannel(0, 20, 0, 0); // 1 block wide!

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        // Lower UUID should yield to higher UUID
        TransitRecord lowerIdBoat = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                west, east, -1, -1, false, false,
                List.of(new BlockPos3i(1, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(19, SEA_Y, 0).waterSurfaceCenter()),
                0.35, false, 0, 0
        );
        TransitRecord higherIdBoat = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                east, west, -1, -1, false, false,
                List.of(new BlockPos3i(19, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(1, SEA_Y, 0).waterSurfaceCenter()),
                0.35, false, 0, 0
        );
        engine.registerTransit(lowerIdBoat);
        engine.registerTransit(higherIdBoat);

        VirtualTransitEngine.TransitWorldBridge bridge = alwaysLoadedBridge(narrowCanal);

        boolean lowerYielded = false;
        int completedCount = 0;
        for (int t = 0; t < 300; t++) {
            var report = engine.tick(bridge);
            completedCount += report.completed().size();
            if (lowerIdBoat.ticksWaiting() > 0) {
                lowerYielded = true;
            }
            assertEquals(0, higherIdBoat.ticksWaiting(), "Higher-ID boat should maintain right of way");
            if (completedCount == 2) {
                break;
            }
        }

        assertTrue(lowerYielded, "Lower-ID boat must yield in narrow opposing canal");
        assertEquals(2, completedCount, "Both narrow-canal opposing boats must complete without deadlock");
    }

    @Test
    void followingBoatMaintainsSafeHeadwayBehindSlowerLeader() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i west = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i east = new BlockPos3i(30, SEA_Y, 0);
        WaterGrid narrowCanal = flatChannel(0, 30, 0, 0);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        TransitRecord slowLeader = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                west, east, -1, -1, false, false,
                List.of(new BlockPos3i(6, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(29, SEA_Y, 0).waterSurfaceCenter()),
                0.25, false, 0, 0
        );
        // Faster overclocked follower starting 4 blocks behind leader
        TransitRecord fastFollower = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                VehicleKind.ELECTRIC_CARGO_BOAT,
                west, east, -1, -1, false, false,
                List.of(new BlockPos3i(2, SEA_Y, 0).waterSurfaceCenter(), new BlockPos3i(29, SEA_Y, 0).waterSurfaceCenter()),
                0.49, false, 0, 0
        );
        engine.registerTransit(slowLeader);
        engine.registerTransit(fastFollower);

        VirtualTransitEngine.TransitWorldBridge bridge = alwaysLoadedBridge(narrowCanal);

        boolean followerYielded = false;
        double minHeadway = Double.MAX_VALUE;
        int completed = 0;

        for (int t = 0; t < 250; t++) {
            var report = engine.tick(bridge);
            completed += report.completed().size();
            if (slowLeader.phase().isMoving() && fastFollower.phase().isMoving()) {
                if (fastFollower.ticksWaiting() > 0) {
                    followerYielded = true;
                }
                double d = slowLeader.currentPos().horizontalDistanceTo(fastFollower.currentPos());
                minHeadway = Math.min(minHeadway, d);
            }
            if (completed == 2) {
                break;
            }
        }

        assertEquals(2, completed);
        assertTrue(followerYielded, "Faster trailing boat must yield to maintain safe headway");
        assertTrue(minHeadway >= 2.4, "Trailing boat must never rear-end leader, got minHeadway=" + minHeadway);
    }

    @Test
    void concurrentDronesApply3dVerticalAvoidanceWhenConvergingAtSameAltitude() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i west = new BlockPos3i(0, 64, 0);
        BlockPos3i east = new BlockPos3i(30, 64, 0);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        // Force two drones onto the EXACT same cruise altitude Y=80 heading directly toward each other
        TransitRecord lowerIdDrone = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                VehicleKind.STANDARD_DRONE,
                west, east, 0, 0, false, false,
                List.of(new Vec3d(0.5, 80.0, 0.5), new Vec3d(30.5, 80.0, 0.5)),
                0.80, false, 0, 64
        );
        TransitRecord higherIdDrone = new TransitRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                VehicleKind.STANDARD_DRONE,
                east, west, 1, 1, false, false,
                List.of(new Vec3d(30.5, 80.0, 0.5), new Vec3d(0.5, 80.0, 0.5)),
                0.80, false, 0, 64
        );
        engine.registerTransit(lowerIdDrone);
        engine.registerTransit(higherIdDrone);

        VirtualTransitEngine.TransitWorldBridge bridge = alwaysLoadedBridge(null);

        double maxLowerDroneY = 0.0;
        double min3dSeparation = Double.MAX_VALUE;
        int completedCount = 0;

        for (int t = 0; t < 200; t++) {
            var report = engine.tick(bridge);
            completedCount += report.completed().size();
            if (lowerIdDrone.phase().isMoving() && higherIdDrone.phase().isMoving()) {
                maxLowerDroneY = Math.max(maxLowerDroneY, lowerIdDrone.currentPos().y());
                double dist3d = lowerIdDrone.currentPos().distanceTo(higherIdDrone.currentPos());
                min3dSeparation = Math.min(min3dSeparation, dist3d);
                // Higher-ID drone stays steadily at cruise altitude 80.0
                assertEquals(80.0, higherIdDrone.currentPos().y(), 1e-6);
            }
            if (completedCount == 2) {
                break;
            }
        }

        assertEquals(2, completedCount, "Both drones must complete safely");
        assertEquals(83.0, maxLowerDroneY, 1e-6, "Lower-ID drone must climb +3.0 Y during 3D avoidance");
        assertTrue(min3dSeparation >= 2.8,
                "3D drone separation must remain safe during passing, got " + min3dSeparation);
        // After passing, vertical avoidance offset returns cleanly to 0
        assertEquals(0.0, lowerIdDrone.verticalAvoidanceOffset(), 1e-6);
    }

    @Test
    void bayReservationPreventsOverbookingSixSlotCargoStation() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(100, 64, 0);

        registry.register(origin, StationType.CARGO_STATION, "Depot", null);
        registry.register(target, StationType.CARGO_STATION, "Busy Station", null);
        registry.setDockedDrones(target, 4); // 4 already docked -> only 2 free bays!

        assertEquals(2, registry.freeBays(target));

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        var d1 = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.randomUUID(), VehicleKind.STANDARD_DRONE, origin, 0, target, false, true,
                RfChipTier.TIER_1, UpgradeSummary.EMPTY, 10_000.0, 0, EnergyPriority.EU_FIRST, null));
        var d2 = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.randomUUID(), VehicleKind.HEAVY_DRONE, origin, 1, target, false, true,
                RfChipTier.TIER_1, UpgradeSummary.EMPTY, 10_000.0, 0, EnergyPriority.EU_FIRST, null));
        var d3 = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.randomUUID(), VehicleKind.STANDARD_DRONE, origin, 2, target, false, true,
                RfChipTier.TIER_1, UpgradeSummary.EMPTY, 10_000.0, 0, EnergyPriority.EU_FIRST, null));

        assertTrue(d1.isSuccess());
        assertTrue(d2.isSuccess());
        assertFalse(d3.isSuccess());
        assertEquals(DispatchFailure.TARGET_BAY_FULL, d3.failure());
        assertEquals(0, registry.freeBays(target));
        assertTrue(d1.transit().targetBay() != d2.transit().targetBay(),
                "Concurrent inbound drones must reserve distinct landing pad bays");
    }

    @Test
    void ridingLockAllowsPrelaunchRidingAndBlocksMidTransitBoarding() {
        // Docked / idle boat (no active transit) allows boarding
        assertTrue(VirtualTransitEngine.canPlayerBoard(null));

        StationRegistry registry = new StationRegistry();
        BlockPos3i origin = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i target = new BlockPos3i(40, SEA_Y, 0);
        registry.register(origin, StationType.SMART_PORT, "Port A", new BlockPos3i(1, SEA_Y, 0));
        registry.register(target, StationType.SMART_PORT, "Port B", new BlockPos3i(39, SEA_Y, 0));

        WaterGrid sea = flatChannel(0, 40, -2, 2);
        VirtualTransitEngine engine = new VirtualTransitEngine(registry);

        // Player boards BEFORE launch
        var dispatch = engine.dispatchBoat(new VirtualTransitEngine.BoatDispatchRequest(
                UUID.randomUUID(), origin, target, false, true, true, true, // hasPrelaunchRider = true!
                UpgradeSummary.EMPTY, 3_000.0, 0, EnergyPriority.EU_FIRST, sea, null
        ));
        assertTrue(dispatch.isSuccess());
        TransitRecord boat = dispatch.transit();
        assertTrue(boat.hasRider(), "Player seated before launch rides along");

        // While actively running outbound -> boarding is strictly BLOCKED
        assertFalse(VirtualTransitEngine.canPlayerBoard(boat));
        assertFalse(boat.canPlayerBoard());

        // Seated player keeps boat entity alive (never dematerialized) even if chunk reports unloaded
        AtomicBoolean originLoaded = new AtomicBoolean(true);
        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return originLoaded.get();
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                return true;
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                return false;
            }
        };

        var t1 = engine.tick(bridge);
        assertTrue(boat.physicalEntityLoaded());
        assertEquals(1, t1.toMaterialize().size());

        originLoaded.set(false);
        var t2 = engine.tick(bridge);
        assertTrue(boat.physicalEntityLoaded(), "Boat with seated player must NOT dematerialize");
        assertTrue(t2.toDematerialize().isEmpty());
        assertEquals(1, t2.toSync().size());

        // Drones are never rideable even when not moving
        TransitRecord drone = new TransitRecord(
                UUID.randomUUID(), VehicleKind.STANDARD_DRONE, origin, target, 0, 0, false, false,
                List.of(new Vec3d(0, 80, 0), new Vec3d(10, 80, 0)), 0.80, true, 0, 64
        );
        assertFalse(drone.hasRider());
        assertFalse(VirtualTransitEngine.canPlayerBoard(drone));
    }

    @Test
    void persistenceSnapshotRoundTripPreservesStateAndBayReservations() {
        StationRegistry reg1 = new StationRegistry("minecraft:overworld");
        BlockPos3i p1 = new BlockPos3i(0, 64, 0);
        BlockPos3i p2 = new BlockPos3i(120, 64, 40);
        reg1.register(p1, StationType.CARGO_STATION, "North Hub", null);
        reg1.register(p2, StationType.CARGO_STATION, "South Hub", null);
        reg1.setDockedDrones(p2, 2);

        VirtualTransitEngine engine1 = new VirtualTransitEngine(reg1);
        var dispatch = engine1.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000555"),
                VehicleKind.HEAVY_DRONE,
                p1,
                3,
                p2,
                true,
                true,
                RfChipTier.TIER_1,
                UpgradeSummary.builder().add("mhera:overclocker_upgrade", 2).build(),
                1_600.0,
                0,
                EnergyPriority.EU_FIRST,
                null
        ));
        assertTrue(dispatch.isSuccess());
        assertEquals(3, reg1.freeBays(p2)); // 6 - 2 docked - 1 reserved = 3

        VirtualTransitEngine.TransitWorldBridge bridge = alwaysLoadedBridge(null);
        for (int t = 0; t < 45; t++) {
            engine1.tick(bridge);
        }

        // Capture full state mid-flight
        List<StationEntry> stationSnapshots = reg1.snapshot();
        List<TransitSnapshot> transitSnapshots = engine1.captureSnapshots();
        assertEquals(1, transitSnapshots.size());
        TransitSnapshot before = transitSnapshots.get(0);

        // Simulate full server restart into brand-new registry & engine
        StationRegistry reg2 = new StationRegistry("minecraft:overworld");
        reg2.loadSnapshot(stationSnapshots);
        VirtualTransitEngine engine2 = new VirtualTransitEngine(reg2);
        engine2.loadSnapshots(transitSnapshots);

        // Exact snapshot equality & exact bay reservation restoration (NO double counting!)
        List<TransitSnapshot> afterList = engine2.captureSnapshots();
        assertEquals(1, afterList.size());
        assertEquals(before, afterList.get(0));
        assertEquals(3, reg2.freeBays(p2), "Bay reservation count must remain exact across restart");

        // Resume simulation to full round-trip completion
        int completed = 0;
        for (int t = 0; t < 1_000; t++) {
            var report = engine2.tick(bridge);
            completed += report.completed().size();
            if (completed == 1) {
                break;
            }
        }
        assertEquals(1, completed);
        assertTrue(engine2.activeTransits().isEmpty());
        assertEquals(4, reg2.freeBays(p2), "Target bay released after drone returned");
    }

    private static WaterGrid flatChannel(int minX, int maxX, int minZ, int maxZ) {
        return new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == SEA_Y && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };
    }

    private static VirtualTransitEngine.TransitWorldBridge alwaysLoadedBridge(WaterGrid waterGrid) {
        return new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                return true;
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                return true;
            }

            @Override
            public WaterGrid waterGrid() {
                return waterGrid;
            }
        };
    }
}
