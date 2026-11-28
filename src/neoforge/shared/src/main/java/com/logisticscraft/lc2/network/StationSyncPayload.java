package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client synchronization payload delivering live EU readings, configuration,
 * status feedback, RF range, chunk-loader status, and same-dimension compatible target list
 * to an open Smart Port or Cargo Station screen.
 */
public record StationSyncPayload(
        BlockPos stationPos,
        String stationName,
        double storedEu,
        double maxEu,
        int inputTier,
        int packetLimitEu,
        boolean autoReturn,
        int energyPriorityOrdinal,
        boolean hasTarget,
        BlockPos targetPos,
        String statusKey,
        boolean chunkLoaderActive,
        boolean vehicleReady,
        int readyVehicleCount,
        int rfRange,
        List<TargetInfo> targets
) implements CustomPacketPayload {

    public static final Type<StationSyncPayload> TYPE = Lc2Native.payloadType("station_sync");

    /** Compact summary of one compatible target station in the same dimension. */
    public record TargetInfo(
            BlockPos pos,
            String displayName,
            int distance,
            boolean reachableFlag,
            int freeBays
    ) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, StationSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.stationPos);
                        buf.writeUtf(p.stationName == null ? "" : p.stationName, 64);
                        buf.writeDouble(p.storedEu);
                        buf.writeDouble(p.maxEu);
                        buf.writeVarInt(p.inputTier);
                        buf.writeVarInt(p.packetLimitEu);
                        buf.writeBoolean(p.autoReturn);
                        buf.writeVarInt(p.energyPriorityOrdinal);
                        buf.writeBoolean(p.hasTarget);
                        buf.writeBlockPos(p.targetPos == null ? BlockPos.ZERO : p.targetPos);
                        buf.writeUtf(p.statusKey == null ? "status.trera.ready" : p.statusKey, 128);
                        buf.writeBoolean(p.chunkLoaderActive);
                        buf.writeBoolean(p.vehicleReady);
                        buf.writeVarInt(p.readyVehicleCount);
                        buf.writeVarInt(p.rfRange);
                        int count = Math.min(p.targets.size(), 256);
                        buf.writeVarInt(count);
                        for (int i = 0; i < count; i++) {
                            TargetInfo info = p.targets.get(i);
                            buf.writeBlockPos(info.pos());
                            buf.writeUtf(info.displayName(), 64);
                            buf.writeVarInt(info.distance());
                            buf.writeBoolean(info.reachableFlag());
                            buf.writeVarInt(info.freeBays());
                        }
                    },
                    buf -> {
                        BlockPos stationPos = buf.readBlockPos();
                        String name = buf.readUtf(64);
                        double storedEu = buf.readDouble();
                        double maxEu = buf.readDouble();
                        int tier = buf.readVarInt();
                        int packetLimit = buf.readVarInt();
                        boolean autoReturn = buf.readBoolean();
                        int priorityOrd = buf.readVarInt();
                        boolean hasTarget = buf.readBoolean();
                        BlockPos targetPos = buf.readBlockPos();
                        String statusKey = buf.readUtf(128);
                        boolean chunkLoaderActive = buf.readBoolean();
                        boolean vehicleReady = buf.readBoolean();
                        int readyVehicleCount = buf.readVarInt();
                        int rfRange = buf.readVarInt();
                        int count = Math.min(Math.max(0, buf.readVarInt()), 256);
                        List<TargetInfo> targets = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            targets.add(new TargetInfo(
                                    buf.readBlockPos(),
                                    buf.readUtf(64),
                                    buf.readVarInt(),
                                    buf.readBoolean(),
                                    buf.readVarInt()
                            ));
                        }
                        return new StationSyncPayload(
                                stationPos, name, storedEu, maxEu, tier, packetLimit,
                                autoReturn, priorityOrd, hasTarget, targetPos, statusKey,
                                chunkLoaderActive, vehicleReady, readyVehicleCount, rfRange, List.copyOf(targets)
                        );
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Returns the decoded {@link EnergyPriority} enum value. */
    public EnergyPriority energyPriority() {
        return EnergyPriority.fromOrdinal(energyPriorityOrdinal);
    }

    /** Looks up the {@link TargetInfo} matching {@code targetPos}, if present in {@code targets}. */
    public TargetInfo selectedTargetInfo() {
        if (!hasTarget) {
            return null;
        }
        for (TargetInfo info : targets) {
            if (info.pos().equals(targetPos)) {
                return info;
            }
        }
        return null;
    }
}
