package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared zero-dependency NBT serialization and localized tooltip builder for Communication Base
 * Station HUD probes (The One Probe, Jade, and WTHIT).
 */
public final class CommProbeHelper {

    public static final String KEY_PRESENT = "lc2CommProbe";
    public static final String KEY_ONLINE = "lc2Online";
    public static final String KEY_EU = "lc2Eu";
    public static final String KEY_MAX_EU = "lc2MaxEu";
    public static final String KEY_ADJACENT = "lc2Adj";

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
            ).withStyle(ChatFormatting.GOLD));
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
            lines.add(Component.translatable("probe.lc2.comm.adjacent", joined).withStyle(ChatFormatting.GOLD));
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
                (long) Math.floor(eu),
                (long) Math.floor(maxEu),
                EnergyUnits.euToFeFloor(eu),
                EnergyUnits.euToFeFloor(maxEu)
        ).withStyle(ChatFormatting.AQUA);
    }
}
