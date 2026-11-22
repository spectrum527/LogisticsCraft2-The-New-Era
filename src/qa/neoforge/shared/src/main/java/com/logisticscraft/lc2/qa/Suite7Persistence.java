package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 7: Cold-Restart & NBT Persistence Verification.
 * - Write phase:
 *   1. Launches a fresh Persistence Boat (carrying 20 DIAMOND_BLOCK) and Persistence Drone
 *      (carrying 28 EMERALD_BLOCK), ticks 15 ticks so both are genuinely mid-flight.
 *   2. Verifies direct in-memory {@code LogisticsSavedData.saveToTag -> loadFromTag} NBT round-trip.
 *   3. Persists expected mid-flight transit IDs and coordinates to {@code cold-restart-state.properties}
 *      so the server halts and restarts in a brand-new JVM process.
 * - Read phase (cold restart):
 *   1. Verifies both stations, both active mid-flight transits, their mid-flight positions, and
 *      their serialized cargo holds survived full server save + cold restart intact.
 *   2. Ticks until both mid-flight vehicles finish delivery and verifies 100% cargo arrival & docking.
 */
public final class Suite7Persistence {

    private static final Path STATE_FILE = Path.of("cold-restart-state.properties");
    private static final int WATER_Y = 63;

    private static final BlockPos PERSIST_PORT_A = new BlockPos(10, WATER_Y, -56);
    private static final BlockPos PERSIST_PORT_B = new BlockPos(50, WATER_Y, -56);
    private static final BlockPos PERSIST_PORT_B_CHEST = PERSIST_PORT_B.east();

    private static final BlockPos PERSIST_AIR_A = new BlockPos(10, 64, -48);
    private static final BlockPos PERSIST_AIR_B = new BlockPos(50, 64, -48);
    private static final BlockPos PERSIST_AIR_B_CHEST = PERSIST_AIR_B.east();

    private boolean writeForced = false;
    private int writeForceTick = 0;
    private boolean writePlaced = false;
    private int writePlacedTick = 0;
    private boolean writeLaunched = false;
    private int writeLaunchTick = 0;
    private UUID boatId;
    private UUID droneId;

    private int readStartTick = -1;
    private boolean readDone = false;

    /** Runs write-phase setup + mid-flight launch + NBT round-trip + state dump before server halt. */
    public boolean tickWrite(ServerLevel level, int tick, QaChecks checks) {
        if (!writeForced) {
            QaChecks.setChunkRangeForced(level, 0, 4, -4, -3, true);
            writeForced = true;
            writeForceTick = tick;
            return false;
        }
        if (!writePlaced) {
            boolean chunksReady = Lc2Native.isChunkEntityTicking(level, PERSIST_PORT_A.getX() >> 4, PERSIST_PORT_A.getZ() >> 4)
                    && Lc2Native.isChunkEntityTicking(level, PERSIST_PORT_B.getX() >> 4, PERSIST_PORT_B.getZ() >> 4)
                    && Lc2Native.isChunkEntityTicking(level, PERSIST_AIR_A.getX() >> 4, PERSIST_AIR_A.getZ() >> 4)
                    && Lc2Native.isChunkEntityTicking(level, PERSIST_AIR_B.getX() >> 4, PERSIST_AIR_B.getZ() >> 4);
            if (!chunksReady) {
                if (tick - writeForceTick > 500) {
                    throw new AssertionError("Timeout waiting for Suite 7 chunks to become entity-ticking");
                }
                return false;
            }
            QaChecks.buildCanal(level, 11, 49, -56, -56, WATER_Y);

            level.setBlockAndUpdate(PERSIST_PORT_A,
                    QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(PERSIST_PORT_B,
                    QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(PERSIST_PORT_B_CHEST, Blocks.CHEST.defaultBlockState());

            var stBlock = QaChecks.block("lc2:cargo_station").defaultBlockState();
            level.setBlockAndUpdate(PERSIST_AIR_A, stBlock);
            level.setBlockAndUpdate(PERSIST_AIR_B, stBlock);
            level.setBlockAndUpdate(PERSIST_AIR_B_CHEST, Blocks.CHEST.defaultBlockState());

            writePlaced = true;
            writePlacedTick = tick;
            return false;
        }
        if (!writeLaunched) {
            if (tick - writePlacedTick < 2) {
                return false;
            }
            SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(PERSIST_PORT_A);
            SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PERSIST_PORT_B);
            portA.setStationName("Persist Port A");
            portB.setStationName("Persist Port B");
            portA.refreshWaterBerth(level);
            portB.refreshWaterBerth(level);

            var player = FakePlayerFactory.getMinecraft(level);
            checks.check("s7.write.boat_docked",
                    portA.spawnAndDockBoat(level, player, new ItemStack(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())));
            portA.setTargetPos(PERSIST_PORT_B);
            portA.setAutoReturn(false);
            portA.setEnergyPriority(EnergyPriority.EU_FIRST);
            portA.addEnergy(5000.0);
            for (int i = 0; i < 4; i++) {
                portA.setItem(i, new ItemStack(Items.DIAMOND_BLOCK, 5)); // 20 total in Outbound
            }
            // Place sentinel stack in Port A Storage (27) to prove boat NEVER ships Storage AND Storage persists across cold restart
            portA.setItem(SmartPortBlockEntity.STORAGE_START, new ItemStack(Items.NETHERITE_BLOCK, 3));

            CargoStationBlockEntity airA = (CargoStationBlockEntity) level.getBlockEntity(PERSIST_AIR_A);
            CargoStationBlockEntity airB = (CargoStationBlockEntity) level.getBlockEntity(PERSIST_AIR_B);
            airA.setStationName("Persist Air A");
            airB.setStationName("Persist Air B");
            airA.setItem(CargoStationBlockEntity.RF_CHIP_SLOT,
                    new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID), 1));
            airA.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                    new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));
            airA.setTargetPos(PERSIST_AIR_B);
            airA.setAutoReturn(false);
            airA.setEnergyPriority(EnergyPriority.EU_FIRST);
            airA.addEnergy(5000.0);
            for (int i = 0; i < 4; i++) {
                airA.setItem(i, new ItemStack(Items.EMERALD_BLOCK, 7)); // 28 total
            }

            var bRes = portA.tryDispatch(level, true);
            var dRes = airA.tryDispatch(level, true);
            checks.check("s7.write.midflight_vehicles_launched",
                    bRes.isSuccess() && dRes.isSuccess(),
                    "boatFail=" + bRes.failure() + ", droneFail=" + dRes.failure());
            boatId = bRes.transit().id();
            droneId = dRes.transit().id();
            writeLaunched = true;
            writeLaunchTick = tick;
            return false;
        }

        if (tick - writeLaunchTick < 15) {
            return false;
        }

        LogisticsSavedData liveData = LogisticsLevelManager.getData(level);
        TransitRecord boat = liveData.engine().getTransit(boatId).orElseThrow();
        TransitRecord drone = liveData.engine().getTransit(droneId).orElseThrow();
        checks.check("s7.write.vehicles_genuinely_midflight",
                boat.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && drone.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && boat.currentPos().x() > 12.0 && boat.currentPos().x() < 48.0,
                String.format("boatX=%.2f, drone=(%.2f,%.2f)", boat.currentPos().x(),
                        drone.currentPos().x(), drone.currentPos().y()));

        // Direct in-memory NBT round-trip check
        CompoundTag savedTag = liveData.saveToTag(level.registryAccess());
        LogisticsSavedData reloaded = LogisticsSavedData.loadFromTag(
                savedTag, level.registryAccess(), Lc2Native.dimensionId(level));
        TransitRecord rtBoat = reloaded.engine().getTransit(boatId).orElse(null);
        TransitRecord rtDrone = reloaded.engine().getTransit(droneId).orElse(null);
        int rtBoatDiamonds = reloaded.getCargo(boatId).stream()
                .filter(s -> s.is(Items.DIAMOND_BLOCK)).mapToInt(ItemStack::getCount).sum();
        int rtDroneEmeralds = reloaded.getCargo(droneId).stream()
                .filter(s -> s.is(Items.EMERALD_BLOCK)).mapToInt(ItemStack::getCount).sum();
        checks.check("s7.nbt.roundtrip_preserves_transits_cargo_stations",
                rtBoat != null && rtDrone != null
                        && rtBoat.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && rtDrone.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && rtBoatDiamonds == 20 && rtDroneEmeralds == 28
                        && reloaded.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_PORT_A)).isPresent()
                        && reloaded.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_AIR_B)).isPresent(),
                "rtDiamonds=" + rtBoatDiamonds + ", rtEmeralds=" + rtDroneEmeralds);

        // Record expected state for cold-restart read phase!
        Properties state = new Properties();
        state.setProperty("boatId", boatId.toString());
        state.setProperty("droneId", droneId.toString());
        state.setProperty("boatX", Double.toString(boat.currentPos().x()));
        state.setProperty("droneY", Double.toString(drone.currentPos().y()));
        try (var w = Files.newBufferedWriter(STATE_FILE)) {
            state.store(w, "LC2 cold restart persistence baseline");
        } catch (IOException e) {
            throw new AssertionError("Failed writing cold-restart state file", e);
        }
        return true;
    }

    /** Runs cold-restart read-phase startup assertions immediately after world load. */
    public void startRead(ServerLevel level, QaChecks checks) throws IOException {
        QaChecks.setChunkRangeForced(level, 0, 4, -4, -3, true);
        Properties state = new Properties();
        try (var r = Files.newBufferedReader(STATE_FILE)) {
            state.load(r);
        }
        boatId = UUID.fromString(state.getProperty("boatId"));
        droneId = UUID.fromString(state.getProperty("droneId"));
        double savedBoatX = Double.parseDouble(state.getProperty("boatX"));
        double savedDroneY = Double.parseDouble(state.getProperty("droneY"));

        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        TransitRecord boat = data.engine().getTransit(boatId).orElse(null);
        TransitRecord drone = data.engine().getTransit(droneId).orElse(null);
        int boatDiamonds = data.getCargo(boatId).stream()
                .filter(s -> s.is(Items.DIAMOND_BLOCK)).mapToInt(ItemStack::getCount).sum();
        int droneEmeralds = data.getCargo(droneId).stream()
                .filter(s -> s.is(Items.EMERALD_BLOCK)).mapToInt(ItemStack::getCount).sum();

        checks.check("s7.cold.stations_restored",
                data.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_PORT_A)).isPresent()
                        && data.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_PORT_B)).isPresent()
                        && data.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_AIR_A)).isPresent()
                        && data.stationRegistry().get(LogisticsSavedData.toPos3i(PERSIST_AIR_B)).isPresent());
        checks.check("s7.cold.midflight_transits_restored_with_exact_cargo",
                boat != null && drone != null
                        && boat.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && drone.phase() == TransitPhase.EN_ROUTE_OUTBOUND
                        && Math.abs(boat.currentPos().x() - savedBoatX) < 1.5
                        && Math.abs(drone.currentPos().y() - savedDroneY) < 2.0
                        && boatDiamonds == 20 && droneEmeralds == 28,
                String.format("boatX=%.2f(saved=%.2f), droneY=%.2f(saved=%.2f), cargo=%d/%d",
                        boat != null ? boat.currentPos().x() : -1, savedBoatX,
                        drone != null ? drone.currentPos().y() : -1, savedDroneY,
                        boatDiamonds, droneEmeralds));
    }

    /** Ticks read phase until restarted mid-flight boat and drone finish delivery. */
    public boolean tickRead(ServerLevel level, int tick, QaChecks checks) {
        if (readDone) {
            return true;
        }
        if (readStartTick < 0) {
            readStartTick = tick;
        }
        if (tick - readStartTick > 300) {
            throw new AssertionError("Timeout waiting for cold-restarted mid-flight transits to finish");
        }
        var engine = LogisticsLevelManager.getData(level).engine();
        if (engine.getTransit(boatId).isPresent() || engine.getTransit(droneId).isPresent()) {
            return false;
        }

        SmartPortBlockEntity portA = (SmartPortBlockEntity) level.getBlockEntity(PERSIST_PORT_A);
        SmartPortBlockEntity portB = (SmartPortBlockEntity) level.getBlockEntity(PERSIST_PORT_B);
        CargoStationBlockEntity airB = (CargoStationBlockEntity) level.getBlockEntity(PERSIST_AIR_B);
        var boatDelivered = QaChecks.countStorageItems(portB);
        var droneDelivered = QaChecks.countStorageItems(airB);
        var portAStorage = QaChecks.countStorageItems(portA);

        checks.check("s7.cold.restarted_boat_delivered_all_20_diamonds_to_storage",
                boatDelivered.getOrDefault("minecraft:diamond_block", 0) == 20
                        && QaChecks.countOutboundItems(portB).isEmpty()
                        && portAStorage.getOrDefault("minecraft:netherite_block", 0) == 3
                        && portB != null && portB.findDockedBoat(level).isPresent(),
                "deliveredStorage=" + boatDelivered + ", originStoragePreserved=" + portAStorage);
        checks.check("s7.cold.restarted_drone_delivered_all_28_emeralds_to_storage",
                droneDelivered.getOrDefault("minecraft:emerald_block", 0) == 28
                        && QaChecks.countOutboundItems(airB).isEmpty()
                        && airB != null && airB.countReadyDrones() == 1,
                "deliveredStorage=" + droneDelivered);

        readDone = true;
        return true;
    }
}
