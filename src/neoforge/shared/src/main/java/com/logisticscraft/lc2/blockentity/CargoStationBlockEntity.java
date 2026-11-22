package com.logisticscraft.lc2.blockentity;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.world.LevelWaterGrid;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cargo Station block entity ({@code lc2:cargo_station}) managing RF chip range enforcement,
 * 6 physical drone landing bays (with roof rendering sync), atomic target bay reservation,
 * target-side auto-return parking, and EU/battery dispatch payment.
 * <p>
 * Slot layout (matches {@code docs/GUI_LAYOUT.md}):
 * <ul>
 *   <li>{@code 0..17}: 18-slot cargo buffer (2x9)</li>
 *   <li>{@code 18}: RF chip slot ({@code lc2:rf_chip_1/2/3} only)</li>
 *   <li>{@code 19..24}: 6 drone landing bay slots ({@code lc2:transport_drone} / {@code lc2:heavy_transport_drone}, max stack 1)</li>
 *   <li>{@code 25}: single-use battery slot ({@code ic2:single_use_battery} only)</li>
 *   <li>{@code 26..29}: 4 machine upgrade slots</li>
 * </ul>
 */
public final class CargoStationBlockEntity extends AbstractLogisticsStationBlockEntity {

    public static final int CARGO_SLOTS = LogisticsConstants.CARGO_STATION_BUFFER_SLOTS; // 18
    public static final int RF_CHIP_SLOT = 18;
    public static final int DRONE_BAY_START = 19;
    public static final int DRONE_BAY_COUNT = LogisticsConstants.CARGO_STATION_DRONE_BAYS; // 6
    public static final int BATTERY_SLOT = 25;
    public static final int UPGRADE_START = 26;
    public static final int TOTAL_SLOTS = 30;

    /** Bay visual encoding synced to client renderer: 0 = empty, 1 = STANDARD, 2 = HEAVY. */
    public static final byte BAY_EMPTY = 0;
    public static final byte BAY_STANDARD = 1;
    public static final byte BAY_HEAVY = 2;

    private record WaitingReturnDrone(UUID transitId, VehicleKind kind) {
    }

    private final Map<Integer, WaitingReturnDrone> waitingReturnBays = new HashMap<>();
    private final byte[] clientBayKinds = new byte[DRONE_BAY_COUNT];

    public CargoStationBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.CARGO_STATION_BE.get(), pos, state, TOTAL_SLOTS);
    }

    @Override
    public StationType stationType() {
        return StationType.CARGO_STATION;
    }

    @Override
    public int cargoBufferSize() {
        return CARGO_SLOTS;
    }

    @Override
    public int batterySlotIndex() {
        return BATTERY_SLOT;
    }

    @Override
    public int upgradeSlotStart() {
        return UPGRADE_START;
    }

    @Override
    public Component getDisplayName() {
        return stationName.isBlank()
                ? Component.translatable("container.lc2.cargo_station")
                : Component.literal(stationName);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CargoStationMenu(containerId, playerInventory, this);
    }

    /** Returns the installed {@link RfChipTier} in slot {@value #RF_CHIP_SLOT}. */
    public RfChipTier installedRfChip() {
        return RfChipTier.fromItemId(Lc2Native.itemId(items.get(RF_CHIP_SLOT)));
    }

    /** Returns the synced visual drone kind for roof landing pad {@code bay} (0=empty, 1=standard, 2=heavy). */
    public byte getClientBayKind(int bay) {
        if (bay < 0 || bay >= DRONE_BAY_COUNT) {
            return BAY_EMPTY;
        }
        return clientBayKinds[bay];
    }

    /** Returns the count of idle ready drones currently parked in bays {@code 0..5}. */
    public int countReadyDrones() {
        int count = 0;
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            if (droneKindInSlot(bay).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /** Marks a landing bay as visually holding an arrived drone awaiting return energy. */
    public void setWaitingReturnBay(int bay, UUID transitId, VehicleKind kind) {
        if (bay < 0 || bay >= DRONE_BAY_COUNT || transitId == null || kind == null) {
            return;
        }
        WaitingReturnDrone existing = waitingReturnBays.get(bay);
        if (existing != null && existing.transitId().equals(transitId)) {
            return;
        }
        waitingReturnBays.put(bay, new WaitingReturnDrone(transitId, kind));
        syncVisualBaysToClient();
    }

    /** Clears the waiting-return bay record when that drone departs back to its origin. */
    public void clearWaitingReturnBay(UUID transitId) {
        if (transitId == null) {
            return;
        }
        boolean removed = waitingReturnBays.values().removeIf(w -> w.transitId().equals(transitId));
        if (removed) {
            syncVisualBaysToClient();
        }
    }

    /**
     * Parks an arriving (one-way) or returning (auto-return) drone item into {@code preferredBay}
     * or the first empty bay slot {@code 0..5}.
     */
    public boolean parkReturningOrArrivingDrone(int preferredBay, ItemStack droneStack) {
        if (droneStack.isEmpty()) {
            return false;
        }
        if (preferredBay >= 0 && preferredBay < DRONE_BAY_COUNT
                && items.get(DRONE_BAY_START + preferredBay).isEmpty()) {
            items.set(DRONE_BAY_START + preferredBay, droneStack.copyWithCount(1));
            onInventorySlotModified(DRONE_BAY_START + preferredBay);
            return true;
        }
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            if (items.get(DRONE_BAY_START + bay).isEmpty()) {
                items.set(DRONE_BAY_START + bay, droneStack.copyWithCount(1));
                onInventorySlotModified(DRONE_BAY_START + bay);
                return true;
            }
        }
        return false;
    }

    private Optional<VehicleKind> droneKindInSlot(int bay) {
        if (bay < 0 || bay >= DRONE_BAY_COUNT) {
            return Optional.empty();
        }
        ItemStack stack = items.get(DRONE_BAY_START + bay);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return VehicleKind.fromItemId(Lc2Native.itemId(stack)).filter(VehicleKind::isDrone);
    }

    private int computeDockedBayMask() {
        int mask = 0;
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            if (droneKindInSlot(bay).isPresent()) {
                mask |= (1 << bay);
            }
        }
        return mask;
    }

    private void syncDockedMaskToRegistry(ServerLevel level) {
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        BlockPos3i pos = LogisticsSavedData.toPos3i(worldPosition);
        data.stationRegistry().register(pos, StationType.CARGO_STATION, stationName, null);
        data.stationRegistry().setDockedBayMask(pos, computeDockedBayMask());
        data.setDirty();
    }

    private void recomputeVisualBays() {
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            Optional<VehicleKind> slotKind = droneKindInSlot(bay);
            if (slotKind.isPresent()) {
                clientBayKinds[bay] = slotKind.get() == VehicleKind.HEAVY_DRONE ? BAY_HEAVY : BAY_STANDARD;
            } else {
                WaitingReturnDrone waiting = waitingReturnBays.get(bay);
                if (waiting != null) {
                    clientBayKinds[bay] = waiting.kind() == VehicleKind.HEAVY_DRONE ? BAY_HEAVY : BAY_STANDARD;
                } else {
                    clientBayKinds[bay] = BAY_EMPTY;
                }
            }
        }
    }

    private void syncVisualBaysToClient() {
        recomputeVisualBays();
        setChanged();
        if (level instanceof ServerLevel serverLevel && !isRemoved()) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void registerInWorld(ServerLevel level) {
        syncDockedMaskToRegistry(level);
        syncVisualBaysToClient();
    }

    @Override
    protected void onUnregisterFromWorld(ServerLevel level, LogisticsSavedData data) {
        // Safely drop any drones currently parked in WAITING_RETURN_ENERGY on our roof
        List<WaitingReturnDrone> waitingCopy = new ArrayList<>(waitingReturnBays.values());
        waitingReturnBays.clear();
        for (WaitingReturnDrone w : waitingCopy) {
            List<ItemStack> cargo = LogisticsLevelManager.cancelAndTakeCargo(level, w.transitId());
            ItemStack droneItem = Lc2Native.stack(w.kind().itemId(), 1);
            if (!droneItem.isEmpty()) {
                Containers.dropItemStack(
                        level,
                        worldPosition.getX() + 0.5,
                        worldPosition.getY() + 1.0,
                        worldPosition.getZ() + 0.5,
                        droneItem
                );
            }
            for (ItemStack stack : cargo) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(
                            level,
                            worldPosition.getX() + 0.5,
                            worldPosition.getY() + 1.0,
                            worldPosition.getZ() + 0.5,
                            stack
                    );
                }
            }
        }
    }

    @Override
    protected void tickStationLogic(ServerLevel level, boolean redstoneTriggered, boolean redstoneRisingEdge) {
        if (redstoneTriggered && (redstoneRisingEdge || canAttemptAutoDispatch())) {
            VirtualTransitEngine.DispatchResult res = tryDispatch(level, false);
            setDispatchCooldown(res.isSuccess() ? 20 : 40);
        }
    }

    @Override
    public void handleManualDispatch(ServerLevel level) {
        VirtualTransitEngine.DispatchResult res = tryDispatch(level, true);
        setDispatchCooldown(res.isSuccess() ? 10 : 20);
    }

    /**
     * Validates all Cargo Station dispatch conditions (RF chip, Manhattan range, target free bay,
     * ready drone, non-hybrid energy payment) and launches a drone on a stratified VTOL route.
     *
     * @param level  server level
     * @param manual {@code true} if triggered manually via GUI/API (bypasses redstone requirement)
     * @return dispatch outcome
     */
    public VirtualTransitEngine.DispatchResult tryDispatch(ServerLevel level, boolean manual) {
        refreshUpgrades();
        syncDockedMaskToRegistry(level);

        RfChipTier chip = installedRfChip();
        if (!chip.isInstalled()) {
            setStatusKey(DispatchFailure.NO_RF_CHIP.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_RF_CHIP);
        }
        if (targetPos == null) {
            setStatusKey(DispatchFailure.NO_TARGET.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_TARGET);
        }
        BlockPos3i originPos = LogisticsSavedData.toPos3i(worldPosition);
        BlockPos3i target3i = LogisticsSavedData.toPos3i(targetPos);
        if (originPos.equals(target3i)) {
            setStatusKey(DispatchFailure.SAME_STATION.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.SAME_STATION);
        }

        boolean triggerActive = manual || isRedstoneTriggered(level);
        if (!triggerActive) {
            setStatusKey(DispatchFailure.REDSTONE_INACTIVE.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.REDSTONE_INACTIVE);
        }

        // Find first ready drone parked in bays 0..5
        int readyBay = -1;
        VehicleKind droneKind = null;
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            Optional<VehicleKind> kind = droneKindInSlot(bay);
            if (kind.isPresent()) {
                readyBay = bay;
                droneKind = kind.get();
                break;
            }
        }
        if (readyBay < 0 || droneKind == null) {
            setStatusKey(DispatchFailure.NO_VEHICLE.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_VEHICLE);
        }

        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        UUID transitId = UUID.randomUUID();

        VirtualTransitEngine.DroneDispatchRequest req = new VirtualTransitEngine.DroneDispatchRequest(
                transitId,
                droneKind,
                originPos,
                readyBay,
                target3i,
                autoReturn,
                true,
                chip,
                upgrades,
                storedEu,
                availableSingleUseBatteries(),
                energyPriority,
                LevelWaterGrid.heightSampler(level)
        );

        VirtualTransitEngine.DispatchResult result = data.engine().dispatchDrone(req);
        if (!result.isSuccess()) {
            setStatusKey(result.failure().translationKey());
            return result;
        }

        // Commit energy payment
        applyPayment(result.payment());

        // Remove departing drone from its origin bay slot
        items.set(DRONE_BAY_START + readyBay, ItemStack.EMPTY);
        syncDockedMaskToRegistry(level);
        syncVisualBaysToClient();

        // Load outgoing cargo up to drone capacity (12 standard / 36 heavy)
        List<ItemStack> cargo = loadOutgoingCargo(level, droneKind.cargoSlots());
        data.setCargo(transitId, cargo);
        data.setDirty();

        setStatusKey("status.lc2.dispatched");
        return result;
    }

    @Override
    protected boolean shouldDisplayActive(ServerLevel level) {
        return installedRfChip().isInstalled() && (storedEu > 0.0 || availableSingleUseBatteries() > 0 || !waitingReturnBays.isEmpty());
    }

    @Override
    public StationSyncPayload buildSyncPayload(ServerLevel level) {
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        BlockPos3i self = LogisticsSavedData.toPos3i(worldPosition);
        RfChipTier chip = installedRfChip();
        List<StationEntry> targets = data.stationRegistry().listCompatibleTargets(self, StationType.CARGO_STATION);
        List<StationSyncPayload.TargetInfo> infos = new ArrayList<>(targets.size());
        for (StationEntry entry : targets) {
            int dist = self.manhattanDistance(entry.pos());
            boolean inRange = chip.canReach(dist);
            infos.add(new StationSyncPayload.TargetInfo(
                    LogisticsSavedData.toBlockPos(entry.pos()),
                    entry.displayName(),
                    dist,
                    inRange,
                    entry.freeBays()
            ));
        }
        boolean ready = chip.isInstalled() && countReadyDrones() > 0;
        return new StationSyncPayload(
                worldPosition,
                stationName,
                storedEu,
                upgrades.maxCapacityEu(),
                upgrades.inputTier(),
                upgrades.packetLimitEu(),
                autoReturn,
                energyPriority.ordinal(),
                targetPos != null,
                targetPos == null ? BlockPos.ZERO : targetPos,
                statusKey,
                isChunkLoaderActive(),
                ready,
                chip.maxRange(),
                infos
        );
    }

    @Override
    protected int getSlotMaxStackSize(int slot) {
        if (slot == RF_CHIP_SLOT || (slot >= DRONE_BAY_START && slot < DRONE_BAY_START + DRONE_BAY_COUNT)) {
            return 1;
        }
        return super.getSlotMaxStackSize(slot);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == RF_CHIP_SLOT) {
            return RfChipTier.fromItemId(Lc2Native.itemId(stack)).isInstalled();
        }
        if (slot >= DRONE_BAY_START && slot < DRONE_BAY_START + DRONE_BAY_COUNT) {
            int bay = slot - DRONE_BAY_START;
            if (waitingReturnBays.containsKey(bay)) {
                return false;
            }
            return VehicleKind.fromItemId(Lc2Native.itemId(stack)).map(VehicleKind::isDrone).orElse(false);
        }
        return super.canPlaceItem(slot, stack);
    }

    @Override
    protected void onInventorySlotModified(int slot) {
        super.onInventorySlotModified(slot);
        if (slot >= DRONE_BAY_START && slot < DRONE_BAY_START + DRONE_BAY_COUNT) {
            if (level instanceof ServerLevel serverLevel) {
                syncDockedMaskToRegistry(serverLevel);
            }
            syncVisualBaysToClient();
        }
    }

    @Override
    protected void saveClientSyncTag(CompoundTag tag) {
        recomputeVisualBays();
        for (int i = 0; i < DRONE_BAY_COUNT; i++) {
            tag.putInt("bay" + i, clientBayKinds[i]);
        }
    }

    @Override
    protected void loadClientSyncTag(CompoundTag tag) {
        for (int i = 0; i < DRONE_BAY_COUNT; i++) {
            clientBayKinds[i] = (byte) Lc2Nbt.getInt(tag, "bay" + i, BAY_EMPTY);
        }
    }
}
