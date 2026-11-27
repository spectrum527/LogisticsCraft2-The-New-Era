package com.logisticscraft.lc2.block;

import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.platform.Lc2Native;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Handles third-party wrench interactions ({@code #c:tools/wrench}, {@code #c:tools/wrenches},
 * {@code #c:wrenches}) on LC2 blocks while excluding IC2's own wrenches so IC2's
 * {@link com.industrialage.ia2.wrench.WrenchTarget} never double-triggers.
 */
public final class Lc2WrenchHelper {

    private Lc2WrenchHelper() {
    }

    /**
     * Attempts to handle a third-party wrench right-click on an LC2 block:
     * <ul>
     *   <li>Sneak-right-click: losslessly dismantles the block, drops its block item, and damages
     *       the wrench by 1 point if damageable (non-creative).</li>
     *   <li>Normal right-click on a rotatable block ({@code SmartPortBlock}): rotates horizontal
     *       facing clockwise 90 degrees, refreshes its water berth, and damages the wrench by 1
     *       point if damageable (non-creative).</li>
     *   <li>Normal right-click on a non-rotatable LC2 block: consumes the click without opening GUI.</li>
     * </ul>
     *
     * @return {@code true} if {@code stack} is a third-party wrench and the interaction was handled
     */
    public static boolean tryHandleWrench(
            Block block,
            boolean rotatable,
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand
    ) {
        if (!Lc2Native.isThirdPartyWrench(stack)) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            if (player.isShiftKeyDown()) {
                if (serverLevel.destroyBlock(pos, false, player)) {
                    Block.popResource(serverLevel, pos, new ItemStack(block));
                    Lc2Native.damageWrenchItem(stack, player, hand);
                }
            } else if (rotatable) {
                BlockState rotated = state.rotate(Rotation.CLOCKWISE_90);
                if (!rotated.equals(state)) {
                    serverLevel.setBlock(pos, rotated, Block.UPDATE_ALL);
                    if (serverLevel.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
                        port.refreshWaterBerth(serverLevel, rotated.getValue(SmartPortBlock.FACING));
                    }
                    Lc2Native.damageWrenchItem(stack, player, hand);
                }
            }
        }
        return true;
    }

    /**
     * Low-priority fallback listener for sneak-right-click wrench dismantling when a third-party
     * wrench does not override {@code doesSneakBypassUse} and IC2 did not already cancel the event.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player == null || !player.isShiftKeyDown()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!Lc2Native.isThirdPartyWrench(held)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (!(block instanceof LogisticsPowerBlock)) {
            return;
        }
        boolean rotatable = block instanceof SmartPortBlock;
        if (tryHandleWrench(block, rotatable, held, state, level, pos, player, event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }
}
