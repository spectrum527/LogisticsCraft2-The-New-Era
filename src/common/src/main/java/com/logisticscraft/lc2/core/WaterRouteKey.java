package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Typed directed cache key identifying a verified water route between two Smart Port water berths,
 * eliminating string-substring collisions when invalidating routes touching a single berth.
 *
 * @param originBerth origin Smart Port water berth block position
 * @param targetBerth target Smart Port water berth block position
 */
public record WaterRouteKey(BlockPos3i originBerth, BlockPos3i targetBerth) {

    /** Compact constructor enforcing non-null berth coordinates. */
    public WaterRouteKey {
        Objects.requireNonNull(originBerth, "originBerth");
        Objects.requireNonNull(targetBerth, "targetBerth");
    }

    /**
     * Returns the reversed route key ({@code targetBerth -> originBerth}).
     *
     * @return reversed route key
     */
    public WaterRouteKey reversed() {
        return new WaterRouteKey(targetBerth, originBerth);
    }

    /**
     * Returns {@code true} if either endpoint equals {@code berth}.
     *
     * @param berth berth coordinate to test
     * @return {@code true} when this route originates or terminates at {@code berth}
     */
    public boolean touches(BlockPos3i berth) {
        return berth != null && (originBerth.equals(berth) || targetBerth.equals(berth));
    }
}
