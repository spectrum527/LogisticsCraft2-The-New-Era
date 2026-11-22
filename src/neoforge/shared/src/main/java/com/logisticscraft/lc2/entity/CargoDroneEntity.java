package com.logisticscraft.lc2.entity;

import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Airborne autonomous Cargo Drone entity ({@code lc2:cargo_drone}) supporting both
 * {@code STANDARD} and {@code HEAVY} variants, synchronized cleanly with {@link LogisticsLevelManager}.
 */
public final class CargoDroneEntity extends CargoDroneBase {

    private static final EntityDataAccessor<Boolean> DATA_HEAVY =
            SynchedEntityData.defineId(CargoDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AIRBORNE =
            SynchedEntityData.defineId(CargoDroneEntity.class, EntityDataSerializers.BOOLEAN);

    private UUID transitId;
    private boolean dematerializing = false;

    public CargoDroneEntity(EntityType<CargoDroneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HEAVY, false);
        builder.define(DATA_AIRBORNE, true);
    }

    /** Returns {@code true} if this drone is the armored Heavy Transport Drone variant. */
    public boolean isHeavy() {
        return entityData.get(DATA_HEAVY);
    }

    /** Sets whether this drone uses the heavy variant. */
    public void setHeavy(boolean heavy) {
        entityData.set(DATA_HEAVY, heavy);
    }

    /** Returns {@code true} if this drone's rotors should spin at full flight speed. */
    public boolean isAirborne() {
        return entityData.get(DATA_AIRBORNE);
    }

    /** Sets whether this drone is currently airborne. */
    public void setAirborne(boolean airborne) {
        entityData.set(DATA_AIRBORNE, airborne);
    }

    /** Returns the active transit UUID driving this drone, or {@code null} if unbound. */
    public UUID getTransitId() {
        return transitId;
    }

    /** Configures transit identity, variant, and airborne state in one call. */
    public void configureTransit(UUID transitId, boolean heavy, boolean airborne) {
        this.transitId = transitId;
        setHeavy(heavy);
        setAirborne(airborne);
    }

    /** Marks clean dematerialization so removing the entity never drops its drone item or cargo. */
    public void setDematerializing(boolean dematerializing) {
        this.dematerializing = dematerializing;
    }

    @Override
    public boolean isPickable() {
        return !isAirborne();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        this.noPhysics = true;
        if (!Lc2Native.isClientSide(level()) && tickCount > 20 && level() instanceof ServerLevel serverLevel) {
            if (transitId == null
                    || LogisticsLevelManager.getData(serverLevel).engine().getTransit(transitId).isEmpty()) {
                // Clean up orphaned drone entity whose transit already completed across a chunk reload
                setDematerializing(true);
                discard();
            }
        }
    }

    @Override
    protected boolean handleDroneHurt(ServerLevel level, DamageSource source, float amount) {
        if (dematerializing || isRemoved()) {
            return false;
        }
        // Only allow creative players or void/kill commands to destroy an active airborne drone
        boolean creativeBreaker = source.getEntity() instanceof Player player && player.isCreative();
        if (!creativeBreaker && amount < 1000.0F) {
            return false;
        }
        if (transitId != null) {
            List<ItemStack> cargo = LogisticsLevelManager.cancelAndTakeCargo(level, transitId);
            for (ItemStack stack : cargo) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, getX(), getY(), getZ(), stack);
                }
            }
            VehicleKind kind = isHeavy() ? VehicleKind.HEAVY_DRONE : VehicleKind.STANDARD_DRONE;
            if (!creativeBreaker) {
                Lc2Native.spawnAtLocation(this, level, Lc2Native.stack(kind.itemId(), 1));
            }
            transitId = null;
        }
        discard();
        return true;
    }

    @Override
    protected void writeDroneMeta(CompoundTag tag) {
        tag.putBoolean("heavy", isHeavy());
        tag.putBoolean("airborne", isAirborne());
        if (transitId != null) {
            tag.putString("transitId", transitId.toString());
        }
    }

    @Override
    protected void readDroneMeta(CompoundTag tag) {
        setHeavy(Lc2Nbt.getBoolean(tag, "heavy", false));
        setAirborne(Lc2Nbt.getBoolean(tag, "airborne", true));
        String idStr = Lc2Nbt.getString(tag, "transitId", "");
        if (!idStr.isEmpty()) {
            try {
                this.transitId = UUID.fromString(idStr);
            } catch (IllegalArgumentException ignored) {
                this.transitId = null;
            }
        }
    }
}
