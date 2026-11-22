package com.logisticscraft.lc2.core;

import java.util.List;

/**
 * Planned 3D VTOL flight path for a drone between an origin Cargo Station bay
 * and a target Cargo Station bay.
 *
 * @param waypoints     ordered 3D waypoints (vertical climb, horizontal cruise, vertical descent)
 * @param cruiseY       stratified cruise altitude in world Y coordinates
 * @param directionBand heading altitude band ({@code 0.0} or {@code +4.0})
 * @param laneLayer     concurrent airspace layer altitude offset ({@code (laneSlot % 6) * 3})
 * @param originBay     origin drone bay index ({@code 0..5})
 * @param targetBay     target drone bay index ({@code 0..5})
 */
public record AirspaceRoute(
        List<Vec3d> waypoints,
        double cruiseY,
        double directionBand,
        double laneLayer,
        int originBay,
        int targetBay
) {

    /**
     * Compact constructor defensively copying waypoints.
     */
    public AirspaceRoute {
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    /**
     * Computes the total 3D polyline flight distance across all route segments.
     *
     * @return total path distance in blocks
     */
    public double totalDistance() {
        double total = 0.0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            total += waypoints.get(i).distanceTo(waypoints.get(i + 1));
        }
        return total;
    }
}
