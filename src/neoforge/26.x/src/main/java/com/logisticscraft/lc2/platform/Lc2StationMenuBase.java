package com.logisticscraft.lc2.platform;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;

/**
 * NeoForge 26.x container menu base bridging {@link ContainerInput} into a version-neutral click guard.
 */
public abstract class Lc2StationMenuBase extends AbstractContainerMenu {

    protected Lc2StationMenuBase(MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    /** Returns {@code true} if this container click must be blocked (e.g. remote read-only mode). */
    protected boolean shouldBlockContainerClick(
            int slotId,
            int button,
            boolean isQuickMove,
            boolean isPickupAll,
            boolean isQuickCraft,
            Player player
    ) {
        return false;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (shouldBlockContainerClick(
                slotId,
                button,
                input == ContainerInput.QUICK_MOVE,
                input == ContainerInput.PICKUP_ALL,
                input == ContainerInput.QUICK_CRAFT,
                player
        )) {
            return;
        }
        super.clicked(slotId, button, input, player);
    }
}
