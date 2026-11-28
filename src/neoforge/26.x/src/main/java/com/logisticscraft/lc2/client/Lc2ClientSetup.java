package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.network.TerminalActionPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.object.boat.BoatModel;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge 26.x client registration for screens, keybinds, model layers, entity renderers, and BERs.
 */
public final class Lc2ClientSetup {

    private static final KeyMapping.Category LC2_KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "main")
    );

    public static final KeyMapping OPEN_TERMINAL_KEY = new KeyMapping(
            "key.trera.open_terminal",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            LC2_KEY_CATEGORY
    );

    // BoatRenderer maps layer "trera:boat/electric_cargo_boat" -> "trera:textures/entity/boat/electric_cargo_boat.png"
    private static final ModelLayerLocation ELECTRIC_CARGO_BOAT_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "boat/electric_cargo_boat"),
            "main"
    );

    private Lc2ClientSetup() {
    }

    /** Installs client-only mod and game event bus listeners on 26.x. */
    public static void install(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(LC2_KEY_CATEGORY);
            event.register(OPEN_TERMINAL_KEY);
        });

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_TERMINAL_KEY.consumeClick()) {
                if (mc.player != null && Lc2ScreenAccess.currentScreen(mc) == null) {
                    Lc2Native.sendToServer(TerminalActionPayload.openFromKeybind());
                }
            }
        });

        modBus.addListener(RegisterMenuScreensEvent.class, event -> {
            event.register(Lc2Registries.SMART_PORT_MENU.get(), SmartPortScreen::new);
            event.register(Lc2Registries.CARGO_STATION_MENU.get(), CargoStationScreen::new);
            event.register(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), ContainerScreen::new);
        });

        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions.class, event -> {
            // Bind 128x128 ChestBoatModel layer definition to electric_cargo_boat
            event.registerLayerDefinition(ELECTRIC_CARGO_BOAT_LAYER, BoatModel::createChestBoatModel);
            event.registerLayerDefinition(
                    CargoDroneModel.LAYER_LOCATION,
                    CargoDroneModelGeometry::createLayerDefinition
            );
        });

        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event -> {
            event.registerEntityRenderer(
                    Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                    context -> new BoatRenderer(context, ELECTRIC_CARGO_BOAT_LAYER)
            );
            event.registerEntityRenderer(
                    Lc2Registries.CARGO_DRONE_ENTITY.get(),
                    CargoDroneRenderer::new
            );
            event.registerBlockEntityRenderer(
                    Lc2Registries.CARGO_STATION_BE.get(),
                    CargoStationRenderer::new
            );
        });
    }
}
