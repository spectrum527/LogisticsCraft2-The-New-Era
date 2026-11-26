package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.network.TerminalActionPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.model.ListModel;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.vehicle.Boat;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge 1.21.1 client registration for screens, keybinds, model layers, entity renderers, and BERs.
 */
public final class Lc2ClientSetup {

    public static final KeyMapping OPEN_TERMINAL_KEY = new KeyMapping(
            "key.lc2.open_terminal",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.lc2"
    );

    private static final ResourceLocation BOAT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "textures/entity/boat/electric_cargo_boat.png"
    );

    private Lc2ClientSetup() {
    }

    /** Installs client-only mod and game event bus listeners on 1.21.1. */
    public static void install(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_TERMINAL_KEY));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_TERMINAL_KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null) {
                    Lc2Native.sendToServer(TerminalActionPayload.openFromKeybind());
                }
            }
        });

        modBus.addListener(RegisterMenuScreensEvent.class, event -> {
            event.register(Lc2Registries.SMART_PORT_MENU.get(), SmartPortScreen::new);
            event.register(Lc2Registries.CARGO_STATION_MENU.get(), CargoStationScreen::new);
            event.register(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), ContainerScreen::new);
        });

        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions.class, event ->
                event.registerLayerDefinition(
                        CargoDroneModel.LAYER_LOCATION,
                        CargoDroneModelGeometry::createLayerDefinition
                )
        );

        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event -> {
            // Bind ChestBoatModel (hasChest = true, 128x128 UV) to electric_cargo_boat.png
            event.registerEntityRenderer(
                    Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                    context -> new BoatRenderer(context, true) {
                        @Override
                        public Pair<ResourceLocation, ListModel<Boat>> getModelWithLocation(Boat boat) {
                            return Pair.of(BOAT_TEXTURE, super.getModelWithLocation(boat).getSecond());
                        }
                    }
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
