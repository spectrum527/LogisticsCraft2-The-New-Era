package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server control payload for Smart Port and Cargo Station GUI actions.
 */
public record StationActionPayload(
        BlockPos stationPos,
        Action action,
        String textArg,
        BlockPos targetArg
) implements CustomPacketPayload {

    public static final Type<StationActionPayload> TYPE = Lc2Native.payloadType("station_action");

    /** Supported station GUI commands sent from client to server. */
    public enum Action {
        RENAME,
        PREV_TARGET,
        NEXT_TARGET,
        SELECT_TARGET,
        SET_TARGET_COORDS,
        TOGGLE_AUTO_RETURN,
        TOGGLE_ENERGY_PRIORITY,
        MANUAL_DISPATCH,
        REQUEST_SYNC;

        public static Action fromOrdinal(int ordinal) {
            Action[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                return REQUEST_SYNC;
            }
            return values[ordinal];
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, StationActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.stationPos);
                        buf.writeVarInt(payload.action.ordinal());
                        buf.writeUtf(payload.textArg == null ? "" : payload.textArg, 64);
                        buf.writeBlockPos(payload.targetArg == null ? BlockPos.ZERO : payload.targetArg);
                    },
                    buf -> new StationActionPayload(
                            buf.readBlockPos(),
                            Action.fromOrdinal(buf.readVarInt()),
                            buf.readUtf(64),
                            buf.readBlockPos()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Convenience factory for actions without extra arguments. */
    public static StationActionPayload simple(BlockPos stationPos, Action action) {
        return new StationActionPayload(stationPos, action, "", BlockPos.ZERO);
    }

    /** Convenience factory for station rename commands. */
    public static StationActionPayload rename(BlockPos stationPos, String newName) {
        return new StationActionPayload(stationPos, Action.RENAME, newName == null ? "" : newName, BlockPos.ZERO);
    }

    /** Convenience factory for setting explicit target coordinates. */
    public static StationActionPayload setTarget(BlockPos stationPos, BlockPos target) {
        return new StationActionPayload(stationPos, Action.SET_TARGET_COORDS, "", target);
    }
}
