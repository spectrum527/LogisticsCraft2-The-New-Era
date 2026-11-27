package com.logisticscraft.lc2.blockentity;

import com.industrialage.ia2.energy.graph.EnergyGraph.Ports;
import com.industrialage.ia2.platform.CapabilityFacade.Face;
import com.industrialage.ia2.power.EuBuffer;
import com.industrialage.ia2.power.WorldEnergy;
import com.industrialage.ia2.power.WorldPowerPort;
import com.industrialage.ia2.world.MachineAutomation;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.EnergyCost;
import com.logisticscraft.lc2.core.EnergyCostCalculator;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.PaymentPlan;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.UpgradeSummary;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2ItemAccess;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.platform.Lc2StationBlockEntityBase;
import com.logisticscraft.lc2.platform.LogisticsChunkTickets;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared server/client block entity base for Smart Port and Cargo Station, implementing:
 * <ul>
 *   <li>IC2 EU network sink integration ({@link WorldPowerPort}, {@link EuBuffer}, {@link WorldEnergy})</li>
 *   <li>Single-use battery slot & non-hybrid energy priority payment</li>
 *   <li>All 9 supported IC2 + LC2 machine upgrades including 3x3 chunk loading at 2 EU/t</li>
 *   <li>Strict 2-zone inventory (Outbound {@code 0..26} / Storage {@code 27..53}) with atomic swap
 *       and loop-free IC2 pulling/ejector upgrade automation</li>
 * </ul>
 */
public abstract class AbstractLogisticsStationBlockEntity extends Lc2StationBlockEntityBase
        implements WorldPowerPort, EuBuffer, MenuProvider, WorldlyContainer {

    /** Shared {@code active} blockstate property used by both Smart Port and Cargo Station. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    /** Starting slot index of the Outbound Area ({@code 0..26}). */
    public static final int OUTBOUND_START = LogisticsConstants.STATION_OUTBOUND_START;

    /** Number of slots in the Outbound Area ({@code 27}). */
    public static final int OUTBOUND_SLOTS = LogisticsConstants.STATION_OUTBOUND_SLOTS;

    /** Starting slot index of the Storage Area ({@code 27..53}). */
    public static final int STORAGE_START = LogisticsConstants.STATION_STORAGE_START;

    /** Number of slots in the Storage Area ({@code 27}). */
    public static final int STORAGE_SLOTS = LogisticsConstants.STATION_STORAGE_SLOTS;

    /** Total cargo slots across Outbound + Storage ({@code 54}). */
    public static final int TOTAL_CARGO_SLOTS = LogisticsConstants.STATION_TOTAL_CARGO_SLOTS;

    /** Current persistent inventory layout version (v2 = 2-zone Outbound/Storage). */
    public static final int LAYOUT_VERSION = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractLogisticsStationBlockEntity.class);
    private static final AtomicBoolean FILTER_REFLECTION_WARNED = new AtomicBoolean(false);
    private static final Ports SINK_ALL_FACES = new Ports(EnumSet.allOf(Face.class), Set.of(), false);
    private static final Method IC2_MATCHES_FILTER = resolveIc2MatchesFilter();

    private static Method resolveIc2MatchesFilter() {
        try {
            return MachineAutomation.class.getMethod("matchesFilter", ItemStack.class, ItemStack.class);
        } catch (Throwable t) {
            if (FILTER_REFLECTION_WARNED.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Failed to resolve IC2 MachineAutomation.matchesFilter; advanced upgrade filters will fall back to permissive matching",
                        t
                );
            }
            return null;
        }
    }

    private static boolean matchesUpgradeFilter(ItemStack upgrade, ItemStack candidate) {
        if (IC2_MATCHES_FILTER == null || upgrade.isEmpty() || candidate.isEmpty()) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(IC2_MATCHES_FILTER.invoke(null, upgrade, candidate));
        } catch (Throwable t) {
            if (FILTER_REFLECTION_WARNED.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Failed to invoke IC2 MachineAutomation.matchesFilter; advanced upgrade filters will fall back to permissive matching",
                        t
                );
            }
            return true;
        }
    }

    protected final NonNullList<ItemStack> items;
    protected double storedEu = 0.0;
    protected String stationName = "";
    protected BlockPos targetPos = null;
    protected boolean autoReturn = false;
    protected EnergyPriority energyPriority = EnergyPriority.EU_FIRST;
    protected String statusKey = "status.lc2.ready";
    protected UpgradeSummary upgrades = UpgradeSummary.EMPTY;

    private int lastServerTick = Integer.MIN_VALUE;
    private boolean chunkLoaderActive = false;
    private ChunkPos heldChunkCenter = null;
    private boolean lastRedstoneActive = false;
    private int ioTickCounter = 0;
    private int dispatchCooldown = 0;
    private boolean removalProcessed = false;

    protected AbstractLogisticsStationBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            int inventorySize
    ) {
        super(type, pos, state);
        this.items = NonNullList.withSize(inventorySize, ItemStack.EMPTY);
    }

    // --- Abstract Layout Hooks ---

    /** Returns the station type ({@link StationType#SMART_PORT} or {@link StationType#CARGO_STATION}). */
    public abstract StationType stationType();

    /** Returns the single-use battery slot index. */
    public abstract int batterySlotIndex();

    /** Returns the first machine upgrade slot index (4 consecutive slots). */
    public abstract int upgradeSlotStart();

    /** Remaps legacy v1 inventory slots into the current v2 2-zone layout with zero item loss. */
    protected abstract void migrateLegacyInventory(int fromVersion);

    /** Returns {@code true} if {@code slot} is inside the Outbound Area ({@code 0..26}). */
    public static boolean isOutboundSlot(int slot) {
        return slot >= OUTBOUND_START && slot < OUTBOUND_START + OUTBOUND_SLOTS;
    }

    /** Returns {@code true} if {@code slot} is inside the Storage Area ({@code 27..53}). */
    public static boolean isStorageSlot(int slot) {
        return slot >= STORAGE_START && slot < STORAGE_START + STORAGE_SLOTS;
    }

    /** Returns {@code true} if {@code slot} is inside either cargo zone ({@code 0..53}). */
    public static boolean isCargoSlot(int slot) {
        return slot >= OUTBOUND_START && slot < TOTAL_CARGO_SLOTS;
    }

    /** Registers or updates this station in the dimension's {@link com.logisticscraft.lc2.core.StationRegistry}. */
    protected abstract void registerInWorld(ServerLevel level);

    /** Called periodically during server ticks for station-specific checks and redstone dispatch. */
    protected abstract void tickStationLogic(ServerLevel level, boolean redstoneTriggered, boolean redstoneRisingEdge);

    /** Returns whether this station's blockstate {@code active} property should currently be true. */
    protected abstract boolean shouldDisplayActive(ServerLevel level);

    /** Builds a live GUI synchronization payload for an open player screen. */
    public abstract StationSyncPayload buildSyncPayload(ServerLevel level);

    /** Attempts to dispatch a vehicle from this station (called by redstone or GUI button). */
    public final void handleManualDispatch(ServerLevel level) {
        handleManualDispatchWithResult(level);
    }

    /**
     * Attempts to manually dispatch a vehicle from this station and returns {@code true} iff
     * the launch succeeded (used by remote Operation Terminal dispatch energy accounting).
     */
    public abstract boolean handleManualDispatchWithResult(ServerLevel level);

    // --- Lifecycle ---

    @Override
    protected NonNullList<ItemStack> inventoryItems() {
        return items;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refreshUpgrades();
        if (level instanceof ServerLevel serverLevel) {
            WorldEnergy.connect(this);
            registerInWorld(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            WorldEnergy.disconnect(this);
            releaseChunkTickets(serverLevel);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel serverLevel) {
            WorldEnergy.disconnect(this);
            releaseChunkTickets(serverLevel);
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
        releaseChunkTickets(level);
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        data.setChunkLoaderStation(LogisticsSavedData.toPos3i(pos), false);
        onUnregisterFromWorld(level, data);
        data.stationRegistry().unregister(LogisticsSavedData.toPos3i(pos));
        data.setDirty();
        Containers.dropContents(level, pos, items);
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
    }

    /** Subclass hook invoked right before unregistering from StationRegistry on block destruction. */
    protected void onUnregisterFromWorld(ServerLevel level, LogisticsSavedData data) {
    }

    /** Server-side per-tick driver invoked by the block's BlockEntityTicker. */
    public final void serverTick(ServerLevel level) {
        lastServerTick = level.getServer().getTickCount();
        refreshUpgrades();

        // 1. Chunk Loading Upgrade maintenance (3x3 chunks, 2 EU/t standby drain)
        tickChunkLoader(level);

        // 2. Periodic loop-free adjacent container overflow push + pulling upgrade
        tickAdjacentContainerIo(level);

        // 3. Redstone trigger evaluation (respecting ic2:redstone_inverter_upgrade)
        boolean redstoneTriggered = isRedstoneTriggered(level);
        boolean risingEdge = redstoneTriggered && !lastRedstoneActive;
        lastRedstoneActive = redstoneTriggered;

        if (dispatchCooldown > 0) {
            dispatchCooldown--;
        }
        tickStationLogic(level, redstoneTriggered, risingEdge);

        // 4. Sync ACTIVE blockstate property for block texture feedback
        boolean active = shouldDisplayActive(level);
        BlockState state = getBlockState();
        if (!isRemoved() && state.hasProperty(ACTIVE) && state.getValue(ACTIVE) != active) {
            level.setBlock(worldPosition, state.setValue(ACTIVE, active), Block.UPDATE_CLIENTS);
        }
    }

    /** Called immediately when a neighbor block changes (fast redstone reaction). */
    public final void onRedstoneUpdate(ServerLevel level) {
        refreshUpgrades();
        boolean triggered = isRedstoneTriggered(level);
        if (triggered && !lastRedstoneActive && dispatchCooldown <= 0) {
            lastRedstoneActive = true;
            tickStationLogic(level, true, true);
        } else if (!triggered) {
            lastRedstoneActive = false;
        }
    }

    protected final boolean canAttemptAutoDispatch() {
        return dispatchCooldown <= 0;
    }

    protected final void setDispatchCooldown(int ticks) {
        this.dispatchCooldown = Math.max(this.dispatchCooldown, ticks);
    }

    /** Clears any remaining auto-dispatch retry cooldown so newly docked vehicles launch immediately. */
    protected final void resetDispatchCooldown() {
        this.dispatchCooldown = 0;
    }

    private void tickChunkLoader(ServerLevel level) {
        if (upgrades.shouldChunkLoad(storedEu)) {
            storedEu = Math.max(0.0, storedEu - upgrades.chunkLoaderStandbyDrainEuPerTick());
            ChunkPos center = new ChunkPos(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
            if (!chunkLoaderActive) {
                LogisticsChunkTickets.holdArea3x3(level, center);
                chunkLoaderActive = true;
                heldChunkCenter = center;
                LogisticsLevelManager.getData(level)
                        .setChunkLoaderStation(LogisticsSavedData.toPos3i(worldPosition), true);
            }
            setChanged();
        } else if (chunkLoaderActive) {
            releaseChunkTickets(level);
            LogisticsLevelManager.getData(level)
                    .setChunkLoaderStation(LogisticsSavedData.toPos3i(worldPosition), false);
        }
    }

    private void releaseChunkTickets(ServerLevel level) {
        if (chunkLoaderActive && heldChunkCenter != null) {
            LogisticsChunkTickets.releaseArea3x3(level, heldChunkCenter);
            chunkLoaderActive = false;
            heldChunkCenter = null;
        }
    }

    // --- Upgrades & Loop-Free Adjacent Container I/O ---

    /** Recomputes {@link UpgradeSummary} from the 4 upgrade slots and clamps stored EU to max capacity. */
    public final void refreshUpgrades() {
        UpgradeSummary.Builder builder = UpgradeSummary.builder();
        int start = upgradeSlotStart();
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack stack = items.get(start + i);
            if (!stack.isEmpty()) {
                builder.add(Lc2Native.itemId(stack), stack.getCount());
            }
        }
        upgrades = builder.build();
        double maxEu = upgrades.maxCapacityEu();
        if (storedEu > maxEu) {
            storedEu = maxEu;
        }
    }

    /** Returns the configured ejector faces if an ejector upgrade is installed, or empty if none. */
    protected final List<Direction> resolveEjectorFaces() {
        Set<Direction> faces = new LinkedHashSet<>();
        int start = upgradeSlotStart();
        boolean hasConfiguredEjector = false;
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack stack = items.get(start + i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Lc2Native.itemId(stack);
            if (LogisticsConstants.UPGRADE_EJECTOR.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_EJECTOR.equals(id)) {
                hasConfiguredEjector = true;
                faces.addAll(MachineAutomation.faces(stack));
            }
        }
        if (!hasConfiguredEjector) {
            return List.of();
        }
        if (faces.isEmpty()) {
            return List.of(Direction.values());
        }
        return List.copyOf(faces);
    }

    /** Returns the configured pulling faces if a pulling upgrade is installed, or empty if none. */
    protected final List<Direction> resolvePullingFaces() {
        Set<Direction> faces = new LinkedHashSet<>();
        int start = upgradeSlotStart();
        boolean hasConfiguredPuller = false;
        boolean hasExplicitPullFace = false;
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack stack = items.get(start + i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Lc2Native.itemId(stack);
            if (LogisticsConstants.UPGRADE_PULLING.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_PULLING.equals(id)) {
                hasConfiguredPuller = true;
                List<Direction> upgFaces = MachineAutomation.faces(stack);
                if (upgFaces.size() < Direction.values().length) {
                    hasExplicitPullFace = true;
                }
                faces.addAll(upgFaces);
            }
        }
        if (!hasConfiguredPuller) {
            return List.of();
        }
        if (faces.isEmpty()) {
            faces.addAll(List.of(Direction.values()));
        }
        // Prevent single-chest push/pull loop-back by excluding faces actively used by ejector upgrades
        if (upgrades.hasEjector()) {
            Set<Direction> explicitEjectFaces = new LinkedHashSet<>();
            for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
                ItemStack stack = items.get(start + i);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = Lc2Native.itemId(stack);
                if (LogisticsConstants.UPGRADE_EJECTOR.equals(id)
                        || LogisticsConstants.UPGRADE_ADVANCED_EJECTOR.equals(id)) {
                    List<Direction> ef = MachineAutomation.faces(stack);
                    if (ef.size() < Direction.values().length) {
                        explicitEjectFaces.addAll(ef);
                    }
                }
            }
            faces.removeAll(explicitEjectFaces);
            if (!hasExplicitPullFace) {
                faces.removeAll(resolveEjectorFaces());
            }
        }
        return List.copyOf(faces);
    }

    /** Returns {@code true} if at least one installed ejector upgrade targeting {@code face} permits {@code stack}. */
    private boolean permitsEject(Direction face, ItemStack stack) {
        int start = upgradeSlotStart();
        boolean matchedUpgrade = false;
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack upg = items.get(start + i);
            if (upg.isEmpty()) {
                continue;
            }
            String id = Lc2Native.itemId(upg);
            if (LogisticsConstants.UPGRADE_EJECTOR.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_EJECTOR.equals(id)) {
                List<Direction> upgFaces = MachineAutomation.faces(upg);
                if (upgFaces.isEmpty() || upgFaces.contains(face)) {
                    matchedUpgrade = true;
                    if (matchesUpgradeFilter(upg, stack)) {
                        return true;
                    }
                }
            }
        }
        return !matchedUpgrade;
    }

    /** Returns {@code true} if at least one installed pulling upgrade targeting {@code face} permits {@code stack}. */
    private boolean permitsPull(Direction face, ItemStack stack) {
        int start = upgradeSlotStart();
        boolean matchedUpgrade = false;
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            ItemStack upg = items.get(start + i);
            if (upg.isEmpty()) {
                continue;
            }
            String id = Lc2Native.itemId(upg);
            if (LogisticsConstants.UPGRADE_PULLING.equals(id)
                    || LogisticsConstants.UPGRADE_ADVANCED_PULLING.equals(id)) {
                List<Direction> upgFaces = MachineAutomation.faces(upg);
                if (upgFaces.isEmpty() || upgFaces.contains(face)) {
                    matchedUpgrade = true;
                    if (matchesUpgradeFilter(upg, stack)) {
                        return true;
                    }
                }
            }
        }
        return !matchedUpgrade;
    }

    /**
     * Unloads arriving vehicle cargo strictly into the Storage Area ({@code 27..53}).
     * If an IC2 ejector upgrade is installed, immediately ejects from Storage / overflow through
     * the configured ejector faces so full 36-slot Heavy Drones and 54-slot Boats unload smoothly.
     * Any overflow that still cannot fit is returned to the caller so the vehicle retries safely
     * without ever losing or dropping items.
     */
    public List<ItemStack> receiveIncomingCargo(ServerLevel level, List<ItemStack> incoming) {
        refreshUpgrades();
        List<ItemStack> unaccepted = new ArrayList<>();
        boolean hasEjector = upgrades.hasEjector();
        List<Direction> ejectFaces = hasEjector ? resolveEjectorFaces() : List.of();

        for (ItemStack raw : incoming) {
            if (raw.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoStorage(raw.copy());

            if (hasEjector && !ejectFaces.isEmpty()) {
                ejectStorageStacks(level, ejectFaces, Integer.MAX_VALUE);
                if (!remaining.isEmpty()) {
                    remaining = insertIntoStorage(remaining);
                }
                if (!remaining.isEmpty()) {
                    for (Direction dir : ejectFaces) {
                        if (remaining.isEmpty()) {
                            break;
                        }
                        if (!permitsEject(dir, remaining)) {
                            continue;
                        }
                        int pushed = Lc2ItemAccess.push(level, worldPosition.relative(dir), dir.getOpposite(), remaining);
                        if (pushed > 0) {
                            remaining.shrink(pushed);
                        }
                    }
                }
            }

            if (!remaining.isEmpty()) {
                unaccepted.add(remaining);
            }
        }
        if (hasEjector && !ejectFaces.isEmpty()) {
            ejectStorageStacks(level, ejectFaces, Integer.MAX_VALUE);
        }
        setChanged();
        return unaccepted;
    }

    /**
     * Loads outgoing cargo for a departing vehicle up to {@code maxStacks}:
     * drains ONLY the Outbound Area ({@code 0..26}) — NEVER Storage ({@code 27..53}).
     * If a pulling upgrade is installed and the vehicle still has free slots, pulls remaining
     * capacity strictly from configured pulling upgrade faces. Without a pulling upgrade, never
     * pulls from adjacent containers during launch.
     */
    public List<ItemStack> loadOutgoingCargo(ServerLevel level, int maxStacks) {
        refreshUpgrades();
        List<ItemStack> cargo = new ArrayList<>();

        // Step 1: Drain ONLY Outbound Area slots (0..26) — NEVER Storage (27..53)
        for (int i = OUTBOUND_START; i < OUTBOUND_START + OUTBOUND_SLOTS && cargo.size() < maxStacks; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                cargo.add(stack.copy());
                items.set(i, ItemStack.EMPTY);
            }
        }

        // Step 2: ONLY if pulling upgrade is installed AND vehicle still has free slots
        if (cargo.size() < maxStacks && upgrades.hasPulling()) {
            List<Direction> pullFaces = resolvePullingFaces();
            boolean pulledAny = true;
            while (cargo.size() < maxStacks && pulledAny && !pullFaces.isEmpty()) {
                pulledAny = false;
                for (Direction dir : pullFaces) {
                    if (cargo.size() >= maxStacks) {
                        break;
                    }
                    ItemStack extracted = Lc2ItemAccess.pull(
                            level,
                            worldPosition.relative(dir),
                            dir.getOpposite(),
                            64,
                            stack -> !stack.isEmpty() && permitsPull(dir, stack)
                    );
                    if (!extracted.isEmpty()) {
                        ItemStack leftover = addToCargoList(cargo, extracted, maxStacks);
                        if (!leftover.isEmpty()) {
                            ItemStack unreturned = insertIntoOutbound(leftover);
                            if (!unreturned.isEmpty()) {
                                int pushedBack = Lc2ItemAccess.push(
                                        level, worldPosition.relative(dir), dir.getOpposite(), unreturned);
                                unreturned.shrink(pushedBack);
                            }
                            if (!unreturned.isEmpty()) {
                                Containers.dropItemStack(
                                        level,
                                        worldPosition.getX() + 0.5,
                                        worldPosition.getY() + 1.0,
                                        worldPosition.getZ() + 0.5,
                                        unreturned
                                );
                            }
                        }
                        pulledAny = true;
                    }
                }
            }
        }

        if (!cargo.isEmpty()) {
            setChanged();
        }
        return cargo;
    }

    /**
     * Atomically swaps every slot pair {@code i <-> 27 + i} between Outbound ({@code 0..26})
     * and Storage ({@code 27..53}), marks the station dirty, and updates status to
     * {@code status.lc2.swapped}.
     */
    public final void swapCargoZones() {
        for (int i = 0; i < OUTBOUND_SLOTS; i++) {
            int outboundIdx = OUTBOUND_START + i;
            int storageIdx = STORAGE_START + i;
            ItemStack tmp = items.get(outboundIdx);
            items.set(outboundIdx, items.get(storageIdx));
            items.set(storageIdx, tmp);
        }
        setStatusKey("status.lc2.swapped");
        setChanged();
    }

    private ItemStack addToCargoList(List<ItemStack> cargo, ItemStack stack, int maxStacks) {
        ItemStack remaining = stack.copy();
        for (ItemStack existing : cargo) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining.getCount());
                    existing.grow(move);
                    remaining.shrink(move);
                }
            }
        }
        while (!remaining.isEmpty() && cargo.size() < maxStacks) {
            int maxStack = Math.max(1, remaining.getMaxStackSize());
            int move = Math.min(maxStack, remaining.getCount());
            cargo.add(remaining.split(move));
        }
        return remaining;
    }

    /**
     * Computes a vanilla redstone comparator analog output signal (0..15) strictly over this
     * station's Outbound Area slots ({@code 0..26}) so comparators trigger dispatch iff Outbound
     * has cargo waiting to ship.
     */
    public final int computeCargoComparatorSignal() {
        int nonEmpty = 0;
        float fillSum = 0.0F;
        for (int i = OUTBOUND_START; i < OUTBOUND_START + OUTBOUND_SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                int limit = Math.min(getMaxStackSize(), Math.max(1, stack.getMaxStackSize()));
                fillSum += Math.min(1.0F, (float) stack.getCount() / (float) limit);
                nonEmpty++;
            }
        }
        float avg = fillSum / (float) OUTBOUND_SLOTS;
        return Math.min(15, Mth.floor(avg * 14.0F) + (nonEmpty > 0 ? 1 : 0));
    }

    private void tickAdjacentContainerIo(ServerLevel level) {
        ioTickCounter++;
        int interval = Math.max(1, 10 - upgrades.overclockerCount() * 2);
        if (ioTickCounter < interval) {
            return;
        }
        ioTickCounter = 0;
        int budget = upgrades.transferRatePerTick();

        // 1. If ejector upgrade is installed, push strictly from Storage (27..53) to configured faces
        if (upgrades.hasEjector()) {
            List<Direction> ejectFaces = resolveEjectorFaces();
            if (!ejectFaces.isEmpty()) {
                ejectStorageStacks(level, ejectFaces, budget);
            }
        }

        // 2. If pulling upgrade is installed, pull from configured faces strictly into Outbound (0..26)
        if (upgrades.hasPulling()) {
            List<Direction> pullFaces = resolvePullingFaces();
            int remainingBudget = budget;
            for (Direction dir : pullFaces) {
                if (remainingBudget <= 0 || !hasOutboundSpace()) {
                    break;
                }
                ItemStack pulled = Lc2ItemAccess.pull(
                        level,
                        worldPosition.relative(dir),
                        dir.getOpposite(),
                        remainingBudget,
                        stack -> !stack.isEmpty() && permitsPull(dir, stack) && canFitInOutbound(stack)
                );
                if (!pulled.isEmpty()) {
                    remainingBudget -= pulled.getCount();
                    ItemStack leftover = insertIntoOutbound(pulled);
                    if (!leftover.isEmpty()) {
                        int returned = Lc2ItemAccess.push(
                                level, worldPosition.relative(dir), dir.getOpposite(), leftover);
                        leftover.shrink(returned);
                        if (!leftover.isEmpty()) {
                            Containers.dropItemStack(
                                    level,
                                    worldPosition.getX() + 0.5,
                                    worldPosition.getY() + 1.0,
                                    worldPosition.getZ() + 0.5,
                                    leftover
                            );
                        }
                    }
                    setChanged();
                }
            }
        }
    }

    private void ejectStorageStacks(ServerLevel level, List<Direction> ejectFaces, int budget) {
        if (ejectFaces.isEmpty() || budget <= 0) {
            return;
        }
        int remainingBudget = budget;
        boolean changed = false;
        for (int i = STORAGE_START; i < STORAGE_START + STORAGE_SLOTS && remainingBudget > 0; i++) {
            ItemStack slotStack = items.get(i);
            if (slotStack.isEmpty()) {
                continue;
            }
            int offerCount = Math.min(slotStack.getCount(), remainingBudget);
            ItemStack offer = slotStack.copyWithCount(offerCount);
            for (Direction dir : ejectFaces) {
                if (offer.isEmpty()) {
                    break;
                }
                if (!permitsEject(dir, offer)) {
                    continue;
                }
                int pushed = Lc2ItemAccess.push(level, worldPosition.relative(dir), dir.getOpposite(), offer);
                if (pushed > 0) {
                    slotStack.shrink(pushed);
                    offer.shrink(pushed);
                    if (remainingBudget != Integer.MAX_VALUE) {
                        remainingBudget -= pushed;
                    }
                    changed = true;
                }
            }
            if (slotStack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (changed) {
            setChanged();
        }
    }

    private boolean hasOutboundSpace() {
        for (int i = OUTBOUND_START; i < OUTBOUND_START + OUTBOUND_SLOTS; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean canFitInOutbound(ItemStack sample) {
        for (int i = OUTBOUND_START; i < OUTBOUND_START + OUTBOUND_SLOTS; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(s, sample) && s.getCount() < s.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    protected final ItemStack insertIntoOutbound(ItemStack stack) {
        return insertIntoRange(stack, OUTBOUND_START, OUTBOUND_START + OUTBOUND_SLOTS);
    }

    protected final ItemStack insertIntoStorage(ItemStack stack) {
        return insertIntoRange(stack, STORAGE_START, STORAGE_START + STORAGE_SLOTS);
    }

    private ItemStack insertIntoRange(ItemStack stack, int startInclusive, int endExclusive) {
        ItemStack remaining = stack.copy();

        // Pass 1: merge into compatible existing stacks in the target zone
        for (int i = startInclusive; i < endExclusive && !remaining.isEmpty(); i++) {
            ItemStack existing = items.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining.getCount());
                    existing.grow(move);
                    remaining.shrink(move);
                }
            }
        }

        // Pass 2: fill empty slots in the target zone
        for (int i = startInclusive; i < endExclusive && !remaining.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int move = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                items.set(i, remaining.split(move));
            }
        }
        return remaining;
    }

    // --- Energy & Payment ---

    /** Returns the current count of supported single-use batteries ({@code #lc2:single_use_batteries}) in the battery slot. */
    public final int availableSingleUseBatteries() {
        ItemStack stack = items.get(batterySlotIndex());
        if (stack.isEmpty()) {
            return 0;
        }
        return Lc2Native.isSingleUseBattery(stack) ? stack.getCount() : 0;
    }

    /** Commits a resolved {@link PaymentPlan} (strictly EU-only or battery-only). */
    public final boolean applyPayment(PaymentPlan plan) {
        if (plan == null || !plan.isPayable()) {
            return false;
        }
        if (plan.isEu()) {
            if (storedEu + 1e-6 < plan.euToSpend()) {
                return false;
            }
            storedEu = Math.max(0.0, storedEu - plan.euToSpend());
            setChanged();
            return true;
        }
        if (plan.isBattery()) {
            ItemStack stack = items.get(batterySlotIndex());
            if (!Lc2Native.isSingleUseBattery(stack)
                    || stack.getCount() < plan.batteriesToSpend()) {
                return false;
            }
            stack.shrink(plan.batteriesToSpend());
            if (stack.isEmpty()) {
                items.set(batterySlotIndex(), ItemStack.EMPTY);
            }
            setChanged();
            return true;
        }
        return false;
    }

    /** Attempts to pay a target-side return trip cost using this station's EU or battery priority. */
    public final boolean tryPayReturnEnergy(EnergyCost returnCost) {
        PaymentPlan plan = EnergyCostCalculator.resolvePayment(
                returnCost,
                storedEu,
                availableSingleUseBatteries(),
                energyPriority
        );
        if (!plan.isPayable()) {
            return false;
        }
        return applyPayment(plan);
    }

    /** Evaluates whether redstone trigger is active (respecting redstone inverter upgrade). */
    public final boolean isRedstoneTriggered(ServerLevel level) {
        return upgrades.isRedstoneTriggered(level.hasNeighborSignal(worldPosition));
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
        double room = Math.max(0.0, upgrades.maxCapacityEu() - storedEu);
        double accepted = Math.min(offered, room);
        if (accepted > 0.0) {
            storedEu += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public double packetLimit() {
        return upgrades.packetLimitEu();
    }

    @Override
    public Reading bufferedEu() {
        return new Reading(storedEu, upgrades.maxCapacityEu());
    }

    // --- Public Accessors & Configuration Mutators ---

    public double getStoredEu() {
        return storedEu;
    }

    public double getMaxEu() {
        return upgrades.maxCapacityEu();
    }

    /** Adds EU directly into the internal buffer (clamped to capacity); useful for testing and API. */
    public double addEnergy(double amount) {
        return receive(amount);
    }

    public UpgradeSummary getUpgrades() {
        return upgrades;
    }

    public boolean isChunkLoaderActive() {
        return chunkLoaderActive;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String name) {
        this.stationName = name == null ? "" : name.trim();
        if (this.stationName.length() > 32) {
            this.stationName = this.stationName.substring(0, 32);
        }
        if (level instanceof ServerLevel serverLevel) {
            LogisticsSavedData data = LogisticsLevelManager.getData(serverLevel);
            data.stationRegistry().updateName(LogisticsSavedData.toPos3i(worldPosition), this.stationName);
            data.setDirty();
        }
        setStatusKey("status.lc2.name_saved");
        setChanged();
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public void setTargetPos(BlockPos pos) {
        BlockPos next = pos == null ? null : pos.immutable();
        boolean changed = (this.targetPos == null) != (next == null)
                || (this.targetPos != null && !this.targetPos.equals(next));
        this.targetPos = next;
        if (changed) {
            onTargetChanged();
        }
        setStatusKey("status.lc2.target_updated");
        setChanged();
    }

    /** Subclass hook invoked whenever the configured target position changes. */
    protected void onTargetChanged() {
    }

    public boolean isAutoReturn() {
        return autoReturn;
    }

    public void setAutoReturn(boolean autoReturn) {
        this.autoReturn = autoReturn;
        setChanged();
    }

    public EnergyPriority getEnergyPriority() {
        return energyPriority;
    }

    public void setEnergyPriority(EnergyPriority energyPriority) {
        this.energyPriority = energyPriority == null ? EnergyPriority.EU_FIRST : energyPriority;
        setChanged();
    }

    public String getStatusKey() {
        return statusKey;
    }

    public void setStatusKey(String statusKey) {
        this.statusKey = statusKey == null ? "status.lc2.ready" : statusKey;
        setChanged();
    }

    /** Cycles the configured target to the previous or next compatible station in this dimension. */
    public void cycleTarget(ServerLevel level, int delta) {
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        BlockPos3i self = LogisticsSavedData.toPos3i(worldPosition);
        var targets = data.stationRegistry().listCompatibleTargets(self, stationType());
        if (targets.isEmpty()) {
            setTargetPos(null);
            setStatusKey("status.lc2.failure.target_not_found");
            return;
        }
        int currentIndex = -1;
        if (targetPos != null) {
            BlockPos3i cur = LogisticsSavedData.toPos3i(targetPos);
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
        setTargetPos(LogisticsSavedData.toBlockPos(targets.get(nextIndex).pos()));
    }

    // --- Persistence ---

    @Override
    protected void saveStationTag(CompoundTag tag) {
        tag.putInt("layoutVersion", LAYOUT_VERSION);
        tag.putDouble("eu", storedEu);
        tag.putString("name", stationName);
        if (targetPos != null) {
            tag.putBoolean("hasTarget", true);
            tag.putInt("tx", targetPos.getX());
            tag.putInt("ty", targetPos.getY());
            tag.putInt("tz", targetPos.getZ());
        }
        tag.putBoolean("autoReturn", autoReturn);
        tag.putInt("priority", energyPriority.ordinal());
        tag.putString("status", statusKey);
    }

    @Override
    protected void loadStationTag(CompoundTag tag) {
        int layoutVersion = Lc2Nbt.getInt(tag, "layoutVersion", 1);
        if (layoutVersion < LAYOUT_VERSION) {
            migrateLegacyInventory(layoutVersion);
        }
        refreshUpgrades();
        double rawEu = Lc2Nbt.getDouble(tag, "eu", 0.0);
        storedEu = Double.isFinite(rawEu)
                ? Math.max(0.0, Math.min(upgrades.maxCapacityEu(), rawEu))
                : 0.0;
        stationName = Lc2Nbt.getString(tag, "name", "");
        if (Lc2Nbt.getBoolean(tag, "hasTarget", false)) {
            targetPos = new BlockPos(
                    Lc2Nbt.getInt(tag, "tx", 0),
                    Lc2Nbt.getInt(tag, "ty", 0),
                    Lc2Nbt.getInt(tag, "tz", 0)
            );
        } else {
            targetPos = null;
        }
        autoReturn = Lc2Nbt.getBoolean(tag, "autoReturn", false);
        energyPriority = EnergyPriority.fromOrdinal(Lc2Nbt.getInt(tag, "priority", 0));
        statusKey = Lc2Nbt.getString(tag, "status", "status.lc2.ready");
    }

    @Override
    protected void saveClientSyncTag(CompoundTag tag) {
    }

    @Override
    protected void loadClientSyncTag(CompoundTag tag) {
    }

    // --- WorldlyContainer Implementation ---

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onInventorySlotModified(slot);
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            onInventorySlotModified(slot);
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        int limit = Math.min(getMaxStackSize(), getSlotMaxStackSize(slot));
        if (!stack.isEmpty() && stack.getCount() > limit) {
            stack.setCount(limit);
        }
        onInventorySlotModified(slot);
    }

    /** Returns the per-slot maximum stack size (overridden by CargoStation for drone bays / RF chip). */
    protected int getSlotMaxStackSize(int slot) {
        return 64;
    }

    /** Called whenever a slot is modified by players or automation. */
    protected void onInventorySlotModified(int slot) {
        if (slot >= upgradeSlotStart() && slot < upgradeSlotStart() + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            refreshUpgrades();
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        if (level == null || level.getBlockEntity(worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(
                worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5
        ) <= 64.0;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        refreshUpgrades();
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) {
            return false;
        }
        if (isCargoSlot(slot)) {
            return true;
        }
        if (slot == batterySlotIndex()) {
            return Lc2Native.isSingleUseBattery(stack);
        }
        if (slot >= upgradeSlotStart() && slot < upgradeSlotStart() + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            return UpgradeSummary.isSupportedUpgrade(Lc2Native.itemId(stack));
        }
        return false;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        int[] slots = new int[TOTAL_CARGO_SLOTS + 1];
        for (int i = 0; i < TOTAL_CARGO_SLOTS; i++) {
            slots[i] = i;
        }
        slots[TOTAL_CARGO_SLOTS] = batterySlotIndex();
        return slots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        if (isOutboundSlot(slot)) {
            return true;
        }
        if (slot == batterySlotIndex()) {
            return Lc2Native.isSingleUseBattery(stack);
        }
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return isStorageSlot(slot);
    }
}
