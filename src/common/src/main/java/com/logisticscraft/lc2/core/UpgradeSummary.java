package com.logisticscraft.lc2.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable summary of all supported IC2 and LC2 machine upgrades installed in a station's
 * 4 machine upgrade slots, along with derived electrical, transfer, redstone, and chunk-loading parameters.
 *
 * @param transformerCount       count of {@code ic2:transformer_upgrade}
 * @param energyStorageCount     count of {@code ic2:energy_storage_upgrade}
 * @param overclockerCount       count of {@code ic2:overclocker_upgrade}
 * @param ejectorCount           count of {@code ic2:ejector_upgrade}
 * @param advancedEjectorCount   count of {@code ic2:advanced_ejector_upgrade}
 * @param pullingCount           count of {@code ic2:pulling_upgrade}
 * @param advancedPullingCount   count of {@code ic2:advanced_pulling_upgrade}
 * @param redstoneInverterCount  count of {@code ic2:redstone_inverter_upgrade}
 * @param chunkLoadingCount      count of {@code lc2:chunk_loading_upgrade}
 */
public record UpgradeSummary(
        int transformerCount,
        int energyStorageCount,
        int overclockerCount,
        int ejectorCount,
        int advancedEjectorCount,
        int pullingCount,
        int advancedPullingCount,
        int redstoneInverterCount,
        int chunkLoadingCount
) {

    /** Summary representing a station with no upgrades installed. */
    public static final UpgradeSummary EMPTY = new UpgradeSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);

    private static final Set<String> SUPPORTED_UPGRADE_IDS = Set.of(
            LogisticsConstants.UPGRADE_TRANSFORMER,
            LogisticsConstants.UPGRADE_ENERGY_STORAGE,
            LogisticsConstants.UPGRADE_OVERCLOCKER,
            LogisticsConstants.UPGRADE_EJECTOR,
            LogisticsConstants.UPGRADE_ADVANCED_EJECTOR,
            LogisticsConstants.UPGRADE_PULLING,
            LogisticsConstants.UPGRADE_ADVANCED_PULLING,
            LogisticsConstants.UPGRADE_REDSTONE_INVERTER,
            LogisticsConstants.UPGRADE_CHUNK_LOADING
    );

    /**
     * Compact constructor validating that all upgrade counts are non-negative.
     */
    public UpgradeSummary {
        requireNonNegative(transformerCount, "transformerCount");
        requireNonNegative(energyStorageCount, "energyStorageCount");
        requireNonNegative(overclockerCount, "overclockerCount");
        requireNonNegative(ejectorCount, "ejectorCount");
        requireNonNegative(advancedEjectorCount, "advancedEjectorCount");
        requireNonNegative(pullingCount, "pullingCount");
        requireNonNegative(advancedPullingCount, "advancedPullingCount");
        requireNonNegative(redstoneInverterCount, "redstoneInverterCount");
        requireNonNegative(chunkLoadingCount, "chunkLoadingCount");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    /**
     * Returns whether the given qualified item ID is accepted in station machine upgrade slots.
     *
     * @param itemId qualified item registry ID
     * @return {@code true} if supported
     */
    public static boolean isSupportedUpgrade(String itemId) {
        return itemId != null && SUPPORTED_UPGRADE_IDS.contains(itemId);
    }

    /**
     * Returns an unmodifiable set containing all 9 supported upgrade item IDs.
     *
     * @return set of supported qualified item IDs
     */
    public static Set<String> supportedUpgradeIds() {
        return SUPPORTED_UPGRADE_IDS;
    }

    /**
     * Creates a new mutable builder for tallying upgrades from platform item stacks.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds an {@link UpgradeSummary} by counting each qualified item ID in {@code itemIds} once.
     * Unknown or {@code null} item IDs are ignored.
     *
     * @param itemIds iterable of qualified item IDs
     * @return computed upgrade summary
     */
    public static UpgradeSummary fromItemIds(Iterable<String> itemIds) {
        Objects.requireNonNull(itemIds, "itemIds");
        Builder builder = builder();
        for (String itemId : itemIds) {
            builder.add(itemId, 1);
        }
        return builder.build();
    }

    /**
     * Builds an {@link UpgradeSummary} from a map of qualified item IDs to stack counts.
     * Unknown, {@code null}, or non-positive entries are ignored.
     *
     * @param itemCounts map of item ID to count
     * @return computed upgrade summary
     */
    public static UpgradeSummary fromItemCounts(Map<String, Integer> itemCounts) {
        Objects.requireNonNull(itemCounts, "itemCounts");
        Builder builder = builder();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            Integer count = entry.getValue();
            if (count != null && count > 0) {
                builder.add(entry.getKey(), count);
            }
        }
        return builder.build();
    }

    /**
     * Returns the maximum EU storage capacity: {@code 14400 + 10000 * energyStorageCount}.
     *
     * @return maximum EU capacity
     */
    public double maxCapacityEu() {
        return LogisticsConstants.BASE_ENERGY_CAPACITY_EU
                + LogisticsConstants.ENERGY_STORAGE_BONUS_EU * energyStorageCount;
    }

    /**
     * Returns the electrical input voltage tier clamped to {@code [1, 5]} ({@code 1 + transformerCount}).
     *
     * @return voltage tier (1=LV, 2=MV, 3=HV, 4=EV, 5=IV)
     */
    public int inputTier() {
        int rawTier = LogisticsConstants.BASE_INPUT_TIER + transformerCount;
        return Math.max(LogisticsConstants.BASE_INPUT_TIER, Math.min(LogisticsConstants.MAX_INPUT_TIER, rawTier));
    }

    /**
     * Returns the maximum safe EU packet size per tick for the current {@link #inputTier()}:
     * {@code 32 << (2 * (tier - 1))} (32 / 128 / 512 / 2048 / 8192 EU/t).
     *
     * @return packet limit in EU/t
     */
    public int packetLimitEu() {
        return LogisticsConstants.LV_PACKET_LIMIT_EU << (2 * (inputTier() - 1));
    }

    /**
     * Returns {@code true} if at least one {@code lc2:chunk_loading_upgrade} is installed.
     *
     * @return {@code true} if chunk loading upgrade is present
     */
    public boolean hasChunkLoader() {
        return chunkLoadingCount > 0;
    }

    /**
     * Returns the standby EU/t drain required for active chunk loading ({@code 2.0} if installed, {@code 0.0} otherwise).
     *
     * @return standby drain in EU/t
     */
    public double chunkLoaderStandbyDrainEuPerTick() {
        return hasChunkLoader() ? LogisticsConstants.CHUNK_LOADER_STANDBY_DRAIN_EU_PER_TICK : 0.0;
    }

    /**
     * Returns {@code true} if the station should actively hold a 3x3 chunk-loading ticket given {@code currentEu}.
     *
     * @param currentEu current stored EU
     * @return {@code true} if chunk loader is installed and {@code currentEu >= 2.0}
     */
    public boolean shouldChunkLoad(double currentEu) {
        return hasChunkLoader() && currentEu >= chunkLoaderStandbyDrainEuPerTick();
    }

    /**
     * Returns {@code true} if at least one {@code ic2:redstone_inverter_upgrade} is installed.
     *
     * @return {@code true} when redstone logic is inverted
     */
    public boolean invertRedstone() {
        return redstoneInverterCount > 0;
    }

    /**
     * Evaluates whether the station's redstone dispatch condition is currently active.
     *
     * @param redstonePowered raw redstone signal state received by the station block
     * @return {@code !redstonePowered} if inverted, otherwise {@code redstonePowered}
     */
    public boolean isRedstoneTriggered(boolean redstonePowered) {
        return invertRedstone() ? !redstonePowered : redstonePowered;
    }

    /**
     * Returns the item transfer throughput in items per tick: {@code min(64, 4 + 8 * overclockerCount)}.
     *
     * @return items transferred per tick
     */
    public int transferRatePerTick() {
        long scaled = (long) LogisticsConstants.BASE_TRANSFER_RATE_PER_TICK
                + (long) LogisticsConstants.OVERCLOCKER_TRANSFER_BONUS_PER_TICK * overclockerCount;
        return (int) Math.min(LogisticsConstants.MAX_TRANSFER_RATE_PER_TICK, scaled);
    }

    /**
     * Returns the cruise speed multiplier applied to vehicles dispatched by this station:
     * {@code 1.0 + min(0.40, 0.08 * overclockerCount)}.
     *
     * @return speed multiplier in {@code [1.0, 1.4]}
     */
    public double speedMultiplier() {
        double bonus = Math.min(
                LogisticsConstants.MAX_OVERCLOCKER_SPEED_BONUS,
                LogisticsConstants.OVERCLOCKER_SPEED_BONUS_PER_UPGRADE * overclockerCount
        );
        return 1.0 + bonus;
    }

    /**
     * Returns {@code true} if any ejector upgrade (standard or advanced) is installed.
     *
     * @return {@code true} if ejector capability is active
     */
    public boolean hasEjector() {
        return ejectorCount > 0 || advancedEjectorCount > 0;
    }

    /**
     * Returns {@code true} if an advanced ejector upgrade is installed.
     *
     * @return {@code true} if advanced ejector is present
     */
    public boolean hasAdvancedEjector() {
        return advancedEjectorCount > 0;
    }

    /**
     * Returns {@code true} if any pulling upgrade (standard or advanced) is installed.
     *
     * @return {@code true} if pulling capability is active
     */
    public boolean hasPulling() {
        return pullingCount > 0 || advancedPullingCount > 0;
    }

    /**
     * Returns {@code true} if an advanced pulling upgrade is installed.
     *
     * @return {@code true} if advanced pulling is present
     */
    public boolean hasAdvancedPulling() {
        return advancedPullingCount > 0;
    }

    /**
     * Returns the total number of supported upgrades counted in this summary.
     *
     * @return total upgrade item count
     */
    public int totalUpgradeCount() {
        return transformerCount
                + energyStorageCount
                + overclockerCount
                + ejectorCount
                + advancedEjectorCount
                + pullingCount
                + advancedPullingCount
                + redstoneInverterCount
                + chunkLoadingCount;
    }

    /**
     * Mutable builder for accumulating upgrade item IDs and stack counts into an {@link UpgradeSummary}.
     */
    public static final class Builder {
        private int transformerCount;
        private int energyStorageCount;
        private int overclockerCount;
        private int ejectorCount;
        private int advancedEjectorCount;
        private int pullingCount;
        private int advancedPullingCount;
        private int redstoneInverterCount;
        private int chunkLoadingCount;

        private Builder() {
        }

        /**
         * Adds {@code count} items of {@code itemId} if {@code itemId} is a supported upgrade
         * and {@code count > 0}.
         *
         * @param itemId qualified item registry ID
         * @param count  stack size to add
         * @return this builder
         */
        public Builder add(String itemId, int count) {
            if (itemId == null || count <= 0) {
                return this;
            }
            switch (itemId) {
                case LogisticsConstants.UPGRADE_TRANSFORMER -> transformerCount += count;
                case LogisticsConstants.UPGRADE_ENERGY_STORAGE -> energyStorageCount += count;
                case LogisticsConstants.UPGRADE_OVERCLOCKER -> overclockerCount += count;
                case LogisticsConstants.UPGRADE_EJECTOR -> ejectorCount += count;
                case LogisticsConstants.UPGRADE_ADVANCED_EJECTOR -> advancedEjectorCount += count;
                case LogisticsConstants.UPGRADE_PULLING -> pullingCount += count;
                case LogisticsConstants.UPGRADE_ADVANCED_PULLING -> advancedPullingCount += count;
                case LogisticsConstants.UPGRADE_REDSTONE_INVERTER -> redstoneInverterCount += count;
                case LogisticsConstants.UPGRADE_CHUNK_LOADING -> chunkLoadingCount += count;
                default -> {
                    // Unsupported upgrade ID ignored.
                }
            }
            return this;
        }

        /**
         * Builds the immutable {@link UpgradeSummary}.
         *
         * @return immutable upgrade summary
         */
        public UpgradeSummary build() {
            return new UpgradeSummary(
                    transformerCount,
                    energyStorageCount,
                    overclockerCount,
                    ejectorCount,
                    advancedEjectorCount,
                    pullingCount,
                    advancedPullingCount,
                    redstoneInverterCount,
                    chunkLoadingCount
            );
        }
    }
}
