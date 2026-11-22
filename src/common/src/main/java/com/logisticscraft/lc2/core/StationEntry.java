package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Immutable snapshot describing a registered Smart Port or Cargo Station within a dimension.
 *
 * @param pos                   station block position
 * @param type                  station type ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION})
 * @param customName            player-configured station label (empty string if unnamed)
 * @param waterBerthPos         adjacent navigable water berth block for Smart Ports (nullable)
 * @param maxDroneBays          maximum drone landing bays (6 for Cargo Stations, 0 for Smart Ports)
 * @param dockedDrones          number of drones currently parked at this station
 * @param reservedInboundDrones number of bays atomically reserved for inbound drones
 */
public record StationEntry(
        BlockPos3i pos,
        StationType type,
        String customName,
        BlockPos3i waterBerthPos,
        int maxDroneBays,
        int dockedDrones,
        int reservedInboundDrones
) {

    /**
     * Compact constructor normalizing defaults and validating counts.
     */
    public StationEntry {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(type, "type");
        customName = customName == null ? "" : customName;
        if (maxDroneBays < 0) {
            throw new IllegalArgumentException("maxDroneBays must be >= 0, got " + maxDroneBays);
        }
        if (dockedDrones < 0) {
            throw new IllegalArgumentException("dockedDrones must be >= 0, got " + dockedDrones);
        }
        if (reservedInboundDrones < 0) {
            throw new IllegalArgumentException("reservedInboundDrones must be >= 0, got " + reservedInboundDrones);
        }
    }

    /**
     * Creates a new station entry with default bay capacity for {@code type} and zero docked/reserved drones.
     *
     * @param pos           station position
     * @param type          station type
     * @param customName    optional custom label
     * @param waterBerthPos optional adjacent water berth (Smart Ports only)
     * @return new station entry
     */
    public static StationEntry create(
            BlockPos3i pos,
            StationType type,
            String customName,
            BlockPos3i waterBerthPos
    ) {
        return new StationEntry(pos, type, customName, waterBerthPos, type.maxDroneBays(), 0, 0);
    }

    /**
     * Returns the number of currently free drone bays ({@code max(0, maxDroneBays - dockedDrones - reservedInboundDrones)}).
     *
     * @return free drone bay count
     */
    public int freeBays() {
        return Math.max(0, maxDroneBays - dockedDrones - reservedInboundDrones);
    }

    /**
     * Returns {@code true} if at least one drone bay is free.
     *
     * @return {@code true} if a bay can be reserved
     */
    public boolean hasFreeBay() {
        return freeBays() > 0;
    }

    /**
     * Returns the custom station name if non-blank, otherwise formatted block coordinates.
     *
     * @return display name for GUI lists
     */
    public String displayName() {
        if (!customName.isBlank()) {
            return customName;
        }
        return "(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")";
    }
}
