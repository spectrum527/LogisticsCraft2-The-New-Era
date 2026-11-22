package com.logisticscraft.lc2.menu;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for {@code lc2:smart_port} matching {@code docs/GUI_LAYOUT.md} section 3:
 * <ul>
 *   <li>{@code 0..26}: 3x9 Outbound Area at {@code (8 + col*18, 87 + row*18)}</li>
 *   <li>{@code 27..53}: 3x9 Storage Area at {@code (190 + col*18, 87 + row*18)}</li>
 *   <li>{@code 54}: Single-Use Battery slot at {@code (363, 76)}</li>
 *   <li>{@code 55..58}: 4 Machine Upgrade slots at {@code (363, 102/120/138/156)}</li>
 *   <li>{@code 59..85}: Player Inventory (3x9 at {@code x = 99 + col*18, y = 153 + row*18})</li>
 *   <li>{@code 86..94}: Player Hotbar (1x9 at {@code x = 99 + col*18, y = 211})</li>
 * </ul>
 */
public final class SmartPortMenu extends AbstractLogisticsStationMenu {

    public static final int MACHINE_SLOTS = SmartPortBlockEntity.TOTAL_SLOTS; // 59

    /** Server-side constructor bound to the live {@link SmartPortBlockEntity}. */
    public SmartPortMenu(int containerId, Inventory playerInventory, SmartPortBlockEntity station) {
        this(containerId, playerInventory, station.getBlockPos(), station, station);
    }

    /** Client-side factory constructor decoding the station BlockPos from the open buffer. */
    public SmartPortMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, buf.readBlockPos(), new SimpleContainer(MACHINE_SLOTS), null);
    }

    private SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos pos,
            Container container,
            SmartPortBlockEntity serverStation
    ) {
        super(Lc2Registries.SMART_PORT_MENU.get(), containerId, playerInventory, pos, container, serverStation);

        // 0..26: Outbound Area (3x9) at (8 + col*18, 87 + row*18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = SmartPortBlockEntity.OUTBOUND_START + row * 9 + col;
                addSlot(new Slot(container, idx, 8 + col * 18, 87 + row * 18));
            }
        }

        // 27..53: Storage Area (3x9) at (190 + col*18, 87 + row*18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = SmartPortBlockEntity.STORAGE_START + row * 9 + col;
                addSlot(new Slot(container, idx, 190 + col * 18, 87 + row * 18));
            }
        }

        // 54: Single-Use Battery Slot (363, 76)
        addSlot(new Slot(container, SmartPortBlockEntity.BATTERY_SLOT, 363, 76) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Native.itemId(stack));
            }
        });

        // 55..58: Machine Upgrade Slots (363, 102..156)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            int slotIdx = SmartPortBlockEntity.UPGRADE_START + i;
            addSlot(new Slot(container, slotIdx, 363, 102 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return UpgradeSummary.isSupportedUpgrade(Lc2Native.itemId(stack));
                }
            });
        }

        // 59..94: Player Inventory & Hotbar
        addPlayerInventorySlots(playerInventory);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int outboundEnd = SmartPortBlockEntity.OUTBOUND_START + SmartPortBlockEntity.OUTBOUND_SLOTS;
        if (index < MACHINE_SLOTS) {
            if (!moveItemStackTo(stack, MACHINE_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            String id = Lc2Native.itemId(stack);
            if (LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(id)) {
                if (!moveItemStackTo(stack, SmartPortBlockEntity.BATTERY_SLOT, SmartPortBlockEntity.BATTERY_SLOT + 1, false)
                        && !moveItemStackTo(stack, SmartPortBlockEntity.OUTBOUND_START, outboundEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (UpgradeSummary.isSupportedUpgrade(id)) {
                if (!moveItemStackTo(stack, SmartPortBlockEntity.UPGRADE_START, MACHINE_SLOTS, false)
                        && !moveItemStackTo(stack, SmartPortBlockEntity.OUTBOUND_START, outboundEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, SmartPortBlockEntity.OUTBOUND_START, outboundEnd, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }
}
