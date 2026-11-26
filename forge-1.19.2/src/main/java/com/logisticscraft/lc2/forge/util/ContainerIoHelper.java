package com.logisticscraft.lc2.forge.util;

import com.industrialage.ia2.world.MachineAutomation;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Adjacent-container I/O helper supporting Forge {@link IItemHandler}, vanilla {@link ChestBlock}
 * double chests, {@link WorldlyContainer}, and plain {@link Container} (barrels, shulker boxes,
 * IC2 storage boxes) while preventing station-to-station loops and honoring IC2 ejector/pulling faces.
 */
public final class ContainerIoHelper {

    private static final Method IC2_MATCHES_FILTER = resolveMatchesFilterMethod();

    private ContainerIoHelper() {
    }

    private static Method resolveMatchesFilterMethod() {
        try {
            return MachineAutomation.class.getMethod("matchesFilter", ItemStack.class, ItemStack.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean matchesAdvancedFilter(ItemStack upgrade, ItemStack candidate) {
        if (IC2_MATCHES_FILTER == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(IC2_MATCHES_FILTER.invoke(null, upgrade, candidate));
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static boolean permitsTransfer(
            List<ItemStack> upgradeStacks,
            Direction face,
            ItemStack candidate,
            boolean isPush
    ) {
        if (upgradeStacks == null || upgradeStacks.isEmpty() || IC2_MATCHES_FILTER == null) {
            return true;
        }
        String basicId = isPush ? LogisticsConstants.UPGRADE_EJECTOR : LogisticsConstants.UPGRADE_PULLING;
        String advId = isPush ? LogisticsConstants.UPGRADE_ADVANCED_EJECTOR : LogisticsConstants.UPGRADE_ADVANCED_PULLING;
        boolean foundCoveringUpgrade = false;
        for (ItemStack upg : upgradeStacks) {
            String id = Lc2Coords.itemId(upg);
            boolean isBasic = basicId.equals(id);
            boolean isAdv = advId.equals(id);
            if (!isBasic && !isAdv) {
                continue;
            }
            if (!MachineAutomation.faces(upg).contains(face)) {
                continue;
            }
            foundCoveringUpgrade = true;
            if (isBasic || matchesAdvancedFilter(upg, candidate)) {
                return true;
            }
        }
        return !foundCoveringUpgrade;
    }

    private static boolean isVanillaContainerBlock(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && "minecraft".equals(key.getNamespace());
    }

    /**
     * Resolves target push faces from installed {@code ic2:ejector_upgrade} /
     * {@code ic2:advanced_ejector_upgrade} stacks, or returns an empty list when none are installed.
     */
    public static List<Direction> resolvePushFaces(List<ItemStack> upgradeStacks) {
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        boolean hasEjector = false;
        for (ItemStack stack : upgradeStacks) {
            String id = Lc2Coords.itemId(stack);
            if (LogisticsConstants.UPGRADE_EJECTOR.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_EJECTOR.equals(id)) {
                hasEjector = true;
                faces.addAll(MachineAutomation.faces(stack));
            }
        }
        return hasEjector ? new ArrayList<>(faces) : List.of();
    }

    /**
     * Resolves source pull faces from installed {@code ic2:pulling_upgrade} /
     * {@code ic2:advanced_pulling_upgrade} stacks, or returns an empty list when none are installed.
     */
    public static List<Direction> resolvePullFaces(List<ItemStack> upgradeStacks) {
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        boolean hasPulling = false;
        for (ItemStack stack : upgradeStacks) {
            String id = Lc2Coords.itemId(stack);
            if (LogisticsConstants.UPGRADE_PULLING.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_PULLING.equals(id)) {
                hasPulling = true;
                faces.addAll(MachineAutomation.faces(stack));
            }
        }
        return hasPulling ? new ArrayList<>(faces) : List.of();
    }

    /**
     * Pushes as much of {@code stack} as possible into adjacent containers along {@code faces}.
     * Returns the leftover stack that could not fit (empty if fully inserted).
     */
    public static ItemStack pushToAdjacent(
            ServerLevel level,
            BlockPos stationPos,
            List<Direction> faces,
            ItemStack stack
    ) {
        return pushToAdjacent(level, stationPos, faces, stack, List.of());
    }

    /**
     * Pushes as much of {@code stack} as possible into adjacent containers along {@code faces},
     * honoring any installed IC2 advanced ejector item filters.
     */
    public static ItemStack pushToAdjacent(
            ServerLevel level,
            BlockPos stationPos,
            List<Direction> faces,
            ItemStack stack,
            List<ItemStack> upgradeStacks
    ) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (Direction face : faces) {
            if (remaining.isEmpty()) {
                break;
            }
            if (!permitsTransfer(upgradeStacks, face, remaining, true)) {
                continue;
            }
            BlockPos neighborPos = stationPos.relative(face);
            if (!level.hasChunkAt(neighborPos)) {
                continue;
            }
            remaining = insertIntoBlock(level, neighborPos, face.getOpposite(), remaining);
        }
        return remaining;
    }

    /**
     * Pulls items from adjacent containers along {@code faces} up to {@code maxItems} total items,
     * inserting them directly into {@code destination} (a vehicle hold or buffer container).
     *
     * @return total number of items moved
     */
    public static int pullFromAdjacentInto(
            ServerLevel level,
            BlockPos stationPos,
            List<Direction> faces,
            Set<Direction> excludedFaces,
            Container destination,
            int destStartSlot,
            int destEndExclusive,
            int maxItems
    ) {
        return pullFromAdjacentInto(
                level, stationPos, faces, excludedFaces, destination, destStartSlot, destEndExclusive, maxItems, List.of()
        );
    }

    /**
     * Pulls items from adjacent containers along {@code faces} up to {@code maxItems} total items,
     * honoring any installed IC2 advanced pulling upgrade item filters.
     */
    public static int pullFromAdjacentInto(
            ServerLevel level,
            BlockPos stationPos,
            List<Direction> faces,
            Set<Direction> excludedFaces,
            Container destination,
            int destStartSlot,
            int destEndExclusive,
            int maxItems,
            List<ItemStack> upgradeStacks
    ) {
        if (maxItems <= 0) {
            return 0;
        }
        int totalMoved = 0;
        for (Direction face : faces) {
            if (totalMoved >= maxItems) {
                break;
            }
            if (excludedFaces != null && excludedFaces.contains(face)) {
                continue;
            }
            BlockPos neighborPos = stationPos.relative(face);
            if (!level.hasChunkAt(neighborPos)) {
                continue;
            }
            totalMoved += extractFromBlockInto(
                    level,
                    neighborPos,
                    face,
                    face.getOpposite(),
                    destination,
                    destStartSlot,
                    destEndExclusive,
                    maxItems - totalMoved,
                    upgradeStacks
            );
        }
        return totalMoved;
    }

    /**
     * Inserts {@code stack} into {@code container} across slots {@code [startSlot, endExclusive)},
     * merging into matching stacks first, then empty slots. Returns leftover items.
     */
    public static ItemStack insertIntoContainer(
            Container container,
            int startSlot,
            int endExclusive,
            ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        int maxStack = Math.min(remaining.getMaxStackSize(), container.getMaxStackSize());

        // Pass 1: merge with existing compatible stacks
        for (int slot = startSlot; slot < endExclusive && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty()
                    && ItemStack.isSameItemSameTags(existing, remaining)
                    && existing.getCount() < maxStack) {
                int space = Math.min(maxStack, existing.getMaxStackSize()) - existing.getCount();
                int toMove = Math.min(space, remaining.getCount());
                if (toMove > 0) {
                    existing.grow(toMove);
                    remaining.shrink(toMove);
                    container.setChanged();
                }
            }
        }

        // Pass 2: fill empty slots
        for (int slot = startSlot; slot < endExclusive && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() && container.canPlaceItem(slot, remaining)) {
                int toMove = Math.min(maxStack, remaining.getCount());
                if (toMove > 0) {
                    ItemStack placed = remaining.split(toMove);
                    container.setItem(slot, placed);
                    container.setChanged();
                }
            }
        }
        return remaining;
    }

    /**
     * Checks how many items of {@code sample} can currently fit into {@code container}
     * across slots {@code [startSlot, endExclusive)}.
     */
    public static int capacityFor(
            Container container,
            int startSlot,
            int endExclusive,
            ItemStack sample
    ) {
        if (sample.isEmpty()) {
            return 0;
        }
        int maxStack = Math.min(sample.getMaxStackSize(), container.getMaxStackSize());
        int totalSpace = 0;
        for (int slot = startSlot; slot < endExclusive; slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                if (container.canPlaceItem(slot, sample)) {
                    totalSpace += maxStack;
                }
            } else if (ItemStack.isSameItemSameTags(existing, sample)) {
                int slotLimit = Math.min(maxStack, existing.getMaxStackSize());
                if (existing.getCount() < slotLimit) {
                    totalSpace += slotLimit - existing.getCount();
                }
            }
        }
        return totalSpace;
    }

    private static ItemStack insertIntoBlock(
            ServerLevel level,
            BlockPos pos,
            Direction accessSide,
            ItemStack stack
    ) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof AbstractLogisticsStationBlockEntity) {
            return stack;
        }

        // Prefer ChestBlock.getContainer(ignoreBlocked=true) so double chests expose all 54 slots
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container chestContainer = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (chestContainer != null) {
                return insertIntoVanillaContainer(chestContainer, accessSide, stack);
            }
        }

        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, accessSide).orElse(null);
        if (handler != null) {
            return ItemHandlerHelper.insertItemStacked(handler, stack, false);
        }

        if (isVanillaContainerBlock(state) && be instanceof Container container) {
            return insertIntoVanillaContainer(container, accessSide, stack);
        }
        return stack;
    }

    private static ItemStack insertIntoVanillaContainer(
            Container container,
            Direction accessSide,
            ItemStack stack
    ) {
        ItemStack remaining = stack.copy();
        int[] slots = container instanceof WorldlyContainer wc
                ? wc.getSlotsForFace(accessSide)
                : allSlotIndices(container.getContainerSize());

        // Pass 1: stack onto existing items
        for (int slot : slots) {
            if (remaining.isEmpty()) {
                break;
            }
            if (!canInsertVanilla(container, slot, remaining, accessSide)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)) {
                int limit = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
                int space = limit - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.grow(moved);
                    remaining.shrink(moved);
                    container.setChanged();
                }
            }
        }

        // Pass 2: place into empty slots
        for (int slot : slots) {
            if (remaining.isEmpty()) {
                break;
            }
            if (!canInsertVanilla(container, slot, remaining, accessSide)) {
                continue;
            }
            if (container.getItem(slot).isEmpty()) {
                int limit = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                int moved = Math.min(limit, remaining.getCount());
                if (moved > 0) {
                    container.setItem(slot, remaining.split(moved));
                    container.setChanged();
                }
            }
        }
        return remaining;
    }

    private static int extractFromBlockInto(
            ServerLevel level,
            BlockPos pos,
            Direction pullFace,
            Direction accessSide,
            Container destination,
            int destStartSlot,
            int destEndExclusive,
            int maxItems,
            List<ItemStack> upgradeStacks
    ) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof AbstractLogisticsStationBlockEntity) {
            return 0;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container chestContainer = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (chestContainer != null) {
                return extractFromVanillaContainerInto(
                        chestContainer, pullFace, accessSide, destination, destStartSlot, destEndExclusive, maxItems, upgradeStacks
                );
            }
        }

        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, accessSide).orElse(null);
        if (handler != null) {
            int moved = 0;
            for (int slot = 0; slot < handler.getSlots() && moved < maxItems; slot++) {
                int budget = maxItems - moved;
                ItemStack preview = handler.extractItem(slot, budget, true);
                if (preview.isEmpty() || !permitsTransfer(upgradeStacks, pullFace, preview, false)) {
                    continue;
                }
                int fit = Math.min(preview.getCount(), capacityFor(destination, destStartSlot, destEndExclusive, preview));
                if (fit <= 0) {
                    continue;
                }
                ItemStack extracted = handler.extractItem(slot, fit, false);
                if (extracted.isEmpty()) {
                    continue;
                }
                int extractedCount = extracted.getCount();
                ItemStack leftover = insertIntoContainer(destination, destStartSlot, destEndExclusive, extracted);
                moved += extractedCount - leftover.getCount();
                if (!leftover.isEmpty()) {
                    // Return any unexpected leftover back into the handler so items are never lost
                    ItemHandlerHelper.insertItemStacked(handler, leftover, false);
                }
            }
            return moved;
        }

        if (isVanillaContainerBlock(state) && be instanceof Container container) {
            return extractFromVanillaContainerInto(
                    container, pullFace, accessSide, destination, destStartSlot, destEndExclusive, maxItems, upgradeStacks
            );
        }
        return 0;
    }

    private static int extractFromVanillaContainerInto(
            Container source,
            Direction pullFace,
            Direction accessSide,
            Container destination,
            int destStartSlot,
            int destEndExclusive,
            int maxItems,
            List<ItemStack> upgradeStacks
    ) {
        int[] slots = source instanceof WorldlyContainer wc
                ? wc.getSlotsForFace(accessSide)
                : allSlotIndices(source.getContainerSize());

        int moved = 0;
        for (int slot : slots) {
            if (moved >= maxItems) {
                break;
            }
            ItemStack stack = source.getItem(slot);
            if (stack.isEmpty() || !permitsTransfer(upgradeStacks, pullFace, stack, false)) {
                continue;
            }
            if (source instanceof WorldlyContainer wc && !wc.canTakeItemThroughFace(slot, stack, accessSide)) {
                continue;
            }
            int budget = Math.min(maxItems - moved, stack.getCount());
            int fit = Math.min(budget, capacityFor(destination, destStartSlot, destEndExclusive, stack));
            if (fit <= 0) {
                continue;
            }
            ItemStack taken = source.removeItem(slot, fit);
            if (taken.isEmpty()) {
                continue;
            }
            int takenCount = taken.getCount();
            ItemStack leftover = insertIntoContainer(destination, destStartSlot, destEndExclusive, taken);
            moved += takenCount - leftover.getCount();
            if (!leftover.isEmpty()) {
                ItemStack current = source.getItem(slot);
                if (current.isEmpty()) {
                    source.setItem(slot, leftover);
                } else {
                    current.grow(leftover.getCount());
                }
                source.setChanged();
            }
        }
        return moved;
    }

    private static boolean canInsertVanilla(Container container, int slot, ItemStack stack, Direction side) {
        if (!container.canPlaceItem(slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer wc) {
            return wc.canPlaceItemThroughFace(slot, stack, side);
        }
        return true;
    }

    private static int[] allSlotIndices(int size) {
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }
        return indices;
    }
}
