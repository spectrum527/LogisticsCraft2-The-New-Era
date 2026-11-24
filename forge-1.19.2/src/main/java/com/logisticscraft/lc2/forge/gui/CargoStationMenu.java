package com.logisticscraft.lc2.forge.gui;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
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
 * Container menu for {@link CargoStationBlockEntity} matching {@code docs/GUI_LAYOUT.md} 2-zone slot indices:
 * <ul>
 *   <li>{@code 0..26}: Outbound Area (3x9 at {@code x=8..152, y=87..123})</li>
 *   <li>{@code 27..53}: Storage Area (3x9 at {@code x=190..334, y=87..123})</li>
 *   <li>{@code 54}: RF Chip Slot {@code (8, 57)}</li>
 *   <li>{@code 55..60}: Drone Bay Slots 0..5 {@code (74..164, 57)}</li>
 *   <li>{@code 61}: Single-Use Battery Slot {@code (363, 76)}</li>
 *   <li>{@code 62..65}: Machine Upgrade Slots {@code (363, 102..156)}</li>
 *   <li>{@code 66..92}: Player Inventory (3x9 at {@code x=99..243, y=153..189})</li>
 *   <li>{@code 93..101}: Player Hotbar (1x9 at {@code x=99..243, y=211})</li>
 * </ul>
 */
public class CargoStationMenu extends AbstractStationMenu {

    public static final int MACHINE_SLOTS = CargoStationBlockEntity.TOTAL_SLOTS; // 66

    /** Client constructor invoked by {@code IForgeMenuType}. */
    public CargoStationMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(
                containerId,
                playerInventory,
                readExtraData(extraData),
                createClientContainer(),
                null
        );
    }

    /** Server constructor for local proximity access. */
    public CargoStationMenu(int containerId, Inventory playerInventory, CargoStationBlockEntity blockEntity) {
        this(
                containerId,
                playerInventory,
                new OpenParams(blockEntity.getBlockPos(), false, BlockPos.ZERO, ""),
                blockEntity,
                blockEntity
        );
    }

    /** Server constructor for remote read-only Operation Terminal access. */
    public CargoStationMenu(
            int containerId,
            Inventory playerInventory,
            CargoStationBlockEntity blockEntity,
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

    private CargoStationMenu(
            int containerId,
            Inventory playerInventory,
            OpenParams params,
            Container container,
            @Nullable CargoStationBlockEntity serverBe
    ) {
        super(
                Lc2Registries.CARGO_STATION_MENU.get(),
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
                int index = CargoStationBlockEntity.SLOT_OUTBOUND_START + col + row * 9;
                addSlot(new StationSlot(container, index, 8 + col * 18, 87 + row * 18, -1));
            }
        }

        // 27..53: Storage Area (3x9 at x = 190 + col * 18, y = 87 + row * 18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = CargoStationBlockEntity.SLOT_STORAGE_START + col + row * 9;
                addSlot(new StationSlot(container, index, 190 + col * 18, 87 + row * 18, -1));
            }
        }

        // 54: RF Chip Slot (8, 57)
        addSlot(new StationSlot(container, CargoStationBlockEntity.SLOT_RF_CHIP, 8, 57, 1));

        // 55..60: 6 Drone Bay Slots (74 + bay * 18, 57)
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            addSlot(new StationSlot(
                    container,
                    CargoStationBlockEntity.SLOT_DRONE_BAY_START + bay,
                    74 + bay * 18,
                    57,
                    1
            ));
        }

        // 61: Single-Use Battery Slot (363, 76)
        addSlot(new StationSlot(
                container,
                CargoStationBlockEntity.SLOT_BATTERY,
                363,
                76,
                LogisticsConstants.SINGLE_USE_BATTERY_STACK_LIMIT
        ));

        // 62..65: 4 Machine Upgrade Slots (363, 102..156)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            addSlot(new StationSlot(
                    container,
                    CargoStationBlockEntity.SLOT_UPGRADE_START + i,
                    363,
                    102 + i * 18,
                    -1
            ));
        }

        // 66..101: Player Inventory & Hotbar
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
            // Shift-clicking normal items moves ONLY into Outbound (0..26), NEVER Storage (27..53)
            if (!moved && !moveItemStackTo(
                    stack,
                    CargoStationBlockEntity.SLOT_OUTBOUND_START,
                    CargoStationBlockEntity.SLOT_OUTBOUND_START + CargoStationBlockEntity.SLOT_OUTBOUND_COUNT,
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
