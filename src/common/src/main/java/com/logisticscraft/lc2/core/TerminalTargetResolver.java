package com.logisticscraft.lc2.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java resolver that expands bound Communication Base Stations into a deduplicated,
 * deterministically sorted flat list of {@link TerminalTargetEntry} rows for the Operation
 * Terminal GUI, and validates live remote station sessions.
 */
public final class TerminalTargetResolver {

    private static final Comparator<TerminalTargetEntry> ENTRY_COMPARATOR = Comparator
            .comparingInt((TerminalTargetEntry e) -> e.status().sortOrder())
            .thenComparing(e -> e.stationName().isBlank())
            .thenComparing(e -> e.stationName().isBlank() ? "" : e.stationName().toLowerCase(Locale.ROOT))
            .thenComparingInt(TerminalTargetEntry::distance)
            .thenComparing(TerminalTargetEntry::stationPos, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TerminalTargetEntry::commPos)
            .thenComparing(TerminalTargetEntry::dimensionId)
            .thenComparing(TerminalTargetEntry::stationName);

    private TerminalTargetResolver() {
    }

    /**
     * Metadata describing a Smart Port or Cargo Station face-adjacent to a Communication Base Station.
     *
     * @param pos        station block position
     * @param type       station type ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION})
     * @param customName player-configured station label (normalized to empty string if {@code null})
     */
    public record AdjacentStationInfo(BlockPos3i pos, StationType type, String customName) {

        /**
         * Compact constructor normalizing null custom names and enforcing non-null position/type.
         */
        public AdjacentStationInfo {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(type, "type");
            customName = customName == null ? "" : customName;
        }
    }

    /**
     * Result of probing a single same-dimension {@link CommBinding} in the world without forcing
     * unloaded chunks to load.
     *
     * @param chunkLoaded      {@code true} if the chunk containing the Communication Base Station is loaded
     * @param commPresent      {@code true} if a Communication Base Station block entity exists at the bound pos
     * @param powered          {@code true} if the Communication Base Station currently has active standby power
     * @param adjacentStations immutable list of face-adjacent Smart Ports and Cargo Stations (up to 6)
     */
    public record CommProbeResult(
            boolean chunkLoaded,
            boolean commPresent,
            boolean powered,
            List<AdjacentStationInfo> adjacentStations
    ) {

        /**
         * Compact constructor copying {@code adjacentStations} into an unmodifiable list.
         */
        public CommProbeResult {
            adjacentStations = adjacentStations == null ? List.of() : List.copyOf(adjacentStations);
        }

        /**
         * Convenience factory for an unloaded chunk probe result.
         *
         * @return unloaded probe result
         */
        public static CommProbeResult unloaded() {
            return new CommProbeResult(false, false, false, List.of());
        }

        /**
         * Convenience factory for a loaded chunk where the Communication Base Station block is missing.
         *
         * @return missing-comm probe result
         */
        public static CommProbeResult missing() {
            return new CommProbeResult(true, false, false, List.of());
        }

        /**
         * Convenience factory for a loaded, present Communication Base Station.
         *
         * @param powered          whether the base station is currently powered
         * @param adjacentStations list of face-adjacent stations
         * @return present probe result
         */
        public static CommProbeResult present(boolean powered, List<AdjacentStationInfo> adjacentStations) {
            return new CommProbeResult(true, true, powered, adjacentStations);
        }
    }

    /**
     * Platform-agnostic world probe callback invoked only for same-dimension bindings.
     */
    @FunctionalInterface
    public interface CommProbe {

        /**
         * Probes the live world state at {@code binding} without forcing chunk loads.
         *
         * @param binding same-dimension Communication Base Station binding
         * @return probe result
         */
        CommProbeResult probe(CommBinding binding);
    }

    /**
     * Resolves all bound Communication Base Stations into a deduplicated, deterministically sorted
     * flat list of {@link TerminalTargetEntry} rows.
     * <p>
     * Resolution rules:
     * <ul>
     *   <li>Same-dimension range is unlimited; bindings in another dimension produce a single
     *       {@link CommStationStatus#WRONG_DIMENSION} row with {@code distance = -1} without invoking {@code probe}.</li>
     *   <li>In the same dimension: unloaded chunk -&gt; {@link CommStationStatus#CHUNK_UNLOADED};
     *       missing base station -&gt; {@link CommStationStatus#COMM_MISSING}; no adjacent stations -&gt;
     *       {@link CommStationStatus#NO_ADJACENT_STATION} (if powered) or {@link CommStationStatus#UNPOWERED}.</li>
     *   <li>Each adjacent station expands to its own row with status {@link CommStationStatus#ONLINE}
     *       (if powered) or {@link CommStationStatus#UNPOWERED} and 3D Manhattan distance from {@code playerPos}.</li>
     *   <li>If multiple bound Communication Base Stations touch the same {@code stationPos}, they merge
     *       into a single row preferring {@link CommStationStatus#ONLINE} over {@link CommStationStatus#UNPOWERED}
     *       and preserving earliest binding order on tie.</li>
     *   <li>Final output is sorted by {@code status.sortOrder()}, then {@code stationName} (case-insensitive,
     *       non-blank before blank), then {@code distance}, then {@code stationPos}/{@code commPos} coordinates.</li>
     * </ul>
     *
     * @param playerDimension player's current dimension ID
     * @param playerPos       player's current block position
     * @param bindings        ordered list of Communication Base Station bindings on the Operation Terminal
     * @param probe           world probe callback for same-dimension bindings
     * @return immutable sorted list of resolved terminal target entries
     */
    public static List<TerminalTargetEntry> resolve(
            String playerDimension,
            BlockPos3i playerPos,
            List<CommBinding> bindings,
            CommProbe probe
    ) {
        Objects.requireNonNull(playerDimension, "playerDimension");
        Objects.requireNonNull(playerPos, "playerPos");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(probe, "probe");

        if (bindings.isEmpty()) {
            return List.of();
        }

        Set<CommBinding> seenBindings = new HashSet<>();
        Map<BlockPos3i, TerminalTargetEntry> stationEntries = new LinkedHashMap<>();
        List<TerminalTargetEntry> diagnosticEntries = new ArrayList<>();

        for (CommBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            if (!seenBindings.add(binding)) {
                continue;
            }

            if (!binding.isInDimension(playerDimension)) {
                diagnosticEntries.add(new TerminalTargetEntry(
                        binding.pos(),
                        binding.dimensionId(),
                        false,
                        null,
                        null,
                        "",
                        CommStationStatus.WRONG_DIMENSION,
                        -1
                ));
                continue;
            }

            CommProbeResult probed = probe.probe(binding);
            if (probed == null || !probed.chunkLoaded()) {
                diagnosticEntries.add(new TerminalTargetEntry(
                        binding.pos(),
                        binding.dimensionId(),
                        false,
                        null,
                        null,
                        "",
                        CommStationStatus.CHUNK_UNLOADED,
                        playerPos.manhattanDistance(binding.pos())
                ));
                continue;
            }

            if (!probed.commPresent()) {
                diagnosticEntries.add(new TerminalTargetEntry(
                        binding.pos(),
                        binding.dimensionId(),
                        false,
                        null,
                        null,
                        "",
                        CommStationStatus.COMM_MISSING,
                        playerPos.manhattanDistance(binding.pos())
                ));
                continue;
            }

            List<AdjacentStationInfo> adjacent = probed.adjacentStations();
            if (adjacent.isEmpty()) {
                CommStationStatus status = probed.powered()
                        ? CommStationStatus.NO_ADJACENT_STATION
                        : CommStationStatus.UNPOWERED;
                diagnosticEntries.add(new TerminalTargetEntry(
                        binding.pos(),
                        binding.dimensionId(),
                        false,
                        null,
                        null,
                        "",
                        status,
                        playerPos.manhattanDistance(binding.pos())
                ));
                continue;
            }

            CommStationStatus status = probed.powered()
                    ? CommStationStatus.ONLINE
                    : CommStationStatus.UNPOWERED;

            for (AdjacentStationInfo station : adjacent) {
                TerminalTargetEntry candidate = new TerminalTargetEntry(
                        binding.pos(),
                        binding.dimensionId(),
                        true,
                        station.pos(),
                        station.type(),
                        station.customName(),
                        status,
                        playerPos.manhattanDistance(station.pos())
                );
                TerminalTargetEntry existing = stationEntries.get(station.pos());
                if (existing == null || candidate.status().sortOrder() < existing.status().sortOrder()) {
                    stationEntries.put(station.pos(), candidate);
                }
            }
        }

        List<TerminalTargetEntry> resolved = new ArrayList<>(stationEntries.size() + diagnosticEntries.size());
        resolved.addAll(stationEntries.values());
        resolved.addAll(diagnosticEntries);
        resolved.sort(ENTRY_COMPARATOR);
        return List.copyOf(resolved);
    }

    /**
     * Validates whether an active remote station session may stay connected, returning
     * {@link RemoteDisconnectReason#NONE} when all invariants hold or the highest-precedence
     * disconnect reason otherwise.
     * <p>
     * Evaluation precedence:
     * <ol>
     *   <li>{@code !sameDimension} -&gt; {@link RemoteDisconnectReason#WRONG_DIMENSION}</li>
     *   <li>{@code !commChunkLoaded || !stationChunkLoaded} -&gt; {@link RemoteDisconnectReason#CHUNK_UNLOADED}</li>
     *   <li>{@code !commPresent} -&gt; {@link RemoteDisconnectReason#COMM_MISSING}</li>
     *   <li>{@code !commPowered} -&gt; {@link RemoteDisconnectReason#COMM_UNPOWERED}</li>
     *   <li>{@code !stationPresent} -&gt; {@link RemoteDisconnectReason#STATION_MISSING}</li>
     *   <li>{@code !adjacent} -&gt; {@link RemoteDisconnectReason#NOT_ADJACENT}</li>
     *   <li>{@code !terminalHeld} -&gt; {@link RemoteDisconnectReason#TERMINAL_MISSING}</li>
     * </ol>
     *
     * @param sameDimension      whether player and remote station share the same dimension
     * @param commChunkLoaded    whether the Communication Base Station chunk is loaded
     * @param stationChunkLoaded whether the target logistics station chunk is loaded
     * @param commPresent        whether the Communication Base Station block entity still exists
     * @param commPowered        whether the Communication Base Station has active standby power
     * @param stationPresent     whether the target logistics station block entity still exists
     * @param adjacent           whether Communication Base Station and target station are face-adjacent
     * @param terminalHeld       whether the player still carries or equips an Operation Terminal
     * @return {@link RemoteDisconnectReason#NONE} if valid, otherwise the disconnect reason
     */
    public static RemoteDisconnectReason validateRemoteSession(
            boolean sameDimension,
            boolean commChunkLoaded,
            boolean stationChunkLoaded,
            boolean commPresent,
            boolean commPowered,
            boolean stationPresent,
            boolean adjacent,
            boolean terminalHeld
    ) {
        if (!sameDimension) {
            return RemoteDisconnectReason.WRONG_DIMENSION;
        }
        if (!commChunkLoaded || !stationChunkLoaded) {
            return RemoteDisconnectReason.CHUNK_UNLOADED;
        }
        if (!commPresent) {
            return RemoteDisconnectReason.COMM_MISSING;
        }
        if (!commPowered) {
            return RemoteDisconnectReason.COMM_UNPOWERED;
        }
        if (!stationPresent) {
            return RemoteDisconnectReason.STATION_MISSING;
        }
        if (!adjacent) {
            return RemoteDisconnectReason.NOT_ADJACENT;
        }
        if (!terminalHeld) {
            return RemoteDisconnectReason.TERMINAL_MISSING;
        }
        return RemoteDisconnectReason.NONE;
    }

    /**
     * Returns {@code true} if block positions {@code a} and {@code b} share a direct block face
     * (3D Manhattan distance equals {@code 1}). Safely returns {@code false} if either argument is {@code null}.
     *
     * @param a first block position
     * @param b second block position
     * @return {@code true} if {@code a} and {@code b} are face-adjacent
     */
    public static boolean areFaceAdjacent(BlockPos3i a, BlockPos3i b) {
        if (a == null || b == null) {
            return false;
        }
        return a.manhattanDistance(b) == 1;
    }
}
