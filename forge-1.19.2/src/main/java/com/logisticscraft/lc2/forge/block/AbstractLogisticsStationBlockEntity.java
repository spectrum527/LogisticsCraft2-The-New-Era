package com.logisticscraft.lc2.forge.block;

import com.industrialage.ia2.energy.graph.EnergyGraph;
import com.industrialage.ia2.platform.CapabilityFacade;
import com.industrialage.ia2.power.EuBuffer;
import com.industrialage.ia2.power.WorldEnergy;
import com.industrialage.ia2.power.WorldPowerPort;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.PaymentPlan;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.forge.network.Lc2Network;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.ContainerIoHelper;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.util.LogisticsChunkTickets;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

/**
 * Shared base {@link BlockEntity} for {@link SmartPortBlockEntity} and {@link CargoStationBlockEntity}.
 * Integrates IC2 EU power ({@link WorldPowerPort} + {@link EuBuffer}), all 9 supported IC2/LC2 machine
 * upgrades, 3x3 chunk-loading tickets (2 EU/t), strict 2-zone Outbound ({@code 0..26}) / Storage
 * ({@code 27..53}) cargo separation with atomic swap and legacy NBT migration, non-hybrid EU/battery
 * payment, station naming/targeting, and GUI sync packet construction.
 */
public abstract class AbstractLogisticsStationBlockEntity extends BlockEntity
        implements WorldPowerPort, EuBuffer, MenuProvider, WorldlyContainer {

    public static final int OUTBOUND_START = LogisticsConstants.STATION_OUTBOUND_START; // 0
    public static final int OUTBOUND_COUNT = LogisticsConstants.STATION_OUTBOUND_SLOTS; // 27
    public static final int OUTBOUND_END = OUTBOUND_START + OUTBOUND_COUNT; // 27
    public static final int STORAGE_START = LogisticsConstants.STATION_STORAGE_START; // 27
    public static final int STORAGE_COUNT = LogisticsConstants.STATION_STORAGE_SLOTS; // 27
    public static final int STORAGE_END = STORAGE_START + STORAGE_COUNT; // 54
    public static final int TOTAL_CARGO_SLOTS = LogisticsConstants.STATION_TOTAL_CARGO_SLOTS; // 54

    /** Current inventory NBT layout version (v2 = 2-zone 27 Outbound + 27 Storage). */
    public static final int LAYOUT_VERSION = 2;

    private static final EnergyGraph.Ports ALL_FACE_SINK =
            new EnergyGraph.Ports(EnumSet.allOf(CapabilityFacade.Face.class), Set.of(), false);

    protected final NonNullList<ItemStack> items;
    private final int[] automatedSlots;

    protected double storedEu = 0.0;
    protected String stationName = "";
    @Nullable
    protected BlockPos targetPos = null;
    protected boolean autoReturn = false;
    protected EnergyPriority energyPriority = EnergyPriority.EU_FIRST;
    protected String statusKey = "status.lc2.ready";

    protected UpgradeSummary upgrades = UpgradeSummary.EMPTY;
    private boolean upgradesDirty = true;

    private boolean powerAttached = false;
    private boolean chunkTicketsHeld = false;
    private long lastTickTime = Long.MIN_VALUE;
    protected boolean wasRedstoneTriggered = false;
    protected int dispatchCooldown = 0;

    private LazyOptional<? extends IItemHandler>[] sidedHandlers;

    protected AbstractLogisticsStationBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            int totalSlots
    ) {
        super(type, pos, state);
        this.items = NonNullList.withSize(totalSlots, ItemStack.EMPTY);
        int cargoCount = cargoSlotCount();
        this.automatedSlots = new int[cargoCount + 1];
        for (int i = 0; i < cargoCount; i++) {
            this.automatedSlots[i] = i;
        }
        this.automatedSlots[cargoCount] = batterySlotIndex();
        this.sidedHandlers = SidedInvWrapper.create(this, Direction.values());
    }

    /** Returns {@code true} if {@code slot} belongs to the Outbound zone ({@code 0..26}). */
    public static boolean isOutboundSlot(int slot) {
        return slot >= OUTBOUND_START && slot < OUTBOUND_END;
    }

    /** Returns {@code true} if {@code slot} belongs to the Storage zone ({@code 27..53}). */
    public static boolean isStorageSlot(int slot) {
        return slot >= STORAGE_START && slot < STORAGE_END;
    }

    /** Station category ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION}). */
    public abstract StationType stationType();

    /** Total internal 2-zone cargo slots starting at index 0 ({@code 54} = 27 Outbound + 27 Storage). */
    public int cargoSlotCount() {
        return TOTAL_CARGO_SLOTS;
    }

    /** Legacy v1 total inventory slot count used for NBT migration when {@code LayoutVersion < 2}. */
    protected abstract int legacyTotalSlots();

    /** Maps a legacy v1 slot index to its v2 slot index, or {@code -1} if invalid. */
    protected abstract int remapLegacySlot(int legacySlot);

    /** Slot index of the dedicated {@code ic2:single_use_battery} slot. */
    public abstract int batterySlotIndex();

    /** First slot index of the 4 machine upgrade slots ({@code [first, first + 4)}). */
    public abstract int upgradeStartIndex();

    /** {@link BooleanProperty} for the block's active state indicator. */
    protected abstract BooleanProperty activeProperty();

    /** Station-specific per-tick logic invoked by {@link #serverTick(ServerLevel)}. */
    protected abstract void tickStation(ServerLevel level, long gameTime, boolean redstoneTriggered);

    /** Manually or automatically attempts to dispatch a vehicle from this station. */
    public abstract boolean tryDispatch(ServerLevel level, boolean manualTrigger);

    /** Registers or updates this station in the dimension's {@link com.logisticscraft.lc2.core.StationRegistry}. */
    public abstract void syncRegistry(ServerLevel level);

    /** Builds the S2C sync packet for GUI clients. */
    public abstract S2CStationSyncPacket buildSyncPacket(ServerLevel level);

    // --- Lifecycle & Server Tick ---

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel serverLevel) {
            WorldEnergy.connect(this);
            this.powerAttached = true;
            refreshUpgradesIfNeeded();
            syncRegistry(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        unlinkResources();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unlinkResources();
        super.onChunkUnloaded();
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        for (LazyOptional<? extends IItemHandler> handler : sidedHandlers) {
            handler.invalidate();
        }
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        this.sidedHandlers = SidedInvWrapper.create(this, Direction.values());
    }

    private void unlinkResources() {
        if (this.level instanceof ServerLevel serverLevel) {
            releaseChunkTickets(serverLevel);
        }
        if (this.powerAttached) {
            WorldEnergy.disconnect(this);
            this.powerAttached = false;
        }
        this.lastTickTime = Long.MIN_VALUE;
    }

    /**
     * Authoritative per-tick server update for both Smart Ports and Cargo Stations.
     */
    public final void serverTick(ServerLevel level) {
        long gameTime = level.getGameTime();
        this.lastTickTime = gameTime;

        if (!this.powerAttached) {
            WorldEnergy.connect(this);
            this.powerAttached = true;
        }

        refreshUpgradesIfNeeded();

        double maxCap = maxCapacityEu();
        if (this.storedEu > maxCap) {
            this.storedEu = maxCap;
            setChanged();
        }

        // Chunk Loading Upgrade: 2 EU/t standby drain to keep 3x3 chunk area force-loaded
        if (this.upgrades.shouldChunkLoad(this.storedEu)) {
            this.storedEu = Math.max(0.0, this.storedEu - this.upgrades.chunkLoaderStandbyDrainEuPerTick());
            if (!this.chunkTicketsHeld) {
                LogisticsChunkTickets.holdArea(level, this.worldPosition);
                this.chunkTicketsHeld = true;
                LogisticsLevelManager.markChunkLoaderTakenOver(level, this.worldPosition);
            }
            LogisticsLevelManager.data(level).setChunkLoaderStation(Lc2Coords.toCore(this.worldPosition), true);
            setChanged();
        } else {
            releaseChunkTickets(level);
            LogisticsLevelManager.data(level).setChunkLoaderStation(Lc2Coords.toCore(this.worldPosition), false);
        }

        // Periodic strict 2-zone upgrade automation:
        // ejector_upgrade -> pushes strictly from Storage (27..53)
        // pulling_upgrade -> pulls strictly into Outbound (0..26)
        int ioPeriod = this.upgrades.overclockerCount() > 0 ? 2 : 5;
        if (gameTime % ioPeriod == 0) {
            if (this.upgrades.hasEjector()) {
                ejectStorageArea(level);
            }
            if (this.upgrades.hasPulling()) {
                pullIntoOutboundArea(level);
            }
        }

        if (this.dispatchCooldown > 0) {
            this.dispatchCooldown--;
        }

        boolean rawRedstone = level.hasNeighborSignal(this.worldPosition);
        boolean triggered = this.upgrades.isRedstoneTriggered(rawRedstone);
        if (triggered && !this.wasRedstoneTriggered) {
            // Rising edge triggers dispatch immediately even for 1-tick pulses
            this.dispatchCooldown = 0;
        }
        this.wasRedstoneTriggered = triggered;

        tickStation(level, gameTime, triggered);
    }

    /**
     * Handles immediate block neighbor updates so 1-tick redstone pulses trigger a rising-edge
     * dispatch without waiting for the next server tick.
     */
    public void onNeighborRedstoneChanged(ServerLevel level) {
        refreshUpgradesIfNeeded();
        boolean triggered = this.upgrades.isRedstoneTriggered(level.hasNeighborSignal(this.worldPosition));
        if (triggered && !this.wasRedstoneTriggered) {
            this.wasRedstoneTriggered = true;
            this.dispatchCooldown = 0;
            tryDispatch(level, false);
        } else if (!triggered) {
            this.wasRedstoneTriggered = false;
        }
    }

    private void releaseChunkTickets(ServerLevel level) {
        if (this.chunkTicketsHeld) {
            LogisticsChunkTickets.releaseArea(level, this.worldPosition);
            this.chunkTicketsHeld = false;
        }
    }

    /** Updates the blockstate {@code active} property only when it changes. */
    protected void setActiveBlockState(ServerLevel level, boolean active) {
        BlockState state = getBlockState();
        BooleanProperty prop = activeProperty();
        if (state.hasProperty(prop) && state.getValue(prop) != active) {
            level.setBlock(this.worldPosition, state.setValue(prop, active), Block.UPDATE_CLIENTS);
        }
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
        refreshUpgradesIfNeeded();
        return this.upgrades.packetLimitEu();
    }

    @Override
    public double receive(double offered) {
        if (!Double.isFinite(offered) || offered <= 0.0) {
            return 0.0;
        }
        refreshUpgradesIfNeeded();
        double space = Math.max(0.0, maxCapacityEu() - this.storedEu);
        double accepted = Math.min(offered, space);
        if (accepted > 0.0) {
            this.storedEu += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public EuBuffer.Reading bufferedEu() {
        refreshUpgradesIfNeeded();
        return new EuBuffer.Reading(this.storedEu, maxCapacityEu());
    }

    // --- Energy, Upgrades & Payment ---

    /** Returns current stored EU. */
    public double getStoredEu() {
        return this.storedEu;
    }

    /** Directly sets stored EU clamped to {@code [0, maxCapacityEu()]} (for testing/admin hooks). */
    public void setStoredEu(double eu) {
        refreshUpgradesIfNeeded();
        this.storedEu = Math.max(0.0, Math.min(maxCapacityEu(), eu));
        setChanged();
    }

    /** Returns current maximum EU capacity accounting for Energy Storage upgrades. */
    public double maxCapacityEu() {
        refreshUpgradesIfNeeded();
        return this.upgrades.maxCapacityEu();
    }

    /** Returns the cached immutable {@link UpgradeSummary}. */
    public UpgradeSummary getUpgrades() {
        refreshUpgradesIfNeeded();
        return this.upgrades;
    }

    /** Returns {@code true} if the chunk-loading upgrade is actively holding 3x3 chunk tickets. */
    public boolean isChunkLoaderActive() {
        return this.chunkTicketsHeld;
    }

    /** Returns the number of supported single-use batteries ({@code #lc2:single_use_batteries}) currently in the battery slot. */
    public int getBatteryCount() {
        ItemStack stack = this.items.get(batterySlotIndex());
        if (stack.isEmpty() || !Lc2Coords.isSingleUseBattery(stack)) {
            return 0;
        }
        return stack.getCount();
    }

    /** Debits an approved non-hybrid {@link PaymentPlan} from EU or single-use batteries. */
    public void debitPayment(PaymentPlan plan) {
        if (plan == null || !plan.isPayable()) {
            return;
        }
        if (plan.isEu()) {
            this.storedEu = Math.max(0.0, this.storedEu - plan.euToSpend());
            setChanged();
        } else if (plan.isBattery()) {
            removeItem(batterySlotIndex(), plan.batteriesToSpend());
            setChanged();
        }
    }

    /** Returns the 4 installed machine upgrade {@link ItemStack}s. */
    public List<ItemStack> getUpgradeStacks() {
        int start = upgradeStartIndex();
        List<ItemStack> list = new ArrayList<>(LogisticsConstants.MACHINE_UPGRADE_SLOTS);
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack stack = this.items.get(start + i);
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    protected void refreshUpgradesIfNeeded() {
        if (!this.upgradesDirty) {
            return;
        }
        UpgradeSummary.Builder builder = UpgradeSummary.builder();
        int start = upgradeStartIndex();
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack stack = this.items.get(start + i);
            if (!stack.isEmpty()) {
                builder.add(Lc2Coords.itemId(stack), stack.getCount());
            }
        }
        this.upgrades = builder.build();
        this.upgradesDirty = false;
    }

    // --- Strict 2-Zone Cargo I/O (Outbound 0..26 / Storage 27..53) ---

    /**
     * Unloads arriving vehicle cargo into the Storage zone ({@code 27..53}). If an IC2
     * {@code ejector_upgrade} / {@code advanced_ejector_upgrade} is installed, immediately ejects
     * from Storage / overflow through configured ejector faces so full 36-slot Heavy Drones and
     * 54-slot Boats unload smoothly. Any overflow that still cannot fit is returned to remain
     * safely inside the vehicle ({@code ARRIVED_UNLOADING}) for retry with zero item loss.
     *
     * @return any stacks that could not fit into Storage (or ejector targets when installed)
     */
    public List<ItemStack> unloadIncomingCargo(ServerLevel level, List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return List.of();
        }
        refreshUpgradesIfNeeded();
        boolean hasEjector = this.upgrades.hasEjector();
        List<ItemStack> upgradeStacks = hasEjector ? getUpgradeStacks() : List.of();
        List<Direction> pushFaces = hasEjector
                ? ContainerIoHelper.resolvePushFaces(upgradeStacks)
                : List.of();
        boolean ejectorBlocked = pushFaces.isEmpty();
        List<ItemStack> remaining = new ArrayList<>();

        for (ItemStack raw : cargo) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            ItemStack leftover = ContainerIoHelper.insertIntoContainer(
                    this, STORAGE_START, STORAGE_END, raw.copy()
            );
            if (!leftover.isEmpty() && hasEjector && !ejectorBlocked) {
                int freed = ejectStorageSlotsUnlimited(level, pushFaces, upgradeStacks);
                if (freed > 0) {
                    leftover = ContainerIoHelper.insertIntoContainer(
                            this, STORAGE_START, STORAGE_END, leftover
                    );
                }
                if (!leftover.isEmpty()) {
                    int before = leftover.getCount();
                    leftover = ContainerIoHelper.pushToAdjacent(level, this.worldPosition, pushFaces, leftover, upgradeStacks);
                    if (freed == 0 && leftover.getCount() == before) {
                        ejectorBlocked = true;
                    }
                }
            }
            if (!leftover.isEmpty()) {
                remaining.add(leftover);
            }
        }
        if (hasEjector && !ejectorBlocked) {
            ejectStorageSlotsUnlimited(level, pushFaces, upgradeStacks);
        }
        setChanged();
        return remaining;
    }

    /**
     * Loads outgoing cargo for a departing vehicle into {@code destination} (up to {@code maxSlots}
     * slots): drains ONLY the Outbound zone ({@code 0..26}) — never touching Storage ({@code 27..53}).
     * If an IC2 {@code pulling_upgrade} / {@code advanced_pulling_upgrade} is installed AND the
     * vehicle still has free space after draining Outbound (e.g. 36-slot Heavy Drone or 54-slot Boat),
     * pulls remaining capacity strictly from configured pulling upgrade faces. When no pulling upgrade
     * is installed, adjacent chests are never touched during launch.
     */
    protected void loadOutgoingCargoInto(ServerLevel level, Container destination, int maxSlots) {
        refreshUpgradesIfNeeded();

        // 1. Drain ONLY Outbound slots (0..26) — NEVER touch Storage (27..53)
        for (int slot = OUTBOUND_START; slot < OUTBOUND_END; slot++) {
            ItemStack stack = this.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = ContainerIoHelper.insertIntoContainer(destination, 0, maxSlots, stack);
            this.items.set(slot, leftover);
        }

        // 2. If pulling upgrade is installed AND vehicle still has free space, pull from configured faces
        if (this.upgrades.hasPulling() && hasFreeSpace(destination, maxSlots)) {
            List<ItemStack> upgradeStacks = getUpgradeStacks();
            List<Direction> pullFaces = ContainerIoHelper.resolvePullFaces(upgradeStacks);
            if (!pullFaces.isEmpty()) {
                Set<Direction> excluded = this.upgrades.hasEjector()
                        ? copyToDirectionSet(ContainerIoHelper.resolvePushFaces(upgradeStacks))
                        : Set.of();
                ContainerIoHelper.pullFromAdjacentInto(
                        level,
                        this.worldPosition,
                        pullFaces,
                        excluded,
                        destination,
                        0,
                        maxSlots,
                        maxSlots * 64,
                        upgradeStacks
                );
            }
        }
        setChanged();
    }

    private static boolean hasFreeSpace(Container destination, int maxSlots) {
        int limit = Math.min(maxSlots, destination.getContainerSize());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = destination.getItem(i);
            if (stack.isEmpty()) {
                return true;
            }
            int slotLimit = Math.min(stack.getMaxStackSize(), destination.getMaxStackSize());
            if (stack.getCount() < slotLimit) {
                return true;
            }
        }
        return false;
    }

    private static Set<Direction> copyToDirectionSet(List<Direction> faces) {
        return faces.isEmpty() ? Set.of() : EnumSet.copyOf(faces);
    }

    private void ejectStorageArea(ServerLevel level) {
        List<ItemStack> upgradeStacks = getUpgradeStacks();
        List<Direction> pushFaces = ContainerIoHelper.resolvePushFaces(upgradeStacks);
        if (pushFaces.isEmpty()) {
            return;
        }
        int budget = this.upgrades.transferRatePerTick();
        boolean changed = false;

        for (int slot = STORAGE_START; slot < STORAGE_END && budget > 0; slot++) {
            ItemStack stack = this.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int moveCount = Math.min(budget, stack.getCount());
            ItemStack probe = stack.copy();
            probe.setCount(moveCount);
            ItemStack leftover = ContainerIoHelper.pushToAdjacent(level, this.worldPosition, pushFaces, probe, upgradeStacks);
            int pushed = moveCount - leftover.getCount();
            if (pushed > 0) {
                stack.shrink(pushed);
                budget -= pushed;
                if (stack.isEmpty()) {
                    this.items.set(slot, ItemStack.EMPTY);
                }
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    private int ejectStorageSlotsUnlimited(ServerLevel level, List<Direction> pushFaces, List<ItemStack> upgradeStacks) {
        if (pushFaces.isEmpty()) {
            return 0;
        }
        int totalPushed = 0;
        for (int slot = STORAGE_START; slot < STORAGE_END; slot++) {
            ItemStack stack = this.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            ItemStack leftover = ContainerIoHelper.pushToAdjacent(
                    level, this.worldPosition, pushFaces, stack.copy(), upgradeStacks
            );
            int pushed = before - leftover.getCount();
            if (pushed > 0) {
                this.items.set(slot, leftover);
                totalPushed += pushed;
            }
        }
        return totalPushed;
    }

    private void pullIntoOutboundArea(ServerLevel level) {
        List<ItemStack> upgradeStacks = getUpgradeStacks();
        List<Direction> pullFaces = ContainerIoHelper.resolvePullFaces(upgradeStacks);
        if (pullFaces.isEmpty()) {
            return;
        }
        Set<Direction> excluded = this.upgrades.hasEjector()
                ? copyToDirectionSet(ContainerIoHelper.resolvePushFaces(upgradeStacks))
                : Set.of();
        int budget = this.upgrades.transferRatePerTick();
        ContainerIoHelper.pullFromAdjacentInto(
                level,
                this.worldPosition,
                pullFaces,
                excluded,
                this,
                OUTBOUND_START,
                OUTBOUND_END,
                budget,
                upgradeStacks
        );
    }

    /**
     * Atomically swaps every slot pair {@code i <-> 27 + i} ({@code i = 0..26}) between the
     * Outbound and Storage zones, marks the block entity dirty, and reports {@code status.lc2.swapped}.
     */
    public void swapCargoZones() {
        for (int i = 0; i < OUTBOUND_COUNT; i++) {
            int outboundIdx = OUTBOUND_START + i;
            int storageIdx = STORAGE_START + i;
            ItemStack outStack = this.items.get(outboundIdx);
            ItemStack storageStack = this.items.get(storageIdx);
            this.items.set(outboundIdx, storageStack);
            this.items.set(storageIdx, outStack);
        }
        this.statusKey = "status.lc2.swapped";
        setChanged();
    }

    // --- Station Configuration & GUI Actions ---

    public String getStationName() {
        return this.stationName;
    }

    public void setStationName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32);
        }
        this.stationName = cleaned;
        this.statusKey = "status.lc2.name_saved";
        setChanged();
        if (this.level instanceof ServerLevel serverLevel) {
            syncRegistry(serverLevel);
        }
    }

    @Nullable
    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public void setTargetPos(@Nullable BlockPos target) {
        BlockPos next = target == null ? null : target.immutable();
        boolean changed = (this.targetPos == null) != (next == null)
                || (this.targetPos != null && !this.targetPos.equals(next));
        this.targetPos = next;
        if (changed) {
            onTargetChanged();
        }
        this.statusKey = this.targetPos == null
                ? DispatchFailure.NO_TARGET.translationKey()
                : "status.lc2.target_updated";
        setChanged();
    }

    /** Subclass hook invoked whenever the configured target position changes. */
    protected void onTargetChanged() {
    }

    public void cycleTarget(ServerLevel level, int delta) {
        List<StationEntry> targets = LogisticsLevelManager.stations(level)
                .listCompatibleTargets(Lc2Coords.toCore(this.worldPosition), stationType());
        if (targets.isEmpty()) {
            boolean hadTarget = this.targetPos != null;
            this.targetPos = null;
            if (hadTarget) {
                onTargetChanged();
            }
            this.statusKey = DispatchFailure.TARGET_NOT_FOUND.translationKey();
            setChanged();
            return;
        }
        int currentIndex = -1;
        if (this.targetPos != null) {
            BlockPos3i cur = Lc2Coords.toCore(this.targetPos);
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i).pos().equals(cur)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        int nextIndex;
        if (currentIndex < 0) {
            nextIndex = delta >= 0 ? 0 : targets.size() - 1;
        } else {
            nextIndex = Math.floorMod(currentIndex + delta, targets.size());
        }
        BlockPos nextTarget = Lc2Coords.toMc(targets.get(nextIndex).pos());
        boolean changed = this.targetPos == null || !this.targetPos.equals(nextTarget);
        this.targetPos = nextTarget;
        if (changed) {
            onTargetChanged();
        }
        this.statusKey = "status.lc2.target_updated";
        setChanged();
    }

    public boolean isAutoReturn() {
        return this.autoReturn;
    }

    public void setAutoReturn(boolean autoReturn) {
        this.autoReturn = autoReturn;
        setChanged();
    }

    public void toggleAutoReturn() {
        setAutoReturn(!this.autoReturn);
    }

    public EnergyPriority getEnergyPriority() {
        return this.energyPriority;
    }

    public void setEnergyPriority(EnergyPriority priority) {
        this.energyPriority = priority == null ? EnergyPriority.EU_FIRST : priority;
        setChanged();
    }

    public void toggleEnergyPriority() {
        setEnergyPriority(this.energyPriority.toggle());
    }

    public String getStatusKey() {
        return this.statusKey;
    }

    public void setStatusKey(String statusKey) {
        if (statusKey != null && !statusKey.equals(this.statusKey)) {
            this.statusKey = statusKey;
            setChanged();
        }
    }

    /** Sends an up-to-date {@link S2CStationSyncPacket} to {@code player}. */
    public void sendSyncTo(ServerPlayer player) {
        if (this.level instanceof ServerLevel serverLevel) {
            Lc2Network.sendToPlayer(player, buildSyncPacket(serverLevel));
        }
    }

    protected List<S2CStationSyncPacket.TargetSummary> buildTargetSummaries(ServerLevel level) {
        BlockPos3i origin = Lc2Coords.toCore(this.worldPosition);
        List<StationEntry> entries = LogisticsLevelManager.stations(level)
                .listCompatibleTargets(origin, stationType());
        List<S2CStationSyncPacket.TargetSummary> list = new ArrayList<>(entries.size());
        for (StationEntry entry : entries) {
            list.add(new S2CStationSyncPacket.TargetSummary(
                    Lc2Coords.toMc(entry.pos()),
                    entry.displayName(),
                    origin.manhattanDistance(entry.pos()),
                    entry.freeBays(),
                    entry.maxDroneBays(),
                    entry.waterBerthPos() != null
            ));
        }
        return list;
    }

    // --- WorldlyContainer & Forge Capability ---

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(this.items, slot, amount);
        if (!removed.isEmpty()) {
            onSlotChanged(slot);
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(this.items, slot);
        if (!removed.isEmpty()) {
            onSlotChanged(slot);
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        int limit = getSlotMaxStackSize(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > limit) {
            stack.setCount(limit);
        }
        onSlotChanged(slot);
        setChanged();
    }

    protected int getSlotMaxStackSize(int slot, ItemStack stack) {
        if (slot == batterySlotIndex()) {
            return LogisticsConstants.SINGLE_USE_BATTERY_STACK_LIMIT;
        }
        return Math.min(getMaxStackSize(), stack.isEmpty() ? getMaxStackSize() : stack.getMaxStackSize());
    }

    protected void onSlotChanged(int slot) {
        if (slot == batterySlotIndex() && !this.items.get(slot).isEmpty()) {
            this.dispatchCooldown = 0;
        }
        int upgStart = upgradeStartIndex();
        if (slot >= upgStart && slot < upgStart + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            this.upgradesDirty = true;
        }
    }

    /**
     * Computes the vanilla 0..15 Redstone Comparator analog output signal strictly over the
     * Outbound cargo zone {@code [0, 27)}, excluding Storage ({@code 27..53}), batteries,
     * RF chips, docked drones, and machine upgrades.
     */
    public int getCargoComparatorSignal() {
        if (OUTBOUND_COUNT <= 0) {
            return 0;
        }
        int nonEmpty = 0;
        float fillFractionSum = 0.0F;
        for (int i = OUTBOUND_START; i < OUTBOUND_END; i++) {
            ItemStack stack = this.items.get(i);
            if (!stack.isEmpty()) {
                int maxStack = Math.max(1, Math.min(getMaxStackSize(), stack.getMaxStackSize()));
                fillFractionSum += (float) stack.getCount() / (float) maxStack;
                nonEmpty++;
            }
        }
        fillFractionSum /= (float) OUTBOUND_COUNT;
        return Mth.floor(fillFractionSum * 14.0F) + (nonEmpty > 0 ? 1 : 0);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < cargoSlotCount()) {
            return true;
        }
        if (slot == batterySlotIndex()) {
            return Lc2Coords.isSingleUseBattery(stack);
        }
        int upgStart = upgradeStartIndex();
        if (slot >= upgStart && slot < upgStart + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            return UpgradeSummary.isSupportedUpgrade(Lc2Coords.itemId(stack));
        }
        return false;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return this.automatedSlots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        if (isOutboundSlot(slot)) {
            return true;
        }
        if (slot == batterySlotIndex()) {
            return Lc2Coords.isSingleUseBattery(stack);
        }
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return isStorageSlot(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(
                this.worldPosition.getX() + 0.5,
                this.worldPosition.getY() + 0.5,
                this.worldPosition.getZ() + 0.5
        ) <= 64.0;
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.upgradesDirty = true;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
            Direction resolved = side == null ? Direction.UP : side;
            return this.sidedHandlers[resolved.ordinal()].cast();
        }
        return super.getCapability(cap, side);
    }

    // --- NBT Persistence & Legacy Migration ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("LayoutVersion", LAYOUT_VERSION);
        ContainerHelper.saveAllItems(tag, this.items);
        tag.putDouble("StoredEu", this.storedEu);
        tag.putString("StationName", this.stationName);
        tag.putBoolean("AutoReturn", this.autoReturn);
        tag.putInt("EnergyPriority", this.energyPriority.ordinal());
        tag.putString("StatusKey", this.statusKey);
        if (this.targetPos != null) {
            tag.putBoolean("HasTarget", true);
            tag.putInt("TargetX", this.targetPos.getX());
            tag.putInt("TargetY", this.targetPos.getY());
            tag.putInt("TargetZ", this.targetPos.getZ());
        } else {
            tag.putBoolean("HasTarget", false);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items.clear();
        if (tag.getInt("LayoutVersion") >= LAYOUT_VERSION) {
            ContainerHelper.loadAllItems(tag, this.items);
        } else {
            migrateLegacyInventory(tag);
        }
        double rawEu = tag.getDouble("StoredEu");
        this.storedEu = (!Double.isFinite(rawEu) || rawEu <= 0.0) ? 0.0 : rawEu;
        this.stationName = tag.getString("StationName");
        this.autoReturn = tag.getBoolean("AutoReturn");
        this.energyPriority = EnergyPriority.fromOrdinal(tag.getInt("EnergyPriority"));
        this.statusKey = tag.contains("StatusKey") ? tag.getString("StatusKey") : "status.lc2.ready";
        if (tag.getBoolean("HasTarget")) {
            this.targetPos = new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"));
        } else {
            this.targetPos = null;
        }
        this.upgradesDirty = true;
    }

    private void migrateLegacyInventory(CompoundTag tag) {
        int legacySize = legacyTotalSlots();
        NonNullList<ItemStack> legacy = NonNullList.withSize(legacySize, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, legacy);
        for (int oldSlot = 0; oldSlot < legacySize; oldSlot++) {
            ItemStack stack = legacy.get(oldSlot);
            if (stack.isEmpty()) {
                continue;
            }
            int mapped = remapLegacySlot(oldSlot);
            if (mapped >= 0 && mapped < this.items.size()) {
                this.items.set(mapped, stack);
            }
        }
    }
}
