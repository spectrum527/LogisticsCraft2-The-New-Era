package com.logisticscraft.lc2.compat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;

/**
 * Optional soft-dependency registration for The One Probe ({@code theoneprobe}).
 * <p>
 * {@link Lc2TopProvider} is only referenced inside the {@code ModList.get().isLoaded("theoneprobe")}
 * guard so ZERO TOP classes are loaded when The One Probe is absent.
 */
public final class Lc2TopCompat {

    private static final String TOP_MOD_ID = "theoneprobe";

    private Lc2TopCompat() {
    }

    /** Registers the {@link InterModEnqueueEvent} listener on the mod event bus. */
    public static void install(IEventBus modBus) {
        modBus.addListener(InterModEnqueueEvent.class, event -> {
            if (ModList.get().isLoaded(TOP_MOD_ID)) {
                InterModComms.sendTo(TOP_MOD_ID, "getTheOneProbe", Lc2TopProvider::createCallback);
            }
        });
    }
}
