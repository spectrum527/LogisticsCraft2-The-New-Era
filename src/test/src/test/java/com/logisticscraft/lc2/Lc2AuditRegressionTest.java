package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.AirspaceRoute;
import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.EnergyPriority;
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
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.core.WaterRouteKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test suite covering core engine fixes from the LC2 audit:
 * LC2-01, LC2-03, LC2-06, LC2-08, LC2-10, LC2-18, LC2-20, and LC2-22.
 */
class Lc2AuditRegressionTest {

    // --- LC2-01: Entity materialization generation tracking ---

    @Test
    void transitGenerationAdvancesAndRoundTripsThroughSnapshot() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i origin = new BlockPos3i(0, 62, 0);
        BlockPos3i originBerth = new BlockPos3i(1, 62, 0);
        BlockPos3i target = new BlockPos3i(12, 62, 0);
        BlockPos3i targetBerth = new BlockPos3i(11, 62, 0);
        registry.register(origin, StationType.SMART_PORT, "A", originBerth);
        registry.register(target, StationType.SMART_PORT, "B", targetBerth);

        WaterGrid water = flatChannel(0, 12, -2, 2, 62);
        VirtualTransitEngine engine = new VirtualTransitEngine(registry);
        var dispatch = engine.dispatchBoat(new VirtualTransitEngine.BoatDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000a01"),
                origin,
                target,
                false,
                true,
                true,
                false,
                UpgradeSummary.EMPTY,
                3_000.0,
                0,
                EnergyPriority.EU_FIRST,
                water,
                null
        ));
        assertTrue(dispatch.isSuccess());
        TransitRecord rec = dispatch.transit();
        assertEquals(0, rec.entityGeneration());
        assertEquals(1, rec.advanceEntityGeneration());
        assertEquals(2, rec.advanceEntityGeneration());
        assertEquals(2, rec.entityGeneration());

        List<TransitSnapshot> snapshots = engine.captureSnapshots();
        assertEquals(1, snapshots.size());
        assertEquals(2, snapshots.get(0).entityGeneration());

        VirtualTransitEngine restored = new VirtualTransitEngine(registry);
        restored.loadSnapshots(snapshots);
        TransitRecord loaded = restored.getTransit(rec.id()).orElseThrow();
        assertEquals(2, loaded.entityGeneration());

        // Legacy 23-arg constructor defaults entityGeneration to 0
        TransitSnapshot legacy = new TransitSnapshot(
                rec.id(), rec.vehicleKind(), origin, target, -1, -1, false, false,
                rec.waypoints(), 0, 0.0, rec.nominalPos(), rec.currentPos(), 0.0F,
                rec.baseSpeed(), TransitPhase.EN_ROUTE_OUTBOUND, false, false, 0,
                0.0, 0.0, 0, 62
        );
        assertEquals(0, legacy.entityGeneration());
    }

    // --- LC2-03 & LC2-06: Auto-return deadlock & orphan cleanup when target or origin breaks ---

    @Test
    void autoReturnDroneCompletesImmediatelyWhenTargetStationBrokenBeforeArrival() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(32, 64, 0);
        registry.register(origin, StationType.CARGO_STATION, "Origin", null);
        registry.register(target, StationType.CARGO_STATION, "Target", null);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);
        var dispatch = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000b01"),
                VehicleKind.STANDARD_DRONE,
                origin,
                0,
                target,
                true, // autoReturn
                true,
                RfChipTier.TIER_1,
                UpgradeSummary.EMPTY,
                2_000.0,
                0,
                EnergyPriority.EU_FIRST,
                (x, z) -> 64
        ));
        assertTrue(dispatch.isSuccess());
        TransitRecord rec = dispatch.transit();

        // Target is broken mid-flight before arrival
        AtomicBoolean targetPresent = new AtomicBoolean(false);
        registry.unregister(target);

        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean isStationPresent(BlockPos3i pos, StationType expectedType) {
                if (pos.equals(target)) {
                    return targetPresent.get();
                }
                return true;
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

        TransitRecord completedRecord = null;
        for (int t = 0; t < 400; t++) {
            var report = engine.tick(bridge);
            if (!report.completed().isEmpty()) {
                completedRecord = report.completed().get(0);
                break;
            }
        }

        assertTrue(completedRecord != null, "Drone must not deadlock in WAITING_RETURN_ENERGY when target is broken");
        assertTrue(completedRecord.isReturnAborted());
        assertEquals(target, completedRecord.finalStationPos());
        assertEquals(rec.targetBay(), completedRecord.finalBay());
        assertTrue(engine.activeTransits().isEmpty());
    }

    @Test
    void autoReturnDroneAbortsCleanlyWhenTargetStationBrokenWhileWaitingReturnEnergy() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(32, 64, 0);
        registry.register(origin, StationType.CARGO_STATION, "Origin", null);
        registry.register(target, StationType.CARGO_STATION, "Target", null);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);
        var dispatch = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000b02"),
                VehicleKind.STANDARD_DRONE,
                origin,
                0,
                target,
                true,
                true,
                RfChipTier.TIER_1,
                UpgradeSummary.EMPTY,
                2_000.0,
                0,
                EnergyPriority.EU_FIRST,
                (x, z) -> 64
        ));
        assertTrue(dispatch.isSuccess());
        TransitRecord rec = dispatch.transit();

        AtomicBoolean targetPresent = new AtomicBoolean(true);
        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean isStationPresent(BlockPos3i pos, StationType expectedType) {
                return !pos.equals(target) || targetPresent.get();
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                return true;
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                return false; // unpowered target -> drone waits for return energy
            }
        };

        for (int t = 0; t < 400 && rec.phase() != TransitPhase.WAITING_RETURN_ENERGY; t++) {
            engine.tick(bridge);
        }
        assertEquals(TransitPhase.WAITING_RETURN_ENERGY, rec.phase());

        // Now break target station while drone is waiting
        targetPresent.set(false);
        registry.unregister(target);

        var report = engine.tick(bridge);
        assertEquals(1, report.completed().size());
        assertTrue(rec.isReturnAborted());
        assertEquals(target, rec.finalStationPos());
        assertTrue(engine.activeTransits().isEmpty());
    }

    @Test
    void autoReturnDroneDocksAtTargetWhenOriginStationBrokenWhileWaitingReturnEnergy() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(32, 64, 0);
        registry.register(origin, StationType.CARGO_STATION, "Origin", null);
        registry.register(target, StationType.CARGO_STATION, "Target", null);

        VirtualTransitEngine engine = new VirtualTransitEngine(registry);
        var dispatch = engine.dispatchDrone(new VirtualTransitEngine.DroneDispatchRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000b03"),
                VehicleKind.STANDARD_DRONE,
                origin,
                0,
                target,
                true,
                true,
                RfChipTier.TIER_1,
                UpgradeSummary.EMPTY,
                2_000.0,
                0,
                EnergyPriority.EU_FIRST,
                (x, z) -> 64
        ));
        assertTrue(dispatch.isSuccess());
        TransitRecord rec = dispatch.transit();

        AtomicBoolean originPresent = new AtomicBoolean(true);
        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean isStationPresent(BlockPos3i pos, StationType expectedType) {
                return !pos.equals(origin) || originPresent.get();
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

        for (int t = 0; t < 400 && rec.phase() != TransitPhase.WAITING_RETURN_ENERGY; t++) {
            engine.tick(bridge);
        }
        assertEquals(TransitPhase.WAITING_RETURN_ENERGY, rec.phase());

        // Break origin station -> drone must abort return and dock at target bay instead of deadlocking
        originPresent.set(false);
        registry.unregister(origin);

        var report = engine.tick(bridge);
        assertEquals(1, report.completed().size());
        assertTrue(rec.isReturnAborted());
        assertEquals(target, rec.finalStationPos());
        assertEquals(rec.targetBay(), rec.finalBay());
        assertFalse(registry.isBayReserved(target, rec.targetBay()));
        assertTrue(engine.activeTransits().isEmpty());
    }

    // --- LC2-08: Exact dockedBayMask persistence & bay reservation query ---

    @Test
    void stationRegistryPreservesExactDockedBayMaskAcrossReloadAndQueriesReservedBays() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i pos = new BlockPos3i(10, 64, 20);
        registry.register(pos, StationType.CARGO_STATION, "Hub", null);
        // Only bay 5 (0b100000) is physically occupied
        registry.setDockedBayMask(pos, 1 << 5);

        StationEntry snapEntry = registry.get(pos).orElseThrow();
        assertEquals(1, snapEntry.dockedDrones());
        assertEquals(1 << 5, snapEntry.dockedBayMask());

        StationRegistry reloaded = new StationRegistry("minecraft:overworld");
        reloaded.loadSnapshot(registry.snapshot());

        // Reserving 5 inbound drones must allocate bays 0..4 and never collide with docked bay 5
        Set<Integer> allocated = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            var bayOpt = reloaded.tryReserveBay(pos, UUID.randomUUID());
            assertTrue(bayOpt.isPresent(), "Expected free bay reservation #" + i);
            int bay = bayOpt.getAsInt();
            assertTrue(bay >= 0 && bay < 5, "Expected bay in 0..4, got " + bay);
            assertTrue(allocated.add(bay));
            assertTrue(reloaded.isBayReserved(pos, bay));
        }
        assertFalse(reloaded.isBayReserved(pos, 5));
        assertEquals(0b011111, reloaded.reservedBayMask(pos));
        assertTrue(reloaded.tryReserveBay(pos, UUID.randomUUID()).isEmpty(),
                "6th reservation must fail while bay 5 is docked");

        // Legacy 7-arg StationEntry constructor fills lower docked bits cleanly
        StationEntry legacy = new StationEntry(pos, StationType.CARGO_STATION, "Legacy", null, 6, 3, 0);
        assertEquals(0b000111, legacy.dockedBayMask());
    }

    // --- LC2-10: Overflow-safe Manhattan distance ---

    @Test
    void manhattanDistancesSaturateWithoutOverflowOnExtremeCoordinates() {
        BlockPos3i minCorner = new BlockPos3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        BlockPos3i maxCorner = new BlockPos3i(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, minCorner.manhattanDistance(maxCorner));
        assertEquals(Integer.MAX_VALUE, minCorner.horizontalManhattanDistance(maxCorner));

        BlockPos3i worldMin = new BlockPos3i(-30_000_000, -64, -30_000_000);
        BlockPos3i worldMax = new BlockPos3i(30_000_000, 319, 30_000_000);
        assertEquals(120_000_000, worldMin.horizontalManhattanDistance(worldMax));
        assertEquals(120_000_383, worldMin.manhattanDistance(worldMax));
    }

    // --- LC2-18: Nether ceiling & unloaded chunk height clearance ---

    @Test
    void airspaceRouterRespectsDimensionCeilingAndUnloadedChunkMargin() {
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(120, 72, 0);

        // Ceilinged dimension (e.g. Nether bedrock roof at 127 -> ceilingY = 124)
        AirspaceRouter.HeightSampler netherSampler = new AirspaceRouter.HeightSampler() {
            @Override
            public int highestSolidY(int x, int z) {
                return 80;
            }

            @Override
            public int ceilingY() {
                return 124;
            }
        };

        for (int slot = 0; slot < 6; slot++) {
            AirspaceRoute fwd = AirspaceRouter.planRoute(origin, 0, target, 0, netherSampler, slot);
            AirspaceRoute rev = AirspaceRouter.planRoute(target, 0, origin, 0, netherSampler, slot);
            assertTrue(fwd.cruiseY() < 124.0, "Forward cruiseY " + fwd.cruiseY() + " exceeded Nether ceiling!");
            assertTrue(rev.cruiseY() < 124.0, "Reverse cruiseY " + rev.cruiseY() + " exceeded Nether ceiling!");
        }

        // Unloaded middle columns must use conservative clearance instead of collapsing to minBuildHeight
        AirspaceRouter.HeightSampler unloadedMidSampler = (x, z) ->
                (x > 16 && x < 104) ? AirspaceRouter.UNLOADED_COLUMN : 64;
        AirspaceRoute route = AirspaceRouter.planRoute(origin, 0, target, 0, unloadedMidSampler, 0);
        assertTrue(route.cruiseY() >= 72.0 + 16.0,
                "Expected conservative clearance >= 88.0 over unloaded chunks, got " + route.cruiseY());
    }

    // --- LC2-20: Typed WaterRouteKey exact invalidation ---

    @Test
    void waterRouteKeyTouchesMatchesExactBerthsWithoutSubstringCollision() {
        BlockPos3i a = new BlockPos3i(100, 64, 0);
        BlockPos3i b = new BlockPos3i(-10, 64, 0);
        BlockPos3i decoy = new BlockPos3i(0, 64, 0); // "0,64,0" is a substring of "100,64,0->-10,64,0"

        WaterRouteKey key = new WaterRouteKey(a, b);
        assertTrue(key.touches(a));
        assertTrue(key.touches(b));
        assertFalse(key.touches(decoy), "Typed WaterRouteKey must never falsely match substring coordinates");
        assertEquals(new WaterRouteKey(b, a), key.reversed());
    }

    // --- LC2-22: WaterPathfinder explores both ny+1 and ny-1 independently ---

    @Test
    void waterPathfinderExploresDownwardStepEvenWhenUpwardLedgeExistsAtSameColumn() {
        Set<BlockPos3i> water = new HashSet<>();
        // Start at (0, 62, 0)
        water.add(new BlockPos3i(0, 62, 0));
        // Column x=1 has BOTH an upward dead-end ledge at y=63 AND a downward channel at y=61
        water.add(new BlockPos3i(1, 63, 0));
        water.add(new BlockPos3i(1, 61, 0));
        // Continuation at y=61 to target (3, 61, 0)
        water.add(new BlockPos3i(2, 61, 0));
        water.add(new BlockPos3i(3, 61, 0));

        WaterGrid grid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return water.contains(new BlockPos3i(x, y, z));
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return true;
            }
        };

        WaterPathResult result = WaterPathfinder.findPath(
                grid,
                new BlockPos3i(0, 62, 0),
                new BlockPos3i(3, 61, 0),
                1_024,
                true
        );
        assertTrue(result.connected(), "Pathfinder must explore ny-1 even when ny+1 is also passable");
        assertTrue(result.gridPath().contains(new BlockPos3i(1, 61, 0)));
    }

    private static WaterGrid flatChannel(int minX, int maxX, int minZ, int maxZ, int seaY) {
        return new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == seaY && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == seaY + 1;
            }
        };
    }
}
