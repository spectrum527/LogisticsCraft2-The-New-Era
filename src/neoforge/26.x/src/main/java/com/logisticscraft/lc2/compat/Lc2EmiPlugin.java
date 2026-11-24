package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiStack;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * NeoForge 26.x EMI plugin adding {@link EmiInfoRecipe} documentation pages for the
 * Communication Base Station and Operation Terminal.
 */
@EmiEntrypoint
public final class Lc2EmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipe(new EmiInfoRecipe(
                List.of(EmiStack.of(Lc2Registries.COMM_BASE_STATION_ITEM.get())),
                List.of(Component.translatable("info.lc2.comm_base_station")),
                Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "info/comm_base_station")
        ));
        registry.addRecipe(new EmiInfoRecipe(
                List.of(EmiStack.of(Lc2Registries.OPERATION_TERMINAL_ITEM.get())),
                List.of(Component.translatable("info.lc2.operation_terminal")),
                Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "info/operation_terminal")
        ));
    }
}
