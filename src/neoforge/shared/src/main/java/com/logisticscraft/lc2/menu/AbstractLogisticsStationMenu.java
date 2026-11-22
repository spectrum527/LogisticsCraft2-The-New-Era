package com.logisticscraft.lc2.menu;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

/**
 * Shared container menu base for Smart Port and Cargo Station, providing exact player
 * inventory slot coordinates from {@code docs/GUI_LAYOUT.md} and periodic server-to-client
 * live status synchronization.
 */
public abstract class AbstractLogisticsStationMenu extends AbstractContainerMenu {

    protected final BlockPos stationPos;
    protected final Container container;
    protected final AbstractLogisticsStationBlockEntity serverStation;
    protected final Player player;

    private StationSyncPayload syncedState;
    private Runnable syncListener;
    private int syncTicker = 0;

    protected AbstractLogisticsStationMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            BlockPos stationPos,
            Container container,
            AbstractLogisticsStationBlockEntity serverStation
    ) {
        super(menuType, containerId);
        this.stationPos = stationPos;
        this.container = container;
        this.serverStation = serverStation;
        this.player = playerInventory.player;
    }

    /** Returns the block position of the station backing this menu. */
    public final BlockPos stationPos() {
        return stationPos;
    }

    /** Returns the most recent server-synced GUI state, or {@code null} before first packet. */
    public final StationSyncPayload syncedState() {
        return syncedState;
    }

    /** Registers a client screen callback invoked whenever fresh {@link StationSyncPayload} arrives. */
    public final void setSyncListener(Runnable listener) {
        this.syncListener = listener;
    }

    /** Applies a server-to-client synchronization payload on the client thread. */
    public final void applyClientSync(StationSyncPayload payload) {
        this.syncedState = payload;
        if (syncListener != null) {
            syncListener.run();
        }
    }

    /** Adds standard 27-slot player inventory + 9-slot hotbar at exact {@code docs/GUI_LAYOUT.md} coords. */
    protected final void addPlayerInventorySlots(Inventory playerInventory) {
        // Player inventory (3x9): x = 8 + col * 18, y = 142 + row * 18
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 142 + row * 18));
            }
        }
        // Player hotbar (1x9): x = 8 + col * 18, y = 199
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 199));
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (serverStation != null
                && player instanceof ServerPlayer serverPlayer
                && serverStation.getLevel() instanceof ServerLevel serverLevel) {
            if (++syncTicker >= 10) {
                syncTicker = 0;
                Lc2Native.sendToPlayer(serverPlayer, serverStation.buildSyncPayload(serverLevel));
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
