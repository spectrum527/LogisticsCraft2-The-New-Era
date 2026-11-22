package com.logisticscraft.lc2.item;

import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.platform.Lc2TooltipItem;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge 1.21.1 placement item for {@link ElectricCargoBoatEntity}.
 */
public final class ElectricCargoBoatItem extends Lc2TooltipItem {

    public ElectricCargoBoatItem(Properties properties, List<String> tooltipKeys) {
        super(properties, tooltipKeys);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        var hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK
                || !level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
            return InteractionResultHolder.pass(stack);
        }
        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                level
        );
        Vec3 loc = hit.getLocation();
        boat.setPos(loc.x, loc.y, loc.z);
        boat.setYRot(player.getYRot());
        if (!level.noCollision(boat, boat.getBoundingBox())) {
            return InteractionResultHolder.fail(stack);
        }
        if (level instanceof ServerLevel && level.addFreshEntity(boat)) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
