package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.AirspaceRoute;
import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirspaceRouterTest {

    @Test
    void vtolFlightPathHasPureVerticalClimbLevelCruiseAndPureVerticalDescent() {
        BlockPos3i origin = new BlockPos3i(10, 64, 20);
        BlockPos3i target = new BlockPos3i(110, 70, 80);

        AirspaceRoute route = AirspaceRouter.planRoute(origin, 2, target, 5, 70, 0);
        List<Vec3d> wps = route.waypoints();
        assertEquals(4, wps.size());

        Vec3d start = wps.get(0);
        Vec3d ascendTop = wps.get(1);
        Vec3d descendTop = wps.get(2);
        Vec3d end = wps.get(3);

        // Pure vertical ascent from origin bay 2
        assertEquals(AirspaceRouter.bayWorldPos(origin, 2), start);
        assertEquals(start.x(), ascendTop.x(), 1e-9);
        assertEquals(start.z(), ascendTop.z(), 1e-9);
        assertTrue(ascendTop.y() > start.y());

        // Level horizontal cruise at exact cruiseY
        assertEquals(route.cruiseY(), ascendTop.y(), 1e-9);
        assertEquals(route.cruiseY(), descendTop.y(), 1e-9);

        // Pure vertical descent onto target bay 5
        assertEquals(AirspaceRouter.bayWorldPos(target, 5), end);
        assertEquals(end.x(), descendTop.x(), 1e-9);
        assertEquals(end.z(), descendTop.z(), 1e-9);
        assertTrue(descendTop.y() > end.y());

        // Expected altitude: max(64, 70, 70) + 16 + 0 + 0 = 86.0
        assertEquals(86.0, route.cruiseY(), 1e-9);
    }

    @Test
    void oppositeHeadingsReceiveDisjointCruiseAltitudesAcrossAllSixLaneSlots() {
        BlockPos3i a = new BlockPos3i(0, 64, 0);
        BlockPos3i b = new BlockPos3i(200, 64, 50);

        assertNotEquals(AirspaceRouter.directionBand(a, b), AirspaceRouter.directionBand(b, a));
        assertEquals(0.0, AirspaceRouter.directionBand(a, b), 1e-9);
        assertEquals(4.0, AirspaceRouter.directionBand(b, a), 1e-9);

        Set<Double> forwardAltitudes = new HashSet<>();
        Set<Double> reverseAltitudes = new HashSet<>();
        for (int slot = 0; slot < 6; slot++) {
            AirspaceRoute fwd = AirspaceRouter.planRoute(a, 0, b, 0, 64, slot);
            AirspaceRoute rev = AirspaceRouter.planRoute(b, 0, a, 0, 64, slot);
            forwardAltitudes.add(fwd.cruiseY());
            reverseAltitudes.add(rev.cruiseY());
        }

        // All 6 forward slots are distinct, all 6 reverse slots are distinct, and forward ∩ reverse = ∅
        assertEquals(6, forwardAltitudes.size());
        assertEquals(6, reverseAltitudes.size());
        for (double y : forwardAltitudes) {
            assertTrue(!reverseAltitudes.contains(y),
                    "Forward altitude " + y + " collided with a reverse altitude!");
        }
    }

    @Test
    void sixDroneBaysHaveDistinctOffsetsInsideStationRoof() {
        Set<Vec3d> offsets = new HashSet<>();
        for (int bay = 0; bay < 6; bay++) {
            Vec3d off = AirspaceRouter.bayLandingOffset(bay);
            assertTrue(off.x() > 0.0 && off.x() < 1.0, "Bay X must be inside 1x1 block roof");
            assertTrue(off.z() > 0.0 && off.z() < 1.0, "Bay Z must be inside 1x1 block roof");
            assertTrue(off.y() > 1.0 && off.y() < 1.2, "Bay Y must sit cleanly on block roof");
            assertTrue(offsets.add(off), "Duplicate pad offset for bay " + bay);
        }
        assertEquals(6, offsets.size());

        assertThrows(IllegalArgumentException.class, () -> AirspaceRouter.bayLandingOffset(-1));
        assertThrows(IllegalArgumentException.class, () -> AirspaceRouter.bayLandingOffset(6));
    }

    @Test
    void obstacleSamplerRaisesCruiseAltitudeAboveMountainPeak() {
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i target = new BlockPos3i(80, 64, 0);

        // Tall mountain ridge at x in [36..44] reaching Y = 130
        AirspaceRouter.HeightSampler mountainSampler = (x, z) -> (x >= 36 && x <= 44) ? 130 : 64;

        AirspaceRoute route = AirspaceRouter.planRoute(origin, 0, target, 1, mountainSampler, 2);
        // Expected: max(64, 64, 130) + 16 + 0 (origin < target) + 6 (slot 2 * 3) = 152.0
        assertEquals(152.0, route.cruiseY(), 1e-9);
        assertTrue(route.cruiseY() >= 130 + 16);
    }

    @Test
    void negativeAndWrappingLaneSlotsNormalizeModuloSix() {
        assertEquals(0.0, AirspaceRouter.laneLayer(0), 1e-9);
        assertEquals(0.0, AirspaceRouter.laneLayer(6), 1e-9);
        assertEquals(15.0, AirspaceRouter.laneLayer(5), 1e-9);
        assertEquals(15.0, AirspaceRouter.laneLayer(-1), 1e-9);
    }
}
