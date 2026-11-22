package com.logisticscraft.lc2.forge.item;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * BlockItem for LogisticsCraft² machines with localized multi-line tooltips.
 */
public class Lc2BlockItem extends BlockItem {

    private final List<String> tooltipKeys;

    public Lc2BlockItem(Block block, Properties properties, String... tooltipKeys) {
        super(block, properties);
        this.tooltipKeys = List.of(tooltipKeys);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        for (int i = 0; i < tooltipKeys.size(); i++) {
            ChatFormatting color = i == 0 ? ChatFormatting.GRAY : ChatFormatting.DARK_AQUA;
            tooltip.add(Component.translatable(tooltipKeys.get(i)).withStyle(color));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
