package com.logisticscraft.lc2.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 1.21.1 NBT compatibility helpers shared across SavedData, BlockEntities, and Entities.
 */
public final class Lc2Nbt {

    private static final Logger LOGGER = LoggerFactory.getLogger(Lc2Nbt.class);

    private Lc2Nbt() {
    }

    /** Reads an integer from {@code tag}, returning {@code fallback} if absent. */
    public static int getInt(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : fallback;
    }

    /** Reads a long from {@code tag}, returning {@code fallback} if absent. */
    public static long getLong(CompoundTag tag, String key, long fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getLong(key) : fallback;
    }

    /** Reads a double from {@code tag}, returning {@code fallback} if absent. */
    public static double getDouble(CompoundTag tag, String key, double fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getDouble(key) : fallback;
    }

    /** Reads a float from {@code tag}, returning {@code fallback} if absent. */
    public static float getFloat(CompoundTag tag, String key, float fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(key) : fallback;
    }

    /** Reads a boolean from {@code tag}, returning {@code fallback} if absent. */
    public static boolean getBoolean(CompoundTag tag, String key, boolean fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getBoolean(key) : fallback;
    }

    /** Reads a string from {@code tag}, returning {@code fallback} if absent. */
    public static String getString(CompoundTag tag, String key, String fallback) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback;
    }

    /** Reads a child compound tag, returning an empty CompoundTag if absent. */
    public static CompoundTag getCompound(CompoundTag tag, String key) {
        return tag.getCompound(key);
    }

    /** Reads a list of CompoundTags from {@code tag}. */
    public static List<CompoundTag> getCompoundList(CompoundTag tag, String key) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        return result;
    }

    /** Encodes a list of ItemStacks (including empty slots) into {@code parent} under {@code key}. */
    public static void saveItemList(
            CompoundTag parent,
            String key,
            List<ItemStack> stacks,
            HolderLookup.Provider registries
    ) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        ItemStack.OPTIONAL_CODEC.listOf()
                .encodeStart(ops, stacks)
                .resultOrPartial(err -> LOGGER.warn("Failed to encode LC2 item list {}: {}", key, err))
                .ifPresent(encoded -> parent.put(key, encoded));
    }

    /** Decodes a list of ItemStacks from {@code parent} under {@code key}. */
    public static List<ItemStack> loadItemList(
            CompoundTag parent,
            String key,
            HolderLookup.Provider registries
    ) {
        if (!parent.contains(key)) {
            return new ArrayList<>();
        }
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        return ItemStack.OPTIONAL_CODEC.listOf()
                .parse(ops, parent.get(key))
                .resultOrPartial(err -> LOGGER.warn("Failed to decode LC2 item list {}: {}", key, err))
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }
}
