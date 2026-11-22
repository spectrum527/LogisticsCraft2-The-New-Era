package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.network.C2SStationActionPacket;
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
 * Base 212x222 IC2-styled station screen shared by {@link SmartPortScreen} and
 * {@link CargoStationScreen}, implementing every pixel coordinate in {@code docs/GUI_LAYOUT.md}.
 */
public abstract class AbstractStationScreen<T extends AbstractStationMenu> extends AbstractContainerScreen<T> {

    private static final int TITLE_COLOR = 0x2C3440;
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

    protected AbstractStationScreen(
            T menu,
            Inventory playerInventory,
            Component title,
            ResourceLocation texture
    ) {
        super(menu, playerInventory, title);
        this.texture = texture;
        this.imageWidth = 212;
        this.imageHeight = 222;
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 133;
    }

    @Override
    protected void init() {
        super.init();

        // Station Name EditBox (8, 15, 112, 12) + Save Button (122, 15, 48, 12)
        this.nameBox = new EditBox(
                this.font,
                this.leftPos + 8,
                this.topPos + 15,
                112,
                12,
                Component.translatable("gui.lc2.station_name")
        );
        this.nameBox.setMaxLength(32);
        this.nameBox.setBordered(true);
        addRenderableWidget(this.nameBox);

        addRenderableWidget(new CompactButton(
                this.leftPos + 122,
                this.topPos + 15,
                48,
                12,
                Component.translatable("gui.lc2.save"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.setName(
                        this.menu.getStationPos(),
                        this.nameBox.getValue()
                ))
        ));

        // Prev Target '<' (8, 28, 14, 12) & Next Target '>' (156, 28, 14, 12)
        addRenderableWidget(new CompactButton(
                this.leftPos + 8,
                this.topPos + 28,
                14,
                12,
                Component.literal("<"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.cycleTarget(this.menu.getStationPos(), -1))
        ));
        addRenderableWidget(new CompactButton(
                this.leftPos + 156,
                this.topPos + 28,
                14,
                12,
                Component.literal(">"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.cycleTarget(this.menu.getStationPos(), +1))
        ));

        // Manual X/Y/Z EditBoxes (8,41,38,12), (48,41,32,12), (82,41,38,12) + Set Button (122,41,48,12)
        this.targetXBox = createCoordBox(this.leftPos + 8, this.topPos + 41, 38);
        this.targetYBox = createCoordBox(this.leftPos + 48, this.topPos + 41, 32);
        this.targetZBox = createCoordBox(this.leftPos + 82, this.topPos + 41, 38);
        addRenderableWidget(this.targetXBox);
        addRenderableWidget(this.targetYBox);
        addRenderableWidget(this.targetZBox);

        addRenderableWidget(new CompactButton(
                this.leftPos + 122,
                this.topPos + 41,
                48,
                12,
                Component.translatable("gui.lc2.set_target"),
                btn -> applyManualCoords()
        ));

        // Auto-Return Toggle (8, 54, 54, 12), Priority Toggle (64, 54, 56, 12), Dispatch (122, 54, 48, 12)
        this.autoReturnBtn = addRenderableWidget(new CompactButton(
                this.leftPos + 8,
                this.topPos + 54,
                54,
                12,
                Component.translatable("gui.lc2.auto_return.off"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.TOGGLE_AUTO_RETURN
                ))
        ));
        this.energyPriorityBtn = addRenderableWidget(new CompactButton(
                this.leftPos + 64,
                this.topPos + 54,
                56,
                12,
                Component.translatable(EnergyPriority.EU_FIRST.translationKey()),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.TOGGLE_ENERGY_PRIORITY
                ))
        ));
        addRenderableWidget(new CompactButton(
                this.leftPos + 122,
                this.topPos + 54,
                48,
                12,
                Component.translatable("gui.lc2.dispatch"),
                btn -> Lc2Network.sendToServer(C2SStationActionPacket.simple(
                        this.menu.getStationPos(),
                        C2SStationActionPacket.Action.DISPATCH
                ))
        ));

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

        // Main 212x222 panel
        blit(poseStack, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        double eu = this.sync != null ? this.sync.storedEu() : 0.0;
        double maxEu = this.sync != null ? this.sync.maxEu() : LogisticsConstants.BASE_ENERGY_CAPACITY_EU;

        // EU Gauge Fill bottom-up: (186, 17, 16, 40) <- UV (216, 0, 16, 40)
        if (maxEu > 0.0 && eu > 0.0) {
            int h = Math.min(40, Math.max(1, (int) Math.round(40.0 * eu / maxEu)));
            blit(poseStack, this.leftPos + 186, this.topPos + 17 + 40 - h, 216, 40 - h, 16, h);
        }

        // EU Bolt Indicator: (189, 60, 10, 14) <- UV (234, 0, 10, 14)
        if (eu > 0.0) {
            blit(poseStack, this.leftPos + 189, this.topPos + 60, 234, 0, 10, 14);
        }

        // Chunk-Loader Active Badge: (186, 180, 16, 16) <- UV (216, 64, 16, 16)
        if (this.sync != null && this.sync.chunkLoaderActive()) {
            blit(poseStack, this.leftPos + 186, this.topPos + 180, 216, 64, 16, 16);
        }

        // Vehicle / Port Status Badge: (186, 199, 16, 16) <- Active (216, 44) / Warn (234, 44)
        if (this.sync != null) {
            boolean ready = isStatusBadgeHealthy(this.sync);
            blit(poseStack, this.leftPos + 186, this.topPos + 199, ready ? 216 : 234, 44, 16, 16);
        }

        renderExtraBg(poseStack, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        this.font.draw(poseStack, this.title, this.titleLabelX, this.titleLabelY, TITLE_COLOR);
        this.font.draw(poseStack, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TITLE_COLOR);

        // Target Readout LCD at (23, 28, 132, 12) -> text at (26, 30)
        String targetLine = formatTargetReadout();
        int targetColor = targetReadoutColor();
        this.font.draw(poseStack, trimToWidth(targetLine, 126), 26, 30, targetColor);

        // Status Readout LCD at (7, 67, 164, 10) -> text at (10, 68)
        String statusKey = this.sync != null ? this.sync.statusKey() : "status.lc2.ready";
        String statusText = Component.translatable(statusKey).getString();
        int statusColor = statusKey.contains(".failure.") ? LCD_TEXT_RED : LCD_TEXT_GREEN;
        this.font.draw(poseStack, trimToWidth(statusText, 158), 10, 68, statusColor);

        renderExtraLabels(poseStack, mouseX, mouseY);
    }

    private void renderCustomTooltips(PoseStack poseStack, int mouseX, int mouseY) {
        int relX = mouseX - this.leftPos;
        int relY = mouseY - this.topPos;

        // EU Gauge tooltip (185, 16, 18, 42)
        if (inRect(relX, relY, 185, 16, 18, 42) && this.sync != null) {
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

        // Chunk loader badge tooltip (186, 180, 16, 16)
        if (inRect(relX, relY, 186, 180, 16, 16) && this.sync != null) {
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
            int maxTextW = this.width - 4;
            if (mc.font.width(label) > maxTextW) {
                label = mc.font.plainSubstrByWidth(label, maxTextW);
            }
            int tx = this.x + (this.width - mc.font.width(label)) / 2;
            int ty = this.y + (this.height - 8) / 2;
            mc.font.drawShadow(poseStack, label, tx, ty, textColor);
        }
    }
}
