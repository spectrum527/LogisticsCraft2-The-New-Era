package com.logisticscraft.lc2.qa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.industrialage.ia2.gameplay.IndustrialMachine;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live multiplayer dedicated-server host ({@code lc2.qa.mode=field_live}) for Phase 3 real
 * graphical client-server acceptance testing on natural seed {@code 20261121}.
 */
public final class FieldLiveServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldLiveServer.class);

    private FieldFactoryBuilder.BuiltFactory factory;
    private UUID lastUnmannedBoatTransit;
    private int tickCounter = 0;

    public void install() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, this::registerCommands);
    }

    public void onServerStarted(MinecraftServer server, QaChecks checks) throws IOException {
        ServerLevel level = server.overworld();
        FieldWorldScanner.FieldSite site = FieldWorldScanner.findNaturalSite(level);
        factory = FieldFactoryBuilder.build(level, site, checks);

        // Lock sunny bright noon weather for crisp screenshots
        lockDaytimeAndWeather(server);

        // Pre-populate realistic EU on all 4 stations (while real IC2 generators also keep charging them!)
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(factory.portB());
        CargoStationBlockEntity hill = (CargoStationBlockEntity) level.getBlockEntity(factory.hillCargoPos());
        CargoStationBlockEntity baseCargo = (CargoStationBlockEntity) level.getBlockEntity(factory.baseCargoPos());
        if (portA != null) {
            portA.addEnergy(11_200.0);
        }
        if (portB != null) {
            portB.addEnergy(12_000.0);
        }
        if (hill != null) {
            hill.addEnergy(10_500.0);
        }
        if (baseCargo != null) {
            baseCargo.addEnergy(12_000.0);
        }

        // Write site coordinates JSON for the external Python client-server orchestrator
        writeSiteJson(level);
        LOGGER.info("[LC2-LIVE] Natural field-live world ready (seed={}, portA={}, hill={}, portB={})",
                site.seed(), factory.portA(), factory.hillCargoPos(), factory.portB());
    }

    public void onServerTick(MinecraftServer server) {
        if (factory == null) {
            return;
        }
        tickCounter++;
        if (tickCounter % 200 == 1) {
            lockDaytimeAndWeather(server);
        }
    }

    private void lockDaytimeAndWeather(MinecraftServer server) {
        var src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "time set 5500");
        server.getCommands().performPrefixedCommand(src, "weather clear 999999");
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("lc2field")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .executes(ctx -> {
                                    String action = StringArgumentType.getString(ctx, "action");
                                    MinecraftServer server = ctx.getSource().getServer();
                                    ServerLevel level = server.overworld();
                                    ServerPlayer player = findTestPlayer(server);
                                    try {
                                        handleAction(server, level, player, action);
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("lc2field:" + action + ":ok"),
                                                false);
                                        return 1;
                                    } catch (Exception e) {
                                        LOGGER.error("[LC2-LIVE] Command /lc2field {} failed", action, e);
                                        ctx.getSource().sendFailure(Component.literal("lc2field error: " + e));
                                        return 0;
                                    }
                                }))
        );
    }

    private ServerPlayer findTestPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    private void handleAction(
            MinecraftServer server,
            ServerLevel level,
            ServerPlayer player,
            String action
    ) throws IOException {
        if (factory == null) {
            throw new IllegalStateException("Field factory not built yet");
        }
        switch (action) {
            case "scene_harbor" -> stageHarborScene(level, player);
            case "prep_smart_port_gui" -> prepSmartPortGui(level, player);
            case "prep_cargo_station_gui" -> prepCargoStationGui(level, player);
            case "launch_boat_unmanned" -> launchBoatUnmanned(level, player);
            case "prep_boat_ride" -> prepBoatRide(level, player);
            case "trigger_boat_redstone" -> triggerBoatRedstone(level);
            case "scene_drones" -> stageDronesScene(level, player);
            case "scene_refinery" -> stageRefineryScene(level, player);
            case "dump_state" -> writeServerStateJson(level, player);
            default -> throw new IllegalArgumentException("Unknown lc2field action: " + action);
        }
        writeServerStateJson(level, player);
    }

    private void stageHarborScene(ServerLevel level, ServerPlayer player) {
        // Pause comparator wire on portA.above() briefly so docked boat stays neatly parked for the harbor panorama
        level.setBlockAndUpdate(factory.portA().above(), Blocks.AIR.defaultBlockState());
        cancelBoatTransits(level);
        ensureBoatDockedAtPortA(level);

        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        if (portA != null) {
            portA.setStationName("Outpost Harbor");
            portA.setTargetPos(factory.portB());
            portA.setAutoReturn(true);
            portA.setEnergyPriority(EnergyPriority.EU_FIRST);
            portA.addEnergy(12_000.0);
        }

        if (player != null) {
            Direction waterDir = factory.site().waterDirA();
            Direction left = waterDir.getCounterClockWise();
            BlockPos p = factory.portA();
            // Position floating gracefully over the water at an oblique angle showing boat + port + cables + BatBox + Advanced Miner!
            double ex = p.getX() + 0.5 + waterDir.getStepX() * 6.5 + left.getStepX() * 5.0;
            double ey = p.getY() + 4.8;
            double ez = p.getZ() + 0.5 + waterDir.getStepZ() * 6.5 + left.getStepZ() * 5.0;
            double tx = p.getX() + 0.5 - waterDir.getStepX() * 0.8 + left.getStepX() * 0.4;
            double ty = p.getY() + 0.5;
            double tz = p.getZ() + 0.5 - waterDir.getStepZ() * 0.8 + left.getStepZ() * 0.4;
            placeCameraFlying(level, player, ex, ey, ez, tx, ty, tz);
        }
    }

    private void prepSmartPortGui(ServerLevel level, ServerPlayer player) {
        level.setBlockAndUpdate(factory.portA().above(), Blocks.AIR.defaultBlockState());
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        if (portA != null) {
            portA.setStationName("Outpost Harbor");
            portA.setTargetPos(factory.portB());
            portA.setAutoReturn(true);
            portA.setEnergyPriority(EnergyPriority.EU_FIRST);
            portA.addEnergy(12_000.0);
            for (int i = 0; i < SmartPortBlockEntity.TOTAL_CARGO_SLOTS; i++) {
                portA.setItem(i, ItemStack.EMPTY);
            }
            // Seed distinct items in BOTH Outbound (0..2) and Storage (27..29) for 2-zone GUI + live Swap TCP check!
            // Slot 0 uses crafted ingots/blocks so live miners never merge raw ores into slot 0 during GUI check.
            portA.setItem(0, new ItemStack(Items.GOLD_BLOCK, 8));
            portA.setItem(1, new ItemStack(Items.RAW_COPPER, 16));
            portA.setItem(2, new ItemStack(Items.RAW_IRON, 12));
            portA.setItem(SmartPortBlockEntity.STORAGE_START, new ItemStack(Items.COPPER_INGOT, 24));
            portA.setItem(SmartPortBlockEntity.STORAGE_START + 1, new ItemStack(Items.IRON_INGOT, 18));
            portA.setItem(SmartPortBlockEntity.STORAGE_START + 2, new ItemStack(Items.GOLD_INGOT, 6));
            portA.setItem(SmartPortBlockEntity.BATTERY_SLOT,
                    new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 4));
        }
        if (player != null) {
            player.getInventory().clearContent();
            Direction waterDir = factory.site().waterDirA();
            BlockPos p = factory.portA();
            double ex = p.getX() + 0.5 + waterDir.getStepX() * 2.2;
            double ey = p.getY() + 1.8;
            double ez = p.getZ() + 0.5 + waterDir.getStepZ() * 2.2;
            placeCameraFlying(level, player, ex, ey, ez, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        }
    }

    private void prepCargoStationGui(ServerLevel level, ServerPlayer player) {
        // Temporarily pause comparator trigger wire at hillCargo.south() so drones stay neatly parked for GUI inspection
        level.setBlockAndUpdate(factory.hillCargoPos().south(), Blocks.AIR.defaultBlockState());
        CargoStationBlockEntity hill = (CargoStationBlockEntity) level.getBlockEntity(factory.hillCargoPos());
        if (hill != null) {
            hill.setStationName("Ridge Airfield");
            hill.setTargetPos(factory.baseCargoPos());
            hill.setAutoReturn(true);
            hill.setEnergyPriority(EnergyPriority.EU_FIRST);
            hill.addEnergy(11_500.0);
            hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 0,
                    new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
            hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1,
                    new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
            hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 2,
                    new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
            hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 3,
                    new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
            hill.setItem(CargoStationBlockEntity.BATTERY_SLOT,
                    new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 6));
            for (int i = 0; i < CargoStationBlockEntity.TOTAL_CARGO_SLOTS; i++) {
                hill.setItem(i, ItemStack.EMPTY);
            }
            // Seed distinct items in BOTH Outbound (0..2) and Storage (27..29) for 2-zone GUI + live Swap TCP check!
            // Slot 0 uses crafted ingots/blocks so live miners never merge raw ores into slot 0 during GUI check.
            hill.setItem(0, new ItemStack(Items.IRON_BLOCK, 12));
            hill.setItem(1, new ItemStack(Items.RAW_COPPER, 24));
            hill.setItem(2, new ItemStack(Items.RAW_IRON, 16));
            hill.setItem(CargoStationBlockEntity.STORAGE_START, new ItemStack(Items.DIAMOND_BLOCK, 10));
            hill.setItem(CargoStationBlockEntity.STORAGE_START + 1, new ItemStack(Items.EMERALD_BLOCK, 14));
            hill.setItem(CargoStationBlockEntity.STORAGE_START + 2, new ItemStack(Items.REDSTONE_BLOCK, 20));
        }
        if (player != null) {
            player.getInventory().clearContent();
            BlockPos c = factory.hillCargoPos();
            double ex = c.getX() + 0.5;
            double ey = c.getY() + 2.0;
            double ez = c.getZ() + 2.5;
            placeCameraFlying(level, player, ex, ey, ez, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
        }
    }

    private void launchBoatUnmanned(ServerLevel level, ServerPlayer player) {
        level.setBlockAndUpdate(factory.portA().above(), Blocks.AIR.defaultBlockState());
        cancelBoatTransits(level);
        ensureBoatDockedAtPortA(level);

        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        if (portA == null) {
            throw new IllegalStateException("Missing portA");
        }
        portA.setTargetPos(factory.portB());
        portA.setAutoReturn(true);
        portA.setEnergyPriority(EnergyPriority.EU_FIRST);
        portA.addEnergy(12_000.0);
        var res = portA.tryDispatch(level, true);
        if (!res.isSuccess()) {
            throw new IllegalStateException("Failed launching unmanned boat: " + res.failure());
        }
        lastUnmannedBoatTransit = res.transit().id();
        Entity boat = LogisticsLevelManager.findLiveEntity(level, lastUnmannedBoatTransit);
        if (player != null && boat != null) {
            player.getInventory().clearContent();
            // Position player right next to the autonomously moving boat so client can attempt boarding
            placeCameraFlying(level, player,
                    boat.getX(), boat.getY() + 2.0, boat.getZ(),
                    boat.getX(), boat.getY() + 0.3, boat.getZ());
        }
    }

    private void prepBoatRide(ServerLevel level, ServerPlayer player) {
        level.setBlockAndUpdate(factory.portA().above(), Blocks.AIR.defaultBlockState());
        cancelBoatTransits(level);
        ElectricCargoBoatEntity boat = ensureBoatDockedAtPortA(level);

        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        if (portA == null) {
            throw new IllegalStateException("Missing portA");
        }
        portA.setTargetPos(factory.portB());
        portA.setAutoReturn(false);
        portA.setEnergyPriority(EnergyPriority.EU_FIRST);
        portA.addEnergy(12_000.0);
        portA.setItem(0, new ItemStack(Items.RAW_COPPER, 20));
        portA.setItem(1, new ItemStack(Items.RAW_IRON, 16));

        if (player != null) {
            player.getInventory().clearContent();
            player.setGameMode(GameType.SURVIVAL);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            // Stand directly on top of Outpost Smart Port looking down at the docked Electric Cargo Boat (<2 blocks!)
            BlockPos p = factory.portA();
            double ex = p.getX() + 0.5;
            double ey = p.getY() + 1.0 + 1.62;
            double ez = p.getZ() + 0.5;
            float[] ang = computeLookAngles(ex, ey, ez, boat.getX(), boat.getY() + 0.4, boat.getZ());
            QaPlatform.teleportPlayer(player, level, p.getX() + 0.5, p.getY() + 1.05, p.getZ() + 0.5, ang[0], ang[1]);
        }
    }

    private void triggerBoatRedstone(ServerLevel level) {
        // Trigger real redstone launch on Outpost Smart Port A while client player is seated inside!
        level.setBlockAndUpdate(factory.portA().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
    }

    private void stageDronesScene(ServerLevel level, ServerPlayer player) {
        level.setBlockAndUpdate(factory.hillCargoPos().south(), Blocks.AIR.defaultBlockState());
        CargoStationBlockEntity hill = (CargoStationBlockEntity) level.getBlockEntity(factory.hillCargoPos());
        CargoStationBlockEntity baseCargo = (CargoStationBlockEntity) level.getBlockEntity(factory.baseCargoPos());
        if (hill == null || baseCargo == null) {
            throw new IllegalStateException("Missing Cargo Stations");
        }
        hill.setTargetPos(factory.baseCargoPos());
        hill.setAutoReturn(true);
        hill.setEnergyPriority(EnergyPriority.EU_FIRST);
        hill.addEnergy(14_000.0);
        baseCargo.addEnergy(14_000.0);

        // Ensure bays 0..3 stay parked on the roof (2 Standard + 2 Heavy), then launch 1 Standard + 1 Heavy!
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 0,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1,
                new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 2,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 3,
                new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 4,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
        hill.setItem(CargoStationBlockEntity.DRONE_BAY_START + 5,
                new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));

        // Clear only immediate bush/grass blocking sightlines around Ridge Airfield pad
        BlockPos c = factory.hillCargoPos();
        for (int dx = -1; dx <= 4; dx++) {
            for (int dy = 0; dy <= 5; dy++) {
                for (int dz = -1; dz <= 4; dz++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    var st = level.getBlockState(p);
                    if (st.is(net.minecraft.tags.BlockTags.LEAVES)
                            || st.is(Blocks.SHORT_GRASS)
                            || st.is(Blocks.TALL_GRASS)
                            || st.is(Blocks.FERN)
                            || st.is(Blocks.VINE)) {
                        level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        // Launch Standard (bay 0) and Heavy (bay 1), leaving 4 drones parked on roof bays 2..5
        hill.setItem(0, new ItemStack(Items.RAW_COPPER, 16));
        hill.tryDispatch(level, true);
        hill.setItem(0, new ItemStack(Items.RAW_IRON, 16));
        hill.tryDispatch(level, true);

        // Keep 4 drones visibly parked on the roof bays 2..5 while the 2 launched drones climb directly above!
        if (player != null) {
            // Stand slightly south-east & level looking gently upward across Ridge Airfield roof + rising drones!
            double ex = c.getX() + 6.5;
            double ey = c.getY() + 2.8;
            double ez = c.getZ() + 6.5;
            placeCameraFlying(level, player, ex, ey, ez, c.getX() + 0.5, c.getY() + 3.6, c.getZ() + 0.5);
        }
    }

    private void stageRefineryScene(ServerLevel level, ServerPlayer player) {
        // Ensure Refining Base B machines are actively crushing & smelting AND Steel Box has finished ingots
        if (level.getBlockEntity(factory.maceratorPos()) instanceof IndustrialMachine mac) {
            if (mac.getItem(0).isEmpty()) {
                mac.setItem(0, new ItemStack(Items.RAW_COPPER, 16));
            }
        }
        if (level.getBlockEntity(factory.electricFurnacePos()) instanceof IndustrialMachine efur) {
            if (efur.getItem(0).isEmpty()) {
                efur.setItem(0, new ItemStack(QaChecks.item("mhera:crushed_copper"), 16));
            }
        }
        if (level.getBlockEntity(factory.baseCargoPos()) instanceof CargoStationBlockEntity baseCargo) {
            baseCargo.addEnergy(14_000.0);
            if (baseCargo.countReadyDrones() == 0) {
                baseCargo.setItem(CargoStationBlockEntity.DRONE_BAY_START + 0,
                        new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
                baseCargo.setItem(CargoStationBlockEntity.DRONE_BAY_START + 1,
                        new ItemStack(QaChecks.item(LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID), 1));
            }
        }

        if (player != null) {
            Direction waterDir = factory.site().waterDirB();
            Direction flow = factory.site().baseFlowDir();
            BlockPos rch = factory.receivingChestB();
            // Trim tall grass directly in front of Macerator / Electric Furnace / Steel Storage Box
            for (BlockPos m : List.of(factory.maceratorPos(), factory.electricFurnacePos(), factory.steelOutputBoxPos())) {
                for (BlockPos g : List.of(m.relative(waterDir), m.relative(waterDir).above())) {
                    var st = level.getBlockState(g);
                    if (st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN)) {
                        level.setBlockAndUpdate(g, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            // Position over the water/front looking across the entire Base B refining line:
            // Smart Port + Cargo Station (with roof drones) + Receiving Chest + Active Macerator + Active Electric Furnace + Steel Box!
            double ex = rch.getX() + 0.5 + waterDir.getStepX() * 6.5 + flow.getStepX() * 2.5;
            double ey = rch.getY() + 4.5;
            double ez = rch.getZ() + 0.5 + waterDir.getStepZ() * 6.5 + flow.getStepZ() * 2.5;
            double tx = rch.getX() + 0.5 + flow.getStepX() * 1.2;
            double ty = rch.getY() + 0.6;
            double tz = rch.getZ() + 0.5 + flow.getStepZ() * 1.2;
            placeCameraFlying(level, player, ex, ey, ez, tx, ty, tz);
        }
    }

    private void cancelBoatTransits(ServerLevel level) {
        var data = LogisticsLevelManager.getData(level);
        List<UUID> boatIds = data.engine().activeTransits().stream()
                .filter(t -> t.vehicleKind().isBoat())
                .map(TransitRecord::id)
                .toList();
        for (UUID id : boatIds) {
            Entity e = LogisticsLevelManager.findLiveEntity(level, id);
            LogisticsLevelManager.cancelAndTakeCargo(level, id);
            if (e instanceof ElectricCargoBoatEntity boat) {
                boat.setDematerializing(true);
                boat.discard();
            }
        }
    }

    private ElectricCargoBoatEntity ensureBoatDockedAtPortA(ServerLevel level) {
        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(factory.portA());
        if (portA == null) {
            throw new IllegalStateException("Missing portA");
        }
        Optional<ElectricCargoBoatEntity> existing = portA.findDockedBoat(level);
        if (existing.isPresent()) {
            ElectricCargoBoatEntity b = existing.get();
            Vec3d berth = portA.getWaterBerthPos().waterSurfaceCenter();
            b.setPos(berth.x(), berth.y(), berth.z());
            b.setYRot(factory.site().waterDirA().toYRot());
            return b;
        }
        Vec3d berth = portA.getWaterBerthPos().waterSurfaceCenter();
        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
        boat.setPos(berth.x(), berth.y(), berth.z());
        boat.setYRot(factory.site().waterDirA().toYRot());
        level.addFreshEntity(boat);
        return boat;
    }

    private void placeCameraFlying(
            ServerLevel level,
            ServerPlayer player,
            double eyeX,
            double eyeY,
            double eyeZ,
            double targetX,
            double targetY,
            double targetZ
    ) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        double feetY = eyeY - 1.62;
        // Clear any stray jungle leaves right in the player's camera box so shots are never blocked by foliage
        BlockPos camBlock = BlockPos.containing(eyeX, eyeY, eyeZ);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = camBlock.offset(dx, dy, dz);
                    var st = level.getBlockState(p);
                    if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)) {
                        level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        float[] ang = computeLookAngles(eyeX, eyeY, eyeZ, targetX, targetY, targetZ);
        QaPlatform.teleportPlayer(player, level, eyeX, feetY, eyeZ, ang[0], ang[1]);
    }

    private static float[] computeLookAngles(
            double ex, double ey, double ez,
            double tx, double ty, double tz
    ) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{yaw, pitch};
    }

    private void writeSiteJson(ServerLevel level) throws IOException {
        JsonObject root = new JsonObject();
        var s = factory.site();
        root.addProperty("seed", s.seed());
        root.addProperty("waterY", s.waterY());
        root.addProperty("waterwayDistance", s.waterwayDistanceBlocks());
        root.addProperty("waypoints", s.verifiedWaypoints().size());
        root.addProperty("aquaticPlants", s.aquaticVegetationNearRoute());
        root.addProperty("biomeA", s.biomeA());
        root.addProperty("biomeB", s.biomeB());
        root.add("portA", posJson(factory.portA()));
        root.addProperty("waterDirA", s.waterDirA().getName());
        root.add("berthA", posJson(s.berthA()));
        root.add("advMiner", posJson(factory.advMinerPos()));
        root.add("hillCargo", posJson(factory.hillCargoPos()));
        root.add("basicMiner", posJson(factory.basicMinerPos()));
        root.add("portB", posJson(factory.portB()));
        root.addProperty("waterDirB", s.waterDirB().getName());
        root.add("berthB", posJson(s.berthB()));
        root.add("baseCargo", posJson(factory.baseCargoPos()));
        root.add("macerator", posJson(factory.maceratorPos()));
        root.add("electricFurnace", posJson(factory.electricFurnacePos()));
        root.add("steelBox", posJson(factory.steelOutputBoxPos()));
        writeAtomic(Path.of("field-site.json"), root);
    }

    private void writeServerStateJson(ServerLevel level, ServerPlayer player) throws IOException {
        JsonObject root = new JsonObject();
        if (level.getBlockEntity(factory.portA()) instanceof SmartPortBlockEntity portA) {
            JsonObject pa = new JsonObject();
            pa.addProperty("name", portA.getStationName());
            pa.addProperty("autoReturn", portA.isAutoReturn());
            pa.addProperty("priority", portA.getEnergyPriority().name());
            pa.addProperty("storedEu", portA.getStoredEu());
            pa.addProperty("targetPos", portA.getTargetPos() == null ? "" : posString(portA.getTargetPos()));
            pa.addProperty("boatDocked", portA.findDockedBoat(level).isPresent());
            pa.addProperty("outboundSlot0", slotDesc(portA.getItem(SmartPortBlockEntity.OUTBOUND_START)));
            pa.addProperty("storageSlot0", slotDesc(portA.getItem(SmartPortBlockEntity.STORAGE_START)));
            pa.addProperty("statusKey", portA.getStatusKey());
            root.add("portA", pa);
        }
        if (level.getBlockEntity(factory.hillCargoPos()) instanceof CargoStationBlockEntity hill) {
            JsonObject hc = new JsonObject();
            hc.addProperty("name", hill.getStationName());
            hc.addProperty("autoReturn", hill.isAutoReturn());
            hc.addProperty("priority", hill.getEnergyPriority().name());
            hc.addProperty("storedEu", hill.getStoredEu());
            hc.addProperty("rfRange", hill.installedRfChip().maxRange());
            hc.addProperty("readyDrones", hill.countReadyDrones());
            hc.addProperty("targetPos", hill.getTargetPos() == null ? "" : posString(hill.getTargetPos()));
            hc.addProperty("outboundSlot0", slotDesc(hill.getItem(CargoStationBlockEntity.OUTBOUND_START)));
            hc.addProperty("storageSlot0", slotDesc(hill.getItem(CargoStationBlockEntity.STORAGE_START)));
            hc.addProperty("statusKey", hill.getStatusKey());
            root.add("hillCargo", hc);
        }
        JsonArray transits = new JsonArray();
        for (TransitRecord rec : LogisticsLevelManager.getData(level).engine().activeTransits()) {
            JsonObject t = new JsonObject();
            t.addProperty("id", rec.id().toString());
            t.addProperty("kind", rec.vehicleKind().name());
            t.addProperty("phase", rec.phase().name());
            t.addProperty("hasRider", rec.hasRider());
            t.addProperty("x", rec.currentPos().x());
            t.addProperty("y", rec.currentPos().y());
            t.addProperty("z", rec.currentPos().z());
            transits.add(t);
        }
        root.add("activeTransits", transits);
        Map<String, Integer> steelOut = QaChecks.countItemsAcross(level, factory.steelOutputBoxPos());
        JsonObject outObj = new JsonObject();
        steelOut.forEach(outObj::addProperty);
        root.add("steelBox", outObj);
        if (player != null) {
            JsonObject pl = new JsonObject();
            pl.addProperty("name", player.getScoreboardName());
            pl.addProperty("x", player.getX());
            pl.addProperty("y", player.getY());
            pl.addProperty("z", player.getZ());
            pl.addProperty("vehicle", player.getVehicle() == null ? "" : player.getVehicle().getType().toString());
            root.add("player", pl);
        }
        writeAtomic(Path.of("server-state.json"), root);
    }

    private static JsonObject posJson(BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static String posString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String slotDesc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return Lc2Native.itemId(stack) + "x" + stack.getCount();
    }

    private static void writeAtomic(Path path, JsonObject obj) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(obj) + "\n");
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
