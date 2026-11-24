package com.logisticscraft.lc2.forge.item;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.logisticscraft.lc2.forge.util.TerminalSessionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Handheld Operation Terminal item ({@code lc2:operation_terminal}) storing bound Communication Base
 * Stations and an internal EU/FE rechargeable battery ({@value LogisticsConstants#TERMINAL_CAPACITY_EU} EU
 * / {@code 40,000 FE}, LV {@value LogisticsConstants#TERMINAL_PACKET_LIMIT_EU} EU/t = {@code 128 FE/t}).
 */
public class OperationTerminalItem extends Item {

    public static final String TAG_TERMINAL_ID = "lc2TerminalId";
    public static final String TAG_TERMINAL_EU = "lc2TerminalEu";
    public static final String TAG_BINDINGS = "lc2Bindings";

    private static final int BAR_COLOR_CYAN = 0x38D9FF;

    /** Result of attempting to bind a Communication Base Station to a terminal. */
    public enum BindResult {
        BOUND,
        ALREADY_BOUND,
        LIMIT_REACHED
    }

    public OperationTerminalItem(Properties properties) {
        super(properties);
    }

    /** Creates a fully charged ({@value LogisticsConstants#TERMINAL_CAPACITY_EU} EU) terminal stack. */
    public static ItemStack createChargedStack() {
        ItemStack stack = new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get());
        setStoredEu(stack, LogisticsConstants.TERMINAL_CAPACITY_EU);
        return stack;
    }

    // --- Persistent Stack Data Helpers ---

    /** Returns the terminal's persistent UUID string, or {@code ""} if not yet initialized. */
    public static String getTerminalId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_TERMINAL_ID, Tag.TAG_STRING)) {
            return "";
        }
        return tag.getString(TAG_TERMINAL_ID);
    }

    /** Ensures the terminal stack has a persistent UUID string and returns it. */
    public static String getOrCreateTerminalId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        String existing = tag.getString(TAG_TERMINAL_ID);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        tag.putString(TAG_TERMINAL_ID, created);
        return created;
    }

    /** Returns stored EU clamped to {@code [0, TERMINAL_CAPACITY_EU]}. */
    public static double getStoredEu(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_TERMINAL_EU)) {
            return 0.0;
        }
        double raw = tag.getDouble(TAG_TERMINAL_EU);
        if (!Double.isFinite(raw) || raw <= 0.0) {
            return 0.0;
        }
        return Math.min(LogisticsConstants.TERMINAL_CAPACITY_EU, raw);
    }

    /** Writes stored EU clamped to {@code [0, TERMINAL_CAPACITY_EU]}. */
    public static void setStoredEu(ItemStack stack, double eu) {
        double safe = (!Double.isFinite(eu) || eu <= 0.0)
                ? 0.0
                : Math.min(LogisticsConstants.TERMINAL_CAPACITY_EU, eu);
        stack.getOrCreateTag().putDouble(TAG_TERMINAL_EU, safe);
    }

    /** Reads all unique, ordered {@link CommBinding} entries stored on {@code stack}. */
    public static List<CommBinding> getBindings(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_BINDINGS, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag listTag = tag.getList(TAG_BINDINGS, Tag.TAG_COMPOUND);
        Set<CommBinding> unique = new LinkedHashSet<>();
        for (int i = 0; i < listTag.size() && unique.size() < LogisticsConstants.TERMINAL_MAX_BINDINGS; i++) {
            CompoundTag entry = listTag.getCompound(i);
            String dim = entry.getString("dim");
            if (dim == null || dim.isBlank()) {
                continue;
            }
            BlockPos3i pos = new BlockPos3i(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            unique.add(new CommBinding(dim, pos));
        }
        return List.copyOf(unique);
    }

    /** Persists {@code bindings} onto {@code stack}, preserving uniqueness and max binding cap. */
    public static void setBindings(ItemStack stack, List<CommBinding> bindings) {
        ListTag listTag = new ListTag();
        Set<CommBinding> seen = new LinkedHashSet<>();
        if (bindings != null) {
            for (CommBinding binding : bindings) {
                if (binding == null || seen.size() >= LogisticsConstants.TERMINAL_MAX_BINDINGS) {
                    continue;
                }
                if (seen.add(binding)) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("dim", binding.dimensionId());
                    entry.putInt("x", binding.pos().x());
                    entry.putInt("y", binding.pos().y());
                    entry.putInt("z", binding.pos().z());
                    listTag.add(entry);
                }
            }
        }
        stack.getOrCreateTag().put(TAG_BINDINGS, listTag);
    }

    /** Returns {@code true} if {@code stack} is bound to the given dimension and block position. */
    public static boolean isBoundTo(ItemStack stack, String dimensionId, BlockPos pos) {
        if (dimensionId == null || dimensionId.isBlank() || pos == null) {
            return false;
        }
        CommBinding target = new CommBinding(dimensionId, Lc2Coords.toCore(pos));
        return getBindings(stack).contains(target);
    }

    /** Attempts to bind {@code (dimensionId, pos)} onto {@code stack}. */
    public static BindResult bind(ItemStack stack, String dimensionId, BlockPos pos) {
        getOrCreateTerminalId(stack);
        CommBinding target = new CommBinding(dimensionId, Lc2Coords.toCore(pos));
        List<CommBinding> current = new ArrayList<>(getBindings(stack));
        if (current.contains(target)) {
            return BindResult.ALREADY_BOUND;
        }
        if (current.size() >= LogisticsConstants.TERMINAL_MAX_BINDINGS) {
            return BindResult.LIMIT_REACHED;
        }
        current.add(target);
        setBindings(stack, current);
        return BindResult.BOUND;
    }

    /** Removes {@code (dimensionId, pos)} from {@code stack}'s bindings; returns {@code true} if removed. */
    public static boolean unbind(ItemStack stack, String dimensionId, BlockPos pos) {
        CommBinding target = new CommBinding(dimensionId, Lc2Coords.toCore(pos));
        List<CommBinding> current = new ArrayList<>(getBindings(stack));
        boolean removed = current.remove(target);
        if (removed) {
            setBindings(stack, current);
        }
        return removed;
    }

    // --- Comm Base Station Binding Interaction ---

    /**
     * Handles binding (normal right-click) or unbinding (sneak-right-click) against a
     * {@link CommBaseStationBlock} on the server and displays localized actionbar feedback.
     */
    public static void interactWithCommStation(
            ServerLevel level,
            BlockPos pos,
            Player player,
            ItemStack stack
    ) {
        String dimId = level.dimension().location().toString();
        if (player.isShiftKeyDown()) {
            if (unbind(stack, dimId, pos)) {
                player.displayClientMessage(
                        Component.translatable("status.lc2.terminal.unbound", pos.getX(), pos.getY(), pos.getZ()),
                        true
                );
            } else {
                player.displayClientMessage(Component.translatable("status.lc2.terminal.not_bound"), true);
            }
            return;
        }

        BindResult result = bind(stack, dimId, pos);
        switch (result) {
            case BOUND -> player.displayClientMessage(
                    Component.translatable(
                            "status.lc2.terminal.bound",
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            getBindings(stack).size(),
                            LogisticsConstants.TERMINAL_MAX_BINDINGS
                    ),
                    true
            );
            case ALREADY_BOUND -> player.displayClientMessage(
                    Component.translatable("status.lc2.terminal.already_bound", pos.getX(), pos.getY(), pos.getZ()),
                    true
            );
            case LIMIT_REACHED -> player.displayClientMessage(
                    Component.translatable("status.lc2.terminal.limit_reached", LogisticsConstants.TERMINAL_MAX_BINDINGS),
                    true
            );
        }
    }

    // --- Item Overrides ---

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).getBlock() instanceof CommBaseStationBlock) {
            Player player = context.getPlayer();
            if (level instanceof ServerLevel serverLevel && player != null) {
                interactWithCommStation(serverLevel, pos, player, context.getItemInHand());
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            TerminalSessionService.sendTerminalSync(serverPlayer, stack, true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void fillItemCategory(CreativeModeTab category, NonNullList<ItemStack> items) {
        if (allowedIn(category)) {
            items.add(new ItemStack(this));
            items.add(createChargedStack());
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double ratio = getStoredEu(stack) / LogisticsConstants.TERMINAL_CAPACITY_EU;
        return Mth.clamp((int) Math.round(13.0 * ratio), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOR_CYAN;
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSame(oldStack, newStack);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.translatable("tooltip.lc2.operation_terminal.line1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.lc2.operation_terminal.line2").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.lc2.operation_terminal.line3").withStyle(ChatFormatting.DARK_AQUA));

        double eu = getStoredEu(stack);
        double maxEu = LogisticsConstants.TERMINAL_CAPACITY_EU;
        tooltip.add(Component.translatable(
                "tooltip.lc2.operation_terminal.energy",
                (long) Math.floor(eu),
                (long) Math.floor(maxEu),
                EnergyUnits.euToFeFloor(eu),
                EnergyUnits.euToFeFloor(maxEu)
        ).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable(
                "tooltip.lc2.operation_terminal.bound_count",
                getBindings(stack).size()
        ).withStyle(ChatFormatting.GOLD));

        super.appendHoverText(stack, level, tooltip, flag);
    }

    @Override
    @Nullable
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new TerminalEnergyProvider(stack);
    }

    private static final class TerminalEnergyProvider implements ICapabilityProvider {

        private final LazyOptional<IEnergyStorage> holder;

        private TerminalEnergyProvider(ItemStack stack) {
            IEnergyStorage storage = new IEnergyStorage() {
                @Override
                public int receiveEnergy(int maxReceive, boolean simulate) {
                    if (stack.getCount() != 1) {
                        return 0;
                    }
                    EnergyUnits.FeTransferResult res = EnergyUnits.receiveFeIntoEuBuffer(
                            getStoredEu(stack),
                            LogisticsConstants.TERMINAL_CAPACITY_EU,
                            LogisticsConstants.TERMINAL_PACKET_LIMIT_EU,
                            maxReceive,
                            simulate
                    );
                    if (!simulate && res.acceptedFe() > 0) {
                        setStoredEu(stack, res.newStoredEu());
                    }
                    return res.acceptedFe();
                }

                @Override
                public int extractEnergy(int maxExtract, boolean simulate) {
                    return 0;
                }

                @Override
                public int getEnergyStored() {
                    return EnergyUnits.euToFeFloor(getStoredEu(stack));
                }

                @Override
                public int getMaxEnergyStored() {
                    return EnergyUnits.euToFeFloor(LogisticsConstants.TERMINAL_CAPACITY_EU);
                }

                @Override
                public boolean canExtract() {
                    return false;
                }

                @Override
                public boolean canReceive() {
                    return true;
                }
            };
            this.holder = LazyOptional.of(() -> storage);
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
            if (cap == ForgeCapabilities.ENERGY) {
                return this.holder.cast();
            }
            return LazyOptional.empty();
        }
    }
}
