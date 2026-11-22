package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTypesAndStationRegistryTest {

    @Test
    void dispatchFailuresAllHaveUniqueNonBlankTranslationKeys() {
        Set<String> keys = new HashSet<>();
        for (DispatchFailure failure : DispatchFailure.values()) {
            assertFalse(failure.translationKey().isBlank());
            assertTrue(failure.translationKey().startsWith("status.lc2.failure."));
            assertTrue(keys.add(failure.translationKey()), "Duplicate key: " + failure.translationKey());
        }
    }

    @Test
    void domainEnumsLookupAndMetadataMatchSpec() {
        assertEquals(Optional.of(StationType.SMART_PORT), StationType.fromBlockId("lc2:smart_port"));
        assertEquals(Optional.of(StationType.CARGO_STATION), StationType.fromBlockId("lc2:cargo_station"));
        assertEquals(6, StationType.CARGO_STATION.maxDroneBays());
        assertEquals(0, StationType.SMART_PORT.maxDroneBays());

        assertEquals(54, VehicleKind.ELECTRIC_CARGO_BOAT.cargoSlots());
        assertEquals(12, VehicleKind.STANDARD_DRONE.cargoSlots());
        assertEquals(36, VehicleKind.HEAVY_DRONE.cargoSlots());
        assertEquals(0.80, VehicleKind.STANDARD_DRONE.baseSpeed(), 1e-9);
        assertEquals(0.50, VehicleKind.HEAVY_DRONE.baseSpeed(), 1e-9);
        assertEquals(Optional.of(VehicleKind.HEAVY_DRONE), VehicleKind.fromItemId(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID));

        assertEquals(RfChipTier.NONE, RfChipTier.fromItemId(null));
        assertEquals(RfChipTier.TIER_1, RfChipTier.fromItemId("lc2:rf_chip_1"));
        assertEquals(RfChipTier.TIER_2, RfChipTier.fromItemId("lc2:rf_chip_2"));
        assertEquals(RfChipTier.TIER_3, RfChipTier.fromItemId("lc2:rf_chip_3"));
        assertEquals(256, RfChipTier.TIER_1.maxRange());
        assertEquals(512, RfChipTier.TIER_2.maxRange());
        assertEquals(1024, RfChipTier.TIER_3.maxRange());

        assertEquals(EnergyPriority.BATTERY_FIRST, EnergyPriority.EU_FIRST.toggle());
        assertEquals(EnergyPriority.EU_FIRST, EnergyPriority.BATTERY_FIRST.toggle());
    }

    @Test
    void twoZoneStationInventoryConstantsMatchContract() {
        assertEquals(0, LogisticsConstants.STATION_OUTBOUND_START);
        assertEquals(27, LogisticsConstants.STATION_OUTBOUND_SLOTS);
        assertEquals(27, LogisticsConstants.STATION_STORAGE_START);
        assertEquals(27, LogisticsConstants.STATION_STORAGE_SLOTS);
        assertEquals(54, LogisticsConstants.STATION_TOTAL_CARGO_SLOTS);
        assertEquals(
                LogisticsConstants.STATION_OUTBOUND_SLOTS + LogisticsConstants.STATION_STORAGE_SLOTS,
                LogisticsConstants.STATION_TOTAL_CARGO_SLOTS);
        assertEquals(27, LogisticsConstants.SMART_PORT_BUFFER_SLOTS);
        assertEquals(27, LogisticsConstants.CARGO_STATION_BUFFER_SLOTS);
        assertEquals(59, LogisticsConstants.SMART_PORT_MACHINE_SLOTS);
        assertEquals(66, LogisticsConstants.CARGO_STATION_MACHINE_SLOTS);
    }

    @Test
    void vec3dHorizontalYawMatchesMinecraftConvention() {
        Vec3d o = Vec3d.ZERO;
        assertEquals(0.0f, Vec3d.horizontalYawDegrees(o, new Vec3d(0, 0, 10)), 1e-4f);     // South = 0
        assertEquals(90.0f, Vec3d.horizontalYawDegrees(o, new Vec3d(-10, 0, 0)), 1e-4f);   // West  = 90
        assertEquals(180.0f, Math.abs(Vec3d.horizontalYawDegrees(o, new Vec3d(0, 0, -10))), 1e-4f); // North = 180
        assertEquals(-90.0f, Vec3d.horizontalYawDegrees(o, new Vec3d(10, 0, 0)), 1e-4f);   // East  = -90
    }

    @Test
    void stationRegistryListsCompatibleTargetsSortedByDistanceAndFiltersByRfRange() {
        StationRegistry registry = new StationRegistry("minecraft:overworld");
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        BlockPos3i nearDrone = new BlockPos3i(100, 64, 50);     // Manhattan = 150
        BlockPos3i midDrone = new BlockPos3i(300, 64, 100);     // Manhattan = 400
        BlockPos3i farDrone = new BlockPos3i(800, 64, 300);     // Manhattan = 1100 (out of all chips)
        BlockPos3i port = new BlockPos3i(20, 62, 0);            // Wrong type for drone query

        registry.register(origin, StationType.CARGO_STATION, "Origin", null);
        registry.register(farDrone, StationType.CARGO_STATION, "Far", null);
        registry.register(midDrone, StationType.CARGO_STATION, "Mid", null);
        registry.register(nearDrone, StationType.CARGO_STATION, "Near", null);
        registry.register(port, StationType.SMART_PORT, "Port", new BlockPos3i(21, 62, 0));

        List<StationEntry> allDroneTargets = registry.listCompatibleTargets(origin, StationType.CARGO_STATION);
        assertEquals(List.of(nearDrone, midDrone, farDrone),
                allDroneTargets.stream().map(StationEntry::pos).toList());

        assertEquals(List.of(nearDrone),
                registry.listReachableDroneTargets(origin, RfChipTier.TIER_1).stream().map(StationEntry::pos).toList());
        assertEquals(List.of(nearDrone, midDrone),
                registry.listReachableDroneTargets(origin, RfChipTier.TIER_2).stream().map(StationEntry::pos).toList());
        assertTrue(registry.listReachableDroneTargets(origin, RfChipTier.NONE).isEmpty());
        assertTrue(StationRegistry.isWithinRange(origin, midDrone, RfChipTier.TIER_2));
        assertFalse(StationRegistry.isWithinRange(origin, midDrone, RfChipTier.TIER_1));
    }

    @Test
    void stationRegistryAtomicBayReservationAndReleaseEnforcesSixBayLimit() {
        StationRegistry registry = new StationRegistry();
        BlockPos3i pos = new BlockPos3i(10, 64, 10);
        registry.register(pos, StationType.CARGO_STATION, "Six Bays", null);

        Set<Integer> assignedBays = new HashSet<>();
        UUID firstId = UUID.randomUUID();
        OptionalInt firstBay = registry.tryReserveBay(pos, firstId);
        assertTrue(firstBay.isPresent());
        assignedBays.add(firstBay.getAsInt());

        // Idempotent for same transit ID
        assertEquals(firstBay, registry.tryReserveBay(pos, firstId));
        assertEquals(5, registry.freeBays(pos));

        for (int i = 0; i < 5; i++) {
            OptionalInt bay = registry.tryReserveBay(pos, UUID.randomUUID());
            assertTrue(bay.isPresent());
            assertTrue(assignedBays.add(bay.getAsInt()));
        }
        assertEquals(6, assignedBays.size());
        assertEquals(0, registry.freeBays(pos));

        // 7th reservation fails
        assertTrue(registry.tryReserveBay(pos, UUID.randomUUID()).isEmpty());

        // Releasing first reservation frees 1 bay
        assertTrue(registry.releaseBayReservation(pos, firstId));
        assertEquals(1, registry.freeBays(pos));
    }
}
