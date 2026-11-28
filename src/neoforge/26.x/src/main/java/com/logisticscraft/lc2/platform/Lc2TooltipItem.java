package com.logisticscraft.lc2.platform;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * NeoForge 26.x Item with localized {@code tooltip.trera.*} hover text lines.
 */
public class Lc2TooltipItem extends Item {

    private final List<String> tooltipKeys;

    public Lc2TooltipItem(Properties properties, List<String> tooltipKeys) {
        super(properties);
        this.tooltipKeys = List.copyOf(tooltipKeys);
    }

    /** Subclass hook for appending dynamic per-stack tooltip lines after static localized lines. */
    protected void appendExtraHoverText(ItemStack stack, Consumer<Component> tooltipAdder) {
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
        appendExtraHoverText(stack, tooltipAdder);
    }
}
