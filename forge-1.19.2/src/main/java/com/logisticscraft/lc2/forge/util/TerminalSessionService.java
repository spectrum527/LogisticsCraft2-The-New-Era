package com.logisticscraft.lc2.forge.util;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RemoteDisconnectReason;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.compat.Lc2CuriosCompat;
import com.logisticscraft.lc2.forge.gui.CargoStationMenu;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.network.S2CTerminalSyncPacket;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkHooks;

/**
 * Authoritative server-side service for Operation Terminal discovery (inventory + Curios),
 * non-chunk-loading target resolution, live remote session validation, and remote station menu opening.
 */
public final class TerminalSessionService {

    private TerminalSessionService() {
    }

    /**
     * Handle to a located {@link OperationTerminalItem} stack in player inventory or Curios,
     * paired with a dirty callback that persists/syncs stack mutations back to the player.
     */
    public static final class FoundTerminal {
        private final ItemStack stack;
        private final Runnable dirtyCallback;

        public FoundTerminal(ItemStack stack, Runnable dirtyCallback) {
            this.stack = stack;
            this.dirtyCallback = dirtyCallback;
        }

        public ItemStack stack() {
            return this.stack;
        }

        public void markDirty() {
            this.dirtyCallback.run();
        }
    }

    /**
     * Checks whether the chunk at {@code pos} is already loaded and actively ticking without
     * forcing unloaded chunks to load.
     */
    public static boolean isChunkLoadedAndTicking(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getFullStatus().isOrAfter(ChunkHolder.FullChunkStatus.TICKING);
    }

    /**
     * Searches player mainhand, offhand, hotbar, main inventory, and active Curios slots for an
     * {@link OperationTerminalItem} matching {@code requiredTerminalId} (or the first terminal found
     * if {@code requiredTerminalId} is {@code null} or blank).
     */
    @Nullable
    public static FoundTerminal findTerminal(Player player, @Nullable String requiredTerminalId) {
        boolean matchId = requiredTerminalId != null && !requiredTerminalId.isBlank();
        Runnable invDirty = () -> {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        };

        ItemStack mainHand = player.getMainHandItem();
        if (matchesTerminal(mainHand, matchId, requiredTerminalId)) {
            return new FoundTerminal(mainHand, invDirty);
        }

        ItemStack offHand = player.getOffhandItem();
        if (matchesTerminal(offHand, matchId, requiredTerminalId)) {
            return new FoundTerminal(offHand, invDirty);
        }

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (matchesTerminal(stack, matchId, requiredTerminalId)) {
                return new FoundTerminal(stack, invDirty);
            }
        }

        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (matchesTerminal(carried, matchId, requiredTerminalId)) {
                return new FoundTerminal(carried, () -> player.containerMenu.broadcastChanges());
            }
        }

        return Lc2CuriosCompat.findTerminal(player, requiredTerminalId);
    }

    private static boolean matchesTerminal(ItemStack stack, boolean matchId, @Nullable String requiredTerminalId) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return false;
        }
        return !matchId || requiredTerminalId.equals(OperationTerminalItem.getTerminalId(stack));
    }

    /**
     * Resolves all bound Communication Base Stations on {@code terminalStack} into a deterministic,
     * deduplicated list of {@link TerminalTargetEntry} rows without forcing any chunk loads.
     */
    public static List<TerminalTargetEntry> resolveTargets(ServerPlayer player, ItemStack terminalStack) {
        ServerLevel level = player.getLevel();
        String playerDim = level.dimension().location().toString();
        BlockPos3i playerPos = Lc2Coords.toCore(player.blockPosition());
        List<CommBinding> bindings = OperationTerminalItem.getBindings(terminalStack);

        return TerminalTargetResolver.resolve(playerDim, playerPos, bindings, binding -> {
            BlockPos commPos = Lc2Coords.toMc(binding.pos());
            if (!isChunkLoadedAndTicking(level, commPos)) {
                return TerminalTargetResolver.CommProbeResult.unloaded();
            }
            BlockEntity be = level.getBlockEntity(commPos);
            if (!(be instanceof CommBaseStationBlockEntity comm)) {
                return TerminalTargetResolver.CommProbeResult.missing();
            }
            return TerminalTargetResolver.CommProbeResult.present(
                    comm.isOnline(),
                    comm.findAdjacentStations(level)
            );
        });
    }

    /**
     * Validates all 8 live remote-session invariants via
     * {@link TerminalTargetResolver#validateRemoteSession}.
     */
    public static RemoteDisconnectReason validateRemoteSession(
            ServerPlayer player,
            String expectedDimensionId,
            BlockPos commPos,
            BlockPos stationPos,
            String terminalId,
            boolean requireBinding
    ) {
        ServerLevel level = player.getLevel();
        String currentDim = level.dimension().location().toString();
        boolean sameDim = expectedDimensionId != null && expectedDimensionId.equals(currentDim);
        boolean commChunkLoaded = sameDim && isChunkLoadedAndTicking(level, commPos);
        boolean stationChunkLoaded = sameDim && isChunkLoadedAndTicking(level, stationPos);

        CommBaseStationBlockEntity comm = (commChunkLoaded
                && level.getBlockEntity(commPos) instanceof CommBaseStationBlockEntity c)
                ? c
                : null;
        boolean commPresent = comm != null;
        boolean commPowered = comm != null && comm.isOnline();

        boolean stationPresent = stationChunkLoaded
                && level.getBlockEntity(stationPos) instanceof AbstractLogisticsStationBlockEntity;
        boolean adjacent = TerminalTargetResolver.areFaceAdjacent(
                Lc2Coords.toCore(commPos),
                Lc2Coords.toCore(stationPos)
        );

        FoundTerminal found = findTerminal(player, terminalId);
        boolean terminalHeld = found != null
                && (!requireBinding || OperationTerminalItem.isBoundTo(found.stack(), expectedDimensionId, commPos));

        return TerminalTargetResolver.validateRemoteSession(
                sameDim,
                commChunkLoaded,
                stationChunkLoaded,
                commPresent,
                commPowered,
                stationPresent,
                adjacent,
                terminalHeld
        );
    }

    /** Sends an authoritative {@link S2CTerminalSyncPacket} for {@code terminalStack} to {@code player}. */
    public static void sendTerminalSync(ServerPlayer player, ItemStack terminalStack, boolean openScreen) {
        String terminalId = OperationTerminalItem.getOrCreateTerminalId(terminalStack);
        double storedEu = OperationTerminalItem.getStoredEu(terminalStack);
        int boundCount = OperationTerminalItem.getBindings(terminalStack).size();
        List<TerminalTargetEntry> entries = resolveTargets(player, terminalStack);
        Lc2Network.sendToPlayer(player, new S2CTerminalSyncPacket(
                terminalId,
                openScreen,
                storedEu,
                LogisticsConstants.TERMINAL_CAPACITY_EU,
                boundCount,
                entries
        ));
    }

    /** Opens a remote read-only Smart Port or Cargo Station menu and pushes an immediate station sync. */
    public static boolean openRemoteStation(
            ServerPlayer player,
            BlockPos commPos,
            BlockPos stationPos,
            String terminalId
    ) {
        ServerLevel level = player.getLevel();
        BlockEntity be = level.getBlockEntity(stationPos);
        if (be instanceof SmartPortBlockEntity port) {
            port.refreshWaterBerth(level);
            port.syncRegistry(level);
            port.requestImmediateWaterCheck();
            NetworkHooks.openScreen(
                    player,
                    new SimpleMenuProvider(
                            (containerId, inv, p) -> new SmartPortMenu(containerId, inv, port, commPos, terminalId),
                            port.getDisplayName()
                    ),
                    buf -> {
                        buf.writeBlockPos(stationPos);
                        buf.writeBoolean(true);
                        buf.writeBlockPos(commPos);
                        buf.writeUtf(terminalId == null ? "" : terminalId, 64);
                    }
            );
            port.sendSyncTo(player);
            return true;
        }
        if (be instanceof CargoStationBlockEntity station) {
            station.syncRegistry(level);
            NetworkHooks.openScreen(
                    player,
                    new SimpleMenuProvider(
                            (containerId, inv, p) -> new CargoStationMenu(containerId, inv, station, commPos, terminalId),
                            station.getDisplayName()
                    ),
                    buf -> {
                        buf.writeBlockPos(stationPos);
                        buf.writeBoolean(true);
                        buf.writeBlockPos(commPos);
                        buf.writeUtf(terminalId == null ? "" : terminalId, 64);
                    }
            );
            station.sendSyncTo(player);
            return true;
        }
        return false;
    }
}
