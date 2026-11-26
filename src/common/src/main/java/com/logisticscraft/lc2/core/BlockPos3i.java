package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Immutable platform-agnostic 3D integer block coordinate.
 *
 * @param x block X coordinate
 * @param y block Y coordinate
 * @param z block Z coordinate
 */
public record BlockPos3i(int x, int y, int z) implements Comparable<BlockPos3i> {

    /** Origin block position {@code (0, 0, 0)}. */
    public static final BlockPos3i ZERO = new BlockPos3i(0, 0, 0);

    /**
     * Returns a position translated by the given integer deltas.
     *
     * @param dx delta X
     * @param dy delta Y
     * @param dz delta Z
     * @return translated block position
     */
    public BlockPos3i offset(int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return this;
        }
        return new BlockPos3i(x + dx, y + dy, z + dz);
    }

    /**
     * Returns the block position 1 block above this position.
     *
     * @return block above
     */
    public BlockPos3i above() {
        return offset(0, 1, 0);
    }

    /**
     * Returns the block position {@code n} blocks above this position.
     *
     * @param n vertical offset
     * @return translated block position
     */
    public BlockPos3i above(int n) {
        return offset(0, n, 0);
    }

    /**
     * Returns the block position 1 block below this position.
     *
     * @return block below
     */
    public BlockPos3i below() {
        return offset(0, -1, 0);
    }

    /**
     * Returns the block position {@code n} blocks below this position.
     *
     * @param n downward offset
     * @return translated block position
     */
    public BlockPos3i below(int n) {
        return offset(0, -n, 0);
    }

    /**
     * Returns the block position 1 block north ({@code -Z}).
     *
     * @return north neighbor
     */
    public BlockPos3i north() {
        return offset(0, 0, -1);
    }

    /**
     * Returns the block position 1 block south ({@code +Z}).
     *
     * @return south neighbor
     */
    public BlockPos3i south() {
        return offset(0, 0, 1);
    }

    /**
     * Returns the block position 1 block east ({@code +X}).
     *
     * @return east neighbor
     */
    public BlockPos3i east() {
        return offset(1, 0, 0);
    }

    /**
     * Returns the block position 1 block west ({@code -X}).
     *
     * @return west neighbor
     */
    public BlockPos3i west() {
        return offset(-1, 0, 0);
    }

    /**
     * Computes the 3D Manhattan distance {@code |dx| + |dy| + |dz|} to {@code other},
     * using {@code long} arithmetic saturated to {@link Integer#MAX_VALUE} to prevent overflow.
     *
     * @param other target block position
     * @return non-negative Manhattan distance
     */
    public int manhattanDistance(BlockPos3i other) {
        Objects.requireNonNull(other, "other");
        long dist = Math.abs((long) x - other.x)
                + Math.abs((long) y - other.y)
                + Math.abs((long) z - other.z);
        return (int) Math.min(Integer.MAX_VALUE, dist);
    }

    /**
     * Computes the horizontal 2D Manhattan distance {@code |dx| + |dz|} to {@code other},
     * using {@code long} arithmetic saturated to {@link Integer#MAX_VALUE} to prevent overflow.
     *
     * @param other target block position
     * @return non-negative horizontal Manhattan distance
     */
    public int horizontalManhattanDistance(BlockPos3i other) {
        Objects.requireNonNull(other, "other");
        long dist = Math.abs((long) x - other.x) + Math.abs((long) z - other.z);
        return (int) Math.min(Integer.MAX_VALUE, dist);
    }

    /**
     * Computes the squared Euclidean distance to {@code other}.
     *
     * @param other target block position
     * @return squared Euclidean distance
     */
    public long distanceSquared(BlockPos3i other) {
        Objects.requireNonNull(other, "other");
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Computes the Euclidean distance to {@code other}.
     *
     * @param other target block position
     * @return Euclidean distance
     */
    public double distanceTo(BlockPos3i other) {
        return Math.sqrt(distanceSquared(other));
    }

    /**
     * Returns the center of this block in world space ({@code x + 0.5, y + 0.5, z + 0.5}).
     *
     * @return center vector
     */
    public Vec3d center() {
        return new Vec3d(x + 0.5, y + 0.5, z + 0.5);
    }

    /**
     * Returns the bottom-center of this block in world space ({@code x + 0.5, y, z + 0.5}).
     *
     * @return bottom-center vector
     */
    public Vec3d bottomCenter() {
        return new Vec3d(x + 0.5, y, z + 0.5);
    }

    /**
     * Returns the top-center of this block in world space ({@code x + 0.5, y + 1.0, z + 0.5}).
     *
     * @return top-center vector
     */
    public Vec3d topCenter() {
        return new Vec3d(x + 0.5, y + 1.0, z + 0.5);
    }

    /**
     * Returns the water surface center point used for boat navigation through this block.
     *
     * @return waterline center vector
     */
    public Vec3d waterSurfaceCenter() {
        return new Vec3d(x + 0.5, y + LogisticsConstants.BOAT_WATER_SURFACE_Y_OFFSET, z + 0.5);
    }

    /**
     * Returns the Minecraft chunk X coordinate containing this block.
     *
     * @return chunk X ({@code x >> 4})
     */
    public int chunkX() {
        return x >> 4;
    }

    /**
     * Returns the Minecraft chunk Z coordinate containing this block.
     *
     * @return chunk Z ({@code z >> 4})
     */
    public int chunkZ() {
        return z >> 4;
    }

    @Override
    public int compareTo(BlockPos3i other) {
        int cmp = Integer.compare(this.x, other.x);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.y, other.y);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.z, other.z);
    }
}
