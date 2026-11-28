package com.logisticscraft.lc2.platform;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge 26.x BlockItem with localized {@code tooltip.trera.*} hover text lines.
 */
public class Lc2TooltipBlockItem extends BlockItem {

    private final List<String> tooltipKeys;

    public Lc2TooltipBlockItem(Block block, Properties properties, List<String> tooltipKeys) {
        super(block, properties);
        this.tooltipKeys = List.copyOf(tooltipKeys);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> tooltipAdder,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, display, tooltipAdder, flag);
        for (int i = 0; i < tooltipKeys.size(); i++) {
            ChatFormatting color = i == 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY;
            tooltipAdder.accept(Component.translatable(tooltipKeys.get(i)).withStyle(color));
        }
    }
}
