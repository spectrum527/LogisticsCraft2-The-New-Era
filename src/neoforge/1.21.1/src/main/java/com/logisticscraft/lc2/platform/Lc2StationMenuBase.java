package com.logisticscraft.lc2.platform;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;

/**
 * NeoForge 1.21.1 container menu base bridging {@link ClickType} into a version-neutral click guard.
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
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (shouldBlockContainerClick(
                slotId,
                button,
                clickType == ClickType.QUICK_MOVE,
                clickType == ClickType.PICKUP_ALL,
                clickType == ClickType.QUICK_CRAFT,
                player
        )) {
            sendAllDataToRemote();
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }
}
