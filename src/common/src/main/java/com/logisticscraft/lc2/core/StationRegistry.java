package com.logisticscraft.lc2.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Dimension-scoped registry tracking all Smart Ports and Cargo Stations, providing
 * atomic drone bay reservations and distance-sorted target query helpers.
 */
public final class StationRegistry {

    private final String dimensionId;
    private final Map<BlockPos3i, MutableStation> stations = new LinkedHashMap<>();

    /**
     * Creates a station registry scoped to the default Overworld dimension ID.
     */
    public StationRegistry() {
        this("minecraft:overworld");
    }

    /**
     * Creates a station registry scoped to the given dimension identifier.
     *
     * @param dimensionId dimension resource key string (e.g. {@code "minecraft:overworld"})
     */
    public StationRegistry(String dimensionId) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
    }

    /**
     * Returns the dimension identifier this registry belongs to.
     *
     * @return dimension identifier
     */
    public String dimensionId() {
        return dimensionId;
    }

    /**
     * Registers a new station or updates its metadata (type, name, water berth) while preserving
     * existing docked drone counts and active inbound bay reservations when the station type is unchanged.
     *
     * @param pos           station position
     * @param type          station type
     * @param customName    custom station label (nullable)
     * @param waterBerthPos adjacent water berth position for Smart Ports (nullable)
     * @return current immutable {@link StationEntry} snapshot
     */
    public synchronized StationEntry register(
            BlockPos3i pos,
            StationType type,
            String customName,
            BlockPos3i waterBerthPos
    ) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(type, "type");
        MutableStation existing = stations.get(pos);
        if (existing != null && existing.type == type) {
            existing.customName = customName == null ? "" : customName;
            existing.waterBerthPos = waterBerthPos;
            return existing.toEntry();
        }
        MutableStation created = new MutableStation(
                pos,
                type,
                customName == null ? "" : customName,
                waterBerthPos,
                type.maxDroneBays(),
                0,
                0,
                0
        );
        stations.put(pos, created);
        return created.toEntry();
    }

    /**
     * Inserts or replaces a station directly from an immutable {@link StationEntry} snapshot
     * (used when loading persisted SavedData or configuring test scenarios).
     *
     * @param entry station snapshot
     */
    public synchronized void putEntry(StationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        MutableStation state = new MutableStation(
                entry.pos(),
                entry.type(),
                entry.customName(),
                entry.waterBerthPos(),
                entry.maxDroneBays(),
                entry.dockedDrones(),
                entry.reservedInboundDrones(),
                entry.dockedBayMask()
        );
        stations.put(entry.pos(), state);
    }

    /**
     * Removes a station from this registry along with any bay reservations held against it.
     *
     * @param pos station position
     * @return {@code true} if a station was removed
     */
    public synchronized boolean unregister(BlockPos3i pos) {
        Objects.requireNonNull(pos, "pos");
        return stations.remove(pos) != null;
    }

    /**
     * Looks up a station snapshot by block position.
     *
     * @param pos station position
     * @return station entry if registered
     */
    public synchronized Optional<StationEntry> get(BlockPos3i pos) {
        Objects.requireNonNull(pos, "pos");
        MutableStation state = stations.get(pos);
        return state == null ? Optional.empty() : Optional.of(state.toEntry());
    }

    /**
     * Updates the custom label of a registered station.
     *
     * @param pos        station position
     * @param customName new custom name
     * @return {@code true} if the station existed
     */
    public synchronized boolean updateName(BlockPos3i pos, String customName) {
        MutableStation state = stations.get(Objects.requireNonNull(pos, "pos"));
        if (state == null) {
            return false;
        }
        state.customName = customName == null ? "" : customName;
        return true;
    }

    /**
     * Updates the detected adjacent water berth block of a Smart Port.
     *
     * @param pos           Smart Port position
     * @param waterBerthPos detected navigable water block (nullable)
     * @return {@code true} if the station existed
     */
    public synchronized boolean updateWaterBerth(BlockPos3i pos, BlockPos3i waterBerthPos) {
        MutableStation state = stations.get(Objects.requireNonNull(pos, "pos"));
        if (state == null) {
            return false;
        }
        state.waterBerthPos = waterBerthPos;
        return true;
    }

    /**
     * Updates the number of docked drones parked at a Cargo Station (clamped to {@code [0, maxDroneBays]}).
     *
     * @param pos          Cargo Station position
     * @param dockedDrones current parked drone count
     * @return {@code true} if the station existed
     */
    public synchronized boolean setDockedDrones(BlockPos3i pos, int dockedDrones) {
        MutableStation state = stations.get(Objects.requireNonNull(pos, "pos"));
        if (state == null) {
            return false;
        }
        if (dockedDrones < 0) {
            throw new IllegalArgumentException("dockedDrones must be >= 0, got " + dockedDrones);
        }
        state.dockedDrones = Math.min(state.maxDroneBays, dockedDrones);
        // Keep docked bay mask synchronized with the aggregate count if not explicitly set
        state.dockedBayMask = 0;
        int assigned = 0;
        for (int bay = 0; bay < state.maxDroneBays && assigned < state.dockedDrones; bay++) {
            if (!state.reservedBays.containsValue(bay)) {
                state.dockedBayMask |= (1 << bay);
                assigned++;
            }
        }
        return true;
    }

    /**
     * Updates the exact 6-bit mask of occupied drone bay slots ({@code bit i == 1} means bay {@code i} has a parked drone)
     * and synchronizes {@code dockedDrones} to the population count of the mask.
     *
     * @param pos     Cargo Station position
     * @param bitmask bitmask of docked bays
     * @return {@code true} if the station existed
     */
    public synchronized boolean setDockedBayMask(BlockPos3i pos, int bitmask) {
        MutableStation state = stations.get(Objects.requireNonNull(pos, "pos"));
        if (state == null) {
            return false;
        }
        int validBits = (1 << state.maxDroneBays) - 1;
        state.dockedBayMask = bitmask & validBits;
        state.dockedDrones = Integer.bitCount(state.dockedBayMask);
        return true;
    }

    /**
     * Returns the number of free drone bays at {@code stationPos}, or {@code 0} if absent
     * or not a Cargo Station.
     *
     * @param stationPos station position
     * @return free drone bays
     */
    public synchronized int freeBays(BlockPos3i stationPos) {
        MutableStation state = stations.get(Objects.requireNonNull(stationPos, "stationPos"));
        return state == null ? 0 : state.freeBays();
    }

    /**
     * Atomically reserves a specific free drone bay ({@code 0..5}) at {@code stationPos} for {@code transitId}.
     * Idempotent if {@code transitId} already holds a reservation at {@code stationPos}.
     *
     * @param stationPos target Cargo Station position
     * @param transitId  unique transit identifier
     * @return reserved bay index ({@code 0..5}), or empty if station is full/absent/incompatible
     */
    public synchronized OptionalInt tryReserveBay(BlockPos3i stationPos, UUID transitId) {
        Objects.requireNonNull(stationPos, "stationPos");
        Objects.requireNonNull(transitId, "transitId");
        MutableStation state = stations.get(stationPos);
        if (state == null || state.type != StationType.CARGO_STATION) {
            return OptionalInt.empty();
        }
        Integer existing = state.reservedBays.get(transitId);
        if (existing != null) {
            return OptionalInt.of(existing);
        }
        if (state.freeBays() <= 0) {
            return OptionalInt.empty();
        }
        int bay = state.allocateFreeBayIndex();
        if (bay < 0) {
            return OptionalInt.empty();
        }
        state.reservedBays.put(transitId, bay);
        return OptionalInt.of(bay);
    }

    /**
     * Atomically reserves one anonymous drone bay slot at {@code stationPos} if any bay is free.
     *
     * @param stationPos target Cargo Station position
     * @return {@code true} if a bay was reserved
     */
    public synchronized boolean tryReserveBay(BlockPos3i stationPos) {
        Objects.requireNonNull(stationPos, "stationPos");
        MutableStation state = stations.get(stationPos);
        if (state == null || state.type != StationType.CARGO_STATION || state.freeBays() <= 0) {
            return false;
        }
        state.anonymousReservations++;
        return true;
    }

    /**
     * Releases the inbound drone bay reservation held by {@code transitId} at {@code stationPos}.
     *
     * @param stationPos station position
     * @param transitId  transit identifier
     * @return {@code true} if a reservation existed and was released
     */
    public synchronized boolean releaseBayReservation(BlockPos3i stationPos, UUID transitId) {
        Objects.requireNonNull(stationPos, "stationPos");
        Objects.requireNonNull(transitId, "transitId");
        MutableStation state = stations.get(stationPos);
        if (state == null) {
            return false;
        }
        return state.reservedBays.remove(transitId) != null;
    }

    /**
     * Releases one anonymous bay reservation at {@code stationPos}.
     *
     * @param stationPos station position
     * @return {@code true} if a reservation existed and was released
     */
    public synchronized boolean releaseBayReservation(BlockPos3i stationPos) {
        Objects.requireNonNull(stationPos, "stationPos");
        MutableStation state = stations.get(stationPos);
        if (state == null || state.anonymousReservations <= 0) {
            return false;
        }
        state.anonymousReservations--;
        return true;
    }

    /**
     * Returns the bay index reserved for {@code transitId} at {@code stationPos}, if any.
     *
     * @param stationPos station position
     * @param transitId  transit identifier
     * @return reserved bay index or empty
     */
    public synchronized OptionalInt reservedBayFor(BlockPos3i stationPos, UUID transitId) {
        MutableStation state = stations.get(Objects.requireNonNull(stationPos, "stationPos"));
        if (state == null) {
            return OptionalInt.empty();
        }
        Integer bay = state.reservedBays.get(Objects.requireNonNull(transitId, "transitId"));
        return bay == null ? OptionalInt.empty() : OptionalInt.of(bay);
    }

    /**
     * Returns {@code true} if {@code bayIndex} at {@code stationPos} is currently reserved for an
     * inbound or waiting-return drone transit.
     *
     * @param stationPos Cargo Station position
     * @param bayIndex   bay slot index ({@code 0..5})
     * @return {@code true} if reserved by an active transit
     */
    public synchronized boolean isBayReserved(BlockPos3i stationPos, int bayIndex) {
        MutableStation state = stations.get(Objects.requireNonNull(stationPos, "stationPos"));
        if (state == null || bayIndex < 0 || bayIndex >= state.maxDroneBays) {
            return false;
        }
        return state.reservedBays.containsValue(bayIndex);
    }

    /**
     * Returns a bitmask of all bay indices currently reserved for inbound or waiting-return drones
     * at {@code stationPos}.
     *
     * @param stationPos Cargo Station position
     * @return bitmask of reserved bays
     */
    public synchronized int reservedBayMask(BlockPos3i stationPos) {
        MutableStation state = stations.get(Objects.requireNonNull(stationPos, "stationPos"));
        if (state == null) {
            return 0;
        }
        int mask = 0;
        for (Integer bay : state.reservedBays.values()) {
            if (bay != null && bay >= 0 && bay < state.maxDroneBays) {
                mask |= (1 << bay);
            }
        }
        return mask;
    }

    /**
     * Reconciles all inbound bay reservations directly from active persisted drone transits
     * after loading SavedData, preventing stale or double-counted reservations across restarts.
     *
     * @param activeTransits active transits restored into the engine
     */
    public synchronized void reconcileReservationsFromTransits(Collection<TransitRecord> activeTransits) {
        Objects.requireNonNull(activeTransits, "activeTransits");
        for (MutableStation state : stations.values()) {
            state.reservedBays.clear();
            state.anonymousReservations = 0;
        }
        for (TransitRecord transit : activeTransits) {
            if (!transit.vehicleKind().isDrone() || transit.phase() == TransitPhase.COMPLETED) {
                continue;
            }
            if (transit.phase() == TransitPhase.EN_ROUTE_RETURN) {
                restoreReservationInternal(transit.originPos(), transit.id(), transit.originBay());
            } else {
                // EN_ROUTE_OUTBOUND, ARRIVED_UNLOADING, WAITING_RETURN_ENERGY hold targetBay at targetPos
                restoreReservationInternal(transit.targetPos(), transit.id(), transit.targetBay());
            }
        }
    }

    private void restoreReservationInternal(BlockPos3i stationPos, UUID transitId, int bayIndex) {
        MutableStation state = stations.get(stationPos);
        if (state == null || state.type != StationType.CARGO_STATION) {
            return;
        }
        if (bayIndex >= 0 && bayIndex < state.maxDroneBays) {
            state.reservedBays.put(transitId, bayIndex);
        }
    }

    /**
     * Lists all compatible stations of {@code type} in this dimension (excluding {@code originPos}),
     * sorted by 3D Manhattan distance ascending (tie-broken by Euclidean distance, then coordinates).
     *
     * @param originPos querying station position
     * @param type      required target station type
     * @return sorted list of compatible target station snapshots
     */
    public synchronized List<StationEntry> listCompatibleTargets(BlockPos3i originPos, StationType type) {
        Objects.requireNonNull(originPos, "originPos");
        Objects.requireNonNull(type, "type");
        List<StationEntry> result = new ArrayList<>();
        for (MutableStation state : stations.values()) {
            if (state.type == type && !state.pos.equals(originPos)) {
                result.add(state.toEntry());
            }
        }
        result.sort(Comparator
                .comparingInt((StationEntry e) -> originPos.manhattanDistance(e.pos()))
                .thenComparingLong(e -> originPos.distanceSquared(e.pos()))
                .thenComparing(StationEntry::pos));
        return result;
    }

    /**
     * Lists all Cargo Stations reachable from {@code originPos} with the installed {@code chip},
     * sorted by Manhattan distance ascending.
     *
     * @param originPos querying Cargo Station position
     * @param chip      installed RF chip tier
     * @return sorted list of reachable Cargo Station snapshots
     */
    public synchronized List<StationEntry> listReachableDroneTargets(BlockPos3i originPos, RfChipTier chip) {
        Objects.requireNonNull(originPos, "originPos");
        if (chip == null || !chip.isInstalled()) {
            return List.of();
        }
        List<StationEntry> targets = listCompatibleTargets(originPos, StationType.CARGO_STATION);
        List<StationEntry> reachable = new ArrayList<>(targets.size());
        for (StationEntry entry : targets) {
            if (chip.canReach(originPos.manhattanDistance(entry.pos()))) {
                reachable.add(entry);
            }
        }
        return reachable;
    }

    /**
     * Checks whether {@code targetPos} is within the installed {@code chip}'s maximum Manhattan range
     * from {@code originPos}.
     *
     * @param originPos origin Cargo Station position
     * @param targetPos target Cargo Station position
     * @param chip      installed RF chip tier
     * @return {@code true} if installed and Manhattan distance {@code <= chip.maxRange()}
     */
    public static boolean isWithinRange(BlockPos3i originPos, BlockPos3i targetPos, RfChipTier chip) {
        Objects.requireNonNull(originPos, "originPos");
        Objects.requireNonNull(targetPos, "targetPos");
        return chip != null && chip.canReach(originPos.manhattanDistance(targetPos));
    }

    /**
     * Returns immutable snapshots of all registered stations in deterministic registration order.
     *
     * @return immutable list of station entries
     */
    public synchronized List<StationEntry> snapshot() {
        List<StationEntry> list = new ArrayList<>(stations.size());
        for (MutableStation state : stations.values()) {
            list.add(state.toEntry());
        }
        return List.copyOf(list);
    }

    /**
     * Clears this registry and loads all stations from a persisted snapshot list.
     *
     * @param entries persisted station entries
     */
    public synchronized void loadSnapshot(Collection<StationEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        stations.clear();
        for (StationEntry entry : entries) {
            putEntry(entry);
        }
    }

    /**
     * Returns the total number of registered stations in this dimension.
     *
     * @return station count
     */
    public synchronized int size() {
        return stations.size();
    }

    private static final class MutableStation {
        final BlockPos3i pos;
        final StationType type;
        String customName;
        BlockPos3i waterBerthPos;
        final int maxDroneBays;
        int dockedDrones;
        int dockedBayMask;
        int anonymousReservations;
        final Map<UUID, Integer> reservedBays = new HashMap<>();

        MutableStation(
                BlockPos3i pos,
                StationType type,
                String customName,
                BlockPos3i waterBerthPos,
                int maxDroneBays,
                int dockedDrones,
                int anonymousReservations,
                int dockedBayMask
        ) {
            this.pos = pos;
            this.type = type;
            this.customName = customName;
            this.waterBerthPos = waterBerthPos;
            this.maxDroneBays = maxDroneBays;
            this.anonymousReservations = Math.max(0, anonymousReservations);
            int validBits = maxDroneBays >= 31 ? Integer.MAX_VALUE : ((1 << maxDroneBays) - 1);
            int masked = dockedBayMask & validBits;
            if (masked != 0 || dockedDrones <= 0) {
                this.dockedBayMask = masked;
                this.dockedDrones = Integer.bitCount(masked);
            } else {
                this.dockedDrones = Math.min(maxDroneBays, Math.max(0, dockedDrones));
                int fallbackMask = 0;
                for (int i = 0; i < this.dockedDrones; i++) {
                    fallbackMask |= (1 << i);
                }
                this.dockedBayMask = fallbackMask;
            }
        }

        int totalReserved() {
            return reservedBays.size() + anonymousReservations;
        }

        int freeBays() {
            return Math.max(0, maxDroneBays - dockedDrones - totalReserved());
        }

        int allocateFreeBayIndex() {
            Set<Integer> usedByReservations = new HashSet<>(reservedBays.values());
            for (int bay = 0; bay < maxDroneBays; bay++) {
                boolean dockedHere = (dockedBayMask & (1 << bay)) != 0;
                if (!dockedHere && !usedByReservations.contains(bay)) {
                    return bay;
                }
            }
            for (int bay = 0; bay < maxDroneBays; bay++) {
                if (!usedByReservations.contains(bay)) {
                    return bay;
                }
            }
            return -1;
        }

        StationEntry toEntry() {
            return new StationEntry(
                    pos,
                    type,
                    customName,
                    waterBerthPos,
                    maxDroneBays,
                    dockedDrones,
                    totalReserved(),
                    dockedBayMask
            );
        }
    }
}
