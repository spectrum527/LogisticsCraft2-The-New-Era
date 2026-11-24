package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client screen for {@link SmartPortMenu} bound to the 512x256 {@code lc2:textures/gui/smart_port.png}
 * atlas, rendering the Middle Deck Harbor Telemetry LCD at {@code (7, 56, 340, 18)}.
 */
public class SmartPortScreen extends AbstractStationScreen<SmartPortMenu> {

    private static final ResourceLocation TEXTURE = Lc2Coords.id("textures/gui/smart_port.png");

    public SmartPortScreen(SmartPortMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE);
    }

    @Override
    protected void renderExtraLabels(PoseStack poseStack, int mouseX, int mouseY) {
        // Middle Deck Harbor Telemetry LCD at (7, 56, 340, 18) -> text at (12, 61), maxWidth = 330
        boolean boatDocked = this.sync != null && this.sync.vehicleReady();
        boolean hasBerth = this.sync != null && this.sync.hasWaterBerth();
        boolean waterOk = hasBerth && (this.sync.targetWaterConnected() || !this.sync.hasTarget());

        String berthText = Component.translatable(boatDocked ? "gui.lc2.berth_docked" : "gui.lc2.berth_empty").getString();
        String waterText = Component.translatable(waterOk ? "gui.lc2.water_ok" : "gui.lc2.water_blocked").getString();
        String separator = "  |  ";
        int totalW = this.font.width(berthText) + this.font.width(separator) + this.font.width(waterText);

        int berthColor = boatDocked ? colorGreen() : colorAmber();
        int waterColor = waterOk ? colorCyan() : colorRed();

        if (totalW <= 330) {
            int x = 12;
            this.font.draw(poseStack, berthText, x, 61, berthColor);
            x += this.font.width(berthText);
            this.font.draw(poseStack, separator, x, 61, colorMuted());
            x += this.font.width(separator);
            this.font.draw(poseStack, waterText, x, 61, waterColor);
        } else {
            drawFittedLcdText(poseStack, berthText + " | " + waterText, 12, 61, 330, boatDocked && waterOk ? colorCyan() : colorAmber());
        }
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
        boolean waterOk = this.sync.hasWaterBerth() && this.sync.targetWaterConnected();
        String waterTag = waterOk ? "OK" : "?";
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
        return (this.sync.hasWaterBerth() && this.sync.targetWaterConnected()) ? colorCyan() : colorAmber();
    }

    @Override
    protected boolean isStatusBadgeHealthy(S2CStationSyncPacket sync) {
        return sync.vehicleReady() && sync.hasWaterBerth();
    }

    @Override
    @Nullable
    protected Component emptySlotTooltip(int slotIndex) {
        if (slotIndex >= SmartPortBlockEntity.SLOT_OUTBOUND_START
                && slotIndex < SmartPortBlockEntity.SLOT_OUTBOUND_START + SmartPortBlockEntity.SLOT_OUTBOUND_COUNT) {
            return Component.translatable("gui.lc2.slot.outbound");
        }
        if (slotIndex >= SmartPortBlockEntity.SLOT_STORAGE_START
                && slotIndex < SmartPortBlockEntity.SLOT_STORAGE_START + SmartPortBlockEntity.SLOT_STORAGE_COUNT) {
            return Component.translatable("gui.lc2.slot.storage");
        }
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
