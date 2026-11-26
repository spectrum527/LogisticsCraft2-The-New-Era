package com.logisticscraft.lc2.entity;

import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.TransitRecord;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Autonomous 54-slot Electric Smart Cargo Boat ({@code lc2:electric_cargo_boat}).
 * <p>
 * Critical riding rule:
 * <ul>
 *   <li>When docked/idle ({@code isAutonomousTransit() == false}), players can right-click to board
 *       or sneak-right-click to inspect its 54-slot cargo hold.</li>
 *   <li>If a player sits inside BEFORE a Smart Port launches the boat, they remain seated and ride
 *       along autonomously (normal dismount remains available).</li>
 *   <li>While actively running ({@code isAutonomousTransit() == true}), new boarding is blocked
 *       both in {@link #canAddPassenger} and {@link #handleBoatInteract}.</li>
 * </ul>
 */
public final class ElectricCargoBoatEntity extends ElectricCargoBoatBase {

    private static final EntityDataAccessor<Boolean> DATA_AUTONOMOUS_TRANSIT =
            SynchedEntityData.defineId(ElectricCargoBoatEntity.class, EntityDataSerializers.BOOLEAN);

    private static final double STALE_TRANSIT_MAX_DRIFT_SQ = 32.0 * 32.0;

    private UUID transitId;
    private int transitGeneration = 0;

    public ElectricCargoBoatEntity(EntityType<ElectricCargoBoatEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_AUTONOMOUS_TRANSIT, false);
    }

    /** Returns {@code true} while this boat is actively sailing under autonomous Smart Port control. */
    public boolean isAutonomousTransit() {
        return entityData.get(DATA_AUTONOMOUS_TRANSIT);
    }

    /** Returns the active transit UUID driving this boat, or {@code null} when idle/docked. */
    public UUID getTransitId() {
        return transitId;
    }

    /** Returns the generation counter stamped by {@link LogisticsLevelManager} when materialized. */
    public int getTransitGeneration() {
        return transitGeneration;
    }

    /** Updates the autonomous transit state and associated transit UUID (retaining current generation). */
    public void setAutonomousTransit(boolean autonomous, UUID transitId) {
        setAutonomousTransit(autonomous, transitId, autonomous ? this.transitGeneration : 0);
    }

    /** Updates the autonomous transit state, transit UUID, and authoritative entity generation. */
    public void setAutonomousTransit(boolean autonomous, UUID transitId, int generation) {
        entityData.set(DATA_AUTONOMOUS_TRANSIT, autonomous);
        this.transitId = autonomous ? transitId : null;
        this.transitGeneration = autonomous ? Math.max(0, generation) : 0;
        if (autonomous) {
            setNoGravity(true);
        } else {
            setNoGravity(false);
            setPaddleState(false, false);
        }
    }

    /**
     * Inserts {@code stack} into this boat's 54-slot cargo hold, returning any leftover remainder.
     */
    public ItemStack insertCargoStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (int i = 0; i < getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining.getCount());
                    existing.grow(move);
                    remaining.shrink(move);
                }
            }
        }
        for (int i = 0; i < getContainerSize() && !remaining.isEmpty(); i++) {
            if (getItem(i).isEmpty()) {
                int move = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                setItem(i, remaining.split(move));
            }
        }
        setChanged();
        return remaining;
    }

    /** Extracts and clears all non-empty ItemStacks from this boat's 54-slot hold. */
    public List<ItemStack> drainAllCargo() {
        List<ItemStack> drained = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (!s.isEmpty()) {
                drained.add(s.copy());
                setItem(i, ItemStack.EMPTY);
            }
        }
        setChanged();
        return drained;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (isAutonomousTransit()) {
            return false;
        }
        return super.canAddPassenger(passenger);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        if (isAutonomousTransit()) {
            // Keep server position authoritative during autonomous transit while seated player rides along
            return null;
        }
        return super.getControllingPassenger();
    }

    @Override
    protected InteractionResult handleBoatInteract(Player player, InteractionHand hand) {
        if (isAutonomousTransit()) {
            if (!Lc2Native.isClientSide(level())) {
                Lc2Native.sendActionBar(
                        player,
                        Component.translatable(DispatchFailure.BOARDING_BLOCKED_IN_TRANSIT.translationKey())
                );
            }
            return InteractionResult.FAIL;
        }
        if (player.isSecondaryUseActive()) {
            if (!Lc2Native.isClientSide(level())) {
                player.openMenu(this);
            }
            return InteractionResult.SUCCESS;
        }
        return superInteractIdle(player, hand);
    }

    @Override
    public void tick() {
        if (!Lc2Native.isClientSide(level()) && isAutonomousTransit()) {
            tickAutonomousBase();
            resetFallDistance();
            if (tickCount > 2 && level() instanceof ServerLevel serverLevel) {
                if (isStaleAutonomousGhost(serverLevel)) {
                    // Never promote a stale ghost boat reloaded from an unloaded chunk back into an idle boat!
                    setDematerializing(true);
                    discard();
                }
            }
            return;
        }
        super.tick();
    }

    private boolean isStaleAutonomousGhost(ServerLevel serverLevel) {
        if (transitId == null) {
            return true;
        }
        Optional<TransitRecord> activeOpt = LogisticsLevelManager.getData(serverLevel).engine().getTransit(transitId);
        if (activeOpt.isEmpty()) {
            return true;
        }
        TransitRecord rec = activeOpt.get();
        if (rec.entityGeneration() > 0 && transitGeneration != rec.entityGeneration()) {
            return true;
        }
        Entity live = LogisticsLevelManager.findLiveEntity(serverLevel, transitId);
        if (live != null && live != this) {
            return true;
        }
        Vec3d simPos = rec.currentPos();
        double dx = getX() - simPos.x();
        double dz = getZ() - simPos.z();
        return (dx * dx + dz * dz) > STALE_TRANSIT_MAX_DRIFT_SQ;
    }

    @Override
    protected void writeBoatMeta(CompoundTag tag) {
        tag.putBoolean("autonomous", isAutonomousTransit());
        if (transitId != null) {
            tag.putString("transitId", transitId.toString());
        }
        tag.putInt("transitGen", transitGeneration);
    }

    @Override
    protected void readBoatMeta(CompoundTag tag) {
        boolean autonomous = Lc2Nbt.getBoolean(tag, "autonomous", false);
        String idStr = Lc2Nbt.getString(tag, "transitId", "");
        int gen = Math.max(0, Lc2Nbt.getInt(tag, "transitGen", 0));
        UUID parsed = null;
        if (!idStr.isEmpty()) {
            try {
                parsed = UUID.fromString(idStr);
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        setAutonomousTransit(autonomous && parsed != null, parsed, gen);
    }

    @Override
    protected void onBoatDestroyed(ServerLevel level) {
        if (transitId != null) {
            if (isAutonomousTransit() && isStaleAutonomousGhost(level)) {
                // Stale ghost boat from a previously unloaded chunk must not cancel the real active transit
                setDematerializing(true);
                transitId = null;
                return;
            }
            List<ItemStack> savedCargo = LogisticsLevelManager.cancelAndTakeCargo(level, transitId);
            for (ItemStack stack : savedCargo) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, getX(), getY(), getZ(), stack);
                }
            }
            transitId = null;
        }
    }
}
