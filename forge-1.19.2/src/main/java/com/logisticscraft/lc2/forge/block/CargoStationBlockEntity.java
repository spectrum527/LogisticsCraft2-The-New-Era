package com.logisticscraft.lc2.forge.block;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.RfChipTier;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.core.VirtualTransitEngine;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.gui.CargoStationMenu;
import com.logisticscraft.lc2.forge.network.S2CStationSyncPacket;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Block entity for {@link CargoStationBlock} ({@code lc2:cargo_station}).
 * <p>
 * Slot layout matching {@code docs/GUI_LAYOUT.md}:
 * <ul>
 *   <li>{@code 0..17}: 18-slot internal cargo buffer</li>
 *   <li>{@code 18}: RF chip slot ({@code lc2:rf_chip_1/2/3})</li>
 *   <li>{@code 19..24}: 6 drone bay slots ({@code lc2:transport_drone} / {@code lc2:heavy_transport_drone}, max stack 1)</li>
 *   <li>{@code 25}: dedicated {@code ic2:single_use_battery} slot</li>
 *   <li>{@code 26..29}: 4 machine upgrade slots</li>
 * </ul>
 */
public class CargoStationBlockEntity extends AbstractLogisticsStationBlockEntity {

    public static final int SLOT_CARGO_START = 0;
    public static final int SLOT_CARGO_COUNT = LogisticsConstants.CARGO_STATION_BUFFER_SLOTS; // 18
    public static final int SLOT_RF_CHIP = 18;
    public static final int SLOT_DRONE_BAY_START = 19;
    public static final int DRONE_BAY_COUNT = LogisticsConstants.CARGO_STATION_DRONE_BAYS; // 6
    public static final int SLOT_BATTERY = 25;
    public static final int SLOT_UPGRADE_START = 26;
    public static final int TOTAL_SLOTS = 30;

    /** Client-synced drone visual state per bay (0=empty, 1=standard, 2=heavy) for {@code CargoStationRenderer}. */
    private final byte[] clientBayRenderKinds = new byte[DRONE_BAY_COUNT];

    public CargoStationBlockEntity(BlockPos pos, BlockState state) {
        super(Lc2Registries.CARGO_STATION_BE.get(), pos, state, TOTAL_SLOTS);
    }

    @Override
    public StationType stationType() {
        return StationType.CARGO_STATION;
    }

    @Override
    public int cargoSlotCount() {
        return SLOT_CARGO_COUNT;
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
        return CargoStationBlock.ACTIVE;
    }

    /** Returns the installed {@link RfChipTier} in slot 18, or {@link RfChipTier#NONE}. */
    public RfChipTier getRfChipTier() {
        return RfChipTier.fromItemId(Lc2Coords.itemId(this.items.get(SLOT_RF_CHIP)));
    }

    /**
     * Returns the {@link VehicleKind} parked in {@code bayIndex} ({@code 0..5}),
     * or {@code null} if that bay is empty. Works on both client (via synced bay array) and server.
     */
    @Nullable
    public VehicleKind getBayDroneKind(int bayIndex) {
        if (bayIndex < 0 || bayIndex >= DRONE_BAY_COUNT) {
            return null;
        }
        if (this.level != null && this.level.isClientSide) {
            return switch (this.clientBayRenderKinds[bayIndex]) {
                case 1 -> VehicleKind.STANDARD_DRONE;
                case 2 -> VehicleKind.HEAVY_DRONE;
                default -> null;
            };
        }
        return VehicleKind.fromItemId(Lc2Coords.itemId(this.items.get(SLOT_DRONE_BAY_START + bayIndex)))
                .filter(VehicleKind::isDrone)
                .orElse(null);
    }

    /** Computes the 6-bit mask of physically docked drones across bays {@code 0..5}. */
    public int getDockedBayMask() {
        int mask = 0;
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            ItemStack stack = this.items.get(SLOT_DRONE_BAY_START + bay);
            if (!stack.isEmpty() && isDroneItem(stack)) {
                mask |= (1 << bay);
            }
        }
        return mask;
    }

    /** Finds the first bay index ({@code 0..5}) containing a ready drone, or {@code -1} if none. */
    public int findReadyDroneBay() {
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            ItemStack stack = this.items.get(SLOT_DRONE_BAY_START + bay);
            if (!stack.isEmpty() && isDroneItem(stack)) {
                return bay;
            }
        }
        return -1;
    }

    /**
     * Docks an arriving or returning drone into {@code preferredBay} if empty, else the first free
     * bay {@code 0..5}, dropping the drone item on top of the station only if all 6 bays were filled manually.
     */
    public void dockReturnedDrone(ServerLevel level, VehicleKind kind, int preferredBay) {
        ItemStack droneItem = new ItemStack(
                kind == VehicleKind.HEAVY_DRONE
                        ? Lc2Registries.HEAVY_TRANSPORT_DRONE_ITEM.get()
                        : Lc2Registries.TRANSPORT_DRONE_ITEM.get()
        );
        int targetBay = -1;
        if (preferredBay >= 0 && preferredBay < DRONE_BAY_COUNT
                && this.items.get(SLOT_DRONE_BAY_START + preferredBay).isEmpty()) {
            targetBay = preferredBay;
        } else {
            for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
                if (this.items.get(SLOT_DRONE_BAY_START + bay).isEmpty()) {
                    targetBay = bay;
                    break;
                }
            }
        }

        if (targetBay >= 0) {
            this.items.set(SLOT_DRONE_BAY_START + targetBay, droneItem);
            syncBayStateAndVisuals(level);
            setStatusKey("status.lc2.docked");
        } else {
            Containers.dropItemStack(
                    level,
                    this.worldPosition.getX() + 0.5,
                    this.worldPosition.getY() + 1.15,
                    this.worldPosition.getZ() + 0.5,
                    droneItem
            );
        }
        this.dispatchCooldown = 25;
        setChanged();
    }

    @Override
    public void syncRegistry(ServerLevel level) {
        BlockPos3i core = Lc2Coords.toCore(this.worldPosition);
        LogisticsLevelManager.stations(level).register(
                core,
                StationType.CARGO_STATION,
                this.stationName,
                null
        );
        LogisticsLevelManager.stations(level).setDockedBayMask(core, getDockedBayMask());
        LogisticsLevelManager.data(level).setDirty();
    }

    private void syncBayStateAndVisuals(ServerLevel level) {
        syncRegistry(level);
        refreshClientBayBytes();
        level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    private void refreshClientBayBytes() {
        for (int bay = 0; bay < DRONE_BAY_COUNT; bay++) {
            String id = Lc2Coords.itemId(this.items.get(SLOT_DRONE_BAY_START + bay));
            if (LogisticsConstants.TRANSPORT_DRONE_ID.equals(id)) {
                this.clientBayRenderKinds[bay] = 1;
            } else if (LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID.equals(id)) {
                this.clientBayRenderKinds[bay] = 2;
            } else {
                this.clientBayRenderKinds[bay] = 0;
            }
        }
    }

    @Override
    protected void onSlotChanged(int slot) {
        super.onSlotChanged(slot);
        if (slot >= SLOT_DRONE_BAY_START && slot < SLOT_DRONE_BAY_START + DRONE_BAY_COUNT) {
            refreshClientBayBytes();
            if (this.level instanceof ServerLevel serverLevel) {
                syncBayStateAndVisuals(serverLevel);
            }
        }
    }

    @Override
    protected int getSlotMaxStackSize(int slot, ItemStack stack) {
        if (slot == SLOT_RF_CHIP || (slot >= SLOT_DRONE_BAY_START && slot < SLOT_DRONE_BAY_START + DRONE_BAY_COUNT)) {
            return 1;
        }
        return super.getSlotMaxStackSize(slot, stack);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_RF_CHIP) {
            return RfChipTier.fromItemId(Lc2Coords.itemId(stack)).isInstalled();
        }
        if (slot >= SLOT_DRONE_BAY_START && slot < SLOT_DRONE_BAY_START + DRONE_BAY_COUNT) {
            return isDroneItem(stack);
        }
        return super.canPlaceItem(slot, stack);
    }

    @Override
    protected void tickStation(ServerLevel level, long gameTime, boolean redstoneTriggered) {
        boolean active = isChunkLoaderActive()
                || getRfChipTier().isInstalled()
                || hasActiveDroneTransit(level);
        setActiveBlockState(level, active);

        if (redstoneTriggered && this.dispatchCooldown <= 0) {
            if (!tryDispatch(level, false)) {
                this.dispatchCooldown = 20;
            }
        }
    }

    @Override
    public boolean tryDispatch(ServerLevel level, boolean manualTrigger) {
        refreshUpgradesIfNeeded();
        syncRegistry(level);

        boolean triggered = manualTrigger
                || this.upgrades.isRedstoneTriggered(level.hasNeighborSignal(this.worldPosition));
        if (!triggered) {
            setStatusKey(DispatchFailure.REDSTONE_INACTIVE.translationKey());
            return false;
        }

        RfChipTier chip = getRfChipTier();
        if (!chip.isInstalled()) {
            setStatusKey(DispatchFailure.NO_RF_CHIP.translationKey());
            return false;
        }

        int bay = findReadyDroneBay();
        if (bay < 0) {
            setStatusKey(DispatchFailure.NO_VEHICLE.translationKey());
            return false;
        }

        VehicleKind kind = VehicleKind.fromItemId(Lc2Coords.itemId(this.items.get(SLOT_DRONE_BAY_START + bay)))
                .orElse(VehicleKind.STANDARD_DRONE);

        BlockPos3i originCore = Lc2Coords.toCore(this.worldPosition);
        BlockPos3i targetCore = this.targetPos == null ? null : Lc2Coords.toCore(this.targetPos);

        VirtualTransitEngine.DroneDispatchRequest request = new VirtualTransitEngine.DroneDispatchRequest(
                null,
                kind,
                originCore,
                bay,
                targetCore,
                this.autoReturn,
                true,
                chip,
                this.upgrades,
                this.storedEu,
                getBatteryCount(),
                this.energyPriority,
                LogisticsLevelManager.heightSampler(level)
        );

        VirtualTransitEngine.DispatchResult result = LogisticsLevelManager.engine(level).dispatchDrone(request);
        if (!result.isSuccess()) {
            setStatusKey(result.failure().translationKey());
            return false;
        }

        // Debit origin energy, consume drone from bay slot, load cargo up to drone capacity, and launch
        debitPayment(result.payment());
        this.items.set(SLOT_DRONE_BAY_START + bay, ItemStack.EMPTY);
        syncBayStateAndVisuals(level);

        SimpleContainer droneHold = new SimpleContainer(kind.cargoSlots());
        loadOutgoingCargoInto(level, droneHold, kind.cargoSlots());
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < droneHold.getContainerSize(); i++) {
            ItemStack stack = droneHold.getItem(i);
            if (!stack.isEmpty()) {
                cargo.add(stack.copy());
            }
        }
        LogisticsLevelManager.bindDispatchedDrone(level, result.transit(), cargo);

        this.dispatchCooldown = 20;
        setStatusKey("status.lc2.dispatched");
        setActiveBlockState(level, true);
        setChanged();
        return true;
    }

    private boolean hasActiveDroneTransit(ServerLevel level) {
        BlockPos3i core = Lc2Coords.toCore(this.worldPosition);
        for (TransitRecord rec : LogisticsLevelManager.activeTransits(level)) {
            if (rec.vehicleKind().isDrone()
                    && (rec.originPos().equals(core) || rec.targetPos().equals(core))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public S2CStationSyncPacket buildSyncPacket(ServerLevel level) {
        refreshUpgradesIfNeeded();
        RfChipTier chip = getRfChipTier();
        int rfTierIndex = switch (chip) {
            case TIER_1 -> 1;
            case TIER_2 -> 2;
            case TIER_3 -> 3;
            default -> 0;
        };
        boolean droneReady = findReadyDroneBay() >= 0;

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
                droneReady,
                false,
                false,
                rfTierIndex,
                this.statusKey,
                buildTargetSummaries(level)
        );
    }

    // --- Client Roof Renderer Sync ---

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        refreshClientBayBytes();
        tag.putByteArray("BayKinds", this.clientBayRenderKinds.clone());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        if (tag.contains("BayKinds")) {
            byte[] incoming = tag.getByteArray("BayKinds");
            for (int i = 0; i < DRONE_BAY_COUNT; i++) {
                this.clientBayRenderKinds[i] = i < incoming.length ? incoming[i] : 0;
            }
        }
    }

    @Override
    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("BayKinds")) {
            handleUpdateTag(tag);
        } else {
            refreshClientBayBytes();
        }
    }

    private static boolean isDroneItem(ItemStack stack) {
        String id = Lc2Coords.itemId(stack);
        return LogisticsConstants.TRANSPORT_DRONE_ID.equals(id)
                || LogisticsConstants.HEAVY_TRANSPORT_DRONE_ID.equals(id);
    }

    @Override
    public Component getDisplayName() {
        return this.stationName.isBlank()
                ? Component.translatable("container.lc2.cargo_station")
                : Component.literal(this.stationName);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CargoStationMenu(containerId, playerInventory, this);
    }
}
