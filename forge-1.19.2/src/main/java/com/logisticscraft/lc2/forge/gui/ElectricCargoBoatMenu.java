package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import javax.annotation.Nullable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;

/**
 * 54-slot (6x9) cargo hold menu for {@link ElectricCargoBoatEntity} using vanilla's standard
 * {@code generic_54.png} layout via {@link ChestMenu}.
 */
public class ElectricCargoBoatMenu extends ChestMenu {

    @Nullable
    private final ElectricCargoBoatEntity boat;

    /** Client constructor invoked by {@code IForgeMenuType}. */
    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, resolveBoat(playerInventory, extraData.readVarInt()));
    }

    /** Server constructor invoked by {@link ElectricCargoBoatEntity#createMenu}. */
    public ElectricCargoBoatMenu(int containerId, Inventory playerInventory, @Nullable ElectricCargoBoatEntity boat) {
        super(
                Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(),
                containerId,
                playerInventory,
                resolveContainer(boat),
                6
        );
        this.boat = boat;
    }

    private static Container resolveContainer(@Nullable ElectricCargoBoatEntity boat) {
        return boat != null ? boat.getCargoContainer() : new SimpleContainer(LogisticsConstants.BOAT_CARGO_SLOTS);
    }

    @Nullable
    private static ElectricCargoBoatEntity resolveBoat(Inventory playerInventory, int entityId) {
        Entity ent = playerInventory.player.level.getEntity(entityId);
        return ent instanceof ElectricCargoBoatEntity b ? b : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.boat == null) {
            return player.level.isClientSide;
        }
        return !this.boat.isRemoved()
                && !this.boat.isAutonomousTransit()
                && this.boat.distanceToSqr(player) <= 64.0;
    }
}
