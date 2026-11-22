package com.logisticscraft.lc2.forge.entity;

import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * Physical visual entity representing an active {@code STANDARD} or {@code HEAVY} Cargo Drone
 * while flying or waiting on a target landing pad inside loaded chunks.
 */
public class CargoDroneEntity extends Entity {

    private static final EntityDataAccessor<Boolean> DATA_HEAVY =
            SynchedEntityData.defineId(CargoDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AIRBORNE =
            SynchedEntityData.defineId(CargoDroneEntity.class, EntityDataSerializers.BOOLEAN);

    @Nullable
    private UUID transitId;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    public CargoDroneEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_HEAVY, false);
        this.entityData.define(DATA_AIRBORNE, true);
    }

    /** Returns {@code true} if this entity represents a Heavy Transport Drone. */
    public boolean isHeavy() {
        return this.entityData.get(DATA_HEAVY);
    }

    /** Sets whether this drone uses the Heavy Transport Drone variant and scale. */
    public void setHeavy(boolean heavy) {
        this.entityData.set(DATA_HEAVY, heavy);
    }

    /** Returns {@code true} while this drone is airborne (fast rotor spin). */
    public boolean isAirborne() {
        return this.entityData.get(DATA_AIRBORNE);
    }

    /** Sets the synced airborne state controlling rotor spin animation. */
    public void setAirborne(boolean airborne) {
        this.entityData.set(DATA_AIRBORNE, airborne);
    }

    /** Returns the bound virtual transit UUID, or {@code null}. */
    @Nullable
    public UUID getTransitId() {
        return transitId;
    }

    /** Binds this drone entity to an active virtual transit. */
    public void bindToTransit(UUID id, boolean heavy, boolean airborne) {
        this.transitId = id;
        setHeavy(heavy);
        setAirborne(airborne);
    }

    /** Synchronizes position and yaw smoothly from {@link LogisticsLevelManager}. */
    public void syncFromTransit(Vec3 targetPos, float yaw) {
        Vec3 delta = targetPos.subtract(this.position());
        this.setDeltaMovement(delta);
        this.setPos(targetPos.x, targetPos.y, targetPos.z);
        this.setYRot(yaw);
        this.setXRot(0.0F);
        this.hasImpulse = true;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = Math.max(1, steps);
    }

    @Override
    public void tick() {
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        super.tick();

        if (this.level instanceof ServerLevel serverLevel) {
            if (this.transitId == null || !LogisticsLevelManager.isTransitActive(serverLevel, this.transitId)) {
                this.discard();
            }
            return;
        }

        if (this.lerpSteps > 0) {
            double nx = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
            double ny = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
            double nz = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
            double dyRot = Mth.wrapDegrees(this.lerpYRot - this.getYRot());
            this.setYRot((float) (this.getYRot() + dyRot / this.lerpSteps));
            this.lerpSteps--;
            this.setPos(nx, ny, nz);
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.transitId = tag.hasUUID("TransitId") ? tag.getUUID("TransitId") : null;
        setHeavy(tag.getBoolean("Heavy"));
        setAirborne(tag.getBoolean("Airborne"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.transitId != null) {
            tag.putUUID("TransitId", this.transitId);
        }
        tag.putBoolean("Heavy", isHeavy());
        tag.putBoolean("Airborne", isAirborne());
    }

    @Override
    public Packet<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
