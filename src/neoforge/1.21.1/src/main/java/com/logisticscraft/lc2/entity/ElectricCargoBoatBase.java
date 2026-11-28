package com.logisticscraft.lc2.entity;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.menu.ElectricCargoBoatMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * NeoForge 1.21.1 base class for {@link ElectricCargoBoatEntity}, extending vanilla
 * {@link ChestBoat} with a 54-slot cargo hold and version-bridged save/interaction hooks.
 */
public abstract class ElectricCargoBoatBase extends ChestBoat {

    protected final NonNullList<ItemStack> cargoItems =
            NonNullList.withSize(LogisticsConstants.BOAT_CARGO_SLOTS, ItemStack.EMPTY);
    private boolean dematerializing = false;

    protected ElectricCargoBoatBase(EntityType<? extends Boat> type, Level level) {
        super(type, level);
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
        return super.interact(player, hand);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return handleBoatInteract(player, hand);
    }

    @Override
    public Item getDropItem() {
        return Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get();
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
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ContainerHelper.saveAllItems(tag, cargoItems, registryAccess());
        CompoundTag meta = new CompoundTag();
        writeBoatMeta(meta);
        tag.put("trera_boat", meta);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        clearItemStacks();
        ContainerHelper.loadAllItems(tag, cargoItems, registryAccess());
        if (tag.contains("trera_boat")) {
            readBoatMeta(tag.getCompound("trera_boat"));
        }
    }

    @Override
    public void destroy(DamageSource source) {
        if (dematerializing) {
            return;
        }
        if (level() instanceof ServerLevel serverLevel) {
            onBoatDestroyed(serverLevel);
        }
        super.destroy(source);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (dematerializing) {
            clearItemStacks();
        }
        super.remove(reason);
    }
}
