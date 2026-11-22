package com.logisticscraft.lc2.core;

/**
 * Radio-frequency chip tiers controlling Cargo Station maximum drone Manhattan dispatch range.
 */
public enum RfChipTier {

    /** No RF chip installed; station cannot dispatch drones. */
    NONE(0, null),

    /** RF Chip I ({@code lc2:rf_chip_1}): max Manhattan range 256 blocks. */
    TIER_1(LogisticsConstants.DRONE_RANGE_BAND_1, LogisticsConstants.RF_CHIP_1_ID),

    /** RF Chip II ({@code lc2:rf_chip_2}): max Manhattan range 512 blocks. */
    TIER_2(LogisticsConstants.DRONE_RANGE_BAND_2, LogisticsConstants.RF_CHIP_2_ID),

    /** RF Chip III ({@code lc2:rf_chip_3}): max Manhattan range 1024 blocks. */
    TIER_3(LogisticsConstants.DRONE_RANGE_BAND_3, LogisticsConstants.RF_CHIP_3_ID);

    private final int maxRange;
    private final String itemId;

    RfChipTier(int maxRange, String itemId) {
        this.maxRange = maxRange;
        this.itemId = itemId;
    }

    /**
     * Returns the maximum Manhattan dispatch range in blocks.
     *
     * @return max range (0, 256, 512, or 1024)
     */
    public int maxRange() {
        return maxRange;
    }

    /**
     * Returns the canonical qualified item ID for this tier, or {@code null} for {@link #NONE}.
     *
     * @return item ID or {@code null}
     */
    public String itemId() {
        return itemId;
    }

    /**
     * Returns {@code true} if an actual RF chip is installed (i.e. not {@link #NONE}).
     *
     * @return {@code true} when installed
     */
    public boolean isInstalled() {
        return this != NONE;
    }

    /**
     * Returns {@code true} if this chip is installed and covers the specified Manhattan distance.
     *
     * @param manhattanDistance non-negative Manhattan distance between stations
     * @return {@code true} if within range
     */
    public boolean canReach(int manhattanDistance) {
        return isInstalled() && manhattanDistance >= 0 && manhattanDistance <= maxRange;
    }

    /**
     * Resolves an {@link RfChipTier} from a qualified item registry ID.
     * Returns {@link #NONE} for {@code null} or unrecognized item IDs.
     *
     * @param itemId qualified item ID
     * @return matching chip tier or {@link #NONE}
     */
    public static RfChipTier fromItemId(String itemId) {
        if (itemId == null) {
            return NONE;
        }
        for (RfChipTier tier : values()) {
            if (itemId.equals(tier.itemId)) {
                return tier;
            }
        }
        return NONE;
    }

    /**
     * Returns the tier corresponding to a numeric index ({@code 0..3}), defaulting to {@link #NONE}.
     *
     * @param index tier index
     * @return matching tier
     */
    public static RfChipTier fromTierIndex(int index) {
        return switch (index) {
            case 1 -> TIER_1;
            case 2 -> TIER_2;
            case 3 -> TIER_3;
            default -> NONE;
        };
    }
}
