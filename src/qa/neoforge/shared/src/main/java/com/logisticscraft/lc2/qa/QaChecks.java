package com.logisticscraft.lc2.qa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.platform.Lc2ItemAccess;
import com.logisticscraft.lc2.platform.Lc2Native;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared assertion recorder, structured JSON/Properties reporter, and world-construction utilities
 * for the live NeoForge LC2 QA harness.
 */
public final class QaChecks {

    private static final Logger LOGGER = LoggerFactory.getLogger(QaChecks.class);

    /** 45 distinct vanilla items used to verify 54-slot boat and 36-slot heavy drone cargo holds. */
    public static final List<Item> DISTINCT_CARGO_ITEMS = List.of(
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND, Items.EMERALD,
            Items.LAPIS_LAZULI, Items.REDSTONE, Items.COAL, Items.CHARCOAL, Items.QUARTZ,
            Items.AMETHYST_SHARD, Items.NETHERITE_SCRAP, Items.GLOWSTONE_DUST, Items.GUNPOWDER, Items.FLINT,
            Items.CLAY_BALL, Items.BRICK, Items.NETHER_BRICK, Items.SLIME_BALL, Items.MAGMA_CREAM,
            Items.BLAZE_POWDER, Items.PRISMARINE_SHARD, Items.PRISMARINE_CRYSTALS, Items.ENDER_PEARL, Items.BONE,
            Items.STRING, Items.FEATHER, Items.LEATHER, Items.PAPER, Items.BOOK,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.COBBLESTONE, Items.STONE, Items.GRANITE, Items.DIORITE,
            Items.ANDESITE, Items.DEEPSLATE, Items.SANDSTONE, Items.GLASS, Items.OBSIDIAN
    );

    private final Map<String, String> passedChecks = new LinkedHashMap<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    public void putMeta(String key, String value) {
        metadata.put(key, value);
    }

    public void check(String key, boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError("QA check failed [" + key + "]: " + detail);
        }
        passedChecks.put(key, detail == null || detail.isEmpty() ? "passed" : detail);
        LOGGER.info("[LC2-QA PASS] {}: {}", key, detail);
    }

    public void check(String key, boolean condition) {
        check(key, condition, "passed");
    }

    public int passedCount() {
        return passedChecks.size();
    }

    public void writeReports(String phase, int ticks, Throwable failure) throws IOException {
        long pid = ProcessHandle.current().pid();
        Properties props = new Properties();
        props.setProperty("phase", phase);
        props.setProperty("pid", Long.toString(pid));
        props.setProperty("ticks", Integer.toString(ticks));
        props.setProperty("status", failure == null ? "passed" : "failed");
        props.setProperty("passedCount", Integer.toString(passedChecks.size()));
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            props.setProperty(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : passedChecks.entrySet()) {
            props.setProperty("check." + e.getKey(), e.getValue());
        }
        if (failure != null) {
            props.setProperty("problem", failure.toString());
            StringWriter sw = new StringWriter();
            failure.printStackTrace(new PrintWriter(sw));
            props.setProperty("trace", sw.toString());
        }
        try (var writer = Files.newBufferedWriter(Path.of("qa-report-" + phase + ".properties"))) {
            props.store(writer, "LC2 NeoForge QA report (" + phase + ")");
        }

        JsonObject json = new JsonObject();
        json.addProperty("phase", phase);
        json.addProperty("pid", pid);
        json.addProperty("ticks", ticks);
        json.addProperty("status", failure == null ? "passed" : "failed");
        json.addProperty("passedCount", passedChecks.size());
        JsonObject metaObj = new JsonObject();
        metadata.forEach(metaObj::addProperty);
        json.add("metadata", metaObj);
        JsonArray checksArr = new JsonArray();
        for (Map.Entry<String, String> e : passedChecks.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("name", e.getKey());
            row.addProperty("status", "passed");
            row.addProperty("detail", e.getValue());
            checksArr.add(row);
        }
        json.add("checks", checksArr);
        if (failure != null) {
            json.addProperty("problem", failure.toString());
        }
        Files.writeString(
                Path.of("qa-report-" + phase + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n"
        );
    }

    public static Block block(String qualifiedId) {
        return BuiltInRegistries.BLOCK.stream()
                .filter(b -> BuiltInRegistries.BLOCK.getKey(b).toString().equals(qualifiedId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing registered block: " + qualifiedId));
    }

    public static Item item(String qualifiedId) {
        return BuiltInRegistries.ITEM.stream()
                .filter(i -> BuiltInRegistries.ITEM.getKey(i).toString().equals(qualifiedId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing registered item: " + qualifiedId));
    }

    public static boolean hasBlock(String qualifiedId) {
        return BuiltInRegistries.BLOCK.stream()
                .anyMatch(b -> BuiltInRegistries.BLOCK.getKey(b).toString().equals(qualifiedId));
    }

    public static boolean hasItem(String qualifiedId) {
        return BuiltInRegistries.ITEM.stream()
                .anyMatch(i -> BuiltInRegistries.ITEM.getKey(i).toString().equals(qualifiedId));
    }

    public static boolean hasBlockEntity(String qualifiedId) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.stream()
                .anyMatch(t -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t).toString().equals(qualifiedId));
    }

    public static boolean hasEntity(String qualifiedId) {
        return BuiltInRegistries.ENTITY_TYPE.stream()
                .anyMatch(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString().equals(qualifiedId));
    }

    public static boolean hasMenu(String qualifiedId) {
        return BuiltInRegistries.MENU.stream()
                .anyMatch(t -> BuiltInRegistries.MENU.getKey(t).toString().equals(qualifiedId));
    }

    /** Places a block via real FakePlayer survival placement so {@code getStateForPlacement} executes. */
    public static void survivalPlace(ServerLevel level, FakePlayer player, String qualifiedBlockId, BlockPos pos) {
        Block expected = block(qualifiedBlockId);
        BlockPos floor = pos.below();
        if (level.getBlockState(floor).isAir()) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        if (!level.getBlockState(pos).isAir()) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        ItemStack stack = new ItemStack(expected);
        player.setPos(pos.getX() + 2.5, pos.getY(), pos.getZ() + 0.5);
        player.setYRot(90.0F);
        player.setYHeadRot(90.0F);
        player.setXRot(0.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, floor, false);
        var result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction() || !level.getBlockState(pos).is(expected)) {
            throw new AssertionError("Survival placement failed for " + qualifiedBlockId + " at " + pos
                    + " (result=" + result + ", block=" + level.getBlockState(pos).getBlock() + ")");
        }
    }

    /** Carves a stone-lined navigable water canal (water at {@code waterY}, stone bed below, air above). */
    public static void buildCanal(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int waterY) {
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                boolean inside = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                level.setBlock(new BlockPos(x, waterY - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (inside) {
                    level.setBlock(new BlockPos(x, waterY, z), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(x, waterY + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(x, waterY + 2, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                } else {
                    BlockPos wallPos = new BlockPos(x, waterY, z);
                    if (level.getBlockState(wallPos).isAir() || level.getBlockState(wallPos).is(Blocks.WATER)) {
                        level.setBlock(wallPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /** Force-loads or unforces a rectangular range of chunk coordinates. */
    public static void setChunkRangeForced(ServerLevel level, int minCx, int maxCx, int minCz, int maxCz, boolean forced) {
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                level.setChunkForced(cx, cz, forced);
                if (forced) {
                    level.getChunk(cx, cz);
                }
            }
        }
    }

    /** Pushes an ItemStack into a container/capability at {@code pos} and asserts all items were accepted. */
    public static void insertOrFail(ServerLevel level, BlockPos pos, ItemStack stack) {
        int pushed = Lc2ItemAccess.push(level, pos, Direction.UP, stack);
        if (pushed != stack.getCount()) {
            throw new AssertionError("Expected to insert " + stack.getCount() + "x " + Lc2Native.itemId(stack)
                    + " into " + pos + " but inserted " + pushed);
        }
    }

    /** Counts items by qualified item ID across one or more container positions. */
    public static Map<String, Integer> countItemsAcross(ServerLevel level, BlockPos... positions) {
        Map<String, Integer> counts = new TreeMap<>();
        for (BlockPos pos : positions) {
            for (ItemStack stack : QaPlatform.readAllStacks(level, pos)) {
                counts.merge(Lc2Native.itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Counts total distinct non-empty stacks across one or more container positions. */
    public static int countStacksAcross(ServerLevel level, BlockPos... positions) {
        int total = 0;
        for (BlockPos pos : positions) {
            total += QaPlatform.readAllStacks(level, pos).size();
        }
        return total;
    }

    /** Invokes protected {@code ElectricCargoBoatEntity#canAddPassenger(Entity)} directly for QA verification. */
    public static boolean invokeCanAddPassenger(ElectricCargoBoatEntity boat, Entity passenger) {
        try {
            Method m = ElectricCargoBoatEntity.class.getDeclaredMethod("canAddPassenger", Entity.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(boat, passenger);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke ElectricCargoBoatEntity.canAddPassenger", e);
        }
    }
}
