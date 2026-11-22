package com.logisticscraft.lc2.mixin;

import com.industrialage.ia2.gameplay.CableBlock;
import com.logisticscraft.lc2.block.LogisticsPowerBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks IC2's {@link CableBlock#updateConnection} so IC2 power cables visually connect their arms
 * to LC2 blocks implementing {@link LogisticsPowerBlock}.
 * <p>
 * Intentionally avoids enum {@code switch} statements to prevent javac from generating a synthetic
 * {@code CableBlockMixin$1} inner class inside the Mixin package.
 */
@Mixin(value = CableBlock.class, remap = false)
public abstract class CableBlockMixin {

    @Inject(method = "updateConnection", at = @At("RETURN"), cancellable = true)
    private void lc2$connectLogisticsPowerBlock(
            BlockState state,
            Direction side,
            BlockState neighbor,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (neighbor.getBlock() instanceof LogisticsPowerBlock) {
            BooleanProperty prop;
            if (side == Direction.DOWN) {
                prop = BlockStateProperties.DOWN;
            } else if (side == Direction.UP) {
                prop = BlockStateProperties.UP;
            } else if (side == Direction.NORTH) {
                prop = BlockStateProperties.NORTH;
            } else if (side == Direction.SOUTH) {
                prop = BlockStateProperties.SOUTH;
            } else if (side == Direction.WEST) {
                prop = BlockStateProperties.WEST;
            } else if (side == Direction.EAST) {
                prop = BlockStateProperties.EAST;
            } else {
                return;
            }
            BlockState current = cir.getReturnValue();
            if (current != null && current.hasProperty(prop) && !current.getValue(prop)) {
                cir.setReturnValue(current.setValue(prop, true));
            }
        }
    }
}
