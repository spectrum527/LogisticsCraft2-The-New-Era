package com.logisticscraft.lc2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Screen accessor shared by NeoForge 1.21.1 and 26.1.2 ({@code mc.screen} / {@code mc.setScreen}).
 */
public final class Lc2ScreenAccess {

    private Lc2ScreenAccess() {
    }

    /** Returns the currently open client screen, or {@code null} if none. */
    public static Screen currentScreen(Minecraft mc) {
        return mc.screen;
    }

    /** Sets the active client screen. */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }
}
