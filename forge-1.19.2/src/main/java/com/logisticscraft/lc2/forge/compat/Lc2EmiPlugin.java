package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiStack;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Optional soft integration with EMI ({@code emi}) on Forge 1.19.2.
 * Loaded exclusively by EMI's {@link EmiEntrypoint} scanner when EMI is installed.
 */
@EmiEntrypoint
public final class Lc2EmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipe(new EmiInfoRecipe(
                List.of(EmiStack.of(Lc2Registries.COMM_BASE_STATION_ITEM.get())),
                List.of(Component.translatable("info.lc2.comm_base_station")),
                Lc2Coords.id("info/comm_base_station")
        ));
        registry.addRecipe(new EmiInfoRecipe(
                List.of(
                        EmiStack.of(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                        EmiStack.of(OperationTerminalItem.createChargedStack())
                ),
                List.of(Component.translatable("info.lc2.operation_terminal")),
                Lc2Coords.id("info/operation_terminal")
        ));
    }
}
