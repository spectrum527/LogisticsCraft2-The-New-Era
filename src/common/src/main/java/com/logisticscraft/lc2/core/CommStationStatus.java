package com.logisticscraft.lc2.core;

/**
 * Operational connectivity status for a bound Communication Base Station or adjacent logistics
 * station row shown in the Operation Terminal selection screen.
 */
public enum CommStationStatus {

    /** Communication Base Station is powered and linked to an adjacent logistics station. */
    ONLINE("status.lc2.comm.online", 0, true),

    /** Communication Base Station lacks sufficient standby power to maintain a remote link. */
    UNPOWERED("status.lc2.comm.unpowered", 1, false),

    /** Communication Base Station is powered but has no face-adjacent Smart Port or Cargo Station. */
    NO_ADJACENT_STATION("status.lc2.comm.no_station", 2, false),

    /** Chunk containing the bound Communication Base Station is currently unloaded. */
    CHUNK_UNLOADED("status.lc2.comm.chunk_unloaded", 3, false),

    /** Bound Communication Base Station resides in a different dimension than the player. */
    WRONG_DIMENSION("status.lc2.comm.wrong_dimension", 4, false),

    /** Bound block coordinate no longer contains a Communication Base Station. */
    COMM_MISSING("status.lc2.comm.missing", 5, false);

    private final String translationKey;
    private final int sortOrder;
    private final boolean connectable;

    CommStationStatus(String translationKey, int sortOrder, boolean connectable) {
        this.translationKey = translationKey;
        this.sortOrder = sortOrder;
        this.connectable = connectable;
    }

    /**
     * Returns the localization key describing this status in the Operation Terminal UI.
     *
     * @return translation key
     */
    public String translationKey() {
        return translationKey;
    }

    /**
     * Returns the deterministic UI sort priority for this status (lower values sort earlier).
     *
     * @return sort order in {@code [0, 5]}
     */
    public int sortOrder() {
        return sortOrder;
    }

    /**
     * Returns {@code true} if a player may open a remote session to a station with this status.
     *
     * @return {@code true} only for {@link #ONLINE}
     */
    public boolean isConnectable() {
        return connectable;
    }

    /**
     * Resolves a {@link CommStationStatus} from a serialized ordinal, defaulting to {@link #COMM_MISSING}.
     *
     * @param ordinal serialized enum ordinal
     * @return matching status or {@link #COMM_MISSING}
     */
    public static CommStationStatus fromOrdinal(int ordinal) {
        CommStationStatus[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return COMM_MISSING;
    }
}
