package com.logisticscraft.lc2.menu;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/**
 * 54-slot (6x9) Double-Chest-sized cargo hold menu for {@code lc2:electric_cargo_boat}.
 */
public final class ElectricCargoBoatMenu extends ChestMenu {

    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, Container container) {
        super(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), containerId, playerInventory, container, 6);
    }

    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf ignored) {
        this(containerId, playerInventory, new SimpleContainer(LogisticsConstants.BOAT_CARGO_SLOTS));
    }
}
