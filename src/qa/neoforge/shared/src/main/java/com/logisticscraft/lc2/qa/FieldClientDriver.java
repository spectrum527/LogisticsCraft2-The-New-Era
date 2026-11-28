package com.logisticscraft.lc2.qa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side QA driver ({@code lc2.qa.clientDriver=true}) for Phase 3 real graphical
 * Client ↔ Dedicated Server live testing, sprite/texture audits, GUI packet interaction,
 * real boat riding verification, and 1280x720 PNG screenshot capture.
 */
public final class FieldClientDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldClientDriver.class);

    private static final List<String> REQUIRED_BLOCK_SPRITES = List.of(
            "trera:block/smart_port_bottom",
            "trera:block/smart_port_front",
            "trera:block/smart_port_front_active",
            "trera:block/smart_port_side",
            "trera:block/smart_port_top",
            "trera:block/cargo_station_bottom",
            "trera:block/cargo_station_side",
            "trera:block/cargo_station_side_active",
            "trera:block/cargo_station_top",
            "trera:block/cargo_station_top_active"
    );

    private static final List<String> REQUIRED_ITEM_SPRITES = List.of(
            "trera:item/electric_cargo_boat",
            "trera:item/transport_drone",
            "trera:item/heavy_transport_drone",
            "trera:item/rf_chip_1",
            "trera:item/rf_chip_2",
            "trera:item/rf_chip_3",
            "trera:item/chunk_loading_upgrade"
    );

    private static final List<String> REQUIRED_RESOURCES = List.of(
            "trera:textures/gui/smart_port.png",
            "trera:textures/gui/cargo_station.png",
            "trera:textures/entity/boat/electric_cargo_boat.png",
            "trera:textures/entity/drone/transport_drone.png",
            "trera:textures/entity/drone/heavy_transport_drone.png"
    );

    private final Path input = Path.of("qa-input.txt");
    private final Path output = Path.of("qa-output.json");

    private String lastOverlay = "";
    private int overlayCount = 0;
    private String lastChat = "";
    private int chatCount = 0;
    private String lastInteractResult = "";

    public static void install() {
        if (!Boolean.getBoolean("lc2.qa.clientDriver")) {
            return;
        }
        FieldClientDriver driver = new FieldClientDriver();
        NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.System.class, event -> {
            String msg = event.getMessage().getString();
            if (event.isOverlay()) {
                driver.lastOverlay = msg;
                driver.overlayCount++;
            } else {
                driver.lastChat = msg;
                driver.chatCount++;
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> driver.tick());
        LOGGER.info("[LC2-CLIENT-QA] FieldClientDriver installed");
    }

    private void tick() {
        if (!Files.isRegularFile(input)) {
            return;
        }
        JsonObject result = new JsonObject();
        try {
            JsonObject req = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
            Files.delete(input);
            result.addProperty("seq", req.get("seq").getAsInt());
            String action = req.get("action").getAsString();
            Minecraft mc = Minecraft.getInstance();

            act(mc, action, req, result);
            recordState(mc, result);
        } catch (Throwable t) {
            LOGGER.error("[LC2-CLIENT-QA] Action error", t);
            result.addProperty("error", t.toString());
        }
        try {
            Path tmp = output.resolveSibling("qa-output.tmp");
            Files.writeString(tmp, result.toString());
            Files.move(tmp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void act(Minecraft mc, String action, JsonObject req, JsonObject result) {
        switch (action) {
            case "state" -> {
            }
            case "connect" -> {
                int port = Integer.getInteger("lc2.qa.clientPort", 25565);
                String address = "127.0.0.1:" + port;
                ConnectScreen.startConnecting(
                        new TitleScreen(),
                        mc,
                        ServerAddress.parseString(address),
                        new ServerData("LC2 Field Server", address, ServerData.Type.OTHER),
                        false,
                        null
                );
            }
            case "check_sprites" -> checkSprites(mc, result);
            case "clear_hud" -> QaClientPlatform.clearHud(mc);
            case "hide_gui" -> QaClientPlatform.setHideGui(mc, req.get("value").getAsBoolean());
            case "camera" -> {
                String mode = req.get("mode").getAsString();
                mc.options.setCameraType("third_back".equals(mode)
                        ? CameraType.THIRD_PERSON_BACK
                        : CameraType.FIRST_PERSON);
            }
            case "look" -> {
                if (mc.player != null) {
                    float yaw = req.get("yaw").getAsFloat();
                    float pitch = req.get("pitch").getAsFloat();
                    mc.player.setYRot(yaw);
                    mc.player.yRotO = yaw;
                    mc.player.setYHeadRot(yaw);
                    mc.player.setXRot(pitch);
                    mc.player.xRotO = pitch;
                }
            }
            case "command" -> {
                if (mc.getConnection() == null) {
                    throw new IllegalStateException("Not connected to server");
                }
                mc.getConnection().sendCommand(req.get("cmd").getAsString());
            }
            case "right_click_block" -> {
                BlockPos pos = new BlockPos(req.get("x").getAsInt(), req.get("y").getAsInt(), req.get("z").getAsInt());
                Direction face = Direction.byName(req.get("face").getAsString());
                if (face == null) {
                    face = Direction.UP;
                }
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                var res = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                lastInteractResult = res.toString();
            }
            case "gui_rename" -> Lc2ScreenProbe.renameAndSave(
                    QaClientPlatform.currentScreen(mc), req.get("name").getAsString());
            case "gui_click" -> Lc2ScreenProbe.clickButton(
                    QaClientPlatform.currentScreen(mc), req.get("button").getAsString());
            case "gui_set_coords" -> Lc2ScreenProbe.setCoordsAndApply(
                    QaClientPlatform.currentScreen(mc),
                    req.get("x").getAsInt(), req.get("y").getAsInt(), req.get("z").getAsInt());
            case "close_screen" -> {
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
                QaClientPlatform.setScreen(mc, null);
            }
            case "interact_boat" -> {
                ElectricCargoBoatEntity boat = findNearestBoat(mc);
                if (boat == null) {
                    throw new IllegalStateException("No ElectricCargoBoatEntity nearby within 10 blocks");
                }
                var res = QaClientPlatform.interactEntity(mc, boat);
                lastInteractResult = res.toString();
            }
            case "screenshot" -> {
                String name = req.get("name").getAsString();
                QaClientPlatform.grabScreenshot(mc, name);
            }
            case "quit" -> mc.stop();
            default -> throw new IllegalArgumentException("Unknown client action: " + action);
        }
    }

    private void checkSprites(Minecraft mc, JsonObject out) {
        JsonArray missing = new JsonArray();
        for (String s : REQUIRED_BLOCK_SPRITES) {
            if (!QaClientPlatform.hasBlockSprite(mc, s)) {
                missing.add("block:" + s);
            }
        }
        for (String s : REQUIRED_ITEM_SPRITES) {
            if (!QaClientPlatform.hasItemSprite(mc, s)) {
                missing.add("item:" + s);
            }
        }
        for (String r : REQUIRED_RESOURCES) {
            if (!QaClientPlatform.hasResource(mc, r)) {
                missing.add("resource:" + r);
            }
        }
        out.addProperty("spritesChecked",
                REQUIRED_BLOCK_SPRITES.size() + REQUIRED_ITEM_SPRITES.size() + REQUIRED_RESOURCES.size());
        out.addProperty("spritesOk", missing.isEmpty());
        out.add("missingSprites", missing);
    }

    private void recordState(Minecraft mc, JsonObject out) {
        Screen screen = QaClientPlatform.currentScreen(mc);
        out.addProperty("world", mc.level != null);
        out.addProperty("overlay", QaClientPlatform.hasOverlay(mc));
        out.addProperty("screen", screen == null ? "" : screen.getClass().getSimpleName());
        out.addProperty("screenTitle", screen == null ? "" : screen.getTitle().getString());
        out.addProperty("lastOverlay", lastOverlay);
        out.addProperty("overlayCount", overlayCount);
        out.addProperty("lastChat", lastChat);
        out.addProperty("chatCount", chatCount);
        out.addProperty("lastInteractResult", lastInteractResult);

        if (mc.player != null) {
            out.addProperty("playerX", mc.player.getX());
            out.addProperty("playerY", mc.player.getY());
            out.addProperty("playerZ", mc.player.getZ());
            out.addProperty("playerYaw", mc.player.getYRot());
            out.addProperty("playerPitch", mc.player.getXRot());
            out.addProperty("vehicle", QaClientPlatform.entityId(mc.player.getVehicle()));
            out.addProperty("menuId", mc.player.containerMenu != null ? mc.player.containerMenu.containerId : 0);
        }
        if (mc.level != null && mc.player != null) {
            ElectricCargoBoatEntity boat = findNearestBoat(mc);
            out.addProperty("boatNearby", boat != null);
            if (boat != null) {
                out.addProperty("boatAutonomous", boat.isAutonomousTransit());
                out.addProperty("boatX", boat.getX());
                out.addProperty("boatY", boat.getY());
                out.addProperty("boatZ", boat.getZ());
                out.addProperty("boatPassengers", boat.getPassengers().size());
            }
            List<CargoDroneEntity> drones = mc.level.getEntitiesOfClass(
                    CargoDroneEntity.class,
                    mc.player.getBoundingBox().inflate(64)
            );
            out.addProperty("nearbyFlyingDrones", drones.size());
        }
        if (screen != null) {
            Lc2ScreenProbe.recordScreenState(screen, out);
        }
    }

    private static ElectricCargoBoatEntity findNearestBoat(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        List<ElectricCargoBoatEntity> boats = mc.level.getEntitiesOfClass(
                ElectricCargoBoatEntity.class,
                mc.player.getBoundingBox().inflate(10.0)
        );
        return boats.stream()
                .min(Comparator.comparingDouble(b -> b.distanceToSqr(mc.player)))
                .orElse(null);
    }
}
