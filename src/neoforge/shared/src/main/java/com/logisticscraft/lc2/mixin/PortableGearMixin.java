package com.logisticscraft.lc2.mixin;

import com.industrialage.ia2.audio.IndustrialSounds;
import com.industrialage.ia2.content.PortableEquipment;
import com.industrialage.ia2.gameplay.PortableGear;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks IC2's {@link PortableGear} so {@link OperationTerminalItem} charges seamlessly in IC2
 * storage blocks (BatBox/CESU/MFE/MFSU), ChargePads, BatPacks, Charging Batteries, and
 * right-click offhand battery transfers.
 * <p>
 * Intentionally avoids enum {@code switch} statements to prevent javac from generating a synthetic
 * {@code PortableGearMixin$1} inner class inside the Mixin package.
 */
@Mixin(value = PortableGear.class, remap = false)
public abstract class PortableGearMixin {

    @Shadow
    private static PortableEquipment type(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    public static double stored(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    private static void setStored(ItemStack stack, double energy) {
        throw new AssertionError();
    }

    @Unique
    private static void lc2$transferIntoTerminal(PortableEquipment src, ItemStack source, ItemStack target) {
        if (src == null || !src.canProvide() || src.tier < LogisticsConstants.TERMINAL_INPUT_TIER) {
            return;
        }
        if (src.kind == PortableEquipment.Kind.DISPOSABLE) {
            double current = OperationTerminalItem.getStoredEu(target);
            double room = LogisticsConstants.TERMINAL_CAPACITY_EU - current;
            if (room >= (double) src.capacity) {
                OperationTerminalItem.setStoredEu(target, current + (double) src.capacity);
                source.shrink(1);
            }
            return;
        }
        double available = stored(source);
        if (available <= 0.0) {
            return;
        }
        double offer = Math.min((double) src.rate, available);
        double accepted = OperationTerminalItem.chargeEu(target, offer, src.tier);
        if (accepted > 0.0) {
            setStored(source, Math.max(0.0, available - accepted));
        }
    }

    @Inject(method = "acceptsCharge", at = @At("HEAD"), cancellable = true)
    private static void lc2$acceptsChargeTerminal(
            ItemStack stack,
            int tier,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(tier >= LogisticsConstants.TERMINAL_INPUT_TIER);
        }
    }

    @Inject(method = "charge", at = @At("HEAD"), cancellable = true)
    private static void lc2$chargeTerminal(
            ItemStack stack,
            double amount,
            int tier,
            CallbackInfoReturnable<Double> cir
    ) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(OperationTerminalItem.chargeEu(stack, amount, tier));
        }
    }

    @Inject(method = "transfer", at = @At("HEAD"), cancellable = true)
    private static void lc2$transferToTerminal(
            ItemStack source,
            ItemStack target,
            CallbackInfo ci
    ) {
        if (target.getItem() instanceof OperationTerminalItem && target.getCount() == 1) {
            lc2$transferIntoTerminal(type(source), source, target);
            ci.cancel();
        }
    }

    @Inject(method = "manualTransfer", at = @At("HEAD"), cancellable = true)
    private static void lc2$manualTransferToTerminal(
            PlayerInteractEvent.RightClickItem event,
            CallbackInfo ci
    ) {
        ItemStack held = event.getItemStack();
        PortableEquipment src = type(held);
        if (src == null || !src.canProvide() || src.tier < LogisticsConstants.TERMINAL_INPUT_TIER) {
            return;
        }
        Player player = event.getEntity();
        ItemStack other = event.getHand() == InteractionHand.MAIN_HAND
                ? player.getOffhandItem()
                : player.getMainHandItem();
        if (!(other.getItem() instanceof OperationTerminalItem) || other.getCount() != 1) {
            return;
        }
        double currentEu = OperationTerminalItem.getStoredEu(other);
        if (stored(held) <= 0.0 || currentEu >= LogisticsConstants.TERMINAL_CAPACITY_EU) {
            return;
        }
        if (src.kind == PortableEquipment.Kind.DISPOSABLE
                && LogisticsConstants.TERMINAL_CAPACITY_EU - currentEu < (double) src.capacity) {
            return;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            double before = currentEu;
            lc2$transferIntoTerminal(src, held, other);
            if (OperationTerminalItem.getStoredEu(other) > before) {
                IndustrialSounds.play(serverLevel, player.blockPosition(), "item.battery.use");
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        ci.cancel();
    }

    @Inject(method = "chargeComplete", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lc2$chargeCompleteTerminal(
            ItemStack stack,
            int tier,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(
                    tier >= LogisticsConstants.TERMINAL_INPUT_TIER
                            && OperationTerminalItem.getStoredEu(stack) >= LogisticsConstants.TERMINAL_CAPACITY_EU - 1e-6
            );
        }
    }

    @Inject(method = "electricalItem", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lc2$electricalItemTerminal(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "powerSlotLimit", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lc2$powerSlotLimitTerminal(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(1);
        }
    }

    @Inject(method = "stored", at = @At("HEAD"), cancellable = true)
    private static void lc2$storedTerminal(ItemStack stack, CallbackInfoReturnable<Double> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(OperationTerminalItem.getStoredEu(stack));
        }
    }

    @Inject(method = "capacity", at = @At("HEAD"), cancellable = true)
    private static void lc2$capacityTerminal(ItemStack stack, CallbackInfoReturnable<Double> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(LogisticsConstants.TERMINAL_CAPACITY_EU);
        }
    }
}
