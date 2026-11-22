package com.logisticscraft.lc2.qa;

import com.google.gson.JsonObject;
import com.logisticscraft.lc2.client.AbstractLogisticsScreen;
import com.logisticscraft.lc2.network.StationSyncPayload;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * QA-only screen probe strictly inside {@code com.logisticscraft.lc2.qa} (zero split-package conflict!)
 * operating {@link AbstractLogisticsScreen} widgets via public {@link Screen#children()}.
 */
public final class Lc2ScreenProbe {

    private Lc2ScreenProbe() {
    }

    private static List<EditBox> editBoxes(Screen screen) {
        return screen.children().stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .toList();
    }

    private static List<Button> buttons(Screen screen) {
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
    }

    public static void recordScreenState(Screen screen, JsonObject out) {
        if (!(screen instanceof AbstractLogisticsScreen<?> ls)) {
            return;
        }
        List<EditBox> edits = editBoxes(screen);
        List<Button> btns = buttons(screen);
        if (edits.size() >= 4) {
            out.addProperty("nameBoxText", edits.get(0).getValue());
            out.addProperty("xBoxText", edits.get(1).getValue());
            out.addProperty("yBoxText", edits.get(2).getValue());
            out.addProperty("zBoxText", edits.get(3).getValue());
        }
        if (btns.size() >= 7) {
            out.addProperty("autoReturnBtnText", btns.get(4).getMessage().getString());
            out.addProperty("priorityBtnText", btns.get(5).getMessage().getString());
        }
        StationSyncPayload sync = ls.getMenu().syncedState();
        if (sync != null) {
            out.addProperty("syncPresent", true);
            out.addProperty("syncStationName", sync.stationName());
            out.addProperty("syncStoredEu", sync.storedEu());
            out.addProperty("syncMaxEu", sync.maxEu());
            out.addProperty("syncAutoReturn", sync.autoReturn());
            out.addProperty("syncPriority", sync.energyPriority().name());
            out.addProperty("syncHasTarget", sync.hasTarget());
            out.addProperty("syncTargetPos",
                    sync.targetPos().getX() + "," + sync.targetPos().getY() + "," + sync.targetPos().getZ());
            out.addProperty("syncStatusKey", sync.statusKey());
            out.addProperty("syncChunkLoader", sync.chunkLoaderActive());
            out.addProperty("syncVehicleReady", sync.vehicleReady());
            out.addProperty("syncRfRange", sync.rfRange());
            out.addProperty("syncTargetCount", sync.targets().size());
        } else {
            out.addProperty("syncPresent", false);
        }
    }

    public static void renameAndSave(Screen screen, String newName) {
        if (!(screen instanceof AbstractLogisticsScreen<?>)) {
            throw new IllegalStateException("Not an AbstractLogisticsScreen: " + screen);
        }
        List<EditBox> edits = editBoxes(screen);
        List<Button> btns = buttons(screen);
        edits.get(0).setValue(newName);
        QaClientPlatform.pressButton(btns.get(0)); // saveNameBtn
    }

    public static void clickButton(Screen screen, String which) {
        if (!(screen instanceof AbstractLogisticsScreen<?>)) {
            throw new IllegalStateException("Not an AbstractLogisticsScreen: " + screen);
        }
        List<Button> btns = buttons(screen);
        int idx = switch (which) {
            case "save" -> 0;
            case "prev_target" -> 1;
            case "next_target" -> 2;
            case "apply_coords" -> 3;
            case "toggle_autoreturn" -> 4;
            case "toggle_priority" -> 5;
            case "dispatch" -> 6;
            default -> throw new IllegalArgumentException("Unknown GUI button: " + which);
        };
        QaClientPlatform.pressButton(btns.get(idx));
    }

    public static void setCoordsAndApply(Screen screen, int x, int y, int z) {
        if (!(screen instanceof AbstractLogisticsScreen<?>)) {
            throw new IllegalStateException("Not an AbstractLogisticsScreen: " + screen);
        }
        List<EditBox> edits = editBoxes(screen);
        List<Button> btns = buttons(screen);
        edits.get(1).setValue(Integer.toString(x));
        edits.get(2).setValue(Integer.toString(y));
        edits.get(3).setValue(Integer.toString(z));
        QaClientPlatform.pressButton(btns.get(3)); // applyCoordsBtn
    }
}
