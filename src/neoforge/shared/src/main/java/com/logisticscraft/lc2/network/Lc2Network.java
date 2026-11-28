package com.logisticscraft.lc2.network;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.client.Lc2ClientHandlers;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import com.logisticscraft.lc2.item.TerminalLocator;
import com.logisticscraft.lc2.menu.AbstractLogisticsStationMenu;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * CustomPacketPayload registration and server/client handlers for LC2 station and
 * Operation Terminal networking (protocol v3).
 */
public final class Lc2Network {

    private static final String PROTOCOL_VERSION = "3";

    private Lc2Network() {
    }

    /** Registers all C2S and S2C payloads on the mod event bus. */
    public static void install(IEventBus modBus, Dist side) {
        Lc2Native.registerPayloads(modBus, side, PROTOCOL_VERSION, binder -> {
            binder.playToServer(
                    StationActionPayload.TYPE,
                    StationActionPayload.STREAM_CODEC,
                    Lc2Network::handleServerAction
            );
            binder.playToClient(
                    StationSyncPayload.TYPE,
                    StationSyncPayload.STREAM_CODEC,
                    Lc2Network::handleClientSync
            );
            binder.playToServer(
                    TerminalActionPayload.TYPE,
                    TerminalActionPayload.STREAM_CODEC,
                    Lc2Network::handleTerminalAction
            );
            binder.playToClient(
                    TerminalSyncPayload.TYPE,
                    TerminalSyncPayload.STREAM_CODEC,
                    Lc2Network::handleClientTerminalSync
            );
        });
    }

    /**
     * Probes all bound Communication Base Stations on {@code terminalStack} in the player's
     * current dimension (without triggering chunk loads) and sends {@link TerminalSyncPayload}.
     */
    public static void openTerminalSelection(ServerPlayer player, ItemStack terminalStack, boolean openScreen) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        String terminalId = OperationTerminalItem.ensureTerminalId(terminalStack);
        List<CommBinding> bindings = OperationTerminalItem.getBindings(terminalStack);
        String playerDim = Lc2Native.dimensionId(level);
        BlockPos3i playerPos = LogisticsSavedData.toPos3i(player.blockPosition());

        List<TerminalTargetEntry> entries = TerminalTargetResolver.resolve(
                playerDim,
                playerPos,
                bindings,
                binding -> probeCommBinding(level, binding)
        );

        TerminalSyncPayload payload = new TerminalSyncPayload(
                terminalId,
                openScreen,
                OperationTerminalItem.getStoredEu(terminalStack),
                LogisticsConstants.TERMINAL_CAPACITY_EU,
                bindings.size(),
                entries
        );
        Lc2Native.sendToPlayer(player, payload);
    }

    private static TerminalTargetResolver.CommProbeResult probeCommBinding(
            ServerLevel level,
            CommBinding binding
    ) {
        int cx = binding.pos().x() >> 4;
        int cz = binding.pos().z() >> 4;
        if (!level.hasChunk(cx, cz) || !Lc2Native.isChunkEntityTicking(level, cx, cz)) {
            return TerminalTargetResolver.CommProbeResult.unloaded();
        }
        BlockPos pos = LogisticsSavedData.toBlockPos(binding.pos());
        if (!(level.getBlockEntity(pos) instanceof CommBaseStationBlockEntity comm) || comm.isRemoved()) {
            return TerminalTargetResolver.CommProbeResult.missing();
        }
        return TerminalTargetResolver.CommProbeResult.present(comm.isOnline(), comm.findAdjacentStations(level));
    }

    private static void handleTerminalAction(TerminalActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        switch (payload.action()) {
            case OPEN_FROM_KEYBIND -> {
                Optional<TerminalLocator.LocatedTerminal> found = TerminalLocator.findFirstTerminal(player);
                if (found.isEmpty()) {
                    Lc2Native.sendActionBar(player, Component.translatable("status.trera.terminal.no_terminal_found"));
                    return;
                }
                openTerminalSelection(player, found.get().stack(), true);
                found.get().markChanged();
            }
            case REFRESH -> {
                Optional<TerminalLocator.LocatedTerminal> found =
                        TerminalLocator.findTerminalById(player, payload.terminalId());
                if (found.isEmpty()) {
                    Lc2Native.sendActionBar(player, Component.translatable("status.trera.terminal.no_terminal_found"));
                    return;
                }
                openTerminalSelection(player, found.get().stack(), false);
            }
            case UNBIND -> {
                Optional<TerminalLocator.LocatedTerminal> found =
                        TerminalLocator.findTerminalById(player, payload.terminalId());
                if (found.isEmpty()) {
                    Lc2Native.sendActionBar(player, Component.translatable("status.trera.terminal.no_terminal_found"));
                    return;
                }
                BlockPos commPos = payload.commPos();
                String requestedDim = payload.dimensionId();
                boolean removed;
                if (requestedDim != null && !requestedDim.isBlank()) {
                    removed = OperationTerminalItem.unbindCommStation(found.get().stack(), requestedDim, commPos);
                } else {
                    String dim = Lc2Native.dimensionId(level);
                    removed = OperationTerminalItem.unbindCommStation(found.get().stack(), dim, commPos);
                    if (!removed) {
                        for (CommBinding b : OperationTerminalItem.getBindings(found.get().stack())) {
                            if (b.pos().x() == commPos.getX()
                                    && b.pos().y() == commPos.getY()
                                    && b.pos().z() == commPos.getZ()) {
                                removed = OperationTerminalItem.unbindCommStation(
                                        found.get().stack(),
                                        b.dimensionId(),
                                        commPos
                                );
                                if (removed) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (removed) {
                    found.get().markChanged();
                    Lc2Native.sendActionBar(
                            player,
                            Component.translatable(
                                    "status.trera.terminal.unbound",
                                    commPos.getX(),
                                    commPos.getY(),
                                    commPos.getZ()
                            )
                    );
                }
                openTerminalSelection(player, found.get().stack(), false);
            }
            case CONNECT -> handleTerminalConnect(player, level, payload);
            case RETURN_TO_LIST -> {
                if (player.containerMenu instanceof AbstractLogisticsStationMenu) {
                    player.closeContainer();
                }
                Optional<TerminalLocator.LocatedTerminal> found =
                        TerminalLocator.findTerminalById(player, payload.terminalId());
                if (found.isEmpty()) {
                    found = TerminalLocator.findFirstTerminal(player);
                }
                if (found.isEmpty()) {
                    Lc2Native.sendActionBar(player, Component.translatable("status.trera.terminal.no_terminal_found"));
                    return;
                }
                openTerminalSelection(player, found.get().stack(), true);
            }
        }
    }

    private static void handleTerminalConnect(
            ServerPlayer player,
            ServerLevel level,
            TerminalActionPayload payload
    ) {
        BlockPos commPos = payload.commPos();
        BlockPos stationPos = payload.stationPos();
        String dim = Lc2Native.dimensionId(level);

        Optional<TerminalLocator.LocatedTerminal> found =
                TerminalLocator.findTerminalById(player, payload.terminalId());
        boolean terminalHeld = found.isPresent()
                && OperationTerminalItem.isBoundTo(found.get().stack(), dim, commPos);

        int commCx = commPos.getX() >> 4;
        int commCz = commPos.getZ() >> 4;
        boolean commChunkLoaded = level.hasChunk(commCx, commCz)
                && Lc2Native.isChunkEntityTicking(level, commCx, commCz);

        int stationCx = stationPos.getX() >> 4;
        int stationCz = stationPos.getZ() >> 4;
        boolean stationChunkLoaded = level.hasChunk(stationCx, stationCz)
                && Lc2Native.isChunkEntityTicking(level, stationCx, stationCz);

        CommBaseStationBlockEntity comm = commChunkLoaded
                && level.getBlockEntity(commPos) instanceof CommBaseStationBlockEntity c
                && !c.isRemoved()
                ? c
                : null;
        boolean commPresent = comm != null;
        boolean commPowered = commPresent && comm.isOnline();

        AbstractLogisticsStationBlockEntity station = stationChunkLoaded
                && level.getBlockEntity(stationPos) instanceof AbstractLogisticsStationBlockEntity s
                && !s.isRemoved()
                ? s
                : null;
        boolean stationPresent = station != null;

        boolean adjacent = TerminalTargetResolver.areFaceAdjacent(
                LogisticsSavedData.toPos3i(commPos),
                LogisticsSavedData.toPos3i(stationPos)
        );

        RemoteDisconnectReason reason = TerminalTargetResolver.validateRemoteSession(
                true,
                commChunkLoaded,
                stationChunkLoaded,
                commPresent,
                commPowered,
                stationPresent,
                adjacent,
                terminalHeld
        );
        if (reason.isDisconnected()) {
            Lc2Native.sendActionBar(player, Component.translatable(reason.translationKey()));
            found.ifPresent(loc -> openTerminalSelection(player, loc.stack(), false));
            return;
        }

        String terminalId = OperationTerminalItem.ensureTerminalId(found.get().stack());
        BlockPos immutableCommPos = commPos.immutable();
        if (station instanceof SmartPortBlockEntity port) {
            port.requestImmediateWaterCheck();
            player.openMenu(
                    new SimpleMenuProvider(
                            (cid, inv, p) -> new SmartPortMenu(cid, inv, port, immutableCommPos, terminalId),
                            port.getDisplayName()
                    ),
                    buf -> {
                        buf.writeBlockPos(stationPos);
                        buf.writeBoolean(true);
                        buf.writeUtf(terminalId, 64);
                    }
            );
            Lc2Native.sendToPlayer(player, port.buildSyncPayload(level));
        } else if (station instanceof CargoStationBlockEntity cargo) {
            player.openMenu(
                    new SimpleMenuProvider(
                            (cid, inv, p) -> new CargoStationMenu(cid, inv, cargo, immutableCommPos, terminalId),
                            cargo.getDisplayName()
                    ),
                    buf -> {
                        buf.writeBlockPos(stationPos);
                        buf.writeBoolean(true);
                        buf.writeUtf(terminalId, 64);
                    }
            );
            Lc2Native.sendToPlayer(player, cargo.buildSyncPayload(level));
        }
    }

    private static final int MAX_WORLD_HORIZONTAL = 30_000_000;

    private static BlockPos clampTargetPos(ServerLevel level, BlockPos raw) {
        if (raw == null) {
            return null;
        }
        int cx = Math.clamp(raw.getX(), -MAX_WORLD_HORIZONTAL, MAX_WORLD_HORIZONTAL);
        int cy = Math.clamp(raw.getY(), Lc2Native.minBuildY(level), Lc2Native.maxBuildY(level) - 1);
        int cz = Math.clamp(raw.getZ(), -MAX_WORLD_HORIZONTAL, MAX_WORLD_HORIZONTAL);
        return new BlockPos(cx, cy, cz);
    }

    private static void handleServerAction(StationActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = payload.stationPos();
        if (!level.hasChunkAt(pos)) {
            return;
        }

        AbstractLogisticsStationMenu remoteMenu = null;
        if (player.containerMenu instanceof AbstractLogisticsStationMenu menu
                && menu.isRemoteMode()
                && menu.stationPos().equals(pos)) {
            if (!menu.stillValid(player)) {
                return;
            }
            remoteMenu = menu;
        } else if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
            return;
        }

        if (!(level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity station)) {
            return;
        }

        switch (payload.action()) {
            case RENAME -> station.setStationName(payload.textArg());
            case PREV_TARGET -> station.cycleTarget(level, -1);
            case NEXT_TARGET -> station.cycleTarget(level, 1);
            case SELECT_TARGET, SET_TARGET_COORDS -> station.setTargetPos(clampTargetPos(level, payload.targetArg()));
            case TOGGLE_AUTO_RETURN -> station.setAutoReturn(!station.isAutoReturn());
            case TOGGLE_ENERGY_PRIORITY -> station.setEnergyPriority(station.getEnergyPriority().toggle());
            case MANUAL_DISPATCH -> {
                if (remoteMenu != null) {
                    handleRemoteManualDispatch(player, level, station, remoteMenu);
                } else {
                    station.handleManualDispatch(level);
                }
            }
            case SWAP_CARGO_ZONES -> {
                station.swapCargoZones();
                if (player.containerMenu instanceof AbstractLogisticsStationMenu) {
                    player.containerMenu.broadcastChanges();
                }
            }
            case REQUEST_SYNC -> {
                if (station instanceof SmartPortBlockEntity port) {
                    port.requestWaterCheckIfStale(level);
                }
            }
        }
        Lc2Native.sendToPlayer(player, station.buildSyncPayload(level));
    }

    private static void handleRemoteManualDispatch(
            ServerPlayer player,
            ServerLevel level,
            AbstractLogisticsStationBlockEntity station,
            AbstractLogisticsStationMenu remoteMenu
    ) {
        Optional<TerminalLocator.LocatedTerminal> found = TerminalLocator.findTerminalBoundTo(
                player,
                remoteMenu.remoteTerminalId(),
                Lc2Native.dimensionId(level),
                remoteMenu.remoteCommPos()
        );
        if (found.isEmpty()) {
            Lc2Native.sendActionBar(
                    player,
                    Component.translatable(RemoteDisconnectReason.TERMINAL_MISSING.translationKey())
            );
            return;
        }
        ItemStack termStack = found.get().stack();
        double terminalEu = OperationTerminalItem.getStoredEu(termStack);
        if (!player.isCreative() && !EnergyUnits.canAffordTerminalDispatch(terminalEu)) {
            station.setStatusKey(DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY.translationKey());
            Lc2Native.sendActionBar(
                    player,
                    Component.translatable(DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY.translationKey())
            );
            return;
        }
        boolean dispatched = station.handleManualDispatchWithResult(level);
        if (dispatched && !player.isCreative()) {
            OperationTerminalItem.deductDispatchCost(termStack);
            found.get().markChanged();
        }
    }

    private static void handleClientSync(StationSyncPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player != null && player.containerMenu instanceof AbstractLogisticsStationMenu menu
                && menu.stationPos().equals(payload.stationPos())) {
            menu.applyClientSync(payload);
        }
    }

    private static void handleClientTerminalSync(TerminalSyncPayload payload, IPayloadContext context) {
        ClientDispatch.onTerminalSync(payload);
    }

    private static final class ClientDispatch {
        private static void onTerminalSync(TerminalSyncPayload payload) {
            Lc2ClientHandlers.handleTerminalSync(payload);
        }
    }
}
