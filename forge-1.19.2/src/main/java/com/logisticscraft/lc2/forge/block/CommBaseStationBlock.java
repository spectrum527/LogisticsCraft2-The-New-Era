package com.logisticscraft.lc2.forge.block;

import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.core.EnergyUnits;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import java.util.List;
import javax.annotation.Nullable;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Communication Base Station block ({@code lc2:comm_base_station}) — links face-adjacent
 * Smart Ports and Cargo Stations to the Operation Terminal remote control network.
 */
public class CommBaseStationBlock extends Block implements EntityBlock, WrenchTarget, LogisticsPowerBlock {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final VoxelShape SHAPE = Shapes.or(
            box(0.0, 0.0, 0.0, 16.0, 9.0, 16.0),
            box(4.0, 9.0, 4.0, 12.0, 16.0, 12.0)
    );

    public CommBaseStationBlock() {
        super(Properties.of(Material.METAL)
                .strength(3.5F, 8.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .noOcclusion());
        registerDefaultState(this.stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        ItemStack held = player.getItemInHand(hand);
        if (Lc2WrenchHelper.tryHandleWrench(this, false, held, state, level, pos, player, hand)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (held.getItem() instanceof OperationTerminalItem) {
            if (level instanceof ServerLevel serverLevel) {
                OperationTerminalItem.interactWithCommStation(serverLevel, pos, player, held);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CommBaseStationBlockEntity comm) {
            double eu = comm.getStoredEu();
            double maxEu = LogisticsConstants.COMM_BASE_CAPACITY_EU;
            long euDisplay = (long) Math.floor(eu);
            long maxEuDisplay = (long) Math.floor(maxEu);
            int feDisplay = EnergyUnits.euToFeFloor(eu);
            int maxFeDisplay = EnergyUnits.euToFeFloor(maxEu);

            Component statusPart = Component.translatable(
                    comm.isOnline()
                            ? "status.trera.terminal.comm_status_online"
                            : "status.trera.terminal.comm_status_offline",
                    euDisplay,
                    maxEuDisplay,
                    feDisplay,
                    maxFeDisplay
            );

            List<TerminalTargetResolver.AdjacentStationInfo> adjacent = comm.findAdjacentStations(serverLevel);
            Component adjPart = adjacent.isEmpty()
                    ? Component.translatable("status.trera.terminal.comm_adjacent_none")
                    : Component.translatable(
                            "status.trera.terminal.comm_adjacent_list",
                            formatAdjacentList(adjacent)
                    );

            player.displayClientMessage(
                    Component.empty().append(statusPart).append(Component.literal(" | ")).append(adjPart),
                    true
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Formats a comma-separated localized {@link Component} listing adjacent logistics stations. */
    public static Component formatAdjacentList(List<TerminalTargetResolver.AdjacentStationInfo> adjacent) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < adjacent.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(", "));
            }
            TerminalTargetResolver.AdjacentStationInfo info = adjacent.get(i);
            if (!info.customName().isBlank()) {
                joined.append(Component.literal(info.customName()));
            } else {
                String unnamedKey = info.type() == StationType.SMART_PORT
                        ? "gui.trera.terminal.unnamed_port"
                        : "gui.trera.terminal.unnamed_cargo";
                joined.append(Component.translatable(
                        unnamedKey,
                        info.pos().x(),
                        info.pos().y(),
                        info.pos().z()
                ));
            }
        }
        return joined;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CommBaseStationBlockEntity(pos, state);
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
        return type == Lc2Registries.COMM_BASE_STATION_BE.get()
                ? (lvl, pos, st, be) -> ((CommBaseStationBlockEntity) be).serverTick((ServerLevel) lvl)
                : null;
    }
}
