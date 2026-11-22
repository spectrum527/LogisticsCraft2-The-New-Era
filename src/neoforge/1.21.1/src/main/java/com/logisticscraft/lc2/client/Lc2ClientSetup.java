package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.model.ListModel;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.vehicle.Boat;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * NeoForge 1.21.1 client registration for screens, model layers, entity renderers, and BERs.
 */
public final class Lc2ClientSetup {

    private static final ResourceLocation BOAT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "textures/entity/boat/electric_cargo_boat.png"
    );

    private Lc2ClientSetup() {
    }

    /** Installs client-only mod event bus listeners on 1.21.1. */
    public static void install(IEventBus modBus) {
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
