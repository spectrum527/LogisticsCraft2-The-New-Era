package com.logisticscraft.lc2.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * NeoForge 26.x base class for {@link CargoDroneEntity} bridging ValueInput/ValueOutput & hurtServer hooks.
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
    protected void addAdditionalSaveData(ValueOutput output) {
        CompoundTag meta = new CompoundTag();
        writeDroneMeta(meta);
        output.store("trera_drone", CompoundTag.CODEC, meta);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.read("trera_drone", CompoundTag.CODEC).ifPresent(this::readDroneMeta);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return handleDroneHurt(level, source, amount);
    }
}
