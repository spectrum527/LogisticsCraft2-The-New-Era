package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Reference-counted 3x3 chunk-loading ticket manager for NeoForge 26.x using registered
 * {@link Registries#TICKET_TYPE}.
 */
public final class LogisticsChunkTickets {

    private static final DeferredRegister<TicketType> TYPES =
            DeferredRegister.create(Registries.TICKET_TYPE, LogisticsConstants.MOD_ID);
    private static final Supplier<TicketType> LEASE = TYPES.register(
            "chunk_loader",
            () -> new TicketType(
                    0L,
                    TicketType.FLAG_LOADING
                            | TicketType.FLAG_SIMULATION
                            | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
            )
    );
    private static final Map<ServerLevel, Map<ChunkPos, Integer>> HOLDERS = new WeakHashMap<>();

    private LogisticsChunkTickets() {
    }

    /** Registers the LC2 chunk loader TicketType on the mod event bus. */
    public static void install(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** Acquires ref-counted entity-ticking chunk tickets for the 3x3 area around {@code center}. */
    public static synchronized void holdArea3x3(ServerLevel level, ChunkPos center) {
        int radius = LogisticsConstants.CHUNK_LOADER_RADIUS;
        Map<ChunkPos, Integer> leases = HOLDERS.computeIfAbsent(level, ignored -> new HashMap<>());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(center.x() + dx, center.z() + dz);
                int count = leases.merge(chunk, 1, Integer::sum);
                if (count == 1) {
                    level.getChunkSource().addTicketWithRadius(LEASE.get(), chunk, 2);
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
                ChunkPos chunk = new ChunkPos(center.x() + dx, center.z() + dz);
                Integer count = leases.get(chunk);
                if (count == null) {
                    continue;
                }
                if (count > 1) {
                    leases.put(chunk, count - 1);
                } else {
                    leases.remove(chunk);
                    level.getChunkSource().removeTicketWithRadius(LEASE.get(), chunk, 2);
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
