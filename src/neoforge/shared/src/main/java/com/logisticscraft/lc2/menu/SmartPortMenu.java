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
 * Container menu for {@code lc2:smart_port} matching {@code docs/GUI_LAYOUT.md} section 2:
 * <ul>
 *   <li>{@code 0..26}: 3x9 Cargo Buffer at {@code (8 + col*18, 79 + row*18)}</li>
 *   <li>{@code 27}: Single-Use Battery slot at {@code (186, 79)}</li>
 *   <li>{@code 28..31}: 4 Machine Upgrade slots at {@code (186, 106/124/142/160)}</li>
 *   <li>{@code 32..58}: Player Inventory (3x9)</li>
 *   <li>{@code 59..67}: Player Hotbar (1x9)</li>
 * </ul>
 */
public final class SmartPortMenu extends AbstractLogisticsStationMenu {

    public static final int MACHINE_SLOTS = SmartPortBlockEntity.TOTAL_SLOTS; // 32

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

        // 0..26: Cargo Buffer (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = row * 9 + col;
                addSlot(new Slot(container, idx, 8 + col * 18, 79 + row * 18));
            }
        }

        // 27: Single-Use Battery Slot (186, 79)
        addSlot(new Slot(container, SmartPortBlockEntity.BATTERY_SLOT, 186, 79) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Native.itemId(stack));
            }
        });

        // 28..31: Machine Upgrade Slots (186, 106..160)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            int slotIdx = SmartPortBlockEntity.UPGRADE_START + i;
            addSlot(new Slot(container, slotIdx, 186, 106 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return UpgradeSummary.isSupportedUpgrade(Lc2Native.itemId(stack));
                }
            });
        }

        // 32..67: Player Inventory & Hotbar
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

        if (index < MACHINE_SLOTS) {
            if (!moveItemStackTo(stack, MACHINE_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            String id = Lc2Native.itemId(stack);
            if (LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(id)) {
                if (!moveItemStackTo(stack, SmartPortBlockEntity.BATTERY_SLOT, SmartPortBlockEntity.BATTERY_SLOT + 1, false)
                        && !moveItemStackTo(stack, 0, SmartPortBlockEntity.CARGO_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (UpgradeSummary.isSupportedUpgrade(id)) {
                if (!moveItemStackTo(stack, SmartPortBlockEntity.UPGRADE_START, MACHINE_SLOTS, false)
                        && !moveItemStackTo(stack, 0, SmartPortBlockEntity.CARGO_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, SmartPortBlockEntity.CARGO_SLOTS, false)) {
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
