package com.logisticscraft.lc2.world;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationRegistry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitPhase;
import com.logisticscraft.lc2.core.TransitSnapshot;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.platform.Lc2SavedDataBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-dimension persistent storage for LC2 stations, active virtual transits, serialized
 * cargo holds for in-transit vehicles, and cached verified water routes.
 */
public final class LogisticsSavedData extends Lc2SavedDataBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogisticsSavedData.class);

    private final StationRegistry stationRegistry;
    private final VirtualTransitEngine engine;
    private final Map<UUID, List<ItemStack>> transitCargos = new LinkedHashMap<>();
    private final Map<String, List<Vec3d>> cachedWaterRoutes = new HashMap<>();

    /** Creates a new empty SavedData container for the given dimension ID. */
    public LogisticsSavedData(String dimensionId) {
        this.stationRegistry = new StationRegistry(dimensionId);
        this.engine = new VirtualTransitEngine(this.stationRegistry);
    }

    /** Returns the dimension-scoped station registry. */
    public StationRegistry stationRegistry() {
        return stationRegistry;
    }

    /** Returns the dimension-scoped virtual transit simulation engine. */
    public VirtualTransitEngine engine() {
        return engine;
    }

    /** Stores a deep copy of the cargo stacks belonging to {@code transitId}. */
    public void setCargo(UUID transitId, List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        transitCargos.put(transitId, copy);
        setDirty();
    }

    /** Returns the mutable list of cargo stacks for {@code transitId} (never null). */
    public List<ItemStack> getCargo(UUID transitId) {
        return transitCargos.computeIfAbsent(transitId, ignored -> new ArrayList<>());
    }

    /** Removes stored cargo for a completed or cancelled transit. */
    public List<ItemStack> removeCargo(UUID transitId) {
        List<ItemStack> removed = transitCargos.remove(transitId);
        if (removed != null) {
            setDirty();
            return removed;
        }
        return Collections.emptyList();
    }

    /** Looks up a cached water waypoint list between two water berth blocks. */
    public List<Vec3d> getCachedWaterRoute(BlockPos3i originBerth, BlockPos3i targetBerth) {
        if (originBerth == null || targetBerth == null) {
            return null;
        }
        return cachedWaterRoutes.get(routeKey(originBerth, targetBerth));
    }

    /** Caches a verified water waypoint path (both forward and reverse directions). */
    public void cacheWaterRoute(BlockPos3i originBerth, BlockPos3i targetBerth, List<Vec3d> waypoints) {
        if (originBerth == null || targetBerth == null || waypoints == null || waypoints.isEmpty()) {
            return;
        }
        List<Vec3d> forward = List.copyOf(waypoints);
        List<Vec3d> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        cachedWaterRoutes.put(routeKey(originBerth, targetBerth), forward);
        cachedWaterRoutes.put(routeKey(targetBerth, originBerth), List.copyOf(reverse));
        setDirty();
    }

    /** Invalidates cached water routes referencing {@code berthPos}. */
    public void invalidateWaterRoutesFor(BlockPos3i berthPos) {
        if (berthPos == null || cachedWaterRoutes.isEmpty()) {
            return;
        }
        String token = posToken(berthPos);
        boolean changed = false;
        Iterator<String> it = cachedWaterRoutes.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().contains(token)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    /** Invalidates a specific cached water route between two berths. */
    public void invalidateWaterRoute(BlockPos3i a, BlockPos3i b) {
        if (a == null || b == null) {
            return;
        }
        boolean removed = cachedWaterRoutes.remove(routeKey(a, b)) != null;
        removed |= cachedWaterRoutes.remove(routeKey(b, a)) != null;
        if (removed) {
            setDirty();
        }
    }

    /** Converts a Minecraft BlockPos to a pure-core BlockPos3i. */
    public static BlockPos3i toPos3i(BlockPos pos) {
        return new BlockPos3i(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Converts a pure-core BlockPos3i to a Minecraft BlockPos. */
    public static BlockPos toBlockPos(BlockPos3i pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    @Override
    public CompoundTag saveToTag(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();

        // 1. Stations
        ListTag stationList = new ListTag();
        for (StationEntry entry : stationRegistry.snapshot()) {
            CompoundTag st = new CompoundTag();
            writePos(st, "pos", entry.pos());
            st.putString("type", entry.type().name());
            st.putString("name", entry.customName());
            if (entry.waterBerthPos() != null) {
                writePos(st, "berth", entry.waterBerthPos());
            }
            st.putInt("maxBays", entry.maxDroneBays());
            st.putInt("docked", entry.dockedDrones());
            st.putInt("reserved", entry.reservedInboundDrones());
            stationList.add(st);
        }
        root.put("stations", stationList);

        // 2. Active transits
        ListTag transitList = new ListTag();
        for (TransitSnapshot snap : engine.captureSnapshots()) {
            CompoundTag tr = new CompoundTag();
            tr.putString("id", snap.id().toString());
            tr.putString("kind", snap.vehicleKind().name());
            writePos(tr, "origin", snap.originPos());
            writePos(tr, "target", snap.targetPos());
            tr.putInt("originBay", snap.originBay());
            tr.putInt("targetBay", snap.targetBay());
            tr.putBoolean("autoReturn", snap.autoReturn());
            tr.putBoolean("returnPrepaid", snap.returnPrepaid());
            tr.put("waypoints", writeWaypoints(snap.waypoints()));
            tr.putInt("wpIdx", snap.waypointIndex());
            tr.putDouble("segProg", snap.segmentProgress());
            writeVec(tr, "nom", snap.nominalPos());
            writeVec(tr, "cur", snap.currentPos());
            tr.putFloat("yaw", snap.yaw());
            tr.putDouble("speed", snap.baseSpeed());
            tr.putString("phase", snap.phase().name());
            tr.putBoolean("hasRider", snap.hasRider());
            tr.putBoolean("physLoaded", snap.physicalEntityLoaded());
            tr.putInt("ticksWait", snap.ticksWaiting());
            tr.putDouble("vertAvoid", snap.verticalAvoidanceOffset());
            tr.putDouble("latStarboard", snap.lateralStarboardOffset());
            tr.putInt("laneSlot", snap.laneSlot());
            tr.putInt("maxObsY", snap.maxObstacleY());
            transitList.add(tr);
        }
        root.put("transits", transitList);

        // 3. Serialized cargo holds per transit UUID
        ListTag cargoList = new ListTag();
        for (Map.Entry<UUID, List<ItemStack>> entry : transitCargos.entrySet()) {
            if (engine.getTransit(entry.getKey()).isEmpty()) {
                continue;
            }
            CompoundTag ct = new CompoundTag();
            ct.putString("id", entry.getKey().toString());
            Lc2Nbt.saveItemList(ct, "items", entry.getValue(), registries);
            cargoList.add(ct);
        }
        root.put("cargos", cargoList);

        // 4. Cached water routes
        ListTag routeList = new ListTag();
        for (Map.Entry<String, List<Vec3d>> entry : cachedWaterRoutes.entrySet()) {
            CompoundTag rt = new CompoundTag();
            rt.putString("key", entry.getKey());
            rt.put("waypoints", writeWaypoints(entry.getValue()));
            routeList.add(rt);
        }
        root.put("waterRoutes", routeList);

        return root;
    }

    /** Restores a {@link LogisticsSavedData} instance from NBT. */
    public static LogisticsSavedData loadFromTag(
            CompoundTag root,
            HolderLookup.Provider registries,
            String dimensionId
    ) {
        LogisticsSavedData data = new LogisticsSavedData(dimensionId);

        // 1. Stations
        List<StationEntry> stations = new ArrayList<>();
        for (CompoundTag st : Lc2Nbt.getCompoundList(root, "stations")) {
            try {
                BlockPos3i pos = readPos(st, "pos");
                StationType type = StationType.valueOf(Lc2Nbt.getString(st, "type", StationType.SMART_PORT.name()));
                String name = Lc2Nbt.getString(st, "name", "");
                BlockPos3i berth = st.contains("berth") ? readPos(st, "berth") : null;
                int maxBays = Lc2Nbt.getInt(st, "maxBays", type.maxDroneBays());
                int docked = Lc2Nbt.getInt(st, "docked", 0);
                int reserved = Lc2Nbt.getInt(st, "reserved", 0);
                stations.add(new StationEntry(pos, type, name, berth, maxBays, docked, reserved));
            } catch (RuntimeException ex) {
                LOGGER.warn("Skipping malformed LC2 station entry during load: {}", ex.getMessage());
            }
        }
        data.stationRegistry.loadSnapshot(stations);

        // 2. Active transits
        List<TransitSnapshot> snapshots = new ArrayList<>();
        for (CompoundTag tr : Lc2Nbt.getCompoundList(root, "transits")) {
            try {
                UUID id = UUID.fromString(Lc2Nbt.getString(tr, "id", ""));
                VehicleKind kind = VehicleKind.valueOf(Lc2Nbt.getString(tr, "kind", VehicleKind.ELECTRIC_CARGO_BOAT.name()));
                BlockPos3i origin = readPos(tr, "origin");
                BlockPos3i target = readPos(tr, "target");
                int originBay = Lc2Nbt.getInt(tr, "originBay", -1);
                int targetBay = Lc2Nbt.getInt(tr, "targetBay", -1);
                boolean autoReturn = Lc2Nbt.getBoolean(tr, "autoReturn", false);
                boolean returnPrepaid = Lc2Nbt.getBoolean(tr, "returnPrepaid", false);
                List<Vec3d> waypoints = readWaypoints(tr, "waypoints");
                int wpIdx = Lc2Nbt.getInt(tr, "wpIdx", 0);
                double segProg = Lc2Nbt.getDouble(tr, "segProg", 0.0);
                Vec3d nom = readVec(tr, "nom");
                Vec3d cur = readVec(tr, "cur");
                float yaw = Lc2Nbt.getFloat(tr, "yaw", 0.0f);
                double speed = Math.max(0.05, Lc2Nbt.getDouble(tr, "speed", kind.baseSpeed()));
                TransitPhase phase = TransitPhase.valueOf(Lc2Nbt.getString(tr, "phase", TransitPhase.EN_ROUTE_OUTBOUND.name()));
                boolean hasRider = Lc2Nbt.getBoolean(tr, "hasRider", false);
                int ticksWait = Lc2Nbt.getInt(tr, "ticksWait", 0);
                double vertAvoid = Lc2Nbt.getDouble(tr, "vertAvoid", 0.0);
                double latStarboard = Lc2Nbt.getDouble(tr, "latStarboard", 0.0);
                int laneSlot = Lc2Nbt.getInt(tr, "laneSlot", 0);
                int maxObsY = Lc2Nbt.getInt(tr, "maxObsY", 0);
                // Reset physicalEntityLoaded to false on restart so LogisticsLevelManager cleanly rebinds or materializes
                snapshots.add(new TransitSnapshot(
                        id, kind, origin, target, originBay, targetBay, autoReturn, returnPrepaid,
                        waypoints, wpIdx, segProg, nom, cur, yaw, speed, phase, hasRider,
                        false, ticksWait, vertAvoid, latStarboard, laneSlot, maxObsY
                ));
            } catch (RuntimeException ex) {
                LOGGER.warn("Skipping malformed LC2 transit snapshot during load: {}", ex.getMessage());
            }
        }
        data.engine.loadSnapshots(snapshots);

        // 3. Cargo holds
        for (CompoundTag ct : Lc2Nbt.getCompoundList(root, "cargos")) {
            try {
                UUID id = UUID.fromString(Lc2Nbt.getString(ct, "id", ""));
                List<ItemStack> items = Lc2Nbt.loadItemList(ct, "items", registries);
                data.transitCargos.put(id, items);
            } catch (RuntimeException ex) {
                LOGGER.warn("Skipping malformed LC2 transit cargo during load: {}", ex.getMessage());
            }
        }

        // 4. Cached water routes
        for (CompoundTag rt : Lc2Nbt.getCompoundList(root, "waterRoutes")) {
            String key = Lc2Nbt.getString(rt, "key", "");
            List<Vec3d> waypoints = readWaypoints(rt, "waypoints");
            if (!key.isEmpty() && !waypoints.isEmpty()) {
                data.cachedWaterRoutes.put(key, List.copyOf(waypoints));
            }
        }

        return data;
    }

    private static String routeKey(BlockPos3i a, BlockPos3i b) {
        return posToken(a) + "->" + posToken(b);
    }

    private static String posToken(BlockPos3i p) {
        return p.x() + "," + p.y() + "," + p.z();
    }

    private static void writePos(CompoundTag parent, String key, BlockPos3i pos) {
        CompoundTag p = new CompoundTag();
        p.putInt("x", pos.x());
        p.putInt("y", pos.y());
        p.putInt("z", pos.z());
        parent.put(key, p);
    }

    private static BlockPos3i readPos(CompoundTag parent, String key) {
        CompoundTag p = Lc2Nbt.getCompound(parent, key);
        return new BlockPos3i(
                Lc2Nbt.getInt(p, "x", 0),
                Lc2Nbt.getInt(p, "y", 0),
                Lc2Nbt.getInt(p, "z", 0)
        );
    }

    private static void writeVec(CompoundTag parent, String key, Vec3d vec) {
        CompoundTag v = new CompoundTag();
        v.putDouble("x", vec.x());
        v.putDouble("y", vec.y());
        v.putDouble("z", vec.z());
        parent.put(key, v);
    }

    private static Vec3d readVec(CompoundTag parent, String key) {
        CompoundTag v = Lc2Nbt.getCompound(parent, key);
        return new Vec3d(
                Lc2Nbt.getDouble(v, "x", 0.0),
                Lc2Nbt.getDouble(v, "y", 0.0),
                Lc2Nbt.getDouble(v, "z", 0.0)
        );
    }

    private static ListTag writeWaypoints(List<Vec3d> waypoints) {
        ListTag list = new ListTag();
        for (Vec3d wp : waypoints) {
            CompoundTag v = new CompoundTag();
            v.putDouble("x", wp.x());
            v.putDouble("y", wp.y());
            v.putDouble("z", wp.z());
            list.add(v);
        }
        return list;
    }

    private static List<Vec3d> readWaypoints(CompoundTag parent, String key) {
        List<CompoundTag> tags = Lc2Nbt.getCompoundList(parent, key);
        List<Vec3d> result = new ArrayList<>(tags.size());
        for (CompoundTag v : tags) {
            result.add(new Vec3d(
                    Lc2Nbt.getDouble(v, "x", 0.0),
                    Lc2Nbt.getDouble(v, "y", 0.0),
                    Lc2Nbt.getDouble(v, "z", 0.0)
            ));
        }
        return result;
    }
}
