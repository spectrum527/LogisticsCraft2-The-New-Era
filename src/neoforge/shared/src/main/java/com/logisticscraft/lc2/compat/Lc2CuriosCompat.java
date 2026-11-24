package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.item.TerminalLocator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Optional soft-dependency bridge for Curios ({@code curios}).
 * <p>
 * All Curios API references are isolated inside {@link Bridge}, which is only loaded when
 * {@code ModList.get().isLoaded("curios")} is {@code true}.
 */
public final class Lc2CuriosCompat {

    private static final String CURIOS_MOD_ID = "curios";

    private Lc2CuriosCompat() {
    }

    /** Returns {@code true} if the Curios mod is currently loaded. */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(CURIOS_MOD_ID);
    }

    /**
     * Searches active, non-cosmetic Curios equipment slots on {@code player} for the first stack
     * matching {@code predicate}. Returns empty if Curios is not installed or no match is found.
     */
    public static Optional<TerminalLocator.LocatedTerminal> findActiveCurio(
            Player player,
            Predicate<ItemStack> predicate
    ) {
        if (player == null || !isLoaded()) {
            return Optional.empty();
        }
        return Bridge.find(player, predicate);
    }

    private static final class Bridge {
        private static Optional<TerminalLocator.LocatedTerminal> find(
                Player player,
                Predicate<ItemStack> predicate
        ) {
            Optional<ICuriosItemHandler> opt = CuriosApi.getCuriosInventory(player);
            if (opt.isEmpty()) {
                return Optional.empty();
            }
            ICuriosItemHandler handler = opt.get();
            for (var entry : handler.getCurios().entrySet()) {
                String slotId = entry.getKey();
                ICurioStacksHandler stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                int count = stacks.getSlots();
                for (int i = 0; i < count; i++) {
                    if (!handler.isSlotActive(slotId, i)) {
                        continue;
                    }
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (!stack.isEmpty() && predicate.test(stack)) {
                        final int slotIndex = i;
                        return Optional.of(new TerminalLocator.LocatedTerminal(
                                stack,
                                () -> stacks.setStackInSlot(slotIndex, stack)
                        ));
                    }
                }
            }
            return Optional.empty();
        }
    }
}
