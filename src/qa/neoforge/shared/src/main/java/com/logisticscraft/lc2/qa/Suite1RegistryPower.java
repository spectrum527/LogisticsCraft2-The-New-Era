package com.logisticscraft.lc2.qa;

import com.industrialage.ia2.gameplay.IndustrialMachine;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Suite 1: Mod JAR verification, Registries (2 blocks, 2 BEs, 2 entities, 9 items, 3 menus),
 * all 9 crafting recipes (RecipeManager + live CraftingMenu assembly), IC2 Generator + CableBlockMixin
 * visual connection (both placement orders!), real IC2 EnergyNetwork EU flow, and Energy Storage /
 * Transformer upgrades.
 */
public final class Suite1RegistryPower {

    private record RecipeSpec(String id, String[] pattern, Map<Character, String> key) {
    }

    private static final List<RecipeSpec> EXPECTED_RECIPES = List.of(
            new RecipeSpec("lc2:smart_port", new String[]{"CMC", "IHI", "CRC"}, Map.of(
                    'C', "ic2:circuit", 'M', "ic2:machine", 'I', "ic2:iron_plate",
                    'H', "minecraft:hopper", 'R', "ic2:re_battery")),
            new RecipeSpec("lc2:cargo_station", new String[]{"AFA", "PMP", "HCH"}, Map.of(
                    'A', "ic2:advanced_circuit", 'F', "ic2:frequency_transmitter", 'P', "ic2:steel_plate",
                    'M', "ic2:advanced_machine", 'H', "minecraft:hopper", 'C', "ic2:energy_crystal")),
            new RecipeSpec("lc2:electric_cargo_boat", new String[]{"XCX", "PBP", "PEP"}, Map.of(
                    'X', "minecraft:chest", 'C', "ic2:circuit", 'P', "ic2:iron_plate",
                    'B', "ic2:electric_boat", 'E', "ic2:electric_motor")),
            new RecipeSpec("lc2:transport_drone", new String[]{"BCB", "EXE", "BPB"}, Map.of(
                    'B', "ic2:iron_rotor_blade", 'C', "ic2:circuit", 'E', "ic2:electric_motor",
                    'X', "minecraft:chest", 'P', "ic2:carbon_plate")),
            new RecipeSpec("lc2:heavy_transport_drone", new String[]{"BAB", "XDX", "LEL"}, Map.of(
                    'B', "ic2:carbon_rotor_blade", 'A', "ic2:advanced_circuit", 'X', "minecraft:chest",
                    'D', "lc2:transport_drone", 'L', "ic2:alloy", 'E', "ic2:electric_motor")),
            new RecipeSpec("lc2:rf_chip_1", new String[]{"WFW", "CRC", "WGW"}, Map.of(
                    'W', "ic2:insulated_copper_cable", 'F', "ic2:frequency_transmitter", 'C', "ic2:circuit",
                    'R', "minecraft:redstone", 'G', "ic2:copper_plate")),
            new RecipeSpec("lc2:rf_chip_2", new String[]{"WAW", "GIG", "WEW"}, Map.of(
                    'W', "ic2:insulated_gold_cable", 'A', "ic2:advanced_circuit", 'G', "ic2:gold_plate",
                    'I', "lc2:rf_chip_1", 'E', "minecraft:ender_pearl")),
            new RecipeSpec("lc2:rf_chip_3", new String[]{"WAW", "CIC", "WLW"}, Map.of(
                    'W', "ic2:glass_fibre_cable", 'A', "ic2:advanced_circuit", 'C', "ic2:carbon_plate",
                    'I', "lc2:rf_chip_2", 'L', "ic2:lapotron_crystal")),
            new RecipeSpec("lc2:chunk_loading_upgrade", new String[]{"ALA", "CKC", "AEA"}, Map.of(
                    'A', "ic2:alloy", 'L', "ic2:insulated_gold_cable", 'C', "ic2:advanced_circuit",
                    'K', "ic2:chunk_loader", 'E', "minecraft:ender_eye"))
    );

    private static final BlockPos GEN_PORT = new BlockPos(10, 64, 8);
    private static final BlockPos CABLE_PORT = new BlockPos(11, 64, 8);
    private static final BlockPos SMART_PORT = new BlockPos(12, 64, 8);

    private static final BlockPos GEN_STATION = new BlockPos(10, 64, 11);
    private static final BlockPos CABLE_STATION = new BlockPos(11, 64, 11);
    private static final BlockPos CARGO_STATION = new BlockPos(12, 64, 11);

    private int startTick = -1;
    private boolean done = false;

    public void setup(ServerLevel level, QaChecks checks) throws Exception {
        MinecraftServer server = level.getServer();

        // 1. Verify exact packaged production JARs for both ic2 and lc2
        Path lc2Jar = ModList.get().getModContainerById("lc2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        Path ic2Jar = ModList.get().getModContainerById("ic2").orElseThrow()
                .getModInfo().getOwningFile().getFile().getFilePath();
        String expectedLc2 = System.getProperty("lc2.qa.expectedLc2Jar", lc2Jar.getFileName().toString());
        String expectedIc2 = System.getProperty("lc2.qa.expectedIc2Jar", ic2Jar.getFileName().toString());
        checks.putMeta("lc2Artifact", lc2Jar.getFileName().toString());
        checks.putMeta("ic2Artifact", ic2Jar.getFileName().toString());
        checks.check("s1.jar.lc2_loaded_from_mods",
                Files.isSameFile(lc2Jar, Path.of("mods", expectedLc2)), lc2Jar.getFileName().toString());
        checks.check("s1.jar.ic2_loaded_from_mods",
                Files.isSameFile(ic2Jar, Path.of("mods", expectedIc2)), ic2Jar.getFileName().toString());

        // 2. Verify all registries: 2 blocks, 2 BEs, 2 entities, 9 items, 3 menus
        for (String id : List.of("lc2:smart_port", "lc2:cargo_station")) {
            checks.check("s1.reg.block." + id, QaChecks.hasBlock(id));
            checks.check("s1.reg.be." + id, QaChecks.hasBlockEntity(id));
        }
        for (String id : List.of("lc2:electric_cargo_boat", "lc2:cargo_drone")) {
            checks.check("s1.reg.entity." + id, QaChecks.hasEntity(id));
        }
        for (String id : List.of(
                "lc2:smart_port", "lc2:cargo_station", "lc2:electric_cargo_boat",
                "lc2:transport_drone", "lc2:heavy_transport_drone",
                "lc2:rf_chip_1", "lc2:rf_chip_2", "lc2:rf_chip_3", "lc2:chunk_loading_upgrade")) {
            checks.check("s1.reg.item." + id, QaChecks.hasItem(id));
        }
        for (String id : List.of("lc2:smart_port", "lc2:cargo_station", "lc2:electric_cargo_boat")) {
            checks.check("s1.reg.menu." + id, QaChecks.hasMenu(id));
        }

        // 3. Verify all 9 crafting recipes exist in live RecipeManager AND assemble via live CraftingMenu
        Set<String> liveRecipes = QaPlatform.allRecipeIds(server);
        FakePlayer crafter = FakePlayerFactory.getMinecraft(level);
        for (RecipeSpec spec : EXPECTED_RECIPES) {
            checks.check("s1.recipe.present." + spec.id(), liveRecipes.contains(spec.id()),
                    "present in RecipeManager");
            CraftingMenu menu = new CraftingMenu(101, crafter.getInventory(),
                    ContainerLevelAccess.create(level, new BlockPos(8, 65, 8)));
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    char ch = spec.pattern()[r].charAt(c);
                    if (ch != ' ') {
                        String ingId = spec.key().get(ch);
                        menu.getSlot(1 + r * 3 + c).set(new ItemStack(QaChecks.item(ingId)));
                    }
                }
            }
            menu.slotsChanged(menu.getSlot(1).container);
            ItemStack output = menu.getSlot(0).getItem();
            checks.check("s1.recipe.craft." + spec.id(),
                    !output.isEmpty() && output.is(QaChecks.item(spec.id())) && output.getCount() == 1,
                    "crafted=" + output);
        }

        // 4. IC2 Generator + insulated_copper_cable + CableBlockMixin + EU flow (both placement orders!)
        // Order A: place cable FIRST, then place Smart Port adjacent -> triggers updateShape -> updateConnection
        QaChecks.survivalPlace(level, crafter, "ic2:generator", GEN_PORT);
        QaChecks.survivalPlace(level, crafter, "ic2:insulated_copper_cable", CABLE_PORT);
        level.setBlockAndUpdate(SMART_PORT,
                QaChecks.block("lc2:smart_port").defaultBlockState().setValue(SmartPortBlock.FACING, Direction.EAST));
        BlockState cablePortState = level.getBlockState(CABLE_PORT);
        checks.check("s1.mixin.cable_connects_smart_port_via_update",
                cablePortState.hasProperty(BlockStateProperties.EAST)
                        && cablePortState.getValue(BlockStateProperties.EAST),
                "state=" + cablePortState);

        // Order B: place Cargo Station FIRST, then place cable adjacent via survival placement -> getStateForPlacement
        QaChecks.survivalPlace(level, crafter, "ic2:generator", GEN_STATION);
        level.setBlockAndUpdate(CARGO_STATION, QaChecks.block("lc2:cargo_station").defaultBlockState());
        QaChecks.survivalPlace(level, crafter, "ic2:insulated_copper_cable", CABLE_STATION);
        BlockState cableStationState = level.getBlockState(CABLE_STATION);
        checks.check("s1.mixin.cable_connects_cargo_station_via_placement",
                cableStationState.hasProperty(BlockStateProperties.EAST)
                        && cableStationState.getValue(BlockStateProperties.EAST),
                "state=" + cableStationState);

        // Prime both IC2 Generators with coal
        IndustrialMachine genPortBe = (IndustrialMachine) level.getBlockEntity(GEN_PORT);
        IndustrialMachine genStationBe = (IndustrialMachine) level.getBlockEntity(GEN_STATION);
        checks.check("s1.ic2.generators_present", genPortBe != null && genStationBe != null);
        genPortBe.setItem(0, new ItemStack(Items.COAL, 8));
        genStationBe.setItem(0, new ItemStack(Items.COAL, 8));

        // Verify base EU capacity (14400) & LV packetLimit (32) BEFORE upgrades
        SmartPortBlockEntity portBe = (SmartPortBlockEntity) level.getBlockEntity(SMART_PORT);
        CargoStationBlockEntity stationBe = (CargoStationBlockEntity) level.getBlockEntity(CARGO_STATION);
        checks.check("s1.power.base_ratings",
                portBe != null && stationBe != null
                        && portBe.getMaxEu() == 14_400.0 && portBe.packetLimit() == 32.0
                        && stationBe.getMaxEu() == 14_400.0 && stationBe.packetLimit() == 32.0,
                "portMax=" + (portBe != null ? portBe.getMaxEu() : -1)
                        + ", portLimit=" + (portBe != null ? portBe.packetLimit() : -1));
    }

    public boolean tick(ServerLevel level, int tick, QaChecks checks) {
        if (done) {
            return true;
        }
        if (startTick < 0) {
            startTick = tick;
        }
        if (tick - startTick < 35) {
            return false;
        }

        SmartPortBlockEntity portBe = (SmartPortBlockEntity) level.getBlockEntity(SMART_PORT);
        CargoStationBlockEntity stationBe = (CargoStationBlockEntity) level.getBlockEntity(CARGO_STATION);
        checks.check("s1.power.real_eu_flows_to_smart_port",
                portBe != null && portBe.getStoredEu() > 50.0,
                "smartPortEu=" + (portBe != null ? portBe.getStoredEu() : -1));
        checks.check("s1.power.real_eu_flows_to_cargo_station",
                stationBe != null && stationBe.getStoredEu() > 50.0,
                "cargoStationEu=" + (stationBe != null ? stationBe.getStoredEu() : -1));

        // Test Energy Storage Upgrade (+10,000 -> 24,400 EU) & Transformer Upgrade (32 -> 128 EU/t) on BOTH stations
        ItemStack storageUpg = new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_ENERGY_STORAGE), 1);
        ItemStack transUpg = new ItemStack(QaChecks.item(LogisticsConstants.UPGRADE_TRANSFORMER), 1);

        portBe.setItem(SmartPortBlockEntity.UPGRADE_START, storageUpg.copy());
        stationBe.setItem(CargoStationBlockEntity.UPGRADE_START, storageUpg.copy());
        checks.check("s1.upgrade.energy_storage_24400",
                portBe.getMaxEu() == 24_400.0 && stationBe.getMaxEu() == 24_400.0,
                "portMax=" + portBe.getMaxEu() + ", stationMax=" + stationBe.getMaxEu());

        portBe.setItem(SmartPortBlockEntity.UPGRADE_START + 1, transUpg.copy());
        stationBe.setItem(CargoStationBlockEntity.UPGRADE_START + 1, transUpg.copy());
        checks.check("s1.upgrade.transformer_128",
                portBe.packetLimit() == 128.0 && stationBe.packetLimit() == 128.0,
                "portLimit=" + portBe.packetLimit() + ", stationLimit=" + stationBe.packetLimit());

        // Cleanup Suite 1 blocks so they don't keep burning coal or cluttering StationRegistry
        for (BlockPos pos : List.of(GEN_PORT, CABLE_PORT, SMART_PORT, GEN_STATION, CABLE_STATION, CARGO_STATION)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        done = true;
        return true;
    }
}
