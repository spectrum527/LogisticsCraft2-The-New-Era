package com.logisticscraft.lc2.menu;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.item.TerminalLocator;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2StationMenuBase;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared container menu base for Smart Port and Cargo Station, providing exact player
 * inventory slot coordinates from {@code docs/GUI_LAYOUT.md}, periodic server-to-client
 * live status synchronization, and strict remote read-only protection when opened via
 * an Operation Terminal.
 */
public abstract class AbstractLogisticsStationMenu extends Lc2StationMenuBase {

    protected final BlockPos stationPos;
    protected final Container container;
    protected final AbstractLogisticsStationBlockEntity serverStation;
    protected final Player player;
    protected final boolean remoteMode;
    protected final BlockPos remoteCommPos;
    protected final String remoteTerminalId;

    private StationSyncPayload syncedState;
    private Runnable syncListener;
    private int syncTicker = 0;
    private boolean disconnectNotified = false;

    protected AbstractLogisticsStationMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            BlockPos stationPos,
            Container container,
            AbstractLogisticsStationBlockEntity serverStation,
            boolean remoteMode,
            BlockPos remoteCommPos,
            String remoteTerminalId
    ) {
        super(menuType, containerId);
        this.stationPos = stationPos;
        this.container = container;
        this.serverStation = serverStation;
        this.player = playerInventory.player;
        this.remoteMode = remoteMode;
        this.remoteCommPos = remoteCommPos == null ? null : remoteCommPos.immutable();
        this.remoteTerminalId = remoteTerminalId == null ? "" : remoteTerminalId;
    }

    /** Returns the number of machine-side slots ({@code 0..machineSlotCount-1}) protected in remote mode. */
    protected abstract int machineSlotCount();

    /** Returns the block position of the station backing this menu. */
    public final BlockPos stationPos() {
        return stationPos;
    }

    /** Returns {@code true} if this menu was opened remotely via an Operation Terminal. */
    public final boolean isRemoteMode() {
        return remoteMode;
    }

    /** Returns the Communication Base Station position mediating this remote session, or {@code null} locally. */
    public final BlockPos remoteCommPos() {
        return remoteCommPos;
    }

    /** Returns the Operation Terminal UUID mediating this remote session, or empty locally. */
    public final String remoteTerminalId() {
        return remoteTerminalId;
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

    /**
     * Creates a machine-side slot that automatically enforces strict read-only semantics
     * ({@code mayPlace=false}, {@code mayPickup=false}, {@code allowModification=false})
     * when {@link #remoteMode} is active.
     */
    protected final Slot createMachineSlot(
            Container slotContainer,
            int slotIndex,
            int x,
            int y,
            Predicate<ItemStack> placeFilter,
            int maxStackOverride
    ) {
        return new Slot(slotContainer, slotIndex, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                if (remoteMode) {
                    return false;
                }
                return placeFilter == null || placeFilter.test(stack);
            }

            @Override
            public boolean mayPickup(Player p) {
                return !remoteMode && super.mayPickup(p);
            }

            @Override
            public boolean allowModification(Player p) {
                return !remoteMode && super.allowModification(p);
            }

            @Override
            public int getMaxStackSize() {
                return maxStackOverride > 0 ? maxStackOverride : super.getMaxStackSize();
            }
        };
    }

    /** Adds standard 27-slot player inventory + 9-slot hotbar centered at exact {@code docs/GUI_LAYOUT.md} coords. */
    protected final void addPlayerInventorySlots(Inventory playerInventory) {
        // Player inventory (3x9): x = 99 + col * 18, y = 153 + row * 18
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 99 + col * 18, 153 + row * 18));
            }
        }
        // Player hotbar (1x9): x = 99 + col * 18, y = 211
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 99 + col * 18, 211));
        }
    }

    @Override
    public boolean canDragTo(Slot slot) {
        if (remoteMode && slot.index < machineSlotCount()) {
            return false;
        }
        return super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        if (remoteMode && slot.index < machineSlotCount()) {
            return false;
        }
        return super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    protected boolean shouldBlockContainerClick(
            int slotId,
            int button,
            boolean isQuickMove,
            boolean isPickupAll,
            boolean isQuickCraft,
            Player player
    ) {
        if (!remoteMode) {
            return false;
        }
        if (isQuickMove || isPickupAll) {
            return true;
        }
        return slotId >= 0 && slotId < machineSlotCount();
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
        if (!remoteMode) {
            return container.stillValid(player);
        }
        if (Lc2Native.isClientSide(player.level())) {
            return true;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        boolean sameDimension = serverStation != null && serverStation.getLevel() == level;

        boolean commChunkLoaded = false;
        boolean commPresent = false;
        boolean commPowered = false;
        if (sameDimension && remoteCommPos != null) {
            int commCx = remoteCommPos.getX() >> 4;
            int commCz = remoteCommPos.getZ() >> 4;
            commChunkLoaded = level.hasChunk(commCx, commCz)
                    && Lc2Native.isChunkEntityTicking(level, commCx, commCz);
            if (commChunkLoaded
                    && level.getBlockEntity(remoteCommPos) instanceof CommBaseStationBlockEntity comm
                    && !comm.isRemoved()) {
                commPresent = true;
                commPowered = comm.isOnline();
            }
        }

        boolean stationChunkLoaded = false;
        boolean stationPresent = false;
        if (sameDimension) {
            int stCx = stationPos.getX() >> 4;
            int stCz = stationPos.getZ() >> 4;
            stationChunkLoaded = level.hasChunk(stCx, stCz)
                    && Lc2Native.isChunkEntityTicking(level, stCx, stCz);
            if (stationChunkLoaded
                    && level.getBlockEntity(stationPos) == serverStation
                    && !serverStation.isRemoved()) {
                stationPresent = true;
            }
        }

        boolean adjacent = remoteCommPos != null && TerminalTargetResolver.areFaceAdjacent(
                LogisticsSavedData.toPos3i(remoteCommPos),
                LogisticsSavedData.toPos3i(stationPos)
        );
        boolean terminalHeld = remoteCommPos != null && TerminalLocator.hasTerminalBoundTo(
                player,
                remoteTerminalId,
                Lc2Native.dimensionId(level),
                remoteCommPos
        );

        RemoteDisconnectReason reason = TerminalTargetResolver.validateRemoteSession(
                sameDimension,
                commChunkLoaded,
                stationChunkLoaded,
                commPresent,
                commPowered,
                stationPresent,
                adjacent,
                terminalHeld
        );
        if (reason.isDisconnected()) {
            if (!disconnectNotified) {
                disconnectNotified = true;
                Lc2Native.sendActionBar(player, Component.translatable(reason.translationKey()));
            }
            return false;
        }
        return true;
    }
}
