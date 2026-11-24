package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 26.x JEI plugin adding ingredient info pages for the Communication Base Station
 * and Operation Terminal.
 */
@JeiPlugin
public final class Lc2JeiPlugin implements IModPlugin {

    private static final Identifier PLUGIN_UID = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "jei_plugin"
    );

    @Override
    public Identifier getPluginUid() {
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
                new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("info.lc2.operation_terminal")
        );
    }
}
