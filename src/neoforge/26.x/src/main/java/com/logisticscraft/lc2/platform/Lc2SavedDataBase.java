package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * NeoForge 26.x SavedData persistence bridge using {@link SavedDataType} and {@link CompoundTag#CODEC}.
 */
public abstract class Lc2SavedDataBase extends SavedData {

    private static final SavedDataType<LogisticsSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "logistics"),
            level -> new LogisticsSavedData(Lc2Native.dimensionId(level)),
            level -> {
                String dimId = Lc2Native.dimensionId(level);
                HolderLookup.Provider registries = level.registryAccess();
                return CompoundTag.CODEC.xmap(
                        tag -> LogisticsSavedData.loadFromTag(tag, registries, dimId),
                        data -> data.saveToTag(registries)
                );
            }
    );

    /** Retrieves or creates the per-dimension {@link LogisticsSavedData} for {@code level}. */
    public static LogisticsSavedData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Serializes this saved data instance into a CompoundTag. */
    protected abstract CompoundTag saveToTag(HolderLookup.Provider registries);
}
