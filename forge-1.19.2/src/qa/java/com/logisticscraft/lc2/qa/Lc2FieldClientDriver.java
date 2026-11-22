package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.forge.client.CargoStationScreen;
import com.logisticscraft.lc2.forge.client.SmartPortScreen;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.network.C2SStationActionPacket;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Physical Forge 1.19.2 graphical client driver for {@code runFieldTest}.
 * Auto-connects to the real Forge 1.19.2 dedicated server over TCP {@code 127.0.0.1:<port>},
 * audits textures/models, exercises live {@code SmartPortScreen} and {@code CargoStationScreen}
 * packets, tests real player boat riding & blocked active-transit boarding, and captures PNG screenshots.
 */
final class Lc2FieldClientDriver {

    private static final List<String> REQUIRED_BLOCK_SPRITES = List.of(
            "lc2:block/cargo_station_bottom",
            "lc2:block/cargo_station_side",
            "lc2:block/cargo_station_side_active",
            "lc2:block/cargo_station_top",
            "lc2:block/cargo_station_top_active",
            "lc2:block/smart_port_bottom",
            "lc2:block/smart_port_front",
            "lc2:block/smart_port_front_active",
            "lc2:block/smart_port_side",
            "lc2:block/smart_port_top"
    );

    private static final List<String> REQUIRED_ITEM_SPRITES = List.of(
            "lc2:item/chunk_loading_upgrade",
            "lc2:item/electric_cargo_boat",
            "lc2:item/heavy_transport_drone",
            "lc2:item/rf_chip_1",
            "lc2:item/rf_chip_2",
            "lc2:item/rf_chip_3",
            "lc2:item/transport_drone"
    );

    private static final List<String> REQUIRED_RESOURCES = List.of(
            "lc2:textures/gui/smart_port.png",
            "lc2:textures/gui/cargo_station.png",
            "lc2:textures/entity/boat/electric_cargo_boat.png",
            "lc2:textures/entity/drone/transport_drone.png",
            "lc2:textures/entity/drone/heavy_transport_drone.png"
    );

    private static volatile Lc2FieldClientDriver instance;

    private final int port;
    private final Path screenshotsDir;
    private final Lc2QaAssert qa = new Lc2QaAssert("field_client");
    private final Queue<Lc2FieldNetwork.S2CFieldDirective> pendingDirectives = new ArrayDeque<>();
    private final Map<String, String> capturedScreenshots = new LinkedHashMap<>();

    private boolean connectStarted = false;
    private boolean shuttingDown = false;
    private int shutdownDelay = 0;

    // Active multi-tick directive state machine
    private Lc2FieldNetwork.S2CFieldDirective activeDirective = null;
    private int directiveSubStep = 0;
    private int directiveTicks = 0;
    private String guiOriginalName = "";
    private BlockPos guiOriginalTarget = null;
    private boolean guiOriginalAutoReturn = false;
    private EnergyPriority guiOriginalPriority = EnergyPriority.EU_FIRST;
    private boolean guiScreenshotTaken = false;

    // Pending framebuffer screenshot captured at RenderTickEvent.Phase.END
    private volatile String pendingScreenshotName = null;
    private volatile boolean screenshotDone = false;
    private volatile String screenshotError = null;

    private Lc2FieldClientDriver(int port, Path screenshotsDir) {
        this.port = port;
        this.screenshotsDir = screenshotsDir;
    }

    static void install() {
        int port = Integer.getInteger("lc2.field.port", 25565);
        String dirStr = System.getProperty("lc2.field.screenshotsDir", "field-screenshots");
        Lc2FieldClientDriver driver = new Lc2FieldClientDriver(port, Path.of(dirStr));
        instance = driver;
        MinecraftForge.EVENT_BUS.register(driver);
        Lc2QaMod.LOGGER.info("[LC2-FIELD-CLIENT] Installed client driver (target=127.0.0.1:{}, screenshots={})",
                port, dirStr);
    }

    static void onDirective(Lc2FieldNetwork.S2CFieldDirective pkt) {
        Lc2FieldClientDriver driver = instance;
        if (driver != null) {
            Lc2QaMod.LOGGER.info("[LC2-FIELD-CLIENT] Received directive step={} action={} pos={} ent={} arg={}",
                    pkt.stepId(), pkt.action(), pkt.targetPos(), pkt.entityId(), pkt.argText());
            driver.pendingDirectives.add(pkt);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        if (shuttingDown) {
            shutdownDelay++;
            if (shutdownDelay >= 10) {
                mc.stop();
            }
            return;
        }

        // Auto-connect to dedicated server as soon as TitleScreen opens
        if (!connectStarted && mc.screen instanceof TitleScreen titleScreen) {
            connectStarted = true;
            Lc2QaMod.LOGGER.info("[LC2-FIELD-CLIENT] TitleScreen ready -> connecting to 127.0.0.1:{}", port);
            ServerAddress addr = new ServerAddress("127.0.0.1", port);
            ServerData data = new ServerData("LC2 Field Server", "127.0.0.1:" + port, false);
            ConnectScreen.startConnecting(titleScreen, mc, addr, data);
            return;
        }

        if (connectStarted && mc.screen instanceof DisconnectedScreen disconnected) {
            Lc2QaMod.LOGGER.error("[LC2-FIELD-CLIENT] Unexpectedly disconnected: {}", disconnected.getTitle().getString());
            failAndStop(mc, new AssertionError("Client disconnected unexpectedly: " + disconnected.getTitle().getString()));
            return;
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

        if (activeDirective == null && !pendingDirectives.isEmpty()) {
            activeDirective = pendingDirectives.poll();
            directiveSubStep = 0;
            directiveTicks = 0;
            guiScreenshotTaken = false;
        }

        if (activeDirective == null) {
            return;
        }

        directiveTicks++;
        try {
            tickDirective(mc, activeDirective);
        } catch (Throwable t) {
            Lc2QaMod.LOGGER.error("[LC2-FIELD-CLIENT] Directive {} ({}) failed",
                    activeDirective.stepId(), activeDirective.action(), t);
            Lc2FieldNetwork.sendToServer(new Lc2FieldNetwork.C2SFieldAck(
                    activeDirective.stepId(), false, t.getClass().getSimpleName() + ": " + t.getMessage()));
            failAndStop(mc, t);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        String name = this.pendingScreenshotName;
        if (name == null) {
            return;
        }
        this.pendingScreenshotName = null;
        Minecraft mc = Minecraft.getInstance();
        try {
            Files.createDirectories(screenshotsDir);
            Path outFile = screenshotsDir.resolve(name);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(outFile);
            }
            capturedScreenshots.put(name, outFile.toAbsolutePath().toString());
            Lc2QaMod.LOGGER.info("[LC2-FIELD-CLIENT] Captured screenshot: {}", outFile.toAbsolutePath());
            this.screenshotError = null;
        } catch (Throwable t) {
            Lc2QaMod.LOGGER.error("[LC2-FIELD-CLIENT] Failed capturing screenshot {}", name, t);
            this.screenshotError = t.toString();
        } finally {
            this.screenshotDone = true;
        }
    }

    private void tickDirective(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) throws Exception {
        switch (dir.action()) {
            case "AUDIT_ASSETS" -> runAssetAudit(mc, dir);
            case "SET_CAMERA" -> runSetCamera(mc, dir);
            case "CAPTURE_SCREENSHOT" -> tickCaptureScreenshot(mc, dir);
            case "OPEN_AND_TEST_SMART_PORT" -> tickOpenAndTestStation(mc, dir, true);
            case "OPEN_AND_TEST_CARGO_STATION" -> tickOpenAndTestStation(mc, dir, false);
            case "TEST_BLOCKED_BOAT_BOARDING" -> tickTestBlockedBoatBoarding(mc, dir);
            case "BOARD_DOCKED_BOAT" -> tickBoardDockedBoat(mc, dir);
            case "CHECK_RIDING_STATE" -> runCheckRidingState(mc, dir);
            case "FINISH_AND_DISCONNECT" -> runFinishAndDisconnect(mc, dir);
            default -> throw new AssertionError("Unknown client directive action: " + dir.action());
        }
    }

    private void runAssetAudit(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        TextureAtlas blocksAtlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        ResourceLocation missingLoc = MissingTextureAtlasSprite.getLocation();

        int verifiedSprites = 0;
        for (String id : REQUIRED_BLOCK_SPRITES) {
            ResourceLocation rl = new ResourceLocation(id);
            TextureAtlasSprite sprite = blocksAtlas.getSprite(rl);
            qa.check("client.sprite." + rl.getPath(),
                    sprite != null && !missingLoc.equals(sprite.getName()),
                    "sprite=" + (sprite == null ? "null" : sprite.getName()));
            verifiedSprites++;
        }
        for (String id : REQUIRED_ITEM_SPRITES) {
            ResourceLocation rl = new ResourceLocation(id);
            TextureAtlasSprite sprite = blocksAtlas.getSprite(rl);
            qa.check("client.sprite." + rl.getPath(),
                    sprite != null && !missingLoc.equals(sprite.getName()),
                    "sprite=" + (sprite == null ? "null" : sprite.getName()));
            verifiedSprites++;
        }

        int verifiedResources = 0;
        for (String id : REQUIRED_RESOURCES) {
            ResourceLocation rl = new ResourceLocation(id);
            boolean present = mc.getResourceManager().getResource(rl).isPresent();
            qa.check("client.resource." + rl.getPath(), present, "resource=" + rl);
            verifiedResources++;
        }

        qa.metric("client.verifiedSprites", verifiedSprites);
        qa.metric("client.verifiedResources", verifiedResources);
        ackAndClear(dir, true, "verified " + verifiedSprites + " atlas sprites + " + verifiedResources + " PNG resources");
    }

    private void runSetCamera(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        String arg = dir.argText();
        if ("THIRD_PERSON_BACK".equalsIgnoreCase(arg)) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.hideGui = false;
        } else if ("THIRD_PERSON_FRONT".equalsIgnoreCase(arg)) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.options.hideGui = false;
        } else if ("FIRST_PERSON_CLEAN".equalsIgnoreCase(arg)) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = true;
        } else {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
        }
        ackAndClear(dir, true, "camera=" + mc.options.getCameraType() + ", hideGui=" + mc.options.hideGui);
    }

    private void tickCaptureScreenshot(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        if (directiveSubStep == 0) {
            // Ensure ReceivingLevelScreen ("Loading terrain...") or any modal screen is fully gone before world shots!
            if (mc.screen != null) {
                directiveTicks = 0;
                return;
            }
            // Clear vanilla offline-mode chat/recipe toast banners so screenshots stay spotless
            mc.getToasts().clear();
            int settleTicks = dir.argText().contains("drones") ? 10 : 25;
            if (directiveTicks < settleTicks) {
                return;
            }
            this.screenshotDone = false;
            this.screenshotError = null;
            this.pendingScreenshotName = dir.argText();
            directiveSubStep = 1;
            return;
        }
        if (!this.screenshotDone) {
            if (directiveTicks > 120) {
                throw new AssertionError("Timed out waiting for RenderTickEvent screenshot: " + dir.argText());
            }
            return;
        }
        if (this.screenshotError != null) {
            throw new AssertionError("Screenshot capture error: " + this.screenshotError);
        }
        qa.check("client.screenshot." + dir.argText(), true, "saved " + capturedScreenshots.get(dir.argText()));
        ackAndClear(dir, true, "captured " + dir.argText());
    }

    private void tickOpenAndTestStation(
            Minecraft mc,
            Lc2FieldNetwork.S2CFieldDirective dir,
            boolean isSmartPort
    ) {
        String prefix = isSmartPort ? "client.smart_port_gui" : "client.cargo_station_gui";
        BlockPos pos = dir.targetPos();
        String screenshotFile = dir.argText();

        switch (directiveSubStep) {
            case 0 -> {
                // Ensure clean GUI visibility & open screen via real client right-click packet!
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false;
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                directiveSubStep = 1;
                directiveTicks = 0;
            }
            case 1 -> {
                // Wait for real screen + S2CStationSyncPacket from server
                boolean rightScreen = isSmartPort
                        ? (mc.screen instanceof SmartPortScreen)
                        : (mc.screen instanceof CargoStationScreen);
                S2CStationSyncPacket sync = currentSync(mc);
                if (!rightScreen || sync == null) {
                    if (directiveTicks > 80) {
                        throw new AssertionError("Timed out waiting for " + prefix
                                + " screen/sync (screen=" + mc.screen + ", sync=" + sync + ")");
                    }
                    return;
                }
                if (directiveTicks < 8) {
                    return; // let layout + slots render cleanly
                }
                qa.check(prefix + ".opened", true, "screen=" + mc.screen.getClass().getSimpleName());
                qa.check(prefix + ".sync_received", sync.stationPos().equals(pos),
                        "name=" + sync.stationName() + ", eu=" + sync.storedEu() + "/" + sync.maxEu()
                                + ", targets=" + sync.targets().size());
                if (!isSmartPort) {
                    qa.check(prefix + ".rf_chip_synced", sync.rfChipTierIndex() >= 1,
                            "rfChipTierIndex=" + sync.rfChipTierIndex());
                } else {
                    qa.check(prefix + ".water_berth_synced", sync.hasWaterBerth(),
                            "hasWaterBerth=" + sync.hasWaterBerth() + ", vehicleReady=" + sync.vehicleReady());
                }

                this.guiOriginalName = sync.stationName();
                this.guiOriginalTarget = sync.hasTarget() ? sync.targetPos() : null;
                this.guiOriginalAutoReturn = sync.autoReturn();
                this.guiOriginalPriority = sync.energyPriority();

                // Clear top-right vanilla toast popups before grabbing crisp GUI screenshot
                mc.getToasts().clear();
                this.screenshotDone = false;
                this.screenshotError = null;
                this.pendingScreenshotName = screenshotFile;
                directiveSubStep = 2;
                directiveTicks = 0;
            }
            case 2 -> {
                if (!this.screenshotDone) {
                    if (directiveTicks > 100) {
                        throw new AssertionError("Timed out capturing GUI screenshot " + screenshotFile);
                    }
                    return;
                }
                if (this.screenshotError != null) {
                    throw new AssertionError("GUI screenshot failed: " + this.screenshotError);
                }
                qa.check(prefix + ".screenshot", true, "saved " + capturedScreenshots.get(screenshotFile));

                // Test 1: Rename over C2SStationActionPacket -> verify S2CStationSyncPacket reflects new name
                String testName = isSmartPort ? "Harbor Alpha" : "Airfield Alpha";
                Lc2Network.sendToServer(C2SStationActionPacket.setName(pos, testName));
                directiveSubStep = 3;
                directiveTicks = 0;
            }
            case 3 -> {
                String expected = isSmartPort ? "Harbor Alpha" : "Airfield Alpha";
                S2CStationSyncPacket sync = currentSync(mc);
                if (sync != null && expected.equals(sync.stationName())) {
                    qa.check(prefix + ".rename_roundtrip", true, "syncedName=" + sync.stationName());
                    // Test 2: Toggle Auto-Return
                    Lc2Network.sendToServer(C2SStationActionPacket.simple(pos, C2SStationActionPacket.Action.TOGGLE_AUTO_RETURN));
                    directiveSubStep = 4;
                    directiveTicks = 0;
                } else if (directiveTicks > 60) {
                    throw new AssertionError(prefix + " rename sync timed out: got " + (sync == null ? "null" : sync.stationName()));
                }
            }
            case 4 -> {
                S2CStationSyncPacket sync = currentSync(mc);
                if (sync != null && sync.autoReturn() == !this.guiOriginalAutoReturn) {
                    qa.check(prefix + ".auto_return_toggle_roundtrip", true,
                            "autoReturn=" + this.guiOriginalAutoReturn + " -> " + sync.autoReturn());
                    // Test 3: Toggle Energy Priority
                    Lc2Network.sendToServer(C2SStationActionPacket.simple(pos, C2SStationActionPacket.Action.TOGGLE_ENERGY_PRIORITY));
                    directiveSubStep = 5;
                    directiveTicks = 0;
                } else if (directiveTicks > 60) {
                    throw new AssertionError(prefix + " autoReturn toggle sync timed out");
                }
            }
            case 5 -> {
                S2CStationSyncPacket sync = currentSync(mc);
                if (sync != null && sync.energyPriority() == this.guiOriginalPriority.toggle()) {
                    qa.check(prefix + ".priority_toggle_roundtrip", true,
                            "priority=" + this.guiOriginalPriority + " -> " + sync.energyPriority());
                    // Test 4: Cycle Target
                    Lc2Network.sendToServer(C2SStationActionPacket.cycleTarget(pos, +1));
                    directiveSubStep = 6;
                    directiveTicks = 0;
                } else if (directiveTicks > 60) {
                    throw new AssertionError(prefix + " energyPriority toggle sync timed out");
                }
            }
            case 6 -> {
                if (directiveTicks < 10) {
                    return;
                }
                S2CStationSyncPacket sync = currentSync(mc);
                qa.check(prefix + ".cycle_target_roundtrip", sync != null && sync.hasTarget(),
                        "target=" + (sync == null ? "null" : sync.targetPos()));
                // Restore original settings cleanly!
                Lc2Network.sendToServer(C2SStationActionPacket.setName(pos, this.guiOriginalName));
                if (this.guiOriginalTarget != null) {
                    Lc2Network.sendToServer(C2SStationActionPacket.setTarget(
                            pos, true,
                            this.guiOriginalTarget.getX(),
                            this.guiOriginalTarget.getY(),
                            this.guiOriginalTarget.getZ()));
                }
                Lc2Network.sendToServer(C2SStationActionPacket.simple(pos, C2SStationActionPacket.Action.TOGGLE_AUTO_RETURN));
                Lc2Network.sendToServer(C2SStationActionPacket.simple(pos, C2SStationActionPacket.Action.TOGGLE_ENERGY_PRIORITY));
                directiveSubStep = 7;
                directiveTicks = 0;
            }
            case 7 -> {
                S2CStationSyncPacket sync = currentSync(mc);
                boolean restored = sync != null
                        && this.guiOriginalName.equals(sync.stationName())
                        && sync.autoReturn() == this.guiOriginalAutoReturn
                        && sync.energyPriority() == this.guiOriginalPriority;
                if (restored) {
                    qa.check(prefix + ".settings_restored", true, "restored cleanly");
                    mc.player.closeContainer();
                    ackAndClear(dir, true, prefix + " verified + screenshot " + screenshotFile);
                } else if (directiveTicks > 80) {
                    throw new AssertionError(prefix + " restore sync timed out: " + sync);
                }
            }
        }
    }

    private void tickTestBlockedBoatBoarding(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        if (directiveSubStep == 0) {
            Entity ent = mc.level.getEntity(dir.entityId());
            if (!(ent instanceof ElectricCargoBoatEntity boat)) {
                if (directiveTicks > 60) {
                    throw new AssertionError("Could not find moving ElectricCargoBoatEntity id=" + dir.entityId());
                }
                return;
            }
            if (!boat.isAutonomousTransit()) {
                if (directiveTicks > 60) {
                    throw new AssertionError("Boat id=" + dir.entityId() + " did not sync isAutonomousTransit=true to client");
                }
                return;
            }
            mc.gui.setOverlayMessage(Component.empty(), false);
            mc.gameMode.interact(mc.player, boat, InteractionHand.MAIN_HAND);
            directiveSubStep = 1;
            directiveTicks = 0;
            return;
        }
        if (directiveTicks < 12) {
            return;
        }
        boolean stillNotRiding = mc.player.getVehicle() == null;
        String actionbar = readActionbarText(mc.gui);
        qa.check("client.boat.active_transit_boarding_blocked", stillNotRiding,
                "vehicle=" + mc.player.getVehicle());
        qa.check("client.boat.active_transit_actionbar_hint",
                actionbar.toLowerCase().contains("cannot board") || actionbar.toLowerCase().contains("transit"),
                "actionbar='" + actionbar + "'");
        ackAndClear(dir, true, "blocked boarding verified; actionbar='" + actionbar + "'");
    }

    private void tickBoardDockedBoat(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        if (directiveSubStep == 0) {
            Entity ent = mc.level.getEntity(dir.entityId());
            if (!(ent instanceof ElectricCargoBoatEntity boat)) {
                if (directiveTicks > 60) {
                    throw new AssertionError("Could not find docked ElectricCargoBoatEntity id=" + dir.entityId());
                }
                return;
            }
            qa.check("client.boat.docked_not_autonomous", !boat.isAutonomousTransit(),
                    "isAutonomousTransit=" + boat.isAutonomousTransit());
            mc.gameMode.interact(mc.player, boat, InteractionHand.MAIN_HAND);
            directiveSubStep = 1;
            directiveTicks = 0;
            return;
        }
        if (mc.player.getVehicle() instanceof ElectricCargoBoatEntity ridden && ridden.getId() == dir.entityId()) {
            qa.check("client.boat.boarded_before_launch", true, "riding boat id=" + ridden.getId());
            ackAndClear(dir, true, "player seated in docked boat id=" + ridden.getId());
            return;
        }
        if (directiveTicks > 60) {
            throw new AssertionError("Client player failed to mount docked boat id=" + dir.entityId()
                    + " (vehicle=" + mc.player.getVehicle() + ")");
        }
    }

    private void runCheckRidingState(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) {
        Entity vehicle = mc.player.getVehicle();
        boolean ridingAutoBoat = vehicle instanceof ElectricCargoBoatEntity boat && boat.isAutonomousTransit();
        qa.check("client.boat.riding_during_natural_transit", ridingAutoBoat,
                "vehicle=" + vehicle + ", pos=" + mc.player.position()
                        + ", autonomous=" + (vehicle instanceof ElectricCargoBoatEntity b && b.isAutonomousTransit()));
        ackAndClear(dir, ridingAutoBoat, "ridingAutoBoat=" + ridingAutoBoat + " at " + mc.player.blockPosition());
    }

    private void runFinishAndDisconnect(Minecraft mc, Lc2FieldNetwork.S2CFieldDirective dir) throws Exception {
        Map<String, String> extras = new LinkedHashMap<>();
        int idx = 1;
        for (Map.Entry<String, String> e : capturedScreenshots.entrySet()) {
            extras.put("screenshot." + idx + ".name", e.getKey());
            extras.put("screenshot." + idx + ".path", e.getValue());
            idx++;
        }
        qa.writePassedReport(Path.of("field-client-report.properties"), extras);
        ackAndClear(dir, true, "client finished (" + qa.passedCount() + " checks passed, "
                + capturedScreenshots.size() + " screenshots)");
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.clearLevel();
        shuttingDown = true;
        shutdownDelay = 0;
    }

    private void ackAndClear(Lc2FieldNetwork.S2CFieldDirective dir, boolean ok, String summary) {
        Lc2FieldNetwork.sendToServer(new Lc2FieldNetwork.C2SFieldAck(dir.stepId(), ok, summary));
        activeDirective = null;
        directiveSubStep = 0;
        directiveTicks = 0;
    }

    private void failAndStop(Minecraft mc, Throwable t) {
        qa.writeFailureReport(
                Path.of("field-client-report.properties"),
                Path.of("field-client-failure.txt"),
                t
        );
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.clearLevel();
        shuttingDown = true;
        shutdownDelay = 0;
    }

    private static S2CStationSyncPacket currentSync(Minecraft mc) {
        if (mc.player != null && mc.player.containerMenu instanceof AbstractStationMenu menu) {
            return menu.getLatestSync();
        }
        return null;
    }

    private static String readActionbarText(Gui gui) {
        String fallback = "";
        try {
            for (Field f : Gui.class.getDeclaredFields()) {
                if (Component.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(gui);
                    if (val instanceof Component comp) {
                        String text = comp.getString();
                        if (!text.isEmpty()) {
                            if (text.toLowerCase().contains("board") || text.toLowerCase().contains("transit")) {
                                return text;
                            }
                            if (fallback.isEmpty()) {
                                fallback = text;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
