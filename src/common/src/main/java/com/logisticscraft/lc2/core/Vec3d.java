package com.logisticscraft.lc2.core;

import java.util.Objects;

/**
 * Immutable platform-agnostic 3D double-precision position or vector.
 *
 * @param x X component
 * @param y Y component
 * @param z Z component
 */
public record Vec3d(double x, double y, double z) {

    /** Zero vector {@code (0, 0, 0)}. */
    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    private static final double EPSILON = 1e-9;

    /**
     * Returns this vector translated by the given deltas.
     *
     * @param dx delta X
     * @param dy delta Y
     * @param dz delta Z
     * @return translated vector
     */
    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    /**
     * Returns the vector sum {@code this + other}.
     *
     * @param other vector to add
     * @return vector sum
     */
    public Vec3d add(Vec3d other) {
        Objects.requireNonNull(other, "other");
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    /**
     * Returns the vector difference {@code this - other}.
     *
     * @param other vector to subtract
     * @return vector difference
     */
    public Vec3d subtract(Vec3d other) {
        Objects.requireNonNull(other, "other");
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    /**
     * Returns this vector multiplied by {@code factor}.
     *
     * @param factor scalar multiplier
     * @return scaled vector
     */
    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    /**
     * Returns the squared Euclidean length of this vector.
     *
     * @return squared length
     */
    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * Returns the Euclidean length of this vector.
     *
     * @return length
     */
    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /**
     * Returns the squared horizontal (XZ) length of this vector.
     *
     * @return squared horizontal length
     */
    public double horizontalLengthSquared() {
        return x * x + z * z;
    }

    /**
     * Returns the horizontal (XZ) length of this vector.
     *
     * @return horizontal length
     */
    public double horizontalLength() {
        return Math.sqrt(horizontalLengthSquared());
    }

    /**
     * Returns the squared Euclidean distance from this point to {@code other}.
     *
     * @param other target point
     * @return squared distance
     */
    public double distanceSquared(Vec3d other) {
        Objects.requireNonNull(other, "other");
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns the Euclidean distance from this point to {@code other}.
     *
     * @param other target point
     * @return distance
     */
    public double distanceTo(Vec3d other) {
        return Math.sqrt(distanceSquared(other));
    }

    /**
     * Returns the horizontal (XZ) Euclidean distance from this point to {@code other}.
     *
     * @param other target point
     * @return horizontal distance
     */
    public double horizontalDistanceTo(Vec3d other) {
        Objects.requireNonNull(other, "other");
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Returns a unit-length vector pointing in the same direction, or {@link #ZERO}
     * if this vector's length is effectively zero.
     *
     * @return normalized vector
     */
    public Vec3d normalize() {
        double len = length();
        if (len < EPSILON) {
            return ZERO;
        }
        return new Vec3d(x / len, y / len, z / len);
    }

    /**
     * Returns a unit-length horizontal (XZ) vector with {@code y = 0}, or {@link #ZERO}
     * if the horizontal component is effectively zero.
     *
     * @return horizontal unit direction
     */
    public Vec3d normalizeHorizontal() {
        double len = horizontalLength();
        if (len < EPSILON) {
            return ZERO;
        }
        return new Vec3d(x / len, 0.0, z / len);
    }

    /**
     * Linearly interpolates between {@code this} ({@code t = 0}) and {@code target} ({@code t = 1}).
     *
     * @param target endpoint
     * @param t interpolation factor
     * @return interpolated position
     */
    public Vec3d lerp(Vec3d target, double t) {
        Objects.requireNonNull(target, "target");
        return new Vec3d(
                x + (target.x - x) * t,
                y + (target.y - y) * t,
                z + (target.z - z) * t
        );
    }

    /**
     * Converts this world position to its containing integer block position via floor rounding.
     *
     * @return containing block position
     */
    public BlockPos3i toBlockPos() {
        return new BlockPos3i((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Returns the Minecraft chunk X coordinate containing this position.
     *
     * @return chunk X
     */
    public int chunkX() {
        return ((int) Math.floor(x)) >> 4;
    }

    /**
     * Returns the Minecraft chunk Z coordinate containing this position.
     *
     * @return chunk Z
     */
    public int chunkZ() {
        return ((int) Math.floor(z)) >> 4;
    }

    /**
     * Computes Minecraft-standard horizontal yaw in degrees from {@code from} toward {@code to}.
     * South ({@code +Z}) is {@code 0°}, West ({@code -X}) is {@code 90°},
     * North ({@code -Z}) is {@code 180°}, and East ({@code +X}) is {@code -90°}.
     *
     * @param from source point
     * @param to destination point
     * @return horizontal yaw in degrees
     */
    public static float horizontalYawDegrees(Vec3d from, Vec3d to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < EPSILON * EPSILON) {
            return 0.0f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
