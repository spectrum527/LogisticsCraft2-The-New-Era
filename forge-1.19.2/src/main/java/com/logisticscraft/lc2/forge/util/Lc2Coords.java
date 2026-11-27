package com.logisticscraft.lc2.forge.util;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Conversion utilities and common/mod tag helpers between Minecraft Forge 1.19.2 types and
 * pure-Java {@code lc2.core} types.
 */
public final class Lc2Coords {

    /** Common Forge wrench item tag ({@code #forge:tools/wrench}). */
    public static final TagKey<Item> TAG_FORGE_TOOLS_WRENCH = itemTag("forge", "tools/wrench");

    /** Plural fallback wrench item tag ({@code #forge:tools/wrenches}). */
    public static final TagKey<Item> TAG_FORGE_TOOLS_WRENCHES = itemTag("forge", "tools/wrenches");

    /** Legacy/alias common wrench item tag ({@code #forge:wrenches}). */
    public static final TagKey<Item> TAG_FORGE_WRENCHES = itemTag("forge", "wrenches");

    /** Optional LC2 item tag extending the single-use battery slot ({@code #lc2:single_use_batteries}). */
    public static final TagKey<Item> TAG_SINGLE_USE_BATTERIES =
            itemTag(LogisticsConstants.MOD_ID, "single_use_batteries");

    /** Common Forge relocation-protection block tag ({@code #forge:relocation_not_supported}). */
    public static final TagKey<Block> TAG_FORGE_RELOCATION_NOT_SUPPORTED =
            blockTag("forge", "relocation_not_supported");

    private Lc2Coords() {
    }

    /** Creates an item {@link TagKey} for the given namespace and path. */
    public static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registry.ITEM_REGISTRY, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Creates a block {@link TagKey} for the given namespace and path. */
    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registry.BLOCK_REGISTRY, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Returns {@code true} if {@code stack} is a supported single-use battery item. */
    public static boolean isSingleUseBattery(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(TAG_SINGLE_USE_BATTERIES)
                || LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(itemId(stack));
    }

    /**
     * Returns {@code true} if {@code stack} is a third-party wrench ({@code #forge:tools/wrench},
     * {@code #forge:tools/wrenches}, or {@code #forge:wrenches}), excluding IC2's own wrench items
     * so IC2's {@code WrenchTarget} never double-triggers.
     */
    public static boolean isThirdPartyWrench(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = itemId(stack);
        if (id.startsWith(LogisticsConstants.IC2_MOD_ID + ":")) {
            return false;
        }
        return stack.is(TAG_FORGE_TOOLS_WRENCH)
                || stack.is(TAG_FORGE_TOOLS_WRENCHES)
                || stack.is(TAG_FORGE_WRENCHES);
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
