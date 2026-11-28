package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client-to-server action packet for the Operation Terminal selection screen, global keybind,
 * remote station connect/unbind actions, and returning to the terminal list from a remote station.
 */
public record C2STerminalActionPacket(
        Action action,
        String terminalId,
        String dimensionId,
        BlockPos commPos,
        BlockPos stationPos
) {

    public enum Action {
        OPEN_FROM_KEYBIND,
        REFRESH,
        CONNECT,
        UNBIND,
        RETURN_TO_LIST
    }

    public C2STerminalActionPacket {
        terminalId = terminalId == null ? "" : terminalId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        commPos = commPos == null ? BlockPos.ZERO : commPos;
        stationPos = stationPos == null ? BlockPos.ZERO : stationPos;
    }

    public static C2STerminalActionPacket openFromKeybind() {
        return new C2STerminalActionPacket(Action.OPEN_FROM_KEYBIND, "", "", BlockPos.ZERO, BlockPos.ZERO);
    }

    public static C2STerminalActionPacket refresh(String terminalId) {
        return new C2STerminalActionPacket(Action.REFRESH, terminalId, "", BlockPos.ZERO, BlockPos.ZERO);
    }

    public static C2STerminalActionPacket connect(String terminalId, BlockPos commPos, BlockPos stationPos) {
        return new C2STerminalActionPacket(Action.CONNECT, terminalId, "", commPos, stationPos);
    }

    public static C2STerminalActionPacket unbind(String terminalId, String dimensionId, BlockPos commPos) {
        return new C2STerminalActionPacket(Action.UNBIND, terminalId, dimensionId, commPos, BlockPos.ZERO);
    }

    public static C2STerminalActionPacket returnToList(String terminalId) {
        return new C2STerminalActionPacket(Action.RETURN_TO_LIST, terminalId, "", BlockPos.ZERO, BlockPos.ZERO);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(terminalId, 64);
        buf.writeUtf(dimensionId, 128);
        buf.writeBlockPos(commPos);
        buf.writeBlockPos(stationPos);
    }

    public static C2STerminalActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String terminalId = buf.readUtf(64);
        String dimensionId = buf.readUtf(128);
        BlockPos commPos = buf.readBlockPos();
        BlockPos stationPos = buf.readBlockPos();
        return new C2STerminalActionPacket(action, terminalId, dimensionId, commPos, stationPos);
    }

    public static void handle(C2STerminalActionPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.isSpectator()) {
                return;
            }
            switch (packet.action) {
                case OPEN_FROM_KEYBIND -> handleOpenFromKeybind(player);
                case REFRESH -> handleRefresh(player, packet.terminalId);
                case CONNECT -> handleConnect(player, packet.terminalId, packet.commPos, packet.stationPos);
                case UNBIND -> handleUnbind(player, packet.terminalId, packet.dimensionId, packet.commPos);
                case RETURN_TO_LIST -> handleReturnToList(player, packet.terminalId);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleOpenFromKeybind(ServerPlayer player) {
        TerminalSessionService.FoundTerminal found = TerminalSessionService.findTerminal(player, null);
        if (found == null) {
            player.displayClientMessage(Component.translatable("status.trera.terminal.no_terminal_found"), true);
            return;
        }
        TerminalSessionService.sendTerminalSync(player, found.stack(), true);
    }

    private static void handleRefresh(ServerPlayer player, String terminalId) {
        TerminalSessionService.FoundTerminal found = TerminalSessionService.findTerminal(player, terminalId);
        if (found == null) {
            player.displayClientMessage(Component.translatable("status.trera.terminal.no_terminal_found"), true);
            return;
        }
        TerminalSessionService.sendTerminalSync(player, found.stack(), false);
    }

    private static void handleConnect(ServerPlayer player, String terminalId, BlockPos commPos, BlockPos stationPos) {
        String currentDim = player.getLevel().dimension().location().toString();
        TerminalSessionService.FoundTerminal found = TerminalSessionService.findTerminal(player, terminalId);
        RemoteDisconnectReason reason = TerminalSessionService.validateRemoteSession(
                player,
                currentDim,
                commPos,
                stationPos,
                terminalId,
                true
        );
        if (reason.isDisconnected()) {
            player.displayClientMessage(Component.translatable(reason.translationKey()), true);
            if (found != null) {
                TerminalSessionService.sendTerminalSync(player, found.stack(), false);
            }
            return;
        }
        String resolvedId = found != null
                ? OperationTerminalItem.getOrCreateTerminalId(found.stack())
                : terminalId;
        if (!TerminalSessionService.openRemoteStation(player, commPos, stationPos, resolvedId)) {
            player.displayClientMessage(
                    Component.translatable(RemoteDisconnectReason.STATION_MISSING.translationKey()),
                    true
            );
            if (found != null) {
                TerminalSessionService.sendTerminalSync(player, found.stack(), false);
            }
        }
    }

    private static void handleUnbind(
            ServerPlayer player,
            String terminalId,
            String dimensionId,
            BlockPos commPos
    ) {
        TerminalSessionService.FoundTerminal found = TerminalSessionService.findTerminal(player, terminalId);
        if (found == null) {
            player.displayClientMessage(Component.translatable("status.trera.terminal.no_terminal_found"), true);
            return;
        }
        String targetDim = dimensionId.isBlank()
                ? player.getLevel().dimension().location().toString()
                : dimensionId;
        if (OperationTerminalItem.unbind(found.stack(), targetDim, commPos)) {
            found.markDirty();
            player.displayClientMessage(
                    Component.translatable(
                            "status.trera.terminal.unbound",
                            commPos.getX(),
                            commPos.getY(),
                            commPos.getZ()
                    ),
                    true
            );
        }
        TerminalSessionService.sendTerminalSync(player, found.stack(), false);
    }

    private static void handleReturnToList(ServerPlayer player, String terminalId) {
        player.closeContainer();
        TerminalSessionService.FoundTerminal found = TerminalSessionService.findTerminal(player, terminalId);
        if (found == null) {
            found = TerminalSessionService.findTerminal(player, null);
        }
        if (found == null) {
            player.displayClientMessage(Component.translatable("status.trera.terminal.no_terminal_found"), true);
            return;
        }
        TerminalSessionService.sendTerminalSync(player, found.stack(), true);
    }
}
