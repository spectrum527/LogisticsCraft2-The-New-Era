package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.WaterGrid;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterPathfinderTest {

    private static final int SEA_Y = 62;

    @Test
    void openWaterFindsDirectSimplifiedPath() {
        WaterGrid grid = flatWaterGrid((x, y, z) -> y == SEA_Y && x >= -50 && x <= 50 && z >= -50 && z <= 50);

        BlockPos3i start = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i end = new BlockPos3i(25, SEA_Y, 15);

        WaterPathResult result = WaterPathfinder.findPath(grid, start, end);
        assertTrue(result.connected());
        assertEquals(start, result.gridPath().get(0));
        assertEquals(end, result.gridPath().get(result.gridPath().size() - 1));
        // Line-of-sight simplification collapses completely open water to start -> end
        assertEquals(2, result.waypoints().size());
        assertEquals(start.waterSurfaceCenter(), result.waypoints().get(0));
        assertEquals(end.waterSurfaceCenter(), result.waypoints().get(1));
    }

    @Test
    void uShapedIslandDetourNeverClipsSolidBlocks() {
        // 30x30 sea with a U-shaped solid barrier blocking the direct path from (5, 10) to (20, 10)
        Set<BlockPos3i> land = new HashSet<>();
        // Back wall at x = 14, z in [5..15]
        for (int z = 5; z <= 15; z++) {
            land.add(new BlockPos3i(14, SEA_Y, z));
        }
        // Top & bottom arms of U extending left to x = 8 at z = 5 and z = 15
        for (int x = 8; x <= 14; x++) {
            land.add(new BlockPos3i(x, SEA_Y, 5));
            land.add(new BlockPos3i(x, SEA_Y, 15));
        }

        WaterGrid grid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == SEA_Y
                        && x >= 0 && x <= 25 && z >= 0 && z <= 25
                        && !land.contains(new BlockPos3i(x, y, z));
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };

        BlockPos3i start = new BlockPos3i(11, SEA_Y, 10); // inside the U pocket!
        BlockPos3i target = new BlockPos3i(20, SEA_Y, 10); // behind the U wall!

        WaterPathResult result = WaterPathfinder.findPath(grid, start, target);
        assertTrue(result.connected(), "Boat must escape U-shaped pocket and reach target");
        assertTrue(result.waypoints().size() >= 3, "Detour around U-island requires turning waypoints");

        // Verify EVERY interpolated sample along simplified waypoints stays strictly in passable water
        assertWaypointsStayInNavigableWater(grid, result.waypoints());
    }

    @Test
    void oneBlockNarrowCanalIsTraversable() {
        Set<BlockPos3i> water = new HashSet<>();
        // West lake (x in 0..3, z in 0..4)
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 4; z++) {
                water.add(new BlockPos3i(x, SEA_Y, z));
            }
        }
        // 1-block wide canal with a 90-degree bend: (4..10, z=2) then (x=10, z=2..7) then (10..14, z=7)
        for (int x = 4; x <= 10; x++) {
            water.add(new BlockPos3i(x, SEA_Y, 2));
        }
        for (int z = 2; z <= 7; z++) {
            water.add(new BlockPos3i(10, SEA_Y, z));
        }
        for (int x = 10; x <= 14; x++) {
            water.add(new BlockPos3i(x, SEA_Y, 7));
        }
        // East lake (x in 15..18, z in 5..9)
        for (int x = 15; x <= 18; x++) {
            for (int z = 5; z <= 9; z++) {
                water.add(new BlockPos3i(x, SEA_Y, z));
            }
        }

        WaterGrid grid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return water.contains(new BlockPos3i(x, y, z));
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };

        BlockPos3i start = new BlockPos3i(1, SEA_Y, 2);
        BlockPos3i end = new BlockPos3i(17, SEA_Y, 7);

        WaterPathResult result = WaterPathfinder.findPath(grid, start, end);
        assertTrue(result.connected(), "Must traverse 1-block wide bent canal");
        assertWaypointsStayInNavigableWater(grid, result.waypoints());

        // Narrow 1-block canal must NOT apply starboard offset
        Vec3d offset = WaterPathfinder.computeStarboardOffset(
                new BlockPos3i(7, SEA_Y, 2).waterSurfaceCenter(),
                new Vec3d(1, 0, 0),
                grid,
                0.75
        );
        assertEquals(Vec3d.ZERO, offset);
    }

    @Test
    void completelyBlockedDamFailsWithWaterDisconnected() {
        // Two lakes completely separated by a solid dam at x = 10
        WaterGrid grid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == SEA_Y && x >= 0 && x <= 20 && z >= 0 && z <= 10 && x != 10;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };

        WaterPathResult result = WaterPathfinder.findPath(
                grid,
                new BlockPos3i(2, SEA_Y, 5),
                new BlockPos3i(18, SEA_Y, 5)
        );
        assertFalse(result.connected());
        assertEquals(DispatchFailure.WATER_DISCONNECTED, result.failure());
        assertTrue(result.waypoints().isEmpty());
    }

    @Test
    void invalidBerthOrBlockedHeadroomFailsWithNoWaterBerth() {
        WaterGrid blockedHeadroom = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == SEA_Y;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                // Solid roof over target berth (10, 62, 0)
                return !(x == 10 && y == SEA_Y + 1 && z == 0);
            }
        };

        WaterPathResult result = WaterPathfinder.findPath(
                blockedHeadroom,
                new BlockPos3i(0, SEA_Y, 0),
                new BlockPos3i(10, SEA_Y, 0)
        );
        assertFalse(result.connected());
        assertEquals(DispatchFailure.NO_WATER_BERTH, result.failure());
    }

    @Test
    void bankClearancePreferenceSteersIntoCenterOfThreeWideChannel() {
        // 3-wide channel: x in [0..20], z in [0..2] (center is z = 1; banks at z = -1 and z = 3)
        WaterGrid channel = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == SEA_Y && x >= 0 && x <= 20 && z >= 0 && z <= 2;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };

        // Start and end right against the north bank (z = 0)
        BlockPos3i start = new BlockPos3i(0, SEA_Y, 0);
        BlockPos3i end = new BlockPos3i(20, SEA_Y, 0);

        WaterPathResult result = WaterPathfinder.findPath(channel, start, end);
        assertTrue(result.connected());

        long centerCells = result.gridPath().stream()
                .filter(p -> p.x() >= 2 && p.x() <= 18 && p.z() == 1)
                .count();
        assertTrue(centerCells >= 15,
                "Expected path to favor center lane z=1 over bank lane z=0, got centerCells=" + centerCells);

        // Simplified waypoints must preserve center-channel clearance (not snap back to z=0 bank!)
        boolean simplifiedUsesCenter = result.waypoints().stream()
                .anyMatch(wp -> Math.abs(wp.z() - 1.5) < 0.1);
        assertTrue(simplifiedUsesCenter, "Simplified waypoints must preserve center-channel preference");
    }

    @Test
    void starboardOffsetFollowsRightHandRuleInWideChannel() {
        WaterGrid wideSea = flatWaterGrid((x, y, z) -> y == SEA_Y);

        // North (0, -1) -> starboard is East (+1, 0)
        Vec3d northRight = WaterPathfinder.starboardUnitVector(new Vec3d(0, 0, -1));
        assertEquals(1.0, northRight.x(), 1e-9);
        assertEquals(0.0, northRight.z(), 1e-9);

        // East (+1, 0) -> starboard is South (0, +1)
        Vec3d eastRight = WaterPathfinder.starboardUnitVector(new Vec3d(1, 0, 0));
        assertEquals(0.0, eastRight.x(), 1e-9);
        assertEquals(1.0, eastRight.z(), 1e-9);

        // South (0, +1) -> starboard is West (-1, 0)
        Vec3d southRight = WaterPathfinder.starboardUnitVector(new Vec3d(0, 0, 1));
        assertEquals(-1.0, southRight.x(), 1e-9);
        assertEquals(0.0, southRight.z(), 1e-9);

        // West (-1, 0) -> starboard is North (0, -1)
        Vec3d westRight = WaterPathfinder.starboardUnitVector(new Vec3d(-1, 0, 0));
        assertEquals(0.0, westRight.x(), 1e-9);
        assertEquals(-1.0, westRight.z(), 1e-9);

        // Wide channel produces exact offset magnitude
        Vec3d offset = WaterPathfinder.computeStarboardOffset(
                new BlockPos3i(10, SEA_Y, 10).waterSurfaceCenter(),
                new Vec3d(1, 0, 0),
                wideSea,
                0.75
        );
        assertEquals(0.0, offset.x(), 1e-9);
        assertEquals(0.75, offset.z(), 1e-9);
    }

    @Test
    void findWaterBerthPrefersFrontFacingWaterAndSupportsPierFallback() {
        // Port at (0, 63, 0) mounted on a pier 1 block above water at Y=62, water only to East (+X) and South (+Z)
        WaterGrid pierGrid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return y == 62 && ((x == 1 && z == 0) || (x == 0 && z == 1));
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == 63;
            }
        };

        // Facing South (+Z) should pick (0, 62, 1)
        Optional<BlockPos3i> southBerth = WaterPathfinder.findWaterBerth(pierGrid, new BlockPos3i(0, 63, 0), 0, 1);
        assertEquals(Optional.of(new BlockPos3i(0, 62, 1)), southBerth);

        // Facing North (-Z, dry land) falls back to adjacent water
        Optional<BlockPos3i> fallback = WaterPathfinder.findWaterBerth(pierGrid, new BlockPos3i(0, 63, 0), 0, -1);
        assertTrue(fallback.isPresent());
    }

    @Test
    void verticalStepToleranceTraversesOneBlockWaterLockStep() {
        // West basin at Y=62 (x in 0..5), East basin at Y=63 (x in 6..10), z=0
        WaterGrid lockGrid = new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                if (z != 0) {
                    return false;
                }
                if (x >= 0 && x <= 5 && y == 62) {
                    return true;
                }
                return x >= 6 && x <= 10 && y == 63;
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return z == 0 && y >= 63;
            }
        };

        WaterPathResult result = WaterPathfinder.findPath(
                lockGrid,
                new BlockPos3i(0, 62, 0),
                new BlockPos3i(10, 63, 0)
        );
        assertTrue(result.connected(), "Boat should traverse a 1-block water level step when headroom is clear");
        assertEquals(new BlockPos3i(0, 62, 0), result.gridPath().get(0));
        assertEquals(new BlockPos3i(10, 63, 0), result.gridPath().get(result.gridPath().size() - 1));
    }

    private static WaterGrid flatWaterGrid(TriPredicate waterPredicate) {
        return new WaterGrid() {
            @Override
            public boolean isNavigableWater(int x, int y, int z) {
                return waterPredicate.test(x, y, z);
            }

            @Override
            public boolean isPassableHeadroom(int x, int y, int z) {
                return y == SEA_Y + 1;
            }
        };
    }

    private static void assertWaypointsStayInNavigableWater(WaterGrid grid, List<Vec3d> waypoints) {
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            int samples = Math.max(4, (int) Math.ceil(a.distanceTo(b) * 4.0));
            for (int s = 0; s <= samples; s++) {
                Vec3d p = a.lerp(b, (double) s / samples);
                BlockPos3i cell = p.toBlockPos();
                assertTrue(grid.isBoatPassable(cell),
                        "Interpolated point " + p + " (block " + cell + ") clipped out of navigable water!");
            }
        }
    }

    @FunctionalInterface
    private interface TriPredicate {
        boolean test(int x, int y, int z);
    }
}
