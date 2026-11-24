package com.logisticscraft.lc2.platform;

import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * NeoForge 1.21.1 bridge adapting {@link InteractionResultHolder} for shared right-click items.
 */
public abstract class Lc2UsableTooltipItem extends Lc2TooltipItem {

    protected Lc2UsableTooltipItem(Properties properties, List<String> tooltipKeys) {
        super(properties, tooltipKeys);
    }

    /** Version-neutral right-click item handler invoked from {@link #use}. */
    protected abstract InteractionResult onItemUse(
            Level level,
            Player player,
            InteractionHand hand,
            ItemStack stack
    );

    @Override
    public final InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        InteractionResult result = onItemUse(level, player, hand, stack);
        if (result == InteractionResult.SUCCESS || result == InteractionResult.CONSUME) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (result == InteractionResult.FAIL) {
            return InteractionResultHolder.fail(stack);
        }
        return InteractionResultHolder.pass(stack);
    }
}
