package com.logisticscraft.lc2.platform;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge 1.21.1 BlockItem with localized {@code tooltip.trera.*} hover text lines.
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
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        for (int i = 0; i < tooltipKeys.size(); i++) {
            ChatFormatting color = i == 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY;
            tooltipComponents.add(Component.translatable(tooltipKeys.get(i)).withStyle(color));
        }
    }
}
