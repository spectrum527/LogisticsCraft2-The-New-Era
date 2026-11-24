package com.logisticscraft.lc2.core;

/**
 * Pure utility for EU &lt;-&gt; FE energy unit conversion and safe EU-backed buffer charging/deduction
 * using the canonical {@code 1 EU = 4 FE} ratio ({@link LogisticsConstants#FE_PER_EU}).
 */
public final class EnergyUnits {

    /** Universal FE-per-EU conversion factor ({@code 1 EU = 4 FE}). */
    public static final int FE_PER_EU = LogisticsConstants.FE_PER_EU;

    /** Floating-point tolerance applied when checking terminal dispatch affordability. */
    public static final double EPSILON = 1e-6;

    private EnergyUnits() {
    }

    /**
     * Result of receiving Forge/NeoForge Energy (FE) into an EU-backed storage buffer.
     *
     * @param acceptedFe  integer FE actually accepted (or that would be accepted when simulating)
     * @param newStoredEu resulting EU stored in the buffer (unchanged from input when simulating)
     */
    public record FeTransferResult(int acceptedFe, double newStoredEu) {

        /**
         * Compact constructor validating transfer invariants.
         */
        public FeTransferResult {
            if (acceptedFe < 0) {
                throw new IllegalArgumentException("acceptedFe must be >= 0, got " + acceptedFe);
            }
            if (!Double.isFinite(newStoredEu) || newStoredEu < 0.0) {
                throw new IllegalArgumentException("newStoredEu must be finite and >= 0, got " + newStoredEu);
            }
        }
    }

    /**
     * Result of receiving IndustrialCraft² Energy Units (EU) into an EU-backed storage buffer.
     *
     * @param acceptedEu  EU amount actually accepted into the buffer
     * @param newStoredEu resulting EU stored in the buffer
     */
    public record EuTransferResult(double acceptedEu, double newStoredEu) {

        /**
         * Compact constructor validating transfer invariants.
         */
        public EuTransferResult {
            if (!Double.isFinite(acceptedEu) || acceptedEu < 0.0) {
                throw new IllegalArgumentException("acceptedEu must be finite and >= 0, got " + acceptedEu);
            }
            if (!Double.isFinite(newStoredEu) || newStoredEu < 0.0) {
                throw new IllegalArgumentException("newStoredEu must be finite and >= 0, got " + newStoredEu);
            }
        }
    }

    /**
     * Converts a non-negative EU amount to FE, rounding down ({@code floor}),
     * clamped to {@code [0, Integer.MAX_VALUE]}.
     *
     * @param eu energy amount in EU
     * @return floored equivalent in FE
     */
    public static int euToFeFloor(double eu) {
        if (Double.isNaN(eu) || eu <= 0.0) {
            return 0;
        }
        double scaled = eu * FE_PER_EU;
        if (Double.isInfinite(scaled) || scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(scaled);
    }

    /**
     * Converts a non-negative EU amount to FE, rounding up ({@code ceil}),
     * clamped to {@code [0, Integer.MAX_VALUE]}.
     *
     * @param eu energy amount in EU
     * @return ceiled equivalent in FE
     */
    public static int euToFeCeil(double eu) {
        if (Double.isNaN(eu) || eu <= 0.0) {
            return 0;
        }
        double scaled = eu * FE_PER_EU;
        if (Double.isInfinite(scaled) || scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(scaled);
    }

    /**
     * Converts a non-negative integer FE amount to EU ({@code fe / 4.0}).
     * Non-positive inputs return {@code 0.0}.
     *
     * @param fe energy amount in FE
     * @return exact equivalent in EU
     */
    public static double feToEu(int fe) {
        if (fe <= 0) {
            return 0.0;
        }
        return fe / (double) FE_PER_EU;
    }

    /**
     * Converts a per-tick EU packet/transfer limit to the corresponding FE/t limit ({@code euPerTick * 4}),
     * clamped to {@code [0, Integer.MAX_VALUE]}.
     *
     * @param euPerTick transfer limit in EU/t
     * @return transfer limit in FE/t
     */
    public static int maxFeTransferForEuLimit(int euPerTick) {
        if (euPerTick <= 0) {
            return 0;
        }
        long scaled = (long) euPerTick * FE_PER_EU;
        return scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    /**
     * Computes an FE energy insertion into an EU-backed buffer while enforcing the per-packet FE limit
     * ({@code maxPacketEu * 4}), integer FE accounting, and strict non-overflow against {@code maxEu}.
     * <p>
     * When {@code simulate} is {@code true}, {@link FeTransferResult#acceptedFe()} reports how much FE
     * would be accepted while {@link FeTransferResult#newStoredEu()} preserves the unmodified current EU.
     *
     * @param currentEu   current stored EU in the buffer
     * @param maxEu       maximum EU buffer capacity
     * @param maxPacketEu maximum EU/t packet limit (converted to FE/t via {@link #maxFeTransferForEuLimit})
     * @param offeredFe   FE offered by the external sender
     * @param simulate    {@code true} to simulate without mutating the resulting stored EU
     * @return transfer result containing accepted FE and resulting stored EU
     */
    public static FeTransferResult receiveFeIntoEuBuffer(
            double currentEu,
            double maxEu,
            int maxPacketEu,
            int offeredFe,
            boolean simulate
    ) {
        double safeCurrent = sanitizeEnergy(currentEu);
        if (!Double.isFinite(maxEu) || maxEu <= 0.0 || maxPacketEu <= 0 || offeredFe <= 0 || safeCurrent >= maxEu) {
            return new FeTransferResult(0, safeCurrent);
        }
        int packetCapFe = maxFeTransferForEuLimit(maxPacketEu);
        double remainingEu = Math.max(0.0, maxEu - safeCurrent);
        int roomFe = euToFeFloor(remainingEu);
        int acceptedFe = Math.min(offeredFe, Math.min(packetCapFe, roomFe));
        if (acceptedFe <= 0) {
            return new FeTransferResult(0, safeCurrent);
        }
        if (simulate) {
            return new FeTransferResult(acceptedFe, safeCurrent);
        }
        double updatedEu = Math.min(maxEu, safeCurrent + feToEu(acceptedFe));
        return new FeTransferResult(acceptedFe, updatedEu);
    }

    /**
     * Computes an EU energy insertion into an EU-backed buffer, clamped to remaining room so the
     * buffer never exceeds {@code maxEu}.
     *
     * @param currentEu current stored EU in the buffer
     * @param maxEu     maximum EU buffer capacity
     * @param offeredEu EU offered to the buffer
     * @return transfer result containing accepted EU and resulting stored EU
     */
    public static EuTransferResult receiveEuIntoBuffer(double currentEu, double maxEu, double offeredEu) {
        double safeCurrent = sanitizeEnergy(currentEu);
        if (!Double.isFinite(maxEu) || maxEu <= 0.0
                || !Double.isFinite(offeredEu) || offeredEu <= 0.0
                || safeCurrent >= maxEu) {
            return new EuTransferResult(0.0, safeCurrent);
        }
        double remainingEu = Math.max(0.0, maxEu - safeCurrent);
        double acceptedEu = Math.min(offeredEu, remainingEu);
        double updatedEu = Math.min(maxEu, safeCurrent + acceptedEu);
        return new EuTransferResult(acceptedEu, updatedEu);
    }

    /**
     * Returns whether an Operation Terminal with {@code storedEu} can afford the remote dispatch cost
     * ({@value LogisticsConstants#TERMINAL_DISPATCH_COST_EU} EU / 400 FE), applying a {@code 1e-6} EU
     * floating-point tolerance.
     *
     * @param storedEu terminal's current stored EU
     * @return {@code true} if {@code storedEu + 1e-6 >= 100.0}
     */
    public static boolean canAffordTerminalDispatch(double storedEu) {
        if (!Double.isFinite(storedEu)) {
            return false;
        }
        return storedEu + EPSILON >= LogisticsConstants.TERMINAL_DISPATCH_COST_EU;
    }

    /**
     * Deducts the remote dispatch cost ({@value LogisticsConstants#TERMINAL_DISPATCH_COST_EU} EU)
     * from {@code storedEu}, clamping the result at {@code 0.0}. Callers should check
     * {@link #canAffordTerminalDispatch(double)} before invoking a remote dispatch.
     *
     * @param storedEu terminal's stored EU prior to dispatch
     * @return remaining EU after deduction (always {@code >= 0.0})
     */
    public static double deductTerminalDispatch(double storedEu) {
        double safeCurrent = sanitizeEnergy(storedEu);
        return Math.max(0.0, safeCurrent - LogisticsConstants.TERMINAL_DISPATCH_COST_EU);
    }

    private static double sanitizeEnergy(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}
