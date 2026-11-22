package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.gui.CargoStationMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client screen for {@link CargoStationMenu} bound to the 512x256 {@code lc2:textures/gui/cargo_station.png}
 * atlas, rendering the RF Range mini-LCD at {@code (26, 56, 42, 18)} and Flight Deck Status LCD at
 * {@code (186, 56, 161, 18)}.
 */
public class CargoStationScreen extends AbstractStationScreen<CargoStationMenu> {

    private static final ResourceLocation TEXTURE = Lc2Coords.id("textures/gui/cargo_station.png");

    public CargoStationScreen(CargoStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE);
    }

    @Override
    protected void renderExtraLabels(PoseStack poseStack, int mouseX, int mouseY) {
        // RF Range Mini-LCD box at (26, 56, 42, 18) -> centered around (29, 61)
        RfChipTier chip = this.sync != null ? RfChipTier.fromTierIndex(this.sync.rfChipTierIndex()) : RfChipTier.NONE;
        String rangeText = chip.isInstalled()
                ? Component.translatable("gui.lc2.rf_range", chip.maxRange()).getString()
                : Component.translatable("gui.lc2.rf_none").getString();
        int rangeColor = chip.isInstalled() ? colorCyan() : colorRed();
        int rawRangeW = this.font.width(rangeText);
        if (rawRangeW <= 38) {
            int rx = 26 + (42 - rawRangeW) / 2;
            this.font.draw(poseStack, rangeText, rx, 61, rangeColor);
        } else {
            drawFittedLcdText(poseStack, rangeText, 28, 61, 38, rangeColor);
        }

        // Flight Deck Status LCD at (186, 56, 161, 18) -> text at (190, 61), maxWidth = 153
        int readyBays = countReadyDroneBays();
        String baysText = Component.translatable("gui.lc2.flight_deck_ready", readyBays).getString();
        int baysColor = readyBays > 0 ? colorGreen() : colorAmber();

        String rfStatusText = null;
        int rfStatusColor = colorMuted();
        if (!chip.isInstalled()) {
            rfStatusText = Component.translatable("gui.lc2.rf_none").getString();
            rfStatusColor = colorRed();
        } else if (this.sync != null && this.sync.hasTarget()) {
            S2CStationSyncPacket.TargetSummary summary = findSelectedTargetSummary();
            int dist = summary != null
                    ? summary.manhattanDistance()
                    : this.menu.getStationPos().distManhattan(this.sync.targetPos());
            boolean inRange = chip.canReach(dist);
            rfStatusText = Component.translatable(inRange ? "gui.lc2.in_range" : "gui.lc2.out_of_range").getString();
            rfStatusColor = inRange ? colorCyan() : colorRed();
        }

        if (rfStatusText == null) {
            drawFittedLcdText(poseStack, baysText, 190, 61, 153, baysColor);
        } else {
            String sep = " | ";
            int combinedW = this.font.width(baysText) + this.font.width(sep) + this.font.width(rfStatusText);
            if (combinedW <= 153) {
                int x = 190;
                this.font.draw(poseStack, baysText, x, 61, baysColor);
                x += this.font.width(baysText);
                this.font.draw(poseStack, sep, x, 61, colorMuted());
                x += this.font.width(sep);
                this.font.draw(poseStack, rfStatusText, x, 61, rfStatusColor);
            } else {
                int mergedColor = rfStatusColor == colorRed() ? colorRed() : (readyBays > 0 ? colorCyan() : colorAmber());
                drawFittedLcdText(poseStack, baysText + sep + rfStatusText, 190, 61, 153, mergedColor);
            }
        }
    }

    private int countReadyDroneBays() {
        int count = 0;
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            int idx = CargoStationBlockEntity.SLOT_DRONE_BAY_START + bay;
            if (idx < this.menu.slots.size() && this.menu.getSlot(idx).hasItem()) {
                count++;
            }
        }
        return count;
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
        return summary.displayName()
                + " " + summary.manhattanDistance() + "m"
                + " [" + summary.freeBays() + "/" + summary.maxBays() + "]";
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
        RfChipTier chip = RfChipTier.fromTierIndex(this.sync.rfChipTierIndex());
        if (!chip.isInstalled() || !chip.canReach(summary.manhattanDistance())) {
            return colorRed();
        }
        if (summary.freeBays() <= 0) {
            return colorAmber();
        }
        return colorCyan();
    }

    @Override
    protected boolean isStatusBadgeHealthy(S2CStationSyncPacket sync) {
        return sync.vehicleReady() && RfChipTier.fromTierIndex(sync.rfChipTierIndex()).isInstalled();
    }

    @Override
    @Nullable
    protected Component emptySlotTooltip(int slotIndex) {
        if (slotIndex >= CargoStationBlockEntity.SLOT_OUTBOUND_START
                && slotIndex < CargoStationBlockEntity.SLOT_OUTBOUND_START + CargoStationBlockEntity.SLOT_OUTBOUND_COUNT) {
            return Component.translatable("gui.lc2.slot.outbound");
        }
        if (slotIndex >= CargoStationBlockEntity.SLOT_STORAGE_START
                && slotIndex < CargoStationBlockEntity.SLOT_STORAGE_START + CargoStationBlockEntity.SLOT_STORAGE_COUNT) {
            return Component.translatable("gui.lc2.slot.storage");
        }
        if (slotIndex == CargoStationBlockEntity.SLOT_RF_CHIP) {
            return Component.translatable("gui.lc2.slot.rf_chip");
        }
        if (slotIndex >= CargoStationBlockEntity.SLOT_DRONE_BAY_START
                && slotIndex < CargoStationBlockEntity.SLOT_DRONE_BAY_START + CargoStationBlockEntity.DRONE_BAY_COUNT) {
            return Component.translatable("gui.lc2.slot.drone_bay");
        }
        if (slotIndex == CargoStationBlockEntity.SLOT_BATTERY) {
            return Component.translatable("gui.lc2.slot.battery");
        }
        if (slotIndex >= CargoStationBlockEntity.SLOT_UPGRADE_START
                && slotIndex < CargoStationBlockEntity.TOTAL_SLOTS) {
            return Component.translatable("gui.lc2.slot.upgrade");
        }
        return null;
    }
}
