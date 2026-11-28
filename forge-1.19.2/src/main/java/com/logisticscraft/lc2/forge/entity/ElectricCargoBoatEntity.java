package com.logisticscraft.lc2.forge.entity;

import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.gui.ElectricCargoBoatMenu;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * 54-slot autonomous Electric Smart Cargo Boat entity for Smart Port waterway logistics.
 * <p>
 * Enforces the critical riding rule:
 * <ul>
 *   <li>When docked/idle ({@code !isAutonomousTransit()}): players can right-click to board or
 *       sneak-right-click to inspect the 54-slot cargo hold.</li>
 *   <li>When running autonomously ({@code isAutonomousTransit()}): boarding is refused with
 *       {@code status.trera.failure.boarding_blocked_in_transit}, while any player who boarded
 *       before launch remains seated and rides along seamlessly.</li>
 * </ul>
 */
public class ElectricCargoBoatEntity extends Boat implements MenuProvider {

    private static final EntityDataAccessor<Boolean> DATA_AUTONOMOUS_TRANSIT =
            SynchedEntityData.defineId(ElectricCargoBoatEntity.class, EntityDataSerializers.BOOLEAN);

    private final SimpleContainer cargo = new SimpleContainer(LogisticsConstants.BOAT_CARGO_SLOTS);
    @Nullable
    private UUID transitId;
    private int transitGeneration = 0;
    private boolean suppressDropOnRemove = false;
    private boolean cargoDropped = false;

    // Client-side interpolation state during autonomous transit
    private int autoLerpSteps;
    private double autoLerpX;
    private double autoLerpY;
    private double autoLerpZ;
    private double autoLerpYRot;

    public ElectricCargoBoatEntity(EntityType<? extends Boat> type, Level level) {
        super(type, level);
        this.setType(Boat.Type.OAK);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_AUTONOMOUS_TRANSIT, false);
    }

    /** Returns {@code true} while this boat is actively sailing under autonomous transit control. */
    public boolean isAutonomousTransit() {
        return this.entityData.get(DATA_AUTONOMOUS_TRANSIT);
    }

    /** Sets the synced autonomous transit state. */
    public void setAutonomousTransit(boolean active) {
        this.entityData.set(DATA_AUTONOMOUS_TRANSIT, active);
        this.setNoGravity(active);
        this.noPhysics = active;
    }

    /** Returns the bound {@link com.logisticscraft.lc2.core.TransitRecord} UUID, or {@code null} when docked/idle. */
    @Nullable
    public UUID getTransitId() {
        return transitId;
    }

    /** Returns the transit materialization generation stamped when this boat was bound. */
    public int getTransitGeneration() {
        return transitGeneration;
    }

    /** Binds this boat entity to an active virtual transit. */
    public void bindToTransit(UUID id, boolean moving) {
        bindToTransit(id, 0, moving);
    }

    /** Binds this boat entity to an active virtual transit with an authoritative generation token. */
    public void bindToTransit(UUID id, int generation, boolean moving) {
        this.transitId = id;
        this.transitGeneration = Math.max(0, generation);
        setAutonomousTransit(moving);
    }

    /** Detaches this boat from virtual transit and restores normal docked/idle behavior. */
    public void clearTransit() {
        this.transitId = null;
        this.transitGeneration = 0;
        setAutonomousTransit(false);
        this.setPaddleState(false, false);
    }

    /** Returns the 54-slot cargo hold container. */
    public SimpleContainer getCargoContainer() {
        return cargo;
    }

    /** Captures a defensive list of all non-empty cargo stacks currently in the 54-slot hold. */
    public List<ItemStack> snapshotCargoList() {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack stack = cargo.getItem(i);
            if (!stack.isEmpty()) {
                list.add(stack.copy());
            }
        }
        return list;
    }

    /** Replaces the 54-slot cargo hold contents with {@code items}. */
    public void loadCargoList(List<ItemStack> items) {
        cargo.clearContent();
        if (items == null) {
            return;
        }
        int slot = 0;
        for (ItemStack stack : items) {
            if (stack != null && !stack.isEmpty() && slot < cargo.getContainerSize()) {
                cargo.setItem(slot++, stack.copy());
            }
        }
        cargo.setChanged();
    }

    /**
     * Synchronizes position and yaw smoothly from {@link LogisticsLevelManager} during server transit ticks.
     */
    public void syncFromTransit(Vec3 targetPos, float yaw) {
        Vec3 delta = targetPos.subtract(this.position());
        this.setDeltaMovement(delta);
        this.setPos(targetPos.x, targetPos.y, targetPos.z);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.setXRot(0.0F);
        this.hasImpulse = true;
        this.fallDistance = 0.0F;
        for (Entity passenger : this.getPassengers()) {
            this.positionRider(passenger);
        }
    }

    /**
     * Discards this entity cleanly when dematerializing into unloaded chunks without dropping cargo.
     */
    public void discardWithoutDropping() {
        this.suppressDropOnRemove = true;
        this.discard();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (isAutonomousTransit()) {
            return false;
        }
        return this.getPassengers().isEmpty() && !this.isEyeInFluid(FluidTags.WATER);
    }

    @Override
    @Nullable
    public Entity getControllingPassenger() {
        return isAutonomousTransit() ? null : super.getControllingPassenger();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (isAutonomousTransit()) {
            if (!this.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable(DispatchFailure.BOARDING_BLOCKED_IN_TRANSIT.translationKey()),
                        true
                );
            }
            return InteractionResult.sidedSuccess(this.level.isClientSide);
        }
        if (player.isSecondaryUseActive()) {
            if (!this.level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                NetworkHooks.openScreen(serverPlayer, this, buf -> buf.writeVarInt(this.getId()));
            }
            return InteractionResult.sidedSuccess(this.level.isClientSide);
        }
        if (!this.level.isClientSide) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (isAutonomousTransit()) {
            this.autoLerpX = x;
            this.autoLerpY = y;
            this.autoLerpZ = z;
            this.autoLerpYRot = yRot;
            this.autoLerpSteps = Math.max(1, steps);
        } else {
            super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
        }
    }

    @Override
    public void tick() {
        if (this.level instanceof ServerLevel serverLevel && this.transitId != null) {
            if (this.tickCount > 2 && LogisticsLevelManager.isStaleAutonomousBoat(serverLevel, this)) {
                discardWithoutDropping();
                return;
            }
        }

        if (isAutonomousTransit()) {
            this.setNoGravity(true);
            this.noPhysics = true;
            this.xo = this.getX();
            this.yo = this.getY();
            this.zo = this.getZ();
            this.yRotO = this.getYRot();
            this.xRotO = this.getXRot();
            this.baseTick();

            if (this.level.isClientSide) {
                if (this.autoLerpSteps > 0) {
                    double nx = this.getX() + (this.autoLerpX - this.getX()) / this.autoLerpSteps;
                    double ny = this.getY() + (this.autoLerpY - this.getY()) / this.autoLerpSteps;
                    double nz = this.getZ() + (this.autoLerpZ - this.getZ()) / this.autoLerpSteps;
                    double dyRot = Mth.wrapDegrees(this.autoLerpYRot - this.getYRot());
                    this.setYRot((float) (this.getYRot() + dyRot / this.autoLerpSteps));
                    this.autoLerpSteps--;
                    this.setPos(nx, ny, nz);
                }
                this.setPaddleState(true, true);
            } else {
                this.setPaddleState(true, true);
            }

            for (Entity passenger : this.getPassengers()) {
                this.positionRider(passenger);
            }
            return;
        }

        this.setNoGravity(false);
        this.noPhysics = false;
        super.tick();
    }

    @Override
    public Item getDropItem() {
        return Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get();
    }

    @Override
    public void destroy(DamageSource source) {
        if (!this.level.isClientSide) {
            if (this.transitId != null && this.level instanceof ServerLevel serverLevel) {
                if (LogisticsLevelManager.isStaleAutonomousBoat(serverLevel, this)) {
                    this.suppressDropOnRemove = true;
                    this.cargo.clearContent();
                    this.transitId = null;
                    return;
                }
            }
            if (this.level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                this.spawnAtLocation(getDropItem());
            }
            dropCargoOnce();
            if (this.transitId != null && this.level instanceof ServerLevel serverLevel) {
                LogisticsLevelManager.cancelTransitAndDrop(serverLevel, this.transitId, this.position(), true);
                this.transitId = null;
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level.isClientSide && reason.shouldDestroy() && !this.suppressDropOnRemove) {
            boolean staleGhost = this.transitId != null
                    && this.level instanceof ServerLevel serverLevel
                    && LogisticsLevelManager.isStaleAutonomousBoat(serverLevel, this);
            if (!staleGhost) {
                dropCargoOnce();
                if (this.transitId != null && this.level instanceof ServerLevel serverLevel) {
                    LogisticsLevelManager.cancelTransitAndDrop(serverLevel, this.transitId, this.position(), true);
                    this.transitId = null;
                }
            } else {
                this.cargo.clearContent();
                this.transitId = null;
            }
        }
        super.remove(reason);
    }

    private void dropCargoOnce() {
        if (cargoDropped) {
            return;
        }
        cargoDropped = true;
        Containers.dropContents(this.level, this, this.cargo);
        this.cargo.clearContent();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("AutonomousTransit", isAutonomousTransit());
        if (this.transitId != null) {
            tag.putUUID("TransitId", this.transitId);
            tag.putInt("TransitGen", this.transitGeneration);
        }
        ListTag items = new ListTag();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack stack = cargo.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                stack.save(itemTag);
                items.add(itemTag);
            }
        }
        tag.put("CargoItems", items);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setAutonomousTransit(tag.getBoolean("AutonomousTransit"));
        this.transitId = tag.hasUUID("TransitId") ? tag.getUUID("TransitId") : null;
        this.transitGeneration = Math.max(0, tag.getInt("TransitGen"));
        cargo.clearContent();
        ListTag items = tag.getList("CargoItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < cargo.getContainerSize()) {
                cargo.setItem(slot, ItemStack.of(itemTag));
            }
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.trera.electric_cargo_boat");
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        if (isAutonomousTransit()) {
            return null;
        }
        return new ElectricCargoBoatMenu(containerId, playerInventory, this);
    }

    @Override
    public Packet<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
