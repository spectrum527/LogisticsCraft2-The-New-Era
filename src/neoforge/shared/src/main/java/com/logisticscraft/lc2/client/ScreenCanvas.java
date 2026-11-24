package com.logisticscraft.lc2.client;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral 2D drawing surface bridged to 1.21.1 {@code GuiGraphics} and 26.x
 * {@code GuiGraphicsExtractor} for LC2 station screens.
 */
public interface ScreenCanvas {

    /** Draws a textured rectangle from {@code assets/lc2/textures/gui/<guiName>.png} (512x256). */
    void blit(String guiName, int x, int y, int u, int v, int width, int height);

    /** Fills a solid ARGB rectangle at panel-local {@code [x1, y1) .. [x2, y2)}. */
    void fill(int x1, int y1, int x2, int y2, int argbColor);

    /** Draws a 1px hollow ARGB rectangle outline at panel-local {@code (x, y, width, height)}. */
    default void fillOutline(int x, int y, int width, int height, int argbColor) {
        if (width <= 0 || height <= 0) {
            return;
        }
        fill(x, y, x + width, y + 1, argbColor);
        fill(x, y + height - 1, x + width, y + height, argbColor);
        fill(x, y, x + 1, y + height, argbColor);
        fill(x + width - 1, y, x + width, y + height, argbColor);
    }

    /** Draws a string at panel-local {@code (x, y)} with no drop shadow. */
    void drawString(Component text, int x, int y, int argbColor);

    /** Draws a single-line string clipped cleanly to {@code maxWidth} pixels. */
    void drawClippedString(String text, int x, int y, int maxWidth, int argbColor);

    /** Measures the pixel width of {@code text} using the screen's font. */
    int fontWidth(String text);

    /** Renders a hover tooltip at screen coordinates {@code (mouseX, mouseY)}. */
    void drawTooltip(List<Component> lines, int mouseX, int mouseY);
}
