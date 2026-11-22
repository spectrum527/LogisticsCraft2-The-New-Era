package com.logisticscraft.lc2.core;

/**
 * Required dispatch cost expressed as two mutually exclusive payment alternatives:
 * either {@code euCost} EU from the station's internal buffer or {@code batteryCost}
 * single-use batteries ({@code ic2:single_use_battery}).
 *
 * @param euCost      EU required when paying with EU
 * @param batteryCost single-use batteries required when paying with batteries
 */
public record EnergyCost(double euCost, int batteryCost) {

    /**
     * Compact constructor validating non-negative costs.
     */
    public EnergyCost {
        if (Double.isNaN(euCost) || Double.isInfinite(euCost) || euCost < 0.0) {
            throw new IllegalArgumentException("euCost must be finite and >= 0, got " + euCost);
        }
        if (batteryCost < 0) {
            throw new IllegalArgumentException("batteryCost must be >= 0, got " + batteryCost);
        }
    }
}
