package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
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
}
