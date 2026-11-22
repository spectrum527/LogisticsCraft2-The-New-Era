package com.logisticscraft.lc2.forge.mixin;

import com.industrialage.ia2.forge.content.CableBlock;
import com.logisticscraft.lc2.forge.block.LogisticsPowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allows IC2 cables to visually connect arms to LogisticsCraft² powered stations.
 */
@Mixin(value = CableBlock.class, remap = false)
public class CableBlockMixin {

    @Inject(method = "connects", at = @At("RETURN"), cancellable = true, remap = false)
    private void lc2$connectsToLogisticsPowerBlock(
            BlockState state,
            BlockState neighbor,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ() && neighbor.getBlock() instanceof LogisticsPowerBlock) {
            cir.setReturnValue(true);
        }
    }
}
