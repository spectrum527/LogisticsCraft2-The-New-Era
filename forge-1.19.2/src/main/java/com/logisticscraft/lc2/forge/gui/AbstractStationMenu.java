package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared container menu base for Smart Port and Cargo Station screens, handling periodic
 * server-to-client {@link S2CStationSyncPacket} updates, centered player inventory slots
 * positioned at {@code x = 99..243, y = 153..211} per {@code docs/GUI_LAYOUT.md}, and strict
 * server+client read-only enforcement when opened remotely via an Operation Terminal.
 */
public abstract class AbstractStationMenu extends AbstractContainerMenu {

    protected final BlockPos stationPos;
    @Nullable
    protected final AbstractLogisticsStationBlockEntity serverBlockEntity;
    protected final Container container;
    private final Player viewer;

    protected final boolean remoteMode;
    protected final BlockPos remoteCommPos;
    protected final String remoteTerminalId;
    private final String remoteDimensionId;
    private boolean disconnectNotified = false;
    private int syncTicker = 0;

    @Nullable
    private S2CStationSyncPacket latestSync;

    protected AbstractStationMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            BlockPos stationPos,
            Container container,
            @Nullable AbstractLogisticsStationBlockEntity serverBlockEntity,
            boolean remoteMode,
            @Nullable BlockPos remoteCommPos,
            @Nullable String remoteTerminalId
    ) {
        super(menuType, containerId);
        this.stationPos = stationPos;
        this.container = container;
        this.serverBlockEntity = serverBlockEntity;
        this.viewer = playerInventory.player;
        this.remoteMode = remoteMode;
        this.remoteCommPos = remoteCommPos == null ? BlockPos.ZERO : remoteCommPos.immutable();
        this.remoteTerminalId = remoteTerminalId == null ? "" : remoteTerminalId;
        this.remoteDimensionId = (serverBlockEntity != null && serverBlockEntity.getLevel() != null)
                ? serverBlockEntity.getLevel().dimension().location().toString()
                : playerInventory.player.getLevel().dimension().location().toString();
    }

    /** Returns the number of machine slots at the front of {@link #slots} ({@code 0..machineSlotCount-1}). */
    protected abstract int machineSlotCount();

    /** Returns the world position of the station opened by this menu. */
    public BlockPos getStationPos() {
        return this.stationPos;
    }

    /** Returns {@code true} if this station menu was opened remotely via an Operation Terminal. */
    public boolean isRemoteMode() {
        return this.remoteMode;
    }

    /** Returns the Communication Base Station position serving this remote session, or {@link BlockPos#ZERO}. */
    public BlockPos getRemoteCommPos() {
        return this.remoteCommPos;
    }

    /** Returns the persistent UUID of the Operation Terminal that opened this remote session. */
    public String getRemoteTerminalId() {
        return this.remoteTerminalId;
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
        // Player Inventory (3x9): x = 99 + col * 18, y = 153 + row * 18
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 99 + col * 18, 153 + row * 18));
            }
        }
        // Player Hotbar (1x9): x = 99 + col * 18, y = 211
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 99 + col * 18, 211));
        }
    }

    @Override
    public boolean canDragTo(Slot slot) {
        if (this.remoteMode && slot.index < machineSlotCount()) {
            return false;
        }
        return super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        if (this.remoteMode && slot.index < machineSlotCount()) {
            return false;
        }
        return super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (this.remoteMode) {
            if (clickType == ClickType.QUICK_MOVE
                    || clickType == ClickType.PICKUP_ALL
                    || (slotId >= 0 && slotId < machineSlotCount())) {
                if (!player.getLevel().isClientSide) {
                    sendAllDataToRemote();
                }
                return;
            }
        }
        super.clicked(slotId, button, clickType, player);
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
        if (this.remoteMode) {
            if (player.getLevel().isClientSide) {
                return true;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return false;
            }
            RemoteDisconnectReason reason = TerminalSessionService.validateRemoteSession(
                    serverPlayer,
                    this.remoteDimensionId,
                    this.remoteCommPos,
                    this.stationPos,
                    this.remoteTerminalId,
                    true
            );
            if (reason.isDisconnected()) {
                if (!this.disconnectNotified) {
                    this.disconnectNotified = true;
                    serverPlayer.displayClientMessage(Component.translatable(reason.translationKey()), true);
                }
                return false;
            }
            return true;
        }
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
     * Slot wrapper honoring container validation, optional max stack override, and strict
     * read-only lock when opened remotely via an Operation Terminal.
     */
    protected class StationSlot extends Slot {
        private final int maxStackOverride;

        public StationSlot(Container container, int slot, int x, int y, int maxStackOverride) {
            super(container, slot, x, y);
            this.maxStackOverride = maxStackOverride;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (remoteMode) {
                return false;
            }
            return container.canPlaceItem(getSlotIndex(), stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            if (remoteMode) {
                return false;
            }
            return super.mayPickup(player);
        }

        @Override
        public boolean allowModification(Player player) {
            if (remoteMode) {
                return false;
            }
            return super.allowModification(player);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            if (maxStackOverride > 0) {
                return Math.min(maxStackOverride, stack.getMaxStackSize());
            }
            return super.getMaxStackSize(stack);
        }
    }
}
