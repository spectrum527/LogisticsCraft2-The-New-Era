package com.logisticscraft.lc2.forge.block;

import com.industrialage.ia2.energy.graph.EnergyGraph;
import com.industrialage.ia2.platform.CapabilityFacade;
import com.industrialage.ia2.power.EuBuffer;
import com.industrialage.ia2.power.WorldEnergy;
import com.industrialage.ia2.power.WorldPowerPort;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Block entity for {@link CommBaseStationBlock} ({@code lc2:comm_base_station}).
 * <p>
 * Accepts both IC2 EU ({@link WorldPowerPort} + {@link EuBuffer}) and Forge Energy
 * ({@link ForgeCapabilities#ENERGY} on all 6 faces, {@code 1 EU = 4 FE}) up to
 * {@value LogisticsConstants#COMM_BASE_CAPACITY_EU} EU ({@code 16,000 FE}) at LV
 * ({@value LogisticsConstants#COMM_BASE_PACKET_LIMIT_EU} EU/t = {@code 128 FE/t}),
 * draining {@value LogisticsConstants#COMM_BASE_STANDBY_EU_PER_TICK} EU/t to stay online.
 */
public class CommBaseStationBlockEntity extends BlockEntity implements WorldPowerPort, EuBuffer {

    private static final EnergyGraph.Ports ALL_FACE_SINK =
            new EnergyGraph.Ports(EnumSet.allOf(CapabilityFacade.Face.class), Set.of(), false);

    private double storedEu = 0.0;
    private boolean online = false;
    private boolean powerAttached = false;
    private long lastTickTime = Long.MIN_VALUE;

    private LazyOptional<IEnergyStorage> energyCap = LazyOptional.of(this::createEnergyStorage);

    public CommBaseStationBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.COMM_BASE_STATION_BE.get(), pos, state);
    }

    // --- Lifecycle ---

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel) {
            WorldEnergy.connect(this);
            this.powerAttached = true;
        }
    }

    @Override
    public void setRemoved() {
        unlinkPower();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unlinkPower();
        super.onChunkUnloaded();
    }

    private void unlinkPower() {
        if (this.powerAttached) {
            WorldEnergy.disconnect(this);
            this.powerAttached = false;
        }
        this.lastTickTime = Long.MIN_VALUE;
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.energyCap.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        this.energyCap = LazyOptional.of(this::createEnergyStorage);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (!this.remove && cap == ForgeCapabilities.ENERGY) {
            return this.energyCap.cast();
        }
        return super.getCapability(cap, side);
    }

    // --- Server Tick ---

    /** Authoritative server-side standby drain and online/ACTIVE state synchronization. */
    public void serverTick(ServerLevel level) {
        this.lastTickTime = level.getGameTime();
        if (!this.powerAttached) {
            WorldEnergy.connect(this);
            this.powerAttached = true;
        }

        if (this.storedEu > LogisticsConstants.COMM_BASE_CAPACITY_EU) {
            this.storedEu = LogisticsConstants.COMM_BASE_CAPACITY_EU;
            setChanged();
        }

        if (this.storedEu + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK) {
            this.storedEu = Math.max(0.0, this.storedEu - LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK);
            this.online = true;
            setChanged();
        } else {
            this.online = false;
        }

        syncActiveBlockState(level);
    }

    private void syncActiveBlockState(ServerLevel level) {
        BlockState state = getBlockState();
        if (state.hasProperty(CommBaseStationBlock.ACTIVE)
                && state.getValue(CommBaseStationBlock.ACTIVE) != this.online) {
            level.setBlock(
                    this.worldPosition,
                    state.setValue(CommBaseStationBlock.ACTIVE, this.online),
                    Block.UPDATE_CLIENTS
            );
        }
    }

    // --- Adjacency ---

    /**
     * Scans all 6 block faces ({@link Direction#values()}) without forcing unloaded chunks to load,
     * returning metadata for every face-adjacent {@link SmartPortBlockEntity} and {@link CargoStationBlockEntity}.
     */
    public List<TerminalTargetResolver.AdjacentStationInfo> findAdjacentStations(ServerLevel level) {
        List<TerminalTargetResolver.AdjacentStationInfo> found = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = this.worldPosition.relative(dir);
            if (!TerminalSessionService.isChunkLoadedAndTicking(level, neighborPos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(neighborPos);
            if (be instanceof SmartPortBlockEntity port) {
                found.add(new TerminalTargetResolver.AdjacentStationInfo(
                        Lc2Coords.toCore(neighborPos),
                        StationType.SMART_PORT,
                        port.getStationName()
                ));
            } else if (be instanceof CargoStationBlockEntity cargo) {
                found.add(new TerminalTargetResolver.AdjacentStationInfo(
                        Lc2Coords.toCore(neighborPos),
                        StationType.CARGO_STATION,
                        cargo.getStationName()
                ));
            }
        }
        return List.copyOf(found);
    }

    // --- Energy Accessors ---

    /** Returns current stored EU in {@code [0, COMM_BASE_CAPACITY_EU]}. */
    public double getStoredEu() {
        return this.storedEu;
    }

    /** Directly sets stored EU clamped to {@code [0, COMM_BASE_CAPACITY_EU]}. */
    public void setStoredEu(double eu) {
        double safe = (!Double.isFinite(eu) || eu <= 0.0) ? 0.0 : Math.min(LogisticsConstants.COMM_BASE_CAPACITY_EU, eu);
        this.storedEu = safe;
        if (this.storedEu + EnergyUnits.EPSILON < LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK) {
            this.online = false;
            if (this.level instanceof ServerLevel serverLevel) {
                syncActiveBlockState(serverLevel);
            }
        }
        setChanged();
    }

    /** Returns maximum EU capacity ({@value LogisticsConstants#COMM_BASE_CAPACITY_EU}). */
    public double maxCapacityEu() {
        return LogisticsConstants.COMM_BASE_CAPACITY_EU;
    }

    /**
     * Returns {@code true} when this Communication Base Station is actively powered online
     * (either ran its last tick powered or holds at least one standby tick of EU).
     */
    public boolean isOnline() {
        return this.online || (this.storedEu + EnergyUnits.EPSILON >= LogisticsConstants.COMM_BASE_STANDBY_EU_PER_TICK);
    }

    // --- IC2 WorldPowerPort & EuBuffer ---

    @Override
    public boolean ranThisTick(ServerLevel level) {
        return !isRemoved() && this.lastTickTime == level.getGameTime();
    }

    @Override
    public EnergyGraph.Ports energyPorts() {
        return ALL_FACE_SINK;
    }

    @Override
    public double packetLimit() {
        return LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU;
    }

    @Override
    public double receive(double offered) {
        EnergyUnits.EuTransferResult res = EnergyUnits.receiveEuIntoBuffer(
                this.storedEu,
                LogisticsConstants.COMM_BASE_CAPACITY_EU,
                offered
        );
        if (res.acceptedEu() > 0.0) {
            this.storedEu = res.newStoredEu();
            setChanged();
        }
        return res.acceptedEu();
    }

    @Override
    public EuBuffer.Reading bufferedEu() {
        return new EuBuffer.Reading(this.storedEu, LogisticsConstants.COMM_BASE_CAPACITY_EU);
    }

    // --- Forge Energy Capability (FE <-> EU) ---

    private IEnergyStorage createEnergyStorage() {
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) {
                EnergyUnits.FeTransferResult res = EnergyUnits.receiveFeIntoEuBuffer(
                        storedEu,
                        LogisticsConstants.COMM_BASE_CAPACITY_EU,
                        LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU,
                        maxReceive,
                        simulate
                );
                if (!simulate && res.acceptedFe() > 0) {
                    storedEu = res.newStoredEu();
                    setChanged();
                }
                return res.acceptedFe();
            }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                return EnergyUnits.euToFeFloor(storedEu);
            }

            @Override
            public int getMaxEnergyStored() {
                return EnergyUnits.euToFeFloor(LogisticsConstants.COMM_BASE_CAPACITY_EU);
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return true;
            }
        };
    }

    // --- NBT Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("StoredEu", this.storedEu);
        tag.putBoolean("Online", this.online);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        double raw = tag.getDouble("StoredEu");
        this.storedEu = (!Double.isFinite(raw) || raw <= 0.0)
                ? 0.0
                : Math.min(LogisticsConstants.COMM_BASE_CAPACITY_EU, raw);
        this.online = tag.getBoolean("Online");
    }
}
