package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyUnitsTest {

    @Test
    void commAndTerminalConstantsMatchSpecification() {
        assertEquals("lc2:comm_base_station", LogisticsConstants.COMM_BASE_STATION_ID);
        assertEquals("lc2:operation_terminal", LogisticsConstants.OPERATION_TERMINAL_ID);
        assertEquals(4, LogisticsConstants.FE_PER_EU);
        assertEquals(4, EnergyUnits.FE_PER_EU);

        assertEquals(4_000.0, LogisticsConstants.COMM_BASE_CAPACITY_EU, 1e-9);
        assertEquals(16_000, EnergyUnits.euToFeFloor(LogisticsConstants.COMM_BASE_CAPACITY_EU));
        assertEquals(1, LogisticsConstants.COMM_BASE_INPUT_TIER);
        assertEquals(32, LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU);
        assertEquals(128, EnergyUnits.maxFeTransferForEuLimit(LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU));
        assertEquals(2.0, LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK, 1e-9);

        assertEquals(10_000.0, LogisticsConstants.TERMINAL_CAPACITY_EU, 1e-9);
        assertEquals(40_000, EnergyUnits.euToFeFloor(LogisticsConstants.TERMINAL_CAPACITY_EU));
        assertEquals(1, LogisticsConstants.TERMINAL_INPUT_TIER);
        assertEquals(32, LogisticsConstants.TERMINAL_PACKET_LIMIT_EU);
        assertEquals(100.0, LogisticsConstants.TERMINAL_DISPATCH_COST_EU, 1e-9);
        assertEquals(400, EnergyUnits.euToFeFloor(LogisticsConstants.TERMINAL_DISPATCH_COST_EU));
        assertEquals(256, LogisticsConstants.TERMINAL_MAX_BINDINGS);
    }

    @Test
    void euToFeAndFeToEuConversionsHandleExactFractionsAndBounds() {
        assertEquals(0, EnergyUnits.euToFeFloor(0.0));
        assertEquals(0, EnergyUnits.euToFeFloor(-5.0));
        assertEquals(0, EnergyUnits.euToFeFloor(Double.NaN));
        assertEquals(0, EnergyUnits.euToFeFloor(Double.NEGATIVE_INFINITY));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.euToFeFloor(Double.POSITIVE_INFINITY));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.euToFeFloor(1e12));

        assertEquals(0, EnergyUnits.euToFeCeil(0.0));
        assertEquals(0, EnergyUnits.euToFeCeil(-5.0));
        assertEquals(0, EnergyUnits.euToFeCeil(Double.NaN));
        assertEquals(0, EnergyUnits.euToFeCeil(Double.NEGATIVE_INFINITY));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.euToFeCeil(Double.POSITIVE_INFINITY));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.euToFeCeil(1e12));

        // Exact quarter-EU steps match integer FE cleanly
        assertEquals(1, EnergyUnits.euToFeFloor(0.25));
        assertEquals(1, EnergyUnits.euToFeCeil(0.25));
        assertEquals(3, EnergyUnits.euToFeFloor(0.75));
        assertEquals(3, EnergyUnits.euToFeCeil(0.75));
        assertEquals(4, EnergyUnits.euToFeFloor(1.0));
        assertEquals(4, EnergyUnits.euToFeCeil(1.0));

        // Non-quarter fractions round down vs up
        assertEquals(0, EnergyUnits.euToFeFloor(0.24));
        assertEquals(1, EnergyUnits.euToFeCeil(0.24));
        assertEquals(1, EnergyUnits.euToFeFloor(0.30));
        assertEquals(2, EnergyUnits.euToFeCeil(0.30));

        // FE -> EU exact conversion and negative guard
        assertEquals(0.0, EnergyUnits.feToEu(0), 1e-12);
        assertEquals(0.0, EnergyUnits.feToEu(-16), 1e-12);
        assertEquals(0.25, EnergyUnits.feToEu(1), 1e-12);
        assertEquals(0.75, EnergyUnits.feToEu(3), 1e-12);
        assertEquals(1.0, EnergyUnits.feToEu(4), 1e-12);
        assertEquals(100.0, EnergyUnits.feToEu(400), 1e-12);
        assertEquals(4_000.0, EnergyUnits.feToEu(16_000), 1e-12);

        // Packet transfer limit conversion with overflow clamp
        assertEquals(0, EnergyUnits.maxFeTransferForEuLimit(0));
        assertEquals(0, EnergyUnits.maxFeTransferForEuLimit(-32));
        assertEquals(128, EnergyUnits.maxFeTransferForEuLimit(32));
        assertEquals(512, EnergyUnits.maxFeTransferForEuLimit(128));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.maxFeTransferForEuLimit(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, EnergyUnits.maxFeTransferForEuLimit(600_000_000));
    }

    @Test
    void receiveFeIntoEuBufferEnforcesPacketCapRoomCapSimulateAndExactAccounting() {
        double maxEu = LogisticsConstants.COMM_BASE_CAPACITY_EU; // 4000 EU = 16000 FE
        int packetEu = LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU; // 32 EU = 128 FE

        // Offered > packet cap: clamped to 128 FE (32 EU)
        EnergyUnits.FeTransferResult sim = EnergyUnits.receiveFeIntoEuBuffer(0.0, maxEu, packetEu, 1_000, true);
        assertEquals(128, sim.acceptedFe());
        assertEquals(0.0, sim.newStoredEu(), 1e-12);

        EnergyUnits.FeTransferResult commit = EnergyUnits.receiveFeIntoEuBuffer(0.0, maxEu, packetEu, 1_000, false);
        assertEquals(128, commit.acceptedFe());
        assertEquals(32.0, commit.newStoredEu(), 1e-12);

        // Offered < packet cap, including sub-EU odd FE amounts (3 FE + 1 FE = 1 EU)
        EnergyUnits.FeTransferResult odd1 = EnergyUnits.receiveFeIntoEuBuffer(10.0, maxEu, packetEu, 3, false);
        assertEquals(3, odd1.acceptedFe());
        assertEquals(10.75, odd1.newStoredEu(), 1e-12);

        EnergyUnits.FeTransferResult odd2 = EnergyUnits.receiveFeIntoEuBuffer(odd1.newStoredEu(), maxEu, packetEu, 1, false);
        assertEquals(1, odd2.acceptedFe());
        assertEquals(11.0, odd2.newStoredEu(), 1e-12);

        // Near full: remaining room 0.75 EU = 3 FE -> accepts exactly 3 FE, never overflows maxEu
        EnergyUnits.FeTransferResult nearFull = EnergyUnits.receiveFeIntoEuBuffer(3_999.25, maxEu, packetEu, 128, false);
        assertEquals(3, nearFull.acceptedFe());
        assertEquals(4_000.0, nearFull.newStoredEu(), 1e-12);

        // Non-quarter fractional EU remaining (0.7 EU = 2.8 FE) -> accepts 2 FE (0.5 EU), never overflows maxEu
        EnergyUnits.FeTransferResult fractionalRoom = EnergyUnits.receiveFeIntoEuBuffer(3_999.3, maxEu, packetEu, 128, false);
        assertEquals(2, fractionalRoom.acceptedFe());
        assertEquals(3_999.8, fractionalRoom.newStoredEu(), 1e-9);
        assertTrue(fractionalRoom.newStoredEu() <= maxEu);

        // Less than 1 FE of room remaining (0.2 EU = 0.8 FE) -> accepts 0 FE, preserves current
        EnergyUnits.FeTransferResult subFeRoom = EnergyUnits.receiveFeIntoEuBuffer(3_999.8, maxEu, packetEu, 128, false);
        assertEquals(0, subFeRoom.acceptedFe());
        assertEquals(3_999.8, subFeRoom.newStoredEu(), 1e-9);

        // Already full / invalid inputs -> 0 accepted
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(4_000.0, maxEu, packetEu, 128, false).acceptedFe());
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(0.0, maxEu, packetEu, 0, false).acceptedFe());
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(0.0, maxEu, packetEu, -50, false).acceptedFe());
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(0.0, maxEu, 0, 128, false).acceptedFe());
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(0.0, 0.0, packetEu, 128, false).acceptedFe());

        // Defensive handling of negative / NaN currentEu
        EnergyUnits.FeTransferResult fromNan = EnergyUnits.receiveFeIntoEuBuffer(Double.NaN, maxEu, packetEu, 12, false);
        assertEquals(12, fromNan.acceptedFe());
        assertEquals(3.0, fromNan.newStoredEu(), 1e-12);
    }

    @Test
    void fullFeChargeCycleWithOddPacketsReachesExactCapacityWithoutLoss() {
        double storedEu = 0.0;
        double maxEu = LogisticsConstants.COMM_BASE_CAPACITY_EU; // 4000 EU = 16000 FE
        int totalAcceptedFe = 0;
        int ticks = 0;
        while (storedEu < maxEu && ticks < 5_000) {
            EnergyUnits.FeTransferResult res = EnergyUnits.receiveFeIntoEuBuffer(
                    storedEu, maxEu, LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU, 7, false);
            assertTrue(res.acceptedFe() > 0);
            assertTrue(res.newStoredEu() <= maxEu);
            totalAcceptedFe += res.acceptedFe();
            storedEu = res.newStoredEu();
            ticks++;
        }
        assertEquals(16_000, totalAcceptedFe);
        assertEquals(4_000.0, storedEu, 1e-12);
        assertEquals(0, EnergyUnits.receiveFeIntoEuBuffer(storedEu, maxEu, 32, 7, false).acceptedFe());
    }

    @Test
    void receiveEuIntoBufferClampsToCapacityAndSanitizesInputs() {
        EnergyUnits.EuTransferResult normal = EnergyUnits.receiveEuIntoBuffer(100.0, 4_000.0, 32.0);
        assertEquals(32.0, normal.acceptedEu(), 1e-12);
        assertEquals(132.0, normal.newStoredEu(), 1e-12);

        EnergyUnits.EuTransferResult capped = EnergyUnits.receiveEuIntoBuffer(3_990.0, 4_000.0, 32.0);
        assertEquals(10.0, capped.acceptedEu(), 1e-12);
        assertEquals(4_000.0, capped.newStoredEu(), 1e-12);

        EnergyUnits.EuTransferResult full = EnergyUnits.receiveEuIntoBuffer(4_000.0, 4_000.0, 32.0);
        assertEquals(0.0, full.acceptedEu(), 1e-12);
        assertEquals(4_000.0, full.newStoredEu(), 1e-12);

        EnergyUnits.EuTransferResult nonPositive = EnergyUnits.receiveEuIntoBuffer(50.0, 4_000.0, -10.0);
        assertEquals(0.0, nonPositive.acceptedEu(), 1e-12);
        assertEquals(50.0, nonPositive.newStoredEu(), 1e-12);

        EnergyUnits.EuTransferResult infinite = EnergyUnits.receiveEuIntoBuffer(50.0, 4_000.0, Double.POSITIVE_INFINITY);
        assertEquals(0.0, infinite.acceptedEu(), 1e-12);
        assertEquals(50.0, infinite.newStoredEu(), 1e-12);

        EnergyUnits.EuTransferResult nanOffer = EnergyUnits.receiveEuIntoBuffer(50.0, 4_000.0, Double.NaN);
        assertEquals(0.0, nanOffer.acceptedEu(), 1e-12);
        assertEquals(50.0, nanOffer.newStoredEu(), 1e-12);
    }

    @Test
    void terminalDispatchAffordabilityAndDeductionRespectEpsilonAndNonNegativeClamp() {
        assertTrue(EnergyUnits.canAffordTerminalDispatch(100.0));
        assertTrue(EnergyUnits.canAffordTerminalDispatch(10_000.0));
        // Within 1e-6 tolerance
        assertTrue(EnergyUnits.canAffordTerminalDispatch(99.9999995));
        assertTrue(EnergyUnits.canAffordTerminalDispatch(100.0 - 1e-6));
        // Outside tolerance
        assertFalse(EnergyUnits.canAffordTerminalDispatch(99.999));
        assertFalse(EnergyUnits.canAffordTerminalDispatch(99.99999));
        assertFalse(EnergyUnits.canAffordTerminalDispatch(0.0));
        assertFalse(EnergyUnits.canAffordTerminalDispatch(-100.0));
        assertFalse(EnergyUnits.canAffordTerminalDispatch(Double.NaN));
        assertFalse(EnergyUnits.canAffordTerminalDispatch(Double.POSITIVE_INFINITY));

        assertEquals(900.0, EnergyUnits.deductTerminalDispatch(1_000.0), 1e-12);
        assertEquals(0.0, EnergyUnits.deductTerminalDispatch(100.0), 1e-12);
        assertEquals(0.0, EnergyUnits.deductTerminalDispatch(99.9999995), 1e-12);
        assertEquals(0.0, EnergyUnits.deductTerminalDispatch(50.0), 1e-12);
        assertEquals(0.0, EnergyUnits.deductTerminalDispatch(-10.0), 1e-12);
        assertEquals(0.0, EnergyUnits.deductTerminalDispatch(Double.NaN), 1e-12);
    }

    @Test
    void transferResultRecordsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new EnergyUnits.FeTransferResult(-1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new EnergyUnits.FeTransferResult(0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new EnergyUnits.FeTransferResult(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new EnergyUnits.EuTransferResult(-1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new EnergyUnits.EuTransferResult(0.0, Double.POSITIVE_INFINITY));
    }
}
