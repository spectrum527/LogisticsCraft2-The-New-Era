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
        return info.displayName()
                + " " + info.distance() + "m "
                + info.freeBays() + "/" + LogisticsConstants.CARGO_STATION_DRONE_BAYS;
    }

    @Override
    protected void paintExtraPanel(ScreenCanvas canvas, StationSyncPayload state) {
        // RF Range Mini-LCD at (26, 78, 34, 18) -> render centered around (29, 83)
        int range = state.rfRange();
        String text = range > 0
                ? Component.translatable("gui.lc2.rf_range", range).getString()
                : "---";
        int color = range > 0 ? COLOR_LCD_CYAN : COLOR_LCD_AMBER;
        canvas.drawClippedString(text, 29, 83, 28, color);
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
}
