package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.network.StationSyncPayload;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Cargo Station interactive GUI screen bound to {@code assets/lc2/textures/gui/cargo_station.png}.
 */
public final class CargoStationScreen extends AbstractLogisticsScreen<CargoStationMenu> {

    public CargoStationScreen(CargoStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected String guiTextureName() {
        return "cargo_station";
    }

    @Override
    protected String formatTargetReadout(StationSyncPayload state) {
        if (!state.hasTarget()) {
            return Component.translatable("gui.lc2.no_target").getString();
        }
        StationSyncPayload.TargetInfo info = state.selectedTargetInfo();
        if (info == null) {
            return "(" + state.targetPos().getX() + "," + state.targetPos().getY() + "," + state.targetPos().getZ() + ")";
        }
        String suffix = " " + info.distance() + "m "
                + info.freeBays() + "/" + LogisticsConstants.CARGO_STATION_DRONE_BAYS;
        return fitTargetReadout(info.displayName(), suffix, TARGET_LCD_TEXT_WIDTH);
    }

    @Override
    protected void paintExtraPanel(ScreenCanvas canvas, StationSyncPayload state) {
        // 1. RF Range Mini-LCD at (26, 56, 42, 18) -> centered inside inner (27..66) at y = 61
        int range = state.rfRange();
        String rfText = range > 0
                ? Component.translatable("gui.lc2.rf_range", range).getString()
                : Component.translatable("gui.lc2.rf_none").getString();
        int rfColor = range > 0 ? COLOR_LCD_CYAN : COLOR_LCD_AMBER;
        int rfWidth = Math.min(38, font.width(rfText));
        int rfX = 27 + Math.max(0, (40 - rfWidth) / 2);
        canvas.drawClippedString(rfText, rfX, 61, 38, rfColor);

        // 2. Flight Deck Status LCD at (186, 56, 161, 18) -> render at (189, 61), maxWidth = 156
        String baysText = Component.translatable("gui.lc2.flight_deck_ready", state.readyVehicleCount()).getString();
        int baysColor = state.readyVehicleCount() > 0 ? COLOR_LCD_CYAN : COLOR_LCD_AMBER;

        StationSyncPayload.TargetInfo info = state.selectedTargetInfo();
        if (info == null) {
            canvas.drawClippedString(baysText, 189, 61, 156, baysColor);
            return;
        }

        String rangeText = Component.translatable(
                info.reachableFlag() ? "gui.lc2.in_range" : "gui.lc2.out_of_range"
        ).getString();
        int baysW = font.width(baysText);
        int rangeW = font.width(rangeText);
        String sep = (baysW + font.width(" | ") + rangeW <= 156) ? " | " : " ";
        int sepW = font.width(sep);

        canvas.drawClippedString(baysText, 189, 61, Math.min(92, baysW + 1), baysColor);
        int afterBays = 189 + Math.min(92, baysW);
        canvas.drawClippedString(sep, afterBays, 61, sepW + 1, COLOR_LCD_MUTED);
        int remain = Math.max(20, 156 - (afterBays + sepW - 189));
        canvas.drawClippedString(
                rangeText,
                afterBays + sepW,
                61,
                remain,
                info.reachableFlag() ? COLOR_LCD_GREEN : COLOR_LCD_RED
        );
    }

    @Override
    protected List<Component> buildTargetTooltip(StationSyncPayload.TargetInfo info) {
        return List.of(
                Component.literal(info.displayName()
                        + " (" + info.pos().getX() + ", " + info.pos().getY() + ", " + info.pos().getZ() + ")"),
                Component.translatable("gui.lc2.distance", info.distance()),
                Component.translatable("gui.lc2.bays", info.freeBays(), LogisticsConstants.CARGO_STATION_DRONE_BAYS),
                Component.translatable(info.reachableFlag() ? "gui.lc2.in_range" : "gui.lc2.out_of_range")
        );
    }

    @Override
    protected List<Component> buildVehicleBadgeTooltip(StationSyncPayload state) {
        return List.of(Component.translatable("gui.lc2.flight_deck_ready", state.readyVehicleCount()));
    }
}
