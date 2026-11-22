package com.logisticscraft.lc2.blockentity;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.network.StationSyncPayload;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LevelWaterGrid;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Smart Port block entity ({@code lc2:smart_port}) managing water berth detection,
 * Electric Smart Cargo Boat docking, water connectivity verification, EU/battery dispatch
 * payment, and autonomous maritime launches.
 * <p>
 * 2-zone slot layout (matches {@code docs/GUI_LAYOUT.md}):
 * <ul>
 *   <li>{@code 0..26}: 27-slot Outbound Area (3x9, left)</li>
 *   <li>{@code 27..53}: 27-slot Storage Area (3x9, right)</li>
 *   <li>{@code 54}: single-use battery slot ({@code ic2:single_use_battery} only)</li>
 *   <li>{@code 55..58}: 4 machine upgrade slots</li>
 * </ul>
 */
public final class SmartPortBlockEntity extends AbstractLogisticsStationBlockEntity {

    public static final int BATTERY_SLOT = TOTAL_CARGO_SLOTS; // 54
    public static final int UPGRADE_START = BATTERY_SLOT + 1; // 55
    public static final int TOTAL_SLOTS = LogisticsConstants.SMART_PORT_MACHINE_SLOTS; // 59
    public static final double BOAT_DOCK_RADIUS = 3.5;

    private static final int LEGACY_V1_CARGO_SLOTS = 27;
    private static final int LEGACY_V1_BATTERY_SLOT = 27;
    private static final int LEGACY_V1_UPGRADE_START = 28;
    private static final int LEGACY_V1_TOTAL_SLOTS = 32;

    private BlockPos3i waterBerthPos = null;
    private int berthRefreshTimer = 0;
    private boolean hadDockedBoat = false;

    public SmartPortBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.SMART_PORT_BE.get(), pos, state, TOTAL_SLOTS);
    }

    @Override
    public StationType stationType() {
        return StationType.SMART_PORT;
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
    protected void migrateLegacyInventory(int fromVersion) {
        if (fromVersion >= LAYOUT_VERSION) {
            return;
        }
        ItemStack[] snapshot = new ItemStack[LEGACY_V1_TOTAL_SLOTS];
        for (int i = 0; i < LEGACY_V1_TOTAL_SLOTS; i++) {
            snapshot[i] = items.get(i);
        }
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < LEGACY_V1_CARGO_SLOTS; i++) {
            items.set(OUTBOUND_START + i, snapshot[i]);
        }
        items.set(BATTERY_SLOT, snapshot[LEGACY_V1_BATTERY_SLOT]);
        for (int i = 0; i < LogisticsConstants.MACHINE_UPGRADE_SLOTS; i++) {
            items.set(UPGRADE_START + i, snapshot[LEGACY_V1_UPGRADE_START + i]);
        }
    }

    @Override
    public Component getDisplayName() {
        return stationName.isBlank()
                ? Component.translatable("container.lc2.smart_port")
                : Component.literal(stationName);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new SmartPortMenu(containerId, playerInventory, this);
    }

    /** Returns the currently detected adjacent water berth block, or {@code null} if none. */
    public BlockPos3i getWaterBerthPos() {
        return waterBerthPos;
    }

    /** Re-detects the adjacent navigable water berth (front facing side first, then other horizontals). */
    public Optional<BlockPos3i> refreshWaterBerth(ServerLevel level) {
        Direction facing = getBlockState().hasProperty(SmartPortBlock.FACING)
                ? getBlockState().getValue(SmartPortBlock.FACING)
                : Direction.NORTH;
        BlockPos3i portPos = LogisticsSavedData.toPos3i(worldPosition);
        Optional<BlockPos3i> detected = WaterPathfinder.findWaterBerth(
                LevelWaterGrid.loadedOnly(level),
                portPos,
                facing.getStepX(),
                facing.getStepZ()
        );
        BlockPos3i nextBerth = detected.orElse(null);
        boolean changed = (waterBerthPos == null) != (nextBerth == null)
                || (waterBerthPos != null && !waterBerthPos.equals(nextBerth));
        if (changed) {
            LogisticsSavedData data = LogisticsLevelManager.getData(level);
            if (waterBerthPos != null) {
                data.invalidateWaterRoutesFor(waterBerthPos);
            }
            waterBerthPos = nextBerth;
            data.stationRegistry().register(portPos, StationType.SMART_PORT, stationName, waterBerthPos);
            data.setDirty();
        }
        return detected;
    }

    @Override
    protected void registerInWorld(ServerLevel level) {
        refreshWaterBerth(level);
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        data.stationRegistry().register(
                LogisticsSavedData.toPos3i(worldPosition),
                StationType.SMART_PORT,
                stationName,
                waterBerthPos
        );
        data.setDirty();
    }

    @Override
    protected void onUnregisterFromWorld(ServerLevel level, LogisticsSavedData data) {
        if (waterBerthPos != null) {
            data.invalidateWaterRoutesFor(waterBerthPos);
        }
    }

    /**
     * Finds the closest idle (non-autonomous) {@link ElectricCargoBoatEntity} docked within
     * {@value #BOAT_DOCK_RADIUS} blocks of this port's detected water berth.
     */
    public Optional<ElectricCargoBoatEntity> findDockedBoat(ServerLevel level) {
        if (waterBerthPos == null) {
            refreshWaterBerth(level);
        }
        if (waterBerthPos == null) {
            return Optional.empty();
        }
        Vec3d berthCenter = waterBerthPos.waterSurfaceCenter();
        AABB box = new AABB(
                berthCenter.x() - BOAT_DOCK_RADIUS, berthCenter.y() - 2.0, berthCenter.z() - BOAT_DOCK_RADIUS,
                berthCenter.x() + BOAT_DOCK_RADIUS, berthCenter.y() + 2.0, berthCenter.z() + BOAT_DOCK_RADIUS
        );
        List<ElectricCargoBoatEntity> boats = level.getEntitiesOfClass(
                ElectricCargoBoatEntity.class,
                box,
                boat -> !boat.isRemoved() && !boat.isAutonomousTransit()
        );
        return boats.stream().min(
                Comparator.comparingInt((ElectricCargoBoatEntity b) -> b.getPassengers().isEmpty() ? 1 : 0)
                        .thenComparingDouble(
                                b -> b.distanceToSqr(berthCenter.x(), berthCenter.y(), berthCenter.z())
                        )
        );
    }

    /**
     * Spawns and docks an {@link ElectricCargoBoatEntity} at this port's water berth when the
     * player right-clicks the Smart Port with the boat item.
     */
    public boolean spawnAndDockBoat(ServerLevel level, Player player, ItemStack stack) {
        Optional<BlockPos3i> berthOpt = refreshWaterBerth(level);
        if (berthOpt.isEmpty()) {
            setStatusKey(DispatchFailure.NO_WATER_BERTH.translationKey());
            Lc2Native.sendActionBar(player, Component.translatable(DispatchFailure.NO_WATER_BERTH.translationKey()));
            return false;
        }
        if (findDockedBoat(level).isPresent()) {
            Lc2Native.sendActionBar(player, Component.translatable("status.lc2.boat_docked"));
            return false;
        }
        Vec3d spawnPos = berthOpt.get().waterSurfaceCenter();
        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                level
        );
        Direction facing = getBlockState().hasProperty(SmartPortBlock.FACING)
                ? getBlockState().getValue(SmartPortBlock.FACING)
                : Direction.NORTH;
        boat.setPos(spawnPos.x(), spawnPos.y(), spawnPos.z());
        boat.setYRot(facing.toYRot());
        if (level.addFreshEntity(boat)) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            resetDispatchCooldown();
            setStatusKey("status.lc2.boat_docked");
            Lc2Native.sendActionBar(player, Component.translatable("status.lc2.boat_docked"));
            return true;
        }
        return false;
    }

    @Override
    protected void tickStationLogic(ServerLevel level, boolean redstoneTriggered, boolean redstoneRisingEdge) {
        if (++berthRefreshTimer >= 40) {
            berthRefreshTimer = 0;
            refreshWaterBerth(level);
        }
        boolean hasDockedBoatNow = findDockedBoat(level).isPresent();
        if (!hadDockedBoat && hasDockedBoatNow) {
            resetDispatchCooldown();
        }
        if (redstoneTriggered && (redstoneRisingEdge || canAttemptAutoDispatch())) {
            VirtualTransitEngine.DispatchResult res = tryDispatch(level, false);
            setDispatchCooldown(res.isSuccess() ? 20 : 40);
            if (res.isSuccess()) {
                hasDockedBoatNow = findDockedBoat(level).isPresent();
            }
        }
        hadDockedBoat = hasDockedBoatNow;
    }

    @Override
    public void handleManualDispatch(ServerLevel level) {
        VirtualTransitEngine.DispatchResult res = tryDispatch(level, true);
        setDispatchCooldown(res.isSuccess() ? 10 : 20);
    }

    /**
     * Validates all dispatch conditions and launches the docked Electric Smart Cargo Boat
     * toward the configured target Smart Port.
     *
     * @param level  server level
     * @param manual {@code true} if triggered manually via GUI/API (bypasses redstone requirement)
     * @return dispatch outcome
     */
    public VirtualTransitEngine.DispatchResult tryDispatch(ServerLevel level, boolean manual) {
        refreshUpgrades();
        refreshWaterBerth(level);

        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        BlockPos3i originPos = LogisticsSavedData.toPos3i(worldPosition);
        data.stationRegistry().register(originPos, StationType.SMART_PORT, stationName, waterBerthPos);

        if (targetPos == null) {
            setStatusKey(DispatchFailure.NO_TARGET.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_TARGET);
        }
        BlockPos3i target3i = LogisticsSavedData.toPos3i(targetPos);
        if (originPos.equals(target3i)) {
            setStatusKey(DispatchFailure.SAME_STATION.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.SAME_STATION);
        }
        Optional<StationEntry> targetEntry = data.stationRegistry().get(target3i);
        if (targetEntry.isEmpty() || targetEntry.get().type() != StationType.SMART_PORT) {
            setStatusKey(DispatchFailure.TARGET_NOT_FOUND.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.TARGET_NOT_FOUND);
        }
        boolean triggerActive = manual || isRedstoneTriggered(level);
        if (!triggerActive) {
            setStatusKey(DispatchFailure.REDSTONE_INACTIVE.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.REDSTONE_INACTIVE);
        }
        Optional<ElectricCargoBoatEntity> boatOpt = findDockedBoat(level);
        if (boatOpt.isEmpty()) {
            setStatusKey(DispatchFailure.NO_VEHICLE.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_VEHICLE);
        }
        BlockPos3i targetBerth = targetEntry.get().waterBerthPos();
        if (waterBerthPos == null || targetBerth == null) {
            setStatusKey(DispatchFailure.NO_WATER_BERTH.translationKey());
            return VirtualTransitEngine.DispatchResult.fail(DispatchFailure.NO_WATER_BERTH);
        }

        // Reuse cached verified water route if loaded segments are still clear; otherwise re-probe via A*
        List<Vec3d> waypoints = data.getCachedWaterRoute(waterBerthPos, targetBerth);
        if (waypoints != null && !waypoints.isEmpty() && !isCachedRouteStillClear(level, waypoints)) {
            data.invalidateWaterRoute(waterBerthPos, targetBerth);
            waypoints = null;
        }
        if (waypoints == null || waypoints.isEmpty()) {
            LevelWaterGrid probingGrid = LevelWaterGrid.probing(level);
            WaterPathResult pathResult = WaterPathfinder.findPath(probingGrid, waterBerthPos, targetBerth);
            if (!pathResult.connected()) {
                setStatusKey(pathResult.failure().translationKey());
                return VirtualTransitEngine.DispatchResult.fail(pathResult.failure());
            }
            waypoints = pathResult.waypoints();
            data.cacheWaterRoute(waterBerthPos, targetBerth, waypoints);
        }

        ElectricCargoBoatEntity boat = boatOpt.get();
        boolean hasPrelaunchRider = !boat.getPassengers().isEmpty();
        UUID transitId = UUID.randomUUID();

        VirtualTransitEngine.BoatDispatchRequest req = new VirtualTransitEngine.BoatDispatchRequest(
                transitId,
                originPos,
                target3i,
                autoReturn,
                true,
                true,
                hasPrelaunchRider,
                upgrades,
                storedEu,
                availableSingleUseBatteries(),
                energyPriority,
                null,
                waypoints
        );

        VirtualTransitEngine.DispatchResult result = data.engine().dispatchBoat(req);
        if (!result.isSuccess()) {
            setStatusKey(result.failure().translationKey());
            return result;
        }

        // Commit energy payment
        applyPayment(result.payment());

        // Load cargo: keep existing items in boat's 54-slot hold + pull remaining capacity from buffer & adjacent containers
        List<ItemStack> boatExisting = boat.drainAllCargo();
        int remainingSlots = Math.max(0, LogisticsConstants.BOAT_CARGO_SLOTS - boatExisting.size());
        List<ItemStack> loadedFromStation = remainingSlots > 0
                ? loadOutgoingCargo(level, remainingSlots)
                : List.of();
        List<ItemStack> totalCargo = new ArrayList<>(boatExisting.size() + loadedFromStation.size());
        totalCargo.addAll(boatExisting);
        totalCargo.addAll(loadedFromStation);

        data.setCargo(transitId, totalCargo);
        boat.setAutonomousTransit(true, transitId);
        LogisticsLevelManager.bindLiveEntity(level, transitId, boat);
        data.setDirty();

        setStatusKey("status.lc2.dispatched");
        return result;
    }

    private static boolean isCachedRouteStillClear(ServerLevel level, List<Vec3d> waypoints) {
        LevelWaterGrid loadedGrid = LevelWaterGrid.loadedOnly(level);
        for (int i = 0; i < waypoints.size(); i++) {
            Vec3d a = waypoints.get(i);
            if (level.hasChunk(a.chunkX(), a.chunkZ()) && !loadedGrid.isBoatPassable(a.toBlockPos())) {
                return false;
            }
            if (i + 1 < waypoints.size()) {
                Vec3d b = waypoints.get(i + 1);
                double dist = a.horizontalDistanceTo(b);
                int steps = Math.max(1, (int) Math.ceil(dist));
                for (int s = 1; s < steps; s++) {
                    Vec3d p = a.lerp(b, (double) s / steps);
                    if (level.hasChunk(p.chunkX(), p.chunkZ()) && !loadedGrid.isBoatPassable(p.toBlockPos())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    protected boolean shouldDisplayActive(ServerLevel level) {
        return storedEu > 0.0 || isChunkLoaderActive() || findDockedBoat(level).isPresent();
    }

    @Override
    public StationSyncPayload buildSyncPayload(ServerLevel level) {
        LogisticsSavedData data = LogisticsLevelManager.getData(level);
        BlockPos3i self = LogisticsSavedData.toPos3i(worldPosition);
        List<StationEntry> targets = data.stationRegistry().listCompatibleTargets(self, StationType.SMART_PORT);
        List<StationSyncPayload.TargetInfo> infos = new ArrayList<>(targets.size());
        for (StationEntry entry : targets) {
            int dist = self.manhattanDistance(entry.pos());
            boolean waterOk = waterBerthPos != null
                    && entry.waterBerthPos() != null
                    && data.getCachedWaterRoute(waterBerthPos, entry.waterBerthPos()) != null;
            infos.add(new StationSyncPayload.TargetInfo(
                    LogisticsSavedData.toBlockPos(entry.pos()),
                    entry.displayName(),
                    dist,
                    waterOk,
                    0
            ));
        }
        boolean boatDocked = findDockedBoat(level).isPresent();
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
                boatDocked,
                boatDocked ? 1 : 0,
                0,
                infos
        );
    }
}
