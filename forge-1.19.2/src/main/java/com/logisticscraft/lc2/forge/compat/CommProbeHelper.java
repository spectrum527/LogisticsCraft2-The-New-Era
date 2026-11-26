package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared zero-dependency NBT serialization and localized tooltip builder for Communication Base
 * Station, Smart Port, and Cargo Station HUD probes (The One Probe, Jade, and WTHIT).
 */
public final class CommProbeHelper {

    public static final String KEY_PRESENT = "lc2CommProbe";
    public static final String KEY_ONLINE = "lc2Online";
    public static final String KEY_EU = "lc2Eu";
    public static final String KEY_MAX_EU = "lc2MaxEu";
    public static final String KEY_ADJACENT = "lc2Adj";

    public static final String KEY_STATION_PRESENT = "lc2StationProbe";
    public static final String KEY_STATION_IS_PORT = "lc2StationIsPort";
    public static final String KEY_STATION_EU = "lc2StationEu";
    public static final String KEY_STATION_MAX_EU = "lc2StationMaxEu";
    public static final String KEY_STATION_TIER = "lc2StationTier";
    public static final String KEY_STATION_LIMIT = "lc2StationLimit";
    public static final String KEY_STATION_STATUS = "lc2StationStatus";
    public static final String KEY_STATION_CHUNK_LOADER = "lc2StationChunkLoader";
    public static final String KEY_STATION_HAS_CHUNK_UPG = "lc2StationHasChunkUpg";
    public static final String KEY_STATION_BOAT_DOCKED = "lc2StationBoatDocked";
    public static final String KEY_STATION_DRONES_READY = "lc2StationDronesReady";
    public static final String KEY_STATION_RF_RANGE = "lc2StationRfRange";

    private CommProbeHelper() {
    }

    /** Writes server-authoritative Comm Base Station status, energy, and adjacent stations into {@code tag}. */
    public static void writeProbeData(CompoundTag tag, ServerLevel level, CommBaseStationBlockEntity comm) {
        tag.putBoolean(KEY_PRESENT, true);
        tag.putBoolean(KEY_ONLINE, comm.isOnline());
        tag.putDouble(KEY_EU, comm.getStoredEu());
        tag.putDouble(KEY_MAX_EU, comm.maxCapacityEu());

        List<TerminalTargetResolver.AdjacentStationInfo> adjacent = comm.findAdjacentStations(level);
        ListTag adjTag = new ListTag();
        for (TerminalTargetResolver.AdjacentStationInfo info : adjacent) {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", info.customName());
            entry.putInt("type", info.type().ordinal());
            entry.putInt("x", info.pos().x());
            entry.putInt("y", info.pos().y());
            entry.putInt("z", info.pos().z());
            adjTag.add(entry);
        }
        tag.put(KEY_ADJACENT, adjTag);
    }

    /** Returns {@code true} if {@code tag} carries serialized Comm Base Station probe data. */
    public static boolean hasProbeData(CompoundTag tag) {
        return tag != null && tag.getBoolean(KEY_PRESENT);
    }

    /** Builds localized tooltip lines directly on the server (used by The One Probe). */
    public static List<Component> buildProbeLines(ServerLevel level, CommBaseStationBlockEntity comm) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(statusLine(comm.isOnline()));
        lines.add(energyLine(comm.getStoredEu(), comm.maxCapacityEu()));

        List<TerminalTargetResolver.AdjacentStationInfo> adjacent = comm.findAdjacentStations(level);
        if (adjacent.isEmpty()) {
            lines.add(Component.translatable("probe.lc2.comm.adjacent_none").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable(
                    "probe.lc2.comm.adjacent",
                    CommBaseStationBlock.formatAdjacentList(adjacent)
            ).withStyle(ChatFormatting.AQUA));
        }
        return lines;
    }

    /** Builds localized tooltip lines from synced server NBT (used by Jade and WTHIT). */
    public static List<Component> buildProbeLines(CompoundTag tag) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(statusLine(tag.getBoolean(KEY_ONLINE)));
        lines.add(energyLine(tag.getDouble(KEY_EU), tag.getDouble(KEY_MAX_EU)));

        ListTag adjTag = tag.getList(KEY_ADJACENT, Tag.TAG_COMPOUND);
        if (adjTag.isEmpty()) {
            lines.add(Component.translatable("probe.lc2.comm.adjacent_none").withStyle(ChatFormatting.GRAY));
        } else {
            MutableComponent joined = Component.empty();
            StationType[] types = StationType.values();
            for (int i = 0; i < adjTag.size(); i++) {
                if (i > 0) {
                    joined.append(Component.literal(", "));
                }
                CompoundTag entry = adjTag.getCompound(i);
                String name = entry.getString("name");
                if (name != null && !name.isBlank()) {
                    joined.append(Component.literal(name));
                } else {
                    int ord = entry.getInt("type");
                    StationType type = (ord >= 0 && ord < types.length) ? types[ord] : StationType.SMART_PORT;
                    String key = type == StationType.SMART_PORT
                            ? "gui.lc2.terminal.unnamed_port"
                            : "gui.lc2.terminal.unnamed_cargo";
                    joined.append(Component.translatable(
                            key,
                            entry.getInt("x"),
                            entry.getInt("y"),
                            entry.getInt("z")
                    ));
                }
            }
            lines.add(Component.translatable("probe.lc2.comm.adjacent", joined).withStyle(ChatFormatting.AQUA));
        }
        return lines;
    }

    private static Component statusLine(boolean online) {
        return Component.translatable(online ? "probe.lc2.comm.status_online" : "probe.lc2.comm.status_offline")
                .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component energyLine(double eu, double maxEu) {
        return Component.translatable(
                "probe.lc2.comm.energy",
                Math.round(eu),
                Math.round(maxEu),
                EnergyUnits.euToFeFloor(eu),
                EnergyUnits.euToFeFloor(maxEu)
        ).withStyle(ChatFormatting.YELLOW);
    }

    /** Writes server-authoritative Smart Port / Cargo Station probe fields into {@code tag}. */
    public static void writeStationProbeData(
            CompoundTag tag,
            @Nullable ServerLevel level,
            AbstractLogisticsStationBlockEntity station
    ) {
        UpgradeSummary upg = station.getUpgrades();
        boolean isPort = station instanceof SmartPortBlockEntity;
        tag.putBoolean(KEY_STATION_PRESENT, true);
        tag.putBoolean(KEY_STATION_IS_PORT, isPort);
        tag.putDouble(KEY_STATION_EU, station.getStoredEu());
        tag.putDouble(KEY_STATION_MAX_EU, station.maxCapacityEu());
        tag.putInt(KEY_STATION_TIER, upg.inputTier());
        tag.putInt(KEY_STATION_LIMIT, upg.packetLimitEu());
        tag.putString(KEY_STATION_STATUS, station.getStatusKey());
        tag.putBoolean(KEY_STATION_CHUNK_LOADER, station.isChunkLoaderActive());
        tag.putBoolean(KEY_STATION_HAS_CHUNK_UPG, upg.hasChunkLoader());
        if (station instanceof SmartPortBlockEntity port) {
            tag.putBoolean(KEY_STATION_BOAT_DOCKED, level != null && port.findDockedBoat(level) != null);
        } else if (station instanceof CargoStationBlockEntity cargo) {
            tag.putInt(KEY_STATION_DRONES_READY, Integer.bitCount(cargo.getDockedBayMask()));
            RfChipTier chip = cargo.getRfChipTier();
            tag.putInt(KEY_STATION_RF_RANGE, chip.isInstalled() ? chip.maxRange() : 0);
        }
    }

    /** Overload kept for backwards compatibility when no level reference is supplied. */
    public static void writeStationProbeData(CompoundTag tag, AbstractLogisticsStationBlockEntity station) {
        writeStationProbeData(
                tag,
                station.getLevel() instanceof ServerLevel sl ? sl : null,
                station
        );
    }

    /** Returns {@code true} if {@code tag} carries serialized logistics station probe data. */
    public static boolean hasStationProbeData(CompoundTag tag) {
        return tag != null && tag.getBoolean(KEY_STATION_PRESENT);
    }

    /** Builds localized tooltip lines for a Smart Port or Cargo Station directly on the server (TOP). */
    public static List<Component> buildStationProbeLines(
            @Nullable ServerLevel level,
            AbstractLogisticsStationBlockEntity station
    ) {
        UpgradeSummary upg = station.getUpgrades();
        boolean isPort = station instanceof SmartPortBlockEntity;
        boolean boatDocked = station instanceof SmartPortBlockEntity port
                && level != null
                && port.findDockedBoat(level) != null;
        int readyDrones = station instanceof CargoStationBlockEntity cargo
                ? Integer.bitCount(cargo.getDockedBayMask())
                : 0;
        int rfRange = station instanceof CargoStationBlockEntity cargo && cargo.getRfChipTier().isInstalled()
                ? cargo.getRfChipTier().maxRange()
                : 0;
        return buildStationLines(
                isPort,
                station.getStoredEu(),
                station.maxCapacityEu(),
                upg.inputTier(),
                upg.packetLimitEu(),
                upg.hasChunkLoader(),
                station.isChunkLoaderActive(),
                boatDocked,
                readyDrones,
                rfRange,
                station.getStatusKey()
        );
    }

    /** Overload kept for backwards compatibility when no level reference is supplied. */
    public static List<Component> buildStationProbeLines(AbstractLogisticsStationBlockEntity station) {
        return buildStationProbeLines(
                station.getLevel() instanceof ServerLevel sl ? sl : null,
                station
        );
    }

    /** Builds localized tooltip lines for a Smart Port or Cargo Station from synced NBT (Jade/WTHIT). */
    public static List<Component> buildStationProbeLines(CompoundTag tag) {
        return buildStationLines(
                tag.getBoolean(KEY_STATION_IS_PORT),
                tag.getDouble(KEY_STATION_EU),
                tag.getDouble(KEY_STATION_MAX_EU),
                tag.getInt(KEY_STATION_TIER),
                tag.getInt(KEY_STATION_LIMIT),
                tag.getBoolean(KEY_STATION_HAS_CHUNK_UPG),
                tag.getBoolean(KEY_STATION_CHUNK_LOADER),
                tag.getBoolean(KEY_STATION_BOAT_DOCKED),
                tag.getInt(KEY_STATION_DRONES_READY),
                tag.getInt(KEY_STATION_RF_RANGE),
                tag.getString(KEY_STATION_STATUS)
        );
    }

    private static List<Component> buildStationLines(
            boolean isPort,
            double eu,
            double maxEu,
            int tier,
            int limit,
            boolean hasChunkUpgrade,
            boolean chunkLoaderActive,
            boolean boatDocked,
            int readyDrones,
            int rfRange,
            String statusKey
    ) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable(
                "probe.lc2.station.energy",
                Math.round(eu),
                Math.round(maxEu),
                tier,
                limit
        ).withStyle(ChatFormatting.YELLOW));

        if (isPort) {
            lines.add(Component.translatable(
                    boatDocked ? "gui.lc2.berth_docked" : "gui.lc2.berth_empty"
            ).withStyle(boatDocked ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        } else {
            Component rfComp = rfRange > 0
                    ? Component.translatable("gui.lc2.rf_range", rfRange)
                    : Component.translatable("gui.lc2.rf_none");
            lines.add(Component.translatable("gui.lc2.flight_deck_ready", readyDrones)
                    .append(Component.literal(" | "))
                    .append(rfComp)
                    .withStyle(readyDrones > 0 && rfRange > 0 ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        }

        if (hasChunkUpgrade || chunkLoaderActive) {
            lines.add(Component.translatable(
                    chunkLoaderActive ? "gui.lc2.chunk_loader_active" : "gui.lc2.chunk_loader_inactive"
            ).withStyle(chunkLoaderActive ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        String safeKey = (statusKey == null || statusKey.isBlank()) ? "status.lc2.ready" : statusKey;
        lines.add(Component.translatable(
                "probe.lc2.station.status",
                Component.translatable(safeKey)
        ).withStyle(safeKey.contains(".failure.") ? ChatFormatting.RED : ChatFormatting.GRAY));
        return lines;
    }
}
