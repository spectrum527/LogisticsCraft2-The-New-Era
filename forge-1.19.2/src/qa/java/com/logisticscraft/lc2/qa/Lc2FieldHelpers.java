package com.logisticscraft.lc2.qa;

import com.industrialage.ia2.power.EuBuffer;
import com.industrialage.ia2.world.MachineAutomation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Natural-world scouting, IC2 machine configuration, and Comparator redstone automation helpers
 * for the Forge 1.19.2 real-world field test.
 */
final class Lc2FieldHelpers {

    static final Set<String> NATURAL_METAL_ORE_BLOCKS = Set.of(
            "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "ic2:tin_ore",
            "ic2:deepslate_tin_ore",
            "ic2:lead_ore",
            "ic2:deepslate_lead_ore"
    );

    static final Set<String> NATURAL_ANY_ORE_BLOCKS = Set.of(
            "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore",
            "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore",
            "minecraft:lapis_ore",
            "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore",
            "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore",
            "minecraft:deepslate_emerald_ore",
            "ic2:tin_ore",
            "ic2:deepslate_tin_ore",
            "ic2:lead_ore",
            "ic2:deepslate_lead_ore",
            "ic2:uranium_ore",
            "ic2:deepslate_uranium_ore"
    );

    static final Set<String> RAW_METAL_ORE_ITEMS = Set.of(
            "minecraft:raw_iron",
            "minecraft:raw_copper",
            "minecraft:raw_gold",
            "ic2:raw_tin",
            "ic2:raw_lead",
            "ic2:raw_uranium",
            "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "ic2:tin_ore",
            "ic2:deepslate_tin_ore",
            "ic2:lead_ore",
            "ic2:deepslate_lead_ore"
    );

    static final Set<String> CRUSHED_ORE_ITEMS = Set.of(
            "ic2:crushed_iron",
            "ic2:crushed_copper",
            "ic2:crushed_gold",
            "ic2:crushed_tin",
            "ic2:crushed_lead",
            "ic2:crushed_silver",
            "ic2:crushed_uranium"
    );

    static final Set<String> FINISHED_INGOT_ITEMS = Set.of(
            "minecraft:iron_ingot",
            "minecraft:copper_ingot",
            "minecraft:gold_ingot",
            "ic2:tin_ingot",
            "ic2:lead_ingot",
            "ic2:silver_ingot",
            "ic2:uranium_ingot"
    );

    private Lc2FieldHelpers() {
    }

    static String blockId(Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key == null ? "" : key.toString();
    }

    static String itemId(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "" : key.toString();
    }

    static boolean isNaturalWaterPlant(BlockState state) {
        String id = blockId(state.getBlock());
        return "minecraft:seagrass".equals(id)
                || "minecraft:tall_seagrass".equals(id)
                || "minecraft:kelp".equals(id)
                || "minecraft:kelp_plant".equals(id);
    }

    static int countMatchingItems(Map<Item, Integer> map, Set<String> allowedIds) {
        int sum = 0;
        for (Map.Entry<Item, Integer> e : map.entrySet()) {
            if (allowedIds.contains(itemId(e.getKey()))) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    /**
     * Places a block with its horizontal or directional {@code facing} property set to {@code facing}
     * if the block declares a matching {@link DirectionProperty}.
     */
    static BlockState withFacingIfPresent(Block block, Direction facing) {
        BlockState state = block.defaultBlockState();
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof DirectionProperty dp && dp.getName().equals("facing")
                    && dp.getPossibleValues().contains(facing)) {
                return state.setValue(dp, facing);
            }
        }
        return state;
    }

    /**
     * Inserts {@code stack} into the first compatible empty machine slot exposed by {@link MenuProvider}
     * (strictly using public vanilla {@link Slot#mayPlace(ItemStack)}).
     */
    static boolean insertIntoMachineMenuSlot(
            ServerLevel level,
            BlockPos pos,
            FakePlayer player,
            ItemStack stack
    ) {
        return insertIntoMachineMenuSlot(level, pos, player, stack, false);
    }

    static boolean insertIntoMachineMenuSlot(
            ServerLevel level,
            BlockPos pos,
            FakePlayer player,
            ItemStack stack,
            boolean allowMerge
    ) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof MenuProvider provider)) {
            return false;
        }
        AbstractContainerMenu menu = provider.createMenu(0, player.getInventory(), player);
        if (menu == null) {
            return false;
        }
        // First pass: merge if requested
        if (allowMerge) {
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory()) {
                    continue;
                }
                ItemStack existing = slot.getItem();
                if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack) && slot.mayPlace(stack)) {
                    int space = Math.min(slot.getMaxStackSize(stack), existing.getMaxStackSize()) - existing.getCount();
                    if (space >= stack.getCount()) {
                        existing.grow(stack.getCount());
                        slot.setChanged();
                        be.setChanged();
                        return true;
                    }
                }
            }
        }
        // Second pass: place into first empty compatible slot
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            if (!slot.hasItem() && slot.mayPlace(stack)) {
                ItemStack copy = stack.copy();
                int limit = slot.getMaxStackSize(copy);
                if (copy.getCount() > limit) {
                    copy.setCount(limit);
                }
                slot.set(copy);
                slot.setChanged();
                be.setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * Logs every non-player menu slot of a machine BlockEntity for diagnostic clarity.
     */
    static String describeMachineSlots(ServerLevel level, BlockPos pos, FakePlayer player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof MenuProvider provider)) {
            return "not-a-MenuProvider(" + be + ")";
        }
        AbstractContainerMenu menu = provider.createMenu(0, player.getInventory(), player);
        if (menu == null) {
            return "null-menu";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            ItemStack st = slot.getItem();
            sb.append(idx).append('=').append(st.isEmpty() ? "empty" : (st.getCount() + "x" + itemId(st.getItem())));
            idx++;
        }
        if (be instanceof EuBuffer buf) {
            EuBuffer.Reading r = buf.bufferedEu();
            sb.append(" | EU=").append(String.format("%.1f/%.1f", r.stored(), r.capacity()));
        }
        return sb.toString();
    }

    /**
     * Configures an IC2 {@code pulling_upgrade} or {@code ejector_upgrade} stack to target a single
     * {@code targetFace} using vanilla {@link ItemStack#useOn(UseOnContext)} and verifies the
     * resulting direction via {@link MachineAutomation#faces(ItemStack)}.
     */
    static ItemStack configureUpgradeFace(
            ServerLevel level,
            FakePlayer player,
            BlockPos anchorPos,
            String upgradeId,
            Direction targetFace
    ) {
        // Try normal & sneak click on both targetFace and targetFace.getOpposite() so we match IC2's face convention cleanly
        Direction[] probeFaces = {targetFace, targetFace.getOpposite()};
        boolean[] sneakModes = {true, false};
        for (boolean sneak : sneakModes) {
            for (Direction clickFace : probeFaces) {
                ItemStack stack = Lc2QaHelpers.itemStack(upgradeId, 1);
                player.setShiftKeyDown(sneak);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(anchorPos),
                        clickFace,
                        anchorPos,
                        false
                );
                // Real Forge block right-click path fires PlayerInteractEvent.RightClickBlock before Item.useOn
                PlayerInteractEvent.RightClickBlock evt = new PlayerInteractEvent.RightClickBlock(
                        player,
                        InteractionHand.MAIN_HAND,
                        anchorPos,
                        hit
                );
                MinecraftForge.EVENT_BUS.post(evt);
                if (!evt.isCanceled()) {
                    stack.useOn(new UseOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));
                }
                List<Direction> resolved = MachineAutomation.faces(stack);
                if (resolved.size() == 1 && resolved.get(0) == targetFace) {
                    player.setShiftKeyDown(false);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    return stack;
                }
            }
        }
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        throw new AssertionError("Failed to bind " + upgradeId + " to single face " + targetFace
                + " via real useOn interaction");
    }

    /**
     * Builds a 100% flat, above-waterline vanilla Comparator feedback loop at {@code Y = stationPos.getY()}:
     * Comparator at {@code backDir} reads {@code stationPos}'s analog signal ({@code 1..15}) ->
     * boost Repeater at {@code backDir * 2} restores strength to 15 -> flat Redstone Dust L-loop around
     * {@code leftDir} -> return Repeater at {@code leftDir} pointing straight into {@code stationPos}!
     * Keeps {@code frontDir} (waterfront), {@code rightDir} (IC2 cable), {@code UP} (roof), and
     * {@code DOWN} (Iron Storage Box) 100% unobstructed and completely above sea level.
     */
    static BlockPos buildFlatAboveWaterComparatorLoop(
            ServerLevel level,
            BlockPos stationPos,
            Direction frontDir
    ) {
        Direction backDir = frontDir.getOpposite();
        Direction leftDir = frontDir.getCounterClockWise();

        BlockPos compPos = stationPos.relative(backDir);
        BlockPos boostRepPos = compPos.relative(backDir);
        BlockPos d1 = boostRepPos.relative(backDir);
        BlockPos d2 = d1.relative(leftDir);
        BlockPos d3 = d2.relative(leftDir);
        BlockPos d4 = boostRepPos.relative(leftDir, 2);
        BlockPos d5 = compPos.relative(leftDir, 2);
        BlockPos d6 = stationPos.relative(leftDir, 2);
        BlockPos returnRepPos = stationPos.relative(leftDir);

        for (BlockPos p : List.of(compPos, boostRepPos, d1, d2, d3, d4, d5, d6, returnRepPos)) {
            level.setBlock(p.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // Comparator reads stationPos (back = frontDir -> reads stationPos, outputs toward backDir)
        level.setBlock(
                compPos,
                Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, frontDir),
                Block.UPDATE_ALL
        );
        // Boost repeater restores signal 1..15 to 15
        level.setBlock(
                boostRepPos,
                Blocks.REPEATER.defaultBlockState()
                        .setValue(RepeaterBlock.FACING, frontDir)
                        .setValue(RepeaterBlock.DELAY, 1),
                Block.UPDATE_ALL
        );
        for (BlockPos dust : List.of(d1, d2, d3, d4, d5, d6)) {
            level.setBlock(dust, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Return repeater at stationPos.relative(leftDir): FACING = leftDir -> reads d6 (leftDir), outputs rightDir into stationPos!
        level.setBlock(
                returnRepPos,
                Blocks.REPEATER.defaultBlockState()
                        .setValue(RepeaterBlock.FACING, leftDir)
                        .setValue(RepeaterBlock.DELAY, 1),
                Block.UPDATE_ALL
        );

        level.updateNeighborsAt(stationPos, level.getBlockState(stationPos).getBlock());
        level.updateNeighbourForOutputSignal(stationPos, level.getBlockState(stationPos).getBlock());
        return compPos;
    }

    /**
     * Builds the compact, 100% vanilla Comparator feedback loop reading {@code stationPos}'s
     * {@code getAnalogOutputSignal} from {@code backDir} and routing it into {@code stationPos.below()}
     * via a Repeater on {@code rightDir}, keeping top, front, left (miner), and right (cable)
     * completely unobstructed! (Use on dry hillside terrain above sea level.)
     *
     * @return the {@link BlockPos} of the placed {@link ComparatorBlock}
     */
    static BlockPos buildComparatorAutomationLoop(
            ServerLevel level,
            BlockPos stationPos,
            Direction frontDir
    ) {
        Direction backDir = frontDir.getOpposite();
        Direction rightDir = frontDir.getClockWise();

        // Solid foundation under station and circuit:
        // Comparator at backDir reads analog signal (1..15) -> boost Repeater at backDir*2 restores 1 -> 15
        // -> dust at backDir*3 -> backDir*3+right -> drops down at backDir*2+right (Y-1)
        // -> backDir+right (Y-1) -> backDir+right*2 (Y-1) -> right*2 (Y-1)
        // -> Repeater at right (Y-1) strongly powering stationPos.below()!
        BlockPos underStation = stationPos.below();
        BlockPos compPos = stationPos.relative(backDir);
        BlockPos boostRepPos = compPos.relative(backDir);
        BlockPos dust1Pos = boostRepPos.relative(backDir);
        BlockPos dust2Pos = dust1Pos.relative(rightDir);
        BlockPos dropAirPos = boostRepPos.relative(rightDir);
        BlockPos lowDustA = dropAirPos.below();
        BlockPos lowDustB = compPos.below().relative(rightDir);
        BlockPos lowDustC = compPos.below().relative(rightDir, 2);
        BlockPos lowDustD = underStation.relative(rightDir, 2);
        BlockPos repeaterPos = underStation.relative(rightDir);

        for (BlockPos support : List.of(
                underStation,
                compPos.below(),
                boostRepPos.below(),
                dust1Pos.below(),
                dust2Pos.below(),
                lowDustA.below(),
                lowDustB.below(),
                lowDustC.below(),
                lowDustD.below(),
                repeaterPos.below()
        )) {
            level.setBlock(support, Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        for (BlockPos clear : List.of(
                compPos, compPos.above(),
                boostRepPos, boostRepPos.above(),
                dust1Pos, dust1Pos.above(),
                dust2Pos, dust2Pos.above(),
                dropAirPos, dropAirPos.above(),
                lowDustB.above(),
                lowDustC.above(),
                lowDustD.above(),
                repeaterPos.above()
        )) {
            level.setBlock(clear, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // Comparator reads stationPos (back = frontDir -> reads stationPos, outputs toward backDir)
        level.setBlock(
                compPos,
                Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, frontDir),
                Block.UPDATE_ALL
        );
        // Boost repeater guarantees even Comparator signal 1 (1 single ore in 27-slot buffer!) propagates at strength 15
        level.setBlock(
                boostRepPos,
                Blocks.REPEATER.defaultBlockState()
                        .setValue(RepeaterBlock.FACING, frontDir)
                        .setValue(RepeaterBlock.DELAY, 1),
                Block.UPDATE_ALL
        );
        level.setBlock(dust1Pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(dust2Pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lowDustA, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lowDustB, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lowDustC, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lowDustD, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        // Final repeater at underStation.relative(rightDir) reads lowDustD (rightDir) and strongly powers underStation!
        level.setBlock(
                repeaterPos,
                Blocks.REPEATER.defaultBlockState()
                        .setValue(RepeaterBlock.FACING, rightDir)
                        .setValue(RepeaterBlock.DELAY, 1),
                Block.UPDATE_ALL
        );

        level.updateNeighborsAt(stationPos, level.getBlockState(stationPos).getBlock());
        level.updateNeighbourForOutputSignal(stationPos, level.getBlockState(stationPos).getBlock());
        return compPos;
    }

    /**
     * Checks if {@code comparatorPos} currently has {@code POWERED == true}.
     */
    static boolean isComparatorPowered(ServerLevel level, BlockPos comparatorPos) {
        BlockState state = level.getBlockState(comparatorPos);
        return state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    /**
     * Computes look yaw and pitch from camera eye {@code from} to target world point {@code to}.
     */
    static float[] lookAngles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(-Math.atan2(dy, Math.max(1e-5, horiz))));
        return new float[]{yaw, pitch};
    }
}
