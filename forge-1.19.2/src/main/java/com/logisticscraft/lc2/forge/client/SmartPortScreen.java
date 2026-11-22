package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client screen for {@link SmartPortMenu} bound to {@code lc2:textures/gui/smart_port.png}.
 */
public class SmartPortScreen extends AbstractStationScreen<SmartPortMenu> {

    private static final ResourceLocation TEXTURE = Lc2Coords.id("textures/gui/smart_port.png");

    public SmartPortScreen(SmartPortMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE);
    }

    @Override
    protected String formatTargetReadout() {
        if (this.sync == null || !this.sync.hasTarget()) {
            return Component.translatable("gui.lc2.no_target").getString();
        }
        S2CStationSyncPacket.TargetSummary summary = findSelectedTargetSummary();
        if (summary == null) {
            return "(" + this.sync.targetPos().getX() + "," + this.sync.targetPos().getY() + "," + this.sync.targetPos().getZ() + ")";
        }
        String waterTag = summary.hasWaterBerth() ? "OK" : "!";
        return summary.displayName() + " [" + summary.manhattanDistance() + "m " + waterTag + "]";
    }

    @Override
    protected int targetReadoutColor() {
        if (this.sync == null || !this.sync.hasTarget()) {
            return colorMuted();
        }
        S2CStationSyncPacket.TargetSummary summary = findSelectedTargetSummary();
        if (summary == null) {
            return colorAmber();
        }
        return (this.sync.hasWaterBerth() && summary.hasWaterBerth()) ? colorCyan() : colorAmber();
    }

    @Override
    protected boolean isStatusBadgeHealthy(S2CStationSyncPacket sync) {
        return sync.vehicleReady() && sync.hasWaterBerth();
    }

    @Override
    @Nullable
    protected Component emptySlotTooltip(int slotIndex) {
        if (slotIndex == SmartPortBlockEntity.SLOT_BATTERY) {
            return Component.translatable("gui.lc2.slot.battery");
        }
        if (slotIndex >= SmartPortBlockEntity.SLOT_UPGRADE_START
                && slotIndex < SmartPortBlockEntity.TOTAL_SLOTS) {
            return Component.translatable("gui.lc2.slot.upgrade");
        }
        return null;
    }
}
