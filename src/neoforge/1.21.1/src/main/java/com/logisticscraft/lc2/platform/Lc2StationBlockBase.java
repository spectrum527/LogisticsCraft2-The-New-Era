package com.logisticscraft.lc2.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * NeoForge 1.21.1 base block class for LC2 logistics stations bridging version-specific
 * interaction, neighbor-update, and block-removal hooks.
 */
public abstract class Lc2StationBlockBase extends Block implements EntityBlock {

    protected Lc2StationBlockBase(Properties properties) {
        super(properties);
    }

    /**
     * Handles right-clicking the station while holding an item.
     * Return {@code true} if handled (consumes interaction); {@code false} to fall through to {@code useWithoutItem}.
     */
    protected boolean handleUseWithItem(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        return false;
    }

    /** Called when a neighboring block updates (e.g. redstone power changes). */
    protected void onNeighborRedstoneChanged(BlockState state, Level level, BlockPos pos) {
    }

    /** Called on the server when the station block is truly removed (not merely a blockstate change). */
    protected void onStationBlockRemoved(BlockState state, ServerLevel level, BlockPos pos) {
    }

    /** Computes the 0..15 redstone comparator signal for this station's cargo buffer. */
    protected int computeAnalogOutputSignal(Level level, BlockPos pos) {
        return 0;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return computeAnalogOutputSignal(level, pos);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (handleUseWithItem(stack, state, level, pos, player, hand, hit)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        onNeighborRedstoneChanged(state, level, pos);
    }

    @Override
    protected void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            if (level.getBlockEntity(pos) instanceof Lc2StationBlockEntityBase station) {
                station.onBeforeRemove(serverLevel, pos);
            }
            onStationBlockRemoved(state, serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
