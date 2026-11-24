package com.logisticscraft.lc2.item;

import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.network.Lc2Network;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2Nbt;
import com.logisticscraft.lc2.platform.Lc2UsableTooltipItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Handheld Operation Terminal ({@code lc2:operation_terminal}) storing an EU/FE rechargeable
 * battery (10,000 EU = 40,000 FE, LV 32 EU/t), a stable terminal UUID, and up to 256 bound
 * Communication Base Stations.
 */
public final class OperationTerminalItem extends Lc2UsableTooltipItem {

    public static final String KEY_TERMINAL_ID = "lc2TerminalId";
    public static final String KEY_EU = "lc2TerminalEu";
    public static final String KEY_BINDINGS = "lc2Bindings";
    private static final String KEY_DIM = "dim";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final int BAR_COLOR_CYAN = 0x38D9FF;

    /** Result of attempting to bind a Communication Base Station to a terminal stack. */
    public enum BindResult {
        BOUND,
        ALREADY_BOUND,
        LIMIT_REACHED
    }

    public OperationTerminalItem(Properties properties, List<String> tooltipKeys) {
        super(properties, tooltipKeys);
    }

    /** Creates a fully charged Operation Terminal stack (10,000 EU) for the creative tab. */
    public static ItemStack createChargedStack(Item item) {
        ItemStack stack = new ItemStack(item);
        setStoredEu(stack, LogisticsConstants.TERMINAL_CAPACITY_EU);
        return stack;
    }

    /** Returns the terminal's persisted UUID string, or empty string if not yet initialized. */
    public static String getTerminalId(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return "";
        }
        return Lc2Nbt.getString(Lc2Native.copyCustomData(stack), KEY_TERMINAL_ID, "");
    }

    /** Ensures the terminal stack carries a stable UUID string and returns it. */
    public static String ensureTerminalId(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return "";
        }
        String existing = getTerminalId(stack);
        if (!existing.isBlank()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        Lc2Native.updateCustomData(stack, tag -> tag.putString(KEY_TERMINAL_ID, created));
        return created;
    }

    /** Returns the terminal stack's stored EU clamped to {@code [0, 10,000]}. */
    public static double getStoredEu(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return 0.0;
        }
        double eu = Lc2Nbt.getDouble(Lc2Native.copyCustomData(stack), KEY_EU, 0.0);
        return Double.isFinite(eu)
                ? Math.clamp(eu, 0.0, LogisticsConstants.TERMINAL_CAPACITY_EU)
                : 0.0;
    }

    /** Persists the terminal stack's stored EU clamped to {@code [0, 10,000]}. */
    public static void setStoredEu(ItemStack stack, double eu) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return;
        }
        double clamped = Double.isFinite(eu)
                ? Math.clamp(eu, 0.0, LogisticsConstants.TERMINAL_CAPACITY_EU)
                : 0.0;
        Lc2Native.updateCustomData(stack, tag -> tag.putDouble(KEY_EU, clamped));
    }

    /** Returns the stored energy in FE (floor). */
    public static int getStoredFe(ItemStack stack) {
        return EnergyUnits.euToFeFloor(getStoredEu(stack));
    }

    /** Returns the maximum energy capacity in FE ({@code 40,000}). */
    public static int getMaxFe() {
        return EnergyUnits.euToFeFloor(LogisticsConstants.TERMINAL_CAPACITY_EU);
    }

    /**
     * Charges the terminal via IC2 EU (LV tier &gt;= 1, clamped to 32 EU/t and 10,000 EU capacity).
     *
     * @return accepted EU
     */
    public static double chargeEu(ItemStack stack, double amount, int tier) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof OperationTerminalItem)
                || stack.getCount() != 1
                || tier < LogisticsConstants.TERMINAL_INPUT_TIER
                || !Double.isFinite(amount)
                || amount <= 0.0) {
            return 0.0;
        }
        double offered = Math.min(amount, LogisticsConstants.TERMINAL_PACKET_LIMIT_EU);
        EnergyUnits.EuTransferResult result = EnergyUnits.receiveEuIntoBuffer(
                getStoredEu(stack),
                LogisticsConstants.TERMINAL_CAPACITY_EU,
                offered
        );
        if (result.acceptedEu() > 0.0) {
            setStoredEu(stack, result.newStoredEu());
        }
        return result.acceptedEu();
    }

    /**
     * Charges the terminal via Forge/NeoForge Energy (FE) converted at {@code 4 FE = 1 EU},
     * capped at LV 128 FE/t.
     *
     * @return accepted FE
     */
    public static int receiveFe(ItemStack stack, int offeredFe, boolean simulate) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof OperationTerminalItem)
                || stack.getCount() != 1
                || offeredFe <= 0) {
            return 0;
        }
        EnergyUnits.FeTransferResult result = EnergyUnits.receiveFeIntoEuBuffer(
                getStoredEu(stack),
                LogisticsConstants.TERMINAL_CAPACITY_EU,
                LogisticsConstants.TERMINAL_PACKET_LIMIT_EU,
                offeredFe,
                simulate
        );
        if (!simulate && result.acceptedFe() > 0) {
            setStoredEu(stack, result.newStoredEu());
        }
        return result.acceptedFe();
    }

    /** Deducts the remote dispatch surcharge ({@code 100 EU}) from the terminal stack. */
    public static void deductDispatchCost(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return;
        }
        setStoredEu(stack, EnergyUnits.deductTerminalDispatch(getStoredEu(stack)));
    }

    /** Returns the ordered, deduplicated list of Communication Base Station bindings on {@code stack}. */
    public static List<CommBinding> getBindings(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof OperationTerminalItem)) {
            return List.of();
        }
        CompoundTag root = Lc2Native.copyCustomData(stack);
        List<CompoundTag> list = Lc2Nbt.getCompoundList(root, KEY_BINDINGS);
        if (list.isEmpty()) {
            return List.of();
        }
        List<CommBinding> result = new ArrayList<>(Math.min(list.size(), LogisticsConstants.TERMINAL_MAX_BINDINGS));
        for (CompoundTag entry : list) {
            if (result.size() >= LogisticsConstants.TERMINAL_MAX_BINDINGS) {
                break;
            }
            String dim = Lc2Nbt.getString(entry, KEY_DIM, "");
            if (dim.isBlank()) {
                continue;
            }
            BlockPos3i pos = new BlockPos3i(
                    Lc2Nbt.getInt(entry, KEY_X, 0),
                    Lc2Nbt.getInt(entry, KEY_Y, 0),
                    Lc2Nbt.getInt(entry, KEY_Z, 0)
            );
            CommBinding binding = new CommBinding(dim, pos);
            if (!result.contains(binding)) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    private static void saveBindings(ItemStack stack, List<CommBinding> bindings) {
        ListTag listTag = new ListTag();
        int cap = Math.min(bindings.size(), LogisticsConstants.TERMINAL_MAX_BINDINGS);
        for (int i = 0; i < cap; i++) {
            CommBinding b = bindings.get(i);
            CompoundTag entry = new CompoundTag();
            entry.putString(KEY_DIM, b.dimensionId());
            entry.putInt(KEY_X, b.pos().x());
            entry.putInt(KEY_Y, b.pos().y());
            entry.putInt(KEY_Z, b.pos().z());
            listTag.add(entry);
        }
        Lc2Native.updateCustomData(stack, tag -> tag.put(KEY_BINDINGS, listTag));
    }

    /** Returns {@code true} if {@code stack} is bound to {@code (dimensionId, pos)}. */
    public static boolean isBoundTo(ItemStack stack, String dimensionId, BlockPos pos) {
        if (dimensionId == null || pos == null) {
            return false;
        }
        CommBinding target = new CommBinding(dimensionId, new BlockPos3i(pos.getX(), pos.getY(), pos.getZ()));
        return getBindings(stack).contains(target);
    }

    /** Attempts to bind {@code (dimensionId, pos)} to {@code stack}. */
    public static BindResult bindCommStation(ItemStack stack, String dimensionId, BlockPos pos) {
        ensureTerminalId(stack);
        CommBinding target = new CommBinding(dimensionId, new BlockPos3i(pos.getX(), pos.getY(), pos.getZ()));
        List<CommBinding> current = new ArrayList<>(getBindings(stack));
        if (current.contains(target)) {
            return BindResult.ALREADY_BOUND;
        }
        if (current.size() >= LogisticsConstants.TERMINAL_MAX_BINDINGS) {
            return BindResult.LIMIT_REACHED;
        }
        current.add(target);
        saveBindings(stack, current);
        return BindResult.BOUND;
    }

    /** Attempts to remove {@code (dimensionId, pos)} from {@code stack}'s bindings. */
    public static boolean unbindCommStation(ItemStack stack, String dimensionId, BlockPos pos) {
        CommBinding target = new CommBinding(dimensionId, new BlockPos3i(pos.getX(), pos.getY(), pos.getZ()));
        List<CommBinding> current = new ArrayList<>(getBindings(stack));
        if (!current.remove(target)) {
            return false;
        }
        saveBindings(stack, current);
        return true;
    }

    /**
     * Handles binding (normal right-click) and unbinding (sneak-right-click) against a
     * {@link CommBaseStationBlock}, emitting localized actionbar feedback.
     */
    public static void interactWithCommStation(
            ItemStack stack,
            ServerLevel level,
            BlockPos pos,
            Player player
    ) {
        String dim = Lc2Native.dimensionId(level);
        if (player.isShiftKeyDown()) {
            if (unbindCommStation(stack, dim, pos)) {
                Lc2Native.sendActionBar(
                        player,
                        Component.translatable("status.lc2.terminal.unbound", pos.getX(), pos.getY(), pos.getZ())
                );
            } else {
                Lc2Native.sendActionBar(player, Component.translatable("status.lc2.terminal.not_bound"));
            }
            return;
        }

        BindResult result = bindCommStation(stack, dim, pos);
        if (result == BindResult.BOUND) {
            int count = getBindings(stack).size();
            Lc2Native.sendActionBar(
                    player,
                    Component.translatable(
                            "status.lc2.terminal.bound",
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            count,
                            LogisticsConstants.TERMINAL_MAX_BINDINGS
                    )
            );
        } else if (result == BindResult.ALREADY_BOUND) {
            Lc2Native.sendActionBar(
                    player,
                    Component.translatable("status.lc2.terminal.already_bound", pos.getX(), pos.getY(), pos.getZ())
            );
        } else {
            Lc2Native.sendActionBar(
                    player,
                    Component.translatable("status.lc2.terminal.limit_reached", LogisticsConstants.TERMINAL_MAX_BINDINGS)
            );
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).getBlock() instanceof CommBaseStationBlock) {
            if (level instanceof ServerLevel serverLevel && context.getPlayer() != null) {
                interactWithCommStation(context.getItemInHand(), serverLevel, pos, context.getPlayer());
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected InteractionResult onItemUse(
            Level level,
            Player player,
            InteractionHand hand,
            ItemStack stack
    ) {
        if (player instanceof ServerPlayer serverPlayer) {
            Lc2Network.openTerminalSelection(serverPlayer, stack, true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double ratio = Math.clamp(getStoredEu(stack) / LogisticsConstants.TERMINAL_CAPACITY_EU, 0.0, 1.0);
        return Math.round(13.0F * (float) ratio);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOR_CYAN;
    }

    @Override
    protected void appendExtraHoverText(ItemStack stack, Consumer<Component> tooltipAdder) {
        double eu = getStoredEu(stack);
        double maxEu = LogisticsConstants.TERMINAL_CAPACITY_EU;
        tooltipAdder.accept(
                Component.translatable(
                        "tooltip.lc2.operation_terminal.energy",
                        Math.round(eu),
                        Math.round(maxEu),
                        EnergyUnits.euToFeFloor(eu),
                        EnergyUnits.euToFeFloor(maxEu)
                ).withStyle(ChatFormatting.YELLOW)
        );
        tooltipAdder.accept(
                Component.translatable(
                        "tooltip.lc2.operation_terminal.bound_count",
                        getBindings(stack).size()
                ).withStyle(ChatFormatting.GREEN)
        );
    }
}
