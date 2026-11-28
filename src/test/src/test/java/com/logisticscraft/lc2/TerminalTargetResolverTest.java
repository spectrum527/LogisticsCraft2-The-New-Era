package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.core.TerminalTargetResolver.AdjacentStationInfo;
import com.logisticscraft.lc2.core.TerminalTargetResolver.CommProbeResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalTargetResolverTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    @Test
    void commStatusAndDisconnectReasonEnumsMatchSpecExactly() {
        assertEquals("status.trera.failure.terminal_insufficient_energy",
                DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY.translationKey());

        assertEquals(6, CommStationStatus.values().length);
        assertStatus(CommStationStatus.ONLINE, "status.trera.comm.online", 0, true);
        assertStatus(CommStationStatus.UNPOWERED, "status.trera.comm.unpowered", 1, false);
        assertStatus(CommStationStatus.NO_ADJACENT_STATION, "status.trera.comm.no_station", 2, false);
        assertStatus(CommStationStatus.CHUNK_UNLOADED, "status.trera.comm.chunk_unloaded", 3, false);
        assertStatus(CommStationStatus.WRONG_DIMENSION, "status.trera.comm.wrong_dimension", 4, false);
        assertStatus(CommStationStatus.COMM_MISSING, "status.trera.comm.missing", 5, false);

        assertEquals(CommStationStatus.ONLINE, CommStationStatus.fromOrdinal(0));
        assertEquals(CommStationStatus.COMM_MISSING, CommStationStatus.fromOrdinal(-1));
        assertEquals(CommStationStatus.COMM_MISSING, CommStationStatus.fromOrdinal(99));

        assertEquals(8, RemoteDisconnectReason.values().length);
        assertEquals("status.trera.ready", RemoteDisconnectReason.NONE.translationKey());
        assertFalse(RemoteDisconnectReason.NONE.isDisconnected());

        Set<String> disconnectKeys = new HashSet<>();
        disconnectKeys.add(RemoteDisconnectReason.NONE.translationKey());
        assertDisconnect(RemoteDisconnectReason.WRONG_DIMENSION, "status.trera.remote.disconnected.wrong_dimension", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.CHUNK_UNLOADED, "status.trera.remote.disconnected.chunk_unloaded", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.COMM_MISSING, "status.trera.remote.disconnected.comm_missing", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.COMM_UNPOWERED, "status.trera.remote.disconnected.comm_unpowered", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.STATION_MISSING, "status.trera.remote.disconnected.station_missing", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.NOT_ADJACENT, "status.trera.remote.disconnected.not_adjacent", disconnectKeys);
        assertDisconnect(RemoteDisconnectReason.TERMINAL_MISSING, "status.trera.remote.disconnected.terminal_missing", disconnectKeys);
        assertEquals(8, disconnectKeys.size());

        assertEquals(RemoteDisconnectReason.TERMINAL_MISSING, RemoteDisconnectReason.fromOrdinal(7));
        assertEquals(RemoteDisconnectReason.COMM_MISSING, RemoteDisconnectReason.fromOrdinal(-5));
        assertEquals(RemoteDisconnectReason.COMM_MISSING, RemoteDisconnectReason.fromOrdinal(42));
    }

    @Test
    void commBindingValidationCanonicalKeyAndDimensionMatching() {
        BlockPos3i pos = new BlockPos3i(120, 64, -340);
        CommBinding binding = new CommBinding("  minecraft:overworld  ", pos);
        assertEquals(OVERWORLD, binding.dimensionId());
        assertEquals(pos, binding.pos());
        assertEquals("minecraft:overworld@120,64,-340", binding.canonicalKey());
        assertTrue(binding.isInDimension(OVERWORLD));
        assertFalse(binding.isInDimension(NETHER));
        assertFalse(binding.isInDimension(null));

        assertEquals(Optional.of(binding), CommBinding.parseCanonicalKey(binding.canonicalKey()));
        assertEquals(Optional.empty(), CommBinding.parseCanonicalKey(null));
        assertEquals(Optional.empty(), CommBinding.parseCanonicalKey("bad-key"));
        assertEquals(Optional.empty(), CommBinding.parseCanonicalKey("minecraft:overworld@1,2"));
        assertEquals(Optional.empty(), CommBinding.parseCanonicalKey("minecraft:overworld@a,b,c"));
        assertEquals(Optional.empty(), CommBinding.parseCanonicalKey("@1,2,3"));

        assertThrows(NullPointerException.class, () -> new CommBinding(null, pos));
        assertThrows(IllegalArgumentException.class, () -> new CommBinding("", pos));
        assertThrows(IllegalArgumentException.class, () -> new CommBinding("   ", pos));
        assertThrows(NullPointerException.class, () -> new CommBinding(OVERWORLD, null));

        CommBinding otherDim = new CommBinding(NETHER, pos);
        assertTrue(binding.compareTo(otherDim) < 0);
    }

    @Test
    void terminalTargetEntryDisplayTitleValidationAndFiltering() {
        BlockPos3i commPos = new BlockPos3i(10, 64, 20);
        BlockPos3i stationPos = new BlockPos3i(11, 64, 20);

        TerminalTargetEntry namedPort = new TerminalTargetEntry(
                commPos, OVERWORLD, true, stationPos, StationType.SMART_PORT,
                "Harbor Alpha", CommStationStatus.ONLINE, 55
        );
        assertTrue(namedPort.isConnectable());
        assertEquals("Harbor Alpha", namedPort.displayTitle());
        assertEquals("(11, 64, 20)", namedPort.formattedCoordinates());
        assertTrue(namedPort.matchesFilter(null));
        assertTrue(namedPort.matchesFilter(""));
        assertTrue(namedPort.matchesFilter("   "));
        assertTrue(namedPort.matchesFilter("harbor"));
        assertTrue(namedPort.matchesFilter("ALPHA"));
        assertTrue(namedPort.matchesFilter("11, 64, 20"));
        assertTrue(namedPort.matchesFilter("11,64,20"));
        assertTrue(namedPort.matchesFilter("11 64 20"));
        assertTrue(namedPort.matchesFilter("smart_port"));
        assertTrue(namedPort.matchesFilter("SMART PORT"));
        assertTrue(namedPort.matchesFilter("trera:smart_port"));
        // Must not false-match "mine" from "minecraft:overworld"
        assertFalse(namedPort.matchesFilter("mine"));
        assertFalse(namedPort.matchesFilter("cargo"));

        TerminalTargetEntry unnamedCargo = new TerminalTargetEntry(
                commPos, OVERWORLD, true, new BlockPos3i(10, 65, 20), StationType.CARGO_STATION,
                "   ", CommStationStatus.UNPOWERED, 56
        );
        assertFalse(unnamedCargo.isConnectable());
        assertEquals("(10, 65, 20)", unnamedCargo.displayTitle());
        assertTrue(unnamedCargo.matchesFilter("cargo_station"));
        assertTrue(unnamedCargo.matchesFilter("cargo station"));
        assertTrue(unnamedCargo.matchesFilter("65"));
        assertFalse(unnamedCargo.matchesFilter("smart"));

        TerminalTargetEntry diagnosticRow = new TerminalTargetEntry(
                new BlockPos3i(-50, 70, 80), NETHER, false, null, null,
                null, CommStationStatus.WRONG_DIMENSION, -1
        );
        assertFalse(diagnosticRow.isConnectable());
        assertNull(diagnosticRow.stationPos());
        assertNull(diagnosticRow.stationType());
        assertEquals("(-50, 70, 80)", diagnosticRow.displayTitle());
        assertEquals("", diagnosticRow.stationName());
        assertTrue(diagnosticRow.matchesFilter("-50"));

        // Invalid constructions rejected
        assertThrows(NullPointerException.class, () -> new TerminalTargetEntry(
                commPos, OVERWORLD, true, null, StationType.SMART_PORT, "", CommStationStatus.ONLINE, 1));
        assertThrows(NullPointerException.class, () -> new TerminalTargetEntry(
                commPos, OVERWORLD, true, stationPos, null, "", CommStationStatus.ONLINE, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTargetEntry(
                commPos, OVERWORLD, false, null, null, "", CommStationStatus.COMM_MISSING, -2));
    }

    @Test
    void resolveCoversAllBindingStatesUnlimitedSameDimDistanceAndSkipsCrossDimProbes() {
        BlockPos3i playerPos = new BlockPos3i(0, 64, 0);

        CommBinding farOnline = new CommBinding(OVERWORLD, new BlockPos3i(50_000, 64, -50_000));
        BlockPos3i farStationPos = new BlockPos3i(50_001, 64, -50_000);
        CommBinding unpoweredBare = new CommBinding(OVERWORLD, new BlockPos3i(10, 64, 0));
        CommBinding poweredBare = new CommBinding(OVERWORLD, new BlockPos3i(20, 64, 0));
        CommBinding unloaded = new CommBinding(OVERWORLD, new BlockPos3i(30, 64, 0));
        CommBinding missing = new CommBinding(OVERWORLD, new BlockPos3i(40, 64, 0));
        CommBinding netherBinding = new CommBinding(NETHER, new BlockPos3i(5, 64, 5));

        Map<CommBinding, CommProbeResult> world = new HashMap<>();
        world.put(farOnline, CommProbeResult.present(true, List.of(
                new AdjacentStationInfo(farStationPos, StationType.CARGO_STATION, "Deep Frontier")
        )));
        world.put(unpoweredBare, CommProbeResult.present(false, List.of()));
        world.put(poweredBare, CommProbeResult.present(true, List.of()));
        world.put(unloaded, CommProbeResult.unloaded());
        world.put(missing, CommProbeResult.missing());

        AtomicInteger probeCalls = new AtomicInteger();
        List<TerminalTargetEntry> entries = TerminalTargetResolver.resolve(
                OVERWORLD,
                playerPos,
                List.of(missing, netherBinding, unloaded, poweredBare, unpoweredBare, farOnline),
                binding -> {
                    assertEquals(OVERWORLD, binding.dimensionId(), "Cross-dimension binding must not be probed");
                    probeCalls.incrementAndGet();
                    return world.get(binding);
                }
        );

        assertEquals(5, probeCalls.get());
        assertEquals(6, entries.size());

        // Exact sort order: ONLINE(0) < UNPOWERED(1) < NO_ADJACENT_STATION(2) < CHUNK_UNLOADED(3) < WRONG_DIMENSION(4) < COMM_MISSING(5)
        assertEquals(CommStationStatus.ONLINE, entries.get(0).status());
        assertTrue(entries.get(0).hasStation());
        assertEquals("Deep Frontier", entries.get(0).stationName());
        assertEquals(100_001, entries.get(0).distance()); // Unlimited same-dimension distance!

        assertEquals(CommStationStatus.UNPOWERED, entries.get(1).status());
        assertFalse(entries.get(1).hasStation());
        assertEquals(10, entries.get(1).distance());

        assertEquals(CommStationStatus.NO_ADJACENT_STATION, entries.get(2).status());
        assertFalse(entries.get(2).hasStation());
        assertEquals(20, entries.get(2).distance());

        assertEquals(CommStationStatus.CHUNK_UNLOADED, entries.get(3).status());
        assertFalse(entries.get(3).hasStation());
        assertEquals(30, entries.get(3).distance());

        assertEquals(CommStationStatus.WRONG_DIMENSION, entries.get(4).status());
        assertFalse(entries.get(4).hasStation());
        assertEquals(NETHER, entries.get(4).dimensionId());
        assertEquals(-1, entries.get(4).distance());

        assertEquals(CommStationStatus.COMM_MISSING, entries.get(5).status());
        assertFalse(entries.get(5).hasStation());
        assertEquals(40, entries.get(5).distance());
    }

    @Test
    void resolveExpandsAllSixAdjacentFacesAroundOneCommBaseStation() {
        BlockPos3i center = new BlockPos3i(100, 64, 100);
        CommBinding binding = new CommBinding(OVERWORLD, center);
        List<AdjacentStationInfo> sixFaces = List.of(
                new AdjacentStationInfo(center.east(), StationType.SMART_PORT, "East Port"),
                new AdjacentStationInfo(center.west(), StationType.CARGO_STATION, "West Hub"),
                new AdjacentStationInfo(center.north(), StationType.SMART_PORT, "North Port"),
                new AdjacentStationInfo(center.south(), StationType.CARGO_STATION, "South Hub"),
                new AdjacentStationInfo(center.above(), StationType.CARGO_STATION, "Upper Hub"),
                new AdjacentStationInfo(center.below(), StationType.SMART_PORT, "Lower Port")
        );

        List<TerminalTargetEntry> resolved = TerminalTargetResolver.resolve(
                OVERWORLD,
                new BlockPos3i(0, 64, 0),
                List.of(binding),
                b -> CommProbeResult.present(true, sixFaces)
        );

        assertEquals(6, resolved.size());
        for (TerminalTargetEntry entry : resolved) {
            assertEquals(center, entry.commPos());
            assertTrue(entry.hasStation());
            assertTrue(entry.isConnectable());
            assertEquals(CommStationStatus.ONLINE, entry.status());
            assertTrue(TerminalTargetResolver.areFaceAdjacent(entry.commPos(), entry.stationPos()));
        }
        // Alphabetical order among ONLINE named rows
        assertEquals(
                List.of("East Port", "Lower Port", "North Port", "South Hub", "Upper Hub", "West Hub"),
                resolved.stream().map(TerminalTargetEntry::stationName).toList()
        );
    }

    @Test
    void resolveDeduplicatesSharedStationPreferringOnlineThenEarliestBindingOrder() {
        BlockPos3i sharedStation = new BlockPos3i(50, 64, 50);
        BlockPos3i secondStation = new BlockPos3i(48, 64, 50);
        CommBinding commWest = new CommBinding(OVERWORLD, sharedStation.west());   // (49, 64, 50)
        CommBinding commEast = new CommBinding(OVERWORLD, sharedStation.east());   // (51, 64, 50)
        CommBinding commNorth = new CommBinding(OVERWORLD, sharedStation.north()); // (50, 64, 49)

        // Case 1: Earlier binding UNPOWERED, later binding ONLINE -> ONLINE wins for sharedStation,
        // while secondStation (only touched by commWest) stays UNPOWERED.
        List<TerminalTargetEntry> onlineBeatsUnpowered = TerminalTargetResolver.resolve(
                OVERWORLD,
                BlockPos3i.ZERO,
                List.of(commWest, commEast),
                binding -> {
                    if (binding.equals(commWest)) {
                        return CommProbeResult.present(false, List.of(
                                new AdjacentStationInfo(sharedStation, StationType.CARGO_STATION, "Shared"),
                                new AdjacentStationInfo(secondStation, StationType.SMART_PORT, "Solo")
                        ));
                    }
                    return CommProbeResult.present(true, List.of(
                            new AdjacentStationInfo(sharedStation, StationType.CARGO_STATION, "Shared")
                    ));
                }
        );

        assertEquals(2, onlineBeatsUnpowered.size());
        TerminalTargetEntry mergedShared = onlineBeatsUnpowered.get(0);
        assertEquals(sharedStation, mergedShared.stationPos());
        assertEquals(commEast.pos(), mergedShared.commPos());
        assertEquals(CommStationStatus.ONLINE, mergedShared.status());

        TerminalTargetEntry solo = onlineBeatsUnpowered.get(1);
        assertEquals(secondStation, solo.stationPos());
        assertEquals(commWest.pos(), solo.commPos());
        assertEquals(CommStationStatus.UNPOWERED, solo.status());

        // Case 2: Both ONLINE -> earliest binding in list (commEast listed first even with larger X) wins!
        List<TerminalTargetEntry> earliestOnlineWins = TerminalTargetResolver.resolve(
                OVERWORLD,
                BlockPos3i.ZERO,
                List.of(commEast, commNorth, commWest),
                binding -> CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(sharedStation, StationType.CARGO_STATION, "Shared")
                ))
        );
        assertEquals(1, earliestOnlineWins.size());
        assertEquals(commEast.pos(), earliestOnlineWins.get(0).commPos());

        // Case 3: Both UNPOWERED -> earliest binding wins
        List<TerminalTargetEntry> earliestUnpoweredWins = TerminalTargetResolver.resolve(
                OVERWORLD,
                BlockPos3i.ZERO,
                List.of(commNorth, commWest),
                binding -> CommProbeResult.present(false, List.of(
                        new AdjacentStationInfo(sharedStation, StationType.CARGO_STATION, "Shared")
                ))
        );
        assertEquals(1, earliestUnpoweredWins.size());
        assertEquals(commNorth.pos(), earliestUnpoweredWins.get(0).commPos());
        assertEquals(CommStationStatus.UNPOWERED, earliestUnpoweredWins.get(0).status());
    }

    @Test
    void resolveSortsDeterministicallyByStatusNameBlanknessDistanceAndCoordinates() {
        BlockPos3i playerPos = new BlockPos3i(0, 64, 0);

        CommBinding bOnlineNamed = new CommBinding(OVERWORLD, new BlockPos3i(100, 64, 0));
        CommBinding bOnlineLower = new CommBinding(OVERWORLD, new BlockPos3i(200, 64, 0));
        CommBinding bOnlineSameNameFar = new CommBinding(OVERWORLD, new BlockPos3i(300, 64, 0));
        CommBinding bOnlineSameNameNear = new CommBinding(OVERWORLD, new BlockPos3i(50, 64, 0));
        CommBinding bOnlineBlankFar = new CommBinding(OVERWORLD, new BlockPos3i(40, 64, 0));
        CommBinding bOnlineBlankTieB = new CommBinding(OVERWORLD, new BlockPos3i(20, 64, 0));
        CommBinding bOnlineBlankTieA = new CommBinding(OVERWORLD, new BlockPos3i(0, 64, 20));
        CommBinding bUnpoweredNamed = new CommBinding(OVERWORLD, new BlockPos3i(5, 64, 0));
        CommBinding bEndDim = new CommBinding(END, new BlockPos3i(0, 64, 0));

        Map<CommBinding, CommProbeResult> probes = Map.of(
                bOnlineNamed, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(101, 64, 0), StationType.SMART_PORT, "Bravo"))),
                bOnlineLower, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(201, 64, 0), StationType.SMART_PORT, "alpha"))),
                bOnlineSameNameFar, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(301, 64, 0), StationType.CARGO_STATION, "Delta"))),
                bOnlineSameNameNear, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(51, 64, 0), StationType.CARGO_STATION, "delta"))),
                bOnlineBlankFar, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(41, 64, 0), StationType.CARGO_STATION, ""))),
                bOnlineBlankTieB, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(20, 64, 1), StationType.CARGO_STATION, ""))),
                bOnlineBlankTieA, CommProbeResult.present(true, List.of(
                        new AdjacentStationInfo(new BlockPos3i(1, 64, 20), StationType.CARGO_STATION, "   "))),
                bUnpoweredNamed, CommProbeResult.present(false, List.of(
                        new AdjacentStationInfo(new BlockPos3i(6, 64, 0), StationType.SMART_PORT, "AAA Priority")))
        );

        // Include a duplicate binding to ensure deduplication of identical CommBindings
        List<CommBinding> input = List.of(
                bEndDim, bUnpoweredNamed, bOnlineBlankFar, bOnlineSameNameFar,
                bOnlineNamed, bOnlineBlankTieB, bOnlineLower, bOnlineSameNameNear,
                bOnlineBlankTieA, bEndDim
        );

        List<TerminalTargetEntry> sorted = TerminalTargetResolver.resolve(OVERWORLD, playerPos, input, probes::get);
        assertEquals(9, sorted.size());

        // 0: ONLINE "alpha" (dist 201)
        assertEquals("alpha", sorted.get(0).stationName());
        // 1: ONLINE "Bravo" (dist 101)
        assertEquals("Bravo", sorted.get(1).stationName());
        // 2: ONLINE "delta" (dist 51) before "Delta" (dist 301) via distance tie-break
        assertEquals(51, sorted.get(2).distance());
        assertEquals(301, sorted.get(3).distance());
        // 4 & 5: ONLINE blank names at distance 21 -> coordinate tie-break (1,64,20) before (20,64,1)
        assertEquals(new BlockPos3i(1, 64, 20), sorted.get(4).stationPos());
        assertEquals(new BlockPos3i(20, 64, 1), sorted.get(5).stationPos());
        // 6: ONLINE blank name at distance 41
        assertEquals(new BlockPos3i(41, 64, 0), sorted.get(6).stationPos());
        // 7: UNPOWERED "AAA Priority" comes after all ONLINE rows
        assertEquals(CommStationStatus.UNPOWERED, sorted.get(7).status());
        assertEquals("AAA Priority", sorted.get(7).stationName());
        // 8: WRONG_DIMENSION (deduplicated once despite duplicate bEndDim in input)
        assertEquals(CommStationStatus.WRONG_DIMENSION, sorted.get(8).status());
        assertEquals(-1, sorted.get(8).distance());
    }

    @Test
    void resolveEmptyBindingsReturnsEmptyList() {
        List<TerminalTargetEntry> empty = TerminalTargetResolver.resolve(
                OVERWORLD, BlockPos3i.ZERO, List.of(), b -> {
                    throw new AssertionError("Probe should not be called for empty bindings");
                });
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    void validateRemoteSessionChecksAllConditionsAndPrecedence() {
        // Healthy session
        assertEquals(RemoteDisconnectReason.NONE,
                TerminalTargetResolver.validateRemoteSession(true, true, true, true, true, true, true, true));

        // Each single failure isolated
        assertEquals(RemoteDisconnectReason.WRONG_DIMENSION,
                TerminalTargetResolver.validateRemoteSession(false, true, true, true, true, true, true, true));
        assertEquals(RemoteDisconnectReason.CHUNK_UNLOADED,
                TerminalTargetResolver.validateRemoteSession(true, false, true, true, true, true, true, true));
        assertEquals(RemoteDisconnectReason.CHUNK_UNLOADED,
                TerminalTargetResolver.validateRemoteSession(true, true, false, true, true, true, true, true));
        assertEquals(RemoteDisconnectReason.COMM_MISSING,
                TerminalTargetResolver.validateRemoteSession(true, true, true, false, true, true, true, true));
        assertEquals(RemoteDisconnectReason.COMM_UNPOWERED,
                TerminalTargetResolver.validateRemoteSession(true, true, true, true, false, true, true, true));
        assertEquals(RemoteDisconnectReason.STATION_MISSING,
                TerminalTargetResolver.validateRemoteSession(true, true, true, true, true, false, true, true));
        assertEquals(RemoteDisconnectReason.NOT_ADJACENT,
                TerminalTargetResolver.validateRemoteSession(true, true, true, true, true, true, false, true));
        assertEquals(RemoteDisconnectReason.TERMINAL_MISSING,
                TerminalTargetResolver.validateRemoteSession(true, true, true, true, true, true, true, false));

        // Short-circuiting real-world precedence cascades
        assertEquals(RemoteDisconnectReason.WRONG_DIMENSION,
                TerminalTargetResolver.validateRemoteSession(false, false, false, false, false, false, false, false));
        assertEquals(RemoteDisconnectReason.CHUNK_UNLOADED,
                TerminalTargetResolver.validateRemoteSession(true, false, false, false, false, false, false, true));
        assertEquals(RemoteDisconnectReason.COMM_MISSING,
                TerminalTargetResolver.validateRemoteSession(true, true, true, false, false, true, true, true));
    }

    @Test
    void areFaceAdjacentRecognizesExactlySixCardinalNeighbors() {
        BlockPos3i origin = new BlockPos3i(10, 64, -5);
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.north()));
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.south()));
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.east()));
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.west()));
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.above()));
        assertTrue(TerminalTargetResolver.areFaceAdjacent(origin, origin.below()));

        assertFalse(TerminalTargetResolver.areFaceAdjacent(origin, origin));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(origin, origin.offset(1, 0, 1)));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(origin, origin.offset(1, 1, 1)));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(origin, origin.offset(2, 0, 0)));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(origin, null));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(null, origin));
        assertFalse(TerminalTargetResolver.areFaceAdjacent(null, null));
    }

    private static void assertStatus(CommStationStatus status, String key, int order, boolean connectable) {
        assertEquals(key, status.translationKey());
        assertEquals(order, status.sortOrder());
        assertEquals(connectable, status.isConnectable());
    }

    private static void assertDisconnect(RemoteDisconnectReason reason, String key, Set<String> seen) {
        assertEquals(key, reason.translationKey());
        assertTrue(reason.isDisconnected());
        assertTrue(seen.add(key), "Duplicate disconnect key: " + key);
    }
}
