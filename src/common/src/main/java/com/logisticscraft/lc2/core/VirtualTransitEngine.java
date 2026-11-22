package com.logisticscraft.lc2.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Create-train style virtual simulation engine managing all active Electric Smart Cargo Boats
 * and Transport/Heavy Drones across loaded and unloaded chunks within one dimension.
 * <p>
 * Platform layers invoke {@link #tick(TransitWorldBridge)} once per server level tick and act
 * upon the returned {@link TickReport} to materialize, synchronize, dematerialize, or dock
 * physical Minecraft entities without losing cargo or state across chunk boundaries or restarts.
 */
public final class VirtualTransitEngine {

    private static final double STARBOARD_RAMP_PER_TICK = 0.25;
    private static final double VERTICAL_AVOID_RAMP_PER_TICK = 1.50;

    private final StationRegistry stationRegistry;
    private final TrafficController trafficController;
    private final Map<UUID, TransitRecord> activeTransits = new LinkedHashMap<>();
    private final Map<BlockPos3i, List<UUID>> pendingArrivals = new LinkedHashMap<>();
    private int nextLaneSlot = 0;

    /**
     * Platform callback bridge invoked during {@link #tick(TransitWorldBridge)}.
     */
    public interface TransitWorldBridge {

        /**
         * Returns {@code true} if the chunk {@code (chunkX, chunkZ)} is currently loaded and ticking.
         *
         * @param chunkX chunk X coordinate
         * @param chunkZ chunk Z coordinate
         * @return {@code true} if loaded
         */
        boolean isChunkLoaded(int chunkX, int chunkZ);

        /**
         * Attempts to unload cargo from an arrived vehicle into its target station's adjacent
         * containers and internal buffer. Only invoked when the target station chunk is loaded.
         *
         * @param transit arrived transit record
         * @return {@code true} when unloading has finished (or cargo is empty); {@code false} to retry next tick
         */
        boolean tryUnloadAtTarget(TransitRecord transit);

        /**
         * Attempts to debit target-side return flight energy ({@code returnCost}) from the target
         * station's EU buffer or single-use battery slot using the target station's priority setting.
         * Only invoked for auto-return vehicles with {@code returnPrepaid == false} when the target chunk is loaded.
         *
         * @param transit    waiting transit record
         * @param returnCost required return leg energy cost
         * @return {@code true} if target station paid the full cost; {@code false} if insufficient energy
         */
        boolean tryPayReturnAtTarget(TransitRecord transit, EnergyCost returnCost);

        /**
         * Returns an optional {@link WaterGrid} for loaded-chunk waterway width queries
         * (used for boat starboard offset on wide channels). May return {@code null}.
         *
         * @return water grid or {@code null}
         */
        default WaterGrid waterGrid() {
            return null;
        }

        /**
         * Returns an optional {@link AirspaceRouter.HeightSampler} used when planning return drone routes.
         * May return {@code null}.
         *
         * @return height sampler or {@code null}
         */
        default AirspaceRouter.HeightSampler heightSampler() {
            return null;
        }
    }

    /**
     * Per-tick report summarizing entity materialization, synchronization, dematerialization,
     * arrival, return takeoff, and completion events for platform adapters.
     *
     * @param toMaterialize   transits entering a loaded chunk requiring physical entity spawning/binding
     * @param toDematerialize transits leaving loaded chunks (without a seated player) to despawn cleanly
     * @param toSync          transits whose physical entity is loaded and should be synced to {@code currentPos}/{@code yaw}
     * @param newlyArrived    transits that arrived at their target station this tick
     * @param returnDeparted  transits that departed on their return leg this tick
     * @param completed       transits that completed their full journey this tick (removed from active map)
     * @param dirty           {@code true} if persisted SavedData state changed this tick
     */
    public record TickReport(
            List<TransitRecord> toMaterialize,
            List<TransitRecord> toDematerialize,
            List<TransitRecord> toSync,
            List<TransitRecord> newlyArrived,
            List<TransitRecord> returnDeparted,
            List<TransitRecord> completed,
            boolean dirty
    ) {
        /** Compact constructor ensuring immutable lists. */
        public TickReport {
            toMaterialize = List.copyOf(toMaterialize);
            toDematerialize = List.copyOf(toDematerialize);
            toSync = List.copyOf(toSync);
            newlyArrived = List.copyOf(newlyArrived);
            returnDeparted = List.copyOf(returnDeparted);
            completed = List.copyOf(completed);
        }
    }

    /**
     * Request parameters for validating and dispatching an Electric Smart Cargo Boat from a Smart Port.
     *
     * @param transitId          unique transit ID (if {@code null}, a random UUID is generated)
     * @param originPos          origin Smart Port position
     * @param targetPos          configured target Smart Port position
     * @param autoReturn         whether the boat should automatically return after unloading (doubles cost)
     * @param redstoneTriggered  whether the dispatch trigger (redstone or manual button) is active
     * @param hasDockedBoat      whether an idle Electric Smart Cargo Boat is docked at origin
     * @param hasPrelaunchRider  whether a player seated themselves in the boat before launch
     * @param upgrades           installed machine upgrades at origin Smart Port
     * @param availableEu        current stored EU at origin Smart Port
     * @param availableBatteries current single-use battery count at origin Smart Port
     * @param energyPriority     origin Smart Port's energy priority toggle
     * @param waterGrid          water grid for connectivity search (may be {@code null} if {@code cachedWaypoints} supplied)
     * @param cachedWaypoints    optional pre-validated water waypoints (skips A* when non-empty)
     */
    public record BoatDispatchRequest(
            UUID transitId,
            BlockPos3i originPos,
            BlockPos3i targetPos,
            boolean autoReturn,
            boolean redstoneTriggered,
            boolean hasDockedBoat,
            boolean hasPrelaunchRider,
            UpgradeSummary upgrades,
            double availableEu,
            int availableBatteries,
            EnergyPriority energyPriority,
            WaterGrid waterGrid,
            List<Vec3d> cachedWaypoints
    ) {
    }

    /**
     * Request parameters for validating and dispatching a Standard or Heavy Transport Drone from a Cargo Station.
     *
     * @param transitId          unique transit ID (if {@code null}, a random UUID is generated)
     * @param vehicleKind        {@link VehicleKind#STANDARD_DRONE} or {@link VehicleKind#HEAVY_DRONE}
     * @param originPos          origin Cargo Station position
     * @param originBay          origin drone bay slot ({@code 0..5}) holding the departing drone, or {@code -1} if none
     * @param targetPos          configured target Cargo Station position
     * @param autoReturn         whether the drone should automatically return (target pays return leg)
     * @param redstoneTriggered  whether the dispatch trigger (redstone or manual button) is active
     * @param rfChip             installed RF chip tier at origin Cargo Station
     * @param upgrades           installed machine upgrades at origin Cargo Station
     * @param availableEu        current stored EU at origin Cargo Station
     * @param availableBatteries current single-use battery count at origin Cargo Station
     * @param energyPriority     origin Cargo Station's energy priority toggle
     * @param heightSampler      optional terrain height sampler for obstacle clearance
     */
    public record DroneDispatchRequest(
            UUID transitId,
            VehicleKind vehicleKind,
            BlockPos3i originPos,
            int originBay,
            BlockPos3i targetPos,
            boolean autoReturn,
            boolean redstoneTriggered,
            RfChipTier rfChip,
            UpgradeSummary upgrades,
            double availableEu,
            int availableBatteries,
            EnergyPriority energyPriority,
            AirspaceRouter.HeightSampler heightSampler
    ) {
    }

    /**
     * Outcome of a validated boat or drone dispatch request.
     *
     * @param transit created and registered {@link TransitRecord} on success, or {@code null} on failure
     * @param payment resolved {@link PaymentPlan} the caller must debit from origin station on success, or {@code null}
     * @param failure {@link DispatchFailure} reason on failure, or {@code null} on success
     */
    public record DispatchResult(TransitRecord transit, PaymentPlan payment, DispatchFailure failure) {

        /** @return {@code true} if dispatch succeeded */
        public boolean isSuccess() {
            return transit != null && failure == null;
        }

        /**
         * Creates a successful dispatch result.
         *
         * @param transit registered transit record
         * @param payment payment plan to debit at origin
         * @return successful result
         */
        public static DispatchResult ok(TransitRecord transit, PaymentPlan payment) {
            return new DispatchResult(
                    Objects.requireNonNull(transit, "transit"),
                    Objects.requireNonNull(payment, "payment"),
                    null
            );
        }

        /**
         * Creates a failed dispatch result.
         *
         * @param failure reason dispatch was rejected
         * @return failed result
         */
        public static DispatchResult fail(DispatchFailure failure) {
            return new DispatchResult(null, null, Objects.requireNonNull(failure, "failure"));
        }
    }

    /**
     * Creates a {@link VirtualTransitEngine} backed by the given {@link StationRegistry}
     * and default {@link TrafficController}.
     *
     * @param stationRegistry dimension station registry
     */
    public VirtualTransitEngine(StationRegistry stationRegistry) {
        this(stationRegistry, new TrafficController());
    }

    /**
     * Creates a {@link VirtualTransitEngine} with explicit registry and traffic controller.
     *
     * @param stationRegistry   dimension station registry
     * @param trafficController collision-avoidance controller
     */
    public VirtualTransitEngine(StationRegistry stationRegistry, TrafficController trafficController) {
        this.stationRegistry = Objects.requireNonNull(stationRegistry, "stationRegistry");
        this.trafficController = Objects.requireNonNull(trafficController, "trafficController");
    }

    /**
     * Returns the {@link StationRegistry} associated with this engine.
     *
     * @return station registry
     */
    public StationRegistry stationRegistry() {
        return stationRegistry;
    }

    /**
     * Returns the {@link TrafficController} used for boat and drone collision avoidance.
     *
     * @return traffic controller
     */
    public TrafficController trafficController() {
        return trafficController;
    }

    /**
     * Enforces the critical boarding rule: players may board an Electric Smart Cargo Boat ONLY
     * when it is docked/idle (either {@code record == null} or not currently moving in transit).
     * Attempting to board while {@link TransitPhase#EN_ROUTE_OUTBOUND} or
     * {@link TransitPhase#EN_ROUTE_RETURN} returns {@code false}.
     *
     * @param record active transit record associated with the boat, or {@code null} if docked/idle
     * @return {@code true} if boarding is allowed
     */
    public static boolean canPlayerBoard(TransitRecord record) {
        if (record == null) {
            return true;
        }
        return record.canPlayerBoard();
    }

    /**
     * Validates all Smart Port dispatch conditions (target existence, redstone, docked boat,
     * water connectivity, non-hybrid energy payment) and registers the active boat transit.
     *
     * @param req boat dispatch request
     * @return dispatch outcome containing the active {@link TransitRecord} and {@link PaymentPlan}, or failure reason
     */
    public DispatchResult dispatchBoat(BoatDispatchRequest req) {
        Objects.requireNonNull(req, "req");
        if (req.targetPos() == null) {
            return DispatchResult.fail(DispatchFailure.NO_TARGET);
        }
        if (req.originPos().equals(req.targetPos())) {
            return DispatchResult.fail(DispatchFailure.SAME_STATION);
        }
        Optional<StationEntry> originOpt = stationRegistry.get(req.originPos());
        Optional<StationEntry> targetOpt = stationRegistry.get(req.targetPos());
        if (targetOpt.isEmpty() || targetOpt.get().type() != StationType.SMART_PORT) {
            return DispatchResult.fail(DispatchFailure.TARGET_NOT_FOUND);
        }
        if (!req.redstoneTriggered()) {
            return DispatchResult.fail(DispatchFailure.REDSTONE_INACTIVE);
        }
        if (!req.hasDockedBoat()) {
            return DispatchResult.fail(DispatchFailure.NO_VEHICLE);
        }

        List<Vec3d> waypoints;
        if (req.cachedWaypoints() != null && !req.cachedWaypoints().isEmpty()) {
            waypoints = req.cachedWaypoints();
        } else {
            BlockPos3i originBerth = originOpt.map(StationEntry::waterBerthPos).orElse(null);
            BlockPos3i targetBerth = targetOpt.get().waterBerthPos();
            if (originBerth == null || targetBerth == null || req.waterGrid() == null) {
                return DispatchResult.fail(DispatchFailure.NO_WATER_BERTH);
            }
            WaterPathResult path = WaterPathfinder.findPath(req.waterGrid(), originBerth, targetBerth);
            if (!path.connected()) {
                return DispatchResult.fail(path.failure());
            }
            waypoints = path.waypoints();
        }

        EnergyCost cost = EnergyCostCalculator.boatCost(req.autoReturn());
        EnergyPriority priority = req.energyPriority() == null ? EnergyPriority.EU_FIRST : req.energyPriority();
        PaymentPlan payment = EnergyCostCalculator.resolvePayment(
                cost,
                req.availableEu(),
                req.availableBatteries(),
                priority
        );
        if (!payment.isPayable()) {
            return DispatchResult.fail(DispatchFailure.INSUFFICIENT_ENERGY);
        }

        UpgradeSummary upgrades = req.upgrades() == null ? UpgradeSummary.EMPTY : req.upgrades();
        double speed = VehicleKind.ELECTRIC_CARGO_BOAT.baseSpeed() * upgrades.speedMultiplier();
        UUID id = req.transitId() != null ? req.transitId() : UUID.randomUUID();

        TransitRecord record = new TransitRecord(
                id,
                VehicleKind.ELECTRIC_CARGO_BOAT,
                req.originPos(),
                req.targetPos(),
                -1,
                -1,
                req.autoReturn(),
                req.autoReturn(), // round-trip boats prepay return at origin
                waypoints,
                speed,
                req.hasPrelaunchRider(),
                0,
                0
        );
        registerTransit(record);
        return DispatchResult.ok(record, payment);
    }

    /**
     * Validates all Cargo Station dispatch conditions (drone kind, RF chip & range, target existence,
     * redstone, idle drone, non-hybrid energy payment, atomic target bay reservation), plans a
     * stratified VTOL route, and registers the active drone transit.
     *
     * @param req drone dispatch request
     * @return dispatch outcome containing the active {@link TransitRecord} and {@link PaymentPlan}, or failure reason
     */
    public DispatchResult dispatchDrone(DroneDispatchRequest req) {
        Objects.requireNonNull(req, "req");
        if (req.vehicleKind() == null || !req.vehicleKind().isDrone()) {
            return DispatchResult.fail(DispatchFailure.INVALID_VEHICLE_FOR_STATION);
        }
        if (req.rfChip() == null || !req.rfChip().isInstalled()) {
            return DispatchResult.fail(DispatchFailure.NO_RF_CHIP);
        }
        if (req.targetPos() == null) {
            return DispatchResult.fail(DispatchFailure.NO_TARGET);
        }
        if (req.originPos().equals(req.targetPos())) {
            return DispatchResult.fail(DispatchFailure.SAME_STATION);
        }
        Optional<StationEntry> targetOpt = stationRegistry.get(req.targetPos());
        if (targetOpt.isEmpty() || targetOpt.get().type() != StationType.CARGO_STATION) {
            return DispatchResult.fail(DispatchFailure.TARGET_NOT_FOUND);
        }
        if (!req.redstoneTriggered()) {
            return DispatchResult.fail(DispatchFailure.REDSTONE_INACTIVE);
        }
        if (req.originBay() < 0 || req.originBay() >= LogisticsConstants.CARGO_STATION_DRONE_BAYS) {
            return DispatchResult.fail(DispatchFailure.NO_VEHICLE);
        }

        EnergyCostCalculator.CostCalculation calc = EnergyCostCalculator.droneCost(
                req.vehicleKind(),
                req.originPos(),
                req.targetPos(),
                req.rfChip()
        );
        if (!calc.isValid()) {
            return DispatchResult.fail(calc.failure());
        }

        EnergyPriority priority = req.energyPriority() == null ? EnergyPriority.EU_FIRST : req.energyPriority();
        PaymentPlan payment = EnergyCostCalculator.resolvePayment(
                calc.cost(),
                req.availableEu(),
                req.availableBatteries(),
                priority
        );
        if (!payment.isPayable()) {
            return DispatchResult.fail(DispatchFailure.INSUFFICIENT_ENERGY);
        }

        UUID id = req.transitId() != null ? req.transitId() : UUID.randomUUID();
        OptionalInt reservedBay = stationRegistry.tryReserveBay(req.targetPos(), id);
        if (reservedBay.isEmpty()) {
            return DispatchResult.fail(DispatchFailure.TARGET_BAY_FULL);
        }

        int laneSlot = nextLaneSlot++;
        int maxObstacleY = AirspaceRouter.sampleMaxObstacleY(req.originPos(), req.targetPos(), req.heightSampler());
        AirspaceRoute route = AirspaceRouter.planRoute(
                req.originPos(),
                req.originBay(),
                req.targetPos(),
                reservedBay.getAsInt(),
                maxObstacleY,
                laneSlot
        );

        UpgradeSummary upgrades = req.upgrades() == null ? UpgradeSummary.EMPTY : req.upgrades();
        double speed = req.vehicleKind().baseSpeed() * upgrades.speedMultiplier();

        TransitRecord record = new TransitRecord(
                id,
                req.vehicleKind(),
                req.originPos(),
                req.targetPos(),
                req.originBay(),
                reservedBay.getAsInt(),
                req.autoReturn(),
                false, // drone auto-return is paid separately by target station
                route.waypoints(),
                speed,
                false,
                laneSlot,
                maxObstacleY
        );
        registerTransit(record);
        return DispatchResult.ok(record, payment);
    }

    /**
     * Registers a pre-constructed {@link TransitRecord} directly into the active simulation.
     *
     * @param record transit record to track
     */
    public void registerTransit(TransitRecord record) {
        Objects.requireNonNull(record, "record");
        activeTransits.put(record.id(), record);
        if (record.phase() == TransitPhase.ARRIVED_UNLOADING) {
            enqueuePendingArrival(record.targetPos(), record.id());
        }
    }

    /**
     * Cancels and removes an active transit, releasing any drone bay reservations it held.
     *
     * @param transitId transit UUID
     * @return {@code true} if the transit existed and was removed
     */
    public boolean cancelTransit(UUID transitId) {
        Objects.requireNonNull(transitId, "transitId");
        TransitRecord removed = activeTransits.remove(transitId);
        if (removed == null) {
            return false;
        }
        removePendingArrival(removed.targetPos(), transitId);
        removePendingArrival(removed.originPos(), transitId);
        stationRegistry.releaseBayReservation(removed.targetPos(), transitId);
        stationRegistry.releaseBayReservation(removed.originPos(), transitId);
        return true;
    }

    /**
     * Looks up an active transit by UUID.
     *
     * @param id transit UUID
     * @return active transit record if present
     */
    public Optional<TransitRecord> getTransit(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(activeTransits.get(id));
    }

    /**
     * Returns an unmodifiable view of all currently active transits.
     *
     * @return active transits
     */
    public Collection<TransitRecord> activeTransits() {
        return Collections.unmodifiableCollection(activeTransits.values());
    }

    /**
     * Returns all transits currently queued in {@link TransitPhase#ARRIVED_UNLOADING} at {@code stationPos}
     * (e.g. waiting for an unloaded target chunk to load).
     *
     * @param stationPos target station position
     * @return list of pending arrival transit records
     */
    public List<TransitRecord> pendingArrivalsAt(BlockPos3i stationPos) {
        Objects.requireNonNull(stationPos, "stationPos");
        List<UUID> ids = pendingArrivals.get(stationPos);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<TransitRecord> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            TransitRecord rec = activeTransits.get(id);
            if (rec != null) {
                result.add(rec);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Advances all active transits by one server tick, performing collision avoidance,
     * unloaded-chunk movement, entity materialization/dematerialization transitions,
     * unloading at loaded target stations, and automatic return departures.
     *
     * @param bridge platform world callback bridge
     * @return per-tick {@link TickReport}
     */
    public TickReport tick(TransitWorldBridge bridge) {
        Objects.requireNonNull(bridge, "bridge");

        List<TransitRecord> toMaterialize = new ArrayList<>();
        List<TransitRecord> toDematerialize = new ArrayList<>();
        List<TransitRecord> toSync = new ArrayList<>();
        List<TransitRecord> newlyArrived = new ArrayList<>();
        List<TransitRecord> returnDeparted = new ArrayList<>();
        List<TransitRecord> completed = new ArrayList<>();
        boolean dirty = false;

        WaterGrid waterGrid = bridge.waterGrid();

        // Snapshot iteration list so completed transits can be removed cleanly after ticking
        List<TransitRecord> currentList = new ArrayList<>(activeTransits.values());
        List<UUID> completedIds = new ArrayList<>();

        for (TransitRecord record : currentList) {
            switch (record.phase()) {
                case EN_ROUTE_OUTBOUND, EN_ROUTE_RETURN -> {
                    dirty = true;
                    boolean arrived = tickMovingTransit(record, currentList, waterGrid);
                    if (arrived) {
                        if (record.phase() == TransitPhase.EN_ROUTE_OUTBOUND) {
                            record.setPhase(TransitPhase.ARRIVED_UNLOADING);
                            record.setTicksWaiting(0);
                            enqueuePendingArrival(record.targetPos(), record.id());
                            newlyArrived.add(record);
                            // Attempt immediate unloading if target chunk is already loaded
                            if (processArrivalUnloading(record, bridge, returnDeparted)) {
                                completedIds.add(record.id());
                                completed.add(record);
                            } else {
                                updateChunkMaterialization(record, bridge, toMaterialize, toDematerialize, toSync);
                            }
                        } else {
                            // Reached origin on return leg: wait for origin chunk to be loaded before redocking
                            if (bridge.isChunkLoaded(record.originPos().chunkX(), record.originPos().chunkZ())) {
                                if (record.vehicleKind().isDrone()) {
                                    stationRegistry.releaseBayReservation(record.originPos(), record.id());
                                }
                                removePendingArrival(record.originPos(), record.id());
                                record.setPhase(TransitPhase.COMPLETED);
                                record.setVerticalAvoidanceOffset(0.0);
                                record.setLateralStarboardOffset(0.0);
                                completedIds.add(record.id());
                                completed.add(record);
                            } else {
                                enqueuePendingArrival(record.originPos(), record.id());
                                record.incrementTicksWaiting();
                                updateChunkMaterialization(record, bridge, toMaterialize, toDematerialize, toSync);
                            }
                        }
                    } else {
                        updateChunkMaterialization(record, bridge, toMaterialize, toDematerialize, toSync);
                    }
                }
                case ARRIVED_UNLOADING -> {
                    if (processArrivalUnloading(record, bridge, returnDeparted)) {
                        dirty = true;
                        completedIds.add(record.id());
                        completed.add(record);
                    } else {
                        if (record.phase() != TransitPhase.ARRIVED_UNLOADING) {
                            dirty = true;
                        } else {
                            record.incrementTicksWaiting();
                        }
                        updateChunkMaterialization(record, bridge, toMaterialize, toDematerialize, toSync);
                    }
                }
                case WAITING_RETURN_ENERGY -> {
                    boolean departed = processWaitingReturn(record, bridge, returnDeparted);
                    if (departed) {
                        dirty = true;
                    } else {
                        record.incrementTicksWaiting();
                    }
                    updateChunkMaterialization(record, bridge, toMaterialize, toDematerialize, toSync);
                }
                case COMPLETED -> {
                    completedIds.add(record.id());
                    completed.add(record);
                }
            }
        }

        for (UUID id : completedIds) {
            TransitRecord rec = activeTransits.remove(id);
            if (rec != null) {
                removePendingArrival(rec.targetPos(), id);
                removePendingArrival(rec.originPos(), id);
            }
        }

        return new TickReport(
                toMaterialize,
                toDematerialize,
                toSync,
                newlyArrived,
                returnDeparted,
                completed,
                dirty
        );
    }

    /**
     * Captures immutable persistence snapshots of every active transit in deterministic order.
     *
     * @return immutable list of {@link TransitSnapshot}
     */
    public List<TransitSnapshot> captureSnapshots() {
        List<TransitSnapshot> snapshots = new ArrayList<>(activeTransits.size());
        for (TransitRecord record : activeTransits.values()) {
            snapshots.add(record.toSnapshot());
        }
        return List.copyOf(snapshots);
    }

    /**
     * Replaces active engine state from persisted {@link TransitSnapshot} entries and reconciles
     * drone bay reservations in the associated {@link StationRegistry}.
     *
     * @param snapshots persisted snapshots loaded from SavedData
     */
    public void loadSnapshots(Collection<TransitSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        activeTransits.clear();
        pendingArrivals.clear();
        int maxLane = 0;

        for (TransitSnapshot snapshot : snapshots) {
            if (snapshot.phase() == TransitPhase.COMPLETED) {
                continue;
            }
            TransitRecord record = TransitRecord.fromSnapshot(snapshot);
            activeTransits.put(record.id(), record);
            if (record.phase() == TransitPhase.ARRIVED_UNLOADING) {
                enqueuePendingArrival(record.targetPos(), record.id());
            } else if (record.phase() == TransitPhase.EN_ROUTE_RETURN
                    && record.waypointIndex() >= record.waypoints().size() - 1) {
                enqueuePendingArrival(record.originPos(), record.id());
            }
            maxLane = Math.max(maxLane, record.laneSlot() + 1);
        }
        nextLaneSlot = Math.max(nextLaneSlot, maxLane);
        stationRegistry.reconcileReservationsFromTransits(activeTransits.values());
    }

    private boolean tickMovingTransit(
            TransitRecord record,
            List<TransitRecord> allTransits,
            WaterGrid waterGrid
    ) {
        List<Vec3d> waypoints = record.waypoints();
        if (record.waypointIndex() >= waypoints.size() - 1) {
            return true;
        }

        TrafficController.TrafficDecision decision = trafficController.evaluate(record, allTransits, waterGrid);

        if (decision.yielding()) {
            record.incrementTicksWaiting();
        } else {
            record.setTicksWaiting(0);
        }

        // Smoothly adjust avoidance offsets every tick (even while holding for climb!)
        if (record.vehicleKind().isBoat()) {
            double nextStarboard = moveToward(
                    record.lateralStarboardOffset(),
                    decision.targetStarboardOffset(),
                    STARBOARD_RAMP_PER_TICK
            );
            record.setLateralStarboardOffset(nextStarboard);
            record.setVerticalAvoidanceOffset(0.0);
        } else {
            double nextVertical = moveToward(
                    record.verticalAvoidanceOffset(),
                    decision.targetVerticalOffset(),
                    VERTICAL_AVOID_RAMP_PER_TICK
            );
            record.setVerticalAvoidanceOffset(nextVertical);
            record.setLateralStarboardOffset(0.0);
        }

        double stepDistance = record.baseSpeed() * decision.speedFactor();

        if (stepDistance > 1e-9 && waypoints.size() >= 2) {
            double remaining = stepDistance;
            while (remaining > 1e-9 && record.waypointIndex() < waypoints.size() - 1) {
                Vec3d a = waypoints.get(record.waypointIndex());
                Vec3d b = waypoints.get(record.waypointIndex() + 1);
                double segLen = a.distanceTo(b);
                if (segLen < 1e-9) {
                    record.setWaypointIndex(record.waypointIndex() + 1);
                    record.setSegmentProgress(0.0);
                    record.setNominalPos(b);
                    continue;
                }

                double distAlong = record.segmentProgress() * segLen;
                double distLeft = segLen - distAlong;
                if (remaining >= distLeft) {
                    remaining -= distLeft;
                    record.setWaypointIndex(record.waypointIndex() + 1);
                    record.setSegmentProgress(0.0);
                    record.setNominalPos(b);
                    updateYawIfHorizontal(record, a, b);
                } else {
                    double newProgress = (distAlong + remaining) / segLen;
                    record.setSegmentProgress(newProgress);
                    record.setNominalPos(a.lerp(b, newProgress));
                    updateYawIfHorizontal(record, a, b);
                    remaining = 0.0;
                }
            }
        }

        boolean reachedEnd = record.waypointIndex() >= waypoints.size() - 1;
        if (reachedEnd) {
            Vec3d finalWp = waypoints.get(waypoints.size() - 1);
            record.setNominalPos(finalWp);
            record.setLateralStarboardOffset(0.0);
            record.setVerticalAvoidanceOffset(0.0);
            record.setCurrentPos(finalWp);
            return true;
        }

        // Compose simulated currentPos from nominalPos + active dynamic avoidance offsets
        if (record.vehicleKind().isBoat()) {
            Vec3d dir = record.currentHorizontalDirection();
            Vec3d right = WaterPathfinder.starboardUnitVector(dir);
            record.setCurrentPos(record.nominalPos().add(right.scale(record.lateralStarboardOffset())));
        } else {
            record.setCurrentPos(record.nominalPos().add(0.0, record.verticalAvoidanceOffset(), 0.0));
        }
        return false;
    }

    /**
     * Handles {@link TransitPhase#ARRIVED_UNLOADING}. Returns {@code true} only if the transit reached {@link TransitPhase#COMPLETED}.
     */
    private boolean processArrivalUnloading(
            TransitRecord record,
            TransitWorldBridge bridge,
            List<TransitRecord> returnDeparted
    ) {
        BlockPos3i targetPos = record.targetPos();
        if (!bridge.isChunkLoaded(targetPos.chunkX(), targetPos.chunkZ())) {
            // Stay safely in ARRIVED_UNLOADING / pendingArrivals until target chunk loads
            return false;
        }

        boolean unloaded = bridge.tryUnloadAtTarget(record);
        if (!unloaded) {
            return false;
        }

        removePendingArrival(targetPos, record.id());

        if (!record.autoReturn()) {
            if (record.vehicleKind().isDrone()) {
                stationRegistry.releaseBayReservation(targetPos, record.id());
            }
            record.setPhase(TransitPhase.COMPLETED);
            return true;
        }

        // Auto-return enabled
        if (record.returnPrepaid()) {
            startBoatReturnLeg(record);
            returnDeparted.add(record);
            return false;
        }

        // Target-side payment required (standard & heavy drones, or non-prepaid return)
        record.setPhase(TransitPhase.WAITING_RETURN_ENERGY);
        record.setTicksWaiting(0);
        // Immediately check if target can pay & origin has free bay on arrival tick
        processWaitingReturn(record, bridge, returnDeparted);
        return false;
    }

    private boolean processWaitingReturn(
            TransitRecord record,
            TransitWorldBridge bridge,
            List<TransitRecord> returnDeparted
    ) {
        BlockPos3i targetPos = record.targetPos();
        if (!bridge.isChunkLoaded(targetPos.chunkX(), targetPos.chunkZ())) {
            return false;
        }

        if (record.vehicleKind().isDrone()) {
            // First atomically reserve a free bay at the origin station before debiting target energy
            OptionalInt originReturnBay = stationRegistry.tryReserveBay(record.originPos(), record.id());
            if (originReturnBay.isEmpty()) {
                return false;
            }

            int distance = record.originPos().manhattanDistance(targetPos);
            EnergyCost returnCost = EnergyCostCalculator.droneDistanceCost(record.vehicleKind(), distance);
            boolean paid = bridge.tryPayReturnAtTarget(record, returnCost);
            if (!paid) {
                // Target lacks energy: release origin bay reservation immediately so no bay is leaked
                stationRegistry.releaseBayReservation(record.originPos(), record.id());
                return false;
            }

            // Target paid and origin bay reserved: release target bay and lift off!
            stationRegistry.releaseBayReservation(targetPos, record.id());
            int returnBay = originReturnBay.getAsInt();
            record.setOriginBay(returnBay);

            int maxObstacleY = record.maxObstacleY();
            if (bridge.heightSampler() != null) {
                maxObstacleY = Math.max(
                        maxObstacleY,
                        AirspaceRouter.sampleMaxObstacleY(targetPos, record.originPos(), bridge.heightSampler())
                );
            }
            AirspaceRoute returnRoute = AirspaceRouter.planRoute(
                    targetPos,
                    record.targetBay(),
                    record.originPos(),
                    returnBay,
                    maxObstacleY,
                    record.laneSlot()
            );
            record.setWaypoints(returnRoute.waypoints());
            record.setPhase(TransitPhase.EN_ROUTE_RETURN);
            record.setTicksWaiting(0);
            returnDeparted.add(record);
            return true;
        } else {
            // Non-prepaid boat return fallback
            EnergyCost returnCost = EnergyCostCalculator.boatCost(false);
            if (!bridge.tryPayReturnAtTarget(record, returnCost)) {
                return false;
            }
            startBoatReturnLeg(record);
            returnDeparted.add(record);
            return true;
        }
    }

    private void startBoatReturnLeg(TransitRecord record) {
        List<Vec3d> reversed = new ArrayList<>(record.waypoints());
        Collections.reverse(reversed);
        record.setWaypoints(reversed);
        record.setPhase(TransitPhase.EN_ROUTE_RETURN);
        record.setTicksWaiting(0);
    }

    private void updateChunkMaterialization(
            TransitRecord record,
            TransitWorldBridge bridge,
            List<TransitRecord> toMaterialize,
            List<TransitRecord> toDematerialize,
            List<TransitRecord> toSync
    ) {
        Vec3d pos = record.currentPos();
        boolean loaded = bridge.isChunkLoaded(pos.chunkX(), pos.chunkZ());
        if (loaded) {
            if (!record.physicalEntityLoaded()) {
                record.setPhysicalEntityLoaded(true);
                toMaterialize.add(record);
            }
            toSync.add(record);
        } else {
            if (record.physicalEntityLoaded()) {
                if (record.hasRider()) {
                    // Never dematerialize a boat carrying a seated player
                    toSync.add(record);
                } else {
                    record.setPhysicalEntityLoaded(false);
                    toDematerialize.add(record);
                }
            }
        }
    }

    private void enqueuePendingArrival(BlockPos3i targetPos, UUID transitId) {
        List<UUID> queue = pendingArrivals.computeIfAbsent(targetPos, k -> new ArrayList<>());
        if (!queue.contains(transitId)) {
            queue.add(transitId);
        }
    }

    private void removePendingArrival(BlockPos3i targetPos, UUID transitId) {
        List<UUID> queue = pendingArrivals.get(targetPos);
        if (queue != null) {
            queue.remove(transitId);
            if (queue.isEmpty()) {
                pendingArrivals.remove(targetPos);
            }
        }
    }

    private static void updateYawIfHorizontal(TransitRecord record, Vec3d from, Vec3d to) {
        if (to.subtract(from).horizontalLengthSquared() > 1e-6) {
            record.setYaw(Vec3d.horizontalYawDegrees(from, to));
        }
    }

    private static double moveToward(double current, double target, double maxDelta) {
        double diff = target - current;
        if (Math.abs(diff) <= maxDelta) {
            return target;
        }
        return current + Math.copySign(maxDelta, diff);
    }
}
