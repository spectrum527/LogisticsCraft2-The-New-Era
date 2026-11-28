package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.forge.network.C2STerminalActionPacket;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.network.S2CTerminalSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only 320x224 Operation Terminal target selection screen bound to the 512x256
 * {@code lc2:textures/gui/operation_terminal.png} atlas, implementing every pixel coordinate
 * in the LC2 v1.2.0 Operation Terminal GUI specification.
 */
public class OperationTerminalScreen extends Screen {

    private static final ResourceLocation TEXTURE = Lc2Coords.id("textures/gui/operation_terminal.png");
    private static final int ATLAS_WIDTH = 512;
    private static final int ATLAS_HEIGHT = 256;

    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 224;

    private static final int VISIBLE_ROWS = 6;
    private static final int ROW_X = 10;
    private static final int ROW_START_Y = 40;
    private static final int ROW_WIDTH = 292;
    private static final int ROW_HEIGHT = 25;
    private static final int ROW_STRIDE = 27;

    private static final int SCROLLBAR_X = 306;
    private static final int SCROLLBAR_Y = 38;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_H = 166;
    private static final int THUMB_H = 24;
    private static final int THUMB_TRAVEL = SCROLLBAR_H - THUMB_H; // 142 (y=38..180)

    private static final int TITLE_COLOR = 0x2C3440;
    private static final int ROW_ONLINE_BG = 0xFF1A2532;
    private static final int ROW_ONLINE_HOVER = 0xFF233648;
    private static final int ROW_ONLINE_BORDER = 0xFF2E4960;
    private static final int ROW_OFFLINE_BG = 0xFF171D26;
    private static final int ROW_OFFLINE_HOVER = 0xFF1F2733;
    private static final int ROW_OFFLINE_BORDER = 0xFF2B3442;

    private static final int COLOR_CYAN = 0x55FFFF;
    private static final int COLOR_GREEN = 0x66FF88;
    private static final int COLOR_AMBER = 0xFFCC44;
    private static final int COLOR_RED = 0xFF6666;
    private static final int COLOR_MUTED = 0x8A9AA8;
    private static final int COLOR_TITLE_LIGHT = 0xE6F2FA;
    private static final int COLOR_SUB_LIGHT = 0x9FB2C2;

    private int leftPos;
    private int topPos;

    private String terminalId;
    private double storedEu;
    private double maxEu;
    private int boundCount;
    private List<TerminalTargetEntry> allEntries = List.of();
    private final List<TerminalTargetEntry> filteredEntries = new ArrayList<>();

    private String searchQuery = "";
    private int scrollOffset = 0;
    private boolean draggingScrollbar = false;

    private EditBox searchBox;

    public OperationTerminalScreen(S2CTerminalSyncPacket initialSync) {
        super(Component.translatable("gui.trera.terminal.title"));
        this.terminalId = initialSync.terminalId();
        this.storedEu = initialSync.storedEu();
        this.maxEu = initialSync.maxEu();
        this.boundCount = initialSync.boundCount();
        this.allEntries = initialSync.entries();
        rebuildFilteredEntries(true);
    }

    /** Applies a live {@link S2CTerminalSyncPacket} refresh from the server. */
    public void applySync(S2CTerminalSyncPacket packet) {
        this.terminalId = packet.terminalId();
        this.storedEu = packet.storedEu();
        this.maxEu = packet.maxEu();
        this.boundCount = packet.boundCount();
        this.allEntries = packet.entries();
        rebuildFilteredEntries(false);
    }

    private void rebuildFilteredEntries(boolean resetScroll) {
        this.filteredEntries.clear();
        for (TerminalTargetEntry entry : this.allEntries) {
            if (matchesClientFilter(entry, this.searchQuery)) {
                this.filteredEntries.add(entry);
            }
        }
        int maxScroll = maxScrollOffset();
        this.scrollOffset = resetScroll ? 0 : Mth.clamp(this.scrollOffset, 0, maxScroll);
    }

    private boolean matchesClientFilter(TerminalTargetEntry entry, String query) {
        if (entry.matchesFilter(query)) {
            return true;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase();
        String localizedTitle = resolveRowTitle(entry).toLowerCase();
        if (localizedTitle.contains(needle)) {
            return true;
        }
        String localizedStatus = Component.translatable(entry.status().translationKey()).getString().toLowerCase();
        return localizedStatus.contains(needle);
    }

    private int maxScrollOffset() {
        return Math.max(0, this.filteredEntries.size() - VISIBLE_ROWS);
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - PANEL_WIDTH) / 2;
        this.topPos = (this.height - PANEL_HEIGHT) / 2;

        // Search EditBox inside recessed well at (8, 20, 236, 14)
        this.searchBox = new EditBox(
                this.font,
                this.leftPos + 11,
                this.topPos + 23,
                230,
                10,
                Component.translatable("gui.trera.terminal.search_placeholder")
        );
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(64);
        this.searchBox.setTextColor(0xE0F2FF);
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setResponder(q -> {
            this.searchQuery = q;
            rebuildFilteredEntries(true);
        });
        addRenderableWidget(this.searchBox);

        // Refresh button at (248, 20, 64, 14)
        addRenderableWidget(new TerminalCompactButton(
                this.leftPos + 248,
                this.topPos + 20,
                64,
                14,
                Component.translatable("gui.trera.terminal.refresh"),
                btn -> Lc2Network.sendToServer(C2STerminalActionPacket.refresh(this.terminalId))
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.searchBox != null) {
            this.searchBox.tick();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers) || this.searchBox.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = maxScrollOffset();
        if (maxScroll > 0 && inRect((int) mouseX, (int) mouseY, this.leftPos + 8, this.topPos + 38, 304, 166)) {
            int step = delta > 0 ? -1 : 1;
            this.scrollOffset = Mth.clamp(this.scrollOffset + step, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int mx = (int) mouseX;
            int my = (int) mouseY;

            // Focus search box when clicking anywhere inside its recessed well (8, 20, 236, 14)
            if (inRect(mx, my, this.leftPos + 8, this.topPos + 20, 236, 14)) {
                setFocused(this.searchBox);
                this.searchBox.setFocus(true);
                this.searchBox.mouseClicked(mouseX, mouseY, button);
                return true;
            } else if (this.searchBox != null && this.searchBox.isFocused()) {
                this.searchBox.setFocus(false);
            }

            // Scrollbar track click/drag at (306, 38, 6, 166)
            if (maxScrollOffset() > 0
                    && inRect(mx, my, this.leftPos + SCROLLBAR_X - 1, this.topPos + SCROLLBAR_Y, SCROLLBAR_W + 2, SCROLLBAR_H)) {
                this.draggingScrollbar = true;
                updateScrollFromMouseY(mouseY);
                return true;
            }

            // Row Connect & Unbind buttons
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int idx = this.scrollOffset + i;
                if (idx >= this.filteredEntries.size()) {
                    break;
                }
                TerminalTargetEntry entry = this.filteredEntries.get(idx);
                int rx = this.leftPos + ROW_X;
                int ry = this.topPos + ROW_START_Y + i * ROW_STRIDE;

                // Connect button: (rx + 222, ry + 5, 48, 15)
                if (entry.isConnectable() && inRect(mx, my, rx + 222, ry + 5, 48, 15)) {
                    Lc2Network.sendToServer(C2STerminalActionPacket.connect(
                            this.terminalId,
                            Lc2Coords.toMc(entry.commPos()),
                            Lc2Coords.toMc(entry.stationPos())
                    ));
                    return true;
                }

                // Unbind button: (rx + 274, ry + 5, 14, 15)
                if (inRect(mx, my, rx + 274, ry + 5, 14, 15)) {
                    Lc2Network.sendToServer(C2STerminalActionPacket.unbind(
                            this.terminalId,
                            entry.dimensionId(),
                            Lc2Coords.toMc(entry.commPos())
                    ));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar && button == 0) {
            updateScrollFromMouseY(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.draggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouseY(double mouseY) {
        int maxScroll = maxScrollOffset();
        if (maxScroll <= 0) {
            this.scrollOffset = 0;
            return;
        }
        double trackTop = this.topPos + SCROLLBAR_Y + THUMB_H * 0.5;
        double ratio = Mth.clamp((mouseY - trackTop) / (double) THUMB_TRAVEL, 0.0, 1.0);
        this.scrollOffset = Mth.clamp((int) Math.round(ratio * maxScroll), 0, maxScroll);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);

        // Main 320x224 panel from 512x256 atlas
        bindAtlas();
        blitAtlas(poseStack, this.leftPos, this.topPos, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        // Header title at (8, 5), maxWidth = 174
        drawFittedText(poseStack, this.title.getString(), this.leftPos + 8, this.topPos + 5, 174, TITLE_COLOR, false);

        // Header EU/FE LCD mini-bar fill at (187, 5, 124, 9) <- UV (328, 30, fillW, 9)
        if (this.maxEu > 0.0 && this.storedEu > 0.0) {
            int fillW = Mth.clamp((int) Math.round(124.0 * this.storedEu / this.maxEu), 1, 124);
            bindAtlas();
            blitAtlas(poseStack, this.leftPos + 187, this.topPos + 5, 328, 30, fillW, 9);
        }
        String energyShort = Component.translatable(
                "gui.trera.terminal.energy_short",
                (long) Math.floor(this.storedEu),
                (long) Math.floor(this.maxEu)
        ).getString();
        drawCenteredFittedText(poseStack, energyShort, this.leftPos + 187, this.topPos + 6, 124, 0xFFFFFFFF, true);

        // Render widgets (search box & refresh button)
        super.render(poseStack, mouseX, mouseY, partialTick);

        // Search placeholder when empty and unfocused
        if (this.searchBox != null && this.searchBox.getValue().isEmpty() && !this.searchBox.isFocused()) {
            String placeholder = Component.translatable("gui.trera.terminal.search_placeholder").getString();
            drawFittedText(poseStack, placeholder, this.leftPos + 11, this.topPos + 23, 228, 0x6E8294, false);
        }

        // Scrollable station rows (6 visible) or empty / no-match message
        if (this.filteredEntries.isEmpty()) {
            Component emptyMsg = Component.translatable(
                    this.allEntries.isEmpty() ? "gui.trera.terminal.empty" : "gui.trera.terminal.no_matches"
            );
            List<FormattedCharSequence> lines = this.font.split(emptyMsg, 272);
            int totalH = lines.size() * 10;
            int startY = this.topPos + 38 + (166 - totalH) / 2;
            for (int i = 0; i < lines.size(); i++) {
                FormattedCharSequence line = lines.get(i);
                int lw = this.font.width(line);
                this.font.draw(poseStack, line, this.leftPos + 8 + (296 - lw) / 2, startY + i * 10, COLOR_MUTED);
            }
        } else {
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int idx = this.scrollOffset + i;
                if (idx >= this.filteredEntries.size()) {
                    break;
                }
                renderRow(poseStack, this.filteredEntries.get(idx), i, mouseX, mouseY);
            }
        }

        // Vertical scrollbar thumb (6x24) at (306, 38..180) <- UV (344, 44, 6, 24)
        int maxScroll = maxScrollOffset();
        int thumbY = this.topPos + SCROLLBAR_Y
                + (maxScroll > 0 ? (int) Math.round(THUMB_TRAVEL * (this.scrollOffset / (double) maxScroll)) : 0);
        bindAtlas();
        blitAtlas(poseStack, this.leftPos + SCROLLBAR_X, thumbY, 344, 44, SCROLLBAR_W, THUMB_H);

        // Footer Status LCD at (8, 207, 304, 12) -> text at (12, 209), maxWidth = 296
        int onlineCount = countOnlineEntries();
        String footerText = Component.translatable("gui.trera.terminal.footer", this.boundCount, onlineCount).getString();
        drawFittedText(poseStack, footerText, this.leftPos + 12, this.topPos + 209, 296, COLOR_CYAN, false);

        // Tooltips (header energy gauge & row unbind buttons)
        renderCustomTooltips(poseStack, mouseX, mouseY);
    }

    private void renderRow(PoseStack poseStack, TerminalTargetEntry entry, int rowIndex, int mouseX, int mouseY) {
        int rx = this.leftPos + ROW_X;
        int ry = this.topPos + ROW_START_Y + rowIndex * ROW_STRIDE;

        boolean online = entry.isConnectable();
        boolean hovered = inRect(mouseX, mouseY, rx, ry, ROW_WIDTH, ROW_HEIGHT);
        int border = online ? ROW_ONLINE_BORDER : ROW_OFFLINE_BORDER;
        int bg = online
                ? (hovered ? ROW_ONLINE_HOVER : ROW_ONLINE_BG)
                : (hovered ? ROW_OFFLINE_HOVER : ROW_OFFLINE_BG);

        fill(poseStack, rx, ry, rx + ROW_WIDTH, ry + ROW_HEIGHT, border);
        fill(poseStack, rx + 1, ry + 1, rx + ROW_WIDTH - 1, ry + ROW_HEIGHT - 1, bg);

        // Station badge 14x14 at (rx + 4, ry + 5)
        int badgeU;
        if (entry.hasStation() && entry.stationType() == StationType.SMART_PORT) {
            badgeU = 328;
        } else if (entry.hasStation() && entry.stationType() == StationType.CARGO_STATION) {
            badgeU = 344;
        } else {
            badgeU = 360;
        }
        bindAtlas();
        blitAtlas(poseStack, rx + 4, ry + 5, badgeU, 0, 14, 14);

        // Status LED 8x8 at (rx + 22, ry + 8)
        int ledU = statusLedU(entry.status());
        blitAtlas(poseStack, rx + 22, ry + 8, ledU, 18, 8, 8);

        // Line 1: title + distance at (rx + 34, ry + 3), maxWidth = 184
        String titleText = resolveRowTitle(entry);
        String distText = entry.distance() >= 0
                ? Component.translatable("gui.trera.terminal.distance", entry.distance()).getString()
                : "";
        int distW = distText.isEmpty() ? 0 : this.font.width(distText);
        int titleMaxW = distText.isEmpty() ? 184 : Math.max(40, 184 - distW - 6);
        drawFittedText(poseStack, titleText, rx + 34, ry + 3, titleMaxW, online ? COLOR_TITLE_LIGHT : COLOR_SUB_LIGHT, false);
        if (!distText.isEmpty()) {
            this.font.draw(poseStack, distText, rx + 34 + 184 - distW, ry + 3, COLOR_CYAN);
        }

        // Line 2: coords + status at (rx + 34, ry + 14), maxWidth = 184
        String coordsText = formatRowCoords(entry);
        String statusText = Component.translatable(entry.status().translationKey()).getString();
        int statusColor = statusTextColor(entry.status());
        String sep = " | ";
        int coordsW = this.font.width(coordsText);
        int sepW = this.font.width(sep);
        int statusW = this.font.width(statusText);
        if (coordsW + sepW + statusW <= 184) {
            int tx = rx + 34;
            this.font.draw(poseStack, coordsText, tx, ry + 14, COLOR_MUTED);
            tx += coordsW;
            this.font.draw(poseStack, sep, tx, ry + 14, 0x56687A);
            tx += sepW;
            this.font.draw(poseStack, statusText, tx, ry + 14, statusColor);
        } else {
            drawFittedText(poseStack, coordsText + sep + statusText, rx + 34, ry + 14, 184, statusColor, false);
        }

        // Connect button at (rx + 222, ry + 5, 48, 15)
        boolean connectHover = online && inRect(mouseX, mouseY, rx + 222, ry + 5, 48, 15);
        renderBeveledButton(
                poseStack,
                rx + 222,
                ry + 5,
                48,
                15,
                online,
                connectHover,
                Component.translatable("gui.trera.terminal.connect").getString()
        );

        // Unbind button at (rx + 274, ry + 5, 14, 15) with 10x10 X icon at (rx + 276, ry + 7)
        boolean unbindHover = inRect(mouseX, mouseY, rx + 274, ry + 5, 14, 15);
        renderBeveledFrame(poseStack, rx + 274, ry + 5, 14, 15, true, unbindHover);
        bindAtlas();
        blitAtlas(poseStack, rx + 276, ry + 7, 328, unbindHover ? 56 : 44, 10, 10);
    }

    private void renderCustomTooltips(PoseStack poseStack, int mouseX, int mouseY) {
        // Header EU/FE mini-bar frame at (186, 4, 126, 11)
        if (inRect(mouseX, mouseY, this.leftPos + 186, this.topPos + 4, 126, 11)) {
            renderTooltip(
                    poseStack,
                    Component.translatable(
                            "gui.trera.terminal.energy",
                            (long) Math.floor(this.storedEu),
                            (long) Math.floor(this.maxEu),
                            EnergyUnits.euToFeFloor(this.storedEu),
                            EnergyUnits.euToFeFloor(this.maxEu)
                    ),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Row Unbind button hover tooltip
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = this.scrollOffset + i;
            if (idx >= this.filteredEntries.size()) {
                break;
            }
            int rx = this.leftPos + ROW_X;
            int ry = this.topPos + ROW_START_Y + i * ROW_STRIDE;
            if (inRect(mouseX, mouseY, rx + 274, ry + 5, 14, 15)) {
                renderTooltip(poseStack, Component.translatable("gui.trera.terminal.unbind_tooltip"), mouseX, mouseY);
                return;
            }
        }
    }

    private String resolveRowTitle(TerminalTargetEntry entry) {
        if (entry.hasStation()) {
            if (!entry.stationName().isBlank()) {
                return entry.stationName();
            }
            String key = entry.stationType() == StationType.SMART_PORT
                    ? "gui.trera.terminal.unnamed_port"
                    : "gui.trera.terminal.unnamed_cargo";
            return Component.translatable(
                    key,
                    entry.stationPos().x(),
                    entry.stationPos().y(),
                    entry.stationPos().z()
            ).getString();
        }
        return Component.translatable(
                "gui.trera.terminal.comm_coords",
                entry.commPos().x(),
                entry.commPos().y(),
                entry.commPos().z()
        ).getString();
    }

    private String formatRowCoords(TerminalTargetEntry entry) {
        if (entry.hasStation()) {
            return Component.translatable(
                    "gui.trera.terminal.station_coords",
                    entry.stationPos().x(),
                    entry.stationPos().y(),
                    entry.stationPos().z()
            ).getString();
        }
        if (entry.status() == CommStationStatus.WRONG_DIMENSION) {
            return entry.dimensionId();
        }
        return Component.translatable(
                "gui.trera.terminal.comm_coords",
                entry.commPos().x(),
                entry.commPos().y(),
                entry.commPos().z()
        ).getString();
    }

    private int countOnlineEntries() {
        int online = 0;
        for (TerminalTargetEntry entry : this.allEntries) {
            if (entry.isConnectable()) {
                online++;
            }
        }
        return online;
    }

    private static int statusLedU(CommStationStatus status) {
        return switch (status) {
            case ONLINE -> 328;
            case UNPOWERED, NO_ADJACENT_STATION -> 338;
            case CHUNK_UNLOADED, WRONG_DIMENSION -> 348;
            case COMM_MISSING -> 358;
        };
    }

    private static int statusTextColor(CommStationStatus status) {
        return switch (status) {
            case ONLINE -> COLOR_GREEN;
            case UNPOWERED, NO_ADJACENT_STATION -> COLOR_AMBER;
            case CHUNK_UNLOADED, WRONG_DIMENSION -> COLOR_MUTED;
            case COMM_MISSING -> COLOR_RED;
        };
    }

    private static void bindAtlas() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, TEXTURE);
    }

    private static void blitAtlas(PoseStack poseStack, int x, int y, int u, int v, int w, int h) {
        blit(poseStack, x, y, (float) u, (float) v, w, h, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    private void renderBeveledFrame(PoseStack poseStack, int x, int y, int w, int h, boolean active, boolean hovered) {
        int bg = !active ? 0xFF4A525C : (hovered ? 0xFF3E6586 : 0xFF2D4963);
        int hi = !active ? 0xFF66707C : (hovered ? 0xFF7CB6E0 : 0xFF537B9E);
        int lo = 0xFF1A2530;
        int border = 0xFF101820;

        fill(poseStack, x, y, x + w, y + h, border);
        fill(poseStack, x + 1, y + 1, x + w - 1, y + h - 1, bg);
        fill(poseStack, x + 1, y + 1, x + w - 1, y + 2, hi);
        fill(poseStack, x + 1, y + 1, x + 2, y + h - 1, hi);
        fill(poseStack, x + 1, y + h - 2, x + w - 1, y + h - 1, lo);
        fill(poseStack, x + w - 2, y + 1, x + w - 1, y + h - 1, lo);
    }

    private void renderBeveledButton(
            PoseStack poseStack,
            int x,
            int y,
            int w,
            int h,
            boolean active,
            boolean hovered,
            String label
    ) {
        renderBeveledFrame(poseStack, x, y, w, h, active, hovered);
        int textColor = active ? 0xFFFFFFFF : 0xFF8C96A0;
        drawCenteredFittedText(poseStack, label, x + 2, y + (h - 8) / 2, w - 4, textColor, true);
    }

    private void drawFittedText(
            PoseStack poseStack,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            boolean shadow
    ) {
        int rawW = this.font.width(text);
        if (rawW <= maxWidth) {
            if (shadow) {
                this.font.drawShadow(poseStack, text, x, y, color);
            } else {
                this.font.draw(poseStack, text, x, y, color);
            }
            return;
        }
        float scale = Math.max(0.75F, (float) maxWidth / (float) rawW);
        int scaledMaxW = (int) Math.floor(maxWidth / scale);
        String display = trimToWidth(text, scaledMaxW);
        float yOffset = (8.0F - 8.0F * scale) * 0.5F;
        poseStack.pushPose();
        poseStack.translate(x, y + yOffset, 0.0D);
        poseStack.scale(scale, scale, 1.0F);
        if (shadow) {
            this.font.drawShadow(poseStack, display, 0.0F, 0.0F, color);
        } else {
            this.font.draw(poseStack, display, 0.0F, 0.0F, color);
        }
        poseStack.popPose();
    }

    private void drawCenteredFittedText(
            PoseStack poseStack,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            boolean shadow
    ) {
        int rawW = this.font.width(text);
        if (rawW <= maxWidth) {
            int tx = x + (maxWidth - rawW) / 2;
            if (shadow) {
                this.font.drawShadow(poseStack, text, tx, y, color);
            } else {
                this.font.draw(poseStack, text, tx, y, color);
            }
            return;
        }
        float scale = Math.max(0.65F, (float) maxWidth / (float) rawW);
        int scaledMax = (int) Math.floor(maxWidth / scale);
        String display = rawW > scaledMax ? this.font.plainSubstrByWidth(text, scaledMax) : text;
        int dispW = this.font.width(display);
        float drawW = dispW * scale;
        float yOffset = (8.0F - 8.0F * scale) * 0.5F;
        poseStack.pushPose();
        poseStack.translate(x + (maxWidth - drawW) * 0.5F, y + yOffset, 0.0D);
        poseStack.scale(scale, scale, 1.0F);
        if (shadow) {
            this.font.drawShadow(poseStack, display, 0.0F, 0.0F, color);
        } else {
            this.font.draw(poseStack, display, 0.0F, 0.0F, color);
        }
        poseStack.popPose();
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - 6)) + "...";
    }

    private static boolean inRect(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    /** Compact IC2-beveled button matching {@link AbstractStationScreen.CompactButton}. */
    private static final class TerminalCompactButton extends Button {

        private TerminalCompactButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress);
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int bg = !this.active ? 0xFF707070 : (this.isHoveredOrFocused() ? 0xFF98A8BC : 0xFF8B8B8B);
            int hi = this.isHoveredOrFocused() ? 0xFFE6F0FA : 0xFFD8D8D8;
            int lo = 0xFF3E3E3E;
            int border = 0xFF222222;

            fill(poseStack, this.x, this.y, this.x + this.width, this.y + this.height, border);
            fill(poseStack, this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1, bg);
            fill(poseStack, this.x + 1, this.y + 1, this.x + this.width - 1, this.y + 2, hi);
            fill(poseStack, this.x + 1, this.y + 1, this.x + 2, this.y + this.height - 1, hi);
            fill(poseStack, this.x + 1, this.y + this.height - 2, this.x + this.width - 1, this.y + this.height - 1, lo);
            fill(poseStack, this.x + this.width - 2, this.y + 1, this.x + this.width - 1, this.y + this.height - 1, lo);

            int textColor = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
            String label = getMessage().getString();
            int maxTextW = Math.max(4, this.width - 4);
            int rawW = mc.font.width(label);
            if (rawW <= maxTextW) {
                int tx = this.x + (this.width - rawW) / 2;
                int ty = this.y + (this.height - 8) / 2;
                mc.font.drawShadow(poseStack, label, tx, ty, textColor);
            } else {
                float scale = Math.max(0.65F, (float) maxTextW / (float) rawW);
                int scaledMax = (int) Math.floor(maxTextW / scale);
                if (rawW > scaledMax) {
                    label = mc.font.plainSubstrByWidth(label, scaledMax);
                    rawW = mc.font.width(label);
                }
                float drawW = rawW * scale;
                float drawH = 8.0F * scale;
                float tx = this.x + (this.width - drawW) * 0.5F;
                float ty = this.y + (this.height - drawH) * 0.5F;
                poseStack.pushPose();
                poseStack.translate(tx, ty, 0.0D);
                poseStack.scale(scale, scale, 1.0F);
                mc.font.drawShadow(poseStack, label, 0.0F, 0.0F, textColor);
                poseStack.popPose();
            }
        }
    }
}
