package com.logisticscraft.lc2.core;

/**
 * Reason why an active remote station session opened via an Operation Terminal was terminated,
 * or {@link #NONE} when the session remains healthy.
 */
public enum RemoteDisconnectReason {

    /** Session is healthy and remains connected. */
    NONE("status.trera.ready"),

    /** Player changed to a different dimension than the remote station. */
    WRONG_DIMENSION("status.trera.remote.disconnected.wrong_dimension"),

    /** Chunk containing either the Communication Base Station or target station unloaded. */
    CHUNK_UNLOADED("status.trera.remote.disconnected.chunk_unloaded"),

    /** Communication Base Station block was broken or removed. */
    COMM_MISSING("status.trera.remote.disconnected.comm_missing"),

    /** Communication Base Station ran out of standby power. */
    COMM_UNPOWERED("status.trera.remote.disconnected.comm_unpowered"),

    /** Target Smart Port or Cargo Station block was broken or removed. */
    STATION_MISSING("status.trera.remote.disconnected.station_missing"),

    /** Communication Base Station and target station are no longer face-adjacent. */
    NOT_ADJACENT("status.trera.remote.disconnected.not_adjacent"),

    /** Player no longer carries or equips an Operation Terminal. */
    TERMINAL_MISSING("status.trera.remote.disconnected.terminal_missing");

    private final String translationKey;

    RemoteDisconnectReason(String translationKey) {
        this.translationKey = translationKey;
    }

    /**
     * Returns the localization key shown to the player upon disconnect (or {@code "status.trera.ready"}
     * for {@link #NONE}).
     *
     * @return translation key
     */
    public String translationKey() {
        return translationKey;
    }

    /**
     * Returns {@code true} if this reason represents an actual disconnect (i.e. not {@link #NONE}).
     *
     * @return {@code true} when disconnected
     */
    public boolean isDisconnected() {
        return this != NONE;
    }

    /**
     * Resolves a {@link RemoteDisconnectReason} from a serialized ordinal, defaulting to {@link #COMM_MISSING}.
     *
     * @param ordinal serialized enum ordinal
     * @return matching reason or {@link #COMM_MISSING}
     */
    public static RemoteDisconnectReason fromOrdinal(int ordinal) {
        RemoteDisconnectReason[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return COMM_MISSING;
    }
}
