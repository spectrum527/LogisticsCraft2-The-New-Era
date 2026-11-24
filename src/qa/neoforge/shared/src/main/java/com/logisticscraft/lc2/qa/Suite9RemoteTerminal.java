package com.logisticscraft.lc2.qa;

import com.industrialage.ia2.gameplay.IndustrialMachine;
import com.industrialage.ia2.gameplay.PortableGear;
import com.industrialage.ia2.wrench.WrenchTarget;
import com.logisticscraft.lc2.Lc2Registries;
import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.block.LogisticsPowerBlock;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.CommBinding;
import com.logisticscraft.lc2.core.CommStationStatus;
import com.logisticscraft.lc2.core.DispatchFailure;
import com.logisticscraft.lc2.core.EnergyPriority;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.core.StationType;
import com.logisticscraft.lc2.core.TerminalTargetEntry;
import com.logisticscraft.lc2.core.TerminalTargetResolver;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import com.logisticscraft.lc2.menu.AbstractLogisticsStationMenu;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.network.Lc2Network;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.world.LogisticsLevelManager;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 9: Comprehensive v1.2.0 Communication Base Station &amp; Operation Terminal live E2E QA suite.
 * <ol>
 *   <li>Registry, Crafting Recipes, Block Interfaces ({@link LogisticsPowerBlock}, {@link WrenchTarget}),
 *       and {@code CableBlockMixin} visual connection + real IC2 Generator EU flow (both placement orders)</li>
 *   <li>Comm Base Station dual EU ({@code WorldPowerPort}/{@code EuBuffer}) + FE block capability
 *       (LV 32 EU/t = 128 FE/t, 4,000 EU = 16,000 FE, 1 EU = 4 FE, rollback/simulate safety),
 *       exact 2 EU/t standby drain &amp; {@code ACTIVE} blockstate lifecycle</li>
 *   <li>Operation Terminal dual EU ({@code PortableGearMixin}) + FE item capability (LV 32 EU/t = 128 FE/t,
 *       10,000 EU = 40,000 FE), binding, duplicate-bind rejection, and unbinding</li>
 *   <li>6-face adjacency discovery (horizontal + vertical) &amp; multi-comm deduplication in
 *       {@link TerminalTargetResolver}</li>
 *   <li>Remote read-only {@link SmartPortMenu} &amp; {@link CargoStationMenu} enforcement (every slot locked,
 *       click/quickMove/pickupAll/swap/throw blocked), 0 EU remote config, 100 EU dispatch energy charging
 *       (50 EU blocked, 1,000 EU failed = 0 deducted, 1,000 EU valid = -100 EU + delivery), and live disconnect</li>
 * </ol>
 */
public final class Suite9RemoteTerminal {

    // Cable connection & real IC2 Generator EU flow (chunks cx=7, cz=0)
    private static final BlockPos GEN_A = new BlockPos(116, 64, 6);
    private static final BlockPos CABLE_A = new BlockPos(117, 64, 6);
    private static final BlockPos COMM_A = new BlockPos(118, 64, 6);

    private static final BlockPos GEN_B = new BlockPos(116, 64, 10);
    private static final BlockPos CABLE_B = new BlockPos(117, 64, 10);
    private static final BlockPos COMM_B = new BlockPos(118, 64, 10);

    // Standby drain verification station (no external power)
    private static final BlockPos COMM_DRAIN = new BlockPos(124, 64, 16);

    // Adjacency, deduplication, remote read-only menus, dispatch & disconnect
    private static final BlockPos COMM_MAIN = new BlockPos(128, 64, 16);
    private static final BlockPos SMART_PORT_ADJ = COMM_MAIN.east();
    private static final BlockPos CARGO_ORIGIN_ADJ = COMM_MAIN.above();
    private static final BlockPos NON_STATION_CHEST = COMM_MAIN.west();
    private static final BlockPos COMM_SECOND = SMART_PORT_ADJ.south();
    private static final BlockPos CARGO_TARGET = new BlockPos(146, 64, 16);

    private enum Stage {
        FORCE_CHUNKS,
        WAIT_CHUNKS_AND_SETUP,
        VERIFY_DRAIN_AND_REAL_EU,
        WAIT_REMOTE_DRONE_ARRIVAL,
        DONE
    }

    private Stage stage = Stage.FORCE_CHUNKS;
    private int stageStartTick = 0;
    private boolean drainTick1Checked = false;
    private boolean drainTick3Checked = false;
    private boolean drainTick4Checked = false;
    private UUID remoteDroneTransitId;

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        return switch (stage) {
            case FORCE_CHUNKS -> {
                QaChecks.setChunkRangeForced(level, 7, 9, 0, 2, true);
                stage = Stage.WAIT_CHUNKS_AND_SETUP;
                stageStartTick = tick;
                yield false;
            }
            case WAIT_CHUNKS_AND_SETUP -> {
                if (!Lc2Native.isChunkEntityTicking(level, 7, 0)
                        || !Lc2Native.isChunkEntityTicking(level, 8, 1)
                        || !Lc2Native.isChunkEntityTicking(level, 9, 1)) {
                    if (tick - stageStartTick > 500) {
                        throw new AssertionError("Timeout waiting for Suite 9 chunks to become entity-ticking");
                    }
                    yield false;
                }
                runSetupChecks(level, tick, checks);
                stage = Stage.VERIFY_DRAIN_AND_REAL_EU;
                stageStartTick = tick;
                yield false;
            }
            case VERIFY_DRAIN_AND_REAL_EU -> {
                tickDrainAndRealEu(level, tick, checks);
                yield false;
            }
            case WAIT_REMOTE_DRONE_ARRIVAL -> {
                tickWaitRemoteDroneArrival(level, tick, checks);
                yield stage == Stage.DONE;
            }
            case DONE -> true;
        };
    }

    private void runSetupChecks(ServerLevel level, int tick, QaChecks checks) {
        MinecraftServer server = level.getServer();
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        player.getInventory().clearContent();
        checks.check("s9.env.fake_player_not_creative", !player.isCreative(), "creative=" + player.isCreative());

        // 1. Registries, Block Interfaces & Live Crafting Recipes
        checks.check("s9.reg.comm_base_station_present",
                QaChecks.hasBlock(LogisticsConstants.COMM_BASE_STATION_ID)
                        && QaChecks.hasBlockEntity(LogisticsConstants.COMM_BASE_STATION_ID)
                        && QaChecks.hasItem(LogisticsConstants.COMM_BASE_STATION_ID));
        checks.check("s9.reg.operation_terminal_present",
                QaChecks.hasItem(LogisticsConstants.OPERATION_TERMINAL_ID)
                        && new ItemStack(QaChecks.item(LogisticsConstants.OPERATION_TERMINAL_ID)).getMaxStackSize() == 1);

        Block commBlock = QaChecks.block(LogisticsConstants.COMM_BASE_STATION_ID);
        checks.check("s9.block.interfaces_power_and_wrench",
                commBlock instanceof CommBaseStationBlock
                        && commBlock instanceof LogisticsPowerBlock
                        && commBlock instanceof WrenchTarget,
                commBlock.getClass().getSimpleName());

        Set<String> liveRecipes = QaPlatform.allRecipeIds(server);
        verifyRecipeCraft(level, player, liveRecipes, checks,
                LogisticsConstants.COMM_BASE_STATION_ID,
                new String[]{"CFC", "IMI", "WBW"},
                Map.of(
                        'C', "ic2:circuit",
                        'F', "ic2:frequency_transmitter",
                        'I', "ic2:iron_plate",
                        'M', "ic2:machine",
                        'W', "ic2:insulated_copper_cable",
                        'B', "ic2:re_battery"
                ));
        verifyRecipeCraft(level, player, liveRecipes, checks,
                LogisticsConstants.OPERATION_TERMINAL_ID,
                new String[]{"IGI", "CFC", "WBW"},
                Map.of(
                        'I', "ic2:iron_plate",
                        'G', "minecraft:glass_pane",
                        'C', "ic2:circuit",
                        'F', "ic2:frequency_transmitter",
                        'W', "ic2:insulated_copper_cable",
                        'B', "ic2:re_battery"
                ));

        // 2. CableBlockMixin visual connection (both placement orders!) + real IC2 Generator EU flow
        // Order A: cable first, then CommBaseStation -> updateShape
        QaChecks.survivalPlace(level, player, "ic2:generator", GEN_A);
        QaChecks.survivalPlace(level, player, "ic2:insulated_copper_cable", CABLE_A);
        level.setBlockAndUpdate(COMM_A, commBlock.defaultBlockState());
        BlockState cableAState = level.getBlockState(CABLE_A);
        checks.check("s9.mixin.cable_connects_comm_via_update",
                cableAState.hasProperty(BlockStateProperties.EAST) && cableAState.getValue(BlockStateProperties.EAST),
                "state=" + cableAState);

        // Order B: CommBaseStation first, then survival-placed cable -> getStateForPlacement
        QaChecks.survivalPlace(level, player, "ic2:generator", GEN_B);
        level.setBlockAndUpdate(COMM_B, commBlock.defaultBlockState());
        QaChecks.survivalPlace(level, player, "ic2:insulated_copper_cable", CABLE_B);
        BlockState cableBState = level.getBlockState(CABLE_B);
        checks.check("s9.mixin.cable_connects_comm_via_placement",
                cableBState.hasProperty(BlockStateProperties.EAST) && cableBState.getValue(BlockStateProperties.EAST),
                "state=" + cableBState);

        IndustrialMachine genABe = (IndustrialMachine) level.getBlockEntity(GEN_A);
        checks.check("s9.ic2.generator_primed_for_comm", genABe != null);
        genABe.setItem(0, new ItemStack(Items.COAL, 8));

        // 3. Comm Base Station Dual EU + FE Power Capability Checks
        level.setBlockAndUpdate(COMM_MAIN, commBlock.defaultBlockState());
        CommBaseStationBlockEntity commMain = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_MAIN);
        checks.check("s9.comm.be_created_and_ratings",
                commMain != null
                        && commMain.packetLimit() == 32.0
                        && commMain.getMaxEu() == 4_000.0
                        && commMain.getMaxFe() == 16_000
                        && !commMain.source()
                        && commMain.energyPorts().inputs().size() == 6
                        && commMain.energyPorts().outputs().isEmpty(),
                "limit=" + (commMain != null ? commMain.packetLimit() : -1)
                        + ", maxEu=" + (commMain != null ? commMain.getMaxEu() : -1));

        // Direct IC2 WorldPowerPort receive() & EuBuffer.bufferedEu()
        commMain.setStoredEu(0.0);
        double acceptedEu = commMain.receive(50.0);
        checks.check("s9.comm.world_power_port_receive_and_fe_ratio",
                Math.abs(acceptedEu - 50.0) < 1e-6
                        && Math.abs(commMain.getStoredEu() - 50.0) < 1e-6
                        && commMain.getStoredFe() == 200
                        && Math.abs(commMain.bufferedEu().stored() - 50.0) < 1e-6
                        && Math.abs(commMain.bufferedEu().capacity() - 4_000.0) < 1e-6,
                "acceptedEu=" + acceptedEu + ", storedEu=" + commMain.getStoredEu() + ", storedFe=" + commMain.getStoredFe());

        commMain.setStoredEu(3_990.0);
        double partialEu = commMain.receive(100.0);
        double overEu = commMain.receive(32.0);
        checks.check("s9.comm.eu_capacity_cap_4000",
                Math.abs(partialEu - 10.0) < 1e-6
                        && Math.abs(overEu) < 1e-6
                        && Math.abs(commMain.getStoredEu() - 4_000.0) < 1e-6,
                "partial=" + partialEu + ", over=" + overEu + ", stored=" + commMain.getStoredEu());

        // FE block capability across all 6 faces (LV 128 FE/t, 16,000 FE cap, 1 EU = 4 FE, simulate/rollback)
        commMain.setStoredEu(0.0);
        boolean allSixFaces = true;
        for (Direction dir : Direction.values()) {
            if (!QaPlatform.hasBlockFeCapability(level, COMM_MAIN, dir)) {
                allSixFaces = false;
                break;
            }
        }
        int capFe = QaPlatform.blockFeCapacity(level, COMM_MAIN, Direction.UP);
        int simFe = QaPlatform.blockFeReceive(level, COMM_MAIN, Direction.UP, 500, true);
        double euAfterSim = commMain.getStoredEu();
        int commitFe1 = QaPlatform.blockFeReceive(level, COMM_MAIN, Direction.NORTH, 500, false);
        double euAfterCommit1 = commMain.getStoredEu();
        int commitFe2 = QaPlatform.blockFeReceive(level, COMM_MAIN, Direction.DOWN, 100, false);
        double euAfterCommit2 = commMain.getStoredEu();
        int extractedFe = QaPlatform.blockFeExtract(level, COMM_MAIN, Direction.UP, 128, false);
        commMain.setStoredEu(3_995.0);
        int capEdgeFe = QaPlatform.blockFeReceive(level, COMM_MAIN, Direction.EAST, 128, false);
        int fullFe = QaPlatform.blockFeReceive(level, COMM_MAIN, Direction.WEST, 128, false);
        checks.check("s9.comm.fe_capability_6face_lv_cap_and_conversion",
                allSixFaces
                        && capFe == 16_000
                        && simFe == 128
                        && Math.abs(euAfterSim) < 1e-6
                        && commitFe1 == 128
                        && Math.abs(euAfterCommit1 - 32.0) < 1e-6
                        && commitFe2 == 100
                        && Math.abs(euAfterCommit2 - 57.0) < 1e-6
                        && extractedFe == 0
                        && capEdgeFe == 20
                        && fullFe == 0
                        && Math.abs(commMain.getStoredEu() - 4_000.0) < 1e-6
                        && QaPlatform.blockFeStored(level, COMM_MAIN, Direction.UP) == 16_000,
                String.format("6faces=%s, cap=%d, sim=%d(eu=%.1f), c1=%d(eu=%.1f), c2=%d(eu=%.1f), edge=%d, full=%d",
                        allSixFaces, capFe, simFe, euAfterSim, commitFe1, euAfterCommit1, commitFe2, euAfterCommit2,
                        capEdgeFe, fullFe));

        // 4. Standby Drain Test Station Setup (6.0 EU -> ticks 1..3 online/ACTIVE, tick 4 offline/!ACTIVE)
        level.setBlockAndUpdate(COMM_DRAIN, commBlock.defaultBlockState());
        CommBaseStationBlockEntity commDrain = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_DRAIN);
        commDrain.setStoredEu(6.0);

        // 5. Operation Terminal Dual EU (PortableGearMixin) + FE Item Capability + Binding Checks
        verifyOperationTerminalChargingAndBinding(level, player, checks);

        // 6. Place adjacency & remote-dispatch stations now so onLoad() registers them in StationRegistry
        level.setBlockAndUpdate(SMART_PORT_ADJ,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(CARGO_ORIGIN_ADJ, QaChecks.block("lc2:cargo_station").defaultBlockState());
        level.setBlockAndUpdate(NON_STATION_CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(COMM_SECOND, commBlock.defaultBlockState());
        level.setBlockAndUpdate(CARGO_TARGET, QaChecks.block("lc2:cargo_station").defaultBlockState());
    }

    private void verifyRecipeCraft(
            ServerLevel level,
            FakePlayer crafter,
            Set<String> liveRecipes,
            QaChecks checks,
            String id,
            String[] pattern,
            Map<Character, String> key
    ) {
        checks.check("s9.recipe.present." + id, liveRecipes.contains(id), "present in RecipeManager");
        CraftingMenu menu = new CraftingMenu(301, crafter.getInventory(),
                ContainerLevelAccess.create(level, new BlockPos(116, 65, 6)));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                char ch = pattern[r].charAt(c);
                if (ch != ' ') {
                    menu.getSlot(1 + r * 3 + c).set(new ItemStack(QaChecks.item(key.get(ch))));
                }
            }
        }
        menu.slotsChanged(menu.getSlot(1).container);
        ItemStack output = menu.getSlot(0).getItem();
        checks.check("s9.recipe.craft." + id,
                !output.isEmpty() && output.is(QaChecks.item(id)) && output.getCount() == 1,
                "crafted=" + output);
    }

    private void verifyOperationTerminalChargingAndBinding(ServerLevel level, FakePlayer player, QaChecks checks) {
        // A. IC2 PortableGearMixin verification (direct live PortableGear calls!)
        ItemStack termEu = new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get());
        boolean rejectTier0 = !PortableGear.acceptsCharge(termEu, 0);
        boolean acceptTier1 = PortableGear.acceptsCharge(termEu, 1);
        boolean acceptTier2 = PortableGear.acceptsCharge(termEu, 2);
        boolean acceptTier4 = PortableGear.acceptsCharge(termEu, 4);
        double cap = PortableGear.capacity(termEu);
        double initStored = PortableGear.stored(termEu);
        double chargeTier0 = PortableGear.charge(termEu, 50.0, 0);
        double chargeClamped = PortableGear.charge(termEu, 100.0, 1);
        double afterClamped = PortableGear.stored(termEu);
        double chargePartial = PortableGear.charge(termEu, 20.0, 2);
        double afterPartial = PortableGear.stored(termEu);
        OperationTerminalItem.setStoredEu(termEu, 9_990.0);
        double chargeToCap = PortableGear.charge(termEu, 32.0, 1);
        double afterCap = PortableGear.stored(termEu);
        double chargeWhenFull = PortableGear.charge(termEu, 32.0, 1);
        checks.check("s9.terminal.portable_gear_mixin_eu_charging",
                rejectTier0 && acceptTier1 && acceptTier2 && acceptTier4
                        && Math.abs(cap - 10_000.0) < 1e-6
                        && Math.abs(initStored) < 1e-6
                        && Math.abs(chargeTier0) < 1e-6
                        && Math.abs(chargeClamped - 32.0) < 1e-6
                        && Math.abs(afterClamped - 32.0) < 1e-6
                        && Math.abs(chargePartial - 20.0) < 1e-6
                        && Math.abs(afterPartial - 52.0) < 1e-6
                        && Math.abs(chargeToCap - 10.0) < 1e-6
                        && Math.abs(afterCap - 10_000.0) < 1e-6
                        && Math.abs(chargeWhenFull) < 1e-6
                        && Math.abs(OperationTerminalItem.getStoredEu(termEu) - 10_000.0) < 1e-6,
                String.format("t0=%s, t1=%s, cap=%.0f, clamped=%.1f, partial=%.1f, toCap=%.1f, full=%.1f",
                        rejectTier0, acceptTier1, cap, chargeClamped, chargePartial, chargeToCap, chargeWhenFull));

        // B. FE item capability verification (128 FE/t cap, 40,000 FE cap, 1 EU = 4 FE, simulate/rollback)
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get()));
        boolean hasCap = QaPlatform.hasItemFeCapability(player, 0);
        int feCap = QaPlatform.itemFeCapacity(player, 0);
        int feSim = QaPlatform.itemFeReceive(player, 0, 500, true);
        double euAfterSim = OperationTerminalItem.getStoredEu(player.getInventory().getItem(0));
        int feCommit1 = QaPlatform.itemFeReceive(player, 0, 500, false);
        double euAfterC1 = OperationTerminalItem.getStoredEu(player.getInventory().getItem(0));
        int feCommit2 = QaPlatform.itemFeReceive(player, 0, 24, false);
        double euAfterC2 = OperationTerminalItem.getStoredEu(player.getInventory().getItem(0));
        int feExtract = QaPlatform.itemFeExtract(player, 0, 128, false);
        OperationTerminalItem.setStoredEu(player.getInventory().getItem(0), 9_995.0);
        int feEdge = QaPlatform.itemFeReceive(player, 0, 128, false);
        int feFull = QaPlatform.itemFeReceive(player, 0, 128, false);
        int feFinalStored = QaPlatform.itemFeStored(player, 0);
        double euFinal = OperationTerminalItem.getStoredEu(player.getInventory().getItem(0));
        checks.check("s9.terminal.fe_item_capability_lv_cap_and_40000_max",
                hasCap
                        && feCap == 40_000
                        && feSim == 128
                        && Math.abs(euAfterSim) < 1e-6
                        && feCommit1 == 128
                        && Math.abs(euAfterC1 - 32.0) < 1e-6
                        && feCommit2 == 24
                        && Math.abs(euAfterC2 - 38.0) < 1e-6
                        && feExtract == 0
                        && feEdge == 20
                        && feFull == 0
                        && feFinalStored == 40_000
                        && Math.abs(euFinal - 10_000.0) < 1e-6,
                String.format("cap=%d, sim=%d(eu=%.1f), c1=%d(eu=%.1f), c2=%d(eu=%.1f), edge=%d, full=%d, stored=%d",
                        feCap, feSim, euAfterSim, feCommit1, euAfterC1, feCommit2, euAfterC2, feEdge, feFull, feFinalStored));

        // C. Binding, duplicate-bind rejection, unbinding & sneak-vs-normal CommBaseStation interaction
        ItemStack termBind = new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get());
        String dim = Lc2Native.dimensionId(level);
        var r1 = OperationTerminalItem.bindCommStation(termBind, dim, COMM_MAIN);
        String tid = OperationTerminalItem.getTerminalId(termBind);
        var rDup = OperationTerminalItem.bindCommStation(termBind, dim, COMM_MAIN);
        var r2 = OperationTerminalItem.bindCommStation(termBind, dim, COMM_SECOND);
        boolean boundBoth = OperationTerminalItem.isBoundTo(termBind, dim, COMM_MAIN)
                && OperationTerminalItem.isBoundTo(termBind, dim, COMM_SECOND)
                && OperationTerminalItem.getBindings(termBind).size() == 2;
        boolean unbind1 = OperationTerminalItem.unbindCommStation(termBind, dim, COMM_MAIN);
        boolean unbindDup = OperationTerminalItem.unbindCommStation(termBind, dim, COMM_MAIN);
        boolean onlySecondLeft = !OperationTerminalItem.isBoundTo(termBind, dim, COMM_MAIN)
                && OperationTerminalItem.isBoundTo(termBind, dim, COMM_SECOND)
                && OperationTerminalItem.getBindings(termBind).size() == 1;

        // Exercise normal right-click = bind and sneak-right-click = unbind through interactWithCommStation
        player.setShiftKeyDown(false);
        OperationTerminalItem.interactWithCommStation(termBind, level, COMM_MAIN, player);
        boolean boundViaNormalClick = OperationTerminalItem.isBoundTo(termBind, dim, COMM_MAIN);
        player.setShiftKeyDown(true);
        OperationTerminalItem.interactWithCommStation(termBind, level, COMM_MAIN, player);
        boolean unboundViaSneakClick = !OperationTerminalItem.isBoundTo(termBind, dim, COMM_MAIN);
        player.setShiftKeyDown(false);

        checks.check("s9.terminal.bind_duplicate_reject_unbind_and_interactions",
                r1 == OperationTerminalItem.BindResult.BOUND
                        && !tid.isBlank()
                        && rDup == OperationTerminalItem.BindResult.ALREADY_BOUND
                        && r2 == OperationTerminalItem.BindResult.BOUND
                        && boundBoth
                        && unbind1 && !unbindDup && onlySecondLeft
                        && boundViaNormalClick && unboundViaSneakClick,
                "tid=" + tid + ", boundClick=" + boundViaNormalClick + ", unboundSneak=" + unboundViaSneakClick);
        player.getInventory().clearContent();
    }

    private void tickDrainAndRealEu(ServerLevel level, int tick, QaChecks checks) {
        int elapsed = tick - stageStartTick;
        CommBaseStationBlockEntity commDrain = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_DRAIN);
        if (!drainTick1Checked && elapsed >= 1) {
            BlockState st = level.getBlockState(COMM_DRAIN);
            checks.check("s9.comm.standby_drain_tick1_2eu_and_active",
                    elapsed == 1
                            && commDrain != null
                            && Math.abs(commDrain.getStoredEu() - 4.0) < 1e-6
                            && commDrain.isOnline()
                            && st.getValue(CommBaseStationBlock.ACTIVE),
                    "elapsed=" + elapsed + ", eu=" + (commDrain != null ? commDrain.getStoredEu() : -1)
                            + ", active=" + st.getValue(CommBaseStationBlock.ACTIVE));
            drainTick1Checked = true;
        }
        if (!drainTick3Checked && elapsed >= 3) {
            BlockState st = level.getBlockState(COMM_DRAIN);
            checks.check("s9.comm.standby_drain_tick3_reaches_zero_still_active",
                    elapsed == 3
                            && commDrain != null
                            && Math.abs(commDrain.getStoredEu()) < 1e-6
                            && commDrain.isOnline()
                            && st.getValue(CommBaseStationBlock.ACTIVE),
                    "elapsed=" + elapsed + ", eu=" + (commDrain != null ? commDrain.getStoredEu() : -1)
                            + ", active=" + st.getValue(CommBaseStationBlock.ACTIVE));
            drainTick3Checked = true;
        }
        if (!drainTick4Checked && elapsed >= 4) {
            BlockState st = level.getBlockState(COMM_DRAIN);
            checks.check("s9.comm.standby_drain_tick4_offline_and_inactive",
                    elapsed == 4
                            && commDrain != null
                            && Math.abs(commDrain.getStoredEu()) < 1e-6
                            && !commDrain.isOnline()
                            && !st.getValue(CommBaseStationBlock.ACTIVE),
                    "elapsed=" + elapsed + ", eu=" + (commDrain != null ? commDrain.getStoredEu() : -1)
                            + ", online=" + (commDrain != null && commDrain.isOnline())
                            + ", active=" + st.getValue(CommBaseStationBlock.ACTIVE));
            drainTick4Checked = true;
        }

        if (elapsed < 35) {
            return;
        }

        // Verify real IC2 Generator EU flowed through insulated copper cable into COMM_A!
        CommBaseStationBlockEntity commA = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_A);
        BlockState commAState = level.getBlockState(COMM_A);
        checks.check("s9.comm.real_ic2_generator_eu_flow",
                commA != null
                        && commA.getStoredEu() > 50.0
                        && commA.isOnline()
                        && commAState.getValue(CommBaseStationBlock.ACTIVE),
                "commAEu=" + (commA != null ? commA.getStoredEu() : -1)
                        + ", active=" + commAState.getValue(CommBaseStationBlock.ACTIVE));

        // Now run Adjacency, Deduplication, Remote Read-Only Menus, 100 EU Dispatch & Live Disconnect!
        runAdjacencyRemoteMenuDispatchAndDisconnectChecks(level, tick, checks);
    }

    private void runAdjacencyRemoteMenuDispatchAndDisconnectChecks(ServerLevel level, int tick, QaChecks checks) {
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        String dim = Lc2Native.dimensionId(level);

        CommBaseStationBlockEntity commMain = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_MAIN);
        CommBaseStationBlockEntity commSecond = (CommBaseStationBlockEntity) level.getBlockEntity(COMM_SECOND);
        SmartPortBlockEntity smartPort = (SmartPortBlockEntity) level.getBlockEntity(SMART_PORT_ADJ);
        CargoStationBlockEntity cargoOrigin = (CargoStationBlockEntity) level.getBlockEntity(CARGO_ORIGIN_ADJ);
        CargoStationBlockEntity cargoTarget = (CargoStationBlockEntity) level.getBlockEntity(CARGO_TARGET);

        smartPort.setStationName("S9-RemotePort");
        cargoOrigin.setStationName("S9-RemoteCargo");
        cargoTarget.setStationName("S9-RemoteTarget");

        // 1. Verify 6-face adjacency (horizontal EAST + vertical UP, ignoring WEST chest)
        List<TerminalTargetResolver.AdjacentStationInfo> adjMain = commMain.findAdjacentStations(level);
        boolean foundPort = adjMain.stream().anyMatch(i ->
                i.type() == StationType.SMART_PORT
                        && i.pos().equals(LogisticsSavedData.toPos3i(SMART_PORT_ADJ))
                        && "S9-RemotePort".equals(i.customName()));
        boolean foundCargo = adjMain.stream().anyMatch(i ->
                i.type() == StationType.CARGO_STATION
                        && i.pos().equals(LogisticsSavedData.toPos3i(CARGO_ORIGIN_ADJ))
                        && "S9-RemoteCargo".equals(i.customName()));
        checks.check("s9.comm.adjacency_6face_horizontal_and_vertical",
                adjMain.size() == 2 && foundPort && foundCargo,
                "count=" + adjMain.size() + ", adj=" + adjMain);

        // 2. Verify deduplication when 2 Comm Base Stations touch the same SmartPort
        commMain.setStoredEu(0.0); // UNPOWERED
        commSecond.setStoredEu(2_000.0); // ONLINE
        List<CommBinding> twoBindings = List.of(
                new CommBinding(dim, LogisticsSavedData.toPos3i(COMM_MAIN)),
                new CommBinding(dim, LogisticsSavedData.toPos3i(COMM_SECOND))
        );
        TerminalTargetResolver.CommProbe liveProbe = binding -> {
            BlockPos p = LogisticsSavedData.toBlockPos(binding.pos());
            if (!(level.getBlockEntity(p) instanceof CommBaseStationBlockEntity c) || c.isRemoved()) {
                return TerminalTargetResolver.CommProbeResult.missing();
            }
            return TerminalTargetResolver.CommProbeResult.present(c.isOnline(), c.findAdjacentStations(level));
        };
        BlockPos3i playerPos3i = new BlockPos3i(128, 64, 60);
        List<TerminalTargetEntry> resolvedPrefOnline =
                TerminalTargetResolver.resolve(dim, playerPos3i, twoBindings, liveProbe);
        long portRows1 = resolvedPrefOnline.stream()
                .filter(e -> e.hasStation() && e.stationPos().equals(LogisticsSavedData.toPos3i(SMART_PORT_ADJ)))
                .count();
        TerminalTargetEntry portRowOnline = resolvedPrefOnline.stream()
                .filter(e -> e.hasStation() && e.stationPos().equals(LogisticsSavedData.toPos3i(SMART_PORT_ADJ)))
                .findFirst().orElse(null);

        // Now power both -> tie breaks to earliest binding (COMM_MAIN)!
        commMain.setStoredEu(2_000.0);
        List<TerminalTargetEntry> resolvedBothOnline =
                TerminalTargetResolver.resolve(dim, playerPos3i, twoBindings, liveProbe);
        TerminalTargetEntry portRowTie = resolvedBothOnline.stream()
                .filter(e -> e.hasStation() && e.stationPos().equals(LogisticsSavedData.toPos3i(SMART_PORT_ADJ)))
                .findFirst().orElse(null);

        // Also check cross-dimension binding never invokes probe
        AtomicInteger crossDimProbes = new AtomicInteger();
        List<TerminalTargetEntry> crossDimResolved = TerminalTargetResolver.resolve(
                dim,
                playerPos3i,
                List.of(new CommBinding("minecraft:the_nether", new BlockPos3i(10, 64, 10))),
                b -> {
                    crossDimProbes.incrementAndGet();
                    return TerminalTargetResolver.CommProbeResult.missing();
                }
        );

        checks.check("s9.resolver.dedup_shared_station_prefers_online_and_order",
                resolvedPrefOnline.size() == 2
                        && portRows1 == 1
                        && portRowOnline != null
                        && portRowOnline.status() == CommStationStatus.ONLINE
                        && portRowOnline.commPos().equals(LogisticsSavedData.toPos3i(COMM_SECOND))
                        && resolvedBothOnline.size() == 2
                        && portRowTie != null
                        && portRowTie.status() == CommStationStatus.ONLINE
                        && portRowTie.commPos().equals(LogisticsSavedData.toPos3i(COMM_MAIN))
                        && crossDimProbes.get() == 0
                        && crossDimResolved.size() == 1
                        && crossDimResolved.get(0).status() == CommStationStatus.WRONG_DIMENSION
                        && crossDimResolved.get(0).distance() == -1,
                "prefOnline=" + resolvedPrefOnline + ", tie=" + portRowTie);

        // 3. Remote Read-Only Station Menu Protection (SmartPortMenu & CargoStationMenu)
        player.setPos(128.5, 64.0, 60.5); // > 40 blocks away (outside local 8-block radius!)
        player.getInventory().clearContent();
        ItemStack terminal = new ItemStack(Lc2Registries.OPERATION_TERMINAL_ITEM.get());
        OperationTerminalItem.bindCommStation(terminal, dim, COMM_MAIN);
        OperationTerminalItem.bindCommStation(terminal, dim, COMM_SECOND);
        String terminalId = OperationTerminalItem.ensureTerminalId(terminal);
        player.getInventory().setItem(0, terminal);
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 16));
        player.getInventory().setItem(10, new ItemStack(Items.DIAMOND, 4));

        // Seed station slots
        smartPort.setItem(0, new ItemStack(Items.DIAMOND, 16));
        smartPort.setItem(SmartPortBlockEntity.STORAGE_START, new ItemStack(Items.EMERALD, 12));
        smartPort.setItem(SmartPortBlockEntity.BATTERY_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.SINGLE_USE_BATTERY_ID), 2));
        cargoOrigin.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));
        cargoOrigin.setItem(CargoStationBlockEntity.STORAGE_START, new ItemStack(Items.LAPIS_LAZULI, 10));
        cargoOrigin.setItem(CargoStationBlockEntity.RF_CHIP_SLOT,
                new ItemStack(QaChecks.item(LogisticsConstants.RF_CHIP_1_ID), 1));
        cargoOrigin.setItem(CargoStationBlockEntity.DRONE_BAY_START,
                new ItemStack(QaChecks.item(LogisticsConstants.TRANSPORT_DRONE_ID), 1));

        SmartPortMenu remotePortMenu = new SmartPortMenu(401, player.getInventory(), smartPort, COMM_MAIN, terminalId);
        CargoStationMenu remoteCargoMenu =
                new CargoStationMenu(402, player.getInventory(), cargoOrigin, COMM_MAIN, terminalId);

        checks.check("s9.remote_menu.open_and_still_valid_beyond_local_range",
                remotePortMenu.isRemoteMode()
                        && remoteCargoMenu.isRemoteMode()
                        && remotePortMenu.stillValid(player)
                        && remoteCargoMenu.stillValid(player)
                        && !smartPort.stillValid(player),
                "portValid=" + remotePortMenu.stillValid(player)
                        + ", cargoValid=" + remoteCargoMenu.stillValid(player)
                        + ", localValid=" + smartPort.stillValid(player));

        verifyStrictReadOnlyMenu("smart_port", remotePortMenu, SmartPortMenu.MACHINE_SLOTS,
                smartPort, Items.DIAMOND, 16, Items.EMERALD, 12, player, checks);
        verifyStrictReadOnlyMenu("cargo_station", remoteCargoMenu, CargoStationMenu.MACHINE_SLOTS,
                cargoOrigin, Items.GOLD_INGOT, 8, Items.LAPIS_LAZULI, 10, player, checks);

        // 4. Remote Control Actions at 0 EU + 100 EU Dispatch Energy Deduction
        OperationTerminalItem.setStoredEu(player.getInventory().getItem(0), 0.0);
        cargoOrigin.setStationName("S9-RemoteRenamed");
        cargoOrigin.setAutoReturn(true);
        cargoOrigin.setAutoReturn(false);
        cargoOrigin.setEnergyPriority(EnergyPriority.BATTERY_FIRST);
        cargoOrigin.setEnergyPriority(EnergyPriority.EU_FIRST);
        checks.check("s9.remote.config_allowed_at_0eu",
                remoteCargoMenu.stillValid(player)
                        && "S9-RemoteRenamed".equals(cargoOrigin.getStationName())
                        && Math.abs(OperationTerminalItem.getStoredEu(player.getInventory().getItem(0))) < 1e-6);

        cargoOrigin.addEnergy(5_000.0);
        cargoOrigin.setTargetPos(CARGO_TARGET);

        // Case A: Terminal EU = 50 (< 100 EU) -> rejected with TERMINAL_INSUFFICIENT_ENERGY, 0 deducted, no launch!
        OperationTerminalItem.setStoredEu(player.getInventory().getItem(0), 50.0);
        invokeRemoteManualDispatch(player, level, cargoOrigin, remoteCargoMenu);
        boolean anyLaunchedA = findDroneTransitFrom(level, CARGO_ORIGIN_ADJ) != null;
        checks.check("s9.remote.dispatch_blocked_under_100eu",
                !anyLaunchedA
                        && cargoOrigin.countReadyDrones() == 1
                        && DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY.translationKey().equals(cargoOrigin.getStatusKey())
                        && Math.abs(OperationTerminalItem.getStoredEu(player.getInventory().getItem(0)) - 50.0) < 1e-6,
                "status=" + cargoOrigin.getStatusKey()
                        + ", termEu=" + OperationTerminalItem.getStoredEu(player.getInventory().getItem(0)));

        // Case B: Terminal EU = 1,000 EU + failing dispatch (no target) -> 0 EU deducted!
        OperationTerminalItem.setStoredEu(player.getInventory().getItem(0), 1_000.0);
        cargoOrigin.setTargetPos(null);
        invokeRemoteManualDispatch(player, level, cargoOrigin, remoteCargoMenu);
        boolean anyLaunchedB = findDroneTransitFrom(level, CARGO_ORIGIN_ADJ) != null;
        checks.check("s9.remote.dispatch_failure_deducts_zero_eu",
                !anyLaunchedB
                        && cargoOrigin.countReadyDrones() == 1
                        && DispatchFailure.NO_TARGET.translationKey().equals(cargoOrigin.getStatusKey())
                        && Math.abs(OperationTerminalItem.getStoredEu(player.getInventory().getItem(0)) - 1_000.0) < 1e-6,
                "status=" + cargoOrigin.getStatusKey()
                        + ", termEu=" + OperationTerminalItem.getStoredEu(player.getInventory().getItem(0)));

        // Case C: Terminal EU = 1,000 EU + valid dispatch -> SUCCEEDS & deducts EXACTLY 100 EU (1,000 -> 900 EU)!
        cargoOrigin.setTargetPos(CARGO_TARGET);
        invokeRemoteManualDispatch(player, level, cargoOrigin, remoteCargoMenu);
        remoteDroneTransitId = findDroneTransitFrom(level, CARGO_ORIGIN_ADJ);
        double euAfterSuccess = OperationTerminalItem.getStoredEu(player.getInventory().getItem(0));
        checks.check("s9.remote.dispatch_success_deducts_exact_100eu",
                remoteDroneTransitId != null
                        && cargoOrigin.countReadyDrones() == 0
                        && Math.abs(euAfterSuccess - 900.0) < 1e-6,
                "transitId=" + remoteDroneTransitId + ", status=" + cargoOrigin.getStatusKey()
                        + ", termEu=" + euAfterSuccess);

        // 5. Live Disconnect Protection
        // 5a: Removing terminal from player inventory -> immediately stillValid == false!
        SmartPortMenu liveMenu1 = new SmartPortMenu(403, player.getInventory(), smartPort, COMM_MAIN, terminalId);
        boolean validBeforeDrop = liveMenu1.stillValid(player);
        ItemStack removedTerm = player.getInventory().getItem(0).copy();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        boolean invalidAfterDrop = !liveMenu1.stillValid(player);
        player.getInventory().setItem(0, removedTerm);
        checks.check("s9.disconnect.removing_terminal_closes_remote_menu",
                validBeforeDrop && invalidAfterDrop,
                "before=" + validBeforeDrop + ", invalidAfter=" + invalidAfterDrop);

        // 5b: Draining Comm Base Station EU to 0 -> immediately stillValid == false!
        SmartPortMenu liveMenu2 = new SmartPortMenu(404, player.getInventory(), smartPort, COMM_MAIN, terminalId);
        boolean validBeforeDrain = liveMenu2.stillValid(player);
        commMain.setStoredEu(0.0);
        boolean invalidAfterDrain = !liveMenu2.stillValid(player);
        commMain.setStoredEu(2_000.0);
        checks.check("s9.disconnect.draining_comm_eu_closes_remote_menu",
                validBeforeDrain && invalidAfterDrain,
                "before=" + validBeforeDrain + ", invalidAfter=" + invalidAfterDrain);

        // 5c: Breaking Comm Base Station -> immediately stillValid == false!
        SmartPortMenu liveMenu3 = new SmartPortMenu(405, player.getInventory(), smartPort, COMM_MAIN, terminalId);
        boolean validBeforeBreak = liveMenu3.stillValid(player);
        level.setBlockAndUpdate(COMM_MAIN, Blocks.AIR.defaultBlockState());
        boolean invalidAfterBreak = !liveMenu3.stillValid(player);
        checks.check("s9.disconnect.breaking_comm_closes_remote_menu",
                validBeforeBreak && invalidAfterBreak,
                "before=" + validBeforeBreak + ", invalidAfter=" + invalidAfterBreak);

        // 5d: Non-adjacent station rejected immediately!
        CargoStationMenu nonAdjMenu =
                new CargoStationMenu(406, player.getInventory(), cargoTarget, COMM_SECOND, terminalId);
        checks.check("s9.disconnect.non_adjacent_station_rejected", !nonAdjMenu.stillValid(player));

        player.getInventory().clearContent();
        stage = Stage.WAIT_REMOTE_DRONE_ARRIVAL;
        stageStartTick = tick;
    }

    private void verifyStrictReadOnlyMenu(
            String label,
            AbstractLogisticsStationMenu menu,
            int machineSlots,
            AbstractLogisticsStationBlockEntity station,
            net.minecraft.world.item.Item outboundItem,
            int outboundCount,
            net.minecraft.world.item.Item storageItem,
            int storageCount,
            FakePlayer player,
            QaChecks checks
    ) {
        ItemStack probeStack = new ItemStack(Items.COBBLESTONE, 1);
        boolean allSlotsLocked = true;
        for (int i = 0; i < machineSlots; i++) {
            var slot = menu.getSlot(i);
            if (slot.mayPlace(probeStack)
                    || slot.mayPickup(player)
                    || slot.allowModification(player)
                    || menu.canDragTo(slot)) {
                allSlotsLocked = false;
                break;
            }
        }

        // quickMoveStack from machine Outbound(0), machine Storage(27), and player inv(machineSlots)
        ItemStack qmOut = menu.quickMoveStack(player, 0);
        ItemStack qmSto = menu.quickMoveStack(player, AbstractLogisticsStationBlockEntity.STORAGE_START);
        ItemStack qmPlr = menu.quickMoveStack(player, machineSlots);

        // clicked: PICKUP from machine slot 0
        menu.setCarried(ItemStack.EMPTY);
        QaPlatform.clickMenuPickup(menu, 0, 0, player);
        boolean cursorStillEmpty = menu.getCarried().isEmpty();

        // clicked: PICKUP placing into empty machine slot 1
        menu.setCarried(new ItemStack(Items.IRON_INGOT, 8));
        QaPlatform.clickMenuPickup(menu, 1, 0, player);
        boolean slot1StillEmpty = station.getItem(1).isEmpty()
                && menu.getCarried().is(Items.IRON_INGOT) && menu.getCarried().getCount() == 8;

        // clicked: PICKUP_ALL with matching outbound item on cursor from a player slot
        menu.setCarried(new ItemStack(outboundItem, 1));
        QaPlatform.clickMenuPickupAll(menu, machineSlots + 1, 0, player);
        boolean pickupAllBlocked = station.getItem(0).is(outboundItem)
                && station.getItem(0).getCount() == outboundCount;

        // clicked: QUICK_MOVE, SWAP, THROW against machine slot 0 and player slot
        menu.setCarried(ItemStack.EMPTY);
        QaPlatform.clickMenuQuickMove(menu, 0, 0, player);
        QaPlatform.clickMenuQuickMove(menu, machineSlots, 0, player);
        QaPlatform.clickMenuSwap(menu, 0, 1, player);
        QaPlatform.clickMenuThrow(menu, 0, 0, player);
        menu.setCarried(ItemStack.EMPTY);

        boolean inventoryUntouched = station.getItem(0).is(outboundItem)
                && station.getItem(0).getCount() == outboundCount
                && station.getItem(AbstractLogisticsStationBlockEntity.STORAGE_START).is(storageItem)
                && station.getItem(AbstractLogisticsStationBlockEntity.STORAGE_START).getCount() == storageCount
                && player.getInventory().getItem(9).is(Items.COBBLESTONE)
                && player.getInventory().getItem(9).getCount() == 16;

        checks.check("s9.readonly." + label + "_strict_slot_and_click_protection",
                allSlotsLocked
                        && qmOut.isEmpty() && qmSto.isEmpty() && qmPlr.isEmpty()
                        && cursorStillEmpty && slot1StillEmpty && pickupAllBlocked
                        && inventoryUntouched,
                String.format("locked=%s, qm=%s/%s/%s, pickup=%s, place=%s, pickupAll=%s, untouched=%s",
                        allSlotsLocked, qmOut.isEmpty(), qmSto.isEmpty(), qmPlr.isEmpty(),
                        cursorStillEmpty, slot1StillEmpty, pickupAllBlocked, inventoryUntouched));
    }

    private void tickWaitRemoteDroneArrival(ServerLevel level, int tick, QaChecks checks) {
        if (tick - stageStartTick > 250) {
            throw new AssertionError("Timeout waiting for Suite 9 remote-dispatched drone delivery");
        }
        var engine = LogisticsLevelManager.getData(level).engine();
        if (engine.getTransit(remoteDroneTransitId).isPresent()) {
            return;
        }
        CargoStationBlockEntity cargoTarget = (CargoStationBlockEntity) level.getBlockEntity(CARGO_TARGET);
        Map<String, Integer> storage = QaChecks.countStorageItems(cargoTarget);
        checks.check("s9.remote.dispatched_drone_delivered_cargo_to_target_storage",
                storage.getOrDefault("minecraft:gold_ingot", 0) == 8
                        && QaChecks.countOutboundItems(cargoTarget).isEmpty()
                        && cargoTarget.countReadyDrones() == 1,
                "targetStorage=" + storage + ", readyDrones=" + cargoTarget.countReadyDrones());

        // Cleanup Suite 9 blocks & unforce chunks
        for (BlockPos pos : List.of(
                GEN_A, CABLE_A, COMM_A, GEN_B, CABLE_B, COMM_B,
                COMM_DRAIN, COMM_MAIN, SMART_PORT_ADJ, CARGO_ORIGIN_ADJ,
                NON_STATION_CHEST, COMM_SECOND, CARGO_TARGET)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        QaChecks.setChunkRangeForced(level, 7, 9, 0, 2, false);
        stage = Stage.DONE;
    }

    private static UUID findDroneTransitFrom(ServerLevel level, BlockPos origin) {
        return LogisticsLevelManager.getData(level).engine().activeTransits().stream()
                .filter(t -> t.vehicleKind().isDrone()
                        && t.originPos().x() == origin.getX()
                        && t.originPos().y() == origin.getY()
                        && t.originPos().z() == origin.getZ())
                .map(t -> t.id())
                .findFirst()
                .orElse(null);
    }

    private static void invokeRemoteManualDispatch(
            ServerPlayer player,
            ServerLevel level,
            AbstractLogisticsStationBlockEntity station,
            AbstractLogisticsStationMenu remoteMenu
    ) {
        try {
            Method m = Lc2Network.class.getDeclaredMethod(
                    "handleRemoteManualDispatch",
                    ServerPlayer.class,
                    ServerLevel.class,
                    AbstractLogisticsStationBlockEntity.class,
                    AbstractLogisticsStationMenu.class
            );
            m.setAccessible(true);
            m.invoke(null, player, level, station, remoteMenu);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke Lc2Network.handleRemoteManualDispatch", e);
        }
    }
}
