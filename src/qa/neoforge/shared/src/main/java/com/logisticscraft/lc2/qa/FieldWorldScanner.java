package com.logisticscraft.lc2.qa;

import com.logisticscraft.lc2.core.BlockPos3i;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.WaterPathResult;
import com.logisticscraft.lc2.core.WaterPathfinder;
import com.logisticscraft.lc2.world.LevelWaterGrid;
import com.logisticscraft.lc2.world.LogisticsSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scouts a genuine natural {@code level-type=minecraft:normal} overworld for two real shorelines
 * connected by a 55..140 block natural river/bay waterway, natural underground metallic ore veins
 * beneath both Mining Outpost A miners, and an elevated hillside overlooking the river.
 */
public final class FieldWorldScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldWorldScanner.class);

    private static final Set<String> SMELTABLE_METALLIC_ORES = Set.of(
            "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "mhera:tin_ore",
            "mhera:deepslate_tin_ore",
            "mhera:lead_ore",
            "mhera:deepslate_lead_ore",
            "mhera:silver_ore",
            "mhera:deepslate_silver_ore"
    );

    private static final Set<String> COAL_ORES = Set.of(
            "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore"
    );

    private static final Set<String> AQUATIC_PLANTS = Set.of(
            "minecraft:seagrass",
            "minecraft:tall_seagrass",
            "minecraft:kelp",
            "minecraft:kelp_plant"
    );

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private FieldWorldScanner() {
    }

    /** Discovered natural-world coordinates for Mining Outpost A, Hillside Airfield, and Refining Base B. */
    public record FieldSite(
            long seed,
            int waterY,
            BlockPos portA,
            Direction waterDirA,
            BlockPos berthA,
            BlockPos advMinerPos,
            int advMinerShallowMetallicOres,
            int advMinerFirstOreDepth,
            BlockPos hillsideCargoPos,
            BlockPos basicMinerPos,
            Direction basicMinerDirFromStation,
            int basicMinerShaftMetallicOres,
            int basicMinerShaftCoalOres,
            int basicMinerFirstOreDepth,
            BlockPos portB,
            Direction waterDirB,
            BlockPos berthB,
            Direction baseFlowDir,
            List<Vec3d> verifiedWaypoints,
            int waterwayDistanceBlocks,
            int aquaticVegetationNearRoute,
            String biomeA,
            String biomeB
    ) {
    }

    private record ShoreCandidate(
            BlockPos portPos,
            Direction waterDir,
            BlockPos berthPos,
            int landScore,
            int advOres,
            int advFirstDepth
    ) {
    }

    private record HillsideCandidate(
            BlockPos stationPos,
            BlockPos minerPos,
            Direction minerDirFromStation,
            int metallicOres,
            int coalOres,
            int firstOreDepth,
            int elevationAboveWater
    ) {
    }

    /** Scans natural terrain around world spawn and returns the highest-scoring natural production-line site. */
    public static FieldSite findNaturalSite(ServerLevel level) {
        BlockPos spawn = QaPlatform.spawnPos(level);
        int spawnCx = spawn.getX() >> 4;
        int spawnCz = spawn.getZ() >> 4;
        int waterY = level.getSeaLevel() - 1;
        LOGGER.info("[LC2-FIELD] Scanning natural world seed={} around spawn={} (seaLevel={}, waterY={})",
                level.getSeed(), spawn, level.getSeaLevel(), waterY);

        for (int chunkRadius : new int[]{8, 12}) {
            FieldSite found = scanRadius(level, spawnCx, spawnCz, chunkRadius, waterY);
            if (found != null) {
                return found;
            }
            LOGGER.warn("[LC2-FIELD] Expanding natural terrain search to chunkRadius={}", chunkRadius + 4);
        }
        throw new AssertionError("Could not locate a connected 55..140 block natural waterway with ores on seed "
                + level.getSeed());
    }

    private static FieldSite scanRadius(
            ServerLevel level,
            int centerCx,
            int centerCz,
            int chunkRadius,
            int waterY
    ) {
        // Ensure chunks in scan radius are generated and loaded
        for (int cx = centerCx - chunkRadius; cx <= centerCx + chunkRadius; cx++) {
            for (int cz = centerCz - chunkRadius; cz <= centerCz + chunkRadius; cz++) {
                level.getChunk(cx, cz);
            }
        }

        LevelWaterGrid grid = LevelWaterGrid.loadedOnly(level);
        int minX = ((centerCx - chunkRadius) << 4) + 12;
        int maxX = ((centerCx + chunkRadius) << 4) + 4;
        int minZ = ((centerCz - chunkRadius) << 4) + 12;
        int maxZ = ((centerCz + chunkRadius) << 4) + 4;

        List<ShoreCandidate> allShores = new ArrayList<>();
        // Step every 2 blocks to scan quickly while catching every genuine shoreline
        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) {
                BlockPos berth = new BlockPos(x, waterY, z);
                if (!isOpenNaturalWater(level, grid, berth)) {
                    continue;
                }
                for (Direction waterDir : HORIZONTALS) {
                    Direction back = waterDir.getOpposite();
                    BlockPos portPos = berth.relative(back).above();
                    if (!isNaturalSolidBank(level, portPos.below())) {
                        continue;
                    }
                    int landScore = evaluateLandFootprint(level, portPos, back, waterY);
                    if (landScore < 14) {
                        continue;
                    }
                    // Score shallow natural metallic ores under the Advanced Miner spot (2 blocks behind portA)
                    BlockPos advMinerPos = portPos.relative(back, 2);
                    int[] advStats = countMetallicOresInVolume(level, advMinerPos, 8, 18);
                    allShores.add(new ShoreCandidate(portPos, waterDir, berth, landScore, advStats[0], advStats[1]));
                }
            }
        }

        LOGGER.info("[LC2-FIELD] Discovered {} natural shoreline candidates within radius {} chunks",
                allShores.size(), chunkRadius);
        if (allShores.isEmpty()) {
            return null;
        }

        // Index shores by berth for quick lookup during water BFS
        Map<BlockPos, List<ShoreCandidate>> shoreByBerth = new HashMap<>();
        for (ShoreCandidate c : allShores) {
            shoreByBerth.computeIfAbsent(c.berthPos(), ignored -> new ArrayList<>()).add(c);
        }

        // Rank Shore A candidates by shallow metallic ore density and natural land quality
        List<ShoreCandidate> topShoreA = allShores.stream()
                .filter(c -> c.advOres() >= 4 && c.advFirstDepth() <= 14)
                .sorted(Comparator
                        .comparingInt((ShoreCandidate c) -> c.advFirstDepth() <= 8 ? 0 : 1)
                        .thenComparingInt(c -> -Math.min(c.advOres(), 40))
                        .thenComparingInt(c -> -c.landScore()))
                .toList();

        // Keep spatially diverse top Shore A picks so we explore different river/bay sections
        List<ShoreCandidate> diverseA = new ArrayList<>();
        for (ShoreCandidate c : topShoreA) {
            boolean tooClose = diverseA.stream().anyMatch(other -> other.portPos().distSqr(c.portPos()) < 16 * 16);
            if (!tooClose) {
                diverseA.add(c);
                if (diverseA.size() >= 24) {
                    break;
                }
            }
        }

        FieldSite bestSite = null;
        int bestScore = Integer.MIN_VALUE;

        for (ShoreCandidate shoreA : diverseA) {
            // Find a hillside mining & drone spot near Shore A first
            HillsideCandidate hill = findHillsideForOutpost(level, shoreA, waterY);
            if (hill == null) {
                continue;
            }

            // BFS along real natural water from Shore A to find reachable Shore B at 55..140 blocks
            List<ShoreCandidate> reachableB = findReachableShoreB(grid, shoreA, shoreByBerth);
            if (reachableB.isEmpty()) {
                continue;
            }

            // Try top 6 Shore B candidates for this Shore A
            for (int i = 0; i < Math.min(6, reachableB.size()); i++) {
                ShoreCandidate shoreB = reachableB.get(i);
                Direction flowDir = pickBestBaseFlowDir(level, shoreB, waterY);
                if (flowDir == null) {
                    continue;
                }
                WaterPathResult path = WaterPathfinder.findPath(
                        grid,
                        LogisticsSavedData.toPos3i(shoreA.berthPos()),
                        LogisticsSavedData.toPos3i(shoreB.berthPos())
                );
                if (!path.connected() || path.waypoints().size() < 2) {
                    continue;
                }

                double pathLen = 0.0;
                for (int w = 0; w + 1 < path.waypoints().size(); w++) {
                    pathLen += path.waypoints().get(w).horizontalDistanceTo(path.waypoints().get(w + 1));
                }
                int waterDist = (int) Math.round(pathLen);
                if (waterDist < 55 || waterDist > 165) {
                    continue;
                }
                int plants = countAquaticVegetationAlongPath(level, path.waypoints(), waterY);

                // Compute overall site quality score:
                // - prefers 70..115 block waterway with bends (waypoints >= 3) & natural aquatic vegetation
                // - prefers shallow natural metallic ores under both miners and zero coal in top shaft
                // - prefers scenic elevated hillside
                int distPenalty = Math.abs(waterDist - 85);
                int score = 1000
                        - distPenalty * 4
                        + Math.min(plants, 25) * 8
                        + (path.waypoints().size() >= 3 ? 80 : 0)
                        + Math.min(shoreA.advOres(), 30) * 6
                        + Math.max(0, 12 - shoreA.advFirstDepth()) * 15
                        + Math.min(hill.metallicOres(), 20) * 10
                        + Math.max(0, 20 - hill.firstOreDepth()) * 12
                        - hill.coalOres() * 25
                        + Math.min(hill.elevationAboveWater(), 14) * 10
                        + shoreB.landScore() * 3;

                if (score > bestScore) {
                    bestScore = score;
                    bestSite = new FieldSite(
                            level.getSeed(),
                            waterY,
                            shoreA.portPos(),
                            shoreA.waterDir(),
                            shoreA.berthPos(),
                            shoreA.portPos().relative(shoreA.waterDir().getOpposite(), 2),
                            shoreA.advOres(),
                            shoreA.advFirstDepth(),
                            hill.stationPos(),
                            hill.minerPos(),
                            hill.minerDirFromStation(),
                            hill.metallicOres(),
                            hill.coalOres(),
                            hill.firstOreDepth(),
                            shoreB.portPos(),
                            shoreB.waterDir(),
                            shoreB.berthPos(),
                            flowDir,
                            path.waypoints(),
                            waterDist,
                            plants,
                            QaPlatform.biomeId(level, shoreA.portPos()),
                            QaPlatform.biomeId(level, shoreB.portPos())
                    );
                }
            }
        }
        return bestSite;
    }

    private static List<ShoreCandidate> findReachableShoreB(
            LevelWaterGrid grid,
            ShoreCandidate shoreA,
            Map<BlockPos, List<ShoreCandidate>> shoreByBerth
    ) {
        BlockPos start = shoreA.berthPos();
        int waterY = start.getY();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        List<ShoreCandidate> found = new ArrayList<>();
        int maxVisited = 18_000;
        while (!queue.isEmpty() && visited.size() < maxVisited) {
            BlockPos cur = queue.poll();
            int manhattan = Math.abs(cur.getX() - start.getX()) + Math.abs(cur.getZ() - start.getZ());
            if (manhattan >= 55 && manhattan <= 145) {
                List<ShoreCandidate> atBerth = shoreByBerth.get(cur);
                if (atBerth != null) {
                    for (ShoreCandidate b : atBerth) {
                        if (b.landScore() >= 18) {
                            found.add(b);
                        }
                    }
                }
            }
            if (manhattan > 155) {
                continue;
            }
            for (Direction d : HORIZONTALS) {
                BlockPos next = new BlockPos(cur.getX() + d.getStepX(), waterY, cur.getZ() + d.getStepZ());
                if (visited.contains(next)) {
                    continue;
                }
                if (grid.isBoatPassable(next.getX(), waterY, next.getZ())) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        found.sort(Comparator
                .comparingInt((ShoreCandidate b) -> Math.abs(manhattan2D(b.portPos(), shoreA.portPos()) - 85))
                .thenComparingInt(b -> -b.landScore()));

        // Deduplicate close Shore B candidates
        List<ShoreCandidate> diverseB = new ArrayList<>();
        for (ShoreCandidate b : found) {
            if (diverseB.stream().noneMatch(o -> o.portPos().distSqr(b.portPos()) < 12 * 12)) {
                diverseB.add(b);
                if (diverseB.size() >= 12) {
                    break;
                }
            }
        }
        return diverseB;
    }

    private static HillsideCandidate findHillsideForOutpost(
            ServerLevel level,
            ShoreCandidate shoreA,
            int waterY
    ) {
        BlockPos origin = shoreA.portPos();
        HillsideCandidate best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int dx = -34; dx <= 34; dx += 2) {
            for (int dz = -34; dz <= 34; dz += 2) {
                int distSq = dx * dx + dz * dz;
                if (distSq < 11 * 11 || distSq > 34 * 34) {
                    continue;
                }
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int groundY = findNaturalGroundY(level, x, z);
                if (groundY < waterY + 1 || groundY > waterY + 24) {
                    continue;
                }
                BlockPos stationPos = new BlockPos(x, groundY + 1, z);
                // Ensure the hillside spot is solid land (not in water)
                if (!isNaturalSolidBank(level, stationPos.below())) {
                    continue;
                }
                BlockPos minerPos = stationPos.north();
                if (!isNaturalSolidBank(level, minerPos.below())) {
                    continue;
                }
                // Check drill shaft straight down from minerPos is dry & drillable for top 32 blocks
                if (!isShaftDryAndDrillable(level, minerPos, 32)) {
                    continue;
                }
                int[] shaftStats = countShaftOres(level, minerPos, 3, 28);
                int metallic = shaftStats[0];
                int firstMetallicDepth = shaftStats[1];
                int coalAboveOrNear = shaftStats[2];
                if (metallic < 2 || firstMetallicDepth > 24) {
                    continue;
                }
                int elev = groundY - waterY;
                int score = metallic * 12
                        + Math.max(0, 24 - firstMetallicDepth) * 14
                        - coalAboveOrNear * 40
                        + Math.min(elev, 12) * 10
                        - Math.abs((int) Math.sqrt(distSq) - 18) * 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = new HillsideCandidate(
                            stationPos,
                            minerPos,
                            Direction.NORTH,
                            metallic,
                            coalAboveOrNear,
                            firstMetallicDepth,
                            elev
                    );
                }
            }
        }
        return best;
    }

    private static Direction pickBestBaseFlowDir(ServerLevel level, ShoreCandidate shoreB, int waterY) {
        Direction back = shoreB.waterDir().getOpposite();
        Direction cw = shoreB.waterDir().getClockWise();
        Direction ccw = shoreB.waterDir().getCounterClockWise();
        BlockPos rch = shoreB.portPos().relative(back);

        Direction best = null;
        int bestSolid = -1;
        for (Direction flow : new Direction[]{cw, ccw}) {
            int solidCount = 0;
            // Check CargoStation at rch.relative(flow.getOpposite()), Macerator at +1, Furnace at +2, OutBox at +3,
            // plus rear power bus behind them
            for (int along = -1; along <= 3; along++) {
                for (int depth = 0; depth <= 2; depth++) {
                    BlockPos p = rch.relative(flow, along).relative(back, depth);
                    if (p.getY() - 1 >= waterY && isNaturalSolidOrFillable(level, p.below())) {
                        solidCount++;
                    }
                }
            }
            if (solidCount >= 10 && solidCount > bestSolid) {
                bestSolid = solidCount;
                best = flow;
            }
        }
        return best;
    }

    private static boolean isOpenNaturalWater(ServerLevel level, LevelWaterGrid grid, BlockPos pos) {
        if (!grid.isBoatPassable(pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        if (!level.getBlockState(pos).getFluidState().isSource()) {
            return false;
        }
        int openNeighbors = 0;
        for (Direction d : HORIZONTALS) {
            BlockPos n = pos.relative(d);
            if (grid.isBoatPassable(n.getX(), n.getY(), n.getZ())) {
                openNeighbors++;
            }
        }
        return openNeighbors >= 2;
    }

    private static boolean isNaturalSolidBank(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isNaturalSolidOrFillable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.isAir() || isNaturalSolidBank(level, pos.below()) || isNaturalSolidBank(level, pos.below(2));
    }

    private static int evaluateLandFootprint(ServerLevel level, BlockPos portPos, Direction back, int waterY) {
        Direction side = back.getClockWise();
        int score = 0;
        for (int b = 0; b <= 4; b++) {
            for (int s = -2; s <= 2; s++) {
                BlockPos ground = portPos.relative(back, b).relative(side, s).below();
                if (ground.getY() >= waterY && isNaturalSolidBank(level, ground)) {
                    score++;
                }
            }
        }
        return score;
    }

    /** Returns {totalMetallicOres, shallowestDepth} within a (2*radius+1)^2 column below {@code origin}. */
    private static int[] countMetallicOresInVolume(ServerLevel level, BlockPos origin, int radius, int maxDepth) {
        int count = 0;
        int minDepth = Integer.MAX_VALUE;
        int floorY = Math.max(com.logisticscraft.lc2.platform.Lc2Native.minBuildY(level) + 4, origin.getY() - maxDepth);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int depth = 1; origin.getY() - depth >= floorY; depth++) {
            int y = origin.getY() - depth;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (isSmeltableMetallicOre(state)) {
                        count++;
                        if (depth < minDepth) {
                            minDepth = depth;
                        }
                    }
                }
            }
        }
        return new int[]{count, minDepth == Integer.MAX_VALUE ? 999 : minDepth};
    }

    /** Returns {metallicOres, shallowestMetallicDepth, coalOres} inside an ic2:miner 7x7 shaft. */
    private static int[] countShaftOres(ServerLevel level, BlockPos origin, int radius, int maxDepth) {
        int metallic = 0;
        int coal = 0;
        int firstMetallicDepth = Integer.MAX_VALUE;
        int floorY = Math.max(com.logisticscraft.lc2.platform.Lc2Native.minBuildY(level) + 4, origin.getY() - maxDepth);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int depth = 1; origin.getY() - depth >= floorY; depth++) {
            int y = origin.getY() - depth;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (isSmeltableMetallicOre(state)) {
                        metallic++;
                        if (depth < firstMetallicDepth) {
                            firstMetallicDepth = depth;
                        }
                    } else if (isCoalOre(state)) {
                        coal++;
                    }
                }
            }
        }
        return new int[]{metallic, firstMetallicDepth == Integer.MAX_VALUE ? 999 : firstMetallicDepth, coal};
    }

    private static boolean isShaftDryAndDrillable(ServerLevel level, BlockPos minerPos, int depth) {
        int minY = com.logisticscraft.lc2.platform.Lc2Native.minBuildY(level) + 4;
        for (int d = 1; d <= depth; d++) {
            BlockPos p = minerPos.below(d);
            if (p.getY() < minY) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() || s.getDestroySpeed(level, p) < 0 || level.getBlockEntity(p) != null) {
                return false;
            }
            boolean drillable = s.isAir()
                    || s.is(BlockTags.BASE_STONE_OVERWORLD)
                    || s.is(BlockTags.DIRT)
                    || s.is(Blocks.SAND)
                    || s.is(Blocks.GRAVEL)
                    || s.is(Blocks.CLAY)
                    || isSmeltableMetallicOre(s)
                    || isCoalOre(s);
            if (!drillable) {
                return false;
            }
        }
        return true;
    }

    private static int findNaturalGroundY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top; y >= level.getSeaLevel() - 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return -1;
            }
            if (isNaturalSolidBank(level, pos)) {
                return y;
            }
        }
        return -1;
    }

    private static int countAquaticVegetationAlongPath(ServerLevel level, List<Vec3d> waypoints, int waterY) {
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            int steps = Math.max(1, (int) Math.ceil(a.horizontalDistanceTo(b) / 2.0));
            for (int s = 0; s <= steps; s++) {
                Vec3d p = a.lerp(b, (double) s / steps);
                int cx = (int) Math.floor(p.x());
                int cz = (int) Math.floor(p.z());
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int y = waterY - 8; y <= waterY; y++) {
                            BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                            if (!seen.contains(pos) && isAquaticPlant(level.getBlockState(pos))) {
                                seen.add(pos);
                            }
                        }
                    }
                }
            }
        }
        return seen.size();
    }

    public static boolean isSmeltableMetallicOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return SMELTABLE_METALLIC_ORES.contains(id);
    }

    public static boolean isCoalOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return COAL_ORES.contains(id);
    }

    public static boolean isAnyOre(BlockState state) {
        if (isSmeltableMetallicOre(state) || isCoalOre(state)) {
            return true;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
    }

    public static boolean isAquaticPlant(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return AQUATIC_PLANTS.contains(id);
    }

    private static int manhattan2D(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
}
