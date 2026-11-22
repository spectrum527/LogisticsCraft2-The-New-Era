package com.logisticscraft.lc2.core;

/**
 * Preferred energy source ordering when paying for a vehicle dispatch.
 */
public enum EnergyPriority {

    /** Prefer internal EU buffer first; fall back to single-use batteries only if EU is insufficient. */
    EU_FIRST("gui.lc2.priority.eu_first"),

    /** Prefer single-use batteries first; fall back to internal EU only if batteries are insufficient. */
    BATTERY_FIRST("gui.lc2.priority.battery_first");

    private final String translationKey;

    EnergyPriority(String translationKey) {
        this.translationKey = translationKey;
    }

    /**
     * Returns the localization key representing this priority mode in station GUIs.
     *
     * @return translation key
     */
    public String translationKey() {
        return translationKey;
    }

    /**
     * Returns the alternate priority mode.
     *
     * @return toggled priority
     */
    public EnergyPriority toggle() {
        return this == EU_FIRST ? BATTERY_FIRST : EU_FIRST;
    }

    /**
     * Resolves an {@link EnergyPriority} from a persisted ordinal, defaulting to {@link #EU_FIRST}.
     *
     * @param ordinal persisted ordinal
     * @return priority value
     */
    public static EnergyPriority fromOrdinal(int ordinal) {
        return ordinal == BATTERY_FIRST.ordinal() ? BATTERY_FIRST : EU_FIRST;
    }
}
