package com.logisticscraft.lc2.forge.world;

import com.logisticscraft.lc2.core.WaterGrid;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.FluidState;

/**
 * Forge 1.19.2 {@link WaterGrid} adapter over a {@link ServerLevel} with bounded on-demand
 * chunk probing and a per-search chunk cache so large waterways can be verified without OOM.
 */
public final class LevelWaterGrid implements WaterGrid {

    private final ServerLevel level;
    private final boolean allowChunkProbing;
    private final int maxProbedChunks;
    private final Map<Long, ChunkAccess> chunkCache = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int probedCount = 0;

    /**
     * Creates a water grid backed by {@code level}.
     *
     * @param level             server level
     * @param allowChunkProbing whether unloaded chunks may be temporarily loaded up to {@code maxProbedChunks}
     * @param maxProbedChunks   maximum number of unloaded chunks to probe during a single search
     */
    public LevelWaterGrid(ServerLevel level, boolean allowChunkProbing, int maxProbedChunks) {
        this.level = level;
        this.allowChunkProbing = allowChunkProbing;
        this.maxProbedChunks = Math.max(0, maxProbedChunks);
    }

    /**
     * Returns the number of previously unloaded chunks probed by this grid instance.
     */
    public int probedChunkCount() {
        return probedCount;
    }

    @Override
    public boolean isNavigableWater(int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return false;
        }
        ChunkAccess chunk = resolveChunk(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        cursor.set(x, y, z);
        BlockState state = chunk.getBlockState(cursor);
        FluidState fluid = state.getFluidState();
        if (!fluid.is(FluidTags.WATER)) {
            return false;
        }
        return state.getCollisionShape(chunk, cursor).isEmpty();
    }

    @Override
    public boolean isPassableHeadroom(int x, int y, int z) {
        if (y < level.getMinBuildHeight()) {
            return false;
        }
        if (y >= level.getMaxBuildHeight()) {
            return true;
        }
        ChunkAccess chunk = resolveChunk(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        cursor.set(x, y, z);
        BlockState state = chunk.getBlockState(cursor);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA) || fluid.is(FluidTags.WATER)) {
            return false;
        }
        return state.getCollisionShape(chunk, cursor).isEmpty();
    }

    private ChunkAccess resolveChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (chunkCache.containsKey(key)) {
            return chunkCache.get(key);
        }
        ChunkAccess loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (loaded != null) {
            chunkCache.put(key, loaded);
            return loaded;
        }
        if (!allowChunkProbing || probedCount >= maxProbedChunks) {
            chunkCache.put(key, null);
            return null;
        }
        ChunkAccess probed = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
        probedCount++;
        chunkCache.put(key, probed);
        return probed;
    }
}
