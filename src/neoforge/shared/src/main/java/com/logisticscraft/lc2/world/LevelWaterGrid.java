package com.logisticscraft.lc2.world;

import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.WaterGrid;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Minecraft {@link ServerLevel} implementation of {@link WaterGrid} supporting both fast
 * loaded-chunk-only queries (for per-tick traffic checks) and bounded on-demand chunk probing
 * (for cross-chunk water route verification without permanently force-loading intermediate chunks).
 */
public final class LevelWaterGrid implements WaterGrid {

    /** Default maximum number of unloaded chunks probed during a single water pathfind. */
    public static final int DEFAULT_MAX_PROBED_CHUNKS = 256;

    private final ServerLevel level;
    private final boolean allowProbing;
    private final int maxProbedChunks;
    private final int minY;
    private final int maxY;
    private final Map<Long, ChunkAccess> chunkCache = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int probedUnloadedChunks = 0;

    private LevelWaterGrid(ServerLevel level, boolean allowProbing, int maxProbedChunks) {
        this.level = level;
        this.allowProbing = allowProbing;
        this.maxProbedChunks = maxProbedChunks;
        this.minY = Lc2Native.minBuildY(level);
        this.maxY = Lc2Native.maxBuildY(level);
    }

    /** Creates a non-probing grid that only inspects already-loaded chunks. */
    public static LevelWaterGrid loadedOnly(ServerLevel level) {
        return new LevelWaterGrid(level, false, 0);
    }

    /** Creates a grid that probes up to {@value #DEFAULT_MAX_PROBED_CHUNKS} unloaded chunks on demand. */
    public static LevelWaterGrid probing(ServerLevel level) {
        return new LevelWaterGrid(level, true, DEFAULT_MAX_PROBED_CHUNKS);
    }

    /** Creates a grid with a custom unloaded-chunk probe budget. */
    public static LevelWaterGrid probing(ServerLevel level, int maxProbedChunks) {
        return new LevelWaterGrid(level, true, Math.max(0, maxProbedChunks));
    }

    /** Returns an {@link AirspaceRouter.HeightSampler} backed by loaded chunk motion-blocking heightmaps. */
    public static AirspaceRouter.HeightSampler heightSampler(ServerLevel level) {
        int seaLevel = level.getSeaLevel();
        return (x, z) -> {
            ChunkAccess chunk = Lc2Native.getLoadedChunk(level, x >> 4, z >> 4);
            if (chunk == null) {
                return seaLevel;
            }
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        };
    }

    @Override
    public boolean isNavigableWater(int x, int y, int z) {
        ChunkAccess chunk = chunkAt(x, y, z);
        if (chunk == null) {
            return false;
        }
        cursor.set(x, y, z);
        BlockState state = Lc2Native.getBlockStateFromChunk(chunk, cursor);
        return state.getFluidState().is(FluidTags.WATER)
                && state.getCollisionShape(chunk, cursor).isEmpty();
    }

    @Override
    public boolean isPassableHeadroom(int x, int y, int z) {
        if (y >= maxY) {
            return true;
        }
        ChunkAccess chunk = chunkAt(x, y, z);
        if (chunk == null) {
            return false;
        }
        cursor.set(x, y, z);
        BlockState state = Lc2Native.getBlockStateFromChunk(chunk, cursor);
        if (state.getFluidState().is(FluidTags.WATER) || state.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return state.getCollisionShape(chunk, cursor).isEmpty();
    }

    private ChunkAccess chunkAt(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return null;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        long key = (((long) cx) & 0xFFFFFFFFL) | ((((long) cz) & 0xFFFFFFFFL) << 32);
        if (chunkCache.containsKey(key)) {
            return chunkCache.get(key);
        }
        ChunkAccess chunk = Lc2Native.getLoadedChunk(level, cx, cz);
        if (chunk == null && allowProbing && probedUnloadedChunks < maxProbedChunks) {
            chunk = Lc2Native.loadChunkForProbe(level, cx, cz);
            probedUnloadedChunks++;
        }
        chunkCache.put(key, chunk);
        return chunk;
    }
}
