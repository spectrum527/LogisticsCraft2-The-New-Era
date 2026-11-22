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
        String tag = info.reachableFlag() ? "OK" : "?";
        return info.displayName() + " " + info.distance() + "m [" + tag + "]";
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
}
