package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared probe telemetry serialization and client-localized tooltip formatting used by
 * The One Probe, Jade, and WTHIT without importing any optional mod APIs.
 */
public final class Lc2ProbeHelper {

    public static final String KEY_PRESENT = "lc2CommPresent";
    public static final String KEY_ONLINE = "lc2CommOnline";
    public static final String KEY_EU = "lc2CommEu";
    public static final String KEY_MAX_EU = "lc2CommMaxEu";
    public static final String KEY_ADJACENT = "lc2CommAdjacent";
    public static final String KEY_ADJACENT_LIST = "lc2CommAdjList";

    public static final String KEY_ST_PRESENT = "lc2StPresent";
    public static final String KEY_ST_IS_PORT = "lc2StIsPort";
    public static final String KEY_ST_EU = "lc2StEu";
    public static final String KEY_ST_MAX_EU = "lc2StMaxEu";
    public static final String KEY_ST_TIER = "lc2StTier";
    public static final String KEY_ST_PACKET = "lc2StPacket";
    public static final String KEY_ST_CHUNK_ACTIVE = "lc2StChunkActive";
    public static final String KEY_ST_HAS_CHUNK_UPG = "lc2StHasChunkUpg";
    public static final String KEY_ST_BOAT_DOCKED = "lc2StBoatDocked";
    public static final String KEY_ST_DRONES_READY = "lc2StDronesReady";
    public static final String KEY_ST_RF_RANGE = "lc2StRfRange";
    public static final String KEY_ST_STATUS = "lc2StStatus";

    private Lc2ProbeHelper() {
    }

    /** Encodes live Communication Base Station telemetry into {@code tag} on the server (locale-independent). */
    public static void writeCommStationData(
            CompoundTag tag,
            ServerLevel level,
            CommBaseStationBlockEntity comm
    ) {
        tag.putBoolean(KEY_PRESENT, true);
        tag.putBoolean(KEY_ONLINE, comm.isOnline());
        tag.putDouble(KEY_EU, comm.getStoredEu());
        tag.putDouble(KEY_MAX_EU, comm.getMaxEu());
        ListTag adjList = new ListTag();
        for (TerminalTargetResolver.AdjacentStationInfo info : comm.findAdjacentStations(level)) {
            CompoundTag entry = new CompoundTag();
            entry.putBoolean("port", info.type() == StationType.SMART_PORT);
            entry.putString("name", info.customName() == null ? "" : info.customName());
            entry.putInt("x", info.pos().x());
            entry.putInt("y", info.pos().y());
            entry.putInt("z", info.pos().z());
            adjList.add(entry);
        }
        tag.put(KEY_ADJACENT_LIST, adjList);
    }

    /** Builds client-localized probe lines for a Communication Base Station from synced NBT data. */
    public static List<Component> readCommStationLines(CompoundTag tag) {
        if (tag == null || !Lc2Nbt.getBoolean(tag, KEY_PRESENT, false)) {
            return List.of();
        }
        boolean online = Lc2Nbt.getBoolean(tag, KEY_ONLINE, false);
        double eu = Lc2Nbt.getDouble(tag, KEY_EU, 0.0);
        double maxEu = Lc2Nbt.getDouble(tag, KEY_MAX_EU, LogisticsConstants.COMM_BASE_CAPACITY_EU);
        List<CompoundTag> adjEntries = Lc2Nbt.getCompoundList(tag, KEY_ADJACENT_LIST);
        if (!adjEntries.isEmpty()) {
            MutableComponent adjComp = Component.empty();
            for (int i = 0; i < adjEntries.size(); i++) {
                CompoundTag entry = adjEntries.get(i);
                if (i > 0) {
                    adjComp.append(Component.literal(", "));
                }
                String name = Lc2Nbt.getString(entry, "name", "");
                if (!name.isBlank()) {
                    adjComp.append(Component.literal(name));
                } else {
                    boolean isPort = Lc2Nbt.getBoolean(entry, "port", true);
                    String key = isPort ? "gui.trera.terminal.unnamed_port" : "gui.trera.terminal.unnamed_cargo";
                    adjComp.append(Component.translatable(
                            key,
                            Lc2Nbt.getInt(entry, "x", 0),
                            Lc2Nbt.getInt(entry, "y", 0),
                            Lc2Nbt.getInt(entry, "z", 0)
                    ));
                }
            }
            return buildCommStationLines(online, eu, maxEu, adjComp);
        }
        String legacyAdjacent = Lc2Nbt.getString(tag, KEY_ADJACENT, "");
        return buildCommStationLines(online, eu, maxEu, legacyAdjacent);
    }

    /** Builds formatted status, EU/FE energy, and adjacent station lines using a localized Component. */
    public static List<Component> buildCommStationLines(
            boolean online,
            double storedEu,
            double maxEu,
            Component adjacentComponent
    ) {
        Component statusLine = Component.translatable(
                online ? "probe.trera.comm.status_online" : "probe.trera.comm.status_offline"
        ).withStyle(online ? ChatFormatting.GREEN : ChatFormatting.RED);

        Component energyLine = Component.translatable(
                "probe.trera.comm.energy",
                Math.round(storedEu),
                Math.round(maxEu),
                EnergyUnits.euToFeFloor(storedEu),
                EnergyUnits.euToFeFloor(maxEu)
        ).withStyle(ChatFormatting.YELLOW);

        Component adjacentLine = adjacentComponent == null
                ? Component.translatable("probe.trera.comm.adjacent_none").withStyle(ChatFormatting.GRAY)
                : Component.translatable("probe.trera.comm.adjacent", adjacentComponent).withStyle(ChatFormatting.AQUA);

        return List.of(statusLine, energyLine, adjacentLine);
    }

    /** Builds formatted status, EU/FE energy, and adjacent station lines directly from a string summary. */
    public static List<Component> buildCommStationLines(
            boolean online,
            double storedEu,
            double maxEu,
            String adjacentSummary
    ) {
        Component adj = (adjacentSummary == null || adjacentSummary.isBlank())
                ? null
                : Component.literal(adjacentSummary);
        return buildCommStationLines(online, storedEu, maxEu, adj);
    }

    /** Encodes live Smart Port or Cargo Station telemetry into {@code tag} on the server. */
    public static void writeStationData(
            CompoundTag tag,
            ServerLevel level,
            AbstractLogisticsStationBlockEntity station
    ) {
        tag.putBoolean(KEY_ST_PRESENT, true);
        tag.putBoolean(KEY_ST_IS_PORT, station instanceof SmartPortBlockEntity);
        tag.putDouble(KEY_ST_EU, station.getStoredEu());
        tag.putDouble(KEY_ST_MAX_EU, station.getMaxEu());
        tag.putInt(KEY_ST_TIER, station.getUpgrades().inputTier());
        tag.putInt(KEY_ST_PACKET, station.getUpgrades().packetLimitEu());
        tag.putBoolean(KEY_ST_CHUNK_ACTIVE, station.isChunkLoaderActive());
        tag.putBoolean(KEY_ST_HAS_CHUNK_UPG, station.getUpgrades().hasChunkLoader());
        tag.putString(KEY_ST_STATUS, station.getStatusKey());
        if (station instanceof SmartPortBlockEntity port) {
            tag.putBoolean(KEY_ST_BOAT_DOCKED, port.findDockedBoat(level).isPresent());
        } else if (station instanceof CargoStationBlockEntity cargo) {
            tag.putInt(KEY_ST_DRONES_READY, cargo.countReadyDrones());
            RfChipTier chip = cargo.installedRfChip();
            tag.putInt(KEY_ST_RF_RANGE, chip.isInstalled() ? chip.maxRange() : 0);
        }
    }

    /** Builds client-localized probe lines for a Smart Port or Cargo Station from synced NBT data. */
    public static List<Component> readStationLines(CompoundTag tag) {
        if (tag == null || !Lc2Nbt.getBoolean(tag, KEY_ST_PRESENT, false)) {
            return List.of();
        }
        return buildStationLines(
                Lc2Nbt.getBoolean(tag, KEY_ST_IS_PORT, true),
                Lc2Nbt.getDouble(tag, KEY_ST_EU, 0.0),
                Lc2Nbt.getDouble(tag, KEY_ST_MAX_EU, LogisticsConstants.BASE_ENERGY_CAPACITY_EU),
                Lc2Nbt.getInt(tag, KEY_ST_TIER, LogisticsConstants.BASE_INPUT_TIER),
                Lc2Nbt.getInt(tag, KEY_ST_PACKET, LogisticsConstants.LV_PACKET_LIMIT_EU),
                Lc2Nbt.getBoolean(tag, KEY_ST_HAS_CHUNK_UPG, false),
                Lc2Nbt.getBoolean(tag, KEY_ST_CHUNK_ACTIVE, false),
                Lc2Nbt.getBoolean(tag, KEY_ST_BOAT_DOCKED, false),
                Lc2Nbt.getInt(tag, KEY_ST_DRONES_READY, 0),
                Lc2Nbt.getInt(tag, KEY_ST_RF_RANGE, 0),
                Lc2Nbt.getString(tag, KEY_ST_STATUS, "status.trera.ready")
        );
    }

    /** Builds live Smart Port or Cargo Station probe lines directly on the server (for TOP). */
    public static List<Component> buildStationLines(
            ServerLevel level,
            AbstractLogisticsStationBlockEntity station
    ) {
        boolean isPort = station instanceof SmartPortBlockEntity;
        boolean boatDocked = station instanceof SmartPortBlockEntity port && port.findDockedBoat(level).isPresent();
        int readyDrones = station instanceof CargoStationBlockEntity cargo ? cargo.countReadyDrones() : 0;
        int rfRange = station instanceof CargoStationBlockEntity cargo && cargo.installedRfChip().isInstalled()
                ? cargo.installedRfChip().maxRange()
                : 0;
        return buildStationLines(
                isPort,
                station.getStoredEu(),
                station.getMaxEu(),
                station.getUpgrades().inputTier(),
                station.getUpgrades().packetLimitEu(),
                station.getUpgrades().hasChunkLoader(),
                station.isChunkLoaderActive(),
                boatDocked,
                readyDrones,
                rfRange,
                station.getStatusKey()
        );
    }

    private static List<Component> buildStationLines(
            boolean isPort,
            double storedEu,
            double maxEu,
            int tier,
            int packetLimit,
            boolean hasChunkUpgrade,
            boolean chunkActive,
            boolean boatDocked,
            int readyDrones,
            int rfRange,
            String statusKey
    ) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable(
                "probe.trera.station.energy",
                Math.round(storedEu),
                Math.round(maxEu),
                tier,
                packetLimit
        ).withStyle(ChatFormatting.YELLOW));

        if (isPort) {
            lines.add(Component.translatable(
                    boatDocked ? "gui.trera.berth_docked" : "gui.trera.berth_empty"
            ).withStyle(boatDocked ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        } else {
            Component rfComp = rfRange > 0
                    ? Component.translatable("gui.trera.rf_range", rfRange)
                    : Component.translatable("gui.trera.rf_none");
            lines.add(Component.translatable("gui.trera.flight_deck_ready", readyDrones)
                    .append(Component.literal(" | "))
                    .append(rfComp)
                    .withStyle(readyDrones > 0 && rfRange > 0 ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        }

        if (hasChunkUpgrade || chunkActive) {
            lines.add(Component.translatable(
                    chunkActive ? "gui.trera.chunk_loader_active" : "gui.trera.chunk_loader_inactive"
            ).withStyle(chunkActive ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        String safeStatus = (statusKey == null || statusKey.isBlank()) ? "status.trera.ready" : statusKey;
        lines.add(Component.translatable("probe.trera.station.status", Component.translatable(safeStatus))
                .withStyle(safeStatus.contains(".failure.") ? ChatFormatting.RED : ChatFormatting.GRAY));
        return lines;
    }
}
