package com.logisticscraft.lc2.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable flat row displayed in the Operation Terminal target selection screen.
 * <p>
 * When {@code hasStation} is {@code true}, this row represents a reachable or unpowered adjacent
 * logistics station ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION}) paired
 * with the {@code commPos} Communication Base Station providing its uplink. When {@code hasStation}
 * is {@code false}, this row represents a non-station binding diagnostic entry (unloaded, missing,
 * wrong dimension, unpowered bare base station, or no adjacent station).
 *
 * @param commPos     bound Communication Base Station block position
 * @param dimensionId dimension resource key string of the binding
 * @param hasStation  {@code true} if this row refers to a resolved adjacent logistics station
 * @param stationPos  target logistics station block position (non-null when {@code hasStation} is {@code true})
 * @param stationType target logistics station type (non-null when {@code hasStation} is {@code true})
 * @param stationName custom name of the logistics station (normalized to empty string when absent)
 * @param status      connectivity status of this row
 * @param distance    3D Manhattan distance from the player in blocks, or {@code -1} for another dimension
 */
public record TerminalTargetEntry(
        BlockPos3i commPos,
        String dimensionId,
        boolean hasStation,
        BlockPos3i stationPos,
        StationType stationType,
        String stationName,
        CommStationStatus status,
        int distance
) {

    /**
     * Compact constructor validating non-null invariants, station field consistency, and distance bounds.
     */
    public TerminalTargetEntry {
        Objects.requireNonNull(commPos, "commPos");
        Objects.requireNonNull(dimensionId, "dimensionId");
        dimensionId = dimensionId.trim();
        if (dimensionId.isEmpty()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        stationName = stationName == null ? "" : stationName;
        if (hasStation) {
            Objects.requireNonNull(stationPos, "stationPos");
            Objects.requireNonNull(stationType, "stationType");
        }
        if (distance < -1) {
            throw new IllegalArgumentException("distance must be >= -1, got " + distance);
        }
    }

    /**
     * Returns {@code true} if this row can currently be opened as a remote station session.
     *
     * @return {@code true} when {@code hasStation} is {@code true} and {@code status.isConnectable()}
     */
    public boolean isConnectable() {
        return hasStation && status.isConnectable();
    }

    /**
     * Returns the primary block position represented by this row ({@code stationPos} when
     * {@code hasStation} is {@code true}, otherwise {@code commPos}).
     *
     * @return primary display position
     */
    public BlockPos3i displayPos() {
        return (hasStation && stationPos != null) ? stationPos : commPos;
    }

    /**
     * Returns the formatted coordinate string {@code "(x, y, z)"} for {@link #displayPos()}.
     *
     * @return formatted coordinates
     */
    public String formattedCoordinates() {
        BlockPos3i p = displayPos();
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    /**
     * Returns the row's display title: {@code stationName} when non-blank, otherwise the
     * formatted coordinates {@code "(x, y, z)"} of the station (or bound Communication Base Station
     * when no station is attached).
     *
     * @return display title string
     */
    public String displayTitle() {
        if (!stationName.isBlank()) {
            return stationName;
        }
        return formattedCoordinates();
    }

    /**
     * Performs a case-insensitive substring filter match against the station's custom name,
     * coordinates string (in parenthesized, compact comma, or space-separated forms), and
     * station type token ({@code SMART_PORT} / {@code smart port} / {@code lc2:smart_port}, etc.).
     * <p>
     * Returns {@code true} if {@code query} is {@code null} or blank.
     *
     * @param query search query typed by the player
     * @return {@code true} if this row matches the filter
     */
    public boolean matchesFilter(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (!stationName.isEmpty() && stationName.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (matchesCoordinates(stationPos, needle) || matchesCoordinates(commPos, needle)) {
            return true;
        }
        if (stationType != null) {
            String enumToken = stationType.name().toLowerCase(Locale.ROOT);
            if (enumToken.contains(needle) || enumToken.replace('_', ' ').contains(needle)) {
                return true;
            }
            if (stationType.blockId().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCoordinates(BlockPos3i pos, String needle) {
        if (pos == null) {
            return false;
        }
        String parenthesized = "(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")";
        if (parenthesized.contains(needle)) {
            return true;
        }
        String compact = pos.x() + "," + pos.y() + "," + pos.z();
        if (compact.contains(needle)) {
            return true;
        }
        String spaced = pos.x() + " " + pos.y() + " " + pos.z();
        return spaced.contains(needle);
    }
}
