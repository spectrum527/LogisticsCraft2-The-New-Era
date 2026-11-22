package com.logisticscraft.lc2.core;

/**
 * Platform-agnostic spatial query interface representing navigable water surfaces
 * and overhead clearance for Electric Smart Cargo Boat pathfinding.
 */
public interface WaterGrid {

    /**
     * Returns {@code true} if the block at {@code (x, y, z)} is a navigable water block
     * capable of floating a cargo boat hull.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return {@code true} if navigable water
     */
    boolean isNavigableWater(int x, int y, int z);

    /**
     * Returns {@code true} if the block at {@code (x, y, z)} provides passable overhead
     * headroom clearance (e.g. air or non-colliding block) for a boat floating directly beneath it.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return {@code true} if passable headroom
     */
    boolean isPassableHeadroom(int x, int y, int z);

    /**
     * Returns {@code true} if a boat can occupy the water surface block {@code (x, y, z)},
     * requiring navigable water at {@code (x, y, z)} and passable headroom at {@code (x, y + 1, z)}.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return {@code true} if boat-passable
     */
    default boolean isBoatPassable(int x, int y, int z) {
        return isNavigableWater(x, y, z) && isPassableHeadroom(x, y + 1, z);
    }

    /**
     * Convenience overload of {@link #isBoatPassable(int, int, int)}.
     *
     * @param pos water block position
     * @return {@code true} if boat-passable
     */
    default boolean isBoatPassable(BlockPos3i pos) {
        return isBoatPassable(pos.x(), pos.y(), pos.z());
    }
}
