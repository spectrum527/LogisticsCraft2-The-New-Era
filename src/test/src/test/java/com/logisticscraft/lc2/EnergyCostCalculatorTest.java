package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.EnergyCostCalculator;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.PaymentPlan;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.VehicleKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyCostCalculatorTest {

    @Test
    void boatOneWayAndRoundTripCostsMatchSpec() {
        EnergyCost oneWay = EnergyCostCalculator.boatCost(false);
        assertEquals(3_000.0, oneWay.euCost(), 1e-9);
        assertEquals(2, oneWay.batteryCost());

        EnergyCost roundTrip = EnergyCostCalculator.boatCost(true);
        assertEquals(6_000.0, roundTrip.euCost(), 1e-9);
        assertEquals(4, roundTrip.batteryCost());
    }

    @Test
    void standardDroneCostsAcrossAllThreeDistanceBands() {
        // Band 1: d <= 256 -> 800 EU / 1 battery
        assertDroneCost(VehicleKind.STANDARD_DRONE, 1, RfChipTier.TIER_1, 800.0, 1);
        assertDroneCost(VehicleKind.STANDARD_DRONE, 256, RfChipTier.TIER_1, 800.0, 1);
        // Using higher-tier chip on short distance still charges by actual distance
        assertDroneCost(VehicleKind.STANDARD_DRONE, 100, RfChipTier.TIER_3, 800.0, 1);

        // Band 2: 256 < d <= 512 -> 2000 EU / 2 batteries
        assertDroneCost(VehicleKind.STANDARD_DRONE, 257, RfChipTier.TIER_2, 2_000.0, 2);
        assertDroneCost(VehicleKind.STANDARD_DRONE, 512, RfChipTier.TIER_2, 2_000.0, 2);
        assertDroneCost(VehicleKind.STANDARD_DRONE, 400, RfChipTier.TIER_3, 2_000.0, 2);

        // Band 3: 512 < d <= 1024 -> 7000 EU / 5 batteries
        assertDroneCost(VehicleKind.STANDARD_DRONE, 513, RfChipTier.TIER_3, 7_000.0, 5);
        assertDroneCost(VehicleKind.STANDARD_DRONE, 1024, RfChipTier.TIER_3, 7_000.0, 5);
    }

    @Test
    void heavyDroneCostsAreExactlyDoubleStandardAcrossAllBands() {
        assertDroneCost(VehicleKind.HEAVY_DRONE, 256, RfChipTier.TIER_1, 1_600.0, 2);
        assertDroneCost(VehicleKind.HEAVY_DRONE, 257, RfChipTier.TIER_2, 4_000.0, 4);
        assertDroneCost(VehicleKind.HEAVY_DRONE, 512, RfChipTier.TIER_2, 4_000.0, 4);
        assertDroneCost(VehicleKind.HEAVY_DRONE, 513, RfChipTier.TIER_3, 14_000.0, 10);
        assertDroneCost(VehicleKind.HEAVY_DRONE, 1024, RfChipTier.TIER_3, 14_000.0, 10);
    }

    @Test
    void droneCostUses3dManhattanDistanceAndValidatesChipAndRange() {
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        // |100| + |56| + |100| = 256 -> fits TIER_1 Band 1
        BlockPos3i exact256 = new BlockPos3i(100, 120, 100);
        var ok = EnergyCostCalculator.droneCost(VehicleKind.STANDARD_DRONE, origin, exact256, RfChipTier.TIER_1);
        assertTrue(ok.isValid());
        assertEquals(800.0, ok.cost().euCost(), 1e-9);
        assertEquals(1, ok.cost().batteryCost());

        // |100| + |57| + |100| = 257 -> out of TIER_1 range
        BlockPos3i dist257 = new BlockPos3i(100, 121, 100);
        var outTier1 = EnergyCostCalculator.droneCost(VehicleKind.STANDARD_DRONE, origin, dist257, RfChipTier.TIER_1);
        assertFalse(outTier1.isValid());
        assertEquals(DispatchFailure.OUT_OF_RF_RANGE, outTier1.failure());

        // Missing chip -> NO_RF_CHIP
        var noChip = EnergyCostCalculator.droneCost(VehicleKind.STANDARD_DRONE, 10, RfChipTier.NONE);
        assertFalse(noChip.isValid());
        assertEquals(DispatchFailure.NO_RF_CHIP, noChip.failure());

        // Exceeds TIER_2 (512) and TIER_3 (1024)
        assertEquals(DispatchFailure.OUT_OF_RF_RANGE,
                EnergyCostCalculator.droneCost(VehicleKind.HEAVY_DRONE, 513, RfChipTier.TIER_2).failure());
        assertEquals(DispatchFailure.OUT_OF_RF_RANGE,
                EnergyCostCalculator.droneCost(VehicleKind.HEAVY_DRONE, 1025, RfChipTier.TIER_3).failure());

        // Same station & invalid vehicle kind
        assertEquals(DispatchFailure.SAME_STATION,
                EnergyCostCalculator.droneCost(VehicleKind.STANDARD_DRONE, origin, origin, RfChipTier.TIER_1).failure());
        assertEquals(DispatchFailure.INVALID_VEHICLE_FOR_STATION,
                EnergyCostCalculator.droneCost(VehicleKind.ELECTRIC_CARGO_BOAT, 50, RfChipTier.TIER_1).failure());

        // Distance cost validation
        assertThrows(IllegalArgumentException.class,
                () -> EnergyCostCalculator.droneDistanceCost(VehicleKind.STANDARD_DRONE, 1025));
    }

    @Test
    void resolvePaymentHonorsPriorityAndFallbackWithoutHybridMixing() {
        EnergyCost boatOneWay = EnergyCostCalculator.boatCost(false); // 3000 EU or 2 batteries

        // Both available -> EU_FIRST picks EU
        PaymentPlan euFirst = EnergyCostCalculator.resolvePayment(boatOneWay, 5_000.0, 10, EnergyPriority.EU_FIRST);
        assertTrue(euFirst.isPayable());
        assertTrue(euFirst.isEu());
        assertEquals(3_000.0, euFirst.euToSpend(), 1e-9);
        assertEquals(0, euFirst.batteriesToSpend());

        // Both available -> BATTERY_FIRST picks batteries
        PaymentPlan batFirst = EnergyCostCalculator.resolvePayment(boatOneWay, 5_000.0, 10, EnergyPriority.BATTERY_FIRST);
        assertTrue(batFirst.isPayable());
        assertTrue(batFirst.isBattery());
        assertEquals(0.0, batFirst.euToSpend(), 1e-9);
        assertEquals(2, batFirst.batteriesToSpend());

        // EU_FIRST falls back to batteries when EU insufficient
        PaymentPlan fallbackToBat = EnergyCostCalculator.resolvePayment(boatOneWay, 2_999.9, 2, EnergyPriority.EU_FIRST);
        assertTrue(fallbackToBat.isBattery());
        assertEquals(2, fallbackToBat.batteriesToSpend());
        assertEquals(0.0, fallbackToBat.euToSpend(), 1e-9);

        // BATTERY_FIRST falls back to EU when batteries insufficient
        PaymentPlan fallbackToEu = EnergyCostCalculator.resolvePayment(boatOneWay, 3_000.0, 1, EnergyPriority.BATTERY_FIRST);
        assertTrue(fallbackToEu.isEu());
        assertEquals(3_000.0, fallbackToEu.euToSpend(), 1e-9);
        assertEquals(0, fallbackToEu.batteriesToSpend());

        // STRICT NON-HYBRID RULE: 2999 EU + 1 battery is NOT enough for (3000 EU OR 2 batteries)
        PaymentPlan noHybridEuFirst = EnergyCostCalculator.resolvePayment(boatOneWay, 2_999.0, 1, EnergyPriority.EU_FIRST);
        assertTrue(noHybridEuFirst.isInsufficient());
        assertFalse(noHybridEuFirst.isPayable());
        assertEquals(0.0, noHybridEuFirst.euToSpend(), 1e-9);
        assertEquals(0, noHybridEuFirst.batteriesToSpend());

        PaymentPlan noHybridBatFirst = EnergyCostCalculator.resolvePayment(boatOneWay, 2_999.0, 1, EnergyPriority.BATTERY_FIRST);
        assertTrue(noHybridBatFirst.isInsufficient());
    }

    private static void assertDroneCost(
            VehicleKind kind,
            int distance,
            RfChipTier chip,
            double expectedEu,
            int expectedBatteries
    ) {
        EnergyCostCalculator.CostCalculation calc = EnergyCostCalculator.droneCost(kind, distance, chip);
        assertTrue(calc.isValid(), "Expected valid cost for " + kind + " at d=" + distance + " with " + chip);
        assertNull(calc.failure());
        assertEquals(expectedEu, calc.cost().euCost(), 1e-9);
        assertEquals(expectedBatteries, calc.cost().batteryCost());
    }
}
