package com.logisticscraft.lc2.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable engine-managed simulation record representing one active boat or drone journey
 * across both loaded and unloaded chunks.
 * <p>
 * Platform layers read state via getters and persist/restore via {@link #toSnapshot()}
 * and {@link #fromSnapshot(TransitSnapshot)}.
 */
public final class TransitRecord {

    private final UUID id;
    private final VehicleKind vehicleKind;
    private final BlockPos3i originPos;
    private final BlockPos3i targetPos;
    private int originBay;
    private int targetBay;
    private final boolean autoReturn;
    private final boolean returnPrepaid;
    private List<Vec3d> waypoints;
    private int waypointIndex;
    private double segmentProgress;
    private Vec3d nominalPos;
    private Vec3d currentPos;
    private float yaw;
    private final double baseSpeed;
    private TransitPhase phase;
    private boolean hasRider;
    private boolean physicalEntityLoaded;
    private int ticksWaiting;
    private double verticalAvoidanceOffset;
    private double lateralStarboardOffset;
    private final int laneSlot;
    private final int maxObstacleY;

    /**
     * Constructs a new {@link TransitRecord} positioned at the start of {@code waypoints}.
     *
     * @param id             unique transit identifier
     * @param vehicleKind    vehicle kind
     * @param originPos      origin station position
     * @param targetPos      target station position
     * @param originBay      origin bay index ({@code 0..5}), or {@code -1} for boats
     * @param targetBay      target bay index ({@code 0..5}), or {@code -1} for boats
     * @param autoReturn     whether the vehicle automatically returns after unloading
     * @param returnPrepaid  whether return energy was prepaid at departure
     * @param waypoints      ordered non-empty waypoint list for outbound leg
     * @param baseSpeed      cruise speed in blocks/tick
     * @param hasRider       whether a player boarded before launch
     * @param laneSlot       airspace lane slot (for drones; {@code 0} for boats)
     * @param maxObstacleY   cached obstacle ceiling Y (for drones; {@code 0} for boats)
     */
    public TransitRecord(
            UUID id,
            VehicleKind vehicleKind,
            BlockPos3i originPos,
            BlockPos3i targetPos,
            int originBay,
            int targetBay,
            boolean autoReturn,
            boolean returnPrepaid,
            List<Vec3d> waypoints,
            double baseSpeed,
            boolean hasRider,
            int laneSlot,
            int maxObstacleY
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.vehicleKind = Objects.requireNonNull(vehicleKind, "vehicleKind");
        this.originPos = Objects.requireNonNull(originPos, "originPos");
        this.targetPos = Objects.requireNonNull(targetPos, "targetPos");
        this.originBay = originBay;
        this.targetBay = targetBay;
        this.autoReturn = autoReturn;
        this.returnPrepaid = returnPrepaid;
        Objects.requireNonNull(waypoints, "waypoints");
        if (waypoints.isEmpty()) {
            throw new IllegalArgumentException("waypoints must not be empty");
        }
        if (baseSpeed <= 0.0) {
            throw new IllegalArgumentException("baseSpeed must be > 0, got " + baseSpeed);
        }
        this.waypoints = List.copyOf(waypoints);
        this.waypointIndex = 0;
        this.segmentProgress = 0.0;
        this.nominalPos = this.waypoints.get(0);
        this.currentPos = this.nominalPos;
        this.yaw = computeInitialYaw(this.waypoints);
        this.baseSpeed = baseSpeed;
        this.phase = TransitPhase.EN_ROUTE_OUTBOUND;
        this.hasRider = hasRider && vehicleKind.isBoat();
        this.physicalEntityLoaded = false;
        this.ticksWaiting = 0;
        this.verticalAvoidanceOffset = 0.0;
        this.lateralStarboardOffset = 0.0;
        this.laneSlot = laneSlot;
        this.maxObstacleY = maxObstacleY;
    }

    private TransitRecord(TransitSnapshot snapshot) {
        this.id = snapshot.id();
        this.vehicleKind = snapshot.vehicleKind();
        this.originPos = snapshot.originPos();
        this.targetPos = snapshot.targetPos();
        this.originBay = snapshot.originBay();
        this.targetBay = snapshot.targetBay();
        this.autoReturn = snapshot.autoReturn();
        this.returnPrepaid = snapshot.returnPrepaid();
        this.waypoints = List.copyOf(snapshot.waypoints());
        this.waypointIndex = snapshot.waypointIndex();
        this.segmentProgress = snapshot.segmentProgress();
        this.nominalPos = snapshot.nominalPos();
        this.currentPos = snapshot.currentPos();
        this.yaw = snapshot.yaw();
        this.baseSpeed = snapshot.baseSpeed();
        this.phase = snapshot.phase();
        this.hasRider = snapshot.hasRider();
        this.physicalEntityLoaded = snapshot.physicalEntityLoaded();
        this.ticksWaiting = snapshot.ticksWaiting();
        this.verticalAvoidanceOffset = snapshot.verticalAvoidanceOffset();
        this.lateralStarboardOffset = snapshot.lateralStarboardOffset();
        this.laneSlot = snapshot.laneSlot();
        this.maxObstacleY = snapshot.maxObstacleY();
    }

    private static float computeInitialYaw(List<Vec3d> waypoints) {
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            if (b.subtract(a).horizontalLengthSquared() > 1e-9) {
                return Vec3d.horizontalYawDegrees(a, b);
            }
        }
        return 0.0f;
    }

    /**
     * Reconstructs a {@link TransitRecord} from a persisted {@link TransitSnapshot}.
     *
     * @param snapshot persisted snapshot
     * @return reconstructed transit record
     */
    public static TransitRecord fromSnapshot(TransitSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new TransitRecord(snapshot);
    }

    /**
     * Captures an immutable {@link TransitSnapshot} of this record for persistence.
     *
     * @return immutable snapshot
     */
    public TransitSnapshot toSnapshot() {
        return new TransitSnapshot(
                id,
                vehicleKind,
                originPos,
                targetPos,
                originBay,
                targetBay,
                autoReturn,
                returnPrepaid,
                waypoints,
                waypointIndex,
                segmentProgress,
                nominalPos,
                currentPos,
                yaw,
                baseSpeed,
                phase,
                hasRider,
                physicalEntityLoaded,
                ticksWaiting,
                verticalAvoidanceOffset,
                lateralStarboardOffset,
                laneSlot,
                maxObstacleY
        );
    }

    /**
     * Enforces the critical boat riding rule: a player may board a boat ONLY when docked/idle,
     * never while the transit is actively running ({@link TransitPhase#EN_ROUTE_OUTBOUND} or
     * {@link TransitPhase#EN_ROUTE_RETURN}). Drones are never rideable.
     *
     * @return {@code true} if a player is currently permitted to board
     */
    public boolean canPlayerBoard() {
        if (!vehicleKind.isBoat()) {
            return false;
        }
        return !phase.isMoving();
    }

    /**
     * Returns the current horizontal travel unit direction along the active waypoint segment,
     * or {@link Vec3d#ZERO} when moving purely vertically or stationary at the final waypoint.
     *
     * @return unit horizontal travel direction
     */
    public Vec3d currentHorizontalDirection() {
        if (waypoints.size() < 2 || waypointIndex >= waypoints.size() - 1) {
            return Vec3d.ZERO;
        }
        return waypoints.get(waypointIndex + 1).subtract(waypoints.get(waypointIndex)).normalizeHorizontal();
    }

    /**
     * Returns the current 3D unit travel direction along the active waypoint segment.
     *
     * @return unit 3D travel direction
     */
    public Vec3d currentDirection() {
        if (waypoints.size() < 2 || waypointIndex >= waypoints.size() - 1) {
            return Vec3d.ZERO;
        }
        return waypoints.get(waypointIndex + 1).subtract(waypoints.get(waypointIndex)).normalize();
    }

    /** @return unique transit ID */
    public UUID id() {
        return id;
    }

    /** @return vehicle kind */
    public VehicleKind vehicleKind() {
        return vehicleKind;
    }

    /** @return origin station block position */
    public BlockPos3i originPos() {
        return originPos;
    }

    /** @return target station block position */
    public BlockPos3i targetPos() {
        return targetPos;
    }

    /** @return origin drone bay index ({@code 0..5}), or {@code -1} for boats */
    public int originBay() {
        return originBay;
    }

    /** @return target drone bay index ({@code 0..5}), or {@code -1} for boats */
    public int targetBay() {
        return targetBay;
    }

    /** @return {@code true} if auto-return is enabled */
    public boolean autoReturn() {
        return autoReturn;
    }

    /** @return {@code true} if the return trip was prepaid at origin */
    public boolean returnPrepaid() {
        return returnPrepaid;
    }

    /** @return immutable list of waypoints for the active leg */
    public List<Vec3d> waypoints() {
        return waypoints;
    }

    /** @return current segment start waypoint index */
    public int waypointIndex() {
        return waypointIndex;
    }

    /** @return normalized progress {@code [0.0, 1.0]} along the current waypoint segment */
    public double segmentProgress() {
        return segmentProgress;
    }

    /** @return nominal position directly on the waypoint polyline */
    public Vec3d nominalPos() {
        return nominalPos;
    }

    /** @return simulated world position including any active collision-avoidance offsets */
    public Vec3d currentPos() {
        return currentPos;
    }

    /** @return horizontal yaw in Minecraft degrees */
    public float yaw() {
        return yaw;
    }

    /** @return configured base cruise speed in blocks/tick */
    public double baseSpeed() {
        return baseSpeed;
    }

    /** @return current lifecycle phase */
    public TransitPhase phase() {
        return phase;
    }

    /** @return {@code true} if a player is seated in this boat */
    public boolean hasRider() {
        return hasRider;
    }

    /**
     * Updates whether a player is currently seated in this boat (e.g. player dismounted mid-trip).
     * Ignored for drones.
     *
     * @param hasRider new seated passenger flag
     */
    public void setHasRider(boolean hasRider) {
        this.hasRider = hasRider && vehicleKind.isBoat();
    }

    /** @return {@code true} if a physical world entity is currently materialized */
    public boolean physicalEntityLoaded() {
        return physicalEntityLoaded;
    }

    /** @return consecutive ticks spent waiting or yielding */
    public int ticksWaiting() {
        return ticksWaiting;
    }

    /** @return active temporary vertical offset applied during drone 3D avoidance */
    public double verticalAvoidanceOffset() {
        return verticalAvoidanceOffset;
    }

    /** @return active temporary starboard lateral offset applied during boat lane-keeping */
    public double lateralStarboardOffset() {
        return lateralStarboardOffset;
    }

    /** @return assigned drone airspace lane slot */
    public int laneSlot() {
        return laneSlot;
    }

    /** @return cached max obstacle ceiling Y for drone return route planning */
    public int maxObstacleY() {
        return maxObstacleY;
    }

    // --- Package-private mutation helpers for VirtualTransitEngine & TrafficController ---

    void setOriginBay(int originBay) {
        this.originBay = originBay;
    }

    void setTargetBay(int targetBay) {
        this.targetBay = targetBay;
    }

    void setWaypoints(List<Vec3d> waypoints) {
        this.waypoints = List.copyOf(waypoints);
        this.waypointIndex = 0;
        this.segmentProgress = 0.0;
        if (!this.waypoints.isEmpty()) {
            this.nominalPos = this.waypoints.get(0);
            this.currentPos = this.nominalPos;
            this.yaw = computeInitialYaw(this.waypoints);
        }
    }

    void setWaypointIndex(int waypointIndex) {
        this.waypointIndex = waypointIndex;
    }

    void setSegmentProgress(double segmentProgress) {
        this.segmentProgress = segmentProgress;
    }

    void setNominalPos(Vec3d nominalPos) {
        this.nominalPos = nominalPos;
    }

    void setCurrentPos(Vec3d currentPos) {
        this.currentPos = currentPos;
    }

    void setYaw(float yaw) {
        this.yaw = yaw;
    }

    void setPhase(TransitPhase phase) {
        this.phase = phase;
    }

    void setPhysicalEntityLoaded(boolean physicalEntityLoaded) {
        this.physicalEntityLoaded = physicalEntityLoaded;
    }

    void setTicksWaiting(int ticksWaiting) {
        this.ticksWaiting = ticksWaiting;
    }

    void incrementTicksWaiting() {
        this.ticksWaiting++;
    }

    void setVerticalAvoidanceOffset(double verticalAvoidanceOffset) {
        this.verticalAvoidanceOffset = verticalAvoidanceOffset;
    }

    void setLateralStarboardOffset(double lateralStarboardOffset) {
        this.lateralStarboardOffset = lateralStarboardOffset;
    }
}
