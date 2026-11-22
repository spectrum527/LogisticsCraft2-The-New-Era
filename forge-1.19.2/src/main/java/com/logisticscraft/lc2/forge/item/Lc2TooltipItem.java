package com.logisticscraft.lc2.forge.item;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Standard LogisticsCraft² item with localized multi-line tooltips from {@code tooltip.lc2.*}.
 */
public class Lc2TooltipItem extends Item {

    private final List<String> tooltipKeys;

    public Lc2TooltipItem(Properties properties, String... tooltipKeys) {
        super(properties);
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
