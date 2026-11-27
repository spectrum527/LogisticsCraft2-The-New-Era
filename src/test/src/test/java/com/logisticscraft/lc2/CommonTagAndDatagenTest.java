package com.logisticscraft.lc2;

import com.logisticscraft.lc2.core.LogisticsConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies LC2's version-aware resource generator across all 4 targets (Forge 1.19.2,
 * NeoForge 1.21.1, NeoForge 26.1.2, NeoForge 26.2) for common/mod tag paths, namespace
 * isolation, relocation protection, and crafting recipe tag consumption.
 */
class CommonTagAndDatagenTest {

    private static final List<String> TARGETS = List.of(
            "forge-1.19.2",
            "neoforge-1.21.1",
            "neoforge-26.1.2",
            "neoforge-26.2"
    );

    private static final List<String> LEGACY_HARDCODED_RECIPE_IDS = List.of(
            "\"minecraft:chest\"",
            "\"minecraft:redstone\"",
            "\"minecraft:ender_pearl\"",
            "\"minecraft:glass_pane\"",
            "\"ic2:iron_plate\"",
            "\"ic2:steel_plate\"",
            "\"ic2:copper_plate\"",
            "\"ic2:gold_plate\"",
            "\"ic2:circuit\"",
            "\"ic2:advanced_circuit\""
    );

    private static final Map<String, List<String>> EXPECTED_RECIPE_COMMON_TAGS = Map.ofEntries(
            Map.entry("smart_port", List.of("circuits/basic", "plates/iron")),
            Map.entry("electric_cargo_boat", List.of("chests/wooden", "circuits/basic", "plates/iron")),
            Map.entry("cargo_station", List.of("circuits/advanced", "plates/steel")),
            Map.entry("transport_drone", List.of("circuits/basic", "chests/wooden")),
            Map.entry("heavy_transport_drone", List.of("circuits/advanced", "chests/wooden")),
            Map.entry("rf_chip_1", List.of("circuits/basic", "dusts/redstone", "plates/copper")),
            Map.entry("rf_chip_2", List.of("circuits/advanced", "plates/gold", "ender_pearls")),
            Map.entry("rf_chip_3", List.of("circuits/advanced")),
            Map.entry("chunk_loading_upgrade", List.of("circuits/advanced")),
            Map.entry("comm_base_station", List.of("circuits/basic", "plates/iron")),
            Map.entry("operation_terminal", List.of("plates/iron", "glass_panes", "circuits/basic"))
    );

    @Test
    void singleUseBatteriesTagConstantIsCanonical() {
        assertEquals("lc2:single_use_batteries", LogisticsConstants.SINGLE_USE_BATTERIES_TAG);
        assertEquals("ic2:single_use_battery", LogisticsConstants.SINGLE_USE_BATTERY_ID);
    }

    @Test
    void datagenProducesValidCommonTagsAndRecipesAcrossAllFourTargets(@TempDir Path tempDir) throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path generator = repoRoot.resolve("src/datagen/generate_resources.py");
        assertTrue(Files.isRegularFile(generator), "Missing generator script: " + generator);

        for (String target : TARGETS) {
            Path outDir = tempDir.resolve(target);
            Process process = new ProcessBuilder(
                    "python3",
                    generator.toString(),
                    "--target", target,
                    "--output", outDir.toString()
            ).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, "Generator failed for " + target + ":\n" + output);

            boolean forge = target.equals("forge-1.19.2");
            boolean newest = target.equals("neoforge-26.1.2") || target.equals("neoforge-26.2");
            String ns = forge ? "forge" : "c";
            String otherNs = forge ? "c" : "forge";
            String blockTagDir = forge ? "tags/blocks" : "tags/block";
            String itemTagDir = forge ? "tags/items" : "tags/item";
            String wrongBlockTagDir = forge ? "tags/block" : "tags/blocks";
            String wrongItemTagDir = forge ? "tags/item" : "tags/items";
            String recipeDir = forge ? "recipes" : "recipe";

            // 1. Directory singular/plural discipline & namespace isolation
            assertFalse(Files.exists(outDir.resolve("data/" + otherNs)),
                    target + " must not emit data/" + otherNs);
            assertFalse(Files.exists(outDir.resolve("data/minecraft/" + wrongBlockTagDir)),
                    target + " emitted wrong minecraft block tag dir");
            assertFalse(Files.exists(outDir.resolve("data/curios/" + wrongItemTagDir)),
                    target + " emitted wrong curios item tag dir");
            assertFalse(Files.exists(outDir.resolve("data/lc2/" + wrongBlockTagDir)),
                    target + " emitted wrong lc2 block tag dir");
            assertFalse(Files.exists(outDir.resolve("data/lc2/" + wrongItemTagDir)),
                    target + " emitted wrong lc2 item tag dir");

            // 2. Common relocation_not_supported block tag
            String relocation = readUtf8(outDir.resolve("data/" + ns + "/" + blockTagDir + "/relocation_not_supported.json"));
            assertTrue(relocation.contains("\"lc2:smart_port\""), target + " missing smart_port in relocation tag");
            assertTrue(relocation.contains("\"lc2:cargo_station\""), target + " missing cargo_station in relocation tag");
            assertTrue(relocation.contains("\"lc2:comm_base_station\""), target + " missing comm_base_station in relocation tag");

            // 3. Mod-internal grouping tags + optional single-use battery tag
            assertTagValues(outDir.resolve("data/lc2/" + blockTagDir + "/stations.json"),
                    "lc2:smart_port", "lc2:cargo_station");
            assertTagValues(outDir.resolve("data/lc2/" + itemTagDir + "/stations.json"),
                    "lc2:smart_port", "lc2:cargo_station");
            assertTagValues(outDir.resolve("data/lc2/" + blockTagDir + "/power_blocks.json"),
                    "lc2:smart_port", "lc2:cargo_station", "lc2:comm_base_station");
            assertTagValues(outDir.resolve("data/lc2/" + itemTagDir + "/power_blocks.json"),
                    "lc2:smart_port", "lc2:cargo_station", "lc2:comm_base_station");
            assertTagValues(outDir.resolve("data/lc2/" + itemTagDir + "/rf_chips.json"),
                    "lc2:rf_chip_1", "lc2:rf_chip_2", "lc2:rf_chip_3");
            assertTagValues(outDir.resolve("data/lc2/" + itemTagDir + "/drones.json"),
                    "lc2:transport_drone", "lc2:heavy_transport_drone");
            assertTagValues(outDir.resolve("data/lc2/" + itemTagDir + "/single_use_batteries.json"),
                    "ic2:single_use_battery");
            assertTagValues(outDir.resolve("data/curios/" + itemTagDir + "/charm.json"),
                    "lc2:operation_terminal");
            assertTagValues(outDir.resolve("data/curios/" + itemTagDir + "/belt.json"),
                    "lc2:operation_terminal");

            // 4. All 11 recipes: common tags present, exact version schema, no legacy hardcoded IDs or wrong namespace
            int totalTagMatches = 0;
            Pattern tagSyntax = newest
                    ? Pattern.compile("\"#([a-z0-9_]+:[a-z0-9_/]+)\"")
                    : Pattern.compile("\"tag\"\\s*:\\s*\"([a-z0-9_]+:[a-z0-9_/]+)\"");
            for (Map.Entry<String, List<String>> entry : EXPECTED_RECIPE_COMMON_TAGS.entrySet()) {
                Path recipeFile = outDir.resolve("data/lc2/" + recipeDir + "/" + entry.getKey() + ".json");
                String content = readUtf8(recipeFile);
                assertFalse(content.contains(otherNs + ":"),
                        target + " recipe " + entry.getKey() + " leaked wrong namespace " + otherNs + ":");
                assertFalse(content.contains("#common:"),
                        target + " recipe " + entry.getKey() + " left unresolved #common: placeholder");
                for (String legacy : LEGACY_HARDCODED_RECIPE_IDS) {
                    assertFalse(content.contains(legacy),
                            target + " recipe " + entry.getKey() + " still hardcodes " + legacy);
                }
                for (String expectedPath : entry.getValue()) {
                    String expectedToken = newest
                            ? "\"#" + ns + ":" + expectedPath + "\""
                            : "\"tag\": \"" + ns + ":" + expectedPath + "\"";
                    assertTrue(content.contains(expectedToken),
                            target + " recipe " + entry.getKey() + " missing expected tag " + expectedToken);
                }
                Matcher matcher = tagSyntax.matcher(content);
                while (matcher.find()) {
                    assertTrue(matcher.group(1).startsWith(ns + ":"),
                            target + " unexpected tag namespace in " + entry.getKey() + ": " + matcher.group(1));
                    totalTagMatches++;
                }
            }
            assertEquals(24, totalTagMatches, target + " expected exact 24 common tag bindings across 11 recipes");
        }
    }

    private static void assertTagValues(Path file, String... expectedValues) throws IOException {
        String content = readUtf8(file);
        for (String expected : expectedValues) {
            assertTrue(content.contains("\"" + expected + "\""),
                    "Missing entry " + expected + " in " + file);
        }
    }

    private static String readUtf8(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "Missing generated file: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("src/datagen/generate_resources.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate LC2 repository root from " + Path.of("").toAbsolutePath());
    }
}
