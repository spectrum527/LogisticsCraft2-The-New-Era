package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Optional soft integration with Curios ({@code curios}) on Forge 1.19.2.
 * Uses a nested bridge class loaded strictly when {@code ModList.get().isLoaded("curios")} is
 * {@code true} so LC2 runs with zero {@link NoClassDefFoundError} when Curios is absent.
 */
public final class Lc2CuriosCompat {

    private static final String CURIOS_MOD_ID = "curios";

    private Lc2CuriosCompat() {
    }

    /**
     * Scans all non-cosmetic Curios slots equipped by {@code player} for an
     * {@link OperationTerminalItem} matching {@code requiredTerminalId} (or any terminal if blank/null).
     */
    @Nullable
    public static TerminalSessionService.FoundTerminal findTerminal(
            Player player,
            @Nullable String requiredTerminalId
    ) {
        if (!ModList.get().isLoaded(CURIOS_MOD_ID)) {
            return null;
        }
        return CuriosBridge.findTerminal(player, requiredTerminalId);
    }

    private static final class CuriosBridge {

        private CuriosBridge() {
        }

        @Nullable
        private static TerminalSessionService.FoundTerminal findTerminal(
                Player player,
                @Nullable String requiredTerminalId
        ) {
            Optional<ICuriosItemHandler> opt = CuriosApi.getCuriosHelper().getCuriosHandler(player).resolve();
            if (opt.isEmpty()) {
                return null;
            }
            boolean matchId = requiredTerminalId != null && !requiredTerminalId.isBlank();
            ICuriosItemHandler handler = opt.get();
            for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                IDynamicStackHandler stacks = entry.getValue().getStacks();
                for (int slot = 0; slot < stacks.getSlots(); slot++) {
                    ItemStack stack = stacks.getStackInSlot(slot);
                    if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
                        continue;
                    }
                    if (!matchId || requiredTerminalId.equals(OperationTerminalItem.getTerminalId(stack))) {
                        final int slotIndex = slot;
                        return new TerminalSessionService.FoundTerminal(
                                stack,
                                () -> stacks.setStackInSlot(slotIndex, stack)
                        );
                    }
                }
            }
            return null;
        }
    }
}
