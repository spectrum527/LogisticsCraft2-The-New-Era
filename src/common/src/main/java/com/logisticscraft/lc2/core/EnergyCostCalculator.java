package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Calculates EU and single-use battery dispatch costs for boats and drones, and resolves
 * strict non-hybrid payment plans respecting station energy priority.
 */
public final class EnergyCostCalculator {

    private EnergyCostCalculator() {
    }

    /**
     * Result of computing a drone dispatch cost with RF chip and distance validation.
     *
     * @param cost    computed energy cost, or {@code null} if validation failed
     * @param failure dispatch failure reason, or {@code null} if validation succeeded
     */
    public record CostCalculation(EnergyCost cost, DispatchFailure failure) {

        /**
         * Returns {@code true} if cost calculation succeeded.
         *
         * @return {@code true} on success
         */
        public boolean isValid() {
            return cost != null && failure == null;
        }

        /**
         * Creates a successful calculation result.
         *
         * @param cost calculated cost
         * @return valid result
         */
        public static CostCalculation ok(EnergyCost cost) {
            return new CostCalculation(Objects.requireNonNull(cost, "cost"), null);
        }

        /**
         * Creates a failed calculation result.
         *
         * @param failure failure reason
         * @return failed result
         */
        public static CostCalculation fail(DispatchFailure failure) {
            return new CostCalculation(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    /**
     * Returns the departure energy cost for an Electric Smart Cargo Boat.
     * One-way costs 3,000 EU or 2 single-use batteries; round-trip ({@code autoReturn = true})
     * doubles departure payment to 6,000 EU or 4 single-use batteries so return is prepaid.
     *
     * @param autoReturn whether the boat will automatically return after unloading
     * @return required boat dispatch cost
     */
    public static EnergyCost boatCost(boolean autoReturn) {
        if (autoReturn) {
            return new EnergyCost(
                    LogisticsConstants.BOAT_ROUND_TRIP_EU,
                    LogisticsConstants.BOAT_ROUND_TRIP_BATTERIES
            );
        }
        return new EnergyCost(
                LogisticsConstants.BOAT_ONE_WAY_EU,
                LogisticsConstants.BOAT_ONE_WAY_BATTERIES
        );
    }

    /**
     * Computes a single-leg drone flight energy cost by actual Manhattan distance and drone variant,
     * independent of RF chip validation (useful for target-side auto-return payment and UI previews).
     *
     * @param kind              drone vehicle kind ({@link VehicleKind#STANDARD_DRONE} or {@link VehicleKind#HEAVY_DRONE})
     * @param manhattanDistance Manhattan distance in {@code [0, 1024]}
     * @return energy cost for one flight leg
     * @throws IllegalArgumentException if {@code kind} is not a drone or {@code manhattanDistance} is out of {@code [0, 1024]}
     */
    public static EnergyCost droneDistanceCost(VehicleKind kind, int manhattanDistance) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.isDrone()) {
            throw new IllegalArgumentException("Expected a drone VehicleKind, got " + kind);
        }
        if (manhattanDistance < 0 || manhattanDistance > LogisticsConstants.DRONE_RANGE_BAND_3) {
            throw new IllegalArgumentException(
                    "Drone manhattanDistance must be within [0, "
                            + LogisticsConstants.DRONE_RANGE_BAND_3 + "], got " + manhattanDistance
            );
        }

        double baseEu;
        int baseBatteries;
        if (manhattanDistance <= LogisticsConstants.DRONE_RANGE_BAND_1) {
            baseEu = LogisticsConstants.DRONE_BAND_1_EU;
            baseBatteries = LogisticsConstants.DRONE_BAND_1_BATTERIES;
        } else if (manhattanDistance <= LogisticsConstants.DRONE_RANGE_BAND_2) {
            baseEu = LogisticsConstants.DRONE_BAND_2_EU;
            baseBatteries = LogisticsConstants.DRONE_BAND_2_BATTERIES;
        } else {
            baseEu = LogisticsConstants.DRONE_BAND_3_EU;
            baseBatteries = LogisticsConstants.DRONE_BAND_3_BATTERIES;
        }

        int multiplier = kind.energyMultiplier();
        return new EnergyCost(baseEu * multiplier, baseBatteries * multiplier);
    }

    /**
     * Validates RF chip installation, range, and drone kind, then computes the single-leg drone
     * dispatch cost for {@code manhattanDistance}. Note that drone auto-return does NOT double
     * origin payment; return flights are paid separately at the target station using the same bands.
     *
     * @param kind              drone vehicle kind
     * @param manhattanDistance actual 3D Manhattan distance between origin and target
     * @param chip              installed RF chip tier at the dispatching station
     * @return calculation result containing either the {@link EnergyCost} or a {@link DispatchFailure}
     */
    public static CostCalculation droneCost(VehicleKind kind, int manhattanDistance, RfChipTier chip) {
        if (kind == null || !kind.isDrone()) {
            return CostCalculation.fail(DispatchFailure.INVALID_VEHICLE_FOR_STATION);
        }
        if (chip == null || !chip.isInstalled()) {
            return CostCalculation.fail(DispatchFailure.NO_RF_CHIP);
        }
        if (manhattanDistance < 0) {
            throw new IllegalArgumentException("manhattanDistance must be >= 0, got " + manhattanDistance);
        }
        if (!chip.canReach(manhattanDistance)) {
            return CostCalculation.fail(DispatchFailure.OUT_OF_RF_RANGE);
        }
        return CostCalculation.ok(droneDistanceCost(kind, manhattanDistance));
    }

    /**
     * Validates positions, RF chip installation, and Manhattan range, then computes the single-leg
     * drone dispatch cost between {@code origin} and {@code target}.
     *
     * @param kind   drone vehicle kind
     * @param origin origin Cargo Station position
     * @param target target Cargo Station position
     * @param chip   installed RF chip tier at {@code origin}
     * @return calculation result containing either the {@link EnergyCost} or a {@link DispatchFailure}
     */
    public static CostCalculation droneCost(
            VehicleKind kind,
            BlockPos3i origin,
            BlockPos3i target,
            RfChipTier chip
    ) {
        Objects.requireNonNull(origin, "origin");
        if (target == null) {
            return CostCalculation.fail(DispatchFailure.NO_TARGET);
        }
        if (origin.equals(target)) {
            return CostCalculation.fail(DispatchFailure.SAME_STATION);
        }
        return droneCost(kind, origin.manhattanDistance(target), chip);
    }

    /**
     * Resolves a strict non-hybrid payment plan for {@code cost} using the station's available
     * EU buffer, single-use battery count, and {@link EnergyPriority}.
     * <p>
     * Checks the preferred source first; if it cannot cover the entire cost on its own, checks
     * the alternate source. Never performs a partial hybrid debit combining EU and batteries.
     *
     * @param cost               required dispatch cost
     * @param availableEu        station's current stored EU
     * @param availableBatteries station's current count of {@code ic2:single_use_battery}
     * @param priority           preferred payment source
     * @return resolved {@link PaymentPlan}
     */
    public static PaymentPlan resolvePayment(
            EnergyCost cost,
            double availableEu,
            int availableBatteries,
            EnergyPriority priority
    ) {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(priority, "priority");

        boolean canPayEu = availableEu >= cost.euCost();
        boolean canPayBattery = availableBatteries >= cost.batteryCost();

        if (priority == EnergyPriority.EU_FIRST) {
            if (canPayEu) {
                return PaymentPlan.eu(cost.euCost());
            }
            if (canPayBattery) {
                return PaymentPlan.battery(cost.batteryCost());
            }
            return PaymentPlan.INSUFFICIENT;
        } else {
            if (canPayBattery) {
                return PaymentPlan.battery(cost.batteryCost());
            }
            if (canPayEu) {
                return PaymentPlan.eu(cost.euCost());
            }
            return PaymentPlan.INSUFFICIENT;
        }
    }
}
