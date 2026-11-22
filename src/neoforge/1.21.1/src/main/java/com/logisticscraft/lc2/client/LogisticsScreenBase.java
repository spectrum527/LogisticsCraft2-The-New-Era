package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge 1.21.1 container screen bridge wrapping {@link GuiGraphics} behind {@link ScreenCanvas}
 * and protecting focused {@link EditBox} fields from inventory-close keybinds.
 */
public abstract class LogisticsScreenBase<M extends AbstractContainerMenu>
        extends AbstractContainerScreen<M> {

    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private final List<EditBox> editBoxes = new ArrayList<>();

    protected LogisticsScreenBase(
            M menu,
            Inventory playerInventory,
            Component title,
            int panelWidth,
            int panelHeight
    ) {
        super(menu, playerInventory, title);
        this.imageWidth = panelWidth;
        this.imageHeight = panelHeight;
    }

    protected final void clearEditBoxes() {
        editBoxes.clear();
    }

    protected final EditBox addTrackedEditBox(EditBox box) {
        editBoxes.add(box);
        return box;
    }

    protected abstract void paintPanel(ScreenCanvas canvas, int mouseX, int mouseY);

    protected abstract void paintOverlayTooltips(ScreenCanvas canvas, int mouseX, int mouseY);

    private static ResourceLocation guiTexture(String name) {
        return TEXTURES.computeIfAbsent(
                name,
                n -> ResourceLocation.fromNamespaceAndPath(
                        LogisticsConstants.MOD_ID,
                        "textures/gui/" + n + ".png"
                )
        );
    }

    private ScreenCanvas wrapCanvas(GuiGraphics g) {
        return new ScreenCanvas() {
            @Override
            public void blit(String guiName, int x, int y, int u, int v, int width, int height) {
                if (width <= 0 || height <= 0) {
                    return;
                }
                g.blit(guiTexture(guiName), leftPos + x, topPos + y, (float) u, (float) v, width, height, 256, 256);
            }

            @Override
            public void drawString(Component text, int x, int y, int argbColor) {
                g.drawString(font, text, leftPos + x, topPos + y, argbColor, false);
            }

            @Override
            public void drawClippedString(String text, int x, int y, int maxWidth, int argbColor) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                String rendered = font.plainSubstrByWidth(text, maxWidth);
                g.drawString(font, rendered, leftPos + x, topPos + y, argbColor, false);
            }

            @Override
            public void drawTooltip(List<Component> lines, int mouseX, int mouseY) {
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        };
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        paintPanel(wrapCanvas(g), mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xFF2C3440, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF2C3440, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        paintOverlayTooltips(wrapCanvas(g), mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox box : editBoxes) {
            if (box.isFocused()) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    box.setFocused(false);
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }
                return box.keyPressed(keyCode, scanCode, modifiers) || box.canConsumeInput();
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
