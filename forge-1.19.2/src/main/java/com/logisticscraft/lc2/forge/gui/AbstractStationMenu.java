package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared container menu base for Smart Port and Cargo Station screens, handling periodic
 * server-to-client {@link S2CStationSyncPacket} updates and standard player inventory slots
 * positioned at {@code y = 142..199} per {@code docs/GUI_LAYOUT.md}.
 */
public abstract class AbstractStationMenu extends AbstractContainerMenu {

    protected final BlockPos stationPos;
    @Nullable
    protected final AbstractLogisticsStationBlockEntity serverBlockEntity;
    protected final Container container;
    private final Player viewer;
    private int syncTicker = 0;

    @Nullable
    private S2CStationSyncPacket latestSync;

    protected AbstractStationMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            BlockPos stationPos,
            Container container,
            @Nullable AbstractLogisticsStationBlockEntity serverBlockEntity
    ) {
        super(menuType, containerId);
        this.stationPos = stationPos;
        this.container = container;
        this.serverBlockEntity = serverBlockEntity;
        this.viewer = playerInventory.player;
    }

    /** Returns the world position of the station opened by this menu. */
    public BlockPos getStationPos() {
        return this.stationPos;
    }

    /** Returns the latest client-side sync payload received from the server, or {@code null}. */
    @Nullable
    public S2CStationSyncPacket getLatestSync() {
        return this.latestSync;
    }

    /** Stores an incoming {@link S2CStationSyncPacket} on the client. */
    public void applyClientSync(S2CStationSyncPacket packet) {
        if (this.stationPos.equals(packet.stationPos())) {
            this.latestSync = packet;
        }
    }

    protected void addPlayerInventorySlots(Inventory playerInventory) {
        // Player Inventory (3x9): x = 8 + col * 18, y = 142 + row * 18
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 142 + row * 18));
            }
        }
        // Player Hotbar (1x9): x = 8 + col * 18, y = 199
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 199));
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (this.serverBlockEntity != null && this.viewer instanceof ServerPlayer serverPlayer) {
            if (++this.syncTicker >= 10) {
                this.syncTicker = 0;
                this.serverBlockEntity.sendSyncTo(serverPlayer);
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.serverBlockEntity != null) {
            return this.serverBlockEntity.stillValid(player);
        }
        return player.distanceToSqr(
                this.stationPos.getX() + 0.5,
                this.stationPos.getY() + 0.5,
                this.stationPos.getZ() + 0.5
        ) <= 64.0;
    }

    /**
     * Slot wrapper honoring container validation, optional max stack override, and clearing
     * incoming-overflow flags when players manually modify cargo buffer slots.
     */
    protected class StationSlot extends Slot {
        private final int maxStackOverride;

        public StationSlot(Container container, int slot, int x, int y, int maxStackOverride) {
            super(container, slot, x, y);
            this.maxStackOverride = maxStackOverride;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getSlotIndex(), stack);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            if (maxStackOverride > 0) {
                return Math.min(maxStackOverride, stack.getMaxStackSize());
            }
            return super.getMaxStackSize(stack);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (serverBlockEntity != null) {
                serverBlockEntity.markSlotUserModified(getSlotIndex());
            }
        }
    }
}
