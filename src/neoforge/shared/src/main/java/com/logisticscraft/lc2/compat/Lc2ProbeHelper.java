package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared probe telemetry serialization and tooltip line formatting used by The One Probe,
 * Jade, and WTHIT without importing any optional mod APIs.
 */
public final class Lc2ProbeHelper {

    public static final String KEY_PRESENT = "lc2CommPresent";
    public static final String KEY_ONLINE = "lc2CommOnline";
    public static final String KEY_EU = "lc2CommEu";
    public static final String KEY_MAX_EU = "lc2CommMaxEu";
    public static final String KEY_ADJACENT = "lc2CommAdjacent";

    private Lc2ProbeHelper() {
    }

    /** Encodes live Communication Base Station telemetry into {@code tag} on the server. */
    public static void writeCommStationData(
            CompoundTag tag,
            ServerLevel level,
            CommBaseStationBlockEntity comm
    ) {
        tag.putBoolean(KEY_PRESENT, true);
        tag.putBoolean(KEY_ONLINE, comm.isOnline());
        tag.putDouble(KEY_EU, comm.getStoredEu());
        tag.putDouble(KEY_MAX_EU, comm.getMaxEu());
        tag.putString(KEY_ADJACENT, comm.formatAdjacentSummary(level));
    }

    /** Builds formatted probe lines from synced NBT data, or empty if no data is present. */
    public static List<Component> readCommStationLines(CompoundTag tag) {
        if (tag == null || !Lc2Nbt.getBoolean(tag, KEY_PRESENT, false)) {
            return List.of();
        }
        boolean online = Lc2Nbt.getBoolean(tag, KEY_ONLINE, false);
        double eu = Lc2Nbt.getDouble(tag, KEY_EU, 0.0);
        double maxEu = Lc2Nbt.getDouble(tag, KEY_MAX_EU, LogisticsConstants.COMM_BASE_CAPACITY_EU);
        String adjacent = Lc2Nbt.getString(tag, KEY_ADJACENT, "");
        return buildCommStationLines(online, eu, maxEu, adjacent);
    }

    /** Builds formatted status, EU/FE energy, and adjacent station lines directly from values. */
    public static List<Component> buildCommStationLines(
            boolean online,
            double storedEu,
            double maxEu,
            String adjacentSummary
    ) {
        Component statusLine = Component.translatable(
                online ? "probe.lc2.comm.status_online" : "probe.lc2.comm.status_offline"
        ).withStyle(online ? ChatFormatting.GREEN : ChatFormatting.RED);

        Component energyLine = Component.translatable(
                "probe.lc2.comm.energy",
                Math.round(storedEu),
                Math.round(maxEu),
                EnergyUnits.euToFeFloor(storedEu),
                EnergyUnits.euToFeFloor(maxEu)
        ).withStyle(ChatFormatting.YELLOW);

        Component adjacentLine = (adjacentSummary == null || adjacentSummary.isBlank())
                ? Component.translatable("probe.lc2.comm.adjacent_none").withStyle(ChatFormatting.GRAY)
                : Component.translatable("probe.lc2.comm.adjacent", adjacentSummary).withStyle(ChatFormatting.AQUA);

        return List.of(statusLine, energyLine, adjacentLine);
    }
}
