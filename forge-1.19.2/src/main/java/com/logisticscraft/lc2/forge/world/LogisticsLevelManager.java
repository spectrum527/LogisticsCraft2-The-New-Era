package com.logisticscraft.lc2.forge.world;

import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.EnergyCostCalculator;
import com.logisticscraft.lc2.core.PaymentPlan;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.core.WaterGrid;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.entity.CargoDroneEntity;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.util.LogisticsChunkTickets;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.ArrayList;
import java.util.Collection;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Server-side level coordinator driving {@link VirtualTransitEngine} every tick, managing
 * Create-train-style entity materialization/dematerialization across chunk boundaries,
 * water route caching/validation, unloading, auto-return, and clean station teardown.
 */
public final class LogisticsLevelManager {

    private static final int MAX_WATER_PROBED_CHUNKS = 256;
    private static final int BOOTSTRAP_CHUNK_LOADER_TICKS = 40;
    private static final double STALE_BOAT_DRIFT_SQ = 32.0 * 32.0;

    private static final Map<ServerLevel, Map<UUID, Entity>> LIVE_ENTITIES = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Integer>> BOOTSTRAP_CHUNK_LOADERS = new WeakHashMap<>();
    private static final Set<ServerLevel> BOOTSTRAPPED_LEVELS = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Forge event bus handler ticking every {@link ServerLevel} at phase {@code END}.
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        tickLevel(level);
    }

    /**
     * Cleans up per-level live entity cache and chunk ticket maps when a level unloads.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LIVE_ENTITIES.remove(level);
            BOOTSTRAP_CHUNK_LOADERS.remove(level);
            BOOTSTRAPPED_LEVELS.remove(level);
            LogisticsChunkTickets.clearLevel(level);
        }
    }

    /**
     * Hands off a server-restart bootstrap chunk-loader ticket lease once the station block entity
     * itself has ticked and acquired its own live ticket.
     */
    public static void markChunkLoaderTakenOver(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Integer> active = BOOTSTRAP_CHUNK_LOADERS.get(level);
        if (active == null) {
            return;
        }
        if (active.remove(pos) != null) {
            LogisticsChunkTickets.releaseArea(level, pos);
            if (active.isEmpty()) {
                BOOTSTRAP_CHUNK_LOADERS.remove(level);
            }
        }
    }

    private static void tickBootstrapChunkLoaders(ServerLevel level, LogisticsSavedData data) {
        if (BOOTSTRAPPED_LEVELS.add(level)) {
            Set<BlockPos3i> persisted = data.chunkLoaderStations();
            if (!persisted.isEmpty()) {
                Map<BlockPos, Integer> leases = BOOTSTRAP_CHUNK_LOADERS.computeIfAbsent(level, ignored -> new HashMap<>());
                for (BlockPos3i core : persisted) {
                    BlockPos mcPos = Lc2Coords.toMc(core).immutable();
                    if (!leases.containsKey(mcPos)) {
                        LogisticsChunkTickets.holdArea(level, mcPos);
                        leases.put(mcPos, BOOTSTRAP_CHUNK_LOADER_TICKS);
                    }
                }
            }
        }

        Map<BlockPos, Integer> leases = BOOTSTRAP_CHUNK_LOADERS.get(level);
        if (leases == null || leases.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> it = leases.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            int remaining = entry.getValue() - 1;
            boolean stationMissing = TerminalSessionService.isChunkLoadedAndTicking(level, pos)
                    && !(level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity);
            if (stationMissing) {
                data.setChunkLoaderStation(Lc2Coords.toCore(pos), false);
            }
            if (stationMissing || remaining <= 0) {
                LogisticsChunkTickets.releaseArea(level, pos);
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        if (leases.isEmpty()) {
            BOOTSTRAP_CHUNK_LOADERS.remove(level);
        }
    }

    /**
     * Returns the {@link LogisticsSavedData} for {@code level}.
     */
    public static LogisticsSavedData data(ServerLevel level) {
        return LogisticsSavedData.get(level);
    }

    /**
     * Returns the {@link StationRegistry} for {@code level}.
     */
    public static StationRegistry stations(ServerLevel level) {
        return LogisticsSavedData.get(level).stationRegistry();
    }

    /**
     * Returns the {@link VirtualTransitEngine} for {@code level}.
     */
    public static VirtualTransitEngine engine(ServerLevel level) {
        return LogisticsSavedData.get(level).transitEngine();
    }

    /**
     * Returns all currently active transits in {@code level}.
     */
    public static Collection<TransitRecord> activeTransits(ServerLevel level) {
        return LogisticsSavedData.get(level).transitEngine().activeTransits();
    }

    /**
     * Returns a loaded-chunk, ceiling-aware {@link AirspaceRouter.HeightSampler} for {@code level}.
     */
    public static AirspaceRouter.HeightSampler heightSampler(ServerLevel level) {
        boolean hasCeiling = level.dimensionType().hasCeiling();
        int ceilingLimit = hasCeiling
                ? Math.min(124, level.getMaxBuildHeight() - 4)
                : Integer.MAX_VALUE;
        return new AirspaceRouter.HeightSampler() {
            @Override
            public int highestSolidY(int x, int z) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    return AirspaceRouter.UNLOADED_COLUMN;
                }
                if (!hasCeiling) {
                    return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
                }
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                int minY = level.getMinBuildHeight();
                for (int y = ceilingLimit - 1; y >= minY; y--) {
                    cursor.set(x, y, z);
                    BlockState state = chunk.getBlockState(cursor);
                    if (!state.getCollisionShape(chunk, cursor).isEmpty()) {
                        return y + 1;
                    }
                }
                return minY;
            }

            @Override
            public int ceilingY() {
                return ceilingLimit;
            }
        };
    }

    /**
     * Resolves a water route between {@code originBerth} and {@code targetBerth}, validating
     * any cached route against currently loaded chunks (to detect newly built dams/obstacles)
     * and running bounded on-demand A* pathfinding when uncached or invalidated.
     */
    public static WaterPathResult resolveWaterRoute(
            ServerLevel level,
            BlockPos3i originBerth,
            BlockPos3i targetBerth
    ) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        List<Vec3d> cached = data.getCachedWaterRoute(originBerth, targetBerth);
        if (cached != null && isCachedRouteStillClearInLoadedChunks(level, originBerth, targetBerth, cached)) {
            return WaterPathResult.connected(List.of(), cached, 0);
        }
        if (cached != null) {
            data.invalidateWaterRoute(originBerth, targetBerth);
        }

        LevelWaterGrid grid = new LevelWaterGrid(level, true, MAX_WATER_PROBED_CHUNKS);
        WaterPathResult result = WaterPathfinder.findPath(grid, originBerth, targetBerth);
        if (result.connected()) {
            data.cacheWaterRoute(originBerth, targetBerth, result.waypoints());
        }
        return result;
    }

    /**
     * Binds an already-spawned docked {@link ElectricCargoBoatEntity} to a newly dispatched
     * boat {@link TransitRecord} so seated players ride along without respawning the entity.
     */
    public static void bindDispatchedBoat(
            ServerLevel level,
            TransitRecord record,
            ElectricCargoBoatEntity boat,
            List<ItemStack> cargo
    ) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        data.setTransitCargo(record.id(), cargo);
        int gen = record.advanceEntityGeneration();
        boat.loadCargoList(List.of());
        boat.bindToTransit(record.id(), gen, true);
        liveMap(level).put(record.id(), boat);
        data.setDirty();
    }

    /**
     * Materializes and binds a newly dispatched {@link CargoDroneEntity} for a drone transit.
     */
    public static void bindDispatchedDrone(
            ServerLevel level,
            TransitRecord record,
            List<ItemStack> cargo
    ) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        data.setTransitCargo(record.id(), cargo);
        CargoDroneEntity drone = spawnDroneEntity(level, record);
        liveMap(level).put(record.id(), drone);
        data.setDirty();
    }

    /**
     * Cancels an active transit (e.g. when its physical boat is destroyed by a player).
     */
    public static void cancelTransitAndDrop(ServerLevel level, UUID transitId, Vec3 dropPos, boolean dropCargo) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        List<ItemStack> cargo = data.removeTransitCargo(transitId);
        data.transitEngine().cancelTransit(transitId);
        liveMap(level).remove(transitId);
        if (dropCargo && !cargo.isEmpty()) {
            for (ItemStack stack : cargo) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, dropPos.x, dropPos.y, dropPos.z, stack.copy());
                }
            }
        }
        data.setDirty();
    }

    /**
     * Handles station block destruction: unregisters the station and safely recovers any
     * transits parked/waiting at that station so no items or drones are ever lost.
     */
    public static void onStationRemoved(ServerLevel level, BlockPos pos) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        BlockPos3i corePos = Lc2Coords.toCore(pos);
        data.setChunkLoaderStation(corePos, false);
        markChunkLoaderTakenOver(level, pos);
        data.stationRegistry().get(corePos).ifPresent(entry -> {
            if (entry.waterBerthPos() != null) {
                data.invalidateWaterRoutesForBerth(entry.waterBerthPos());
            }
        });
        data.stationRegistry().unregister(corePos);

        List<TransitRecord> affected = new ArrayList<>();
        for (TransitRecord rec : data.transitEngine().activeTransits()) {
            if (rec.targetPos().equals(corePos)
                    && (rec.phase() == TransitPhase.ARRIVED_UNLOADING
                    || rec.phase() == TransitPhase.WAITING_RETURN_ENERGY)) {
                affected.add(rec);
            }
        }
        Vec3 dropPos = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0);
        for (TransitRecord rec : affected) {
            Entity entity = liveMap(level).remove(rec.id());
            if (rec.vehicleKind().isDrone()) {
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
                ItemStack droneItem = new ItemStack(
                        rec.vehicleKind() == VehicleKind.HEAVY_DRONE
                                ? Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get()
                                : Lc2Registries.TRANSPORT_DRONE_ITEM.get()
                );
                Containers.dropItemStack(level, dropPos.x, dropPos.y, dropPos.z, droneItem);
                cancelTransitAndDrop(level, rec.id(), dropPos, true);
            } else {
                // Boat: detach from transit so it remains intact in the water with its cargo
                List<ItemStack> cargo = data.removeTransitCargo(rec.id());
                data.transitEngine().cancelTransit(rec.id());
                if (entity instanceof ElectricCargoBoatEntity boat && !boat.isRemoved()) {
                    boat.loadCargoList(cargo);
                    boat.clearTransit();
                } else {
                    for (ItemStack stack : cargo) {
                        if (!stack.isEmpty()) {
                            Containers.dropItemStack(level, dropPos.x, dropPos.y, dropPos.z, stack.copy());
                        }
                    }
                }
            }
        }
        data.setDirty();
    }

    /**
     * Returns whether {@code transitId} is currently tracked as active in {@code level}.
     */
    public static boolean isTransitActive(ServerLevel level, UUID transitId) {
        return transitId != null && LogisticsSavedData.get(level).transitEngine().getTransit(transitId).isPresent();
    }

    /**
     * Returns {@code true} if {@code boat} carries a transit ID that no longer matches the live
     * authoritative entity for an active transit (completed transit, stale generation, duplicate
     * entity, or drifted ghost reloaded from an unloaded chunk).
     */
    public static boolean isStaleAutonomousBoat(ServerLevel level, ElectricCargoBoatEntity boat) {
        UUID transitId = boat.getTransitId();
        if (transitId == null) {
            return false;
        }
        Optional<TransitRecord> recOpt = LogisticsSavedData.get(level).transitEngine().getTransit(transitId);
        if (recOpt.isEmpty()) {
            return true;
        }
        TransitRecord rec = recOpt.get();
        if (boat.getTransitGeneration() != rec.entityGeneration()) {
            return true;
        }
        Entity live = liveMap(level).get(transitId);
        if (live != null && live != boat && !live.isRemoved()) {
            return true;
        }
        Vec3d expected = rec.currentPos();
        double dx = boat.getX() - expected.x();
        double dz = boat.getZ() - expected.z();
        return (dx * dx + dz * dz) > STALE_BOAT_DRIFT_SQ;
    }

    /**
     * Returns the number of drones currently waiting in {@link TransitPhase#WAITING_RETURN_ENERGY}
     * at {@code targetStationPos}.
     */
    public static int countWaitingReturnAt(ServerLevel level, BlockPos targetStationPos) {
        BlockPos3i core = Lc2Coords.toCore(targetStationPos);
        int count = 0;
        for (TransitRecord rec : LogisticsSavedData.get(level).transitEngine().activeTransits()) {
            if (rec.phase() == TransitPhase.WAITING_RETURN_ENERGY && rec.targetPos().equals(core)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Advances the virtual transit engine for {@code level} by one tick and synchronizes
     * all physical entities, arrivals, return takeoffs, and completions.
     */
    public static void tickLevel(ServerLevel level) {
        LogisticsSavedData data = LogisticsSavedData.get(level);
        tickBootstrapChunkLoaders(level, data);

        VirtualTransitEngine engine = data.transitEngine();
        if (engine.activeTransits().isEmpty()) {
            return;
        }

        // Before ticking, refresh rider flags on any materialized boats
        Map<UUID, Entity> live = liveMap(level);
        for (TransitRecord record : engine.activeTransits()) {
            if (record.vehicleKind().isBoat()) {
                Entity ent = live.get(record.id());
                if (ent instanceof ElectricCargoBoatEntity boat && !boat.isRemoved()) {
                    record.setHasRider(!boat.getPassengers().isEmpty());
                } else if (ent != null && ent.isRemoved()) {
                    live.remove(record.id());
                    record.setHasRider(false);
                }
            }
        }

        LevelWaterGrid loadedWaterGrid = new LevelWaterGrid(level, false, 0);
        AirspaceRouter.HeightSampler sampler = heightSampler(level);

        VirtualTransitEngine.TransitWorldBridge bridge = new VirtualTransitEngine.TransitWorldBridge() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null
                        && level.isPositionEntityTicking(new BlockPos((chunkX << 4) + 8, level.getSeaLevel(), (chunkZ << 4) + 8));
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
            public boolean isStationPresent(BlockPos3i pos, StationType expectedType) {
                if (data.stationRegistry().get(pos).filter(e -> e.type() == expectedType).isEmpty()) {
                    return false;
                }
                BlockPos mc = Lc2Coords.toMc(pos);
                if (!level.isLoaded(mc)) {
                    return true;
                }
                BlockEntity be = level.getBlockEntity(mc);
                return expectedType == StationType.SMART_PORT
                        ? be instanceof SmartPortBlockEntity
                        : be instanceof CargoStationBlockEntity;
            }

            @Override
            public WaterGrid waterGrid() {
                return loadedWaterGrid;
            }

            @Override
            public AirspaceRouter.HeightSampler heightSampler() {
                return sampler;
            }
        };

        VirtualTransitEngine.TickReport report = engine.tick(bridge);

        for (TransitRecord record : report.toMaterialize()) {
            handleMaterialize(level, data, record);
        }
        for (TransitRecord record : report.toDematerialize()) {
            handleDematerialize(level, data, record);
        }
        for (TransitRecord record : report.returnDeparted()) {
            handleReturnDeparted(level, record);
        }
        for (TransitRecord record : report.toSync()) {
            handleSync(level, data, record);
        }
        for (TransitRecord record : report.completed()) {
            handleCompleted(level, data, record);
        }

        // Update waiting status accurately when a drone is waiting on a full origin station bay
        for (TransitRecord record : engine.activeTransits()) {
            if (record.phase() == TransitPhase.WAITING_RETURN_ENERGY && record.vehicleKind().isDrone()) {
                BlockPos targetMc = Lc2Coords.toMc(record.targetPos());
                if (level.isLoaded(targetMc)
                        && level.getBlockEntity(targetMc) instanceof CargoStationBlockEntity targetStation) {
                    if (data.stationRegistry().freeBays(record.originPos()) <= 0) {
                        targetStation.setStatusKey("status.lc2.waiting_return_bay");
                    }
                }
            }
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
        BlockPos targetMc = Lc2Coords.toMc(transit.targetPos());
        BlockEntity be = level.getBlockEntity(targetMc);
        List<ItemStack> cargo = data.getTransitCargo(transit.id());

        if (!(be instanceof AbstractLogisticsStationBlockEntity station)) {
            // Target block missing: drop cargo safely at target position so items are never lost
            Vec3 dropPos = Lc2Coords.toMc(transit.currentPos());
            for (ItemStack stack : cargo) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, dropPos.x, dropPos.y, dropPos.z, stack.copy());
                }
            }
            data.setTransitCargo(transit.id(), List.of());
            syncBoatCargoIfLoaded(level, transit.id(), List.of());
            return true;
        }

        if (cargo.isEmpty()) {
            station.setStatusKey(transit.autoReturn() && transit.vehicleKind().isDrone()
                    ? "status.lc2.waiting_return_energy"
                    : "status.lc2.arrived");
            return true;
        }

        List<ItemStack> remaining = station.unloadIncomingCargo(level, cargo);
        data.setTransitCargo(transit.id(), remaining);
        syncBoatCargoIfLoaded(level, transit.id(), remaining);

        if (remaining.isEmpty()) {
            station.setStatusKey(transit.autoReturn() && transit.vehicleKind().isDrone()
                    ? "status.lc2.waiting_return_energy"
                    : "status.lc2.arrived");
            return true;
        } else {
            station.setStatusKey("status.lc2.unloading");
            return false;
        }
    }

    private static boolean handlePayReturnAtTarget(
            ServerLevel level,
            TransitRecord transit,
            EnergyCost returnCost
    ) {
        BlockPos targetMc = Lc2Coords.toMc(transit.targetPos());
        BlockEntity be = level.getBlockEntity(targetMc);
        if (!(be instanceof AbstractLogisticsStationBlockEntity station)) {
            return false;
        }
        PaymentPlan plan = EnergyCostCalculator.resolvePayment(
                returnCost,
                station.getStoredEu(),
                station.getBatteryCount(),
                station.getEnergyPriority()
        );
        if (!plan.isPayable()) {
            station.setStatusKey("status.lc2.waiting_return_energy");
            return false;
        }
        station.debitPayment(plan);
        station.setStatusKey("status.lc2.returning");
        return true;
    }

    private static void handleMaterialize(ServerLevel level, LogisticsSavedData data, TransitRecord record) {
        Map<UUID, Entity> live = liveMap(level);
        Entity existing = live.get(record.id());
        if (existing != null && !existing.isRemoved()) {
            return;
        }

        Vec3 pos = Lc2Coords.toMc(record.currentPos());
        AABB search = AABB.ofSize(pos, 12.0, 12.0, 12.0);

        if (record.vehicleKind().isBoat()) {
            ElectricCargoBoatEntity reused = null;
            for (ElectricCargoBoatEntity found : level.getEntitiesOfClass(ElectricCargoBoatEntity.class, search)) {
                if (record.id().equals(found.getTransitId())) {
                    if (reused == null) {
                        reused = found;
                    } else {
                        found.discardWithoutDropping();
                    }
                }
            }
            int gen = record.advanceEntityGeneration();
            if (reused != null) {
                reused.loadCargoList(List.of());
                reused.bindToTransit(record.id(), gen, record.phase().isMoving());
                live.put(record.id(), reused);
                data.setDirty();
                return;
            }
            ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
            boat.setPos(pos.x, pos.y, pos.z);
            boat.setYRot(record.yaw());
            boat.yRotO = record.yaw();
            boat.bindToTransit(record.id(), gen, record.phase().isMoving());
            level.addFreshEntity(boat);
            live.put(record.id(), boat);
            data.setDirty();
        } else {
            for (CargoDroneEntity found : level.getEntitiesOfClass(CargoDroneEntity.class, search)) {
                if (record.id().equals(found.getTransitId())) {
                    found.bindToTransit(record.id(), record.vehicleKind() == VehicleKind.HEAVY_DRONE, record.phase().isMoving());
                    live.put(record.id(), found);
                    return;
                }
            }
            CargoDroneEntity drone = spawnDroneEntity(level, record);
            live.put(record.id(), drone);
        }
    }

    private static void handleDematerialize(ServerLevel level, LogisticsSavedData data, TransitRecord record) {
        Entity entity = liveMap(level).remove(record.id());
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (entity instanceof ElectricCargoBoatEntity boat) {
            if (!boat.getPassengers().isEmpty()) {
                // Keep boat alive while carrying a seated player
                liveMap(level).put(record.id(), boat);
                return;
            }
            // Autonomous boats keep cargo solely in LogisticsSavedData until docking
            boat.discardWithoutDropping();
        } else {
            entity.discard();
        }
    }

    private static void handleReturnDeparted(ServerLevel level, TransitRecord record) {
        Entity entity = liveMap(level).get(record.id());
        if (entity instanceof CargoDroneEntity drone && !drone.isRemoved()) {
            drone.setAirborne(true);
        } else if (entity instanceof ElectricCargoBoatEntity boat && !boat.isRemoved()) {
            boat.setAutonomousTransit(true);
        }
    }

    private static void handleSync(ServerLevel level, LogisticsSavedData data, TransitRecord record) {
        Map<UUID, Entity> live = liveMap(level);
        Entity entity = live.get(record.id());
        if (entity == null || entity.isRemoved()) {
            handleMaterialize(level, data, record);
            entity = live.get(record.id());
        }
        if (entity == null) {
            return;
        }
        Vec3 pos = Lc2Coords.toMc(record.currentPos());
        if (entity instanceof ElectricCargoBoatEntity boat) {
            boat.setAutonomousTransit(record.phase().isMoving());
            boat.syncFromTransit(pos, record.yaw());
            record.setHasRider(!boat.getPassengers().isEmpty());
        } else if (entity instanceof CargoDroneEntity drone) {
            drone.setAirborne(record.phase().isMoving());
            drone.syncFromTransit(pos, record.yaw());
        }
    }

    private static void handleCompleted(ServerLevel level, LogisticsSavedData data, TransitRecord record) {
        BlockPos3i finalStationPos = record.finalStationPos();
        int finalBay = record.finalBay();
        List<ItemStack> remainingCargo = data.removeTransitCargo(record.id());
        Entity entity = liveMap(level).remove(record.id());

        BlockPos finalMc = Lc2Coords.toMc(finalStationPos);
        BlockEntity be = level.getBlockEntity(finalMc);

        if (record.vehicleKind().isBoat()) {
            Vec3 dockPos = Lc2Coords.toMc(record.currentPos());
            ElectricCargoBoatEntity boat = null;
            if (entity instanceof ElectricCargoBoatEntity existing && !existing.isRemoved()) {
                boat = existing;
            } else {
                // Re-materialize boat cleanly at the final Smart Port berth
                boat = new ElectricCargoBoatEntity(Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
                boat.setPos(dockPos.x, dockPos.y, dockPos.z);
                boat.setYRot(record.yaw());
                boat.yRotO = record.yaw();
                level.addFreshEntity(boat);
            }
            boat.loadCargoList(remainingCargo);
            if (remainingCargo.size() > boat.getCargoContainer().getContainerSize()) {
                dropItems(level, dockPos.add(0.0, 0.5, 0.0),
                        remainingCargo.subList(boat.getCargoContainer().getContainerSize(), remainingCargo.size()));
            }
            boat.setPos(dockPos.x, dockPos.y, dockPos.z);
            boat.setYRot(record.yaw());
            boat.setDeltaMovement(Vec3.ZERO);
            boat.clearTransit();

            if (be instanceof SmartPortBlockEntity port) {
                port.onBoatDocked();
            }
        } else {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            if (be instanceof CargoStationBlockEntity station) {
                station.dockReturnedDrone(level, record.vehicleKind(), finalBay);
                if (!remainingCargo.isEmpty()) {
                    List<ItemStack> leftover = station.unloadIncomingCargo(level, remainingCargo);
                    dropItems(level, Vec3.atCenterOf(finalMc).add(0.0, 1.2, 0.0), leftover);
                }
            } else {
                // Station gone: drop drone item + any remaining cargo safely
                Vec3 dropPos = Vec3.atCenterOf(finalMc).add(0.0, 1.1, 0.0);
                ItemStack droneStack = new ItemStack(
                        record.vehicleKind() == VehicleKind.HEAVY_DRONE
                                ? Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get()
                                : Lc2Registries.TRANSPORT_DRONE_ITEM.get()
                );
                Containers.dropItemStack(level, dropPos.x, dropPos.y, dropPos.z, droneStack);
                dropItems(level, dropPos, remainingCargo);
            }
        }
    }

    private static CargoDroneEntity spawnDroneEntity(ServerLevel level, TransitRecord record) {
        Vec3 pos = Lc2Coords.toMc(record.currentPos());
        CargoDroneEntity drone = new CargoDroneEntity(Lc2Registries.CARGO_DRONE_ENTITY.get(), level);
        drone.setPos(pos.x, pos.y, pos.z);
        drone.setYRot(record.yaw());
        drone.yRotO = record.yaw();
        drone.bindToTransit(record.id(), record.vehicleKind() == VehicleKind.HEAVY_DRONE, record.phase().isMoving());
        level.addFreshEntity(drone);
        return drone;
    }

    private static void syncBoatCargoIfLoaded(ServerLevel level, UUID transitId, List<ItemStack> cargo) {
        // Autonomous boats keep cargo strictly in LogisticsSavedData until docking to prevent chunk-reload dupes
    }

    /**
     * Checks whether a cached water route between {@code originBerth} and {@code targetBerth}
     * remains unobstructed across all currently loaded chunks (never forces unloaded chunks).
     */
    public static boolean isCachedRouteStillClearInLoadedChunks(
            ServerLevel level,
            BlockPos3i originBerth,
            BlockPos3i targetBerth,
            List<Vec3d> waypoints
    ) {
        if (waypoints.isEmpty()) {
            return false;
        }
        LevelWaterGrid loadedGrid = new LevelWaterGrid(level, false, 0);
        if (level.getChunkSource().getChunkNow(originBerth.chunkX(), originBerth.chunkZ()) != null
                && !loadedGrid.isBoatPassable(originBerth)) {
            return false;
        }
        if (level.getChunkSource().getChunkNow(targetBerth.chunkX(), targetBerth.chunkZ()) != null
                && !loadedGrid.isBoatPassable(targetBerth)) {
            return false;
        }

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            double dist = a.distanceTo(b);
            int steps = Math.max(1, (int) Math.ceil(dist * 2.0));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                Vec3d sample = a.lerp(b, t);
                int bx = (int) Math.floor(sample.x());
                int by = (int) Math.floor(sample.y() - 0.5);
                int bz = (int) Math.floor(sample.z());
                if (level.getChunkSource().getChunkNow(bx >> 4, bz >> 4) != null) {
                    if (!loadedGrid.isBoatPassable(bx, by, bz)
                            && !loadedGrid.isBoatPassable(bx, by + 1, bz)
                            && !loadedGrid.isBoatPassable(bx, by - 1, bz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void dropItems(ServerLevel level, Vec3 pos, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.x, pos.y, pos.z, stack.copy());
            }
        }
    }

    private static Map<UUID, Entity> liveMap(ServerLevel level) {
        return LIVE_ENTITIES.computeIfAbsent(level, ignored -> new HashMap<>());
    }
}
