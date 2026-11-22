package com.logisticscraft.lc2.block;

import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2StationBlockBase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Cargo Station block ({@code lc2:cargo_station}) with {@code active} property matching
 * {@code docs/DRONE_MODEL_UV.md}.
 */
public final class CargoStationBlock extends Lc2StationBlockBase
        implements WrenchTarget, LogisticsPowerBlock {

    public static final BooleanProperty ACTIVE = AbstractLogisticsStationBlockEntity.ACTIVE;

    public CargoStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CargoStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (!(level instanceof ServerLevel) || type != Lc2Registries.CARGO_STATION_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> ((CargoStationBlockEntity) be).serverTick((ServerLevel) lvl);
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
                && level.getBlockEntity(pos) instanceof CargoStationBlockEntity station) {
            serverPlayer.openMenu(station, buf -> buf.writeBlockPos(pos));
            Lc2Native.sendToPlayer(serverPlayer, station.buildSyncPayload(serverLevel));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onNeighborRedstoneChanged(BlockState state, Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CargoStationBlockEntity station) {
            station.onRedstoneUpdate(serverLevel);
        }
    }

    @Override
    protected int computeAnalogOutputSignal(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AbstractLogisticsStationBlockEntity station) {
            return station.computeCargoComparatorSignal();
        }
        return 0;
    }
}
