package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.UpgradeSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeSummaryTest {

    @Test
    void allNineSupportedUpgradeIdsAreRecognized() {
        List<String> expectedIds = List.of(
                "ic2:transformer_upgrade",
                "ic2:energy_storage_upgrade",
                "ic2:overclocker_upgrade",
                "ic2:ejector_upgrade",
                "ic2:advanced_ejector_upgrade",
                "ic2:pulling_upgrade",
                "ic2:advanced_pulling_upgrade",
                "ic2:redstone_inverter_upgrade",
                "lc2:chunk_loading_upgrade"
        );

        assertEquals(9, UpgradeSummary.supportedUpgradeIds().size());
        for (String id : expectedIds) {
            assertTrue(UpgradeSummary.isSupportedUpgrade(id), "Expected supported upgrade: " + id);
        }

        UpgradeSummary allNine = UpgradeSummary.fromItemIds(expectedIds);
        assertEquals(1, allNine.transformerCount());
        assertEquals(1, allNine.energyStorageCount());
        assertEquals(1, allNine.overclockerCount());
        assertEquals(1, allNine.ejectorCount());
        assertEquals(1, allNine.advancedEjectorCount());
        assertEquals(1, allNine.pullingCount());
        assertEquals(1, allNine.advancedPullingCount());
        assertEquals(1, allNine.redstoneInverterCount());
        assertEquals(1, allNine.chunkLoadingCount());
        assertEquals(9, allNine.totalUpgradeCount());
    }

    @Test
    void unsupportedItemsAreRejectedAndIgnored() {
        assertFalse(UpgradeSummary.isSupportedUpgrade(null));
        assertFalse(UpgradeSummary.isSupportedUpgrade(""));
        assertFalse(UpgradeSummary.isSupportedUpgrade("minecraft:diamond"));
        assertFalse(UpgradeSummary.isSupportedUpgrade(LogisticsConstants.SINGLE_USE_BATTERY_ID));
        assertFalse(UpgradeSummary.isSupportedUpgrade(LogisticsConstants.RF_CHIP_1_ID));
        assertFalse(UpgradeSummary.isSupportedUpgrade("ic2:fluid_ejector_upgrade"));

        UpgradeSummary summary = UpgradeSummary.fromItemCounts(Map.of(
                "minecraft:hopper", 4,
                "ic2:single_use_battery", 16,
                "lc2:rf_chip_3", 1,
                "ic2:transformer_upgrade", 2
        ));
        assertEquals(2, summary.transformerCount());
        assertEquals(2, summary.totalUpgradeCount());
    }

    @Test
    void emptySummaryMatchesBaseStationRatings() {
        UpgradeSummary empty = UpgradeSummary.EMPTY;
        assertEquals(14_400.0, empty.maxCapacityEu(), 1e-9);
        assertEquals(1, empty.inputTier());
        assertEquals(32, empty.packetLimitEu());
        assertFalse(empty.hasChunkLoader());
        assertEquals(0.0, empty.chunkLoaderStandbyDrainEuPerTick(), 1e-9);
        assertFalse(empty.shouldChunkLoad(14_400.0));
        assertFalse(empty.invertRedstone());
        assertTrue(empty.isRedstoneTriggered(true));
        assertFalse(empty.isRedstoneTriggered(false));
        assertEquals(4, empty.transferRatePerTick());
        assertEquals(1.0, empty.speedMultiplier(), 1e-9);
        assertFalse(empty.hasEjector());
        assertFalse(empty.hasPulling());
    }

    @Test
    void voltageTierAndPacketLimitScaleAndClampToTier5() {
        int[] expectedPackets = {32, 128, 512, 2048, 8192};
        for (int transformers = 0; transformers <= 4; transformers++) {
            UpgradeSummary summary = UpgradeSummary.builder()
                    .add(LogisticsConstants.UPGRADE_TRANSFORMER, transformers)
                    .build();
            assertEquals(transformers + 1, summary.inputTier());
            assertEquals(expectedPackets[transformers], summary.packetLimitEu());
        }

        // Clamped at tier 5 (8192 EU/t) even with excessive transformer upgrades
        UpgradeSummary excess = UpgradeSummary.builder()
                .add(LogisticsConstants.UPGRADE_TRANSFORMER, 12)
                .build();
        assertEquals(5, excess.inputTier());
        assertEquals(8192, excess.packetLimitEu());
    }

    @Test
    void energyStorageCapacityScalesLinearly() {
        UpgradeSummary one = UpgradeSummary.builder()
                .add(LogisticsConstants.UPGRADE_ENERGY_STORAGE, 1)
                .build();
        assertEquals(24_400.0, one.maxCapacityEu(), 1e-9);

        UpgradeSummary four = UpgradeSummary.builder()
                .add(LogisticsConstants.UPGRADE_ENERGY_STORAGE, 4)
                .build();
        assertEquals(54_400.0, four.maxCapacityEu(), 1e-9);
    }

    @Test
    void overclockerTransferRateAndSpeedMultiplierScaleAndCapCleanly() {
        UpgradeSummary one = UpgradeSummary.builder().add(LogisticsConstants.UPGRADE_OVERCLOCKER, 1).build();
        assertEquals(12, one.transferRatePerTick());
        assertEquals(1.08, one.speedMultiplier(), 1e-9);

        UpgradeSummary three = UpgradeSummary.builder().add(LogisticsConstants.UPGRADE_OVERCLOCKER, 3).build();
        assertEquals(28, three.transferRatePerTick());
        assertEquals(1.24, three.speedMultiplier(), 1e-9);

        UpgradeSummary five = UpgradeSummary.builder().add(LogisticsConstants.UPGRADE_OVERCLOCKER, 5).build();
        assertEquals(44, five.transferRatePerTick());
        assertEquals(1.40, five.speedMultiplier(), 1e-9);

        UpgradeSummary ten = UpgradeSummary.builder().add(LogisticsConstants.UPGRADE_OVERCLOCKER, 10).build();
        assertEquals(64, ten.transferRatePerTick());
        assertEquals(1.40, ten.speedMultiplier(), 1e-9);
    }

    @Test
    void redstoneInverterFlipsTriggerCondition() {
        UpgradeSummary inverted = UpgradeSummary.fromItemIds(List.of(LogisticsConstants.UPGRADE_REDSTONE_INVERTER));
        assertTrue(inverted.invertRedstone());
        assertTrue(inverted.isRedstoneTriggered(false));
        assertFalse(inverted.isRedstoneTriggered(true));
    }

    @Test
    void chunkLoaderDrainsTwoEuPerTickAndRequiresSufficientEu() {
        UpgradeSummary loader = UpgradeSummary.fromItemIds(List.of(LogisticsConstants.UPGRADE_CHUNK_LOADING));
        assertTrue(loader.hasChunkLoader());
        assertEquals(2.0, loader.chunkLoaderStandbyDrainEuPerTick(), 1e-9);
        assertFalse(loader.shouldChunkLoad(0.0));
        assertFalse(loader.shouldChunkLoad(1.99));
        assertTrue(loader.shouldChunkLoad(2.0));
        assertTrue(loader.shouldChunkLoad(14_400.0));
    }

    @Test
    void ejectorAndPullingFlagsDistinguishStandardAndAdvanced() {
        UpgradeSummary std = UpgradeSummary.fromItemIds(List.of(
                LogisticsConstants.UPGRADE_EJECTOR,
                LogisticsConstants.UPGRADE_PULLING
        ));
        assertTrue(std.hasEjector());
        assertFalse(std.hasAdvancedEjector());
        assertTrue(std.hasPulling());
        assertFalse(std.hasAdvancedPulling());

        UpgradeSummary adv = UpgradeSummary.fromItemIds(List.of(
                LogisticsConstants.UPGRADE_ADVANCED_EJECTOR,
                LogisticsConstants.UPGRADE_ADVANCED_PULLING
        ));
        assertTrue(adv.hasEjector());
        assertTrue(adv.hasAdvancedEjector());
        assertTrue(adv.hasPulling());
        assertTrue(adv.hasAdvancedPulling());
    }

    @Test
    void negativeCountsThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new UpgradeSummary(-1, 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
