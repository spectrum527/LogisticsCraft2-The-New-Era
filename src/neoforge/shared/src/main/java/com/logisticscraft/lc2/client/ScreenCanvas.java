package com.logisticscraft.lc2.client;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral 2D drawing surface bridged to 1.21.1 {@code GuiGraphics} and 26.x
 * {@code GuiGraphicsExtractor} for LC2 station screens.
 */
public interface ScreenCanvas {

    /** Draws a textured rectangle from {@code assets/lc2/textures/gui/<guiName>.png} (256x256). */
    void blit(String guiName, int x, int y, int u, int v, int width, int height);

    /** Draws a string at panel-local {@code (x, y)} with no drop shadow. */
    void drawString(Component text, int x, int y, int argbColor);

    /** Draws a single-line string clipped cleanly to {@code maxWidth} pixels. */
    void drawClippedString(String text, int x, int y, int maxWidth, int argbColor);

    /** Renders a hover tooltip at screen coordinates {@code (mouseX, mouseY)}. */
    void drawTooltip(List<Component> lines, int mouseX, int mouseY);
}
