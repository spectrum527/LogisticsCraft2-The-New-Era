package com.logisticscraft.lc2.core;

import java.util.List;

/**
 * Immutable output of {@link WaterPathfinder#findPath}.
 *
 * @param connected     {@code true} if a continuous navigable water route was found
 * @param gridPath      unsimplified water block path from origin berth to target berth (empty on failure)
 * @param waypoints     simplified world-space waterline waypoints for boat transit (empty on failure)
 * @param nodesExpanded total A* nodes expanded during search
 * @param failure       failure reason when {@code connected == false}, otherwise {@code null}
 */
public record WaterPathResult(
        boolean connected,
        List<BlockPos3i> gridPath,
        List<Vec3d> waypoints,
        int nodesExpanded,
        DispatchFailure failure
) {

    /**
     * Compact constructor creating defensive unmodifiable copies of path lists.
     */
    public WaterPathResult {
        gridPath = gridPath == null ? List.of() : List.copyOf(gridPath);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    /**
     * Creates a successful connected path result.
     *
     * @param gridPath      block-level water path
     * @param waypoints     simplified world-space waypoints
     * @param nodesExpanded nodes expanded by A*
     * @return connected path result
     */
    public static WaterPathResult connected(
            List<BlockPos3i> gridPath,
            List<Vec3d> waypoints,
            int nodesExpanded
    ) {
        return new WaterPathResult(true, gridPath, waypoints, nodesExpanded, null);
    }

    /**
     * Creates a failed path result.
     *
     * @param failure       failure reason
     * @param nodesExpanded nodes expanded before failure
     * @return failed path result
     */
    public static WaterPathResult failed(DispatchFailure failure, int nodesExpanded) {
        return new WaterPathResult(false, List.of(), List.of(), nodesExpanded, failure);
    }
}
