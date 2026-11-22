package com.logisticscraft.lc2.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * NeoForge 26.x BlockEntity serialization, removal, and client-sync adapter for LC2 stations.
 */
public abstract class Lc2StationBlockEntityBase extends BlockEntity {

    private static final String KEY_ITEMS = "items";
    private static final String KEY_LC2_DATA = "lc2";

    protected Lc2StationBlockEntityBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Returns the persistent inventory backing list for this station. */
    protected abstract NonNullList<ItemStack> inventoryItems();

    /** Writes non-item persistent state into {@code tag}. */
    protected abstract void saveStationTag(CompoundTag tag);

    /** Restores non-item persistent state from {@code tag}. */
    protected abstract void loadStationTag(CompoundTag tag);

    /** Writes lightweight client-side visual sync state into {@code tag}. */
    protected abstract void saveClientSyncTag(CompoundTag tag);

    /** Restores lightweight client-side visual sync state from {@code tag}. */
    protected abstract void loadClientSyncTag(CompoundTag tag);

    /** Called immediately before the station block is removed on the server. */
    protected abstract void onBeforeRemove(ServerLevel level, BlockPos pos);

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        NonNullList<ItemStack> items = inventoryItems();
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            copy.add(items.get(i));
        }
        output.store(KEY_ITEMS, ItemStack.OPTIONAL_CODEC.listOf(), copy);
        CompoundTag custom = new CompoundTag();
        saveStationTag(custom);
        output.store(KEY_LC2_DATA, CompoundTag.CODEC, custom);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        NonNullList<ItemStack> items = inventoryItems();
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        List<ItemStack> loaded = input.read(KEY_ITEMS, ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < Math.min(loaded.size(), items.size()); i++) {
            items.set(i, loaded.get(i));
        }
        input.read(KEY_LC2_DATA, CompoundTag.CODEC).ifPresent(this::loadStationTag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        CompoundTag sync = new CompoundTag();
        saveClientSyncTag(sync);
        tag.put(KEY_LC2_DATA, sync);
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        input.read(KEY_LC2_DATA, CompoundTag.CODEC).ifPresent(this::loadClientSyncTag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ValueInput valueInput) {
        valueInput.read(KEY_LC2_DATA, CompoundTag.CODEC).ifPresent(this::loadClientSyncTag);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (getLevel() instanceof ServerLevel serverLevel) {
            onBeforeRemove(serverLevel, pos);
        }
    }
}
