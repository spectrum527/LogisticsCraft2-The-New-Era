package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge 1.21.1 non-container {@link Screen} bridge wrapping {@link GuiGraphics} behind
 * {@link ScreenCanvas} for the Operation Terminal Selection GUI.
 */
public abstract class TerminalScreenBase extends Screen {

    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    protected final int panelWidth;
    protected final int panelHeight;
    protected int leftPos;
    protected int topPos;
    private final List<EditBox> editBoxes = new ArrayList<>();

    protected TerminalScreenBase(Component title, int panelWidth, int panelHeight) {
        super(title);
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.panelWidth) / 2;
        this.topPos = (this.height - this.panelHeight) / 2;
        this.editBoxes.clear();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected final EditBox addTrackedEditBox(EditBox box) {
        editBoxes.add(box);
        return box;
    }

    /** Creates an interactive button rendering a 512x256 icon sprite with separate normal/hover UV. */
    protected final Button createHoverIconButton(
            int panelX,
            int panelY,
            int width,
            int height,
            Component narrationTitle,
            String guiName,
            int iconOffsetX,
            int iconOffsetY,
            int normalU,
            int normalV,
            int hoverU,
            int hoverV,
            int iconW,
            int iconH,
            Button.OnPress onPress
    ) {
        return new Button(
                leftPos + panelX,
                topPos + panelY,
                width,
                height,
                Component.empty(),
                onPress,
                defaultSupplier -> narrationTitle.copy()
        ) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(g, mouseX, mouseY, partialTick);
                boolean hovered = isHoveredOrFocused() && active;
                g.blit(
                        guiTexture(guiName),
                        getX() + iconOffsetX,
                        getY() + iconOffsetY,
                        (float) (hovered ? hoverU : normalU),
                        (float) (hovered ? hoverV : normalV),
                        iconW,
                        iconH,
                        512,
                        256
                );
            }
        };
    }

    protected abstract void paintPanel(ScreenCanvas canvas, int mouseX, int mouseY);

    protected abstract void paintOverlayTooltips(ScreenCanvas canvas, int mouseX, int mouseY);

    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean onMouseRelease(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean onMouseDrag(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollY) {
        return false;
    }

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
                g.blit(guiTexture(guiName), leftPos + x, topPos + y, (float) u, (float) v, width, height, 512, 256);
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int argbColor) {
                g.fill(leftPos + x1, topPos + y1, leftPos + x2, topPos + y2, argbColor);
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
            public int fontWidth(String text) {
                return text == null ? 0 : font.width(text);
            }

            @Override
            public void drawTooltip(List<Component> lines, int mouseX, int mouseY) {
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        };
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        paintPanel(wrapCanvas(g), mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        paintOverlayTooltips(wrapCanvas(g), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (onMouseClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (onMouseRelease(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (onMouseDrag(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (onMouseScroll(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
