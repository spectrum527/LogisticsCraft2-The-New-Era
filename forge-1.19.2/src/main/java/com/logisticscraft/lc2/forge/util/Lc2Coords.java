package com.logisticscraft.lc2.forge.util;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Conversion utilities between Minecraft Forge 1.19.2 types and pure-Java {@code lc2.core} types.
 */
public final class Lc2Coords {

    private Lc2Coords() {
    }

    /** Creates a {@link ResourceLocation} in the {@code lc2} namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(LogisticsConstants.MOD_ID, path);
    }

    /** Converts a Minecraft {@link BlockPos} to a {@link BlockPos3i}. */
    public static BlockPos3i toCore(BlockPos pos) {
        return new BlockPos3i(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Converts a {@link BlockPos3i} to a Minecraft {@link BlockPos}. */
    public static BlockPos toMc(BlockPos3i pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    /** Converts a Minecraft {@link Vec3} to a {@link Vec3d}. */
    public static Vec3d toCore(Vec3 vec) {
        return new Vec3d(vec.x, vec.y, vec.z);
    }

    /** Converts a {@link Vec3d} to a Minecraft {@link Vec3}. */
    public static Vec3 toMc(Vec3d vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    /** Returns the canonical qualified item registry ID for {@code stack}, or {@code ""} if empty/unregistered. */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return itemId(stack.getItem());
    }

    /** Returns the canonical qualified item registry ID for {@code item}, or {@code ""} if unregistered. */
    public static String itemId(Item item) {
        if (item == null) {
            return "";
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "" : key.toString();
    }
}
