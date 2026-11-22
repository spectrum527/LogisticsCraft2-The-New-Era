package com.logisticscraft.lc2.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * NeoForge 26.x base block class for LC2 logistics stations bridging version-specific
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

    /** Called on the server when the station block is truly removed. */
    protected void onStationBlockRemoved(BlockState state, ServerLevel level, BlockPos pos) {
    }

    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (handleUseWithItem(stack, state, level, pos, player, hand, hit)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            Orientation orientation,
            boolean movedByPiston
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        onNeighborRedstoneChanged(state, level, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            boolean movedByPiston
    ) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        onStationBlockRemoved(state, level, pos);
    }
}
