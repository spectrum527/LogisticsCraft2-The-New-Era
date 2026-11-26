package com.logisticscraft.lc2.blockentity;

import com.industrialage.ia2.energy.graph.EnergyGraph.Ports;
import com.industrialage.ia2.platform.CapabilityFacade.Face;
import com.industrialage.ia2.power.EuBuffer;
import com.industrialage.ia2.power.WorldEnergy;
import com.industrialage.ia2.power.WorldPowerPort;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.platform.Lc2StationBlockEntityBase;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Communication Base Station block entity ({@code lc2:comm_base_station}):
 * <ul>
 *   <li>Dual EU ({@link WorldPowerPort} / {@link EuBuffer}) &amp; FE energy sink (LV 32 EU/t = 128 FE/t,
 *       capacity 4,000 EU = 16,000 FE)</li>
 *   <li>Standby drain of 2 EU/t (8 FE/t); syncs {@code active} blockstate for live visual status</li>
 *   <li>Discovers face-adjacent {@link SmartPortBlockEntity} and {@link CargoStationBlockEntity} blocks</li>
 * </ul>
 */
public final class CommBaseStationBlockEntity extends Lc2StationBlockEntityBase
        implements WorldPowerPort, EuBuffer {

    private static final Ports SINK_ALL_FACES = new Ports(EnumSet.allOf(Face.class), Set.of(), false);
    private static final NonNullList<ItemStack> EMPTY_INVENTORY = NonNullList.withSize(0, ItemStack.EMPTY);

    private double storedEu = 0.0;
    private boolean online = false;
    private int lastServerTick = Integer.MIN_VALUE;
    private boolean removalProcessed = false;
    private Object platformEnergyHandler;

    public CommBaseStationBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.COMM_BASE_STATION_BE.get(), pos, state);
    }

    @Override
    protected NonNullList<ItemStack> inventoryItems() {
        return EMPTY_INVENTORY;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            WorldEnergy.connect(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel) {
            WorldEnergy.disconnect(this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel) {
            WorldEnergy.disconnect(this);
        }
        super.onChunkUnloaded();
    }

    @Override
    protected void onBeforeRemove(ServerLevel level, BlockPos pos) {
        if (removalProcessed) {
            return;
        }
        removalProcessed = true;
        WorldEnergy.disconnect(this);
    }

    /** Server tick draining 2 EU/t standby power and synchronizing the {@code active} blockstate. */
    public void serverTick(ServerLevel level) {
        lastServerTick = level.getServer().getTickCount();
        if (storedEu + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK) {
            storedEu = Math.max(0.0, storedEu - LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK);
            online = true;
            setChanged();
        } else {
            online = false;
        }

        BlockState state = getBlockState();
        if (!isRemoved()
                && state.hasProperty(CommBaseStationBlock.ACTIVE)
                && state.getValue(CommBaseStationBlock.ACTIVE) != online) {
            level.setBlock(worldPosition, state.setValue(CommBaseStationBlock.ACTIVE, online), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Returns {@code true} when this Communication Base Station is actively powered and online
     * (either paid standby this tick or freshly supplied with at least one standby tick of EU).
     */
    public boolean isOnline() {
        return !isRemoved()
                && (online || storedEu + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK);
    }

    /** Returns the stored EU in the internal buffer ({@code 0..4,000}). */
    public double getStoredEu() {
        return storedEu;
    }

    /** Returns the maximum EU capacity ({@code 4,000}). */
    public double getMaxEu() {
        return LogisticsConstants.COMM_BASE_CAPACITY_EU;
    }

    /** Returns the current stored energy converted to FE (floor). */
    public int getStoredFe() {
        return EnergyUnits.euToFeFloor(storedEu);
    }

    /** Returns the maximum energy capacity converted to FE ({@code 16,000}). */
    public int getMaxFe() {
        return EnergyUnits.euToFeFloor(LogisticsConstants.COMM_BASE_CAPACITY_EU);
    }

    /** Sets the internal EU buffer directly (clamped to valid range) and marks dirty. */
    public void setStoredEu(double eu) {
        double clamped = Double.isFinite(eu)
                ? Math.clamp(eu, 0.0, LogisticsConstants.COMM_BASE_CAPACITY_EU)
                : 0.0;
        this.storedEu = clamped;
        this.online = clamped + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK;
        setChanged();
    }

    /** Updates the internal EU buffer inside a NeoForge 26.x transfer transaction without flushing yet. */
    public void setStoredEuFromTransaction(double eu) {
        this.storedEu = Double.isFinite(eu)
                ? Math.clamp(eu, 0.0, LogisticsConstants.COMM_BASE_CAPACITY_EU)
                : 0.0;
    }

    /** Marks the block entity dirty after an energy change or root transaction commit. */
    public void markEnergyChanged() {
        if (storedEu + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK) {
            this.online = true;
        }
        setChanged();
    }

    /** Receives Forge/NeoForge Energy (FE) converted at {@code 4 FE = 1 EU}, capped at LV 128 FE/t. */
    public int receiveFe(int offeredFe, boolean simulate) {
        if (offeredFe <= 0 || isRemoved()) {
            return 0;
        }
        EnergyUnits.FeTransferResult result = EnergyUnits.receiveFeIntoEuBuffer(
                storedEu,
                LogisticsConstants.COMM_BASE_CAPACITY_EU,
                LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU,
                offeredFe,
                simulate
        );
        if (!simulate && result.acceptedFe() > 0) {
            this.storedEu = result.newStoredEu();
            markEnergyChanged();
        }
        return result.acceptedFe();
    }

    /** Returns or lazily initializes the cached platform-specific energy handler attachment. */
    @SuppressWarnings("unchecked")
    public <T> T getOrCreatePlatformEnergyHandler(Supplier<T> factory) {
        if (platformEnergyHandler == null) {
            platformEnergyHandler = factory.get();
        }
        return (T) platformEnergyHandler;
    }

    /** Scans all 6 face-adjacent positions in entity-ticking chunks for live Smart Ports and Cargo Stations. */
    public List<TerminalTargetResolver.AdjacentStationInfo> findAdjacentStations(ServerLevel level) {
        List<TerminalTargetResolver.AdjacentStationInfo> result = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos adjPos = worldPosition.relative(dir);
            int cx = adjPos.getX() >> 4;
            int cz = adjPos.getZ() >> 4;
            if (!level.hasChunk(cx, cz) || !Lc2Native.isChunkEntityTicking(level, cx, cz)) {
                continue;
            }
            if (level.getBlockEntity(adjPos) instanceof AbstractLogisticsStationBlockEntity station
                    && !station.isRemoved()) {
                result.add(new TerminalTargetResolver.AdjacentStationInfo(
                        LogisticsSavedData.toPos3i(adjPos),
                        station.stationType(),
                        station.getStationName()
                ));
            }
        }
        return result;
    }

    /**
     * Builds a client-localized {@link Component} summarizing all adjacent logistics stations,
     * or {@code null} if none are adjacent.
     */
    public Component formatAdjacentComponent(ServerLevel level) {
        List<TerminalTargetResolver.AdjacentStationInfo> stations = findAdjacentStations(level);
        if (stations.isEmpty()) {
            return null;
        }
        MutableComponent combined = Component.empty();
        for (int i = 0; i < stations.size(); i++) {
            TerminalTargetResolver.AdjacentStationInfo info = stations.get(i);
            if (i > 0) {
                combined.append(Component.literal(", "));
            }
            if (info.customName() != null && !info.customName().isBlank()) {
                combined.append(Component.literal(info.customName()));
            } else {
                String key = info.type() == StationType.SMART_PORT
                        ? "gui.lc2.terminal.unnamed_port"
                        : "gui.lc2.terminal.unnamed_cargo";
                combined.append(Component.translatable(
                        key, info.pos().x(), info.pos().y(), info.pos().z()
                ));
            }
        }
        return combined;
    }

    /** Formats a comma-separated summary of adjacent logistics station names, or empty string if none. */
    public String formatAdjacentSummary(ServerLevel level) {
        List<TerminalTargetResolver.AdjacentStationInfo> stations = findAdjacentStations(level);
        if (stations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stations.size(); i++) {
            TerminalTargetResolver.AdjacentStationInfo info = stations.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            if (info.customName() != null && !info.customName().isBlank()) {
                sb.append(info.customName());
            } else {
                String key = info.type() == StationType.SMART_PORT
                        ? "gui.lc2.terminal.unnamed_port"
                        : "gui.lc2.terminal.unnamed_cargo";
                sb.append(Component.translatable(
                        key, info.pos().x(), info.pos().y(), info.pos().z()
                ).getString());
            }
        }
        return sb.toString();
    }

    // --- IC2 WorldPowerPort & EuBuffer ---

    @Override
    public boolean ranThisTick(ServerLevel level) {
        return lastServerTick == level.getServer().getTickCount();
    }

    @Override
    public Ports energyPorts() {
        return SINK_ALL_FACES;
    }

    @Override
    public boolean source() {
        return false;
    }

    @Override
    public double receive(double offered) {
        if (offered <= 0.0 || isRemoved()) {
            return 0.0;
        }
        EnergyUnits.EuTransferResult result = EnergyUnits.receiveEuIntoBuffer(
                storedEu,
                LogisticsConstants.COMM_BASE_CAPACITY_EU,
                offered
        );
        if (result.acceptedEu() > 0.0) {
            this.storedEu = result.newStoredEu();
            markEnergyChanged();
        }
        return result.acceptedEu();
    }

    @Override
    public double packetLimit() {
        return LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU;
    }

    @Override
    public Reading bufferedEu() {
        return new Reading(storedEu, LogisticsConstants.COMM_BASE_CAPACITY_EU);
    }

    // --- Persistence ---

    @Override
    protected void saveStationTag(CompoundTag tag) {
        tag.putDouble("eu", storedEu);
        tag.putBoolean("online", online);
    }

    @Override
    protected void loadStationTag(CompoundTag tag) {
        double loadedEu = Lc2Nbt.getDouble(tag, "eu", 0.0);
        this.storedEu = Double.isFinite(loadedEu)
                ? Math.clamp(loadedEu, 0.0, LogisticsConstants.COMM_BASE_CAPACITY_EU)
                : 0.0;
        this.online = Lc2Nbt.getBoolean(tag, "online", false);
    }

    @Override
    protected void saveClientSyncTag(CompoundTag tag) {
    }

    @Override
    protected void loadClientSyncTag(CompoundTag tag) {
    }
}
