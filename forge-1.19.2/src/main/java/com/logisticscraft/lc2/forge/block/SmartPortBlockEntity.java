package com.logisticscraft.lc2.forge.block;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationEntry;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.world.LevelWaterGrid;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Block entity for {@link SmartPortBlock} ({@code lc2:smart_port}).
 * <p>
 * 2-Zone slot layout matching {@code docs/GUI_LAYOUT.md}:
 * <ul>
 *   <li>{@code 0..26}: 27-slot Outbound Area (pending shipment)</li>
 *   <li>{@code 27..53}: 27-slot Storage Area (received cargo)</li>
 *   <li>{@code 54}: dedicated {@code ic2:single_use_battery} slot</li>
 *   <li>{@code 55..58}: 4 machine upgrade slots</li>
 * </ul>
 */
public class SmartPortBlockEntity extends AbstractLogisticsStationBlockEntity {

    public static final int SLOT_OUTBOUND_START = LogisticsConstants.STATION_OUTBOUND_START; // 0
    public static final int SLOT_OUTBOUND_COUNT = LogisticsConstants.STATION_OUTBOUND_SLOTS; // 27
    public static final int SLOT_STORAGE_START = LogisticsConstants.STATION_STORAGE_START; // 27
    public static final int SLOT_STORAGE_COUNT = LogisticsConstants.STATION_STORAGE_SLOTS; // 27
    public static final int SLOT_CARGO_START = 0;
    public static final int SLOT_CARGO_COUNT = LogisticsConstants.STATION_TOTAL_CARGO_SLOTS; // 54
    public static final int SLOT_BATTERY = 54;
    public static final int SLOT_UPGRADE_START = 55;
    public static final int TOTAL_SLOTS = LogisticsConstants.SMART_PORT_MACHINE_SLOTS; // 59

    private static final int LEGACY_TOTAL_SLOTS = 32;
    private static final int LEGACY_CARGO_COUNT = 27;
    private static final int LEGACY_SLOT_BATTERY = 27;
    private static final int LEGACY_SLOT_UPGRADE_START = 28;

    private static final double BOAT_DOCK_RADIUS = 3.5;

    @Nullable
    private BlockPos3i waterBerthPos = null;
    private boolean lastWaterConnected = false;
    private boolean hadDockedBoat = false;

    public SmartPortBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.SMART_PORT_BE.get(), pos, state, TOTAL_SLOTS);
    }

    @Override
    public StationType stationType() {
        return StationType.SMART_PORT;
    }

    @Override
    public int cargoSlotCount() {
        return SLOT_CARGO_COUNT;
    }

    @Override
    protected int legacyTotalSlots() {
        return LEGACY_TOTAL_SLOTS;
    }

    @Override
    protected int remapLegacySlot(int legacySlot) {
        if (legacySlot >= 0 && legacySlot < LEGACY_CARGO_COUNT) {
            return SLOT_OUTBOUND_START + legacySlot;
        }
        if (legacySlot == LEGACY_SLOT_BATTERY) {
            return SLOT_BATTERY;
        }
        if (legacySlot >= LEGACY_SLOT_UPGRADE_START
                && legacySlot < LEGACY_SLOT_UPGRADE_START + LogisticsConstants.MACHINE_UPGRADE_SLOTS) {
            return SLOT_UPGRADE_START + (legacySlot - LEGACY_SLOT_UPGRADE_START);
        }
        return -1;
    }

    @Override
    public int batterySlotIndex() {
        return SLOT_BATTERY;
    }

    @Override
    public int upgradeStartIndex() {
        return SLOT_UPGRADE_START;
    }

    @Override
    protected BooleanProperty activeProperty() {
        return SmartPortBlock.ACTIVE;
    }

    /** Returns the currently detected adjacent water berth block, or {@code null} if none. */
    @Nullable
    public BlockPos3i getWaterBerthPos() {
        return this.waterBerthPos;
    }

    /**
     * Re-scans adjacent horizontal water blocks (front face first, then other horizontal sides,
     * checking Y and Y-1) via {@link WaterPathfinder#findWaterBerth}.
     */
    @Nullable
    public BlockPos3i refreshWaterBerth(ServerLevel level) {
        Direction facing = getBlockState().hasProperty(SmartPortBlock.FACING)
                ? getBlockState().getValue(SmartPortBlock.FACING)
                : Direction.NORTH;
        LevelWaterGrid grid = new LevelWaterGrid(level, false, 0);
        Optional<BlockPos3i> detected = WaterPathfinder.findWaterBerth(
                grid,
                Lc2Coords.toCore(this.worldPosition),
                facing.getStepX(),
                facing.getStepZ()
        );
        BlockPos3i next = detected.orElse(null);
        if ((this.waterBerthPos == null && next != null)
                || (this.waterBerthPos != null && !this.waterBerthPos.equals(next))) {
            this.waterBerthPos = next;
            setChanged();
        }
        return this.waterBerthPos;
    }

    /**
     * Spawns and docks an {@link ElectricCargoBoatEntity} directly at this Smart Port's water berth
     * when a player right-clicks the Smart Port holding {@code lc2:electric_cargo_boat}.
     */
    public boolean spawnAndDockBoat(ServerLevel level, Player player, ItemStack boatStack) {
        BlockPos3i berth = refreshWaterBerth(level);
        syncRegistry(level);
        if (berth == null) {
            player.displayClientMessage(
                    Component.translatable(DispatchFailure.NO_WATER_BERTH.translationKey()),
                    true
            );
            setStatusKey(DispatchFailure.NO_WATER_BERTH.translationKey());
            return false;
        }

        Vec3d surface = berth.waterSurfaceCenter();
        Direction facing = getBlockState().hasProperty(SmartPortBlock.FACING)
                ? getBlockState().getValue(SmartPortBlock.FACING)
                : Direction.NORTH;

        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                level
        );
        boat.setPos(surface.x(), surface.y(), surface.z());
        float yaw = facing.toYRot();
        boat.setYRot(yaw);
        boat.yRotO = yaw;

        if (!level.addFreshEntity(boat)) {
            return false;
        }
        if (!player.getAbilities().instabuild) {
            boatStack.shrink(1);
        }
        player.displayClientMessage(Component.translatable("status.lc2.boat_docked"), true);
        setStatusKey("status.lc2.boat_docked");
        this.dispatchCooldown = 0;
        return true;
    }

    /**
     * Finds the closest idle {@link ElectricCargoBoatEntity} docked within 3.5 blocks of this
     * Smart Port's water berth.
     */
    @Nullable
    public ElectricCargoBoatEntity findDockedBoat(ServerLevel level) {
        BlockPos3i berth = this.waterBerthPos != null ? this.waterBerthPos : refreshWaterBerth(level);
        if (berth == null) {
            return null;
        }
        Vec3 berthCenter = Lc2Coords.toMc(berth.waterSurfaceCenter());
        AABB search = AABB.ofSize(berthCenter, BOAT_DOCK_RADIUS * 2.0, 3.0, BOAT_DOCK_RADIUS * 2.0);
        List<ElectricCargoBoatEntity> candidates = level.getEntitiesOfClass(
                ElectricCargoBoatEntity.class,
                search,
                boat -> !boat.isRemoved()
                        && !boat.isAutonomousTransit()
                        && boat.getTransitId() == null
                        && boat.position().distanceToSqr(berthCenter) <= BOAT_DOCK_RADIUS * BOAT_DOCK_RADIUS + 1.0
        );
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator
                .<ElectricCargoBoatEntity>comparingInt(b -> b.getPassengers().isEmpty() ? 1 : 0)
                .thenComparingDouble(b -> b.position().distanceToSqr(berthCenter)));
        return candidates.get(0);
    }

    /** Called by {@link LogisticsLevelManager} when a boat finishes docking at this Smart Port. */
    public void onBoatDocked() {
        this.hadDockedBoat = true;
        this.dispatchCooldown = 25;
        setStatusKey("status.lc2.docked");
    }

    @Override
    public void syncRegistry(ServerLevel level) {
        if (this.waterBerthPos == null && level.hasChunkAt(this.worldPosition)) {
            refreshWaterBerth(level);
        }
        LogisticsLevelManager.stations(level).register(
                Lc2Coords.toCore(this.worldPosition),
                StationType.SMART_PORT,
                this.stationName,
                this.waterBerthPos
        );
        LogisticsLevelManager.data(level).setDirty();
    }

    @Override
    protected void tickStation(ServerLevel level, long gameTime, boolean redstoneTriggered) {
        if (gameTime % 20 == 0) {
            BlockPos3i prev = this.waterBerthPos;
            BlockPos3i next = refreshWaterBerth(level);
            if ((prev == null) != (next == null) || (prev != null && !prev.equals(next))) {
                syncRegistry(level);
            }
        }

        boolean hasDockedBoatNow = findDockedBoat(level) != null;
        if (!this.hadDockedBoat && hasDockedBoatNow) {
            this.dispatchCooldown = 0;
        }
        this.hadDockedBoat = hasDockedBoatNow;

        boolean active = isChunkLoaderActive() || hasActiveTransitFromOrTo(level);
        setActiveBlockState(level, active);

        if (redstoneTriggered && this.dispatchCooldown <= 0) {
            if (!tryDispatch(level, false)) {
                this.dispatchCooldown = 20;
            } else {
                this.hadDockedBoat = findDockedBoat(level) != null;
            }
        }
    }

    @Override
    public boolean tryDispatch(ServerLevel level, boolean manualTrigger) {
        refreshUpgradesIfNeeded();
        BlockPos3i originBerth = refreshWaterBerth(level);
        syncRegistry(level);

        boolean triggered = manualTrigger
                || this.upgrades.isRedstoneTriggered(level.hasNeighborSignal(this.worldPosition));
        if (!triggered) {
            setStatusKey(DispatchFailure.REDSTONE_INACTIVE.translationKey());
            return false;
        }
        if (this.targetPos == null) {
            setStatusKey(DispatchFailure.NO_TARGET.translationKey());
            return false;
        }
        BlockPos3i originCore = Lc2Coords.toCore(this.worldPosition);
        BlockPos3i targetCore = Lc2Coords.toCore(this.targetPos);
        if (originCore.equals(targetCore)) {
            setStatusKey(DispatchFailure.SAME_STATION.translationKey());
            return false;
        }

        Optional<StationEntry> targetEntryOpt = LogisticsLevelManager.stations(level).get(targetCore);
        if (targetEntryOpt.isEmpty() || targetEntryOpt.get().type() != StationType.SMART_PORT) {
            setStatusKey(DispatchFailure.TARGET_NOT_FOUND.translationKey());
            return false;
        }

        ElectricCargoBoatEntity boat = findDockedBoat(level);
        if (boat == null) {
            setStatusKey(DispatchFailure.NO_VEHICLE.translationKey());
            return false;
        }

        BlockPos3i targetBerth = targetEntryOpt.get().waterBerthPos();
        if (targetBerth == null && level.isLoaded(this.targetPos)
                && level.getBlockEntity(this.targetPos) instanceof SmartPortBlockEntity targetPort) {
            targetBerth = targetPort.refreshWaterBerth(level);
            targetPort.syncRegistry(level);
        }
        if (originBerth == null || targetBerth == null) {
            this.lastWaterConnected = false;
            setStatusKey(DispatchFailure.NO_WATER_BERTH.translationKey());
            return false;
        }

        WaterPathResult route = LogisticsLevelManager.resolveWaterRoute(level, originBerth, targetBerth);
        this.lastWaterConnected = route.connected();
        if (!route.connected()) {
            setStatusKey(route.failure() != null
                    ? route.failure().translationKey()
                    : DispatchFailure.WATER_DISCONNECTED.translationKey());
            return false;
        }

        boolean hasPrelaunchRider = !boat.getPassengers().isEmpty();
        VirtualTransitEngine.BoatDispatchRequest request = new VirtualTransitEngine.BoatDispatchRequest(
                null,
                originCore,
                targetCore,
                this.autoReturn,
                true,
                true,
                hasPrelaunchRider,
                this.upgrades,
                this.storedEu,
                getBatteryCount(),
                this.energyPriority,
                null,
                route.waypoints()
        );

        VirtualTransitEngine.DispatchResult result = LogisticsLevelManager.engine(level).dispatchBoat(request);
        if (!result.isSuccess()) {
            setStatusKey(result.failure().translationKey());
            return false;
        }

        // Debit energy, load outgoing cargo into the boat's 54-slot hold, and bind boat to transit
        debitPayment(result.payment());
        loadOutgoingCargoInto(level, boat.getCargoContainer(), LogisticsConstants.BOAT_CARGO_SLOTS);
        List<ItemStack> loadedCargo = boat.snapshotCargoList();
        LogisticsLevelManager.bindDispatchedBoat(level, result.transit(), boat, loadedCargo);

        this.dispatchCooldown = 20;
        setStatusKey("status.lc2.dispatched");
        setActiveBlockState(level, true);
        setChanged();
        return true;
    }

    private boolean hasActiveTransitFromOrTo(ServerLevel level) {
        BlockPos3i core = Lc2Coords.toCore(this.worldPosition);
        for (TransitRecord rec : LogisticsLevelManager.activeTransits(level)) {
            if (rec.vehicleKind().isBoat()
                    && (rec.originPos().equals(core) || rec.targetPos().equals(core))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public S2CStationSyncPacket buildSyncPacket(ServerLevel level) {
        refreshUpgradesIfNeeded();
        BlockPos3i berth = refreshWaterBerth(level);
        boolean boatReady = findDockedBoat(level) != null;

        boolean waterConnected = this.lastWaterConnected;
        if (berth != null && this.targetPos != null) {
            Optional<StationEntry> target = LogisticsLevelManager.stations(level).get(Lc2Coords.toCore(this.targetPos));
            if (target.isPresent() && target.get().waterBerthPos() != null) {
                waterConnected = LogisticsLevelManager.data(level)
                        .getCachedWaterRoute(berth, target.get().waterBerthPos()) != null || this.lastWaterConnected;
            } else {
                waterConnected = false;
            }
        }

        return new S2CStationSyncPacket(
                this.worldPosition,
                this.stationName,
                this.targetPos != null,
                this.targetPos == null ? BlockPos.ZERO : this.targetPos,
                this.autoReturn,
                this.energyPriority,
                this.storedEu,
                maxCapacityEu(),
                this.upgrades.inputTier(),
                this.upgrades.packetLimitEu(),
                isChunkLoaderActive(),
                boatReady,
                berth != null,
                waterConnected,
                0,
                this.statusKey,
                buildTargetSummaries(level)
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.waterBerthPos != null) {
            tag.putBoolean("HasBerth", true);
            tag.putInt("BerthX", this.waterBerthPos.x());
            tag.putInt("BerthY", this.waterBerthPos.y());
            tag.putInt("BerthZ", this.waterBerthPos.z());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.getBoolean("HasBerth")) {
            this.waterBerthPos = new BlockPos3i(
                    tag.getInt("BerthX"),
                    tag.getInt("BerthY"),
                    tag.getInt("BerthZ")
            );
        } else {
            this.waterBerthPos = null;
        }
    }

    @Override
    public Component getDisplayName() {
        return this.stationName.isBlank()
                ? Component.translatable("container.lc2.smart_port")
                : Component.literal(this.stationName);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new SmartPortMenu(containerId, playerInventory, this);
    }
}
