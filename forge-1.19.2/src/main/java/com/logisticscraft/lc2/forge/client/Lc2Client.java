package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.Lc2Registries;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Physical client bootstrap registering entity renderers, custom model layers, the Cargo Station
 * roof {@code BlockEntityRenderer}, and container screens.
 */
public final class Lc2Client {

    private Lc2Client() {
    }

    /** Attaches all client-only listeners to the mod event bus. */
    public static void install(IEventBus modBus) {
        modBus.addListener(Lc2Client::onRegisterLayers);
        modBus.addListener(Lc2Client::onRegisterRenderers);
        modBus.addListener(Lc2Client::onClientSetup);
    }

    private static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CargoDroneModel.LAYER_LOCATION, CargoDroneModel::createBodyLayer);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                ElectricCargoBoatRenderer::new
        );
        event.registerEntityRenderer(
                Lc2Registries.CARGO_DRONE_ENTITY.get(),
                CargoDroneRenderer::new
        );
        event.registerBlockEntityRenderer(
                Lc2Registries.CARGO_STATION_BE.get(),
                CargoStationRenderer::new
        );
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(Lc2Registries.SMART_PORT_MENU.get(), SmartPortScreen::new);
            MenuScreens.register(Lc2Registries.CARGO_STATION_MENU.get(), CargoStationScreen::new);
            MenuScreens.register(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), ContainerScreen::new);
        });
    }
}
