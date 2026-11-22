package com.logisticscraft.lc2.core;

/**
 * Lifecycle state of an active vehicle transit simulated by {@link VirtualTransitEngine}.
 */
public enum TransitPhase {

    /** Vehicle is traveling from origin station toward target station. */
    EN_ROUTE_OUTBOUND,

    /** Vehicle has reached target station and is waiting for chunk load and cargo unloading. */
    ARRIVED_UNLOADING,

    /** Vehicle has unloaded at target and is waiting for target energy and/or a free origin bay to return. */
    WAITING_RETURN_ENERGY,

    /** Vehicle is traveling from target station back to origin station. */
    EN_ROUTE_RETURN,

    /** Transit has finished completely and the vehicle is docked at its final destination. */
    COMPLETED;

    /**
     * Returns {@code true} if the vehicle is actively moving along a route
     * ({@link #EN_ROUTE_OUTBOUND} or {@link #EN_ROUTE_RETURN}).
     *
     * @return {@code true} when moving in transit
     */
    public boolean isMoving() {
        return this == EN_ROUTE_OUTBOUND || this == EN_ROUTE_RETURN;
    }

    /**
     * Returns {@code true} if this transit has not yet reached {@link #COMPLETED}.
     *
     * @return {@code true} while active
     */
    public boolean isActive() {
        return this != COMPLETED;
    }
}
