package com.logisticscraft.lc2.core;

import java.util.List;
import java.util.Objects;

/**
 * Plans stratified 3D VTOL flight corridors for Transport Drones and Heavy Transport Drones.
 * <p>
 * Every route follows a strict VTOL profile:
 * <ol>
 *   <li>Vertical ascent from one of the 6 distinct 3x2 landing pads on the origin station roof.</li>
 *   <li>Level horizontal cruise at {@code max(originY, targetY, maxObstacleY) + 16 + directionBand + laneLayer}.</li>
 *   <li>Vertical descent onto the reserved landing pad ({@code 0..5}) on the target station roof.</li>
 * </ol>
 * Because {@code directionBand} takes {@code 0} vs {@code +4} (for opposite headings) and
 * {@code laneLayer} takes {@code (slot % 6) * 3} (multiples of 3), forward and reverse
 * corridors are mathematically disjoint across all 6 lane layers.
 */
public final class AirspaceRouter {

    /**
     * Platform-agnostic terrain height query used to ensure cruise corridors clear obstacles.
     */
    @FunctionalInterface
    public interface HeightSampler {
        /**
         * Returns the highest solid obstructing block Y coordinate at column {@code (x, z)}.
         *
         * @param x block X
         * @param z block Z
         * @return highest solid Y coordinate
         */
        int highestSolidY(int x, int z);
    }

    private static final double PAD_ROOF_Y_OFFSET = 1.05;

    /**
     * Six distinct landing pad offsets arranged in a 3x2 grid on a 1x1 block Cargo Station roof.
     */
    private static final Vec3d[] BAY_OFFSETS = {
            new Vec3d(0.20, PAD_ROOF_Y_OFFSET, 0.30),
            new Vec3d(0.50, PAD_ROOF_Y_OFFSET, 0.30),
            new Vec3d(0.80, PAD_ROOF_Y_OFFSET, 0.30),
            new Vec3d(0.20, PAD_ROOF_Y_OFFSET, 0.70),
            new Vec3d(0.50, PAD_ROOF_Y_OFFSET, 0.70),
            new Vec3d(0.80, PAD_ROOF_Y_OFFSET, 0.70)
    };

    private static final double TERRAIN_SAMPLE_STEP = 4.0;

    private AirspaceRouter() {
    }

    /**
     * Returns the local block offset on the Cargo Station roof for drone bay {@code bayIndex} ({@code 0..5}).
     *
     * @param bayIndex bay slot index in {@code [0, 5]}
     * @return local offset within the station block
     * @throws IllegalArgumentException if {@code bayIndex} is outside {@code [0, 5]}
     */
    public static Vec3d bayLandingOffset(int bayIndex) {
        validateBayIndex(bayIndex);
        return BAY_OFFSETS[bayIndex];
    }

    /**
     * Returns the world-space landing pad position for {@code bayIndex} on top of {@code stationPos}.
     *
     * @param stationPos Cargo Station block position
     * @param bayIndex   bay slot index in {@code [0, 5]}
     * @return world-space pad position
     */
    public static Vec3d bayWorldPos(BlockPos3i stationPos, int bayIndex) {
        Objects.requireNonNull(stationPos, "stationPos");
        Vec3d offset = bayLandingOffset(bayIndex);
        return new Vec3d(
                stationPos.x() + offset.x(),
                stationPos.y() + offset.y(),
                stationPos.z() + offset.z()
        );
    }

    /**
     * Computes the deterministic heading direction band ({@code 0.0} vs {@code +4.0}) so that
     * opposite flights {@code A -> B} and {@code B -> A} always receive distinct cruise bands.
     *
     * @param origin origin station position
     * @param target target station position
     * @return {@code 0.0} when {@code origin < target}, {@code 4.0} otherwise
     */
    public static double directionBand(BlockPos3i origin, BlockPos3i target) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        return origin.compareTo(target) < 0
                ? 0.0
                : LogisticsConstants.AIRSPACE_OPPOSITE_DIRECTION_BAND_OFFSET;
    }

    /**
     * Computes the concurrent airspace lane layer offset {@code (floorMod(laneSlot, 6)) * 3.0}.
     *
     * @param laneSlot concurrent flight slot identifier
     * @return vertical lane layer offset in blocks ({@code 0, 3, 6, 9, 12, or 15})
     */
    public static double laneLayer(int laneSlot) {
        int normalized = Math.floorMod(laneSlot, LogisticsConstants.AIRSPACE_LANE_LAYER_MODULO);
        return normalized * LogisticsConstants.AIRSPACE_LANE_LAYER_STEP;
    }

    /**
     * Computes the stratified cruise altitude:
     * {@code max(originY, targetY, maxObstacleY) + 16 + directionBand + laneLayer}.
     *
     * @param origin       origin station position
     * @param target       target station position
     * @param maxObstacleY highest known terrain/structure obstacle Y along corridor
     * @param laneSlot     concurrent airspace lane slot
     * @return stratified cruise altitude Y
     */
    public static double computeCruiseY(
            BlockPos3i origin,
            BlockPos3i target,
            int maxObstacleY,
            int laneSlot
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        int baseMaxY = Math.max(Math.max(origin.y(), target.y()), maxObstacleY);
        return baseMaxY
                + LogisticsConstants.AIRSPACE_BASE_ALTITUDE_CLEARANCE
                + directionBand(origin, target)
                + laneLayer(laneSlot);
    }

    /**
     * Samples the terrain along the horizontal corridor from {@code origin} to {@code target}
     * to find the maximum obstructing block Y.
     *
     * @param origin  origin station position
     * @param target  target station position
     * @param sampler height sampler (if {@code null}, returns {@code max(origin.y(), target.y())})
     * @return maximum obstacle Y along corridor
     */
    public static int sampleMaxObstacleY(BlockPos3i origin, BlockPos3i target, HeightSampler sampler) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        int maxY = Math.max(origin.y(), target.y());
        if (sampler == null) {
            return maxY;
        }
        double dx = target.x() - origin.x();
        double dz = target.z() - origin.z();
        double horizDist = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizDist / TERRAIN_SAMPLE_STEP));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            int sx = (int) Math.round(origin.x() + dx * t);
            int sz = (int) Math.round(origin.z() + dz * t);
            maxY = Math.max(maxY, sampler.highestSolidY(sx, sz));
        }
        return maxY;
    }

    /**
     * Plans a 3D VTOL drone route given an explicit {@code maxObstacleY}.
     *
     * @param origin       origin Cargo Station position
     * @param originBay    origin pad index ({@code 0..5})
     * @param target       target Cargo Station position
     * @param targetBay    target pad index ({@code 0..5})
     * @param maxObstacleY maximum obstacle Y along the horizontal corridor
     * @param laneSlot     concurrent airspace lane slot
     * @return planned {@link AirspaceRoute}
     */
    public static AirspaceRoute planRoute(
            BlockPos3i origin,
            int originBay,
            BlockPos3i target,
            int targetBay,
            int maxObstacleY,
            int laneSlot
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        validateBayIndex(originBay);
        validateBayIndex(targetBay);

        double dirBand = directionBand(origin, target);
        double layer = laneLayer(laneSlot);
        int baseMaxY = Math.max(Math.max(origin.y(), target.y()), maxObstacleY);
        double cruiseY = baseMaxY
                + LogisticsConstants.AIRSPACE_BASE_ALTITUDE_CLEARANCE
                + dirBand
                + layer;

        Vec3d startPad = bayWorldPos(origin, originBay);
        Vec3d ascendTop = new Vec3d(startPad.x(), cruiseY, startPad.z());
        Vec3d endPad = bayWorldPos(target, targetBay);
        Vec3d descendTop = new Vec3d(endPad.x(), cruiseY, endPad.z());

        List<Vec3d> waypoints = List.of(startPad, ascendTop, descendTop, endPad);
        return new AirspaceRoute(waypoints, cruiseY, dirBand, layer, originBay, targetBay);
    }

    /**
     * Plans a 3D VTOL drone route by sampling obstacle terrain heights along the corridor.
     *
     * @param origin   origin Cargo Station position
     * @param originBay origin pad index ({@code 0..5})
     * @param target   target Cargo Station position
     * @param targetBay target pad index ({@code 0..5})
     * @param sampler  optional terrain height sampler
     * @param laneSlot concurrent airspace lane slot
     * @return planned {@link AirspaceRoute}
     */
    public static AirspaceRoute planRoute(
            BlockPos3i origin,
            int originBay,
            BlockPos3i target,
            int targetBay,
            HeightSampler sampler,
            int laneSlot
    ) {
        int maxObstacleY = sampleMaxObstacleY(origin, target, sampler);
        return planRoute(origin, originBay, target, targetBay, maxObstacleY, laneSlot);
    }

    private static void validateBayIndex(int bayIndex) {
        if (bayIndex < 0 || bayIndex >= LogisticsConstants.CARGO_STATION_DRONE_BAYS) {
            throw new IllegalArgumentException(
                    "Drone bay index must be in [0, "
                            + (LogisticsConstants.CARGO_STATION_DRONE_BAYS - 1)
                            + "], got " + bayIndex
            );
        }
    }
}
