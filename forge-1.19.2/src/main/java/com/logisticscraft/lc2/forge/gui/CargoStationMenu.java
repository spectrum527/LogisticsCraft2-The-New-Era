package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
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
 * Container menu for {@link CargoStationBlockEntity} matching {@code docs/GUI_LAYOUT.md} slot indices:
 * <ul>
 *   <li>{@code 0..17}: Cargo Buffer (2x9 at {@code x=8..152, y=97..115})</li>
 *   <li>{@code 18}: RF Chip Slot {@code (8, 79)}</li>
 *   <li>{@code 19..24}: Drone Bay Slots 0..5 {@code (62..152, 79)}</li>
 *   <li>{@code 25}: Single-Use Battery Slot {@code (186, 79)}</li>
 *   <li>{@code 26..29}: Machine Upgrade Slots {@code (186, 106..160)}</li>
 *   <li>{@code 30..56}: Player Inventory (3x9)</li>
 *   <li>{@code 57..65}: Player Hotbar (1x9)</li>
 * </ul>
 */
public class CargoStationMenu extends AbstractStationMenu {

    public static final int MACHINE_SLOTS = CargoStationBlockEntity.TOTAL_SLOTS; // 30

    /** Client constructor invoked by {@code IForgeMenuType}. */
    public CargoStationMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(
                containerId,
                playerInventory,
                extraData.readBlockPos(),
                createClientContainer(),
                null
        );
    }

    /** Server constructor invoked by {@link CargoStationBlockEntity#createMenu}. */
    public CargoStationMenu(int containerId, Inventory playerInventory, CargoStationBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity.getBlockPos(), blockEntity, blockEntity);
    }

    private CargoStationMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos pos,
            Container container,
            CargoStationBlockEntity serverBe
    ) {
        super(Lc2Registries.CARGO_STATION_MENU.get(), containerId, playerInventory, pos, container, serverBe);

        // 0..17: Cargo Buffer (2x9)
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new StationSlot(container, index, 8 + col * 18, 97 + row * 18, -1));
            }
        }

        // 18: RF Chip Slot (8, 79)
        addSlot(new StationSlot(container, CargoStationBlockEntity.SLOT_RF_CHIP, 8, 79, 1));

        // 19..24: 6 Drone Bay Slots (62 + bay * 18, 79)
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            addSlot(new StationSlot(
                    container,
                    CargoStationBlockEntity.SLOT_DRONE_BAY_START + bay,
                    62 + bay * 18,
                    79,
                    1
            ));
        }

        // 25: Single-Use Battery Slot (186, 79)
        addSlot(new StationSlot(
                container,
                CargoStationBlockEntity.SLOT_BATTERY,
                186,
                79,
                LogisticsConstants.SINGLE_USE_BATTERY_STACK_LIMIT
        ));

        // 26..29: 4 Machine Upgrade Slots (186, 106..160)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            addSlot(new StationSlot(
                    container,
                    CargoStationBlockEntity.SLOT_UPGRADE_START + i,
                    186,
                    106 + i * 18,
                    -1
            ));
        }

        // 30..65: Player Inventory & Hotbar
        addPlayerInventorySlots(playerInventory);
    }

    private static Container createClientContainer() {
        return new SimpleContainer(MACHINE_SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                if (slot >= 0 && slot < CargoStationBlockEntity.SLOT_CARGO_COUNT) {
                    return true;
                }
                String id = Lc2Coords.itemId(stack);
                if (slot == CargoStationBlockEntity.SLOT_RF_CHIP) {
                    return RfChipTier.fromItemId(id).isInstalled();
                }
                if (slot >= CargoStationBlockEntity.SLOT_DRONE_BAY_START
                        && slot < CargoStationBlockEntity.SLOT_DRONE_BAY_START + CargoStationBlockEntity.DRONE_BAY_COUNT) {
                    return LogisticsConstants.TRANSPORT_DRONE_ID.equals(id)
                            || LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID.equals(id);
                }
                if (slot == CargoStationBlockEntity.SLOT_BATTERY) {
                    return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(id);
                }
                if (slot >= CargoStationBlockEntity.SLOT_UPGRADE_START && slot < MACHINE_SLOTS) {
                    return UpgradeSummary.isSupportedUpgrade(id);
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
            if (RfChipTier.fromItemId(id).isInstalled()) {
                moved = moveItemStackTo(stack, CargoStationBlockEntity.SLOT_RF_CHIP, CargoStationBlockEntity.SLOT_RF_CHIP + 1, false);
            } else if (LogisticsConstants.TRANSPORT_DRONE_ID.equals(id)
                    || LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID.equals(id)) {
                moved = moveItemStackTo(
                        stack,
                        CargoStationBlockEntity.SLOT_DRONE_BAY_START,
                        CargoStationBlockEntity.SLOT_DRONE_BAY_START + CargoStationBlockEntity.DRONE_BAY_COUNT,
                        false
                );
            } else if (LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(id)) {
                moved = moveItemStackTo(stack, CargoStationBlockEntity.SLOT_BATTERY, CargoStationBlockEntity.SLOT_BATTERY + 1, false);
            } else if (UpgradeSummary.isSupportedUpgrade(id)) {
                moved = moveItemStackTo(stack, CargoStationBlockEntity.SLOT_UPGRADE_START, MACHINE_SLOTS, false);
            }
            if (!moved && !moveItemStackTo(stack, 0, CargoStationBlockEntity.SLOT_CARGO_COUNT, false)) {
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
