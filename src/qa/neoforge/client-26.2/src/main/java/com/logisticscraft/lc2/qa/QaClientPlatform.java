package com.logisticscraft.lc2.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

/** NeoForge 26.2 client-only QA helpers (using MC 26.2 {@code mc.gui} screen/HUD manager). */
public final class QaClientPlatform {

    private static boolean hudHidden = false;

    private QaClientPlatform() {
    }

    public static Screen currentScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    public static boolean hasOverlay(Minecraft mc) {
        return mc.gui.overlay() != null;
    }

    public static void clearHud(Minecraft mc) {
        mc.gui.hud.getChat().clearMessages(false);
        mc.gui.toastManager().clear();
    }

    public static void setHideGui(Minecraft mc, boolean hide) {
        if (hudHidden != hide) {
            mc.gui.hud.toggle();
            hudHidden = hide;
        }
    }

    public static void grabScreenshot(Minecraft mc, String fileName) {
        Screenshot.grab(mc.gameDirectory, fileName, mc.gameRenderer.mainRenderTarget(), 1, message -> {});
    }

    public static void pressButton(Button button) {
        button.onPress(new KeyEvent(0, 0, 0));
    }

    public static InteractionResult interactEntity(Minecraft mc, Entity entity) {
        return mc.gameMode.interact(mc.player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
    }

    public static boolean hasBlockSprite(Minecraft mc, String spriteId) {
        Identifier key = Identifier.parse(spriteId);
        return mc.getAtlasManager()
                .getAtlasOrThrow(AtlasIds.BLOCKS)
                .getSprite(key)
                .contents()
                .name()
                .equals(key);
    }

    public static boolean hasItemSprite(Minecraft mc, String spriteId) {
        Identifier key = Identifier.parse(spriteId);
        boolean inItems = mc.getAtlasManager()
                .getAtlasOrThrow(AtlasIds.ITEMS)
                .getSprite(key)
                .contents()
                .name()
                .equals(key);
        return inItems || hasBlockSprite(mc, spriteId);
    }

    public static boolean hasResource(Minecraft mc, String resourceId) {
        return mc.getResourceManager().getResource(Identifier.parse(resourceId)).isPresent();
    }

    public static String entityId(Entity entity) {
        return entity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
