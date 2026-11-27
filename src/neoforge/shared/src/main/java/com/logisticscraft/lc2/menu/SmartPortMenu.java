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

    /** Server-side constructor for local interaction bound to the live {@link SmartPortBlockEntity}. */
    public SmartPortMenu(int containerId, Inventory playerInventory, SmartPortBlockEntity station) {
        this(containerId, playerInventory, station.getBlockPos(), station, station, false, null, "");
    }

    /** Server-side constructor for remote read-only access via an Operation Terminal. */
    public SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            SmartPortBlockEntity station,
            BlockPos remoteCommPos,
            String remoteTerminalId
    ) {
        this(
                containerId,
                playerInventory,
                station.getBlockPos(),
                station,
                station,
                true,
                remoteCommPos,
                remoteTerminalId
        );
    }

    /** Client-side factory constructor decoding station pos and remote flags from the open buffer. */
    public SmartPortMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, decodeOpenData(buf));
    }

    private record OpenData(BlockPos pos, boolean remoteMode, String remoteTerminalId) {
    }

    private static OpenData decodeOpenData(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean remote = buf.isReadable() && buf.readBoolean();
        String terminalId = buf.isReadable() ? buf.readUtf(64) : "";
        return new OpenData(pos, remote, terminalId);
    }

    private SmartPortMenu(int containerId, Inventory playerInventory, OpenData data) {
        this(
                containerId,
                playerInventory,
                data.pos(),
                new SimpleContainer(MACHINE_SLOTS),
                null,
                data.remoteMode(),
                null,
                data.remoteTerminalId()
        );
    }

    private SmartPortMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos pos,
            Container container,
            SmartPortBlockEntity serverStation,
            boolean remoteMode,
            BlockPos remoteCommPos,
            String remoteTerminalId
    ) {
        super(
                Lc2Registries.SMART_PORT_MENU.get(),
                containerId,
                playerInventory,
                pos,
                container,
                serverStation,
                remoteMode,
                remoteCommPos,
                remoteTerminalId
        );

        // 0..26: Outbound Area (3x9) at (8 + col*18, 87 + row*18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = SmartPortBlockEntity.OUTBOUND_START + row * 9 + col;
                addSlot(createMachineSlot(container, idx, 8 + col * 18, 87 + row * 18, null, -1));
            }
        }

        // 27..53: Storage Area (3x9) at (190 + col*18, 87 + row*18)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = SmartPortBlockEntity.STORAGE_START + row * 9 + col;
                addSlot(createMachineSlot(container, idx, 190 + col * 18, 87 + row * 18, null, -1));
            }
        }

        // 54: Single-Use Battery Slot (363, 76)
        addSlot(createMachineSlot(
                container,
                SmartPortBlockEntity.BATTERY_SLOT,
                363,
                76,
                Lc2Native::isSingleUseBattery,
                -1
        ));

        // 55..58: Machine Upgrade Slots (363, 102..156)
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            int slotIdx = SmartPortBlockEntity.UPGRADE_START + i;
            addSlot(createMachineSlot(
                    container,
                    slotIdx,
                    363,
                    102 + i * 18,
                    stack -> UpgradeSummary.isSupportedUpgrade(Lc2Native.itemId(stack)),
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isRemoteMode()) {
            return ItemStack.EMPTY;
        }
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
            if (Lc2Native.isSingleUseBattery(stack)) {
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
