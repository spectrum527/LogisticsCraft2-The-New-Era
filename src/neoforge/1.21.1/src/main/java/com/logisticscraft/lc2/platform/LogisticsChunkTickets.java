package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;

/**
 * Reference-counted 3x3 chunk-loading ticket manager for NeoForge 1.21.1.
 * Every chunk in the 3x3 area centered on a powered station receives an entity-ticking ticket
 * (radius 2 -> level 31) and is released immediately when power is lost, the upgrade is removed,
 * or the station is broken.
 */
public final class LogisticsChunkTickets {

    private static final TicketType<ChunkPos> LEASE = TicketType.create(
            "trera_chunk_loader",
            Comparator.comparingLong(ChunkPos::toLong)
    );
    private static final Map<ServerLevel, Map<ChunkPos, Integer>> HOLDERS = new WeakHashMap<>();

    private LogisticsChunkTickets() {
    }

    /** No-op on 1.21.1 where TicketType is not registry-backed. */
    public static void install(IEventBus modBus) {
        // TicketType is constructed statically in 1.21.1.
    }

    /** Acquires ref-counted entity-ticking chunk tickets for the 3x3 area around {@code center}. */
    public static synchronized void holdArea3x3(ServerLevel level, ChunkPos center) {
        int radius = LogisticsConstants.CHUNK_LOADER_RADIUS;
        Map<ChunkPos, Integer> leases = HOLDERS.computeIfAbsent(level, ignored -> new HashMap<>());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                int count = leases.merge(chunk, 1, Integer::sum);
                if (count == 1) {
                    level.getChunkSource().addRegionTicket(LEASE, chunk, 2, chunk);
                }
            }
        }
    }

    /** Releases ref-counted chunk tickets for the 3x3 area around {@code center}. */
    public static synchronized void releaseArea3x3(ServerLevel level, ChunkPos center) {
        Map<ChunkPos, Integer> leases = HOLDERS.get(level);
        if (leases == null) {
            return;
        }
        int radius = LogisticsConstants.CHUNK_LOADER_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                Integer count = leases.get(chunk);
                if (count == null) {
                    continue;
                }
                if (count > 1) {
                    leases.put(chunk, count - 1);
                } else {
                    leases.remove(chunk);
                    level.getChunkSource().removeRegionTicket(LEASE, chunk, 2, chunk);
                }
            }
        }
        if (leases.isEmpty()) {
            HOLDERS.remove(level);
        }
    }

    /** Returns {@code true} if at least one LC2 station currently holds a ticket on {@code chunk}. */
    public static synchronized boolean isChunkHeld(ServerLevel level, ChunkPos chunk) {
        Map<ChunkPos, Integer> leases = HOLDERS.get(level);
        return leases != null && leases.getOrDefault(chunk, 0) > 0;
    }

    /** Clears tracked leases when a ServerLevel unloads. */
    public static synchronized void clearLevel(ServerLevel level) {
        HOLDERS.remove(level);
    }
}
