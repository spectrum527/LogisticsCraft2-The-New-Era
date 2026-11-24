package com.logisticscraft.lc2.item;

import com.logisticscraft.lc2.compat.Lc2CuriosCompat;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Locates {@link OperationTerminalItem} stacks carried by a player in priority order:
 * mainhand, offhand, hotbar, main inventory, and active non-cosmetic Curios slots.
 */
public final class TerminalLocator {

    /** A located terminal stack paired with a callback to persist/sync mutations. */
    public record LocatedTerminal(ItemStack stack, Runnable markChanged) {
    }

    private TerminalLocator() {
    }

    /** Finds the first {@link OperationTerminalItem} available to {@code player}. */
    public static Optional<LocatedTerminal> findFirstTerminal(Player player) {
        return findMatching(player, stack -> stack.getItem() instanceof OperationTerminalItem);
    }

    /**
     * Finds the terminal matching {@code terminalId} (or the first available terminal if
     * {@code terminalId} is null or blank).
     */
    public static Optional<LocatedTerminal> findTerminalById(Player player, String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return findFirstTerminal(player);
        }
        return findMatching(
                player,
                stack -> stack.getItem() instanceof OperationTerminalItem
                        && terminalId.equals(OperationTerminalItem.getTerminalId(stack))
        );
    }

    /**
     * Finds a terminal carried by {@code player} bound to {@code (dimensionId, commPos)},
     * matching {@code terminalId} when non-blank.
     */
    public static Optional<LocatedTerminal> findTerminalBoundTo(
            Player player,
            String terminalId,
            String dimensionId,
            BlockPos commPos
    ) {
        if (player == null || dimensionId == null || commPos == null) {
            return Optional.empty();
        }
        if (terminalId != null && !terminalId.isBlank()) {
            Optional<LocatedTerminal> exact = findMatching(
                    player,
                    stack -> stack.getItem() instanceof OperationTerminalItem
                            && terminalId.equals(OperationTerminalItem.getTerminalId(stack))
                            && OperationTerminalItem.isBoundTo(stack, dimensionId, commPos)
            );
            if (exact.isPresent()) {
                return exact;
            }
        }
        return findMatching(
                player,
                stack -> stack.getItem() instanceof OperationTerminalItem
                        && OperationTerminalItem.isBoundTo(stack, dimensionId, commPos)
        );
    }

    /** Returns {@code true} if {@code player} carries a terminal bound to {@code (dimensionId, commPos)}. */
    public static boolean hasTerminalBoundTo(
            Player player,
            String terminalId,
            String dimensionId,
            BlockPos commPos
    ) {
        return findTerminalBoundTo(player, terminalId, dimensionId, commPos).isPresent();
    }

    private static Optional<LocatedTerminal> findMatching(Player player, Predicate<ItemStack> predicate) {
        if (player == null) {
            return Optional.empty();
        }
        Runnable syncInventory = () -> {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        };

        // 1. Mainhand
        ItemStack main = player.getMainHandItem();
        if (!main.isEmpty() && predicate.test(main)) {
            return Optional.of(new LocatedTerminal(main, syncInventory));
        }

        // 2. Offhand
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && predicate.test(off)) {
            return Optional.of(new LocatedTerminal(off, syncInventory));
        }

        // 3. Hotbar (0..8) & Main Inventory (9..35)
        Inventory inv = player.getInventory();
        int limit = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return Optional.of(new LocatedTerminal(stack, syncInventory));
            }
        }

        // 4. Active non-cosmetic Curios slots
        return Lc2CuriosCompat.findActiveCurio(player, predicate);
    }
}
