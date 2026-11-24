package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.forge.client.Lc2ClientHandlers;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-to-client synchronization packet carrying live Operation Terminal energy, binding count,
 * and resolved target station rows. Strictly client-safe via {@link DistExecutor}.
 */
public record S2CTerminalSyncPacket(
        String terminalId,
        boolean openScreen,
        double storedEu,
        double maxEu,
        int boundCount,
        List<TerminalTargetEntry> entries
) {

    private static final int MAX_WIRE_ENTRIES = LogisticsConstants.TERMINAL_MAX_BINDINGS * 6;

    public S2CTerminalSyncPacket {
        terminalId = terminalId == null ? "" : terminalId;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(terminalId, 64);
        buf.writeBoolean(openScreen);
        buf.writeDouble(storedEu);
        buf.writeDouble(maxEu);
        buf.writeVarInt(boundCount);

        int count = Math.min(entries.size(), MAX_WIRE_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            TerminalTargetEntry entry = entries.get(i);
            buf.writeBlockPos(Lc2Coords.toMc(entry.commPos()));
            buf.writeUtf(entry.dimensionId(), 128);
            buf.writeBoolean(entry.hasStation());
            if (entry.hasStation()) {
                buf.writeBlockPos(Lc2Coords.toMc(entry.stationPos()));
                buf.writeVarInt(entry.stationType().ordinal());
            }
            buf.writeUtf(entry.stationName(), 64);
            buf.writeVarInt(entry.status().ordinal());
            buf.writeVarInt(entry.distance());
        }
    }

    public static S2CTerminalSyncPacket decode(FriendlyByteBuf buf) {
        String terminalId = buf.readUtf(64);
        boolean openScreen = buf.readBoolean();
        double storedEu = buf.readDouble();
        double maxEu = buf.readDouble();
        int boundCount = buf.readVarInt();

        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_WIRE_ENTRIES);
        List<TerminalTargetEntry> entries = new ArrayList<>(count);
        StationType[] stationTypes = StationType.values();
        for (int i = 0; i < count; i++) {
            BlockPos3i commPos = Lc2Coords.toCore(buf.readBlockPos());
            String dimId = buf.readUtf(128);
            boolean hasStation = buf.readBoolean();
            BlockPos3i stationPos = null;
            StationType stationType = null;
            if (hasStation) {
                stationPos = Lc2Coords.toCore(buf.readBlockPos());
                int typeOrd = buf.readVarInt();
                stationType = (typeOrd >= 0 && typeOrd < stationTypes.length)
                        ? stationTypes[typeOrd]
                        : StationType.SMART_PORT;
            }
            String stationName = buf.readUtf(64);
            CommStationStatus status = CommStationStatus.fromOrdinal(buf.readVarInt());
            int distance = Math.max(-1, buf.readVarInt());
            entries.add(new TerminalTargetEntry(
                    commPos,
                    dimId.isBlank() ? "minecraft:overworld" : dimId,
                    hasStation,
                    stationPos,
                    stationType,
                    stationName,
                    status,
                    distance
            ));
        }
        return new S2CTerminalSyncPacket(terminalId, openScreen, storedEu, maxEu, boundCount, entries);
    }

    public static void handle(S2CTerminalSyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> Lc2ClientHandlers.handleTerminalSync(packet)
        ));
        ctx.setPacketHandled(true);
    }
}
