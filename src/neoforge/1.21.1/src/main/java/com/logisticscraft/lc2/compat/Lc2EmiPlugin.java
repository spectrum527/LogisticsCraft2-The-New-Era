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
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 1.21.1 EMI plugin adding {@link EmiInfoRecipe} documentation pages for all
 * LogisticsCraft² stations, vehicles, remote terminals, RF chips, and upgrades.
 */
@EmiEntrypoint
public final class Lc2EmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        addInfo(registry, "smart_port", List.of(EmiStack.of(Lc2Registries.SMART_PORT_ITEM.get())),
                "info.trera.smart_port");
        addInfo(registry, "cargo_station", List.of(EmiStack.of(Lc2Registries.CARGO_STATION_ITEM.get())),
                "info.trera.cargo_station");
        addInfo(registry, "comm_base_station", List.of(EmiStack.of(Lc2Registries.COMM_BASE_STATION_ITEM.get())),
                "info.trera.comm_base_station");
        addInfo(registry, "operation_terminal", List.of(
                EmiStack.of(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                EmiStack.of(OperationTerminalItem.createChargedStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()))
        ), "info.trera.operation_terminal");
        addInfo(registry, "electric_cargo_boat", List.of(EmiStack.of(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())),
                "info.trera.electric_cargo_boat");
        addInfo(registry, "transport_drone", List.of(EmiStack.of(Lc2Registries.TRANSPORT_DRONE_ITEM.get())),
                "info.trera.transport_drone");
        addInfo(registry, "heavy_transport_drone", List.of(EmiStack.of(Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get())),
                "info.trera.heavy_transport_drone");
        addInfo(registry, "rf_chip", List.of(
                EmiStack.of(Lc2Registries.RF_CHIP_1_ITEM.get()),
                EmiStack.of(Lc2Registries.RF_CHIP_2_ITEM.get()),
                EmiStack.of(Lc2Registries.RF_CHIP_3_ITEM.get())
        ), "info.trera.rf_chip");
        addInfo(registry, "chunk_loading_upgrade", List.of(EmiStack.of(Lc2Registries.CHUNK_LOADING_UPGRADE_ITEM.get())),
                "info.trera.chunk_loading_upgrade");
    }

    private static void addInfo(EmiRegistry registry, String id, List<EmiStack> stacks, String key) {
        registry.addRecipe(new EmiInfoRecipe(
                List.copyOf(stacks),
                List.of(Component.translatable(key)),
                ResourceLocation.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "/info/" + id)
        ));
    }
}
