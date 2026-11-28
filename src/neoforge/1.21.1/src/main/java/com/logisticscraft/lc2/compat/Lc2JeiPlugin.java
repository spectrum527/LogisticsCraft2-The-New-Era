package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 1.21.1 JEI plugin adding ingredient info pages for all LogisticsCraft² stations,
 * vehicles, remote terminals, RF chips, and upgrades.
 */
@JeiPlugin
public final class Lc2JeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "jei_plugin"
    );

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.SMART_PORT_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.smart_port")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.CARGO_STATION_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.cargo_station")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.COMM_BASE_STATION_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.comm_base_station")
        );
        registration.addIngredientInfo(
                List.of(
                        new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                        OperationTerminalItem.createChargedStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get())
                ),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.operation_terminal")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.electric_cargo_boat")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.TRANSPORT_DRONE_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.transport_drone")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.heavy_transport_drone")
        );
        registration.addIngredientInfo(
                List.of(
                        new ItemStack(Lc2Registries.RF_CHIP_1_ITEM.get()),
                        new ItemStack(Lc2Registries.RF_CHIP_2_ITEM.get()),
                        new ItemStack(Lc2Registries.RF_CHIP_3_ITEM.get())
                ),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.rf_chip")
        );
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.CHUNK_LOADING_UPGRADE_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.trera.chunk_loading_upgrade")
        );
    }
}
