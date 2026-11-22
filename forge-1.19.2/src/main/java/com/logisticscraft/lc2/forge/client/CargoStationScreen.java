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
 * Client screen for {@link CargoStationMenu} bound to {@code lc2:textures/gui/cargo_station.png},
 * rendering the RF Range mini-LCD at {@code (26, 78, 34, 18)} and drone bay / RF range telemetry.
 */
public class CargoStationScreen extends AbstractStationScreen<CargoStationMenu> {

    private static final ResourceLocation TEXTURE = Lc2Coords.id("textures/gui/cargo_station.png");

    public CargoStationScreen(CargoStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE);
    }

    @Override
    protected void renderExtraLabels(PoseStack poseStack, int mouseX, int mouseY) {
        // RF Range Mini-LCD box at (26, 78, 34, 18) -> centered around y=83
        RfChipTier chip = this.sync != null ? RfChipTier.fromTierIndex(this.sync.rfChipTierIndex()) : RfChipTier.NONE;
        String rangeText = chip.isInstalled()
                ? Component.translatable("gui.lc2.rf_range", chip.maxRange()).getString()
                : Component.translatable("gui.lc2.rf_none").getString();
        int rangeColor = chip.isInstalled() ? colorCyan() : colorRed();
        int textW = this.font.width(rangeText);
        int rx = 26 + Math.max(0, (34 - textW) / 2);
        this.font.draw(poseStack, rangeText, rx, 83, rangeColor);
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
