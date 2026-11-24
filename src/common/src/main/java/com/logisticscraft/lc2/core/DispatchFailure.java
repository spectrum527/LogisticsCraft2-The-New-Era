package com.logisticscraft.lc2.core;

/**
 * Exhaustive set of failure reasons for vehicle dispatch, routing, and boarding checks,
 * each paired with a stable localization key.
 */
public enum DispatchFailure {

    /** No destination station or coordinates configured. */
    NO_TARGET("status.lc2.failure.no_target"),

    /** Configured target station does not exist in this dimension or has incompatible type. */
    TARGET_NOT_FOUND("status.lc2.failure.target_not_found"),

    /** Origin and target coordinates refer to the same station. */
    SAME_STATION("status.lc2.failure.same_station"),

    /** No docked boat or idle parked drone is available at the origin station. */
    NO_VEHICLE("status.lc2.failure.no_vehicle"),

    /** Origin or target Smart Port has no adjacent navigable water berth. */
    NO_WATER_BERTH("status.lc2.failure.no_water_berth"),

    /** No continuous navigable water path connects origin and target Smart Port berths. */
    WATER_DISCONNECTED("status.lc2.failure.water_disconnected"),

    /** Cargo Station has no RF Chip installed in its RF chip slot. */
    NO_RF_CHIP("status.lc2.failure.no_rf_chip"),

    /** Target Cargo Station exceeds the installed RF Chip's maximum Manhattan range. */
    OUT_OF_RF_RANGE("status.lc2.failure.out_of_rf_range"),

    /** Target Cargo Station has zero free drone bays (all 6 occupied or reserved). */
    TARGET_BAY_FULL("status.lc2.failure.target_bay_full"),

    /** Neither internal EU nor single-use batteries are sufficient for the full dispatch cost. */
    INSUFFICIENT_ENERGY("status.lc2.failure.insufficient_energy"),

    /** Redstone dispatch trigger condition (accounting for redstone inverter upgrade) is inactive. */
    REDSTONE_INACTIVE("status.lc2.failure.redstone_inactive"),

    /** Vehicle is currently active in transit and cannot be dispatched again. */
    VEHICLE_IN_TRANSIT("status.lc2.failure.vehicle_in_transit"),

    /** Vehicle kind does not match the station type. */
    INVALID_VEHICLE_FOR_STATION("status.lc2.failure.invalid_vehicle_for_station"),

    /** Player attempted to board an autonomous Electric Smart Cargo Boat while in motion. */
    BOARDING_BLOCKED_IN_TRANSIT("status.lc2.failure.boarding_blocked_in_transit"),

    /** Operation Terminal lacks the 100 EU (400 FE) required to execute a remote dispatch. */
    TERMINAL_INSUFFICIENT_ENERGY("status.lc2.failure.terminal_insufficient_energy");

    private final String translationKey;

    DispatchFailure(String translationKey) {
        this.translationKey = translationKey;
    }

    /**
     * Returns the localization key describing this failure reason for GUI status lines and chat messages.
     *
     * @return translation key
     */
    public String translationKey() {
        return translationKey;
    }
}
