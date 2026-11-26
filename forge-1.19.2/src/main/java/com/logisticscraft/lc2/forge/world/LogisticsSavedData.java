package com.logisticscraft.lc2.forge.world;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitSnapshot;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension {@link SavedData} persisting the {@link StationRegistry}, all active
 * {@link VirtualTransitEngine} transits, full serialized {@link ItemStack} cargo payloads per
 * transit UUID, and cached water navigation routes across server restarts and chunk unloads.
 */
public final class LogisticsSavedData extends SavedData {

    /** SavedData storage filename inside each dimension's {@code data/} directory. */
    public static final String DATA_NAME = "lc2_logistics_data";

    private static final int MAX_CACHED_ROUTES = 512;

    /**
     * Cache key identifying a directed water route between two Smart Port water berths.
     */
    public record WaterRouteKey(BlockPos3i originBerth, BlockPos3i targetBerth) {
    }

    private final StationRegistry stationRegistry;
    private final VirtualTransitEngine transitEngine;
    private final Map<UUID, List<ItemStack>> transitCargo = new HashMap<>();
    private final Map<WaterRouteKey, List<Vec3d>> cachedWaterRoutes = new LinkedHashMap<>();
    private final Set<BlockPos3i> chunkLoadedStations = new LinkedHashSet<>();

    /**
     * Creates a fresh {@link LogisticsSavedData} bound to {@code dimensionId}.
     */
    public LogisticsSavedData(String dimensionId) {
        this.stationRegistry = new StationRegistry(dimensionId);
        this.transitEngine = new VirtualTransitEngine(this.stationRegistry);
    }

    /**
     * Fetches or creates the {@link LogisticsSavedData} instance for {@code level}.
     */
    public static LogisticsSavedData get(ServerLevel level) {
        String dimId = level.dimension().location().toString();
        return level.getDataStorage().computeIfAbsent(
                tag -> load(dimId, tag),
                () -> new LogisticsSavedData(dimId),
                DATA_NAME
        );
    }

    /** Returns this dimension's {@link StationRegistry}. */
    public StationRegistry stationRegistry() {
        return stationRegistry;
    }

    /** Returns this dimension's {@link VirtualTransitEngine}. */
    public VirtualTransitEngine transitEngine() {
        return transitEngine;
    }

    /**
     * Stores a defensive copy of the cargo item list carried by {@code transitId}.
     */
    public void setTransitCargo(UUID transitId, List<ItemStack> cargo) {
        Objects.requireNonNull(transitId, "transitId");
        List<ItemStack> copy = new ArrayList<>();
        if (cargo != null) {
            for (ItemStack stack : cargo) {
                if (stack != null && !stack.isEmpty()) {
                    copy.add(stack.copy());
                }
            }
        }
        transitCargo.put(transitId, copy);
        setDirty();
    }

    /**
     * Returns a copy of the cargo items currently held for {@code transitId}.
     */
    public List<ItemStack> getTransitCargo(UUID transitId) {
        List<ItemStack> stored = transitCargo.get(transitId);
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copy = new ArrayList<>(stored.size());
        for (ItemStack stack : stored) {
            if (!stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        return copy;
    }

    /**
     * Removes and returns the cargo list associated with {@code transitId}.
     */
    public List<ItemStack> removeTransitCargo(UUID transitId) {
        List<ItemStack> removed = transitCargo.remove(transitId);
        if (removed != null) {
            setDirty();
            return removed;
        }
        return List.of();
    }

    /**
     * Looks up a cached water route between {@code originBerth} and {@code targetBerth},
     * automatically reversing a cached opposite-direction route when present.
     */
    public List<Vec3d> getCachedWaterRoute(BlockPos3i originBerth, BlockPos3i targetBerth) {
        List<Vec3d> direct = cachedWaterRoutes.get(new WaterRouteKey(originBerth, targetBerth));
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        List<Vec3d> reverse = cachedWaterRoutes.get(new WaterRouteKey(targetBerth, originBerth));
        if (reverse != null && !reverse.isEmpty()) {
            List<Vec3d> flipped = new ArrayList<>(reverse);
            Collections.reverse(flipped);
            return List.copyOf(flipped);
        }
        return null;
    }

    /**
     * Caches a validated water route between {@code originBerth} and {@code targetBerth}.
     */
    public void cacheWaterRoute(BlockPos3i originBerth, BlockPos3i targetBerth, List<Vec3d> waypoints) {
        if (originBerth == null || targetBerth == null || waypoints == null || waypoints.isEmpty()) {
            return;
        }
        if (cachedWaterRoutes.size() >= MAX_CACHED_ROUTES) {
            WaterRouteKey oldest = cachedWaterRoutes.keySet().iterator().next();
            cachedWaterRoutes.remove(oldest);
        }
        cachedWaterRoutes.put(new WaterRouteKey(originBerth, targetBerth), List.copyOf(waypoints));
        setDirty();
    }

    /**
     * Invalidates any cached water route between {@code originBerth} and {@code targetBerth} in either direction.
     */
    public void invalidateWaterRoute(BlockPos3i originBerth, BlockPos3i targetBerth) {
        boolean removed = cachedWaterRoutes.remove(new WaterRouteKey(originBerth, targetBerth)) != null;
        removed |= cachedWaterRoutes.remove(new WaterRouteKey(targetBerth, originBerth)) != null;
        if (removed) {
            setDirty();
        }
    }

    /**
     * Invalidates all cached water routes touching {@code berth}.
     */
    public void invalidateWaterRoutesForBerth(BlockPos3i berth) {
        if (berth == null) {
            return;
        }
        boolean changed = cachedWaterRoutes.keySet().removeIf(
                key -> key.originBerth().equals(berth) || key.targetBerth().equals(berth)
        );
        if (changed) {
            setDirty();
        }
    }

    /**
     * Records or clears whether the station at {@code pos} has an active chunk-loading upgrade so
     * its 3x3 region tickets can be restored on server restart.
     */
    public void setChunkLoaderStation(BlockPos3i pos, boolean active) {
        if (pos == null) {
            return;
        }
        boolean changed = active ? chunkLoadedStations.add(pos) : chunkLoadedStations.remove(pos);
        if (changed) {
            setDirty();
        }
    }

    /** Returns an immutable snapshot of persisted chunk-loading station positions in this dimension. */
    public Set<BlockPos3i> chunkLoaderStations() {
        return Set.copyOf(chunkLoadedStations);
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        // 1. Stations
        ListTag stationList = new ListTag();
        for (StationEntry entry : stationRegistry.snapshot()) {
            CompoundTag st = new CompoundTag();
            writeBlockPos(st, "Pos", entry.pos());
            st.putString("Type", entry.type().name());
            st.putString("Name", entry.customName());
            if (entry.waterBerthPos() != null) {
                writeBlockPos(st, "Berth", entry.waterBerthPos());
            }
            st.putInt("MaxBays", entry.maxDroneBays());
            st.putInt("DockedDrones", entry.dockedDrones());
            st.putInt("DockedMask", entry.dockedBayMask());
            st.putInt("ReservedInbound", entry.reservedInboundDrones());
            stationList.add(st);
        }
        root.put("Stations", stationList);

        // 2. Active transits + cargo payloads
        ListTag transitList = new ListTag();
        for (TransitSnapshot snap : transitEngine.captureSnapshots()) {
            CompoundTag tr = new CompoundTag();
            tr.putUUID("Id", snap.id());
            tr.putString("Kind", snap.vehicleKind().name());
            writeBlockPos(tr, "Origin", snap.originPos());
            writeBlockPos(tr, "Target", snap.targetPos());
            tr.putInt("OriginBay", snap.originBay());
            tr.putInt("TargetBay", snap.targetBay());
            tr.putBoolean("AutoReturn", snap.autoReturn());
            tr.putBoolean("ReturnPrepaid", snap.returnPrepaid());
            tr.put("Waypoints", writeVecList(snap.waypoints()));
            tr.putInt("WpIndex", snap.waypointIndex());
            tr.putDouble("SegProgress", snap.segmentProgress());
            writeVec(tr, "NominalPos", snap.nominalPos());
            writeVec(tr, "CurrentPos", snap.currentPos());
            tr.putFloat("Yaw", snap.yaw());
            tr.putDouble("BaseSpeed", snap.baseSpeed());
            tr.putString("Phase", snap.phase().name());
            tr.putBoolean("HasRider", snap.hasRider());
            tr.putInt("TicksWaiting", snap.ticksWaiting());
            tr.putDouble("VertAvoid", snap.verticalAvoidanceOffset());
            tr.putDouble("LatStarboard", snap.lateralStarboardOffset());
            tr.putInt("LaneSlot", snap.laneSlot());
            tr.putInt("MaxObstacleY", snap.maxObstacleY());
            tr.putInt("EntityGen", snap.entityGeneration());

            List<ItemStack> cargo = transitCargo.get(snap.id());
            if (cargo != null && !cargo.isEmpty()) {
                ListTag cargoList = new ListTag();
                for (ItemStack stack : cargo) {
                    if (!stack.isEmpty()) {
                        cargoList.add(stack.save(new CompoundTag()));
                    }
                }
                tr.put("Cargo", cargoList);
            }
            transitList.add(tr);
        }
        root.put("Transits", transitList);

        // 3. Cached water routes
        ListTag routeList = new ListTag();
        for (Map.Entry<WaterRouteKey, List<Vec3d>> entry : cachedWaterRoutes.entrySet()) {
            CompoundTag rt = new CompoundTag();
            writeBlockPos(rt, "OriginBerth", entry.getKey().originBerth());
            writeBlockPos(rt, "TargetBerth", entry.getKey().targetBerth());
            rt.put("Waypoints", writeVecList(entry.getValue()));
            routeList.add(rt);
        }
        root.put("WaterRoutes", routeList);

        // 4. Persisted chunk-loading stations
        ListTag loaderList = new ListTag();
        for (BlockPos3i pos : chunkLoadedStations) {
            CompoundTag cl = new CompoundTag();
            cl.putInt("X", pos.x());
            cl.putInt("Y", pos.y());
            cl.putInt("Z", pos.z());
            loaderList.add(cl);
        }
        root.put("ChunkLoaders", loaderList);

        return root;
    }

    private static LogisticsSavedData load(String dimensionId, CompoundTag root) {
        LogisticsSavedData data = new LogisticsSavedData(dimensionId);

        // 1. Stations
        List<StationEntry> stations = new ArrayList<>();
        ListTag stationList = root.getList("Stations", Tag.TAG_COMPOUND);
        for (int i = 0; i < stationList.size(); i++) {
            CompoundTag st = stationList.getCompound(i);
            try {
                BlockPos3i pos = readBlockPos(st, "Pos");
                StationType type = StationType.valueOf(st.getString("Type"));
                String name = st.getString("Name");
                BlockPos3i berth = st.contains("Berth", Tag.TAG_COMPOUND) ? readBlockPos(st, "Berth") : null;
                int maxBays = st.contains("MaxBays") ? st.getInt("MaxBays") : type.maxDroneBays();
                int docked = Math.max(0, st.getInt("DockedDrones"));
                int reserved = Math.max(0, st.getInt("ReservedInbound"));
                if (st.contains("DockedMask", Tag.TAG_ANY_NUMERIC)) {
                    int dockedMask = st.getInt("DockedMask");
                    stations.add(new StationEntry(pos, type, name, berth, maxBays, docked, reserved, dockedMask));
                } else {
                    stations.add(new StationEntry(pos, type, name, berth, maxBays, docked, reserved));
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entry gracefully
            }
        }
        data.stationRegistry.loadSnapshot(stations);

        // 2. Transits + cargo
        List<TransitSnapshot> snapshots = new ArrayList<>();
        ListTag transitList = root.getList("Transits", Tag.TAG_COMPOUND);
        for (int i = 0; i < transitList.size(); i++) {
            CompoundTag tr = transitList.getCompound(i);
            try {
                UUID id = tr.getUUID("Id");
                VehicleKind kind = VehicleKind.valueOf(tr.getString("Kind"));
                BlockPos3i origin = readBlockPos(tr, "Origin");
                BlockPos3i target = readBlockPos(tr, "Target");
                int originBay = tr.getInt("OriginBay");
                int targetBay = tr.getInt("TargetBay");
                boolean autoReturn = tr.getBoolean("AutoReturn");
                boolean returnPrepaid = tr.getBoolean("ReturnPrepaid");
                List<Vec3d> waypoints = readVecList(tr.getList("Waypoints", Tag.TAG_COMPOUND));
                int wpIndex = Math.max(0, tr.getInt("WpIndex"));
                double segProgress = tr.getDouble("SegProgress");
                Vec3d nominalPos = readVec(tr, "NominalPos");
                Vec3d currentPos = readVec(tr, "CurrentPos");
                float yaw = tr.getFloat("Yaw");
                double baseSpeed = Math.max(0.01, tr.getDouble("BaseSpeed"));
                TransitPhase phase = TransitPhase.valueOf(tr.getString("Phase"));
                boolean hasRider = tr.getBoolean("HasRider");
                int ticksWaiting = Math.max(0, tr.getInt("TicksWaiting"));
                double vertAvoid = tr.getDouble("VertAvoid");
                double latStarboard = tr.getDouble("LatStarboard");
                int laneSlot = tr.getInt("LaneSlot");
                int maxObstacleY = tr.getInt("MaxObstacleY");
                int entityGen = Math.max(0, tr.getInt("EntityGen"));

                // Always start physicalEntityLoaded = false on load so manager re-binds or spawns cleanly
                TransitSnapshot snapshot = new TransitSnapshot(
                        id,
                        kind,
                        origin,
                        target,
                        originBay,
                        targetBay,
                        autoReturn,
                        returnPrepaid,
                        waypoints,
                        wpIndex,
                        segProgress,
                        nominalPos,
                        currentPos,
                        yaw,
                        baseSpeed,
                        phase,
                        hasRider,
                        false,
                        ticksWaiting,
                        vertAvoid,
                        latStarboard,
                        laneSlot,
                        maxObstacleY,
                        entityGen
                );
                snapshots.add(snapshot);

                if (tr.contains("Cargo", Tag.TAG_LIST)) {
                    ListTag cargoList = tr.getList("Cargo", Tag.TAG_COMPOUND);
                    List<ItemStack> cargo = new ArrayList<>(cargoList.size());
                    for (int c = 0; c < cargoList.size(); c++) {
                        ItemStack stack = ItemStack.of(cargoList.getCompound(c));
                        if (!stack.isEmpty()) {
                            cargo.add(stack);
                        }
                    }
                    data.transitCargo.put(id, cargo);
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entry gracefully
            }
        }
        data.transitEngine.loadSnapshots(snapshots);

        // 3. Cached water routes
        ListTag routeList = root.getList("WaterRoutes", Tag.TAG_COMPOUND);
        for (int i = 0; i < routeList.size(); i++) {
            CompoundTag rt = routeList.getCompound(i);
            BlockPos3i originBerth = readBlockPos(rt, "OriginBerth");
            BlockPos3i targetBerth = readBlockPos(rt, "TargetBerth");
            List<Vec3d> waypoints = readVecList(rt.getList("Waypoints", Tag.TAG_COMPOUND));
            if (!waypoints.isEmpty()) {
                data.cachedWaterRoutes.put(new WaterRouteKey(originBerth, targetBerth), List.copyOf(waypoints));
            }
        }

        // 4. Persisted chunk-loading stations
        ListTag loaderList = root.getList("ChunkLoaders", Tag.TAG_COMPOUND);
        for (int i = 0; i < loaderList.size(); i++) {
            CompoundTag cl = loaderList.getCompound(i);
            data.chunkLoadedStations.add(new BlockPos3i(cl.getInt("X"), cl.getInt("Y"), cl.getInt("Z")));
        }

        return data;
    }

    private static void writeBlockPos(CompoundTag parent, String key, BlockPos3i pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.x());
        tag.putInt("Y", pos.y());
        tag.putInt("Z", pos.z());
        parent.put(key, tag);
    }

    private static BlockPos3i readBlockPos(CompoundTag parent, String key) {
        CompoundTag tag = parent.getCompound(key);
        return new BlockPos3i(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }

    private static void writeVec(CompoundTag parent, String key, Vec3d vec) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("X", vec.x());
        tag.putDouble("Y", vec.y());
        tag.putDouble("Z", vec.z());
        parent.put(key, tag);
    }

    private static Vec3d readVec(CompoundTag parent, String key) {
        CompoundTag tag = parent.getCompound(key);
        return new Vec3d(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }

    private static ListTag writeVecList(List<Vec3d> list) {
        ListTag tag = new ListTag();
        for (Vec3d vec : list) {
            CompoundTag v = new CompoundTag();
            v.putDouble("X", vec.x());
            v.putDouble("Y", vec.y());
            v.putDouble("Z", vec.z());
            tag.add(v);
        }
        return tag;
    }

    private static List<Vec3d> readVecList(ListTag list) {
        List<Vec3d> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag v = list.getCompound(i);
            result.add(new Vec3d(v.getDouble("X"), v.getDouble("Y"), v.getDouble("Z")));
        }
        return result;
    }
}
