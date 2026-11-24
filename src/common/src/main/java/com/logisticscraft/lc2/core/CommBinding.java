package com.logisticscraft.lc2.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable binding linking an Operation Terminal to a specific Communication Base Station
 * block coordinate within a dimension.
 *
 * @param dimensionId dimension resource key string (e.g. {@code "minecraft:overworld"})
 * @param pos         Communication Base Station block position
 */
public record CommBinding(String dimensionId, BlockPos3i pos) implements Comparable<CommBinding> {

    /**
     * Compact constructor enforcing non-null, non-blank dimension IDs and non-null coordinates.
     */
    public CommBinding {
        Objects.requireNonNull(dimensionId, "dimensionId");
        dimensionId = dimensionId.trim();
        if (dimensionId.isEmpty()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        Objects.requireNonNull(pos, "pos");
    }

    /**
     * Returns a stable, unambiguous canonical string key identifying this binding:
     * {@code "<dimensionId>@<x>,<y>,<z>"}.
     *
     * @return canonical binding key
     */
    public String canonicalKey() {
        return dimensionId + "@" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    /**
     * Returns {@code true} if this binding belongs to the specified dimension identifier.
     * Safely returns {@code false} if {@code dim} is {@code null}.
     *
     * @param dim dimension identifier to compare against
     * @return {@code true} if in {@code dim}
     */
    public boolean isInDimension(String dim) {
        return dim != null && dimensionId.equals(dim);
    }

    /**
     * Parses a canonical key produced by {@link #canonicalKey()} back into a {@link CommBinding}.
     *
     * @param key canonical string key
     * @return parsed binding, or empty if {@code key} is {@code null} or malformed
     */
    public static Optional<CommBinding> parseCanonicalKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        int sep = key.lastIndexOf('@');
        if (sep <= 0 || sep == key.length() - 1) {
            return Optional.empty();
        }
        String dim = key.substring(0, sep).trim();
        if (dim.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = key.substring(sep + 1).split(",");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return Optional.of(new CommBinding(dim, new BlockPos3i(x, y, z)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(CommBinding other) {
        Objects.requireNonNull(other, "other");
        int cmp = this.dimensionId.compareTo(other.dimensionId);
        if (cmp != 0) {
            return cmp;
        }
        return this.pos.compareTo(other.pos);
    }
}
