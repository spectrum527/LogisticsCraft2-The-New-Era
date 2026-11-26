package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.network.C2STerminalActionPacket;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Physical client bootstrap registering entity renderers, custom model layers, the Cargo Station
 * roof {@code BlockEntityRenderer}, container screens, and the Operation Terminal hotkey.
 */
public final class Lc2Client {

    public static final KeyMapping OPEN_TERMINAL_KEY = new KeyMapping(
            "key.lc2.open_terminal",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.lc2"
    );

    private Lc2Client() {
    }

    /** Attaches all client-only listeners to the mod and Forge event buses. */
    public static void install(IEventBus modBus) {
        modBus.addListener(Lc2Client::onRegisterLayers);
        modBus.addListener(Lc2Client::onRegisterRenderers);
        modBus.addListener(Lc2Client::onRegisterKeyMappings);
        modBus.addListener(Lc2Client::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(Lc2Client::onClientTick);
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

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TERMINAL_KEY);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(Lc2Registries.SMART_PORT_MENU.get(), SmartPortScreen::new);
            MenuScreens.register(Lc2Registries.CARGO_STATION_MENU.get(), CargoStationScreen::new);
            MenuScreens.register(Lc2Registries.ELECTRIC_CARGO_BOAT_MENU.get(), ContainerScreen::new);
        });
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            while (OPEN_TERMINAL_KEY.consumeClick()) {
                // Discard buffered presses made while a GUI screen was open
            }
            return;
        }
        while (OPEN_TERMINAL_KEY.consumeClick()) {
            Lc2Network.sendToServer(C2STerminalActionPacket.openFromKeybind());
        }
    }
}
