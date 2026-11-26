package com.logisticscraft.lc2.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persistence DTO representing the full runtime state of a {@link TransitRecord},
 * designed for clean NBT serialization in platform {@code SavedData} across server restarts.
 *
 * @param id                      unique transit identifier
 * @param vehicleKind             vehicle type (boat, standard drone, or heavy drone)
 * @param originPos               origin station position
 * @param targetPos               target station position
 * @param originBay               origin drone bay index ({@code 0..5}), or {@code -1} for boats
 * @param targetBay               target drone bay index ({@code 0..5}), or {@code -1} for boats
 * @param autoReturn              whether vehicle returns automatically after unloading at target
 * @param returnPrepaid           whether return leg was prepaid at origin (true for round-trip boats)
 * @param waypoints               active leg waypoint list
 * @param waypointIndex           current starting waypoint index
 * @param segmentProgress         normalized progress {@code [0, 1]} along current waypoint segment
 * @param nominalPos              nominal position on polyline before dynamic avoidance offsets
 * @param currentPos              simulated world position including avoidance offsets
 * @param yaw                     horizontal yaw in degrees
 * @param baseSpeed               configured cruise speed in blocks/tick
 * @param phase                   current transit lifecycle phase
 * @param hasRider                whether a player is seated in this vehicle (pre-launch boat boarding)
 * @param physicalEntityLoaded    whether a physical world entity is currently materialized
 * @param ticksWaiting            consecutive ticks spent waiting/yielding
 * @param verticalAvoidanceOffset active temporary vertical offset for drone 3D avoidance
 * @param lateralStarboardOffset  active temporary starboard offset for wide-waterway boat lane-keeping
 * @param laneSlot                airspace lane slot assigned for drone flights
 * @param maxObstacleY            cached maximum corridor obstacle Y for drone return route planning
 * @param entityGeneration        monotonically increasing generation stamped on the active physical entity
 */
public record TransitSnapshot(
        UUID id,
        VehicleKind vehicleKind,
        BlockPos3i originPos,
        BlockPos3i targetPos,
        int originBay,
        int targetBay,
        boolean autoReturn,
        boolean returnPrepaid,
        List<Vec3d> waypoints,
        int waypointIndex,
        double segmentProgress,
        Vec3d nominalPos,
        Vec3d currentPos,
        float yaw,
        double baseSpeed,
        TransitPhase phase,
        boolean hasRider,
        boolean physicalEntityLoaded,
        int ticksWaiting,
        double verticalAvoidanceOffset,
        double lateralStarboardOffset,
        int laneSlot,
        int maxObstacleY,
        int entityGeneration
) {

    /**
     * Compact constructor enforcing non-null fields and immutable waypoint copying.
     */
    public TransitSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vehicleKind, "vehicleKind");
        Objects.requireNonNull(originPos, "originPos");
        Objects.requireNonNull(targetPos, "targetPos");
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        Objects.requireNonNull(nominalPos, "nominalPos");
        Objects.requireNonNull(currentPos, "currentPos");
        Objects.requireNonNull(phase, "phase");
        if (waypointIndex < 0) {
            throw new IllegalArgumentException("waypointIndex must be >= 0");
        }
        if (baseSpeed <= 0.0) {
            throw new IllegalArgumentException("baseSpeed must be > 0");
        }
        entityGeneration = Math.max(0, entityGeneration);
    }

    /**
     * Legacy 23-argument constructor defaulting {@code entityGeneration} to {@code 0}.
     */
    public TransitSnapshot(
            UUID id,
            VehicleKind vehicleKind,
            BlockPos3i originPos,
            BlockPos3i targetPos,
            int originBay,
            int targetBay,
            boolean autoReturn,
            boolean returnPrepaid,
            List<Vec3d> waypoints,
            int waypointIndex,
            double segmentProgress,
            Vec3d nominalPos,
            Vec3d currentPos,
            float yaw,
            double baseSpeed,
            TransitPhase phase,
            boolean hasRider,
            boolean physicalEntityLoaded,
            int ticksWaiting,
            double verticalAvoidanceOffset,
            double lateralStarboardOffset,
            int laneSlot,
            int maxObstacleY
    ) {
        this(
                id,
                vehicleKind,
                originPos,
                targetPos,
                originBay,
                targetBay,
                autoReturn,
                returnPrepaid,
                waypoints,
                waypointIndex,
                segmentProgress,
                nominalPos,
                currentPos,
                yaw,
                baseSpeed,
                phase,
                hasRider,
                physicalEntityLoaded,
                ticksWaiting,
                verticalAvoidanceOffset,
                lateralStarboardOffset,
                laneSlot,
                maxObstacleY,
                0
        );
    }
}
