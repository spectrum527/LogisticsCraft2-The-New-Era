package com.logisticscraft.lc2.entity;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.menu.ElectricCargoBoatMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge 26.x base class for {@link ElectricCargoBoatEntity}, extending vanilla
 * {@link ChestBoat} with a 54-slot cargo hold and version-bridged save/interaction hooks.
 */
public abstract class ElectricCargoBoatBase extends ChestBoat {

    protected final NonNullList<ItemStack> cargoItems =
            NonNullList.withSize(LogisticsConstants.BOAT_CARGO_SLOTS, ItemStack.EMPTY);
    private boolean dematerializing = false;

    protected ElectricCargoBoatBase(EntityType<? extends ChestBoat> type, Level level) {
        super(type, level, () -> Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get());
    }

    /** Marks whether this boat is being cleanly dematerialized by virtual transit (suppresses drops). */
    public void setDematerializing(boolean dematerializing) {
        this.dematerializing = dematerializing;
    }

    /** Returns {@code true} while cleanly dematerializing into virtual transit. */
    public boolean isDematerializing() {
        return dematerializing;
    }

    /** Shared interaction handler delegated from version-specific {@code interact}. */
    protected abstract InteractionResult handleBoatInteract(Player player, InteractionHand hand);

    /** Writes custom LC2 boat metadata into {@code tag}. */
    protected abstract void writeBoatMeta(CompoundTag tag);

    /** Reads custom LC2 boat metadata from {@code tag}. */
    protected abstract void readBoatMeta(CompoundTag tag);

    /** Called when this boat is genuinely destroyed in the world (not dematerialized). */
    protected abstract void onBoatDestroyed(ServerLevel level);

    /** Runs the base Entity tick without Boat gravity/water physics during autonomous transit. */
    protected final void tickAutonomousBase() {
        super.baseTick();
    }

    /** Delegates to vanilla ChestBoat interact (for idle player boarding). */
    protected final InteractionResult superInteractIdle(Player player, InteractionHand hand) {
        return super.interact(player, hand, Vec3.ZERO);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        return handleBoatInteract(player, hand);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.trera.electric_cargo_boat");
    }

    @Override
    public int getContainerSize() {
        return LogisticsConstants.BOAT_CARGO_SLOTS;
    }

    @Override
    public NonNullList<ItemStack> getItemStacks() {
        return cargoItems;
    }

    @Override
    public void clearItemStacks() {
        for (int i = 0; i < cargoItems.size(); i++) {
            cargoItems.set(i, ItemStack.EMPTY);
        }
    }

    @Override
    public void openCustomInventoryScreen(Player player) {
        if (this instanceof ElectricCargoBoatEntity boat && boat.isAutonomousTransit()) {
            if (!Lc2Native.isClientSide(level())) {
                Lc2Native.sendActionBar(
                        player,
                        Component.translatable(DispatchFailure.VEHICLE_IN_TRANSIT.translationKey())
                );
            }
            return;
        }
        super.openCustomInventoryScreen(player);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        if (this instanceof ElectricCargoBoatEntity boat && boat.isAutonomousTransit()) {
            return null;
        }
        return new ElectricCargoBoatMenu(containerId, playerInventory, this);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        List<ItemStack> copy = new ArrayList<>(cargoItems.size());
        for (int i = 0; i < cargoItems.size(); i++) {
            copy.add(cargoItems.get(i));
        }
        output.store("trera_cargo", ItemStack.OPTIONAL_CODEC.listOf(), copy);
        CompoundTag meta = new CompoundTag();
        writeBoatMeta(meta);
        output.store("trera_boat", CompoundTag.CODEC, meta);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        clearItemStacks();
        List<ItemStack> loaded = input.read("trera_cargo", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < Math.min(loaded.size(), cargoItems.size()); i++) {
            cargoItems.set(i, loaded.get(i));
        }
        input.read("trera_boat", CompoundTag.CODEC).ifPresent(this::readBoatMeta);
    }

    @Override
    public void destroy(ServerLevel level, DamageSource source) {
        if (dematerializing) {
            return;
        }
        onBoatDestroyed(level);
        super.destroy(level, source);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (dematerializing) {
            clearItemStacks();
        }
        super.remove(reason);
    }
}
