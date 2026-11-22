package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.network.StationSyncPayload;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Smart Port interactive GUI screen bound to {@code assets/lc2/textures/gui/smart_port.png}.
 */
public final class SmartPortScreen extends AbstractLogisticsScreen<SmartPortMenu> {

    public SmartPortScreen(SmartPortMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected String guiTextureName() {
        return "smart_port";
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
        String suffix = " " + info.distance() + "m [" + (info.reachableFlag() ? "OK" : "?") + "]";
        return fitTargetReadout(info.displayName(), suffix, TARGET_LCD_TEXT_WIDTH);
    }

    @Override
    protected void paintExtraPanel(ScreenCanvas canvas, StationSyncPayload state) {
        // Middle Deck Harbor Telemetry LCD at (7, 56, 340, 18) -> render at (12, 61), maxWidth = 330
        String berthText = Component.translatable(
                state.vehicleReady() ? "gui.lc2.berth_docked" : "gui.lc2.berth_empty"
        ).getString();
        int berthColor = state.vehicleReady() ? COLOR_LCD_CYAN : COLOR_LCD_AMBER;

        StationSyncPayload.TargetInfo info = state.selectedTargetInfo();
        if (info == null) {
            canvas.drawClippedString(berthText, 12, 61, 330, berthColor);
            return;
        }

        String sep = "  |  ";
        String waterText = Component.translatable(
                info.reachableFlag() ? "gui.lc2.water_ok" : "gui.lc2.water_blocked"
        ).getString();
        int berthW = Math.min(180, font.width(berthText));
        int sepW = font.width(sep);
        canvas.drawClippedString(berthText, 12, 61, 180, berthColor);
        canvas.drawClippedString(sep, 12 + berthW, 61, sepW + 2, COLOR_LCD_MUTED);
        int remain = Math.max(24, 330 - berthW - sepW);
        canvas.drawClippedString(
                waterText,
                12 + berthW + sepW,
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
                Component.translatable(info.reachableFlag() ? "gui.lc2.water_ok" : "gui.lc2.water_blocked")
        );
    }

    @Override
    protected List<Component> buildVehicleBadgeTooltip(StationSyncPayload state) {
        return List.of(Component.translatable(
                state.vehicleReady() ? "gui.lc2.berth_docked" : "gui.lc2.berth_empty"
        ));
    }
}
