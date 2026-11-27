package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for {@link SmartPortBlockEntity} matching {@code docs/GUI_LAYOUT.md} 2-zone slot indices:
 * <ul>
 *   <li>{@code 0..26}: Outbound Area (3x9 at {@code x=8..152, y=87..123})</li>
 *   <li>{@code 27..53}: Storage Area (3x9 at {@code x=190..334, y=87..123})</li>
 *   <li>{@code 54}: Single-Use Battery Slot {@code (363, 76)}</li>
 *   <li>{@code 55..58}: Machine Upgrade Slots {@code (363, 102..156)}</li>
 *   <li>{@code 59..85}: Player Inventory (3x9 at {@code x=99..243, y=153..189})</li>
 *   <li>{@code 86..94}: Player Hotbar (1x9 at {@code x=99..243, y=211})</li>
 * </ul>
 */
public class SmartPortMenu extends AbstractStationMenu {

    public static final int MACHINE_SLOTS = SmartPortBlockEntity.TOTAL_SLOTS; // 59

    /** Client constructor invoked by {@code IForgeMenuType}. */
    public SmartPortMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(
                containerId,
                playerInventory,
                readExtraData(extraData),
                createClientContainer(),
                null
        );
    }

    /** Server constructor for local proximity access. */
    public SmartPortMenu(int containerId, Inventory playerInventory, SmartPortBlockEntity blockEntity) {
        this(
                containerId,
                playerInventory,
                new OpenParams(blockEntity.getBlockPos(), false, BlockPos.ZERO, ""),
                blockEntity,
                blockEntity
        );
    }

    /** Server constructor for remote read-only Operation Terminal access. */
    public SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            SmartPortBlockEntity blockEntity,
            BlockPos remoteCommPos,
            String remoteTerminalId
    ) {
        this(
                containerId,
                playerInventory,
                new OpenParams(blockEntity.getBlockPos(), true, remoteCommPos, remoteTerminalId),
                blockEntity,
                blockEntity
        );
    }

    private record OpenParams(BlockPos pos, boolean remoteMode, BlockPos remoteCommPos, String remoteTerminalId) {
    }

    private static OpenParams readExtraData(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean remote = buf.isReadable() && buf.readBoolean();
        BlockPos commPos = (remote && buf.isReadable()) ? buf.readBlockPos() : BlockPos.ZERO;
        String terminalId = (remote && buf.isReadable()) ? buf.readUtf(64) : "";
        return new OpenParams(pos, remote, commPos, terminalId);
    }

    private SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            OpenParams params,
            Container container,
            @Nullable SmartPortBlockEntity serverBe
    ) {
        super(
                Lc2Registries.SMART_PORT_MENU.get(),
                containerId,
                playerInventory,
                params.pos(),
                container,
                serverBe,
                params.remoteMode(),
                params.remoteCommPos(),
                params.remoteTerminalId()
        );

        // 0..26: Outbound Area (3x9 at x = 8 + col * 18, y = 87 + row * 18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = SmartPortBlockEntity.SLOT_OUTBOUND_START + col + row * 9;
                addSlot(new StationSlot(container, index, 8 + col * 18, 87 + row * 18, -1));
            }
        }

        // 27..53: Storage Area (3x9 at x = 190 + col * 18, y = 87 + row * 18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = SmartPortBlockEntity.SLOT_STORAGE_START + col + row * 9;
                addSlot(new StationSlot(container, index, 190 + col * 18, 87 + row * 18, -1));
            }
        }

        // 54: Single-Use Battery Slot (363, 76)
        addSlot(new StationSlot(
                container,
                SmartPortBlockEntity.SLOT_BATTERY,
                363,
                76,
                LogisticsConstants.SINGLE_USE_BATTERY_STACK_LIMIT
        ));

        // 55..58: Machine Upgrade Slots (4) at (363, 102..156)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            addSlot(new StationSlot(
                    container,
                    SmartPortBlockEntity.SLOT_UPGRADE_START + i,
                    363,
                    102 + i * 18,
                    -1
            ));
        }

        // 59..94: Player Inventory & Hotbar
        addPlayerInventorySlots(playerInventory);
    }

    @Override
    protected int machineSlotCount() {
        return MACHINE_SLOTS;
    }

    private static Container createClientContainer() {
        return new SimpleContainer(MACHINE_SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                if (slot >= 0 && slot < SmartPortBlockEntity.SLOT_CARGO_COUNT) {
                    return true;
                }
                if (slot == SmartPortBlockEntity.SLOT_BATTERY) {
                    return Lc2Coords.isSingleUseBattery(stack);
                }
                if (slot >= SmartPortBlockEntity.SLOT_UPGRADE_START && slot < MACHINE_SLOTS) {
                    return UpgradeSummary.isSupportedUpgrade(Lc2Coords.itemId(stack));
                }
                return false;
            }
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (this.remoteMode) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < MACHINE_SLOTS) {
            if (!moveItemStackTo(stack, MACHINE_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            String id = Lc2Coords.itemId(stack);
            boolean moved = false;
            if (Lc2Coords.isSingleUseBattery(stack)) {
                moved = moveItemStackTo(stack, SmartPortBlockEntity.SLOT_BATTERY, SmartPortBlockEntity.SLOT_BATTERY + 1, false);
            } else if (UpgradeSummary.isSupportedUpgrade(id)) {
                moved = moveItemStackTo(stack, SmartPortBlockEntity.SLOT_UPGRADE_START, MACHINE_SLOTS, false);
            }
            // Shift-clicking normal items moves ONLY into Outbound (0..26), NEVER Storage (27..53)
            if (!moved && !moveItemStackTo(
                    stack,
                    SmartPortBlockEntity.SLOT_OUTBOUND_START,
                    SmartPortBlockEntity.SLOT_OUTBOUND_START + SmartPortBlockEntity.SLOT_OUTBOUND_COUNT,
                    false
            )) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
