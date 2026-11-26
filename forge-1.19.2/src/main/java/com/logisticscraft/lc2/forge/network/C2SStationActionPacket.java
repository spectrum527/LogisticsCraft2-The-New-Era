package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.gui.AbstractStationMenu;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client-to-server control packet for Smart Port and Cargo Station GUI interactions,
 * supporting both local proximity access and authenticated Operation Terminal remote sessions.
 */
public record C2SStationActionPacket(
        BlockPos stationPos,
        Action action,
        String textArg,
        boolean boolArg,
        int xArg,
        int yArg,
        int zArg
) {

    public enum Action {
        SET_NAME,
        SET_TARGET,
        CYCLE_TARGET,
        TOGGLE_AUTO_RETURN,
        TOGGLE_ENERGY_PRIORITY,
        DISPATCH,
        SWAP_CARGO_ZONES,
        REQUEST_SYNC
    }

    public static C2SStationActionPacket setName(BlockPos pos, String name) {
        return new C2SStationActionPacket(pos, Action.SET_NAME, name == null ? "" : name, false, 0, 0, 0);
    }

    public static C2SStationActionPacket setTarget(BlockPos pos, boolean hasTarget, int x, int y, int z) {
        return new C2SStationActionPacket(pos, Action.SET_TARGET, "", hasTarget, x, y, z);
    }

    public static C2SStationActionPacket cycleTarget(BlockPos pos, int delta) {
        return new C2SStationActionPacket(pos, Action.CYCLE_TARGET, "", false, delta, 0, 0);
    }

    public static C2SStationActionPacket simple(BlockPos pos, Action action) {
        return new C2SStationActionPacket(pos, action, "", false, 0, 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(stationPos);
        buf.writeEnum(action);
        buf.writeUtf(textArg, 64);
        buf.writeBoolean(boolArg);
        buf.writeVarInt(xArg);
        buf.writeVarInt(yArg);
        buf.writeVarInt(zArg);
    }

    public static C2SStationActionPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Action action = buf.readEnum(Action.class);
        String text = buf.readUtf(64);
        boolean flag = buf.readBoolean();
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        return new C2SStationActionPacket(pos, action, text, flag, x, y, z);
    }

    public static void handle(C2SStationActionPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.isSpectator()) {
                return;
            }
            ServerLevel level = player.getLevel();
            if (!level.isLoaded(packet.stationPos)) {
                return;
            }

            AbstractStationMenu remoteMenu = null;
            if (player.containerMenu instanceof AbstractStationMenu menu
                    && menu.isRemoteMode()
                    && menu.getStationPos().equals(packet.stationPos)
                    && menu.stillValid(player)) {
                remoteMenu = menu;
            }
            boolean localAccess = player.distanceToSqr(Vec3.atCenterOf(packet.stationPos)) <= 64.0;
            if (!localAccess && remoteMenu == null) {
                return;
            }

            BlockEntity be = level.getBlockEntity(packet.stationPos);
            if (!(be instanceof AbstractLogisticsStationBlockEntity station)) {
                return;
            }
            switch (packet.action) {
                case SET_NAME -> station.setStationName(packet.textArg);
                case SET_TARGET -> {
                    if (packet.boolArg) {
                        int cx = Mth.clamp(packet.xArg, -30_000_000, 30_000_000);
                        int cy = Mth.clamp(packet.yArg, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
                        int cz = Mth.clamp(packet.zArg, -30_000_000, 30_000_000);
                        station.setTargetPos(new BlockPos(cx, cy, cz));
                    } else {
                        station.setTargetPos(null);
                    }
                }
                case CYCLE_TARGET -> station.cycleTarget(level, packet.xArg >= 0 ? 1 : -1);
                case TOGGLE_AUTO_RETURN -> station.toggleAutoReturn();
                case TOGGLE_ENERGY_PRIORITY -> station.toggleEnergyPriority();
                case DISPATCH -> handleDispatch(level, player, station, remoteMenu);
                case SWAP_CARGO_ZONES -> {
                    station.swapCargoZones();
                    if (player.containerMenu != null) {
                        player.containerMenu.broadcastChanges();
                    }
                }
                case REQUEST_SYNC -> {
                    if (station instanceof SmartPortBlockEntity port) {
                        port.requestWaterCheckIfStale(level);
                    }
                }
            }
            station.sendSyncTo(player);
        });
        ctx.setPacketHandled(true);
    }

    private static void handleDispatch(
            ServerLevel level,
            ServerPlayer player,
            AbstractLogisticsStationBlockEntity station,
            AbstractStationMenu remoteMenu
    ) {
        if (remoteMenu == null) {
            station.tryDispatch(level, true);
            return;
        }
        TerminalSessionService.FoundTerminal found =
                TerminalSessionService.findTerminal(player, remoteMenu.getRemoteTerminalId());
        if (found == null) {
            player.displayClientMessage(
                    Component.translatable(RemoteDisconnectReason.TERMINAL_MISSING.translationKey()),
                    true
            );
            return;
        }
        double currentEu = OperationTerminalItem.getStoredEu(found.stack());
        if (!player.isCreative() && !EnergyUnits.canAffordTerminalDispatch(currentEu)) {
            String key = DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY.translationKey();
            station.setStatusKey(key);
            player.displayClientMessage(Component.translatable(key), true);
            return;
        }
        boolean dispatched = station.tryDispatch(level, true);
        if (dispatched && !player.isCreative()) {
            double remaining = EnergyUnits.deductTerminalDispatch(OperationTerminalItem.getStoredEu(found.stack()));
            OperationTerminalItem.setStoredEu(found.stack(), remaining);
            found.markDirty();
        }
    }
}
