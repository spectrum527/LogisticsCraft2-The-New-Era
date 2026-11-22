package com.logisticscraft.lc2.forge.item;

import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placement item for {@link ElectricCargoBoatEntity}. Can be used directly on water or
 * right-clicked onto a Smart Port block to dock immediately in its water berth.
 */
public class ElectricCargoBoatItem extends Lc2TooltipItem {

    public ElectricCargoBoatItem(Properties properties) {
        super(
                properties,
                "tooltip.lc2.electric_cargo_boat.line1",
                "tooltip.lc2.electric_cargo_boat.line2",
                "tooltip.lc2.electric_cargo_boat.line3"
        );
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK
                || !level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
            return InteractionResultHolder.pass(stack);
        }

        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(
                Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(),
                level
        );
        Vec3 pos = hit.getLocation();
        boat.setPos(pos.x, pos.y, pos.z);
        boat.setYRot(player.getYRot());
        boat.yRotO = player.getYRot();

        if (!level.noCollision(boat, boat.getBoundingBox())) {
            return InteractionResultHolder.fail(stack);
        }
        if (level instanceof ServerLevel) {
            if (!level.addFreshEntity(boat)) {
                return InteractionResultHolder.fail(stack);
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
