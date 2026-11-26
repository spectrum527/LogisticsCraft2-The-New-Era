package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * NeoForge 1.21.1 adjacent container item push/pull access supporting vanilla containers,
 * Double Chests, IC2 storage boxes, and any modded {@code IItemHandler}.
 * Automatically skips other LC2 logistics stations to guarantee loop-free I/O.
 */
public final class Lc2ItemAccess {

    private Lc2ItemAccess() {
    }

    /**
     * Inserts up to {@code stack.getCount()} items into the container at {@code targetPos}
     * via {@code targetFace}. Returns the count actually inserted (does not mutate {@code stack}).
     */
    public static int push(ServerLevel level, BlockPos targetPos, Direction targetFace, ItemStack stack) {
        if (stack.isEmpty() || !level.hasChunkAt(targetPos)) {
            return 0;
        }
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be instanceof AbstractLogisticsStationBlockEntity) {
            return 0;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, targetFace);
        if (handler != null) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false);
            return stack.getCount() - remainder.getCount();
        }
        Container container = resolveContainer(level, targetPos, be);
        if (container == null) {
            return 0;
        }
        return pushIntoContainer(container, targetFace, stack);
    }

    /**
     * Extracts up to {@code maxCount} items matching {@code filter} from {@code sourcePos}
     * via {@code sourceFace}.
     */
    public static ItemStack pull(
            ServerLevel level,
            BlockPos sourcePos,
            Direction sourceFace,
            int maxCount,
            Predicate<ItemStack> filter
    ) {
        if (maxCount <= 0 || !level.hasChunkAt(sourcePos)) {
            return ItemStack.EMPTY;
        }
        BlockEntity be = level.getBlockEntity(sourcePos);
        if (be instanceof AbstractLogisticsStationBlockEntity) {
            return ItemStack.EMPTY;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, sourcePos, sourceFace);
        if (handler != null) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack preview = handler.extractItem(slot, maxCount, true);
                if (!preview.isEmpty() && filter.test(preview)) {
                    int limit = Math.min(maxCount, preview.getMaxStackSize());
                    return handler.extractItem(slot, limit, false);
                }
            }
            return ItemStack.EMPTY;
        }
        Container container = resolveContainer(level, sourcePos, be);
        if (container == null) {
            return ItemStack.EMPTY;
        }
        return pullFromContainer(container, sourceFace, maxCount, filter);
    }

    private static Container resolveContainer(ServerLevel level, BlockPos pos, BlockEntity be) {
        BlockState state = level.getBlockState(pos);
        // Never bypass a modded BlockEntity's explicit null Capability (e.g. disabled faces or multiblock shells)
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return null;
        }
        if (state.getBlock() instanceof ChestBlock chest) {
            return ChestBlock.getContainer(chest, state, level, pos, true);
        }
        return be instanceof Container container ? container : null;
    }

    private static int pushIntoContainer(Container container, Direction face, ItemStack stack) {
        int remaining = stack.getCount();
        int[] slots = accessibleSlots(container, face);

        // Pass 1: merge with existing compatible stacks
        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (!canInsert(container, slot, stack, face)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int max = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
                int space = max - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining);
                    existing.grow(move);
                    remaining -= move;
                    container.setChanged();
                }
            }
        }

        // Pass 2: fill empty slots
        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (!canInsert(container, slot, stack, face)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                int max = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
                int move = Math.min(max, remaining);
                container.setItem(slot, stack.copyWithCount(move));
                remaining -= move;
                container.setChanged();
            }
        }
        return stack.getCount() - remaining;
    }

    private static ItemStack pullFromContainer(
            Container container,
            Direction face,
            int maxCount,
            Predicate<ItemStack> filter
    ) {
        int[] slots = accessibleSlots(container, face);
        for (int slot : slots) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !filter.test(existing)) {
                continue;
            }
            if (container instanceof WorldlyContainer worldly && !worldly.canTakeItemThroughFace(slot, existing, face)) {
                continue;
            }
            int take = Math.min(maxCount, Math.min(existing.getCount(), existing.getMaxStackSize()));
            ItemStack extracted = container.removeItem(slot, take);
            if (!extracted.isEmpty()) {
                container.setChanged();
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int[] accessibleSlots(Container container, Direction face) {
        if (container instanceof WorldlyContainer worldly) {
            return worldly.getSlotsForFace(face);
        }
        int[] slots = new int[container.getContainerSize()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private static boolean canInsert(Container container, int slot, ItemStack stack, Direction face) {
        if (!container.canPlaceItem(slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer worldly) {
            return worldly.canPlaceItemThroughFace(slot, stack, face);
        }
        return true;
    }
}
