package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.menu.AbstractLogisticsStationMenu;
import com.logisticscraft.lc2.network.StationActionPayload;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.network.TerminalActionPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Shared interactive 2-zone GUI logic for Smart Port and Cargo Station matching every pixel
 * coordinate in {@code docs/GUI_LAYOUT.md} ({@code 392x234} panel on {@code 512x256} atlas).
 */
public abstract class AbstractLogisticsScreen<M extends AbstractLogisticsStationMenu>
        extends LogisticsScreenBase<M> {

    protected static final int PANEL_WIDTH = 392;
    protected static final int PANEL_HEIGHT = 234;
    protected static final int TARGET_LCD_TEXT_WIDTH = 250;
    protected static final int STATUS_LCD_TEXT_WIDTH = 165;
    protected static final int COLOR_LABEL = 0xFF2C3440;
    protected static final int COLOR_ZONE_OUTBOUND = 0xFF7A4200;
    protected static final int COLOR_ZONE_STORAGE = 0xFF004D66;
    protected static final int COLOR_LCD_CYAN = 0xFF4CE8FF;
    protected static final int COLOR_LCD_GREEN = 0xFF66FF99;
    protected static final int COLOR_LCD_AMBER = 0xFFFFC845;
    protected static final int COLOR_LCD_RED = 0xFFFF6E6E;
    protected static final int COLOR_LCD_MUTED = 0xFF7B90A0;

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
    protected Button swapBtn;
    protected Button backToTerminalBtn;

    private boolean fieldsInitializedFromSync = false;
    private BlockPos lastSyncedTarget = null;
    private int remoteBadgeX = -1;
    private int remoteBadgeY = -1;
    private int remoteBadgeW = 0;
    private int remoteBadgeH = 0;

    protected AbstractLogisticsScreen(M menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, PANEL_WIDTH, PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelX = 99;
        this.inventoryLabelY = 144;
    }

    /** Returns the PNG filename (without extension) inside {@code assets/lc2/textures/gui/}. */
    protected abstract String guiTextureName();

    /** Returns the live header title, reflecting renames immediately once synced from the server. */
    @Override
    protected Component headerTitle() {
        StationSyncPayload state = menu.syncedState();
        if (state != null && state.stationName() != null && !state.stationName().isBlank()) {
            return Component.literal(state.stationName());
        }
        return title;
    }

    /** Formats a target readout preserving the right-hand telemetry suffix while truncating long names. */
    protected final String fitTargetReadout(String displayName, String suffix, int maxWidthPx) {
        String full = displayName + suffix;
        if (font.width(full) <= maxWidthPx) {
            return full;
        }
        int suffixWidth = font.width(suffix);
        int ellipsisWidth = font.width("..");
        int nameBudget = Math.max(8, maxWidthPx - suffixWidth - ellipsisWidth);
        String trimmed = font.plainSubstrByWidth(displayName, nameBudget).trim();
        return trimmed + ".." + suffix;
    }

    /** Formats the Target Readout LCD text for the currently selected target. */
    protected abstract String formatTargetReadout(StationSyncPayload state);

    /** Returns optional subclass-specific dynamic overlays (e.g. Cargo Station RF range mini-LCD). */
    protected void paintExtraPanel(ScreenCanvas canvas, StationSyncPayload state) {
    }

    @Override
    protected void init() {
        super.init();
        clearEditBoxes();

        // Row 1: Station Name EditBox (8, 13, 150, 12)
        nameBox = addTrackedEditBox(new EditBox(
                font, leftPos + 8, topPos + 13, 150, 12,
                Component.translatable("gui.trera.station_name")
        ));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.trera.name_placeholder"));
        addRenderableWidget(nameBox);

        // Button 0: Save Name (162, 13, 44, 12)
        saveNameBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.trera.save"),
                b -> Lc2Native.sendToServer(StationActionPayload.rename(menu.stationPos(), nameBox.getValue()))
        ).bounds(leftPos + 162, topPos + 13, 44, 12).build());

        // Button 1: Prev Target (<) (8, 27, 14, 12)
        prevTargetBtn = addRenderableWidget(Button.builder(
                Component.literal("<"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.PREV_TARGET))
        ).bounds(leftPos + 8, topPos + 27, 14, 12).build());

        // Button 2: Next Target (>) (280, 27, 14, 12)
        nextTargetBtn = addRenderableWidget(Button.builder(
                Component.literal(">"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.NEXT_TARGET))
        ).bounds(leftPos + 280, topPos + 27, 14, 12).build());

        // Row 3: Manual X/Y/Z EditBoxes: (8,41,44,12), (54,41,36,12), (92,41,44,12)
        xBox = addTrackedEditBox(new EditBox(font, leftPos + 8, topPos + 41, 44, 12, Component.literal("X")));
        xBox.setMaxLength(8);
        xBox.setHint(Component.literal("X"));
        addRenderableWidget(xBox);

        yBox = addTrackedEditBox(new EditBox(font, leftPos + 54, topPos + 41, 36, 12, Component.literal("Y")));
        yBox.setMaxLength(6);
        yBox.setHint(Component.literal("Y"));
        addRenderableWidget(yBox);

        zBox = addTrackedEditBox(new EditBox(font, leftPos + 92, topPos + 41, 44, 12, Component.literal("Z")));
        zBox.setMaxLength(8);
        zBox.setHint(Component.literal("Z"));
        addRenderableWidget(zBox);

        // Button 3: Set Coords (138, 41, 36, 12)
        applyCoordsBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.trera.set_target"),
                b -> applyManualCoords()
        ).bounds(leftPos + 138, topPos + 41, 36, 12).build());

        // Button 4: Auto-Return Toggle (210, 13, 66, 12)
        autoReturnBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.trera.auto_return.off"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.TOGGLE_AUTO_RETURN))
        ).bounds(leftPos + 210, topPos + 13, 66, 12).build());

        // Button 5: Energy Priority Toggle (279, 13, 68, 12)
        priorityBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.trera.priority.eu_first"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.TOGGLE_ENERGY_PRIORITY))
        ).bounds(leftPos + 279, topPos + 13, 68, 12).build());

        // Button 6: Launch / Manual Dispatch (297, 27, 50, 12)
        dispatchBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.trera.dispatch"),
                b -> Lc2Native.sendToServer(StationActionPayload.simple(menu.stationPos(), StationActionPayload.Action.MANUAL_DISPATCH))
        ).bounds(leftPos + 297, topPos + 27, 50, 12).build());

        // Button 7: Center Swap Button (172, 104, 16, 20) with baked 12x10 swap arrows at UV (414, 64, 12, 10)
        swapBtn = addRenderableWidget(createIconButton(
                172,
                104,
                16,
                20,
                Component.translatable("gui.trera.swap.title"),
                guiTextureName(),
                2,
                5,
                414,
                64,
                12,
                10,
                b -> Lc2Native.sendToServer(StationActionPayload.simple(
                        menu.stationPos(), StationActionPayload.Action.SWAP_CARGO_ZONES))
        ));

        // Button 8 (remote mode only): Return to Operation Terminal list (279, 1, 68, 11)
        if (menu.isRemoteMode()) {
            backToTerminalBtn = addRenderableWidget(Button.builder(
                    Component.translatable("gui.trera.back_to_terminal"),
                    b -> Lc2Native.sendToServer(TerminalActionPayload.returnToList(menu.remoteTerminalId()))
            ).bounds(leftPos + 279, topPos + 1, 68, 11).build());
        } else {
            backToTerminalBtn = null;
        }

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
                    state.autoReturn() ? "gui.trera.auto_return.on" : "gui.trera.auto_return.off"
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
        // 1. Main 392x234 background panel from 512x256 atlas
        canvas.blit(guiTextureName(), 0, 0, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        // 2. 2-Zone cargo headers: Outbound at (8, 77) and Storage at (190, 77)
        canvas.drawString(Component.translatable("gui.trera.zone.outbound"), 8, 77, COLOR_ZONE_OUTBOUND);
        canvas.drawString(Component.translatable("gui.trera.zone.storage"), 190, 77, COLOR_ZONE_STORAGE);

        // 2b. Remote read-only pill next to header title (never overlaps Back button at x = 279)
        if (menu.isRemoteMode()) {
            String badgeLabel = Component.translatable("gui.trera.remote_readonly").getString();
            int titleWidth = canvas.fontWidth(headerTitle().getString());
            int desiredX = Math.max(8 + titleWidth + 6, 92);
            int maxRight = 275;
            int badgeW = Math.min(maxRight - 64, 16 + canvas.fontWidth(badgeLabel) + 4);
            int badgeX = Math.min(desiredX, maxRight - badgeW);
            int badgeY = 1;
            int badgeH = 11;
            if (badgeX >= 64 && badgeW >= 24) {
                canvas.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 0xFF142D3E);
                canvas.fillOutline(badgeX, badgeY, badgeW, badgeH, 0xFF38A8CC);
                canvas.blit("operation_terminal", badgeX + 2, badgeY + 1, 370, 44, 10, 10);
                canvas.drawClippedString(badgeLabel, badgeX + 14, badgeY + 2, badgeW - 16, COLOR_LCD_CYAN);
                remoteBadgeX = badgeX;
                remoteBadgeY = badgeY;
                remoteBadgeW = badgeW;
                remoteBadgeH = badgeH;
            } else {
                remoteBadgeW = 0;
            }
        } else {
            remoteBadgeW = 0;
        }

        StationSyncPayload state = menu.syncedState();
        if (state == null) {
            return;
        }

        // 3. EU Gauge Fill (bottom-up): screen (363, 14, 16, 40), UV (396, 0, 16, 40)
        double maxEu = Math.max(1.0, state.maxEu());
        int fillHeight = (int) Math.round(40.0 * Math.clamp(state.storedEu() / maxEu, 0.0, 1.0));
        if (fillHeight > 0) {
            canvas.blit(guiTextureName(), 363, 14 + 40 - fillHeight, 396, 40 - fillHeight, 16, fillHeight);
        }

        // 4. EU Bolt Indicator: screen (366, 57, 10, 14), UV (414, 0, 10, 14)
        if (state.storedEu() > 0.0) {
            canvas.blit(guiTextureName(), 366, 57, 414, 0, 10, 14);
        }

        // 5. Chunk-Loader Active Badge: screen (363, 180, 16, 16), UV (396, 64, 16, 16)
        if (state.chunkLoaderActive()) {
            canvas.blit(guiTextureName(), 363, 180, 396, 64, 16, 16);
        }

        // 6. Vehicle / Port Ready Badge: screen (363, 200, 16, 16), Active (396, 44) / Warn (414, 44)
        int badgeU = state.vehicleReady() ? 396 : 414;
        canvas.blit(guiTextureName(), 363, 200, badgeU, 44, 16, 16);

        // 7. Target Readout LCD at (23, 27, 256, 12) -> render text at (26, 29), maxWidth = 250
        String targetText = formatTargetReadout(state);
        canvas.drawClippedString(
                targetText,
                26,
                29,
                TARGET_LCD_TEXT_WIDTH,
                state.hasTarget() ? COLOR_LCD_CYAN : COLOR_LCD_AMBER
        );

        // 8. Status Readout LCD at (176, 41, 171, 12) -> render text at (179, 43), maxWidth = 165
        String statusText = Component.translatable(state.statusKey()).getString();
        int statusColor = state.statusKey().contains(".failure.") ? COLOR_LCD_RED : COLOR_LCD_GREEN;
        canvas.drawClippedString(statusText, 179, 43, STATUS_LCD_TEXT_WIDTH, statusColor);

        // 9. Subclass-specific middle deck strip (Harbor Telemetry / RF Mini-LCD + Flight Deck Status)
        paintExtraPanel(canvas, state);
    }

    @Override
    protected final void paintOverlayTooltips(ScreenCanvas canvas, int mouseX, int mouseY) {
        int relX = mouseX - leftPos;
        int relY = mouseY - topPos;

        // Remote read-only badge hover tooltip
        if (menu.isRemoteMode()
                && remoteBadgeW > 0
                && relX >= remoteBadgeX
                && relX < remoteBadgeX + remoteBadgeW
                && relY >= remoteBadgeY
                && relY < remoteBadgeY + remoteBadgeH) {
            canvas.drawTooltip(List.of(Component.translatable("gui.trera.remote_readonly.tooltip")), mouseX, mouseY);
            return;
        }

        // Swap button hover tooltip: (172, 104, 16, 20)
        if (relX >= 172 && relX < 188 && relY >= 104 && relY < 124) {
            canvas.drawTooltip(List.of(Component.translatable("tooltip.trera.swap")), mouseX, mouseY);
            return;
        }

        StationSyncPayload state = menu.syncedState();
        if (state == null) {
            return;
        }

        // EU Gauge Frame tooltip: (362, 13, 18, 42)
        if (relX >= 362 && relX < 380 && relY >= 13 && relY < 55) {
            canvas.drawTooltip(
                    List.of(Component.translatable(
                            "gui.trera.eu_stored",
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

        // Chunk-Loader Badge tooltip: (362, 179, 18, 18)
        if (relX >= 362 && relX < 380 && relY >= 179 && relY < 197) {
            canvas.drawTooltip(
                    List.of(Component.translatable(
                            state.chunkLoaderActive()
                                    ? "gui.trera.chunk_loader_active"
                                    : "gui.trera.chunk_loader_inactive"
                    )),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Vehicle / Port Ready Badge tooltip: (362, 199, 18, 18)
        if (relX >= 362 && relX < 380 && relY >= 199 && relY < 217) {
            List<Component> lines = buildVehicleBadgeTooltip(state);
            if (!lines.isEmpty()) {
                canvas.drawTooltip(lines, mouseX, mouseY);
            }
            return;
        }

        // Target LCD tooltip: (23, 27, 256, 12)
        if (relX >= 23 && relX < 279 && relY >= 27 && relY < 39 && state.hasTarget()) {
            StationSyncPayload.TargetInfo info = state.selectedTargetInfo();
            if (info != null) {
                canvas.drawTooltip(buildTargetTooltip(info), mouseX, mouseY);
            }
        }
    }

    /** Builds detailed tooltip lines when hovering over the Target Readout LCD. */
    protected abstract List<Component> buildTargetTooltip(StationSyncPayload.TargetInfo info);

    /** Builds tooltip lines when hovering over the right-column Vehicle / Port Ready badge. */
    protected List<Component> buildVehicleBadgeTooltip(StationSyncPayload state) {
        return List.of();
    }
}
