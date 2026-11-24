package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Optional soft integration with Just Enough Items ({@code jei}) on Forge 1.19.2.
 * Loaded exclusively by JEI's {@link JeiPlugin} scanner when JEI is installed.
 */
@JeiPlugin
public final class Lc2JeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID = Lc2Coords.id("jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(
                new ItemStack(Lc2Registries.COMM_BASE_STATION_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.lc2.comm_base_station")
        );
        registration.addIngredientInfo(
                List.of(
                        new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                        OperationTerminalItem.createChargedStack()
                ),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.lc2.operation_terminal")
        );
    }
}
