package com.logisticscraft.lc2.menu;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;

/**
 * 54-slot (6x9) Double-Chest-sized cargo hold menu for {@code lc2:electric_cargo_boat}.
 */
public final class ElectricCargoBoatMenu extends ChestMenu {

    private final Container cargoContainer;

    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, Container container) {
        super(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), containerId, playerInventory, container, 6);
        this.cargoContainer = container;
    }

    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf ignored) {
        this(containerId, playerInventory, new SimpleContainer(LogisticsConstants.BOAT_CARGO_SLOTS));
    }

    @Override
    public boolean stillValid(Player player) {
        if (cargoContainer instanceof ElectricCargoBoatEntity boat) {
            if (!boat.isAlive() || boat.isAutonomousTransit()) {
                return false;
            }
        }
        return super.stillValid(player);
    }
}
