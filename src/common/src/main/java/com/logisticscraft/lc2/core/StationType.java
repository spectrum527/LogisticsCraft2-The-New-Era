package com.logisticscraft.lc2.core;

import java.util.Optional;

/**
 * Logistics station block categories supported by LogisticsCraft².
 */
public enum StationType {

    /** Waterway Smart Port dispatching Electric Smart Cargo Boats. */
    SMART_PORT(LogisticsConstants.SMART_PORT_ID, 0),

    /** Airway Cargo Station dispatching Standard and Heavy Transport Drones. */
    CARGO_STATION(LogisticsConstants.CARGO_STATION_ID, LogisticsConstants.CARGO_STATION_DRONE_BAYS);

    private final String blockId;
    private final int maxDroneBays;

    StationType(String blockId, int maxDroneBays) {
        this.blockId = blockId;
        this.maxDroneBays = maxDroneBays;
    }

    /**
     * Returns the canonical qualified block registry ID for this station type.
     *
     * @return block registry ID
     */
    public String blockId() {
        return blockId;
    }

    /**
     * Returns the maximum number of physical drone landing bays on this station type.
     *
     * @return {@code 6} for {@link #CARGO_STATION}, {@code 0} for {@link #SMART_PORT}
     */
    public int maxDroneBays() {
        return maxDroneBays;
    }

    /**
     * Returns whether this station type can dispatch and receive the specified vehicle kind.
     *
     * @param kind vehicle kind
     * @return {@code true} if compatible
     */
    public boolean supportsVehicle(VehicleKind kind) {
        if (kind == null) {
            return false;
        }
        return kind.stationType() == this;
    }

    /**
     * Looks up a station type by its canonical qualified block ID.
     *
     * @param blockId qualified block ID
     * @return matching station type, or empty if unrecognized
     */
    public static Optional<StationType> fromBlockId(String blockId) {
        if (blockId == null) {
            return Optional.empty();
        }
        for (StationType type : values()) {
            if (type.blockId.equals(blockId)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
