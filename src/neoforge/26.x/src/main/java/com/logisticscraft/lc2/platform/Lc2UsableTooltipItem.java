package com.logisticscraft.lc2.platform;

import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * NeoForge 26.x bridge adapting {@link InteractionResult} for shared right-click items.
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
    public final InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return onItemUse(level, player, hand, stack);
    }
}
