package com.logisticscraft.lc2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Screen accessor for NeoForge 26.2 ({@code mc.gui.screen()} / {@code mc.gui.setScreen}).
 */
public final class Lc2ScreenAccess {

    private Lc2ScreenAccess() {
    }

    /** Returns the currently open client screen, or {@code null} if none. */
    public static Screen currentScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    /** Sets the active client screen. */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }
}
