package com.logisticscraft.lc2.forge.mixin;

import com.industrialage.ia2.audio.IndustrialSounds;
import com.industrialage.ia2.content.PortableEquipment;
import com.industrialage.ia2.forge.equipment.ForgeGear;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Integrates {@link OperationTerminalItem} with IndustrialCraft² Forge 1.19.2's {@link ForgeGear}
 * charging pipeline (BatBox/CESU/MFE/MFSU/Generator slots, ChargePads, BatPacks, Charging Batteries,
 * and manual right-click battery charging).
 */
@Mixin(value = ForgeGear.class, remap = false)
public abstract class ForgeGearMixin {

    @Shadow(remap = false)
    private static PortableEquipment type(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    public static double stored(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private static void setStored(ItemStack stack, double energy) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    public static double charge(ItemStack stack, double amount, int tier) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private static void transfer(ItemStack source, ItemStack target) {
        throw new AssertionError();
    }

    @Inject(method = "acceptsCharge", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$acceptsCharge(ItemStack stack, int tier, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(tier >= LogisticsConstants.TERMINAL_INPUT_TIER);
        }
    }

    @Inject(method = "charge", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$charge(ItemStack stack, double amount, int tier, CallbackInfoReturnable<Double> cir) {
        if (!(stack.getItem() instanceof OperationTerminalItem)) {
            return;
        }
        if (stack.getCount() != 1
                || tier < LogisticsConstants.TERMINAL_INPUT_TIER
                || !Double.isFinite(amount)
                || amount <= 0.0) {
            cir.setReturnValue(0.0);
            return;
        }
        double clampedOffer = Math.min(amount, LogisticsConstants.TERMINAL_PACKET_LIMIT_EU);
        EnergyUnits.EuTransferResult res = EnergyUnits.receiveEuIntoBuffer(
                OperationTerminalItem.getStoredEu(stack),
                LogisticsConstants.TERMINAL_CAPACITY_EU,
                clampedOffer
        );
        if (res.acceptedEu() > 0.0) {
            OperationTerminalItem.setStoredEu(stack, res.newStoredEu());
        }
        cir.setReturnValue(res.acceptedEu());
    }

    @Inject(method = "transfer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$transfer(ItemStack source, ItemStack target, CallbackInfo ci) {
        if (!(target.getItem() instanceof OperationTerminalItem)) {
            return;
        }
        if (target.getCount() == 1) {
            PortableEquipment src = type(source);
            if (src != null && src.canProvide() && src.tier >= LogisticsConstants.TERMINAL_INPUT_TIER) {
                if (src.kind == PortableEquipment.Kind.DISPOSABLE) {
                    double current = OperationTerminalItem.getStoredEu(target);
                    if (LogisticsConstants.TERMINAL_CAPACITY_EU - current >= src.capacity) {
                        OperationTerminalItem.setStoredEu(target, current + src.capacity);
                        source.shrink(1);
                    }
                } else {
                    double sourceStored = stored(source);
                    double offer = Math.min(src.rate, sourceStored);
                    if (offer > 0.0) {
                        double accepted = charge(target, offer, src.tier);
                        if (accepted > 0.0) {
                            setStored(source, Math.max(0.0, sourceStored - accepted));
                        }
                    }
                }
            }
        }
        ci.cancel();
    }

    @Inject(method = "manualTransfer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$manualTransfer(PlayerInteractEvent.RightClickItem event, CallbackInfo ci) {
        Player player = event.getEntity();
        InteractionHand otherHand = event.getHand() == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        ItemStack other = player.getItemInHand(otherHand);
        if (!(other.getItem() instanceof OperationTerminalItem) || other.getCount() != 1) {
            return;
        }
        ItemStack held = event.getItemStack();
        PortableEquipment src = type(held);
        if (src == null
                || !src.canProvide()
                || src.tier < LogisticsConstants.TERMINAL_INPUT_TIER
                || stored(held) <= 0.0
                || OperationTerminalItem.getStoredEu(other) >= LogisticsConstants.TERMINAL_CAPACITY_EU) {
            return;
        }
        if (player.getLevel() instanceof ServerLevel serverLevel) {
            double before = OperationTerminalItem.getStoredEu(other);
            transfer(held, other);
            if (OperationTerminalItem.getStoredEu(other) > before) {
                IndustrialSounds.play(serverLevel, player.blockPosition(), "item.battery.use");
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        ci.cancel();
    }

    @Inject(method = "stored", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$stored(ItemStack stack, CallbackInfoReturnable<Double> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(OperationTerminalItem.getStoredEu(stack));
        }
    }

    @Inject(method = "capacity", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2$capacity(ItemStack stack, CallbackInfoReturnable<Double> cir) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            cir.setReturnValue(LogisticsConstants.TERMINAL_CAPACITY_EU);
        }
    }
}
