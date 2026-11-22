package com.logisticscraft.lc2.block;

import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2StationBlockBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Smart Port block ({@code lc2:smart_port}) with {@code facing} (horizontal) and {@code active}
 * properties matching {@code docs/DRONE_MODEL_UV.md}.
 */
public final class SmartPortBlock extends Lc2StationBlockBase
        implements WrenchTarget, LogisticsPowerBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = AbstractLogisticsStationBlockEntity.ACTIVE;

    public SmartPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any()
                        .setValue(FACING, Direction.NORTH)
                        .setValue(ACTIVE, false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public boolean wrenchTurn(ServerLevel level, BlockPos pos, BlockState state, Direction side) {
        boolean turned = WrenchTarget.super.wrenchTurn(level, pos, state, side);
        if (turned && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            port.refreshWaterBerth(level);
        }
        return turned;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartPortBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (!(level instanceof ServerLevel) || type != Lc2Registries.SMART_PORT_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> ((SmartPortBlockEntity) be).serverTick((ServerLevel) lvl);
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
        if (LogisticsConstants.ELECTRIC_CARGO_BOAT_ID.equals(Lc2Native.itemId(stack))) {
            if (level instanceof ServerLevel serverLevel
                    && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
                port.spawnAndDockBoat(serverLevel, player, stack);
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
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            serverPlayer.openMenu(port, buf -> buf.writeBlockPos(pos));
            Lc2Native.sendToPlayer(serverPlayer, port.buildSyncPayload(serverLevel));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onNeighborRedstoneChanged(BlockState state, Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            port.onRedstoneUpdate(serverLevel);
        }
    }
}
