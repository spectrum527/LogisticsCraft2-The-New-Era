package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.network.C2SStationActionPacket;
import com.logisticscraft.lc2.forge.network.C2STerminalActionPacket;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * Base 392x234 IC2-styled dual-zone station screen (512x256 texture atlas) shared by
 * {@link SmartPortScreen} and {@link CargoStationScreen}, implementing every pixel coordinate
 * in {@code docs/GUI_LAYOUT.md}.
 */
public abstract class AbstractStationScreen<T extends AbstractStationMenu> extends AbstractContainerScreen<T> {

    protected static final int ATLAS_WIDTH = 512;
    protected static final int ATLAS_HEIGHT = 256;

    private static final int TITLE_COLOR = 0x2C3440;
    private static final int OUTBOUND_LABEL_COLOR = 0x7A4200;
    private static final int STORAGE_LABEL_COLOR = 0x004D66;
    private static final int LCD_TEXT_CYAN = 0x55FFFF;
    private static final int LCD_TEXT_GREEN = 0x66FF88;
    private static final int LCD_TEXT_AMBER = 0xFFCC44;
    private static final int LCD_TEXT_RED = 0xFF6666;
    private static final int LCD_TEXT_MUTED = 0x8A9AA8;

    protected final ResourceLocation texture;

    private EditBox nameBox;
    private EditBox targetXBox;
    private EditBox targetYBox;
    private EditBox targetZBox;
    private Button autoReturnBtn;
    private Button energyPriorityBtn;

    @Nullable
    protected S2CStationSyncPacket sync;
    private boolean initializedFromSync = false;

    private int remoteBadgeX = 0;
    private int remoteBadgeY = 2;
    private int remoteBadgeW = 0;
    private int remoteBadgeH = 10;

    protected AbstractStationScreen(
            T menu,
            Inventory playerInventory,
            Component title,
            ResourceLocation texture
    ) {
        super(menu, playerInventory, title);
        this.texture = texture;
        this.imageWidth = 392;
        this.imageHeight = 234;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelX = 99;
        this.inventoryLabelY = 144;
    }

    protected static void blitAtlas(PoseStack poseStack, int x, int y, int u, int v, int w, int h) {
        blit(poseStack, x, y, (float) u, (float) v, w, h, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();

        // Row 1 (y=13): Station Name EditBox (8,13,150,12), Save (162,13,44,12),
        // Auto-Return (210,13,66,12), Energy Priority (279,13,68,12)
        this.nameBox = new EditBox(
                this.font,
                this.leftPos + 8,
                this.topPos + 13,
                150,
                12,
                Component.translatable("gui.lc2.station_name")
        );
        this.nameBox.setMaxLength(32);
        this.nameBox.setBordered(true);
        addRenderableWidget(this.nameBox);

        addRenderableWidget(new CompactButton(
                this.leftPos + 162,
                this.topPos + 13,
                44,
                12,
                Component.translatable("gui.lc2.save"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.setName(
                        this.menu.getStationPos(),
                        this.nameBox.getValue()
                ))
        ));

        this.autoReturnBtn = addRenderableWidget(new CompactButton(
                this.leftPos + 210,
                this.topPos + 13,
                66,
                12,
                Component.translatable("gui.lc2.auto_return.off"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.TOGGLE_AUTO_RETURN
                ))
        ));

        this.energyPriorityBtn = addRenderableWidget(new CompactButton(
                this.leftPos + 279,
                this.topPos + 13,
                68,
                12,
                Component.translatable(EnergyPriority.EU_FIRST.translationKey()),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.TOGGLE_ENERGY_PRIORITY
                ))
        ));

        // Row 2 (y=27): Prev '<' (8,27,14,12), Target LCD (23,27,256,12),
        // Next '>' (280,27,14,12), Launch (297,27,50,12)
        addRenderableWidget(new CompactButton(
                this.leftPos + 8,
                this.topPos + 27,
                14,
                12,
                Component.literal("<"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.cycleTarget(this.menu.getStationPos(), -1))
        ));
        addRenderableWidget(new CompactButton(
                this.leftPos + 280,
                this.topPos + 27,
                14,
                12,
                Component.literal(">"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.cycleTarget(this.menu.getStationPos(), +1))
        ));
        addRenderableWidget(new CompactButton(
                this.leftPos + 297,
                this.topPos + 27,
                50,
                12,
                Component.translatable("gui.lc2.dispatch"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.DISPATCH
                ))
        ));

        // Row 3 (y=41): Manual X (8,41,44,12), Y (54,41,36,12), Z (92,41,44,12),
        // Set (138,41,36,12), Status LCD (176,41,171,12)
        this.targetXBox = createCoordBox(this.leftPos + 8, this.topPos + 41, 44);
        this.targetYBox = createCoordBox(this.leftPos + 54, this.topPos + 41, 36);
        this.targetZBox = createCoordBox(this.leftPos + 92, this.topPos + 41, 44);
        addRenderableWidget(this.targetXBox);
        addRenderableWidget(this.targetYBox);
        addRenderableWidget(this.targetZBox);

        addRenderableWidget(new CompactButton(
                this.leftPos + 138,
                this.topPos + 41,
                36,
                12,
                Component.translatable("gui.lc2.set_target"),
                btn -> applyManualCoords()
        ));

        // Center 2-Zone Swap Button at (172, 104, 16, 20) rendering baked 12x10 sprite at UV (414, 64, 12, 10)
        addRenderableWidget(new SwapZonesButton(
                this.leftPos + 172,
                this.topPos + 104,
                16,
                20,
                this.texture,
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.SWAP_CARGO_ZONES
                ))
        ));

        // Remote mode: compact "Back to List" button at (leftPos + 279, topPos + 1, 68, 11)
        if (this.menu.isRemoteMode()) {
            addRenderableWidget(new CompactButton(
                    this.leftPos + 279,
                    this.topPos + 1,
                    68,
                    11,
                    Component.translatable("gui.lc2.back_to_terminal"),
                    btn -> Lc2Network.sendToServer(
                            C2STerminalActionPacket.returnToList(this.menu.getRemoteTerminalId())
                    )
            ));
        }

        // Apply any sync already buffered in the menu and request fresh sync
        if (this.menu.getLatestSync() != null) {
            onStationSync(this.menu.getLatestSync());
        }
        Lc2Network.sendToServer(C2SStationActionPacket.simple(
                this.menu.getStationPos(),
                C2SStationActionPacket.Action.REQUEST_SYNC
        ));
    }

    private EditBox createCoordBox(int x, int y, int width) {
        EditBox box = new EditBox(this.font, x, y, width, 12, Component.translatable("gui.lc2.coords"));
        box.setMaxLength(8);
        box.setBordered(true);
        box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d+"));
        return box;
    }

    private void applyManualCoords() {
        String sx = this.targetXBox.getValue().trim();
        String sy = this.targetYBox.getValue().trim();
        String sz = this.targetZBox.getValue().trim();
        if (sx.isEmpty() && sy.isEmpty() && sz.isEmpty()) {
            Lc2Network.sendToServer(C2SStationActionPacket.setTarget(this.menu.getStationPos(), false, 0, 0, 0));
            return;
        }
        try {
            int x = Integer.parseInt(sx);
            int y = Integer.parseInt(sy);
            int z = Integer.parseInt(sz);
            Lc2Network.sendToServer(C2SStationActionPacket.setTarget(this.menu.getStationPos(), true, x, y, z));
        } catch (NumberFormatException ignored) {
            // Leave boxes unchanged until valid integer coordinates are provided
        }
    }

    /** Called by {@link Lc2ClientHandlers} whenever an updated {@link S2CStationSyncPacket} arrives. */
    public void onStationSync(S2CStationSyncPacket packet) {
        if (!this.menu.getStationPos().equals(packet.stationPos())) {
            return;
        }
        boolean targetChanged = this.sync == null
                || this.sync.hasTarget() != packet.hasTarget()
                || !this.sync.targetPos().equals(packet.targetPos());

        this.sync = packet;

        if (this.nameBox != null && (!this.initializedFromSync || !this.nameBox.isFocused())) {
            if (!this.nameBox.getValue().equals(packet.stationName())) {
                this.nameBox.setValue(packet.stationName());
            }
        }
        if (this.targetXBox != null && (!this.initializedFromSync || (targetChanged && !anyCoordBoxFocused()))) {
            if (packet.hasTarget()) {
                this.targetXBox.setValue(Integer.toString(packet.targetPos().getX()));
                this.targetYBox.setValue(Integer.toString(packet.targetPos().getY()));
                this.targetZBox.setValue(Integer.toString(packet.targetPos().getZ()));
            } else {
                this.targetXBox.setValue("");
                this.targetYBox.setValue("");
                this.targetZBox.setValue("");
            }
        }
        if (this.autoReturnBtn != null) {
            this.autoReturnBtn.setMessage(Component.translatable(
                    packet.autoReturn() ? "gui.lc2.auto_return.on" : "gui.lc2.auto_return.off"
            ));
        }
        if (this.energyPriorityBtn != null) {
            this.energyPriorityBtn.setMessage(Component.translatable(packet.energyPriority().translationKey()));
        }
        this.initializedFromSync = true;
    }

    private boolean anyCoordBoxFocused() {
        return (this.targetXBox != null && this.targetXBox.isFocused())
                || (this.targetYBox != null && this.targetYBox.isFocused())
                || (this.targetZBox != null && this.targetZBox.isFocused());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (this.nameBox != null) {
            this.nameBox.tick();
        }
        if (this.targetXBox != null) {
            this.targetXBox.tick();
            this.targetYBox.tick();
            this.targetZBox.tick();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        EditBox focused = focusedEditBox();
        if (focused != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                focused.setFocus(false);
                this.setFocused(null);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (focused == this.nameBox) {
                    Lc2Network.sendToServer(C2SStationActionPacket.setName(
                            this.menu.getStationPos(),
                            this.nameBox.getValue()
                    ));
                } else {
                    applyManualCoords();
                }
                focused.setFocus(false);
                this.setFocused(null);
                return true;
            }
            if (focused.keyPressed(keyCode, scanCode, modifiers) || focused.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Nullable
    private EditBox focusedEditBox() {
        if (this.nameBox != null && this.nameBox.isFocused()) {
            return this.nameBox;
        }
        if (this.targetXBox != null && this.targetXBox.isFocused()) {
            return this.targetXBox;
        }
        if (this.targetYBox != null && this.targetYBox.isFocused()) {
            return this.targetYBox;
        }
        if (this.targetZBox != null && this.targetZBox.isFocused()) {
            return this.targetZBox;
        }
        return null;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderTooltip(poseStack, mouseX, mouseY);
        renderCustomTooltips(poseStack, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, this.texture);

        // Main 392x234 panel from 512x256 atlas
        blitAtlas(poseStack, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        double eu = this.sync != null ? this.sync.storedEu() : 0.0;
        double maxEu = this.sync != null ? this.sync.maxEu() : LogisticsConstants.BASE_ENERGY_CAPACITY_EU;

        // EU Gauge Fill bottom-up: (363, 14, 16, 40) <- UV (396, 0, 16, 40)
        if (maxEu > 0.0 && eu > 0.0) {
            int h = Math.min(40, Math.max(1, (int) Math.round(40.0 * eu / maxEu)));
            blitAtlas(poseStack, this.leftPos + 363, this.topPos + 14 + 40 - h, 396, 40 - h, 16, h);
        }

        // EU Bolt Indicator: (366, 57, 10, 14) <- UV (414, 0, 10, 14)
        if (eu > 0.0) {
            blitAtlas(poseStack, this.leftPos + 366, this.topPos + 57, 414, 0, 10, 14);
        }

        // Chunk-Loader Active Badge: (363, 180, 16, 16) <- UV (396, 64, 16, 16)
        if (this.sync != null && this.sync.chunkLoaderActive()) {
            blitAtlas(poseStack, this.leftPos + 363, this.topPos + 180, 396, 64, 16, 16);
        }

        // Vehicle / Port Status Badge: (363, 200, 16, 16) <- Active (396, 44) / Warn (414, 44)
        if (this.sync != null) {
            boolean ready = isStatusBadgeHealthy(this.sync);
            blitAtlas(poseStack, this.leftPos + 363, this.topPos + 200, ready ? 396 : 414, 44, 16, 16);
        }

        renderExtraBg(poseStack, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        Component headerTitle = (this.sync != null && !this.sync.stationName().isBlank())
                ? Component.literal(this.sync.stationName())
                : this.title;
        if (this.menu.isRemoteMode()) {
            String badgeText = Component.translatable("gui.lc2.remote_readonly").getString();
            int badgeW = Math.min(110, this.font.width(badgeText) + 8);
            int maxTitleW = Math.max(40, 274 - this.titleLabelX - badgeW - 6);
            String titleStr = trimToWidth(headerTitle.getString(), maxTitleW);
            this.font.draw(poseStack, titleStr, this.titleLabelX, this.titleLabelY, TITLE_COLOR);

            int titleW = this.font.width(titleStr);
            this.remoteBadgeX = this.titleLabelX + titleW + 6;
            this.remoteBadgeY = 2;
            this.remoteBadgeW = badgeW;
            this.remoteBadgeH = 10;

            fill(poseStack, remoteBadgeX, remoteBadgeY, remoteBadgeX + remoteBadgeW, remoteBadgeY + remoteBadgeH, 0xFF38D9FF);
            fill(poseStack, remoteBadgeX + 1, remoteBadgeY + 1, remoteBadgeX + remoteBadgeW - 1, remoteBadgeY + remoteBadgeH - 1, 0xFF1A2D40);
            drawFittedLcdText(poseStack, badgeText, remoteBadgeX + 4, remoteBadgeY + 1, remoteBadgeW - 8, LCD_TEXT_CYAN);
        } else {
            this.remoteBadgeW = 0;
            this.font.draw(poseStack, headerTitle, this.titleLabelX, this.titleLabelY, TITLE_COLOR);
        }
        this.font.draw(poseStack, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TITLE_COLOR);

        // 2-Zone Cargo Headers: Outbound at (8, 77), Storage at (190, 77)
        this.font.draw(poseStack, Component.translatable("gui.lc2.zone.outbound"), 8, 77, OUTBOUND_LABEL_COLOR);
        this.font.draw(poseStack, Component.translatable("gui.lc2.zone.storage"), 190, 77, STORAGE_LABEL_COLOR);

        // Target Readout LCD at (23, 27, 256, 12) -> text at (26, 29), maxWidth=250
        String targetLine = formatTargetReadout();
        int targetColor = targetReadoutColor();
        drawFittedLcdText(poseStack, targetLine, 26, 29, 250, targetColor);

        // Status Readout LCD at (176, 41, 171, 12) -> text at (179, 43), maxWidth=165
        String statusKey = this.sync != null ? this.sync.statusKey() : "status.lc2.ready";
        String statusText = Component.translatable(statusKey).getString();
        int statusColor = statusKey.contains(".failure.") ? LCD_TEXT_RED : LCD_TEXT_GREEN;
        drawFittedLcdText(poseStack, statusText, 179, 43, 165, statusColor);

        renderExtraLabels(poseStack, mouseX, mouseY);
    }

    private void renderCustomTooltips(PoseStack poseStack, int mouseX, int mouseY) {
        int relX = mouseX - this.leftPos;
        int relY = mouseY - this.topPos;

        // Remote read-only badge tooltip
        if (this.menu.isRemoteMode()
                && this.remoteBadgeW > 0
                && inRect(relX, relY, this.remoteBadgeX, this.remoteBadgeY, this.remoteBadgeW, this.remoteBadgeH)) {
            renderTooltip(poseStack, Component.translatable("gui.lc2.remote_readonly.tooltip"), mouseX, mouseY);
            return;
        }

        // Center Swap Button tooltip (172, 104, 16, 20)
        if (inRect(relX, relY, 172, 104, 16, 20)) {
            renderTooltip(poseStack, Component.translatable("tooltip.lc2.swap"), mouseX, mouseY);
            return;
        }

        // EU Gauge tooltip (362, 13, 18, 42)
        if (inRect(relX, relY, 362, 13, 18, 42) && this.sync != null) {
            renderTooltip(
                    poseStack,
                    Component.translatable(
                            "gui.lc2.eu_stored",
                            (long) Math.floor(this.sync.storedEu()),
                            (long) Math.floor(this.sync.maxEu()),
                            this.sync.inputTier(),
                            this.sync.packetLimit()
                    ),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Chunk loader badge tooltip (363, 180, 16, 16)
        if (inRect(relX, relY, 363, 180, 16, 16) && this.sync != null) {
            renderTooltip(
                    poseStack,
                    Component.translatable(
                            this.sync.chunkLoaderActive()
                                    ? "gui.lc2.chunk_loader_active"
                                    : "gui.lc2.chunk_loader_inactive"
                    ),
                    mouseX,
                    mouseY
            );
            return;
        }

        // Empty slot tooltips
        if (this.menu.getCarried().isEmpty() && this.hoveredSlot != null && !this.hoveredSlot.hasItem()) {
            Component slotTip = emptySlotTooltip(this.hoveredSlot.index);
            if (slotTip != null) {
                renderTooltip(poseStack, slotTip, mouseX, mouseY);
            }
        }
    }

    @Nullable
    protected S2CStationSyncPacket.TargetSummary findSelectedTargetSummary() {
        if (this.sync == null || !this.sync.hasTarget()) {
            return null;
        }
        BlockPos target = this.sync.targetPos();
        for (S2CStationSyncPacket.TargetSummary summary : this.sync.targets()) {
            if (summary.pos().equals(target)) {
                return summary;
            }
        }
        return null;
    }

    protected void drawFittedLcdText(PoseStack poseStack, String text, int x, int y, int maxWidth, int color) {
        int rawW = this.font.width(text);
        if (rawW <= maxWidth) {
            this.font.draw(poseStack, text, x, y, color);
            return;
        }
        float scale = Math.max(0.75F, (float) maxWidth / (float) rawW);
        int scaledMaxW = (int) Math.floor(maxWidth / scale);
        String display = trimToWidth(text, scaledMaxW);
        float yOffset = (8.0F - 8.0F * scale) * 0.5F;
        poseStack.pushPose();
        poseStack.translate(x, y + yOffset, 0.0D);
        poseStack.scale(scale, scale, 1.0F);
        this.font.draw(poseStack, display, 0, 0, color);
        poseStack.popPose();
    }

    protected String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - 6)) + "...";
    }

    protected static boolean inRect(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    protected abstract String formatTargetReadout();

    protected abstract int targetReadoutColor();

    protected abstract boolean isStatusBadgeHealthy(S2CStationSyncPacket sync);

    @Nullable
    protected abstract Component emptySlotTooltip(int slotIndex);

    protected void renderExtraBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
    }

    protected void renderExtraLabels(PoseStack poseStack, int mouseX, int mouseY) {
    }

    protected static int colorCyan() {
        return LCD_TEXT_CYAN;
    }

    protected static int colorGreen() {
        return LCD_TEXT_GREEN;
    }

    protected static int colorAmber() {
        return LCD_TEXT_AMBER;
    }

    protected static int colorRed() {
        return LCD_TEXT_RED;
    }

    protected static int colorMuted() {
        return LCD_TEXT_MUTED;
    }

    /**
     * Crisp 12px IC2-beveled compact button matching {@code docs/GUI_LAYOUT.md} control rows.
     */
    protected static final class CompactButton extends Button {

        public CompactButton(int x, int y, int width, int height, Component message, OnPress onPress) {
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

    /**
     * Center 16x20 IC2-beveled Swap button rendering the baked 12x10 dual-color swap arrows
     * sprite from UV {@code (414, 64, 12, 10)} in the 512x256 station atlas.
     */
    protected static final class SwapZonesButton extends Button {

        private final ResourceLocation atlasTexture;

        public SwapZonesButton(
                int x,
                int y,
                int width,
                int height,
                ResourceLocation atlasTexture,
                OnPress onPress
        ) {
            super(x, y, width, height, Component.translatable("gui.lc2.swap.title"), onPress);
            this.atlasTexture = atlasTexture;
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
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

            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderTexture(0, this.atlasTexture);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            blitAtlas(poseStack, this.x + (this.width - 12) / 2, this.y + (this.height - 10) / 2, 414, 64, 12, 10);
        }
    }
}
