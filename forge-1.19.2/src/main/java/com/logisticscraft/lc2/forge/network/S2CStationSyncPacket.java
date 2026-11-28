package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.forge.client.Lc2ClientHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-to-client synchronization packet carrying live station telemetry, settings, status,
 * and same-dimension compatible target station list for Smart Port & Cargo Station screens.
 */
public record S2CStationSyncPacket(
        BlockPos stationPos,
        String stationName,
        boolean hasTarget,
        BlockPos targetPos,
        boolean autoReturn,
        EnergyPriority energyPriority,
        double storedEu,
        double maxEu,
        int inputTier,
        int packetLimit,
        boolean chunkLoaderActive,
        boolean vehicleReady,
        boolean hasWaterBerth,
        boolean targetWaterConnected,
        int rfChipTierIndex,
        String statusKey,
        List<TargetSummary> targets
) {

    /**
     * Compact target station entry sent to the GUI selector.
     */
    public record TargetSummary(
            BlockPos pos,
            String displayName,
            int manhattanDistance,
            int freeBays,
            int maxBays,
            boolean hasWaterBerth
    ) {
    }

    public S2CStationSyncPacket {
        stationName = stationName == null ? "" : stationName;
        statusKey = statusKey == null ? "status.trera.ready" : statusKey;
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(stationPos);
        buf.writeUtf(stationName, 64);
        buf.writeBoolean(hasTarget);
        buf.writeBlockPos(targetPos);
        buf.writeBoolean(autoReturn);
        buf.writeVarInt(energyPriority.ordinal());
        buf.writeDouble(storedEu);
        buf.writeDouble(maxEu);
        buf.writeVarInt(inputTier);
        buf.writeVarInt(packetLimit);
        buf.writeBoolean(chunkLoaderActive);
        buf.writeBoolean(vehicleReady);
        buf.writeBoolean(hasWaterBerth);
        buf.writeBoolean(targetWaterConnected);
        buf.writeVarInt(rfChipTierIndex);
        buf.writeUtf(statusKey, 128);

        int count = Math.min(targets.size(), 256);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            TargetSummary t = targets.get(i);
            buf.writeBlockPos(t.pos());
            buf.writeUtf(t.displayName(), 64);
            buf.writeVarInt(t.manhattanDistance());
            buf.writeVarInt(t.freeBays());
            buf.writeVarInt(t.maxBays());
            buf.writeBoolean(t.hasWaterBerth());
        }
    }

    public static S2CStationSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos stationPos = buf.readBlockPos();
        String stationName = buf.readUtf(64);
        boolean hasTarget = buf.readBoolean();
        BlockPos targetPos = buf.readBlockPos();
        boolean autoReturn = buf.readBoolean();
        EnergyPriority priority = EnergyPriority.fromOrdinal(buf.readVarInt());
        double storedEu = buf.readDouble();
        double maxEu = buf.readDouble();
        int inputTier = buf.readVarInt();
        int packetLimit = buf.readVarInt();
        boolean chunkLoaderActive = buf.readBoolean();
        boolean vehicleReady = buf.readBoolean();
        boolean hasWaterBerth = buf.readBoolean();
        boolean targetWaterConnected = buf.readBoolean();
        int rfChipTierIndex = buf.readVarInt();
        String statusKey = buf.readUtf(128);

        int count = Math.min(Math.max(0, buf.readVarInt()), 256);
        List<TargetSummary> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos tPos = buf.readBlockPos();
            String name = buf.readUtf(64);
            int dist = buf.readVarInt();
            int freeBays = buf.readVarInt();
            int maxBays = buf.readVarInt();
            boolean hasBerth = buf.readBoolean();
            targets.add(new TargetSummary(tPos, name, dist, freeBays, maxBays, hasBerth));
        }

        return new S2CStationSyncPacket(
                stationPos,
                stationName,
                hasTarget,
                targetPos,
                autoReturn,
                priority,
                storedEu,
                maxEu,
                inputTier,
                packetLimit,
                chunkLoaderActive,
                vehicleReady,
                hasWaterBerth,
                targetWaterConnected,
                rfChipTierIndex,
                statusKey,
                targets
        );
    }

    public static void handle(S2CStationSyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> Lc2ClientHandlers.handleStationSync(packet)
        ));
        ctx.setPacketHandled(true);
    }
}
