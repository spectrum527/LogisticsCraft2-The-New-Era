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
import java.util.BitSet;
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
 * upgrades, 3x3 chunk-loading tickets (2 EU/t), loop-free adjacent container I/O, non-hybrid
 * EU/battery payment, station naming/targeting, and GUI sync packet construction.
 */
public abstract class AbstractLogisticsStationBlockEntity extends BlockEntity
        implements WorldPowerPort, EuBuffer, MenuProvider, WorldlyContainer {

    private static final EnergyGraph.Ports ALL_FACE_SINK =
            new EnergyGraph.Ports(EnumSet.allOf(CapabilityFacade.Face.class), Set.of(), false);

    protected final NonNullList<ItemStack> items;
    protected final BitSet incomingOverflowSlots;
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
        this.incomingOverflowSlots = new BitSet(cargoSlotCount());
        int cargoCount = cargoSlotCount();
        this.automatedSlots = new int[cargoCount + 1];
        for (int i = 0; i < cargoCount; i++) {
            this.automatedSlots[i] = i;
        }
        this.automatedSlots[cargoCount] = batterySlotIndex();
        this.sidedHandlers = SidedInvWrapper.create(this, Direction.values());
    }

    /** Station category ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION}). */
    public abstract StationType stationType();

    /** Number of internal cargo buffer slots starting at index 0. */
    public abstract int cargoSlotCount();

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
            }
            setChanged();
        } else {
            releaseChunkTickets(level);
        }

        // Periodic loop-free container overflow push & optional pulling upgrade trickle
        int ioPeriod = this.upgrades.overclockerCount() > 0 ? 2 : 5;
        if (gameTime % ioPeriod == 0) {
            flushIncomingOverflow(level);
            if (this.upgrades.hasPulling()) {
                tricklePullIntoOutgoingBuffer(level);
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

    /** Returns the number of {@code ic2:single_use_battery} items currently in the battery slot. */
    public int getBatteryCount() {
        ItemStack stack = this.items.get(batterySlotIndex());
        if (stack.isEmpty() || !LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Coords.itemId(stack))) {
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

    // --- Loop-Free Adjacent Container I/O ---

    /**
     * Unloads arriving vehicle cargo immediately into adjacent containers (honoring ejector upgrade
     * faces if installed), placing overflow safely into internal buffer slots marked as incoming
     * overflow so they are never re-dispatched as outgoing cargo.
     *
     * @return any stacks that could not fit into either adjacent containers or internal buffer
     */
    public List<ItemStack> unloadIncomingCargo(ServerLevel level, List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return List.of();
        }
        refreshUpgradesIfNeeded();
        List<Direction> pushFaces = ContainerIoHelper.resolvePushFaces(getUpgradeStacks());
        List<ItemStack> remaining = new ArrayList<>();

        for (ItemStack raw : cargo) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            ItemStack leftover = ContainerIoHelper.pushToAdjacent(level, this.worldPosition, pushFaces, raw.copy());
            if (!leftover.isEmpty()) {
                leftover = insertIntoIncomingOverflowBuffer(leftover);
            }
            if (!leftover.isEmpty()) {
                remaining.add(leftover);
            }
        }
        setChanged();
        return remaining;
    }

    /**
     * Loads outgoing cargo for a departing vehicle into {@code destination} (up to {@code maxSlots}
     * slots): takes from non-overflow internal cargo buffer first, then pulls remaining capacity
     * from adjacent containers (honoring pulling upgrade faces if installed).
     */
    protected void loadOutgoingCargoInto(ServerLevel level, Container destination, int maxSlots) {
        refreshUpgradesIfNeeded();
        int cargoCount = cargoSlotCount();

        // 1. Pull from non-overflow outgoing buffer slots
        for (int slot = 0; slot < cargoCount; slot++) {
            if (this.incomingOverflowSlots.get(slot)) {
                continue;
            }
            ItemStack stack = this.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = ContainerIoHelper.insertIntoContainer(destination, 0, maxSlots, stack);
            this.items.set(slot, leftover);
        }

        // 2. Pull remaining capacity from adjacent containers
        List<Direction> pullFaces = ContainerIoHelper.resolvePullFaces(getUpgradeStacks());
        ContainerIoHelper.pullFromAdjacentInto(
                level,
                this.worldPosition,
                pullFaces,
                Set.of(),
                destination,
                0,
                maxSlots,
                maxSlots * 64
        );
        setChanged();
    }

    private ItemStack insertIntoIncomingOverflowBuffer(ItemStack stack) {
        ItemStack remaining = stack.copy();
        int cargoCount = cargoSlotCount();
        int maxStack = Math.min(remaining.getMaxStackSize(), getMaxStackSize());

        // First stack onto existing incoming overflow slots only (never contaminate outgoing slots)
        for (int slot = 0; slot < cargoCount && !remaining.isEmpty(); slot++) {
            if (!this.incomingOverflowSlots.get(slot)) {
                continue;
            }
            ItemStack existing = this.items.get(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)) {
                int space = Math.min(maxStack, existing.getMaxStackSize()) - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }

        // Next place into empty cargo buffer slots and mark them as incoming overflow
        for (int slot = 0; slot < cargoCount && !remaining.isEmpty(); slot++) {
            if (this.items.get(slot).isEmpty()) {
                int moved = Math.min(maxStack, remaining.getCount());
                this.items.set(slot, remaining.split(moved));
                this.incomingOverflowSlots.set(slot, true);
            }
        }
        return remaining;
    }

    private void flushIncomingOverflow(ServerLevel level) {
        if (this.incomingOverflowSlots.isEmpty()) {
            return;
        }
        List<Direction> pushFaces = ContainerIoHelper.resolvePushFaces(getUpgradeStacks());
        int budget = this.upgrades.transferRatePerTick();
        int cargoCount = cargoSlotCount();
        boolean changed = false;

        for (int slot = 0; slot < cargoCount && budget > 0; slot++) {
            if (!this.incomingOverflowSlots.get(slot)) {
                continue;
            }
            ItemStack stack = this.items.get(slot);
            if (stack.isEmpty()) {
                this.incomingOverflowSlots.clear(slot);
                continue;
            }
            int moveCount = Math.min(budget, stack.getCount());
            ItemStack probe = stack.copy();
            probe.setCount(moveCount);
            ItemStack leftover = ContainerIoHelper.pushToAdjacent(level, this.worldPosition, pushFaces, probe);
            int pushed = moveCount - leftover.getCount();
            if (pushed > 0) {
                stack.shrink(pushed);
                budget -= pushed;
                if (stack.isEmpty()) {
                    this.items.set(slot, ItemStack.EMPTY);
                    this.incomingOverflowSlots.clear(slot);
                }
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    private void tricklePullIntoOutgoingBuffer(ServerLevel level) {
        List<Direction> pullFaces = ContainerIoHelper.resolvePullFaces(getUpgradeStacks());
        Set<Direction> excluded = this.upgrades.hasEjector()
                ? EnumSet.copyOf(ContainerIoHelper.resolvePushFaces(getUpgradeStacks()))
                : Set.of();
        int budget = this.upgrades.transferRatePerTick();
        int cargoCount = cargoSlotCount();

        // Only pull into non-overflow cargo slots via a temporary restricted view
        Container outgoingView = new Container() {
            @Override
            public int getContainerSize() {
                return cargoCount;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public ItemStack getItem(int slot) {
                return incomingOverflowSlots.get(slot) ? ItemStack.EMPTY : items.get(slot);
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                if (!incomingOverflowSlots.get(slot)) {
                    items.set(slot, stack);
                }
            }

            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return !incomingOverflowSlots.get(slot);
            }

            @Override
            public void setChanged() {
                AbstractLogisticsStationBlockEntity.this.setChanged();
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }

            @Override
            public void clearContent() {
            }
        };

        ContainerIoHelper.pullFromAdjacentInto(
                level,
                this.worldPosition,
                pullFaces,
                excluded,
                outgoingView,
                0,
                cargoCount,
                budget
        );
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
        this.targetPos = target == null ? null : target.immutable();
        this.statusKey = this.targetPos == null
                ? DispatchFailure.NO_TARGET.translationKey()
                : "status.lc2.target_updated";
        setChanged();
    }

    public void cycleTarget(ServerLevel level, int delta) {
        List<StationEntry> targets = LogisticsLevelManager.stations(level)
                .listCompatibleTargets(Lc2Coords.toCore(this.worldPosition), stationType());
        if (targets.isEmpty()) {
            this.targetPos = null;
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
        this.targetPos = Lc2Coords.toMc(targets.get(nextIndex).pos());
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
        if (slot >= 0 && slot < cargoSlotCount()) {
            // Player/automation interaction clears incoming-overflow tag when emptied or replaced
            if (this.items.get(slot).isEmpty()) {
                this.incomingOverflowSlots.clear(slot);
            }
        }
        int upgStart = upgradeStartIndex();
        if (slot >= upgStart && slot < upgStart + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            this.upgradesDirty = true;
        }
    }

    /** Clears the incoming-overflow tag when a player manually edits a cargo buffer slot in GUI. */
    public void markSlotUserModified(int slot) {
        if (slot >= 0 && slot < cargoSlotCount()) {
            this.incomingOverflowSlots.clear(slot);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < cargoSlotCount()) {
            return true;
        }
        if (slot == batterySlotIndex()) {
            return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Coords.itemId(stack));
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
        if (slot >= 0 && slot < cargoSlotCount()) {
            return !this.incomingOverflowSlots.get(slot);
        }
        if (slot == batterySlotIndex()) {
            return LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(Lc2Coords.itemId(stack));
        }
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return slot >= 0 && slot < cargoSlotCount();
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
        this.incomingOverflowSlots.clear();
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

    // --- NBT Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
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
        long[] overflowBits = this.incomingOverflowSlots.toLongArray();
        if (overflowBits.length > 0) {
            tag.putLongArray("IncomingOverflow", overflowBits);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items);
        this.storedEu = Math.max(0.0, tag.getDouble("StoredEu"));
        this.stationName = tag.getString("StationName");
        this.autoReturn = tag.getBoolean("AutoReturn");
        this.energyPriority = EnergyPriority.fromOrdinal(tag.getInt("EnergyPriority"));
        this.statusKey = tag.contains("StatusKey") ? tag.getString("StatusKey") : "status.lc2.ready";
        if (tag.getBoolean("HasTarget")) {
            this.targetPos = new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"));
        } else {
            this.targetPos = null;
        }
        this.incomingOverflowSlots.clear();
        if (tag.contains("IncomingOverflow")) {
            BitSet loaded = BitSet.valueOf(tag.getLongArray("IncomingOverflow"));
            this.incomingOverflowSlots.or(loaded);
        }
        this.upgradesDirty = true;
    }
}
