package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.object.boat.BoatModel;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * NeoForge 26.x client registration for screens, model layers, entity renderers, and BERs.
 */
public final class Lc2ClientSetup {

    // BoatRenderer maps layer "lc2:boat/electric_cargo_boat" -> "lc2:textures/entity/boat/electric_cargo_boat.png"
    private static final ModelLayerLocation ELECTRIC_CARGO_BOAT_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "boat/electric_cargo_boat"),
            "main"
    );

    private Lc2ClientSetup() {
    }

    /** Installs client-only mod event bus listeners on 26.x. */
    public static void install(IEventBus modBus) {
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
