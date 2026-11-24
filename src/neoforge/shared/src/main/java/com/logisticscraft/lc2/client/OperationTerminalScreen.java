package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.network.TerminalActionPayload;
import com.logisticscraft.lc2.network.TerminalSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Interactive Operation Terminal Selection GUI matching every exact pixel coordinate in
 * {@code assets/lc2/textures/gui/operation_terminal.png} ({@code 320x224} panel on {@code 512x256} atlas).
 */
public final class OperationTerminalScreen extends TerminalScreenBase {

    private static final String GUI_TEXTURE = "operation_terminal";
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 224;
    private static final int VISIBLE_ROWS = 6;
    private static final int ROW_X = 10;
    private static final int ROW_Y_START = 40;
    private static final int ROW_STEP = 27;
    private static final int ROW_W = 292;
    private static final int ROW_H = 25;

    private static final int SCROLLBAR_X = 306;
    private static final int SCROLLBAR_Y = 38;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_H = 166;
    private static final int THUMB_H = 24;
    private static final int THUMB_TRAVEL = SCROLLBAR_H - THUMB_H; // 142 (y = 38..180)

    private static final int COLOR_HEADER = 0xFF2C3440;
    private static final int COLOR_LCD_CYAN = 0xFF4CE8FF;
    private static final int COLOR_LCD_GREEN = 0xFF66FF99;
    private static final int COLOR_LCD_AMBER = 0xFFFFC845;
    private static final int COLOR_LCD_RED = 0xFFFF6E6E;
    private static final int COLOR_LCD_MUTED = 0xFF8EA4BA;
    private static final int COLOR_TEXT_ONLINE = 0xFFEAF6FF;
    private static final int COLOR_TEXT_OFFLINE = 0xFFAAB8C8;

    private static final int CARD_ONLINE_BG = 0xFF1A2532;
    private static final int CARD_ONLINE_HOVER = 0xFF233648;
    private static final int CARD_ONLINE_BORDER = 0xFF2E4960;
    private static final int CARD_OFFLINE_BG = 0xFF171D26;
    private static final int CARD_OFFLINE_HOVER = 0xFF1F2733;
    private static final int CARD_OFFLINE_BORDER = 0xFF2B3442;

    private String terminalId;
    private double storedEu;
    private double maxEu;
    private int boundCount;
    private List<TerminalTargetEntry> allEntries = List.of();
    private List<TerminalTargetEntry> filteredEntries = List.of();
    private String searchQuery = "";
    private int scrollOffset = 0;
    private boolean draggingScrollbar = false;

    private EditBox searchBox;
    private Button refreshBtn;
    private final Button[] connectBtns = new Button[VISIBLE_ROWS];
    private final Button[] unbindBtns = new Button[VISIBLE_ROWS];

    public OperationTerminalScreen(TerminalSyncPayload initial) {
        super(Component.translatable("gui.lc2.terminal.title"), PANEL_WIDTH, PANEL_HEIGHT);
        applyPayloadFields(initial);
    }

    /** Updates the screen live when a refreshed {@link TerminalSyncPayload} arrives from the server. */
    public void applySync(TerminalSyncPayload payload) {
        applyPayloadFields(payload);
        rebuildFilteredAndRows();
    }

    private void applyPayloadFields(TerminalSyncPayload payload) {
        this.terminalId = payload.terminalId();
        this.storedEu = payload.storedEu();
        this.maxEu = Math.max(1.0, payload.maxEu());
        this.boundCount = payload.boundCount();
        this.allEntries = payload.entries();
    }

    @Override
    protected void init() {
        super.init();

        // Search EditBox: (8, 20, 236, 14)
        searchBox = addTrackedEditBox(new EditBox(
                font,
                leftPos + 8,
                topPos + 20,
                236,
                14,
                Component.translatable("gui.lc2.terminal.search_placeholder")
        ));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("gui.lc2.terminal.search_placeholder"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(q -> {
            searchQuery = q == null ? "" : q;
            scrollOffset = 0;
            rebuildFilteredAndRows();
        });
        addRenderableWidget(searchBox);

        // Refresh Button: (248, 20, 64, 14)
        refreshBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.lc2.terminal.refresh"),
                b -> Lc2Native.sendToServer(TerminalActionPayload.refresh(terminalId))
        ).bounds(leftPos + 248, topPos + 20, 64, 14).build());

        // 6 visible row action buttons
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int rowIdx = i;
            int ry = ROW_Y_START + i * ROW_STEP;

            // Connect button: (rx + 222, ry + 5, 48, 15)
            connectBtns[i] = addRenderableWidget(Button.builder(
                    Component.translatable("gui.lc2.terminal.connect"),
                    b -> onConnectRow(rowIdx)
            ).bounds(leftPos + ROW_X + 222, topPos + ry + 5, 48, 15).build());

            // Unbind button: (rx + 274, ry + 5, 14, 15) with 10x10 X icon centered at (+2, +2)
            unbindBtns[i] = addRenderableWidget(createHoverIconButton(
                    ROW_X + 274,
                    ry + 5,
                    14,
                    15,
                    Component.translatable("gui.lc2.terminal.unbind"),
                    GUI_TEXTURE,
                    2,
                    2,
                    328,
                    44,
                    328,
                    56,
                    10,
                    10,
                    b -> onUnbindRow(rowIdx)
            ));
        }

        rebuildFilteredAndRows();
    }

    private void rebuildFilteredAndRows() {
        List<TerminalTargetEntry> matched = new ArrayList<>(allEntries.size());
        for (TerminalTargetEntry entry : allEntries) {
            if (entry.matchesFilter(searchQuery)) {
                matched.add(entry);
            }
        }
        this.filteredEntries = List.copyOf(matched);
        int maxScroll = maxScrollOffset();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        updateRowButtons();
    }

    private void updateRowButtons() {
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            if (connectBtns[i] == null || unbindBtns[i] == null) {
                continue;
            }
            int idx = scrollOffset + i;
            if (idx < filteredEntries.size()) {
                TerminalTargetEntry entry = filteredEntries.get(idx);
                connectBtns[i].visible = true;
                connectBtns[i].active = entry.isConnectable();
                unbindBtns[i].visible = true;
                unbindBtns[i].active = true;
            } else {
                connectBtns[i].visible = false;
                connectBtns[i].active = false;
                unbindBtns[i].visible = false;
                unbindBtns[i].active = false;
            }
        }
    }

    private int maxScrollOffset() {
        return Math.max(0, filteredEntries.size() - VISIBLE_ROWS);
    }

    private void onConnectRow(int visibleRow) {
        int idx = scrollOffset + visibleRow;
        if (idx < 0 || idx >= filteredEntries.size()) {
            return;
        }
        TerminalTargetEntry entry = filteredEntries.get(idx);
        if (!entry.isConnectable() || !entry.hasStation() || entry.stationPos() == null) {
            return;
        }
        Lc2Native.sendToServer(TerminalActionPayload.connect(
                terminalId,
                LogisticsSavedData.toBlockPos(entry.commPos()),
                LogisticsSavedData.toBlockPos(entry.stationPos())
        ));
    }

    private void onUnbindRow(int visibleRow) {
        int idx = scrollOffset + visibleRow;
        if (idx < 0 || idx >= filteredEntries.size()) {
            return;
        }
        TerminalTargetEntry entry = filteredEntries.get(idx);
        Lc2Native.sendToServer(TerminalActionPayload.unbind(
                terminalId,
                LogisticsSavedData.toBlockPos(entry.commPos())
        ));
    }

    private int countOnlineEntries() {
        int count = 0;
        for (TerminalTargetEntry entry : allEntries) {
            if (entry.isConnectable()) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void paintPanel(ScreenCanvas canvas, int mouseX, int mouseY) {
        int relX = mouseX - leftPos;
        int relY = mouseY - topPos;

        // 1. Main 320x224 panel from 512x256 atlas
        canvas.blit(GUI_TEXTURE, 0, 0, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        // 2. Header title at (8, 5), maxWidth = 174
        canvas.drawClippedString(title.getString(), 8, 5, 174, COLOR_HEADER);

        // 3. Header EU/FE LCD mini-bar: frame (186, 4, 126, 11), fill (187, 5, 124, 9) UV (328, 30)
        double ratio = Math.clamp(storedEu / maxEu, 0.0, 1.0);
        int fillW = (int) Math.round(124.0 * ratio);
        if (fillW > 0) {
            canvas.blit(GUI_TEXTURE, 187, 5, 328, 30, fillW, 9);
        }
        String energyShort = Component.translatable(
                "gui.lc2.terminal.energy_short",
                Math.round(storedEu),
                Math.round(maxEu)
        ).getString();
        int energyW = canvas.fontWidth(energyShort);
        int energyX = 187 + Math.max(2, (124 - Math.min(energyW, 120)) / 2);
        canvas.drawClippedString(energyShort, energyX, 6, 120, 0xFFEAFBFF);

        // 4. Station list viewport (8, 38, 296, 166)
        if (allEntries.isEmpty()) {
            drawCenteredViewportMessage(canvas, Component.translatable("gui.lc2.terminal.empty").getString());
        } else if (filteredEntries.isEmpty()) {
            drawCenteredViewportMessage(canvas, Component.translatable("gui.lc2.terminal.no_matches").getString());
        } else {
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int idx = scrollOffset + i;
                if (idx >= filteredEntries.size()) {
                    break;
                }
                TerminalTargetEntry entry = filteredEntries.get(idx);
                int rx = ROW_X;
                int ry = ROW_Y_START + i * ROW_STEP;
                boolean hovered = relX >= rx && relX < rx + ROW_W && relY >= ry && relY < ry + ROW_H;
                paintRowCard(canvas, entry, rx, ry, hovered);
            }
        }

        // 5. Vertical scrollbar thumb (6x24 at UV 344, 44) in track (306, 38, 6, 166)
        int maxScroll = maxScrollOffset();
        int thumbY = SCROLLBAR_Y;
        if (maxScroll > 0) {
            thumbY = SCROLLBAR_Y + Math.round(((float) scrollOffset / (float) maxScroll) * THUMB_TRAVEL);
        }
        canvas.blit(GUI_TEXTURE, SCROLLBAR_X, thumbY, 344, 44, SCROLLBAR_W, THUMB_H);

        // 6. Footer status LCD (8, 207, 304, 12) -> text at (12, 209), maxWidth = 296
        String footer = Component.translatable(
                "gui.lc2.terminal.footer",
                boundCount,
                countOnlineEntries()
        ).getString();
        canvas.drawClippedString(footer, 12, 209, 296, COLOR_LCD_CYAN);
    }

    private void paintRowCard(ScreenCanvas canvas, TerminalTargetEntry entry, int rx, int ry, boolean hovered) {
        boolean online = entry.isConnectable();
        int bg = online
                ? (hovered ? CARD_ONLINE_HOVER : CARD_ONLINE_BG)
                : (hovered ? CARD_OFFLINE_HOVER : CARD_OFFLINE_BG);
        int border = online ? CARD_ONLINE_BORDER : CARD_OFFLINE_BORDER;
        canvas.fill(rx, ry, rx + ROW_W, ry + ROW_H, bg);
        canvas.fillOutline(rx, ry, ROW_W, ROW_H, border);

        // Station badge 14x14 at (rx + 4, ry + 5)
        int badgeU;
        if (!entry.hasStation()) {
            badgeU = 360;
        } else if (entry.stationType() == StationType.SMART_PORT) {
            badgeU = 328;
        } else {
            badgeU = 344;
        }
        canvas.blit(GUI_TEXTURE, rx + 4, ry + 5, badgeU, 0, 14, 14);

        // Status LED 8x8 at (rx + 22, ry + 8)
        int ledU = switch (entry.status()) {
            case ONLINE -> 328;
            case UNPOWERED -> 338;
            case NO_ADJACENT_STATION, CHUNK_UNLOADED, WRONG_DIMENSION -> 348;
            case COMM_MISSING -> 358;
        };
        canvas.blit(GUI_TEXTURE, rx + 22, ry + 8, ledU, 18, 8, 8);

        // Line 1: title + distance at (rx + 34, ry + 3), maxWidth = 184
        String titleLine = formatEntryTitle(entry);
        if (entry.distance() >= 0) {
            titleLine = titleLine + " [" + Component.translatable("gui.lc2.terminal.distance", entry.distance()).getString() + "]";
        }
        canvas.drawClippedString(titleLine, rx + 34, ry + 3, 184, online ? COLOR_TEXT_ONLINE : COLOR_TEXT_OFFLINE);

        // Line 2: coords + status at (rx + 34, ry + 14), maxWidth = 184
        BlockPos3i pos = entry.displayPos();
        String coords = Component.translatable(
                "gui.lc2.terminal.station_coords",
                pos.x(),
                pos.y(),
                pos.z()
        ).getString();
        String statusText = Component.translatable(entry.status().translationKey()).getString();
        int statusColor = switch (entry.status()) {
            case ONLINE -> COLOR_LCD_GREEN;
            case UNPOWERED -> COLOR_LCD_AMBER;
            case COMM_MISSING -> COLOR_LCD_RED;
            case NO_ADJACENT_STATION, CHUNK_UNLOADED, WRONG_DIMENSION -> COLOR_LCD_MUTED;
        };
        canvas.drawClippedString(coords + " · " + statusText, rx + 34, ry + 14, 184, statusColor);
    }

    private String formatEntryTitle(TerminalTargetEntry entry) {
        if (entry.hasStation()) {
            if (entry.stationName() != null && !entry.stationName().isBlank()) {
                return entry.stationName();
            }
            BlockPos3i sp = entry.stationPos();
            String key = entry.stationType() == StationType.SMART_PORT
                    ? "gui.lc2.terminal.unnamed_port"
                    : "gui.lc2.terminal.unnamed_cargo";
            return Component.translatable(key, sp.x(), sp.y(), sp.z()).getString();
        }
        BlockPos3i cp = entry.commPos();
        return Component.translatable("gui.lc2.terminal.comm_coords", cp.x(), cp.y(), cp.z()).getString();
    }

    private void drawCenteredViewportMessage(ScreenCanvas canvas, String message) {
        int maxLineW = 280;
        if (canvas.fontWidth(message) <= maxLineW) {
            int x = 8 + (296 - canvas.fontWidth(message)) / 2;
            canvas.drawClippedString(message, x, 114, maxLineW, COLOR_LCD_MUTED);
            return;
        }
        String first = font.plainSubstrByWidth(message, maxLineW);
        String second = message.substring(first.length()).trim();
        int x1 = 8 + (296 - Math.min(maxLineW, canvas.fontWidth(first))) / 2;
        int x2 = 8 + (296 - Math.min(maxLineW, canvas.fontWidth(second))) / 2;
        canvas.drawClippedString(first, x1, 108, maxLineW, COLOR_LCD_MUTED);
        canvas.drawClippedString(second, x2, 120, maxLineW, COLOR_LCD_MUTED);
    }

    @Override
    protected void paintOverlayTooltips(ScreenCanvas canvas, int mouseX, int mouseY) {
        int relX = mouseX - leftPos;
        int relY = mouseY - topPos;

        // EU/FE header mini-bar tooltip: (186, 4, 126, 11)
        if (relX >= 186 && relX < 312 && relY >= 4 && relY < 15) {
            canvas.drawTooltip(
                    List.of(Component.translatable(
                            "gui.lc2.terminal.energy",
                            Math.round(storedEu),
                            Math.round(maxEu),
                            EnergyUnits.euToFeFloor(storedEu),
                            EnergyUnits.euToFeFloor(maxEu)
                    )),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Unbind button hover tooltips
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= filteredEntries.size()) {
                break;
            }
            int ux = ROW_X + 274;
            int uy = ROW_Y_START + i * ROW_STEP + 5;
            if (relX >= ux && relX < ux + 14 && relY >= uy && relY < uy + 15) {
                canvas.drawTooltip(
                        List.of(Component.translatable("gui.lc2.terminal.unbind_tooltip")),
                        mouseX,
                        mouseY
                );
                return;
            }
        }
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollY) {
        int maxScroll = maxScrollOffset();
        if (maxScroll <= 0) {
            return false;
        }
        int relX = (int) mouseX - leftPos;
        int relY = (int) mouseY - topPos;
        if (relX >= 8 && relX < 314 && relY >= 38 && relY < 204) {
            int delta = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
            int next = Math.clamp(scrollOffset + delta, 0, maxScroll);
            if (next != scrollOffset) {
                scrollOffset = next;
                updateRowButtons();
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScrollOffset() > 0) {
            double relX = mouseX - leftPos;
            double relY = mouseY - topPos;
            if (relX >= SCROLLBAR_X - 2 && relX <= SCROLLBAR_X + SCROLLBAR_W + 2
                    && relY >= SCROLLBAR_Y && relY <= SCROLLBAR_Y + SCROLLBAR_H) {
                draggingScrollbar = true;
                updateScrollFromMouseY(relY);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onMouseRelease(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseDrag(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            updateScrollFromMouseY(mouseY - topPos);
            return true;
        }
        return false;
    }

    private void updateScrollFromMouseY(double relY) {
        int maxScroll = maxScrollOffset();
        if (maxScroll <= 0) {
            scrollOffset = 0;
            return;
        }
        double fraction = Math.clamp((relY - SCROLLBAR_Y - (THUMB_H * 0.5)) / (double) THUMB_TRAVEL, 0.0, 1.0);
        int next = (int) Math.round(fraction * maxScroll);
        if (next != scrollOffset) {
            scrollOffset = next;
            updateRowButtons();
        }
    }
}
