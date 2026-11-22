package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.world.LogisticsSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * NeoForge 1.21.1 SavedData persistence bridge for {@link LogisticsSavedData}.
 */
public abstract class Lc2SavedDataBase extends SavedData {

    private static final String STORAGE_NAME = "lc2_logistics";

    /** Retrieves or creates the per-dimension {@link LogisticsSavedData} for {@code level}. */
    public static LogisticsSavedData getOrCreate(ServerLevel level) {
        String dimId = Lc2Native.dimensionId(level);
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        () -> new LogisticsSavedData(dimId),
                        (tag, registries) -> LogisticsSavedData.loadFromTag(tag, registries, dimId)
                ),
                STORAGE_NAME
        );
    }

    /** Serializes this saved data instance into a CompoundTag. */
    protected abstract CompoundTag saveToTag(HolderLookup.Provider registries);

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.merge(saveToTag(registries));
        return tag;
    }
}
