package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.network.TerminalSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * Isolated client-only packet dispatch helpers (never loaded on dedicated servers).
 */
public final class Lc2ClientHandlers {

    private Lc2ClientHandlers() {
    }

    /** Opens or refreshes the Operation Terminal Selection GUI with fresh server data. */
    public static void handleTerminalSync(TerminalSyncPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (Lc2ScreenAccess.currentScreen(mc) instanceof OperationTerminalScreen screen) {
            screen.applySync(payload);
        } else if (payload.openScreen()) {
            Lc2ScreenAccess.setScreen(mc, new OperationTerminalScreen(payload));
        }
    }
}
