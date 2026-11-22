package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.forge.Lc2Registries;
import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlock;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * World construction, container I/O, IC2 item lookup, and chunk-ticket helpers for LC2 Forge 1.19.2 QA.
 */
final class Lc2QaHelpers {

    private static final TicketType<ChunkPos> QA_TEMP_TICKET =
            TicketType.create("lc2_qa_temp", Comparator.comparingLong(ChunkPos::toLong));

    /** 48 distinct stackable vanilla items so 42+ stacks never merge into fewer slots. */
    private static final Item[] DISTINCT_CARGO_ITEMS = {
            Items.STONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
            Items.COBBLESTONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.SANDSTONE,
            Items.RED_SANDSTONE, Items.BRICKS, Items.NETHER_BRICKS, Items.QUARTZ_BLOCK,
            Items.PRISMARINE, Items.PRISMARINE_BRICKS, Items.DARK_PRISMARINE, Items.TERRACOTTA,
            Items.GLASS, Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL,
            Items.LIGHT_BLUE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL,
            Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL,
            Items.BLUE_WOOL, Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL,
            Items.BLACK_WOOL, Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT,
            Items.DIAMOND, Items.EMERALD, Items.LAPIS_LAZULI, Items.REDSTONE,
            Items.COAL, Items.CHARCOAL, Items.AMETHYST_SHARD, Items.QUARTZ
    };

    private Lc2QaHelpers() {
    }

    static Block requireBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Block block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || (block == Blocks.AIR && !"minecraft:air".equals(id))) {
            throw new AssertionError("Missing registered block: " + id);
        }
        return block;
    }

    static Item requireItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || (item == Items.AIR && !"minecraft:air".equals(id))) {
            throw new AssertionError("Missing registered item: " + id);
        }
        return item;
    }

    static ItemStack itemStack(String id, int count) {
        return new ItemStack(requireItem(id), count);
    }

    static List<ItemStack> distinctStacks(int count, int stackSize) {
        if (count > DISTINCT_CARGO_ITEMS.length) {
            throw new IllegalArgumentException("Requested " + count + " distinct items, max " + DISTINCT_CARGO_ITEMS.length);
        }
        List<ItemStack> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new ItemStack(DISTINCT_CARGO_ITEMS[i], stackSize));
        }
        return out;
    }

    static List<ItemStack> distinctStacksOffset(int offset, int count, int stackSize) {
        List<ItemStack> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new ItemStack(DISTINCT_CARGO_ITEMS[(offset + i) % DISTINCT_CARGO_ITEMS.length], stackSize));
        }
        return out;
    }

    static Boolean getCableDirectionProp(BlockState cableState, Direction dir) {
        String name = dir.getName();
        for (Property<?> prop : cableState.getProperties()) {
            if (prop instanceof BooleanProperty bp && bp.getName().equals(name)) {
                return cableState.getValue(bp);
            }
        }
        return null;
    }

    static BlockState withAllCableArmsFalse(BlockState cableState) {
        BlockState state = cableState;
        for (Direction dir : Direction.values()) {
            String name = dir.getName();
            for (Property<?> prop : state.getProperties()) {
                if (prop instanceof BooleanProperty bp && bp.getName().equals(name)) {
                    state = state.setValue(bp, Boolean.FALSE);
                }
            }
        }
        return state;
    }

    static SmartPortBlockEntity placeSmartPort(ServerLevel level, BlockPos pos, Direction facing, String name) {
        level.setBlock(pos, Lc2Registries.SMART_PORT_BLOCK.get().defaultBlockState()
                .setValue(SmartPortBlock.FACING, facing)
                .setValue(SmartPortBlock.ACTIVE, false), Block.UPDATE_ALL);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SmartPortBlockEntity port)) {
            throw new AssertionError("Expected SmartPortBlockEntity at " + pos + ", got " + be);
        }
        port.setStationName(name);
        port.refreshWaterBerth(level);
        port.syncRegistry(level);
        return port;
    }

    static CargoStationBlockEntity placeCargoStation(ServerLevel level, BlockPos pos, String name) {
        level.setBlock(pos, Lc2Registries.CARGO_STATION_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof CargoStationBlockEntity station)) {
            throw new AssertionError("Expected CargoStationBlockEntity at " + pos + ", got " + be);
        }
        station.setStationName(name);
        station.syncRegistry(level);
        return station;
    }

    static ElectricCargoBoatEntity spawnDockedBoatAt(ServerLevel level, SmartPortBlockEntity port) {
        var berth = port.refreshWaterBerth(level);
        if (berth == null) {
            throw new AssertionError("SmartPort at " + port.getBlockPos() + " has no adjacent water berth");
        }
        var surface = berth.waterSurfaceCenter();
        ElectricCargoBoatEntity boat = new ElectricCargoBoatEntity(Lc2Registries.ELECTRIC_CARGO_BOAT_ENTITY.get(), level);
        boat.setPos(surface.x(), surface.y(), surface.z());
        if (!level.addFreshEntity(boat)) {
            throw new AssertionError("Failed to spawn ElectricCargoBoatEntity at " + surface);
        }
        return boat;
    }

    /**
     * Builds a clean straight east-west water canal of width 3 ({@code zCenter - 1 .. zCenter + 1})
     * from {@code xMin} to {@code xMax} at {@code waterY}, with stone bed at {@code waterY - 1},
     * stone walls at {@code zCenter ± 2}, and clear air headroom at {@code waterY + 1 .. waterY + 2}.
     */
    static void buildCanalEastWest(ServerLevel level, int xMin, int xMax, int waterY, int zCenter) {
        for (int x = xMin - 1; x <= xMax + 1; x++) {
            for (int z = zCenter - 2; z <= zCenter + 2; z++) {
                level.setBlock(new BlockPos(x, waterY - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean isEndWall = (x == xMin - 1 || x == xMax + 1);
                boolean isSideWall = (z == zCenter - 2 || z == zCenter + 2);
                if (isEndWall || isSideWall) {
                    level.setBlock(new BlockPos(x, waterY, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                } else {
                    level.setBlock(new BlockPos(x, waterY, z), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                level.setBlock(new BlockPos(x, waterY + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(new BlockPos(x, waterY + 2, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    static void setEastWestDam(ServerLevel level, int x, int waterY, int zCenter, boolean blocked) {
        BlockState fill = blocked ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState();
        BlockState top = blocked ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int z = zCenter - 1; z <= zCenter + 1; z++) {
            level.setBlock(new BlockPos(x, waterY, z), fill, Block.UPDATE_ALL);
            level.setBlock(new BlockPos(x, waterY + 1, z), top, Block.UPDATE_ALL);
        }
    }

    static boolean insertItemIntoBlock(ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container c = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (c != null) {
                return insertIntoContainer(c, stack).isEmpty();
            }
        }
        // Try ForgeCapabilities.ITEM_HANDLER on null side then cardinal sides
        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler != null) {
            if (ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false).isEmpty()) {
                return true;
            }
        }
        for (Direction dir : Direction.values()) {
            IItemHandler sided = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir).orElse(null);
            if (sided != null) {
                if (ItemHandlerHelper.insertItemStacked(sided, stack.copy(), false).isEmpty()) {
                    return true;
                }
            }
        }
        if (be instanceof Container c) {
            return insertIntoContainer(c, stack).isEmpty();
        }
        return false;
    }

    private static ItemStack insertIntoContainer(Container c, ItemStack stack) {
        ItemStack rem = stack.copy();
        for (int i = 0; i < c.getContainerSize() && !rem.isEmpty(); i++) {
            ItemStack cur = c.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, rem)) {
                int space = Math.min(c.getMaxStackSize(), cur.getMaxStackSize()) - cur.getCount();
                int move = Math.min(space, rem.getCount());
                if (move > 0) {
                    cur.grow(move);
                    rem.shrink(move);
                    c.setChanged();
                }
            }
        }
        for (int i = 0; i < c.getContainerSize() && !rem.isEmpty(); i++) {
            if (c.getItem(i).isEmpty() && c.canPlaceItem(i, rem)) {
                int move = Math.min(c.getMaxStackSize(), rem.getCount());
                c.setItem(i, rem.split(move));
                c.setChanged();
            }
        }
        return rem;
    }

    static Map<Item, Integer> countItemsInBlocks(ServerLevel level, List<BlockPos> positions) {
        Map<Item, Integer> totals = new HashMap<>();
        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock chestBlock) {
                Container c = ChestBlock.getContainer(chestBlock, state, level, pos, true);
                if (c != null) {
                    accumulateContainer(c, totals);
                    continue;
                }
            }
            IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
            if (handler == null) {
                handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
            }
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack st = handler.getStackInSlot(i);
                    if (!st.isEmpty()) {
                        totals.merge(st.getItem(), st.getCount(), Integer::sum);
                    }
                }
            } else if (be instanceof Container c) {
                accumulateContainer(c, totals);
            }
        }
        return totals;
    }

    static Map<Item, Integer> countStationZone(
            AbstractLogisticsStationBlockEntity station,
            int startSlot,
            int endExclusive
    ) {
        Map<Item, Integer> totals = new HashMap<>();
        for (int i = startSlot; i < endExclusive; i++) {
            ItemStack st = station.getItem(i);
            if (!st.isEmpty()) {
                totals.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    static Map<Item, Integer> countStationOutbound(AbstractLogisticsStationBlockEntity station) {
        return countStationZone(
                station,
                AbstractLogisticsStationBlockEntity.OUTBOUND_START,
                AbstractLogisticsStationBlockEntity.OUTBOUND_END
        );
    }

    static Map<Item, Integer> countStationStorage(AbstractLogisticsStationBlockEntity station) {
        return countStationZone(
                station,
                AbstractLogisticsStationBlockEntity.STORAGE_START,
                AbstractLogisticsStationBlockEntity.STORAGE_END
        );
    }

    static int totalItemCount(Map<Item, Integer> map) {
        int sum = 0;
        for (int v : map.values()) {
            sum += v;
        }
        return sum;
    }

    static void clearContainerBlock(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        if (be instanceof Container c) {
            c.clearContent();
            c.setChanged();
            return;
        }
        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack st = handler.getStackInSlot(i);
                if (!st.isEmpty()) {
                    handler.extractItem(i, st.getCount(), false);
                }
            }
        }
    }

    private static void accumulateContainer(Container c, Map<Item, Integer> totals) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty()) {
                totals.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
    }

    static boolean isChunkCompletelyUnloaded(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
            return false;
        }
        return !level.isPositionEntityTicking(new BlockPos((chunkX << 4) + 8, level.getSeaLevel(), (chunkZ << 4) + 8));
    }

    static void qaHoldChunk(ServerLevel level, ChunkPos cp) {
        level.getChunkSource().addRegionTicket(QA_TEMP_TICKET, cp, 2, cp);
    }

    static void qaReleaseChunk(ServerLevel level, ChunkPos cp) {
        level.getChunkSource().removeRegionTicket(QA_TEMP_TICKET, cp, 2, cp);
    }
}
