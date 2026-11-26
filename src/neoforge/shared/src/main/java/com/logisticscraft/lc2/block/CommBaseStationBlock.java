package com.logisticscraft.lc2.block;

import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2StationBlockBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Communication Base Station block ({@code lc2:comm_base_station}) linking face-adjacent
 * Smart Ports and Cargo Stations to the remote Operation Terminal control network.
 */
public final class CommBaseStationBlock extends Lc2StationBlockBase
        implements WrenchTarget, LogisticsPowerBlock {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0, 0.0, 0.0, 16.0, 9.0, 16.0),
            Block.box(4.0, 9.0, 4.0, 12.0, 16.0, 12.0)
    );

    public CommBaseStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return SHAPE;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CommBaseStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (!(level instanceof ServerLevel) || type != Lc2Registries.COMM_BASE_STATION_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> ((CommBaseStationBlockEntity) be).serverTick((ServerLevel) lvl);
    }

    @Override
    protected boolean handleUseWithItem(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (stack.getItem() instanceof OperationTerminalItem) {
            if (level instanceof ServerLevel serverLevel) {
                OperationTerminalItem.interactWithCommStation(stack, serverLevel, pos, player);
            }
            return true;
        }
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CommBaseStationBlockEntity comm) {
            boolean online = comm.isOnline();
            String statusKey = online
                    ? "status.lc2.terminal.comm_status_online"
                    : "status.lc2.terminal.comm_status_offline";
            MutableComponent statusLine = Component.translatable(
                    statusKey,
                    Math.round(comm.getStoredEu()),
                    Math.round(comm.getMaxEu()),
                    comm.getStoredFe(),
                    comm.getMaxFe()
            );
            Component adjacentSummary = comm.formatAdjacentComponent(serverLevel);
            Component adjacentLine = adjacentSummary == null
                    ? Component.translatable("status.lc2.terminal.comm_adjacent_none")
                    : Component.translatable("status.lc2.terminal.comm_adjacent_list", adjacentSummary);
            Lc2Native.sendActionBar(player, statusLine.append(Component.literal(" | ")).append(adjacentLine));
        }
        return InteractionResult.SUCCESS;
    }
}
