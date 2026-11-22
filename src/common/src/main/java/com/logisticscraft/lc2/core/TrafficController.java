package com.logisticscraft.lc2.core;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Level-wide traffic and collision-avoidance controller for active boats and drones.
 * <p>
 * <ul>
 *   <li><b>Boats</b>: Applies right-hand (starboard) offset in wide waterways ({@code >= 3} wide),
 *       maintains safe longitudinal headway ({@value LogisticsConstants#BOAT_MIN_HEADWAY_BLOCKS} blocks),
 *       and yields the lower-UUID boat in narrow opposing/crossing segments with deterministic
 *       deadlock escape after {@value LogisticsConstants#DEADLOCK_ESCAPE_TICKS} ticks.</li>
 *   <li><b>Drones</b>: Enforces 3D horizontal and vertical separation
 *       ({@value LogisticsConstants#DRONE_MIN_SEPARATION_BLOCKS} blocks); when two active drones
 *       approach within the separation envelope, the lower-UUID drone applies a temporary
 *       {@code +3.0} block vertical offset (or holds briefly if climbing into another drone) until clear.</li>
 * </ul>
 */
public final class TrafficController {

    private static final double BOAT_SAME_DIR_DOT = 0.30;
    private static final double BOAT_SAFE_LATERAL_CLEARANCE = 1.25;
    private static final double BOAT_OPPOSING_CLEAR_BEHIND = -0.50;
    private static final double DRONE_HYSTERESIS_SEPARATION = 4.20;
    private static final double DRONE_SAFE_CLIMB_CLEARANCE = 2.20;
    private static final double DRONE_LOOKAHEAD_TICKS = 2.5;
    private static final double DEADLOCK_CREEP_SPEED_FACTOR = 0.50;

    /**
     * Per-tick avoidance guidance computed for a single active vehicle.
     *
     * @param speedFactor           speed multiplier in {@code [0.0, 1.0]} ({@code 0.0} = yield/hold)
     * @param targetStarboardOffset desired lateral starboard offset for boats in blocks
     * @param targetVerticalOffset  desired temporary vertical offset for drones in blocks
     * @param yielding              {@code true} if the vehicle is currently yielding for traffic
     * @param deadlockEscaping      {@code true} if deadlock escape overrode a yield this tick
     */
    public record TrafficDecision(
            double speedFactor,
            double targetStarboardOffset,
            double targetVerticalOffset,
            boolean yielding,
            boolean deadlockEscaping
    ) {
    }

    /**
     * Deterministic priority tie-breaker: the vehicle with the lexicographically lower UUID
     * has lower priority and yields/climbs for the higher-UUID vehicle.
     *
     * @param selfId  candidate vehicle UUID
     * @param otherId conflicting vehicle UUID
     * @return {@code true} if {@code selfId} is lower priority and should yield
     */
    public static boolean isLowerPriority(UUID selfId, UUID otherId) {
        Objects.requireNonNull(selfId, "selfId");
        Objects.requireNonNull(otherId, "otherId");
        return selfId.compareTo(otherId) < 0;
    }

    /**
     * Evaluates collision-avoidance guidance for {@code self} against all active transits.
     *
     * @param self        moving transit being evaluated
     * @param allTransits all active transits in the dimension
     * @param waterGrid   optional water grid for waterway width checks (may be {@code null} in unloaded chunks)
     * @return traffic decision for this tick
     */
    public TrafficDecision evaluate(
            TransitRecord self,
            Collection<TransitRecord> allTransits,
            WaterGrid waterGrid
    ) {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(allTransits, "allTransits");
        if (!self.phase().isMoving()) {
            return new TrafficDecision(0.0, 0.0, 0.0, false, false);
        }
        return self.vehicleKind().isBoat()
                ? evaluateBoat(self, allTransits, waterGrid)
                : evaluateDrone(self, allTransits);
    }

    private TrafficDecision evaluateBoat(
            TransitRecord self,
            Collection<TransitRecord> allTransits,
            WaterGrid waterGrid
    ) {
        Vec3d selfDir = self.currentHorizontalDirection();
        boolean wideChannel = waterGrid != null
                && selfDir.horizontalLengthSquared() > 1e-9
                && WaterPathfinder.isWideWaterway(waterGrid, self.nominalPos().toBlockPos(), selfDir);

        double targetStarboard = wideChannel ? LogisticsConstants.BOAT_STARBOARD_OFFSET_BLOCKS : 0.0;
        Vec3d starboardUnit = WaterPathfinder.starboardUnitVector(selfDir);

        boolean shouldYield = false;
        boolean mutualDeadlockCandidate = false;

        for (TransitRecord other : allTransits) {
            if (other == self || !other.vehicleKind().isBoat() || !isActivelyMoving(other)) {
                continue;
            }

            double curHorizDist = self.currentPos().horizontalDistanceTo(other.currentPos());
            double nomHorizDist = self.nominalPos().horizontalDistanceTo(other.nominalPos());
            if (curHorizDist >= 6.0 && nomHorizDist >= 6.0) {
                continue;
            }

            Vec3d otherDir = other.currentHorizontalDirection();
            Vec3d toOther = other.currentPos().subtract(self.currentPos());
            double forwardDist = toOther.x() * selfDir.x() + toOther.z() * selfDir.z();
            double lateralDist = Math.abs(toOther.x() * starboardUnit.x() + toOther.z() * starboardUnit.z());
            double dot = selfDir.x() * otherDir.x() + selfDir.z() * otherDir.z();

            if (dot > BOAT_SAME_DIR_DOT) {
                // Same-direction following: trailing boat maintains safe headway
                if (forwardDist > 0.05
                        && forwardDist < LogisticsConstants.BOAT_MIN_HEADWAY_BLOCKS
                        && lateralDist < BOAT_SAFE_LATERAL_CLEARANCE) {
                    shouldYield = true;
                    if (other.ticksWaiting() >= LogisticsConstants.DEADLOCK_ESCAPE_TICKS / 2) {
                        mutualDeadlockCandidate = true;
                    }
                } else if (Math.abs(forwardDist) <= 0.05
                        && curHorizDist < LogisticsConstants.BOAT_MIN_HEADWAY_BLOCKS
                        && lateralDist < BOAT_SAFE_LATERAL_CLEARANCE
                        && isLowerPriority(self.id(), other.id())) {
                    shouldYield = true;
                }
            } else {
                // Opposing or crossing boats
                if (lateralDist >= BOAT_SAFE_LATERAL_CLEARANCE) {
                    // Wide waterway with safe lateral starboard separation: both pass smoothly
                    continue;
                }
                // Ignore opposing boat that has already passed behind us
                if (forwardDist <= BOAT_OPPOSING_CLEAR_BEHIND) {
                    continue;
                }
                if (curHorizDist < LogisticsConstants.BOAT_MIN_HEADWAY_BLOCKS
                        || nomHorizDist < LogisticsConstants.BOAT_MIN_HEADWAY_BLOCKS) {
                    if (isLowerPriority(self.id(), other.id())) {
                        shouldYield = true;
                        if (other.ticksWaiting() >= LogisticsConstants.DEADLOCK_ESCAPE_TICKS / 2) {
                            mutualDeadlockCandidate = true;
                        }
                    }
                }
            }
        }

        if (shouldYield) {
            boolean escape = (self.ticksWaiting() >= LogisticsConstants.DEADLOCK_ESCAPE_TICKS && mutualDeadlockCandidate)
                    || self.ticksWaiting() >= LogisticsConstants.DEADLOCK_ESCAPE_TICKS * 2;
            if (escape) {
                return new TrafficDecision(DEADLOCK_CREEP_SPEED_FACTOR, targetStarboard, 0.0, false, true);
            }
            return new TrafficDecision(0.0, targetStarboard, 0.0, true, false);
        }

        return new TrafficDecision(1.0, targetStarboard, 0.0, false, false);
    }

    private TrafficDecision evaluateDrone(TransitRecord self, Collection<TransitRecord> allTransits) {
        double sep = LogisticsConstants.DRONE_MIN_SEPARATION_BLOCKS;
        double avoidY = LogisticsConstants.DRONE_AVOIDANCE_VERTICAL_OFFSET;

        double targetVerticalOffset = 0.0;
        boolean shouldHold = false;
        Vec3d selfLookahead = self.nominalPos().add(self.currentDirection().scale(self.baseSpeed() * DRONE_LOOKAHEAD_TICKS));

        for (TransitRecord other : allTransits) {
            if (other == self || !other.vehicleKind().isDrone() || !isActivelyMoving(other)) {
                continue;
            }

            double curHoriz = self.currentPos().horizontalDistanceTo(other.currentPos());
            double curVert = Math.abs(self.currentPos().y() - other.currentPos().y());
            double nomHoriz = self.nominalPos().horizontalDistanceTo(other.nominalPos());
            double nomVert = Math.abs(self.nominalPos().y() - other.nominalPos().y());

            Vec3d otherLookahead = other.nominalPos().add(
                    other.currentDirection().scale(other.baseSpeed() * DRONE_LOOKAHEAD_TICKS)
            );
            double predHoriz = selfLookahead.horizontalDistanceTo(otherLookahead);
            double predVert = Math.abs(selfLookahead.y() - otherLookahead.y());

            boolean closeNow = curHoriz < sep && curVert < sep;
            boolean closeNominal = nomHoriz < sep && nomVert < sep;
            boolean convergingSoon = predHoriz < sep && predVert < sep;
            boolean sustainOffset = self.verticalAvoidanceOffset() > 0.1
                    && nomHoriz < DRONE_HYSTERESIS_SEPARATION
                    && nomVert < DRONE_HYSTERESIS_SEPARATION;

            if (!(closeNow || closeNominal || convergingSoon || sustainOffset)) {
                continue;
            }

            if (isLowerPriority(self.id(), other.id())) {
                // Check if climbing +3.0 would climb directly into another drone hovering above us
                double otherY = other.currentPos().y();
                double myNomY = self.nominalPos().y();
                boolean otherDirectlyAbove = otherY >= myNomY + 1.5 && otherY <= myNomY + avoidY + 1.5;

                if (otherDirectlyAbove) {
                    shouldHold = true;
                } else {
                    targetVerticalOffset = Math.max(targetVerticalOffset, avoidY);
                    // Briefly hold horizontal progression while climbing into the +3.0 Y avoidance band
                    if (curHoriz < sep
                            && curVert < DRONE_SAFE_CLIMB_CLEARANCE
                            && self.verticalAvoidanceOffset() < avoidY - 0.1) {
                        shouldHold = true;
                    }
                }
            }
        }

        if (shouldHold) {
            if (self.ticksWaiting() >= LogisticsConstants.DEADLOCK_ESCAPE_TICKS) {
                return new TrafficDecision(DEADLOCK_CREEP_SPEED_FACTOR, 0.0, targetVerticalOffset, false, true);
            }
            return new TrafficDecision(0.0, 0.0, targetVerticalOffset, true, false);
        }

        return new TrafficDecision(1.0, 0.0, targetVerticalOffset, false, false);
    }

    private static boolean isActivelyMoving(TransitRecord record) {
        return record.phase().isMoving() && record.waypointIndex() < record.waypoints().size() - 1;
    }
}
