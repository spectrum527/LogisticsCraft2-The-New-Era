package com.logisticscraft.lc2.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * NeoForge 1.21.1 base class for {@link CargoDroneEntity} bridging save/hurt hooks.
 */
public abstract class CargoDroneBase extends Entity {

    protected CargoDroneBase(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** Writes custom LC2 drone metadata into {@code tag}. */
    protected abstract void writeDroneMeta(CompoundTag tag);

    /** Reads custom LC2 drone metadata from {@code tag}. */
    protected abstract void readDroneMeta(CompoundTag tag);

    /** Handles damage on the server. */
    protected abstract boolean handleDroneHurt(ServerLevel level, DamageSource source, float amount);

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        CompoundTag meta = new CompoundTag();
        writeDroneMeta(meta);
        tag.put("trera_drone", meta);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("trera_drone")) {
            readDroneMeta(tag.getCompound("trera_drone"));
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level() instanceof ServerLevel serverLevel) {
            return handleDroneHurt(serverLevel, source, amount);
        }
        return false;
    }
}
