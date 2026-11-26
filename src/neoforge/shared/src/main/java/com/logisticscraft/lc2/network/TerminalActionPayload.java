package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server payload for Operation Terminal actions (keybind open, refresh, connect,
 * unbind, and returning from a remote station screen back to the terminal list).
 */
public record TerminalActionPayload(
        Action action,
        String terminalId,
        String dimensionId,
        BlockPos commPos,
        BlockPos stationPos
) implements CustomPacketPayload {

    public static final Type<TerminalActionPayload> TYPE = Lc2Native.payloadType("terminal_action");

    /** Supported Operation Terminal C2S commands. */
    public enum Action {
        OPEN_FROM_KEYBIND,
        REFRESH,
        CONNECT,
        UNBIND,
        RETURN_TO_LIST;

        public static Action fromOrdinal(int ordinal) {
            Action[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                return REFRESH;
            }
            return values[ordinal];
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.action.ordinal());
                        buf.writeUtf(p.terminalId == null ? "" : p.terminalId, 64);
                        buf.writeUtf(p.dimensionId == null ? "" : p.dimensionId, 128);
                        buf.writeBlockPos(p.commPos == null ? BlockPos.ZERO : p.commPos);
                        buf.writeBlockPos(p.stationPos == null ? BlockPos.ZERO : p.stationPos);
                    },
                    buf -> new TerminalActionPayload(
                            Action.fromOrdinal(buf.readVarInt()),
                            buf.readUtf(64),
                            buf.readUtf(128),
                            buf.readBlockPos(),
                            buf.readBlockPos()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Requests the server to locate an Operation Terminal in inventory/Curios and open it. */
    public static TerminalActionPayload openFromKeybind() {
        return new TerminalActionPayload(Action.OPEN_FROM_KEYBIND, "", "", BlockPos.ZERO, BlockPos.ZERO);
    }

    /** Requests a live re-probe of all bound Communication Base Stations for {@code terminalId}. */
    public static TerminalActionPayload refresh(String terminalId) {
        return new TerminalActionPayload(Action.REFRESH, terminalId == null ? "" : terminalId, "", BlockPos.ZERO, BlockPos.ZERO);
    }

    /** Requests opening a remote read-only station GUI via {@code commPos -> stationPos}. */
    public static TerminalActionPayload connect(String terminalId, BlockPos commPos, BlockPos stationPos) {
        return new TerminalActionPayload(
                Action.CONNECT,
                terminalId == null ? "" : terminalId,
                "",
                commPos == null ? BlockPos.ZERO : commPos,
                stationPos == null ? BlockPos.ZERO : stationPos
        );
    }

    /** Requests unbinding {@code (dimensionId, commPos)} from {@code terminalId}. */
    public static TerminalActionPayload unbind(String terminalId, String dimensionId, BlockPos commPos) {
        return new TerminalActionPayload(
                Action.UNBIND,
                terminalId == null ? "" : terminalId,
                dimensionId == null ? "" : dimensionId,
                commPos == null ? BlockPos.ZERO : commPos,
                BlockPos.ZERO
        );
    }

    /** Requests unbinding {@code commPos} from {@code terminalId} in the player's current dimension. */
    public static TerminalActionPayload unbind(String terminalId, BlockPos commPos) {
        return unbind(terminalId, "", commPos);
    }

    /** Requests closing the current remote station menu and reopening the Terminal Selection GUI. */
    public static TerminalActionPayload returnToList(String terminalId) {
        return new TerminalActionPayload(Action.RETURN_TO_LIST, terminalId == null ? "" : terminalId, "", BlockPos.ZERO, BlockPos.ZERO);
    }
}
