package com.logisticscraft.lc2.forge.util;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Reference-counted 3x3 chunk-loading ticket manager for stations running {@code lc2:chunk_loading_upgrade}.
 * Uses {@link TicketType} with level-31 entity-ticking region tickets ({@code distance = 2}) for
 * every chunk in the 3x3 area centered on the station.
 */
public final class LogisticsChunkTickets {

    private static final TicketType<ChunkPos> CHUNK_LOADER_TICKET =
            TicketType.create("trera_chunk_loader", Comparator.comparingLong(ChunkPos::toLong));

    private static final int REGION_TICKET_RADIUS = 2;

    private static final Map<ServerLevel, Map<ChunkPos, Integer>> HOLDERS = new WeakHashMap<>();

    private LogisticsChunkTickets() {
    }

    /**
     * Acquires reference-counted tickets for the 3x3 chunk area centered on {@code centerPos}.
     */
    public static void holdArea(ServerLevel level, BlockPos centerPos) {
        ChunkPos center = new ChunkPos(centerPos);
        int radius = LogisticsConstants.CHUNK_LOADER_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                holdChunk(level, new ChunkPos(center.x + dx, center.z + dz));
            }
        }
    }

    /**
     * Releases reference-counted tickets for the 3x3 chunk area centered on {@code centerPos}.
     */
    public static void releaseArea(ServerLevel level, BlockPos centerPos) {
        ChunkPos center = new ChunkPos(centerPos);
        int radius = LogisticsConstants.CHUNK_LOADER_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                releaseChunk(level, new ChunkPos(center.x + dx, center.z + dz));
            }
        }
    }

    /**
     * Acquires one lease on a single chunk, registering a region ticket when count transitions 0 -> 1.
     */
    public static void holdChunk(ServerLevel level, ChunkPos chunk) {
        Map<ChunkPos, Integer> leases = HOLDERS.computeIfAbsent(level, ignored -> new HashMap<>());
        int count = leases.merge(chunk, 1, Integer::sum);
        if (count == 1) {
            level.getChunkSource().addRegionTicket(CHUNK_LOADER_TICKET, chunk, REGION_TICKET_RADIUS, chunk);
        }
    }

    /**
     * Releases one lease on a single chunk, removing the region ticket when count reaches 0.
     */
    public static void releaseChunk(ServerLevel level, ChunkPos chunk) {
        Map<ChunkPos, Integer> leases = HOLDERS.get(level);
        if (leases == null) {
            return;
        }
        Integer count = leases.get(chunk);
        if (count == null) {
            return;
        }
        if (count > 1) {
            leases.put(chunk, count - 1);
        } else {
            leases.remove(chunk);
            level.getChunkSource().removeRegionTicket(CHUNK_LOADER_TICKET, chunk, REGION_TICKET_RADIUS, chunk);
        }
        if (leases.isEmpty()) {
            HOLDERS.remove(level);
        }
    }

    /**
     * Returns whether {@code chunk} currently has at least one active LC2 chunk-loader ticket in {@code level}.
     */
    public static boolean isChunkHeld(ServerLevel level, ChunkPos chunk) {
        Map<ChunkPos, Integer> leases = HOLDERS.get(level);
        return leases != null && leases.getOrDefault(chunk, 0) > 0;
    }

    /**
     * Returns the number of distinct chunks currently held by LC2 chunk-loading upgrades in {@code level}.
     */
    public static int heldChunkCount(ServerLevel level) {
        Map<ChunkPos, Integer> leases = HOLDERS.get(level);
        return leases == null ? 0 : leases.size();
    }

    /**
     * Releases all tracked ticket entries when a level unloads.
     */
    public static void clearLevel(ServerLevel level) {
        HOLDERS.remove(level);
    }
}
