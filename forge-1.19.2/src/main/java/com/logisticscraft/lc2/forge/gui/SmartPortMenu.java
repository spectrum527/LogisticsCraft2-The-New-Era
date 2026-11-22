package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for {@link SmartPortBlockEntity} matching {@code docs/GUI_LAYOUT.md} slot indices:
 * <ul>
 *   <li>{@code 0..26}: Cargo Buffer (3x9 at {@code x=8..152, y=79..115})</li>
 *   <li>{@code 27}: Single-Use Battery Slot {@code (186, 79)}</li>
 *   <li>{@code 28..31}: Machine Upgrade Slots {@code (186, 106..160)}</li>
 *   <li>{@code 32..58}: Player Inventory (3x9)</li>
 *   <li>{@code 59..67}: Player Hotbar (1x9)</li>
 * </ul>
 */
public class SmartPortMenu extends AbstractStationMenu {

    public static final int MACHINE_SLOTS = SmartPortBlockEntity.TOTAL_SLOTS; // 32

    /** Client constructor invoked by {@code IForgeMenuType}. */
    public SmartPortMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(
                containerId,
                playerInventory,
                extraData.readBlockPos(),
                createClientContainer(),
                null
        );
    }

    /** Server constructor invoked by {@link SmartPortBlockEntity#createMenu}. */
    public SmartPortMenu(int containerId, Inventory playerInventory, SmartPortBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity.getBlockPos(), blockEntity, blockEntity);
    }

    private SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos pos,
            Container container,
            SmartPortBlockEntity serverBe
    ) {
        super(Lc2Registries.SMART_PORT_MENU.get(), containerId, playerInventory, pos, container, serverBe);

        // 0..26: Cargo Buffer (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new StationSlot(container, index, 8 + col * 18, 79 + row * 18, -1));
            }
        }

        // 27: Single-Use Battery Slot
        addSlot(new StationSlot(
                container,
                SmartPortBlockEntity.SLOT_BATTERY,
                186,
                79,
                LogisticsConstants.SINGLE_USE_BATTERY_STACK_LIMIT
        ));

        // 28..31: Machine Upgrade Slots (4)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            addSlot(new StationSlot(
                    container,
                    SmartPortBlockEntity.SLOT_UPGRADE_START + i,
                    186,
                    106 + i * 18,
                    -1
            ));
        }

        // 32..67: Player Inventory & Hotbar
        addPlayerInventorySlots(playerInventory);
    }

    private static Container createClientContainer() {
        return new SimpleContainer(MACHINE_SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                if (slot >= 0 && slot < SmartPortBlockEntity.SLOT_CARGO_COUNT) {
                    return true;
                }
                if (slot == SmartPortBlockEntity.SLOT_BATTERY) {
                    return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Coords.itemId(stack));
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
            if (LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(id)) {
                moved = moveItemStackTo(stack, SmartPortBlockEntity.SLOT_BATTERY, SmartPortBlockEntity.SLOT_BATTERY + 1, false);
            } else if (UpgradeSummary.isSupportedUpgrade(id)) {
                moved = moveItemStackTo(stack, SmartPortBlockEntity.SLOT_UPGRADE_START, MACHINE_SLOTS, false);
            }
            if (!moved && !moveItemStackTo(stack, 0, SmartPortBlockEntity.SLOT_CARGO_COUNT, false)) {
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
