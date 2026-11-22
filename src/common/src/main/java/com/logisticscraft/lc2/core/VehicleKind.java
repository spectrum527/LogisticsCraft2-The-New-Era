package com.logisticscraft.lc2.core;

import java.util.Optional;

/**
 * Autonomous logistics vehicle kinds supported by LogisticsCraft².
 */
public enum VehicleKind {

    /** 54-slot waterway Electric Smart Cargo Boat. */
    ELECTRIC_CARGO_BOAT(
            LogisticsConstants.ELECTRIC_CARGO_BOAT_ID,
            LogisticsConstants.BOAT_CARGO_SLOTS,
            LogisticsConstants.BOAT_BASE_SPEED_BLOCKS_PER_TICK,
            1,
            false
    ),

    /** 12-slot fast Standard Transport Drone. */
    STANDARD_DRONE(
            LogisticsConstants.TRANSPORT_DRONE_ID,
            LogisticsConstants.STANDARD_DRONE_CARGO_SLOTS,
            LogisticsConstants.STANDARD_DRONE_BASE_SPEED_BLOCKS_PER_TICK,
            1,
            true
    ),

    /** 36-slot Heavy Transport Drone (2x energy cost). */
    HEAVY_DRONE(
            LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID,
            LogisticsConstants.HEAVY_DRONE_CARGO_SLOTS,
            LogisticsConstants.HEAVY_DRONE_BASE_SPEED_BLOCKS_PER_TICK,
            LogisticsConstants.HEAVY_DRONE_ENERGY_MULTIPLIER,
            true
    );

    private final String itemId;
    private final int cargoSlots;
    private final double baseSpeed;
    private final int energyMultiplier;
    private final boolean drone;

    VehicleKind(String itemId, int cargoSlots, double baseSpeed, int energyMultiplier, boolean drone) {
        this.itemId = itemId;
        this.cargoSlots = cargoSlots;
        this.baseSpeed = baseSpeed;
        this.energyMultiplier = energyMultiplier;
        this.drone = drone;
    }

    /**
     * Returns the canonical qualified item registry ID for this vehicle kind.
     *
     * @return item registry ID
     */
    public String itemId() {
        return itemId;
    }

    /**
     * Returns the cargo inventory slot count carried by this vehicle kind.
     *
     * @return slot count (54 for boat, 12 for standard drone, 36 for heavy drone)
     */
    public int cargoSlots() {
        return cargoSlots;
    }

    /**
     * Returns the base cruise speed in blocks per tick before overclocker modifiers.
     *
     * @return base speed in blocks/tick
     */
    public double baseSpeed() {
        return baseSpeed;
    }

    /**
     * Returns the energy multiplier relative to baseline tier costs (1 for standard, 2 for heavy).
     *
     * @return energy cost multiplier
     */
    public int energyMultiplier() {
        return energyMultiplier;
    }

    /**
     * Returns {@code true} if this vehicle is an airborne drone.
     *
     * @return {@code true} for drones
     */
    public boolean isDrone() {
        return drone;
    }

    /**
     * Returns {@code true} if this vehicle is a waterway boat.
     *
     * @return {@code true} for boats
     */
    public boolean isBoat() {
        return !drone;
    }

    /**
     * Returns the station type that operates this vehicle kind.
     *
     * @return owning station type
     */
    public StationType stationType() {
        return drone ? StationType.CARGO_STATION : StationType.SMART_PORT;
    }

    /**
     * Looks up a vehicle kind by its canonical qualified item registry ID.
     *
     * @param itemId qualified item ID
     * @return matching vehicle kind, or empty if unrecognized
     */
    public static Optional<VehicleKind> fromItemId(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        for (VehicleKind kind : values()) {
            if (kind.itemId.equals(itemId)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
