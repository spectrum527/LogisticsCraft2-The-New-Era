package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Resolved non-hybrid payment decision produced by {@link EnergyCostCalculator#resolvePayment}.
 * A payment is strictly paid entirely in EU, entirely in single-use batteries, or marked insufficient.
 *
 * @param source           chosen payment source
 * @param euToSpend        EU amount to debit (positive only when {@code source == PaymentSource.EU})
 * @param batteriesToSpend single-use batteries to consume (positive only when {@code source == PaymentSource.BATTERY})
 */
public record PaymentPlan(PaymentSource source, double euToSpend, int batteriesToSpend) {

    /** Singleton plan representing inability to pay from either source. */
    public static final PaymentPlan INSUFFICIENT = new PaymentPlan(PaymentSource.INSUFFICIENT, 0.0, 0);

    /**
     * Source selected to settle a dispatch cost.
     */
    public enum PaymentSource {
        /** Pay full cost from internal EU buffer. */
        EU,
        /** Pay full cost using single-use batteries. */
        BATTERY,
        /** Neither source can cover the full cost independently. */
        INSUFFICIENT
    }

    /**
     * Compact constructor enforcing strict non-hybrid invariants.
     */
    public PaymentPlan {
        Objects.requireNonNull(source, "source");
        if (euToSpend < 0.0 || batteriesToSpend < 0) {
            throw new IllegalArgumentException("Payment amounts must be non-negative");
        }
        if (source == PaymentSource.EU && batteriesToSpend != 0) {
            throw new IllegalArgumentException("EU payment cannot also spend batteries");
        }
        if (source == PaymentSource.BATTERY && euToSpend != 0.0) {
            throw new IllegalArgumentException("Battery payment cannot also spend EU");
        }
        if (source == PaymentSource.INSUFFICIENT && (euToSpend != 0.0 || batteriesToSpend != 0)) {
            throw new IllegalArgumentException("Insufficient payment must spend zero resources");
        }
    }

    /**
     * Creates an EU-only payment plan.
     *
     * @param euToSpend EU to debit
     * @return EU payment plan
     */
    public static PaymentPlan eu(double euToSpend) {
        return new PaymentPlan(PaymentSource.EU, euToSpend, 0);
    }

    /**
     * Creates a single-use-battery-only payment plan.
     *
     * @param batteriesToSpend single-use batteries to consume
     * @return battery payment plan
     */
    public static PaymentPlan battery(int batteriesToSpend) {
        return new PaymentPlan(PaymentSource.BATTERY, 0.0, batteriesToSpend);
    }

    /**
     * Returns the {@link #INSUFFICIENT} payment plan.
     *
     * @return insufficient payment plan
     */
    public static PaymentPlan insufficient() {
        return INSUFFICIENT;
    }

    /**
     * Returns {@code true} if the dispatch cost can be paid (either by EU or by batteries).
     *
     * @return {@code true} if payable
     */
    public boolean isPayable() {
        return source != PaymentSource.INSUFFICIENT;
    }

    /**
     * Returns {@code true} if the plan pays using internal EU.
     *
     * @return {@code true} for EU payment
     */
    public boolean isEu() {
        return source == PaymentSource.EU;
    }

    /**
     * Returns {@code true} if the plan pays using single-use batteries.
     *
     * @return {@code true} for battery payment
     */
    public boolean isBattery() {
        return source == PaymentSource.BATTERY;
    }

    /**
     * Returns {@code true} if neither source can cover the cost.
     *
     * @return {@code true} when insufficient
     */
    public boolean isInsufficient() {
        return source == PaymentSource.INSUFFICIENT;
    }
}
