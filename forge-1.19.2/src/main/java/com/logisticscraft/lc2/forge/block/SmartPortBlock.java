package com.logisticscraft.lc2.forge.block;

import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.world.LogisticsLevelManager;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * Smart Port block ({@code lc2:smart_port}) — automated waterway dock for Electric Smart Cargo Boats.
 * Declares {@code facing} ({@link #FACING}) and {@code active} ({@link #ACTIVE}) matching
 * {@code docs/DRONE_MODEL_UV.md}.
 */
public class SmartPortBlock extends Block implements EntityBlock, WrenchTarget, LogisticsPowerBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public SmartPortBlock() {
        super(Properties.of(Material.METAL)
                .strength(3.5F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ACTIVE, false);
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            if (stack.hasCustomHoverName()) {
                port.setStationName(stack.getHoverName().getString());
            }
            port.refreshWaterBerth(serverLevel);
            port.syncRegistry(serverLevel);
        }
    }

    @Override
    public boolean wrenchTurn(ServerLevel level, BlockPos pos, BlockState state, Direction side) {
        boolean turned = WrenchTarget.super.wrenchTurn(level, pos, state, side);
        if (turned && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            port.refreshWaterBerth(level);
            port.syncRegistry(level);
        }
        return turned;
    }

    @Override
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(Lc2Registries.ELECTRIC_CARGO_BOAT_ITEM.get())) {
            if (level instanceof ServerLevel serverLevel
                    && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
                port.spawnAndDockBoat(serverLevel, player, held);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
            port.refreshWaterBerth(serverLevel);
            port.syncRegistry(serverLevel);
            port.requestImmediateWaterCheck();
            NetworkHooks.openScreen(serverPlayer, port, pos);
            port.sendSyncTo(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean isMoving
    ) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SmartPortBlockEntity port) {
                Containers.dropContents(level, pos, port);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            if (level instanceof ServerLevel serverLevel) {
                LogisticsLevelManager.onStationRemoved(serverLevel, pos);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity station) {
            station.onNeighborRedstoneChanged(serverLevel);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity station) {
            return station.getCargoComparatorSignal();
        }
        return 0;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartPortBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (level.isClientSide) {
            return null;
        }
        return type == Lc2Registries.SMART_PORT_BE.get()
                ? (lvl, pos, st, be) -> ((SmartPortBlockEntity) be).serverTick((ServerLevel) lvl)
                : null;
    }
}
