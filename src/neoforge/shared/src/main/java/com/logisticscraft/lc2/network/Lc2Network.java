package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.menu.AbstractLogisticsStationMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * CustomPacketPayload registration and server/client handlers for LC2 station networking.
 */
public final class Lc2Network {

    private static final String PROTOCOL_VERSION = "2";

    private Lc2Network() {
    }

    /** Registers C2S and S2C payloads on the mod event bus. */
    public static void install(IEventBus modBus, Dist side) {
        Lc2Native.registerPayloads(
                modBus,
                side,
                PROTOCOL_VERSION,
                StationActionPayload.TYPE,
                StationActionPayload.STREAM_CODEC,
                Lc2Network::handleServerAction,
                StationSyncPayload.TYPE,
                StationSyncPayload.STREAM_CODEC,
                Lc2Network::handleClientSync
        );
    }

    private static void handleServerAction(StationActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = payload.stationPos();
        if (!level.hasChunkAt(pos)) {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity station)) {
            return;
        }

        switch (payload.action()) {
            case RENAME -> station.setStationName(payload.textArg());
            case PREV_TARGET -> station.cycleTarget(level, -1);
            case NEXT_TARGET -> station.cycleTarget(level, 1);
            case SELECT_TARGET, SET_TARGET_COORDS -> station.setTargetPos(payload.targetArg());
            case TOGGLE_AUTO_RETURN -> station.setAutoReturn(!station.isAutoReturn());
            case TOGGLE_ENERGY_PRIORITY -> station.setEnergyPriority(station.getEnergyPriority().toggle());
            case MANUAL_DISPATCH -> station.handleManualDispatch(level);
            case SWAP_CARGO_ZONES -> {
                station.swapCargoZones();
                if (player.containerMenu instanceof AbstractLogisticsStationMenu) {
                    player.containerMenu.broadcastChanges();
                }
            }
            case REQUEST_SYNC -> {
                // Only refresh and send sync below
            }
        }
        Lc2Native.sendToPlayer(player, station.buildSyncPayload(level));
    }

    private static void handleClientSync(StationSyncPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player != null && player.containerMenu instanceof AbstractLogisticsStationMenu menu
                && menu.stationPos().equals(payload.stationPos())) {
            menu.applyClientSync(payload);
        }
    }
}
