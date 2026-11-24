package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client payload delivering resolved Communication Base Station targets and live
 * Operation Terminal energy readings to the Operation Terminal Selection GUI.
 */
public record TerminalSyncPayload(
        String terminalId,
        boolean openScreen,
        double storedEu,
        double maxEu,
        int boundCount,
        List<TerminalTargetEntry> entries
) implements CustomPacketPayload {

    public static final Type<TerminalSyncPayload> TYPE = Lc2Native.payloadType("terminal_sync");

    private static final int MAX_WIRE_ENTRIES = LogisticsConstants.TERMINAL_MAX_BINDINGS * 6;

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.terminalId == null ? "" : p.terminalId, 64);
                        buf.writeBoolean(p.openScreen);
                        buf.writeDouble(p.storedEu);
                        buf.writeDouble(p.maxEu);
                        buf.writeVarInt(Math.max(0, p.boundCount));
                        int count = Math.min(p.entries.size(), MAX_WIRE_ENTRIES);
                        buf.writeVarInt(count);
                        for (int i = 0; i < count; i++) {
                            TerminalTargetEntry e = p.entries.get(i);
                            buf.writeBlockPos(LogisticsSavedData.toBlockPos(e.commPos()));
                            buf.writeUtf(e.dimensionId() == null ? "" : e.dimensionId(), 128);
                            buf.writeBoolean(e.hasStation());
                            if (e.hasStation()) {
                                buf.writeBlockPos(LogisticsSavedData.toBlockPos(e.stationPos()));
                                buf.writeVarInt(e.stationType() == null ? 0 : e.stationType().ordinal());
                                buf.writeUtf(e.stationName() == null ? "" : e.stationName(), 64);
                            }
                            buf.writeVarInt(e.status().ordinal());
                            buf.writeVarInt(e.distance());
                        }
                    },
                    buf -> {
                        String terminalId = buf.readUtf(64);
                        boolean openScreen = buf.readBoolean();
                        double storedEu = buf.readDouble();
                        double maxEu = buf.readDouble();
                        int boundCount = Math.max(0, buf.readVarInt());
                        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_WIRE_ENTRIES);
                        List<TerminalTargetEntry> entries = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            BlockPos3i commPos = LogisticsSavedData.toPos3i(buf.readBlockPos());
                            String dimId = buf.readUtf(128);
                            boolean hasStation = buf.readBoolean();
                            BlockPos3i stationPos = null;
                            StationType stationType = null;
                            String stationName = "";
                            if (hasStation) {
                                stationPos = LogisticsSavedData.toPos3i(buf.readBlockPos());
                                int typeOrd = buf.readVarInt();
                                StationType[] types = StationType.values();
                                stationType = (typeOrd >= 0 && typeOrd < types.length)
                                        ? types[typeOrd]
                                        : StationType.SMART_PORT;
                                stationName = buf.readUtf(64);
                            }
                            CommStationStatus status = CommStationStatus.fromOrdinal(buf.readVarInt());
                            int distance = buf.readVarInt();
                            entries.add(new TerminalTargetEntry(
                                    commPos,
                                    dimId,
                                    hasStation,
                                    stationPos,
                                    stationType,
                                    stationName,
                                    status,
                                    distance
                            ));
                        }
                        return new TerminalSyncPayload(
                                terminalId,
                                openScreen,
                                storedEu,
                                maxEu,
                                boundCount,
                                List.copyOf(entries)
                        );
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
