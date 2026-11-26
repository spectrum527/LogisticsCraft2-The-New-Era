package com.logisticscraft.lc2.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded A* 2D-water-surface pathfinder (with optional ±1 surface step tolerance),
 * bank-clearance preference weighting, obstacle-safe waypoint simplification,
 * water berth detection, and starboard (right-hand) lane offset calculation.
 */
public final class WaterPathfinder {

    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double VERTICAL_STEP_PENALTY = 0.50;
    private static final double CARDINAL_BANK_PENALTY = 0.30;
    private static final double DIAGONAL_BANK_PENALTY = 0.08;
    private static final double OUTER_BANK_PENALTY = 0.05;

    private static final int[][] CARDINALS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private static final int[][] DIAGONALS = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private WaterPathfinder() {
    }

    /**
     * Searches for a navigable water path between {@code originBerth} and {@code targetBerth}
     * using the default node expansion budget ({@value LogisticsConstants#DEFAULT_WATER_PATHFIND_MAX_NODES}).
     *
     * @param grid        water navigability grid
     * @param originBerth starting water berth block
     * @param targetBerth destination water berth block
     * @return pathfinding result with connectivity status and simplified waypoints
     */
    public static WaterPathResult findPath(WaterGrid grid, BlockPos3i originBerth, BlockPos3i targetBerth) {
        return findPath(grid, originBerth, targetBerth, LogisticsConstants.DEFAULT_WATER_PATHFIND_MAX_NODES, true);
    }

    /**
     * Searches for a navigable water path between {@code originBerth} and {@code targetBerth}
     * with a custom A* node expansion budget.
     *
     * @param grid        water navigability grid
     * @param originBerth starting water berth block
     * @param targetBerth destination water berth block
     * @param maxNodes    maximum number of A* node expansions before aborting
     * @return pathfinding result with connectivity status and simplified waypoints
     */
    public static WaterPathResult findPath(
            WaterGrid grid,
            BlockPos3i originBerth,
            BlockPos3i targetBerth,
            int maxNodes
    ) {
        return findPath(grid, originBerth, targetBerth, maxNodes, true);
    }

    /**
     * Searches for a navigable water path between {@code originBerth} and {@code targetBerth}.
     *
     * @param grid               water navigability grid
     * @param originBerth        starting water berth block
     * @param targetBerth        destination water berth block
     * @param maxNodes           maximum number of A* node expansions
     * @param allowVerticalSteps whether ±1 Y water surface steps are allowed
     * @return pathfinding result
     */
    public static WaterPathResult findPath(
            WaterGrid grid,
            BlockPos3i originBerth,
            BlockPos3i targetBerth,
            int maxNodes,
            boolean allowVerticalSteps
    ) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(originBerth, "originBerth");
        Objects.requireNonNull(targetBerth, "targetBerth");
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be > 0, got " + maxNodes);
        }

        if (!grid.isBoatPassable(originBerth) || !grid.isBoatPassable(targetBerth)) {
            return WaterPathResult.failed(DispatchFailure.NO_WATER_BERTH, 0);
        }

        if (originBerth.equals(targetBerth)) {
            List<BlockPos3i> single = List.of(originBerth);
            return WaterPathResult.connected(single, List.of(originBerth.waterSurfaceCenter()), 1);
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(
                Comparator.comparingDouble(SearchNode::f)
                        .thenComparingDouble(SearchNode::h)
                        .thenComparing(SearchNode::pos)
        );
        Map<BlockPos3i, Double> bestG = new HashMap<>();
        Map<BlockPos3i, BlockPos3i> parent = new HashMap<>();
        Set<BlockPos3i> closed = new HashSet<>();

        double startH = heuristic(originBerth, targetBerth);
        bestG.put(originBerth, 0.0);
        open.add(new SearchNode(originBerth, 0.0, startH, startH));

        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            SearchNode current = open.poll();
            BlockPos3i pos = current.pos();
            if (!closed.add(pos)) {
                continue;
            }
            expanded++;

            if (pos.equals(targetBerth)) {
                List<BlockPos3i> rawPath = reconstructPath(parent, pos);
                List<BlockPos3i> simplifiedBlocks = simplifyPath(grid, rawPath);
                List<Vec3d> waypoints = new ArrayList<>(simplifiedBlocks.size());
                for (BlockPos3i block : simplifiedBlocks) {
                    waypoints.add(block.waterSurfaceCenter());
                }
                return WaterPathResult.connected(rawPath, waypoints, expanded);
            }

            // Expand 4 cardinal neighbors (with optional +/-1 Y water surface step tolerance)
            for (int[] d : CARDINALS) {
                int nx = pos.x() + d[0];
                int nz = pos.z() + d[1];
                int ny = pos.y();

                if (grid.isBoatPassable(nx, ny, nz)) {
                    BlockPos3i next = new BlockPos3i(nx, ny, nz);
                    if (!closed.contains(next)) {
                        double tentativeG = current.g() + 1.0 + bankClearancePenalty(grid, next);
                        relaxNeighbor(next, pos, tentativeG, targetBerth, bestG, parent, open);
                    }
                } else if (allowVerticalSteps) {
                    if (grid.isBoatPassable(nx, ny + 1, nz) && grid.isPassableHeadroom(pos.x(), ny + 2, pos.z())) {
                        BlockPos3i upNext = new BlockPos3i(nx, ny + 1, nz);
                        if (!closed.contains(upNext)) {
                            double tentativeG = current.g() + 1.0 + VERTICAL_STEP_PENALTY + bankClearancePenalty(grid, upNext);
                            relaxNeighbor(upNext, pos, tentativeG, targetBerth, bestG, parent, open);
                        }
                    }
                    if (grid.isBoatPassable(nx, ny - 1, nz) && grid.isPassableHeadroom(nx, ny + 1, nz)) {
                        BlockPos3i downNext = new BlockPos3i(nx, ny - 1, nz);
                        if (!closed.contains(downNext)) {
                            double tentativeG = current.g() + 1.0 + VERTICAL_STEP_PENALTY + bankClearancePenalty(grid, downNext);
                            relaxNeighbor(downNext, pos, tentativeG, targetBerth, bestG, parent, open);
                        }
                    }
                }
            }

            // Expand 4 diagonal neighbors on the same surface Y without cutting solid corners
            for (int[] d : DIAGONALS) {
                int nx = pos.x() + d[0];
                int nz = pos.z() + d[1];
                int ny = pos.y();

                if (!grid.isBoatPassable(nx, ny, nz)) {
                    continue;
                }
                if (!grid.isBoatPassable(pos.x() + d[0], ny, pos.z())
                        || !grid.isBoatPassable(pos.x(), ny, pos.z() + d[1])) {
                    continue;
                }

                BlockPos3i next = new BlockPos3i(nx, ny, nz);
                if (closed.contains(next)) {
                    continue;
                }
                double tentativeG = current.g() + SQRT2 + bankClearancePenalty(grid, next);
                relaxNeighbor(next, pos, tentativeG, targetBerth, bestG, parent, open);
            }
        }

        return WaterPathResult.failed(DispatchFailure.WATER_DISCONNECTED, expanded);
    }

    /**
     * Auto-detects the water berth block for a Smart Port block, checking the front-facing
     * horizontal neighbor first before falling back to the remaining horizontal sides
     * (at both the port's Y and {@code Y - 1} for pier-mounted ports).
     *
     * @param grid     water grid
     * @param portPos  Smart Port block position
     * @param facingDx front facing X delta ({@code -1, 0, 1})
     * @param facingDz front facing Z delta ({@code -1, 0, 1})
     * @return detected navigable water berth position, or empty if none adjacent
     */
    public static Optional<BlockPos3i> findWaterBerth(
            WaterGrid grid,
            BlockPos3i portPos,
            int facingDx,
            int facingDz
    ) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(portPos, "portPos");

        List<int[]> dirs = new ArrayList<>(4);
        if (Math.abs(facingDx) + Math.abs(facingDz) == 1) {
            dirs.add(new int[]{facingDx, facingDz});
        }
        for (int[] d : CARDINALS) {
            if (d[0] != facingDx || d[1] != facingDz) {
                dirs.add(d);
            }
        }

        // First prefer same Y level (port flush with water), then Y - 1 (port on pier 1 block above water)
        for (int dy : new int[]{0, -1}) {
            for (int[] d : dirs) {
                BlockPos3i candidate = portPos.offset(d[0], dy, d[1]);
                if (grid.isBoatPassable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Checks whether the waterway at {@code waterPos} is wide enough ({@code >= 3} navigable blocks across
     * perpendicular to {@code travelDirection}) for boats to safely apply starboard lane offsets.
     *
     * @param grid            water grid
     * @param waterPos        current water surface block
     * @param travelDirection horizontal travel vector
     * @return {@code true} if both port and starboard adjacent perpendicular cells are boat-passable
     */
    public static boolean isWideWaterway(WaterGrid grid, BlockPos3i waterPos, Vec3d travelDirection) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(waterPos, "waterPos");
        Objects.requireNonNull(travelDirection, "travelDirection");

        Vec3d dir = travelDirection.normalizeHorizontal();
        if (dir.horizontalLengthSquared() < 1e-9) {
            return false;
        }
        // Starboard (right-hand) unit vector in Minecraft right-handed coords (+X east, +Z south): (-dirZ, 0, dirX)
        double rx = -dir.z();
        double rz = dir.x();

        int stepX = (int) Math.round(rx);
        int stepZ = (int) Math.round(rz);
        if (stepX == 0 && stepZ == 0) {
            return false;
        }

        // Require center, starboard neighbor, and port neighbor to all be navigable (width >= 3)
        boolean centerOk = grid.isBoatPassable(waterPos);
        boolean starboardOk = grid.isBoatPassable(waterPos.x() + stepX, waterPos.y(), waterPos.z() + stepZ);
        boolean portOk = grid.isBoatPassable(waterPos.x() - stepX, waterPos.y(), waterPos.z() - stepZ);
        return centerOk && starboardOk && portOk;
    }

    /**
     * Computes the starboard (right-hand) unit horizontal vector for a travel direction in
     * Minecraft's right-handed coordinate system ({@code +X} East, {@code +Z} South, {@code +Y} Up).
     * For example, heading North ({@code 0, 0, -1}) yields East ({@code +1, 0, 0}).
     *
     * @param travelDirection travel direction vector
     * @return unit horizontal starboard vector, or {@link Vec3d#ZERO} if direction has no horizontal component
     */
    public static Vec3d starboardUnitVector(Vec3d travelDirection) {
        Objects.requireNonNull(travelDirection, "travelDirection");
        Vec3d dir = travelDirection.normalizeHorizontal();
        if (dir.horizontalLengthSquared() < 1e-9) {
            return Vec3d.ZERO;
        }
        return new Vec3d(-dir.z(), 0.0, dir.x());
    }

    /**
     * Computes the lateral starboard offset delta to apply at {@code position} moving along
     * {@code travelDirection}. Returns a starboard vector of magnitude {@code offsetDistance}
     * when the channel is wide ({@code >= 3} blocks across) and the offset destination remains
     * boat-passable; otherwise returns {@link Vec3d#ZERO} (keeping the boat centered in narrow canals).
     *
     * @param position        current nominal boat position
     * @param travelDirection travel direction vector
     * @param grid            water grid (if {@code null}, returns {@link Vec3d#ZERO})
     * @param offsetDistance  desired starboard offset in blocks
     * @return starboard offset delta vector
     */
    public static Vec3d computeStarboardOffset(
            Vec3d position,
            Vec3d travelDirection,
            WaterGrid grid,
            double offsetDistance
    ) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(travelDirection, "travelDirection");
        if (grid == null || offsetDistance <= 0.0) {
            return Vec3d.ZERO;
        }

        BlockPos3i waterPos = position.toBlockPos();
        if (!isWideWaterway(grid, waterPos, travelDirection)) {
            return Vec3d.ZERO;
        }

        Vec3d starboard = starboardUnitVector(travelDirection);
        if (starboard.horizontalLengthSquared() < 1e-9) {
            return Vec3d.ZERO;
        }

        Vec3d offsetDelta = starboard.scale(offsetDistance);
        Vec3d shifted = position.add(offsetDelta);
        BlockPos3i shiftedBlock = new BlockPos3i((int) Math.floor(shifted.x()), waterPos.y(), (int) Math.floor(shifted.z()));
        if (!grid.isBoatPassable(shiftedBlock)) {
            return Vec3d.ZERO;
        }
        return offsetDelta;
    }

    private static void relaxNeighbor(
            BlockPos3i next,
            BlockPos3i from,
            double tentativeG,
            BlockPos3i target,
            Map<BlockPos3i, Double> bestG,
            Map<BlockPos3i, BlockPos3i> parent,
            PriorityQueue<SearchNode> open
    ) {
        Double existing = bestG.get(next);
        if (existing == null || tentativeG < existing) {
            bestG.put(next, tentativeG);
            parent.put(next, from);
            double h = heuristic(next, target);
            open.add(new SearchNode(next, tentativeG, h, tentativeG + h));
        }
    }

    private static double heuristic(BlockPos3i a, BlockPos3i b) {
        int dx = Math.abs(a.x() - b.x());
        int dz = Math.abs(a.z() - b.z());
        int dy = Math.abs(a.y() - b.y());
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return (max - min) + min * SQRT2 + dy * VERTICAL_STEP_PENALTY;
    }

    private static double bankClearancePenalty(WaterGrid grid, BlockPos3i pos) {
        int blockedCardinal = countBlockedCardinals(grid, pos);
        int blockedDiagonal = 0;
        for (int[] d : DIAGONALS) {
            if (!grid.isBoatPassable(pos.x() + d[0], pos.y(), pos.z() + d[1])) {
                blockedDiagonal++;
            }
        }
        double penalty = blockedCardinal * CARDINAL_BANK_PENALTY + blockedDiagonal * DIAGONAL_BANK_PENALTY;
        if (blockedCardinal == 0) {
            int blockedOuter = 0;
            for (int[] d : CARDINALS) {
                if (!grid.isBoatPassable(pos.x() + d[0] * 2, pos.y(), pos.z() + d[1] * 2)) {
                    blockedOuter++;
                }
            }
            penalty += blockedOuter * OUTER_BANK_PENALTY;
        }
        return penalty;
    }

    private static int countBlockedCardinals(WaterGrid grid, BlockPos3i pos) {
        int blocked = 0;
        for (int[] d : CARDINALS) {
            if (!grid.isBoatPassable(pos.x() + d[0], pos.y(), pos.z() + d[1])) {
                blocked++;
            }
        }
        return blocked;
    }

    private static List<BlockPos3i> reconstructPath(Map<BlockPos3i, BlockPos3i> parent, BlockPos3i end) {
        List<BlockPos3i> reversed = new ArrayList<>();
        BlockPos3i cursor = end;
        while (cursor != null) {
            reversed.add(cursor);
            cursor = parent.get(cursor);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<BlockPos3i> simplifyPath(WaterGrid grid, List<BlockPos3i> rawPath) {
        if (rawPath.size() <= 2) {
            return rawPath;
        }

        // Pass 1: Remove strictly collinear intermediate grid cells
        List<BlockPos3i> collinearReduced = new ArrayList<>();
        collinearReduced.add(rawPath.get(0));
        for (int i = 1; i < rawPath.size() - 1; i++) {
            BlockPos3i prev = collinearReduced.get(collinearReduced.size() - 1);
            BlockPos3i curr = rawPath.get(i);
            BlockPos3i next = rawPath.get(i + 1);

            int dx1 = curr.x() - prev.x();
            int dy1 = curr.y() - prev.y();
            int dz1 = curr.z() - prev.z();
            int dx2 = next.x() - curr.x();
            int dy2 = next.y() - curr.y();
            int dz2 = next.z() - curr.z();

            boolean collinear = (dy1 == 0 && dy2 == 0)
                    && (dx1 * dz2 == dz1 * dx2)
                    && (dx1 * dx2 + dz1 * dz2 > 0);
            if (!collinear) {
                collinearReduced.add(curr);
            }
        }
        collinearReduced.add(rawPath.get(rawPath.size() - 1));

        if (collinearReduced.size() <= 2) {
            return collinearReduced;
        }

        // Pass 2: Greedy line-of-sight string-pulling that preserves obstacle and bank clearance
        List<BlockPos3i> simplified = new ArrayList<>();
        int anchor = 0;
        simplified.add(collinearReduced.get(anchor));

        while (anchor < collinearReduced.size() - 1) {
            int bestNext = anchor + 1;
            for (int candidate = collinearReduced.size() - 1; candidate > anchor + 1; candidate--) {
                if (canShortcutSafely(grid, collinearReduced, anchor, candidate)) {
                    bestNext = candidate;
                    break;
                }
            }
            simplified.add(collinearReduced.get(bestNext));
            anchor = bestNext;
        }
        return simplified;
    }

    private static boolean canShortcutSafely(
            WaterGrid grid,
            List<BlockPos3i> path,
            int fromIdx,
            int toIdx
    ) {
        BlockPos3i a = path.get(fromIdx);
        BlockPos3i b = path.get(toIdx);
        if (a.y() != b.y()) {
            return false;
        }

        // Determine if the original interior stayed strictly away from banks (all interior cells open)
        boolean originalInteriorClearOfBanks = true;
        for (int k = fromIdx + 1; k < toIdx; k++) {
            BlockPos3i mid = path.get(k);
            if (mid.y() != a.y()) {
                return false;
            }
            if (countBlockedCardinals(grid, mid) > 0) {
                originalInteriorClearOfBanks = false;
            }
        }

        double ax = a.x() + 0.5;
        double az = a.z() + 0.5;
        double bx = b.x() + 0.5;
        double bz = b.z() + 0.5;
        double dist = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(4, (int) Math.ceil(dist * 4.0));

        // Hull padding ensures diagonal shortcuts do not clip solid corners
        double hullPad = 0.35;
        int y = a.y();

        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double sx = ax + (bx - ax) * t;
            double sz = az + (bz - az) * t;

            int cx = (int) Math.floor(sx);
            int cz = (int) Math.floor(sz);
            if (!grid.isBoatPassable(cx, y, cz)) {
                return false;
            }

            // Check padded footprint corners for obstacle clipping
            if (!grid.isBoatPassable((int) Math.floor(sx - hullPad), y, (int) Math.floor(sz - hullPad))
                    || !grid.isBoatPassable((int) Math.floor(sx + hullPad), y, (int) Math.floor(sz - hullPad))
                    || !grid.isBoatPassable((int) Math.floor(sx - hullPad), y, (int) Math.floor(sz + hullPad))
                    || !grid.isBoatPassable((int) Math.floor(sx + hullPad), y, (int) Math.floor(sz + hullPad))) {
                return false;
            }

            // Preserve bank-clearance choice made by A* along interior samples
            if (originalInteriorClearOfBanks) {
                double distFromStart = Math.hypot(sx - ax, sz - az);
                double distFromEnd = Math.hypot(sx - bx, sz - bz);
                if (distFromStart > 1.25 && distFromEnd > 1.25) {
                    if (countBlockedCardinals(grid, new BlockPos3i(cx, y, cz)) > 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private record SearchNode(BlockPos3i pos, double g, double h, double f) {
    }
}
