package com.logisticscraft.lc2.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

/** NeoForge 1.21.1 client-only QA helpers. */
public final class QaClientPlatform {

    private QaClientPlatform() {
    }

    public static Screen currentScreen(Minecraft mc) {
        return mc.screen;
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static boolean hasOverlay(Minecraft mc) {
        return mc.getOverlay() != null;
    }

    public static void clearHud(Minecraft mc) {
        mc.gui.getChat().clearMessages(false);
        mc.getToasts().clear();
    }

    public static void setHideGui(Minecraft mc, boolean hide) {
        mc.options.hideGui = hide;
    }

    public static void grabScreenshot(Minecraft mc, String fileName) {
        Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), message -> {});
    }

    public static void pressButton(Button button) {
        button.onPress();
    }

    public static InteractionResult interactEntity(Minecraft mc, Entity entity) {
        return mc.gameMode.interact(mc.player, entity, InteractionHand.MAIN_HAND);
    }

    public static boolean hasBlockSprite(Minecraft mc, String spriteId) {
        ResourceLocation key = ResourceLocation.parse(spriteId);
        return mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(key)
                .contents()
                .name()
                .equals(key);
    }

    public static boolean hasItemSprite(Minecraft mc, String spriteId) {
        return hasBlockSprite(mc, spriteId);
    }

    public static boolean hasResource(Minecraft mc, String resourceId) {
        return mc.getResourceManager().getResource(ResourceLocation.parse(resourceId)).isPresent();
    }

    public static String entityId(Entity entity) {
        return entity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
