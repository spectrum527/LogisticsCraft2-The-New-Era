package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiStack;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * NeoForge 26.x EMI plugin adding {@link EmiInfoRecipe} documentation pages for all
 * LogisticsCraft² stations, vehicles, remote terminals, RF chips, and upgrades.
 */
@EmiEntrypoint
public final class Lc2EmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        addInfo(registry, "smart_port", List.of(EmiStack.of(Lc2Registries.SMART_PORT_ITEM.get())),
                "info.lc2.smart_port");
        addInfo(registry, "cargo_station", List.of(EmiStack.of(Lc2Registries.CARGO_STATION_ITEM.get())),
                "info.lc2.cargo_station");
        addInfo(registry, "comm_base_station", List.of(EmiStack.of(Lc2Registries.COMM_BASE_STATION_ITEM.get())),
                "info.lc2.comm_base_station");
        addInfo(registry, "operation_terminal", List.of(
                EmiStack.of(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                EmiStack.of(OperationTerminalItem.createChargedStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()))
        ), "info.lc2.operation_terminal");
        addInfo(registry, "electric_cargo_boat", List.of(EmiStack.of(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())),
                "info.lc2.electric_cargo_boat");
        addInfo(registry, "transport_drone", List.of(EmiStack.of(Lc2Registries.TRANSPORT_DRONE_ITEM.get())),
                "info.lc2.transport_drone");
        addInfo(registry, "heavy_transport_drone", List.of(EmiStack.of(Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get())),
                "info.lc2.heavy_transport_drone");
        addInfo(registry, "rf_chip", List.of(
                EmiStack.of(Lc2Registries.RF_CHIP_1_ITEM.get()),
                EmiStack.of(Lc2Registries.RF_CHIP_2_ITEM.get()),
                EmiStack.of(Lc2Registries.RF_CHIP_3_ITEM.get())
        ), "info.lc2.rf_chip");
        addInfo(registry, "chunk_loading_upgrade", List.of(EmiStack.of(Lc2Registries.CHUNK_LOADING_UPGRADE_ITEM.get())),
                "info.lc2.chunk_loading_upgrade");
    }

    private static void addInfo(EmiRegistry registry, String id, List<EmiStack> stacks, String key) {
        registry.addRecipe(new EmiInfoRecipe(
                List.copyOf(stacks),
                List.of(Component.translatable(key)),
                Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "/info/" + id)
        ));
    }
}
