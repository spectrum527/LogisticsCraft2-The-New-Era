package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.network.S2CTerminalSyncPacket;
import net.minecraft.client.Minecraft;

/**
 * Client-only packet dispatch helpers invoked via {@code DistExecutor} so server bytecode never
 * touches client classes.
 */
public final class Lc2ClientHandlers {

    private Lc2ClientHandlers() {
    }

    /** Applies an incoming {@link S2CStationSyncPacket} to the currently open station menu/screen. */
    public static void handleStationSync(S2CStationSyncPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof AbstractStationMenu menu) {
            menu.applyClientSync(packet);
        }
        if (mc.screen instanceof AbstractStationScreen<?> screen) {
            screen.onStationSync(packet);
        }
    }

    /** Opens or refreshes {@link OperationTerminalScreen} from an incoming {@link S2CTerminalSyncPacket}. */
    public static void handleTerminalSync(S2CTerminalSyncPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof OperationTerminalScreen screen) {
            screen.applySync(packet);
        } else if (packet.openScreen()) {
            mc.setScreen(new OperationTerminalScreen(packet));
        }
    }
}
