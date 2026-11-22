package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge 26.x container screen bridge wrapping {@link GuiGraphicsExtractor} behind
 * {@link ScreenCanvas} and protecting focused {@link EditBox} fields from inventory-close keybinds.
 */
public abstract class LogisticsScreenBase<M extends AbstractContainerMenu>
        extends AbstractContainerScreen<M> {

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private final List<EditBox> editBoxes = new ArrayList<>();

    protected LogisticsScreenBase(
            M menu,
            Inventory playerInventory,
            Component title,
            int panelWidth,
            int panelHeight
    ) {
        super(menu, playerInventory, title, panelWidth, panelHeight);
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

    private static Identifier guiTexture(String name) {
        return TEXTURES.computeIfAbsent(
                name,
                n -> Identifier.fromNamespaceAndPath(
                        LogisticsConstants.MOD_ID,
                        "textures/gui/" + n + ".png"
                )
        );
    }

    private ScreenCanvas wrapCanvas(GuiGraphicsExtractor g) {
        return new ScreenCanvas() {
            @Override
            public void blit(String guiName, int x, int y, int u, int v, int width, int height) {
                if (width <= 0 || height <= 0) {
                    return;
                }
                g.blit(
                        RenderPipelines.GUI_TEXTURED,
                        guiTexture(guiName),
                        leftPos + x,
                        topPos + y,
                        (float) u,
                        (float) v,
                        width,
                        height,
                        256,
                        256
                );
            }

            @Override
            public void drawString(Component text, int x, int y, int argbColor) {
                g.text(font, text, leftPos + x, topPos + y, argbColor, false);
            }

            @Override
            public void drawClippedString(String text, int x, int y, int maxWidth, int argbColor) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                String rendered = font.plainSubstrByWidth(text, maxWidth);
                g.text(font, rendered, leftPos + x, topPos + y, argbColor, false);
            }

            @Override
            public void drawTooltip(List<Component> lines, int mouseX, int mouseY) {
                g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        paintPanel(wrapCanvas(g), mouseX, mouseY);
    }

    protected Component headerTitle() {
        return title;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, headerTitle(), titleLabelX, titleLabelY, 0xFF2C3440, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF2C3440, false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractTooltip(g, mouseX, mouseY);
        paintOverlayTooltips(wrapCanvas(g), mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        for (EditBox box : editBoxes) {
            if (box.isFocused()) {
                if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                    box.setFocused(false);
                    return super.keyPressed(event);
                }
                return box.keyPressed(event) || box.canConsumeInput();
            }
        }
        return super.keyPressed(event);
    }
}
