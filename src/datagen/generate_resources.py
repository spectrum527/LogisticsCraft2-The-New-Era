#!/usr/bin/env python3
"""Version-aware resource, model, recipe, loot table, and tag generator for Transitera (trera).

Supports four targets with exact Minecraft/loader JSON schemas:
  - forge-1.19.2
  - neoforge-1.21.1
  - neoforge-26.1.2
  - neoforge-26.2

Usage:
  python3 src/datagen/generate_resources.py --target <target> --output <dir>
"""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import tempfile

TARGETS = ("forge-1.19.2", "neoforge-1.21.1", "neoforge-26.1.2", "neoforge-26.2")
MOD_ID = "trera"
MARKER_NAME = ".trera-generated-output"

BLOCK_ITEMS = ("smart_port", "cargo_station", "comm_base_station")
SPRITE_ITEMS = (
    "electric_cargo_boat",
    "transport_drone",
    "heavy_transport_drone",
    "rf_chip_1",
    "rf_chip_2",
    "rf_chip_3",
    "chunk_loading_upgrade",
    "operation_terminal",
)
ALL_ITEMS = BLOCK_ITEMS + SPRITE_ITEMS

# Canonical crafting recipes (v1.0-v1.1 + v1.2.0 Comm Base Station & Operation Terminal).
# Entries prefixed with "#common:" are resolved to "#forge:" on Forge 1.19.2 and "#c:" on NeoForge 1.21.1/26.x.
SHAPED_RECIPES: dict[str, tuple[list[str], dict[str, str], int]] = {
    "smart_port": (
        ["CMC", "IHI", "CRC"],
        {
            "C": "#common:circuits/basic",
            "M": "mhera:machine",
            "I": "#common:plates/iron",
            "H": "minecraft:hopper",
            "R": "mhera:re_battery",
        },
        1,
    ),
    "electric_cargo_boat": (
        ["XCX", "PBP", "PEP"],
        {
            "X": "#common:chests/wooden",
            "C": "#common:circuits/basic",
            "P": "#common:plates/iron",
            "B": "mhera:electric_boat",
            "E": "mhera:electric_motor",
        },
        1,
    ),
    "cargo_station": (
        ["AFA", "PMP", "HCH"],
        {
            "A": "#common:circuits/advanced",
            "F": "mhera:frequency_transmitter",
            "P": "#common:plates/steel",
            "M": "mhera:advanced_machine",
            "H": "minecraft:hopper",
            "C": "mhera:energy_crystal",
        },
        1,
    ),
    "transport_drone": (
        ["BCB", "EXE", "BPB"],
        {
            "B": "mhera:iron_rotor_blade",
            "C": "#common:circuits/basic",
            "E": "mhera:electric_motor",
            "X": "#common:chests/wooden",
            "P": "mhera:carbon_plate",
        },
        1,
    ),
    "heavy_transport_drone": (
        ["BAB", "XDX", "LEL"],
        {
            "B": "mhera:carbon_rotor_blade",
            "A": "#common:circuits/advanced",
            "X": "#common:chests/wooden",
            "D": "trera:transport_drone",
            "L": "mhera:alloy",
            "E": "mhera:electric_motor",
        },
        1,
    ),
    "rf_chip_1": (
        ["WFW", "CRC", "WGW"],
        {
            "W": "mhera:insulated_copper_cable",
            "F": "mhera:frequency_transmitter",
            "C": "#common:circuits/basic",
            "R": "#common:dusts/redstone",
            "G": "#common:plates/copper",
        },
        1,
    ),
    "rf_chip_2": (
        ["WAW", "GIG", "WEW"],
        {
            "W": "mhera:insulated_gold_cable",
            "A": "#common:circuits/advanced",
            "G": "#common:plates/gold",
            "I": "trera:rf_chip_1",
            "E": "#common:ender_pearls",
        },
        1,
    ),
    "rf_chip_3": (
        ["WAW", "CIC", "WLW"],
        {
            "W": "mhera:glass_fibre_cable",
            "A": "#common:circuits/advanced",
            "C": "mhera:carbon_plate",
            "I": "trera:rf_chip_2",
            "L": "mhera:lapotron_crystal",
        },
        1,
    ),
    "chunk_loading_upgrade": (
        ["ALA", "CKC", "AEA"],
        {
            "A": "mhera:alloy",
            "L": "mhera:insulated_gold_cable",
            "C": "#common:circuits/advanced",
            "K": "mhera:chunk_loader",
            "E": "minecraft:ender_eye",
        },
        1,
    ),
    "comm_base_station": (
        ["CFC", "IMI", "WBW"],
        {
            "C": "#common:circuits/basic",
            "F": "mhera:frequency_transmitter",
            "I": "#common:plates/iron",
            "M": "mhera:machine",
            "W": "mhera:insulated_copper_cable",
            "B": "mhera:re_battery",
        },
        1,
    ),
    "operation_terminal": (
        ["IGI", "CFC", "WBW"],
        {
            "I": "#common:plates/iron",
            "G": "#common:glass_panes",
            "C": "#common:circuits/basic",
            "F": "mhera:frequency_transmitter",
            "W": "mhera:insulated_copper_cable",
            "B": "mhera:re_battery",
        },
        1,
    ),
}


@contextmanager
def lock_file(path: Path):
    with path.open("a+b") as handle:
        if os.name == "nt":
            import msvcrt
            if not path.stat().st_size:
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
            try:
                yield
            finally:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)


def publish(staged: Path, destination: Path):
    """Atomically commit generated resource files and prune stale files from prior runs."""
    names = {
        path.relative_to(staged).as_posix()
        for path in staged.rglob("*")
        if path.is_file() and path.suffix in (".json", ".mcmeta")
    }
    marker = destination / MARKER_NAME
    with lock_file(destination.parent / ("." + destination.name + ".lock")):
        if destination.exists() and not destination.is_dir():
            raise SystemExit("Resource output must be a directory")
        destination.mkdir(parents=True, exist_ok=True)
        if marker.is_file():
            previous = set(marker.read_text(encoding="utf-8").splitlines()[1:])
        elif any(destination.iterdir()):
            if not (destination.name == "trera-resources" and destination.parent.name == "generated"):
                raise SystemExit(f"Choose an empty directory for the generated pack: {destination}")
            previous = set()
        else:
            previous = set()
        for name in sorted(names):
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staged / name, target)
        for name in previous - names:
            rel = Path(name)
            if not rel.is_absolute() and ".." not in rel.parts and rel.suffix in (".json", ".mcmeta"):
                (destination / rel).unlink(missing_ok=True)
        ready = staged / MARKER_NAME
        ready.write_text("TRERA generated resources\n" + "\n".join(sorted(names)) + "\n", encoding="utf-8")
        os.replace(ready, marker)


def generate(target: str, destination: Path) -> int:
    forge = target == "forge-1.19.2"
    newest = target in ("neoforge-26.1.2", "neoforge-26.2")

    destination = destination.resolve()
    if destination == Path("/") or len(destination.parts) < 3:
        raise SystemExit("Choose a dedicated resource output directory")
    destination.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix=".trera-pack-", dir=destination.parent) as tmp_dir:
        out = Path(tmp_dir)

        def write_json(rel_path: str, value: dict):
            file_path = out / rel_path
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        def asset(path: str, value: dict):
            write_json(f"assets/{MOD_ID}/{path}.json", value)

        def data(path: str, value: dict):
            write_json(f"data/{MOD_ID}/{path}.json", value)

        def mod(name: str) -> str:
            return f"{MOD_ID}:{name}"

        def item_definition(name: str):
            if newest:
                asset(
                    f"items/{name}",
                    {"model": {"type": "minecraft:model", "model": mod(f"item/{name}")}},
                )

        # ---------------------------------------------------------------------
        # 1. Blockstates & Block Models
        # ---------------------------------------------------------------------

        # Smart Port: 4 horizontal facings x active=false/true
        rotations = {"north": 0, "east": 90, "south": 180, "west": 270}
        sp_variants = {}
        for active in (False, True):
            model_name = "smart_port_active" if active else "smart_port"
            front_tex = "block/smart_port_front_active" if active else "block/smart_port_front"
            asset(
                f"models/block/{model_name}",
                {
                    "parent": "minecraft:block/cube",
                    "textures": {
                        "north": mod(front_tex),
                        "south": mod("block/smart_port_side"),
                        "east": mod("block/smart_port_side"),
                        "west": mod("block/smart_port_side"),
                        "up": mod("block/smart_port_top"),
                        "down": mod("block/smart_port_bottom"),
                        "particle": mod("block/smart_port_side"),
                    },
                },
            )
            for facing, deg in rotations.items():
                entry = {"model": mod(f"block/{model_name}")}
                if deg:
                    entry["y"] = deg
                sp_variants[f"active={str(active).lower()},facing={facing}"] = entry
        asset("blockstates/smart_port", {"variants": sp_variants})

        # Cargo Station: multipart on active=false/true (works with or without facing property)
        for active in (False, True):
            suffix = "_active" if active else ""
            asset(
                f"models/block/cargo_station{suffix}",
                {
                    "parent": "minecraft:block/cube_bottom_top",
                    "textures": {
                        "top": mod(f"block/cargo_station_top{suffix}"),
                        "side": mod(f"block/cargo_station_side{suffix}"),
                        "bottom": mod("block/cargo_station_bottom"),
                    },
                },
            )
        asset(
            "blockstates/cargo_station",
            {
                "multipart": [
                    {"when": {"active": "false"}, "apply": {"model": mod("block/cargo_station")}},
                    {"when": {"active": "true"}, "apply": {"model": mod("block/cargo_station_active")}},
                ]
            },
        )

        # Communication Base Station: custom 3D multi-element model strictly within [0,0,0]..[16,16,16]
        for active in (False, True):
            suffix = "_active" if active else ""
            beacon_element = {
                "from": [6.5, 14.5, 6.5],
                "to": [9.5, 16, 9.5],
                "faces": {
                    "up": {"uv": [6, 1, 10, 5], "texture": "#antenna"},
                    "down": {"uv": [6, 4, 10, 5], "texture": "#antenna"},
                    "north": {"uv": [6, 2, 10, 4], "texture": "#antenna"},
                    "south": {"uv": [6, 2, 10, 4], "texture": "#antenna"},
                    "east": {"uv": [6, 2, 10, 4], "texture": "#antenna"},
                    "west": {"uv": [6, 2, 10, 4], "texture": "#antenna"},
                },
            }
            if active:
                beacon_element["shade"] = False
            asset(
                f"models/block/comm_base_station{suffix}",
                {
                    "parent": "minecraft:block/block",
                    "textures": {
                        "base": mod("block/comm_base_station_base"),
                        "top": mod("block/comm_base_station_top"),
                        "front": mod(f"block/comm_base_station_front{suffix}"),
                        "antenna": mod(f"block/comm_base_station_antenna{suffix}"),
                        "particle": mod("block/comm_base_station_base"),
                    },
                    "elements": [
                        {
                            "from": [0, 0, 0],
                            "to": [16, 9, 16],
                            "faces": {
                                "down": {"uv": [0, 0, 16, 16], "texture": "#base", "cullface": "down"},
                                "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
                                "north": {"uv": [0, 7, 16, 16], "texture": "#front", "cullface": "north"},
                                "south": {"uv": [0, 7, 16, 16], "texture": "#front", "cullface": "south"},
                                "east": {"uv": [0, 7, 16, 16], "texture": "#front", "cullface": "east"},
                                "west": {"uv": [0, 7, 16, 16], "texture": "#front", "cullface": "west"},
                            },
                        },
                        {
                            "from": [4, 9, 4],
                            "to": [12, 11, 12],
                            "faces": {
                                "up": {"uv": [4, 4, 12, 12], "texture": "#top"},
                                "north": {"uv": [4, 5, 12, 7], "texture": "#front"},
                                "south": {"uv": [4, 5, 12, 7], "texture": "#front"},
                                "east": {"uv": [4, 5, 12, 7], "texture": "#front"},
                                "west": {"uv": [4, 5, 12, 7], "texture": "#front"},
                            },
                        },
                        {
                            "from": [7, 11, 7],
                            "to": [9, 15, 9],
                            "faces": {
                                "north": {"uv": [7, 11, 9, 15], "texture": "#antenna"},
                                "south": {"uv": [7, 11, 9, 15], "texture": "#antenna"},
                                "east": {"uv": [7, 11, 9, 15], "texture": "#antenna"},
                                "west": {"uv": [7, 11, 9, 15], "texture": "#antenna"},
                            },
                        },
                        {
                            "from": [4, 12.5, 7.5],
                            "to": [12, 13.5, 8.5],
                            "faces": {
                                "north": {"uv": [4, 7, 12, 8], "texture": "#antenna"},
                                "south": {"uv": [4, 7, 12, 8], "texture": "#antenna"},
                                "up": {"uv": [4, 8, 12, 9], "texture": "#antenna"},
                                "down": {"uv": [4, 8, 12, 9], "texture": "#antenna"},
                                "east": {"uv": [2, 7, 3, 8], "texture": "#antenna"},
                                "west": {"uv": [2, 7, 3, 8], "texture": "#antenna"},
                            },
                        },
                        {
                            "from": [7.5, 12.5, 4],
                            "to": [8.5, 13.5, 12],
                            "faces": {
                                "east": {"uv": [4, 7, 12, 8], "texture": "#antenna"},
                                "west": {"uv": [4, 7, 12, 8], "texture": "#antenna"},
                                "up": {"uv": [4, 8, 12, 9], "texture": "#antenna"},
                                "down": {"uv": [4, 8, 12, 9], "texture": "#antenna"},
                                "north": {"uv": [2, 7, 3, 8], "texture": "#antenna"},
                                "south": {"uv": [2, 7, 3, 8], "texture": "#antenna"},
                            },
                        },
                        beacon_element,
                    ],
                },
            )
        asset(
            "blockstates/comm_base_station",
            {
                "multipart": [
                    {"when": {"active": "false"}, "apply": {"model": mod("block/comm_base_station")}},
                    {"when": {"active": "true"}, "apply": {"model": mod("block/comm_base_station_active")}},
                ]
            },
        )

        # ---------------------------------------------------------------------
        # 2. Item Models & 26.x Client Item Definitions
        # ---------------------------------------------------------------------
        for block_name in BLOCK_ITEMS:
            asset(f"models/item/{block_name}", {"parent": mod(f"block/{block_name}")})
            item_definition(block_name)

        for item_name in SPRITE_ITEMS:
            asset(
                f"models/item/{item_name}",
                {
                    "parent": "minecraft:item/generated",
                    "textures": {"layer0": mod(f"item/{item_name}")},
                },
            )
            item_definition(item_name)

        # ---------------------------------------------------------------------
        # 3. Crafting Recipes (Version-Specific Schema & Common Tags)
        # ---------------------------------------------------------------------
        recipe_dir = "recipes/" if forge else "recipe/"
        common_ns = "forge" if forge else "c"

        def resolve_ingredient(item_id: str) -> str:
            if item_id.startswith("#common:"):
                return f"#{common_ns}:{item_id[len('#common:'):]}"
            return item_id

        def format_ingredient(item_id: str):
            resolved = resolve_ingredient(item_id)
            if newest:
                # MC 1.21.2+ / 26.x: bare string item/tag identifier
                return resolved
            if resolved.startswith("#"):
                return {"tag": resolved[1:]}
            return {"item": resolved}

        def format_result(item_name: str, count: int) -> dict:
            return {("item" if forge else "id"): mod(item_name), "count": count}

        for recipe_name, (pattern, keys, count) in SHAPED_RECIPES.items():
            data(
                recipe_dir + recipe_name,
                {
                    "type": "minecraft:crafting_shaped",
                    "pattern": pattern,
                    "key": {k: format_ingredient(v) for k, v in keys.items()},
                    "result": format_result(recipe_name, count),
                },
            )

        # ---------------------------------------------------------------------
        # 4. Block Loot Tables, Common Tags, Curios Tags & Mod Grouping Tags
        # ---------------------------------------------------------------------
        loot_dir = "loot_tables/blocks/" if forge else "loot_table/blocks/"
        block_tag_dir = "tags/blocks/" if forge else "tags/block/"
        item_tag_dir = "tags/items/" if forge else "tags/item/"

        for block_name in BLOCK_ITEMS:
            data(
                loot_dir + block_name,
                {
                    "type": "minecraft:block",
                    "pools": [
                        {
                            "rolls": 1,
                            "conditions": [{"condition": "minecraft:survives_explosion"}],
                            "entries": [{"type": "minecraft:item", "name": mod(block_name)}],
                        }
                    ],
                },
            )

        write_json(
            f"data/minecraft/{block_tag_dir}mineable/pickaxe.json",
            {"replace": False, "values": [mod(b) for b in BLOCK_ITEMS]},
        )

        # Common relocation protection block tag (forge: on 1.19.2, c: on 1.21.1/26.x)
        write_json(
            f"data/{common_ns}/{block_tag_dir}relocation_not_supported.json",
            {"replace": False, "values": [mod(b) for b in BLOCK_ITEMS]},
        )

        # Curios API item tags (optional integration; version-specific tags/items vs tags/item)
        for curio_slot in ("charm", "belt"):
            write_json(
                f"data/curios/{item_tag_dir}{curio_slot}.json",
                {"replace": False, "values": [mod("operation_terminal")]},
            )

        # Mod-internal grouping tags (block + symmetric item tags where applicable)
        station_ids = [mod("smart_port"), mod("cargo_station")]
        power_block_ids = [mod(b) for b in BLOCK_ITEMS]
        write_json(
            f"data/{MOD_ID}/{block_tag_dir}stations.json",
            {"replace": False, "values": station_ids},
        )
        write_json(
            f"data/{MOD_ID}/{block_tag_dir}power_blocks.json",
            {"replace": False, "values": power_block_ids},
        )
        write_json(
            f"data/{MOD_ID}/{item_tag_dir}stations.json",
            {"replace": False, "values": station_ids},
        )
        write_json(
            f"data/{MOD_ID}/{item_tag_dir}power_blocks.json",
            {"replace": False, "values": power_block_ids},
        )
        write_json(
            f"data/{MOD_ID}/{item_tag_dir}rf_chips.json",
            {"replace": False, "values": [mod("rf_chip_1"), mod("rf_chip_2"), mod("rf_chip_3")]},
        )
        write_json(
            f"data/{MOD_ID}/{item_tag_dir}drones.json",
            {"replace": False, "values": [mod("transport_drone"), mod("heavy_transport_drone")]},
        )
        write_json(
            f"data/{MOD_ID}/{item_tag_dir}single_use_batteries.json",
            {"replace": False, "values": ["mhera:single_use_battery"]},
        )

        # ---------------------------------------------------------------------
        # 5. Forge 1.19.2 pack.mcmeta
        # ---------------------------------------------------------------------
        if forge:
            write_json(
                "pack.mcmeta",
                {
                    "pack": {
                        "pack_format": 9,
                        "forge:resource_pack_format": 9,
                        "forge:data_pack_format": 10,
                        "description": "Transitera resources for Minecraft 1.19.2",
                    }
                },
            )

        count = sum(1 for p in out.rglob("*") if p.is_file() and p.suffix in (".json", ".mcmeta"))
        publish(out, destination)
        print(f"{target}: generated {count} files in {destination}")
        return count


def main():
    parser = argparse.ArgumentParser(description="Generate Transitera (trera) version-specific resources and data.")
    parser.add_argument("--target", required=True, choices=TARGETS)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    generate(args.target, args.output)


if __name__ == "__main__":
    main()
