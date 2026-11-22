package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.menu.AbstractLogisticsStationMenu;
import com.logisticscraft.lc2.network.StationActionPayload;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Shared interactive GUI logic for Smart Port and Cargo Station matching every pixel coordinate
 * in {@code docs/GUI_LAYOUT.md}.
 */
public abstract class AbstractLogisticsScreen<M extends AbstractLogisticsStationMenu>
        extends LogisticsScreenBase<M> {

    protected static final int PANEL_WIDTH = 212;
    protected static final int PANEL_HEIGHT = 222;
    protected static final int COLOR_LABEL = 0xFF2C3440;
    protected static final int COLOR_LCD_CYAN = 0xFF4CE8FF;
    protected static final int COLOR_LCD_GREEN = 0xFF66FF99;
    protected static final int COLOR_LCD_AMBER = 0xFFFFC845;
    protected static final int COLOR_LCD_RED = 0xFFFF6E6E;

    protected EditBox nameBox;
    protected EditBox xBox;
    protected EditBox yBox;
    protected EditBox zBox;
    protected Button saveNameBtn;
    protected Button prevTargetBtn;
    protected Button nextTargetBtn;
    protected Button applyCoordsBtn;
    protected Button autoReturnBtn;
    protected Button priorityBtn;
    protected Button dispatchBtn;

    private boolean fieldsInitializedFromSync = false;
    private BlockPos lastSyncedTarget = null;

    protected AbstractLogisticsScreen(M menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, PANEL_WIDTH, PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 133;
    }

    /** Returns the PNG filename (without extension) inside {@code assets/lc2/textures/gui/}. */
    protected abstract String guiTextureName();

    /** Formats the Target Readout LCD text for the currently selected target. */
    protected abstract String formatTargetReadout(StationSyncPayload state);

    /** Returns optional subclass-specific dynamic overlays (e.g. Cargo Station RF range mini-LCD). */
    protected void paintExtraPanel(ScreenCanvas canvas, StationSyncPayload state) {
    }

    @Override
    protected void init() {
        super.init();
        clearEditBoxes();

        // Station Name EditBox: (8, 15, 112, 12)
        nameBox = addTrackedEditBox(new EditBox(
                font, leftPos + 8, topPos + 15, 112, 12,
                Component.translatable("gui.lc2.station_name")
        ));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.lc2.name_placeholder"));
        addRenderableWidget(nameBox);

        // Save Name Button: (122, 15, 48, 12)
        saveNameBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.save"),
                b -> Lc2Native.sendToServer(StationActionPayload.rename(menu.stationPos(), nameBox.getValue()))
        ).bounds(leftPos + 122, topPos + 15, 48, 12).build());

        // Prev Target Button (<): (8, 28, 14, 12)
        prevTargetBtn = addRenderableWidget(Button.builder(
                Component.literal("<"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.PREV_TARGET))
        ).bounds(leftPos + 8, topPos + 28, 14, 12).build());

        // Next Target Button (>): (156, 28, 14, 12)
        nextTargetBtn = addRenderableWidget(Button.builder(
                Component.literal(">"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.NEXT_TARGET))
        ).bounds(leftPos + 156, topPos + 28, 14, 12).build());

        // Manual X/Y/Z EditBoxes: (8,41,38,12), (48,41,32,12), (82,41,38,12)
        xBox = addTrackedEditBox(new EditBox(font, leftPos + 8, topPos + 41, 38, 12, Component.literal("X")));
        xBox.setMaxLength(8);
        xBox.setHint(Component.literal("X"));
        addRenderableWidget(xBox);

        yBox = addTrackedEditBox(new EditBox(font, leftPos + 48, topPos + 41, 32, 12, Component.literal("Y")));
        yBox.setMaxLength(6);
        yBox.setHint(Component.literal("Y"));
        addRenderableWidget(yBox);

        zBox = addTrackedEditBox(new EditBox(font, leftPos + 82, topPos + 41, 38, 12, Component.literal("Z")));
        zBox.setMaxLength(8);
        zBox.setHint(Component.literal("Z"));
        addRenderableWidget(zBox);

        // Apply Coords Button: (122, 41, 48, 12)
        applyCoordsBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.set_target"),
                b -> applyManualCoords()
        ).bounds(leftPos + 122, topPos + 41, 48, 12).build());

        // Auto-Return Toggle Button: (8, 54, 54, 12)
        autoReturnBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.auto_return.off"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.TOGGLE_AUTO_RETURN))
        ).bounds(leftPos + 8, topPos + 54, 54, 12).build());

        // Energy Priority Toggle Button: (64, 54, 56, 12)
        priorityBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.priority.eu_first"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.TOGGLE_ENERGY_PRIORITY))
        ).bounds(leftPos + 64, topPos + 54, 56, 12).build());

        // Manual Dispatch Button: (122, 54, 48, 12)
        dispatchBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.dispatch"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.MANUAL_DISPATCH))
        ).bounds(leftPos + 122, topPos + 54, 48, 12).build());

        menu.setSyncListener(this::onSyncUpdated);
        onSyncUpdated();
        Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.REQUEST_SYNC));
    }

    @Override
    public void removed() {
        menu.setSyncListener(null);
        super.removed();
    }

    private void onSyncUpdated() {
        StationSyncPayload state = menu.syncedState();
        if (state == null) {
            return;
        }
        if (autoReturnBtn != null) {
            autoReturnBtn.setMessage(Component.translatable(
                    state.autoReturn() ? "gui.lc2.auto_return.on" : "gui.lc2.auto_return.off"
            ));
        }
        if (priorityBtn != null) {
            EnergyPriority prio = state.energyPriority();
            priorityBtn.setMessage(Component.translatable(prio.translationKey()));
        }
        if (!fieldsInitializedFromSync) {
            fieldsInitializedFromSync = true;
            if (nameBox != null && !nameBox.isFocused()) {
                nameBox.setValue(state.stationName());
            }
            populateTargetCoords(state);
        } else if (state.hasTarget()
                && (lastSyncedTarget == null || !lastSyncedTarget.equals(state.targetPos()))
                && !isEditingCoords()) {
            populateTargetCoords(state);
        }
        lastSyncedTarget = state.hasTarget() ? state.targetPos() : null;
    }

    private boolean isEditingCoords() {
        return (xBox != null && xBox.isFocused())
                || (yBox != null && yBox.isFocused())
                || (zBox != null && zBox.isFocused());
    }

    private void populateTargetCoords(StationSyncPayload state) {
        if (xBox == null || yBox == null || zBox == null) {
            return;
        }
        if (state.hasTarget()) {
            xBox.setValue(Integer.toString(state.targetPos().getX()));
            yBox.setValue(Integer.toString(state.targetPos().getY()));
            zBox.setValue(Integer.toString(state.targetPos().getZ()));
        } else {
            xBox.setValue("");
            yBox.setValue("");
            zBox.setValue("");
        }
    }

    private void applyManualCoords() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int y = Integer.parseInt(yBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            Lc2Native.sendToServer(StationActionPayload.setTarget(menu.stationPos(), new BlockPos(x, y, z)));
        } catch (NumberFormatException ignored) {
            // Leave current target unchanged if user typed invalid integer text
        }
    }

    @Override
    protected final void paintPanel(ScreenCanvas canvas, int mouseX, int mouseY) {
        // 1. Main 212x222 background panel
        canvas.blit(guiTextureName(), 0, 0, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        StationSyncPayload state = menu.syncedState();
        if (state == null) {
            return;
        }

        // 2. EU Gauge Fill (bottom-up): screen (186, 17, 16, 40), UV (216, 0, 16, 40)
        double maxEu = Math.max(1.0, state.maxEu());
        int fillHeight = (int) Math.round(40.0 * Math.clamp(state.storedEu() / maxEu, 0.0, 1.0));
        if (fillHeight > 0) {
            canvas.blit(guiTextureName(), 186, 17 + 40 - fillHeight, 216, 40 - fillHeight, 16, fillHeight);
        }

        // 3. EU Bolt Indicator: screen (189, 60, 10, 14), UV (234, 0, 10, 14)
        if (state.storedEu() > 0.0) {
            canvas.blit(guiTextureName(), 189, 60, 234, 0, 10, 14);
        }

        // 4. Chunk-Loader Active Badge: screen (186, 180, 16, 16), UV (216, 64, 16, 16)
        if (state.chunkLoaderActive()) {
            canvas.blit(guiTextureName(), 186, 180, 216, 64, 16, 16);
        }

        // 5. Vehicle / Port Status Badge: screen (186, 199, 16, 16), Active (216, 44) / Warn (234, 44)
        int badgeU = state.vehicleReady() ? 216 : 234;
        canvas.blit(guiTextureName(), 186, 199, badgeU, 44, 16, 16);

        // 6. Target Readout LCD at (23, 28, 132, 12) -> render text at (26, 30)
        String targetText = formatTargetReadout(state);
        canvas.drawClippedString(targetText, 26, 30, 126, state.hasTarget() ? COLOR_LCD_CYAN : COLOR_LCD_AMBER);

        // 7. Status Readout LCD at (7, 67, 164, 10) -> render text at (10, 68)
        String statusText = Component.translatable(state.statusKey()).getString();
        int statusColor = state.statusKey().contains(".failure.") ? COLOR_LCD_RED : COLOR_LCD_GREEN;
        canvas.drawClippedString(statusText, 10, 68, 158, statusColor);

        // 8. Subclass-specific extras (e.g. Cargo Station RF Range Mini-LCD)
        paintExtraPanel(canvas, state);
    }

    @Override
    protected final void paintOverlayTooltips(ScreenCanvas canvas, int mouseX, int mouseY) {
        StationSyncPayload state = menu.syncedState();
        if (state == null) {
            return;
        }
        int relX = mouseX - leftPos;
        int relY = mouseY - topPos;

        // EU Gauge Frame tooltip: (185, 16, 18, 42)
        if (relX >= 185 && relX < 203 && relY >= 16 && relY < 58) {
            canvas.drawTooltip(
                    List.of(Component.translatable(
                            "gui.lc2.eu_stored",
                            Math.round(state.storedEu()),
                            Math.round(state.maxEu()),
                            state.inputTier(),
                            state.packetLimitEu()
                    )),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Chunk-Loader Badge tooltip: (186, 180, 16, 16)
        if (relX >= 186 && relX < 202 && relY >= 180 && relY < 196) {
            canvas.drawTooltip(
                    List.of(Component.translatable(
                            state.chunkLoaderActive()
                                    ? "gui.lc2.chunk_loader_active"
                                    : "gui.lc2.chunk_loader_inactive"
                    )),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Target LCD tooltip: (23, 28, 132, 12)
        if (relX >= 23 && relX < 155 && relY >= 28 && relY < 40 && state.hasTarget()) {
            StationSyncPayload.TargetInfo info = state.selectedTargetInfo();
            if (info != null) {
                canvas.drawTooltip(buildTargetTooltip(info), mouseX, mouseY);
            }
        }
    }

    /** Builds detailed tooltip lines when hovering over the Target Readout LCD. */
    protected abstract List<Component> buildTargetTooltip(StationSyncPayload.TargetInfo info);
}
