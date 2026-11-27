package com.logisticscraft.lc2.world;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.Lc2WrenchHelper;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.core.WaterGrid;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.LogisticsChunkTickets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-side level coordinator driving {@link VirtualTransitEngine} every tick,
 * seamlessly materializing/syncing/dematerializing physical boats and drones across
 * loaded and unloaded chunks without losing cargo or rider state.
 */
public final class LogisticsLevelManager {

    private static final int BOOTSTRAP_CHUNK_LOADER_TTL_TICKS = 40;
    private static final Map<ServerLevel, Map<UUID, Entity>> LIVE_ENTITIES = new WeakHashMap<>();
    private static final Set<ServerLevel> BOOTSTRAPPED_LEVELS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<ServerLevel, Map<BlockPos3i, Integer>> BOOTSTRAP_LEASES = new WeakHashMap<>();

    private LogisticsLevelManager() {
    }

    /** Subscribes level tick and level unload listeners on the NeoForge game event bus. */
    public static void install(IEventBus gameBus) {
        gameBus.addListener(LevelTickEvent.Post.class, event -> {
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                tickLevel(serverLevel);
            }
        });
        gameBus.addListener(LevelEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                LIVE_ENTITIES.remove(serverLevel);
                BOOTSTRAPPED_LEVELS.remove(serverLevel);
                BOOTSTRAP_LEASES.remove(serverLevel);
                LogisticsChunkTickets.clearLevel(serverLevel);
            }
        });
        gameBus.addListener(
                EventPriority.LOW,
                false,
                PlayerInteractEvent.RightClickBlock.class,
                Lc2WrenchHelper::onRightClickBlock
        );
    }

    /** Returns the persistent {@link LogisticsSavedData} for {@code level}. */
    public static LogisticsSavedData getData(ServerLevel level) {
        return LogisticsSavedData.getOrCreate(level);
    }

    /** Associates a newly launched physical entity with its active transit UUID. */
    public static void bindLiveEntity(ServerLevel level, UUID transitId, Entity entity) {
        LIVE_ENTITIES.computeIfAbsent(level, ignored -> new HashMap<>()).put(transitId, entity);
    }

    /** Returns the currently materialized physical entity for {@code transitId}, if any. */
    public static Entity findLiveEntity(ServerLevel level, UUID transitId) {
        Map<UUID, Entity> map = LIVE_ENTITIES.get(level);
        if (map == null) {
            return null;
        }
        Entity entity = map.get(transitId);
        if (entity != null && entity.isRemoved()) {
            map.remove(transitId);
            return null;
        }
        return entity;
    }

    /** Returns the number of active (non-completed) transits in {@code level}. */
    public static int getActiveTransitCount(ServerLevel level) {
        return getData(level).engine().activeTransits().size();
    }

    /**
     * Cancels an active transit (e.g. if its physical entity was destroyed or its waiting station
     * was broken) and returns any cargo stacks still stored in {@link LogisticsSavedData}.
     */
    public static List<ItemStack> cancelAndTakeCargo(ServerLevel level, UUID transitId) {
        LogisticsSavedData data = getData(level);
        Optional<TransitRecord> existing = data.engine().getTransit(transitId);
        if (existing.isPresent()) {
            TransitRecord rec = existing.get();
            if (rec.vehicleKind().isDrone() && rec.phase() == TransitPhase.WAITING_RETURN_ENERGY) {
                BlockPos targetBlock = LogisticsSavedData.toBlockPos(rec.targetPos());
                if (level.hasChunkAt(targetBlock)
                        && level.getBlockEntity(targetBlock) instanceof CargoStationBlockEntity station) {
                    station.clearWaitingReturnBay(rec.id());
                }
            }
        }
        data.engine().cancelTransit(transitId);
        Map<UUID, Entity> map = LIVE_ENTITIES.get(level);
        if (map != null) {
            map.remove(transitId);
        }
        List<ItemStack> cargo = data.removeCargo(transitId);
        data.setDirty();
        return cargo;
    }

    private static void tickLevel(ServerLevel level) {
        LogisticsSavedData data = LogisticsSavedData.getOrCreate(level);
        tickBootstrapChunkLoaders(level, data);

        VirtualTransitEngine engine = data.engine();
        if (engine.activeTransits().isEmpty()) {
            return;
        }

        // 1. Sync rider state from any materialized boats (e.g. if player dismounted mid-voyage)
        for (TransitRecord rec : engine.activeTransits()) {
            if (rec.vehicleKind().isBoat()) {
                Entity live = findLiveEntity(level, rec.id());
                if (live instanceof ElectricCargoBoatEntity boat) {
                    boolean seated = !boat.getPassengers().isEmpty();
                    if (rec.hasRider() != seated) {
                        rec.setHasRider(seated);
                        data.setDirty();
                    }
                }
            }
        }

        WaterGrid loadedWater = LevelWaterGrid.loadedOnly(level);
        AirspaceRouter.HeightSampler heightSampler = LevelWaterGrid.heightSampler(level);

        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return Lc2Native.isChunkEntityTicking(level, chunkX, chunkZ);
            }

            @Override
            public boolean tryUnloadAtTarget(TransitRecord transit) {
                return handleUnloadAtTarget(level, data, transit);
            }

            @Override
            public boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost) {
                return handlePayReturnAtTarget(level, transit, returnCost);
            }

            @Override
            public WaterGrid waterGrid() {
                return loadedWater;
            }

            @Override
            public AirspaceRouter.HeightSampler heightSampler() {
                return heightSampler;
            }

            @Override
            public boolean isStationPresent(BlockPos3i pos, StationType expectedType) {
                BlockPos blockPos = LogisticsSavedData.toBlockPos(pos);
                if (!level.hasChunkAt(blockPos)) {
                    return true;
                }
                if (expectedType == StationType.SMART_PORT) {
                    return level.getBlockEntity(blockPos) instanceof SmartPortBlockEntity port && !port.isRemoved();
                }
                return level.getBlockEntity(blockPos) instanceof CargoStationBlockEntity station && !station.isRemoved();
            }
        };

        VirtualTransitEngine.TickReport report = engine.tick(bridge);

        // 2. Process return departures (release parked waiting drone from target roof bay)
        for (TransitRecord rec : report.returnDeparted()) {
            if (rec.vehicleKind().isDrone()) {
                BlockPos targetPos = LogisticsSavedData.toBlockPos(rec.targetPos());
                if (level.hasChunkAt(targetPos)
                        && level.getBlockEntity(targetPos) instanceof CargoStationBlockEntity station) {
                    station.clearWaitingReturnBay(rec.id());
                    station.setStatusKey("status.lc2.returning");
                }
            }
        }

        // 3. Process drones that entered WAITING_RETURN_ENERGY: dematerialize airborne entity & show parked on pad
        for (TransitRecord rec : engine.activeTransits()) {
            if (rec.vehicleKind().isDrone() && rec.phase() == TransitPhase.WAITING_RETURN_ENERGY) {
                dematerializeEntity(level, data, rec);
                BlockPos targetPos = LogisticsSavedData.toBlockPos(rec.targetPos());
                if (level.hasChunkAt(targetPos)
                        && level.getBlockEntity(targetPos) instanceof CargoStationBlockEntity station) {
                    station.setWaitingReturnBay(rec.targetBay(), rec.id(), rec.vehicleKind());
                    station.setStatusKey("status.lc2.waiting_return_energy");
                }
            }
        }

        // 4. Materialize entities entering loaded chunks (skipping waiting-return drones parked on roof)
        for (TransitRecord rec : report.toMaterialize()) {
            if (rec.vehicleKind().isDrone() && rec.phase() == TransitPhase.WAITING_RETURN_ENERGY) {
                continue;
            }
            ensureMaterialized(level, data, rec);
        }

        // 5. Sync materialized entity positions/velocities/yaw smoothly
        for (TransitRecord rec : report.toSync()) {
            if (rec.vehicleKind().isDrone() && rec.phase() == TransitPhase.WAITING_RETURN_ENERGY) {
                continue;
            }
            Entity entity = ensureMaterialized(level, data, rec);
            if (entity != null) {
                syncPhysicalEntity(entity, rec);
            }
        }

        // 6. Cleanly dematerialize entities leaving loaded chunks (without dropping cargo!)
        for (TransitRecord rec : report.toDematerialize()) {
            dematerializeEntity(level, data, rec);
        }

        // 7. Complete finished journeys (dock boat or park drone in final destination bay)
        for (TransitRecord rec : report.completed()) {
            handleCompletedTransit(level, data, rec);
        }

        if (report.dirty()) {
            data.setDirty();
        }
    }

    private static boolean handleUnloadAtTarget(
            ServerLevel level,
            LogisticsSavedData data,
            TransitRecord transit
    ) {
        BlockPos targetBlock = LogisticsSavedData.toBlockPos(transit.targetPos());
        List<ItemStack> cargo = data.getCargo(transit.id());

        // If physical boat is loaded, pull any live items from the boat's hold into cargo list first
        if (transit.vehicleKind().isBoat()) {
            Entity live = findLiveEntity(level, transit.id());
            if (live instanceof ElectricCargoBoatEntity boat) {
                for (int i = 0; i < boat.getContainerSize(); i++) {
                    ItemStack slotStack = boat.getItem(i);
                    if (!slotStack.isEmpty()) {
                        cargo.add(slotStack.copy());
                        boat.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
        }

        if (transit.vehicleKind().isBoat()) {
            if (!(level.getBlockEntity(targetBlock) instanceof SmartPortBlockEntity targetPort)) {
                // Target Smart Port was broken while boat was en route: keep cargo safely on boat and finish
                return true;
            }
            List<ItemStack> overflow = targetPort.receiveIncomingCargo(level, cargo);
            cargo.clear();
            cargo.addAll(overflow);
            data.setDirty();
            if (cargo.isEmpty()) {
                targetPort.setStatusKey("status.lc2.arrived");
                return true;
            }
            targetPort.setStatusKey("status.lc2.unloading");
            return false;
        } else {
            if (!(level.getBlockEntity(targetBlock) instanceof CargoStationBlockEntity targetStation)) {
                // Target Cargo Station was broken while drone was en route: drop cargo safely at target position
                for (ItemStack stack : cargo) {
                    if (!stack.isEmpty()) {
                        Containers.dropItemStack(level, targetBlock.getX() + 0.5, targetBlock.getY() + 1.0, targetBlock.getZ() + 0.5, stack);
                    }
                }
                cargo.clear();
                data.setDirty();
                return true;
            }
            List<ItemStack> overflow = targetStation.receiveIncomingCargo(level, cargo);
            cargo.clear();
            cargo.addAll(overflow);
            data.setDirty();
            if (cargo.isEmpty()) {
                targetStation.setStatusKey("status.lc2.arrived");
                return true;
            }
            targetStation.setStatusKey("status.lc2.unloading");
            return false;
        }
    }

    private static boolean handlePayReturnAtTarget(
            ServerLevel level,
            TransitRecord transit,
            EnergyCost returnCost
    ) {
        BlockPos targetBlock = LogisticsSavedData.toBlockPos(transit.targetPos());
        if (transit.vehicleKind().isDrone()) {
            if (level.getBlockEntity(targetBlock) instanceof CargoStationBlockEntity targetStation) {
                return targetStation.tryPayReturnEnergy(returnCost);
            }
            return false;
        }
        if (level.getBlockEntity(targetBlock) instanceof SmartPortBlockEntity targetPort) {
            return targetPort.tryPayReturnEnergy(returnCost);
        }
        return false;
    }

    private static void tickBootstrapChunkLoaders(ServerLevel level, LogisticsSavedData data) {
        if (BOOTSTRAPPED_LEVELS.add(level)) {
            Set<BlockPos3i> persisted = data.getChunkLoadedStations();
            if (!persisted.isEmpty()) {
                Map<BlockPos3i, Integer> pending = BOOTSTRAP_LEASES.computeIfAbsent(level, ignored -> new HashMap<>());
                for (BlockPos3i pos : persisted) {
                    LogisticsChunkTickets.holdArea3x3(level, new ChunkPos(pos.chunkX(), pos.chunkZ()));
                    pending.put(pos, BOOTSTRAP_CHUNK_LOADER_TTL_TICKS);
                }
            }
        }
        Map<BlockPos3i, Integer> pending = BOOTSTRAP_LEASES.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos3i, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos3i, Integer> entry = it.next();
            BlockPos3i pos = entry.getKey();
            int remaining = entry.getValue() - 1;
            ChunkPos center = new ChunkPos(pos.chunkX(), pos.chunkZ());
            boolean chunkTicking = Lc2Native.isChunkEntityTicking(level, pos.chunkX(), pos.chunkZ());
            boolean releaseNow = false;
            boolean keepPersisted = true;
            if (chunkTicking) {
                BlockPos bp = LogisticsSavedData.toBlockPos(pos);
                if (level.getBlockEntity(bp) instanceof AbstractLogisticsStationBlockEntity station
                        && !station.isRemoved()) {
                    if (station.isChunkLoaderActive()) {
                        // Station has re-acquired its own ref-counted lease; drop our bootstrap lease
                        releaseNow = true;
                        keepPersisted = true;
                    } else if (!station.getUpgrades().shouldChunkLoad(station.getStoredEu())) {
                        // Station lacks upgrade or EU; drop lease and clear persisted entry
                        releaseNow = true;
                        keepPersisted = false;
                    }
                } else {
                    // Station was removed offline
                    releaseNow = true;
                    keepPersisted = false;
                }
            }
            if (releaseNow || remaining <= 0) {
                LogisticsChunkTickets.releaseArea3x3(level, center);
                if (!keepPersisted) {
                    data.setChunkLoaderStation(pos, false);
                }
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        if (pending.isEmpty()) {
            BOOTSTRAP_LEASES.remove(level);
        }
    }

    private static void handleCompletedTransit(
            ServerLevel level,
            LogisticsSavedData data,
            TransitRecord rec
    ) {
        List<ItemStack> remainingCargo = data.removeCargo(rec.id());

        if (rec.vehicleKind().isBoat()) {
            BlockPos3i finalStationPos = rec.finalStationPos();
            Optional<StationEntry> station = data.stationRegistry().get(finalStationPos);
            Vec3d dockPos = station
                    .map(StationEntry::waterBerthPos)
                    .map(BlockPos3i::waterSurfaceCenter)
                    .orElse(rec.currentPos());

            Entity entity = ensureMaterialized(level, data, rec);
            if (entity instanceof ElectricCargoBoatEntity boat) {
                boat.setPos(dockPos.x(), dockPos.y(), dockPos.z());
                boat.setDeltaMovement(Vec3.ZERO);
                boat.setAutonomousTransit(false, null, 0);
                // Put any undelivered cargo (if target port was broken) back into the boat hold
                for (ItemStack stack : remainingCargo) {
                    ItemStack overflow = boat.insertCargoStack(stack);
                    if (!overflow.isEmpty()) {
                        Containers.dropItemStack(level, dockPos.x(), dockPos.y(), dockPos.z(), overflow);
                    }
                }
            }
            Map<UUID, Entity> map = LIVE_ENTITIES.get(level);
            if (map != null) {
                map.remove(rec.id());
            }
        } else {
            // Drone completed journey: dematerialize airborne entity and park drone item in final station bay
            dematerializeEntity(level, data, rec);
            BlockPos targetBlock = LogisticsSavedData.toBlockPos(rec.targetPos());
            if (level.hasChunkAt(targetBlock)
                    && level.getBlockEntity(targetBlock) instanceof CargoStationBlockEntity targetStation) {
                targetStation.clearWaitingReturnBay(rec.id());
            }
            BlockPos3i finalStationPos = rec.finalStationPos();
            int finalBay = rec.finalBay();
            BlockPos finalBlock = LogisticsSavedData.toBlockPos(finalStationPos);
            ItemStack droneStack = Lc2Native.stack(rec.vehicleKind().itemId(), 1);

            boolean parked = false;
            if (level.hasChunkAt(finalBlock)
                    && level.getBlockEntity(finalBlock) instanceof CargoStationBlockEntity station
                    && !station.isRemoved()) {
                parked = station.parkReturningOrArrivingDrone(finalBay, droneStack);
                if (parked) {
                    station.setStatusKey("status.lc2.docked");
                }
            }
            if (!parked && !droneStack.isEmpty()) {
                Vec3d p = rec.currentPos();
                Containers.dropItemStack(level, p.x(), p.y(), p.z(), droneStack);
            }
            for (ItemStack stack : remainingCargo) {
                if (!stack.isEmpty()) {
                    Vec3d p = rec.currentPos();
                    Containers.dropItemStack(level, p.x(), p.y(), p.z(), stack);
                }
            }
        }
    }

    private static Entity ensureMaterialized(
            ServerLevel level,
            LogisticsSavedData data,
            TransitRecord rec
    ) {
        Entity existing = findLiveEntity(level, rec.id());
        if (existing != null) {
            return existing;
        }

        // Rebind to an existing loaded entity matching transitId (e.g. after server restart)
        Vec3d pos = rec.currentPos();
        AABB searchBox = new AABB(
                pos.x() - 8.0, pos.y() - 8.0, pos.z() - 8.0,
                pos.x() + 8.0, pos.y() + 8.0, pos.z() + 8.0
        );
        if (rec.vehicleKind().isBoat()) {
            List<ElectricCargoBoatEntity> boats = level.getEntitiesOfClass(
                    ElectricCargoBoatEntity.class,
                    searchBox,
                    b -> rec.id().equals(b.getTransitId())
            );
            if (!boats.isEmpty()) {
                ElectricCargoBoatEntity found = boats.get(0);
                int gen = rec.advanceEntityGeneration();
                found.setAutonomousTransit(true, rec.id(), gen);
                bindLiveEntity(level, rec.id(), found);
                // Discard any stale duplicates found in the same box
                for (int i = 1; i < boats.size(); i++) {
                    boats.get(i).setDematerializing(true);
                    boats.get(i).discard();
                }
                data.setDirty();
                return found;
            }
            ElectricCargoBoatEntity spawned = new ElectricCargoBoatEntity(
                    Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                    level
            );
            spawned.setPos(pos.x(), pos.y(), pos.z());
            spawned.setYRot(rec.yaw());
            int gen = rec.advanceEntityGeneration();
            spawned.setAutonomousTransit(true, rec.id(), gen);
            if (level.addFreshEntity(spawned)) {
                bindLiveEntity(level, rec.id(), spawned);
                data.setDirty();
                return spawned;
            }
            return null;
        } else {
            List<CargoDroneEntity> drones = level.getEntitiesOfClass(
                    CargoDroneEntity.class,
                    searchBox,
                    d -> rec.id().equals(d.getTransitId())
            );
            if (!drones.isEmpty()) {
                CargoDroneEntity found = drones.get(0);
                found.configureTransit(rec.id(), rec.vehicleKind() == VehicleKind.HEAVY_DRONE, true);
                bindLiveEntity(level, rec.id(), found);
                return found;
            }
            CargoDroneEntity spawned = new CargoDroneEntity(
                    Lc2Registries.CARGO_DRONE_ENTITY.get(),
                    level
            );
            spawned.setPos(pos.x(), pos.y(), pos.z());
            spawned.setYRot(rec.yaw());
            spawned.configureTransit(rec.id(), rec.vehicleKind() == VehicleKind.HEAVY_DRONE, true);
            if (level.addFreshEntity(spawned)) {
                bindLiveEntity(level, rec.id(), spawned);
                return spawned;
            }
            return null;
        }
    }

    private static void syncPhysicalEntity(Entity entity, TransitRecord rec) {
        Vec3d target = rec.currentPos();
        double dx = target.x() - entity.getX();
        double dy = target.y() - entity.getY();
        double dz = target.z() - entity.getZ();
        entity.setYRot(rec.yaw());
        entity.setYHeadRot(rec.yaw());
        entity.setDeltaMovement(dx, dy, dz);
        entity.setPos(target.x(), target.y(), target.z());
        for (Entity passenger : entity.getPassengers()) {
            entity.positionRider(passenger);
        }
        Lc2Native.markEntityMoved(entity);
        if (entity instanceof ElectricCargoBoatEntity boat) {
            boat.setAutonomousTransit(true, rec.id(), rec.entityGeneration());
            boat.setPaddleState(true, true);
        } else if (entity instanceof CargoDroneEntity drone) {
            drone.setAirborne(rec.phase().isMoving());
        }
    }

    private static void dematerializeEntity(
            ServerLevel level,
            LogisticsSavedData data,
            TransitRecord rec
    ) {
        Map<UUID, Entity> map = LIVE_ENTITIES.get(level);
        Entity entity = map != null ? map.remove(rec.id()) : null;
        if (entity == null) {
            // Also check world near current position in case entity loaded from disk before tracking
            Vec3d pos = rec.currentPos();
            AABB box = new AABB(
                    pos.x() - 6.0, pos.y() - 6.0, pos.z() - 6.0,
                    pos.x() + 6.0, pos.y() + 6.0, pos.z() + 6.0
            );
            if (rec.vehicleKind().isBoat()) {
                var list = level.getEntitiesOfClass(
                        ElectricCargoBoatEntity.class, box, b -> rec.id().equals(b.getTransitId()));
                if (!list.isEmpty()) {
                    entity = list.get(0);
                }
            } else {
                var list = level.getEntitiesOfClass(
                        CargoDroneEntity.class, box, d -> rec.id().equals(d.getTransitId()));
                if (!list.isEmpty()) {
                    entity = list.get(0);
                }
            }
        }
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (entity instanceof ElectricCargoBoatEntity boat) {
            if (!boat.getPassengers().isEmpty()) {
                // Never dematerialize while carrying a seated player
                bindLiveEntity(level, rec.id(), boat);
                return;
            }
            // Capture any items left in the boat's physical hold before clean dematerialization
            List<ItemStack> cargo = data.getCargo(rec.id());
            for (int i = 0; i < boat.getContainerSize(); i++) {
                ItemStack s = boat.getItem(i);
                if (!s.isEmpty()) {
                    cargo.add(s.copy());
                    boat.setItem(i, ItemStack.EMPTY);
                }
            }
            boat.setDematerializing(true);
            boat.discard();
        } else if (entity instanceof CargoDroneEntity drone) {
            drone.setDematerializing(true);
            drone.discard();
        }
    }
}
