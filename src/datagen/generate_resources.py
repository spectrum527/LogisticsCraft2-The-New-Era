#!/usr/bin/env python3
"""Version-aware resource, model, recipe, loot table, and tag generator for LogisticsCraft² (lc2).

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
MOD_ID = "lc2"
MARKER_NAME = ".lc2-generated-output"

BLOCK_ITEMS = ("smart_port", "cargo_station")
SPRITE_ITEMS = (
    "electric_cargo_boat",
    "transport_drone",
    "heavy_transport_drone",
    "rf_chip_1",
    "rf_chip_2",
    "rf_chip_3",
    "chunk_loading_upgrade",
)
ALL_ITEMS = BLOCK_ITEMS + SPRITE_ITEMS

# Canonical 9 crafting recipes from docs/DESIGN.md Section 3
SHAPED_RECIPES: dict[str, tuple[list[str], dict[str, str], int]] = {
    "smart_port": (
        ["CMC", "IHI", "CRC"],
        {
            "C": "ic2:circuit",
            "M": "ic2:machine",
            "I": "ic2:iron_plate",
            "H": "minecraft:hopper",
            "R": "ic2:re_battery",
        },
        1,
    ),
    "electric_cargo_boat": (
        ["XCX", "PBP", "PEP"],
        {
            "X": "minecraft:chest",
            "C": "ic2:circuit",
            "P": "ic2:iron_plate",
            "B": "ic2:electric_boat",
            "E": "ic2:electric_motor",
        },
        1,
    ),
    "cargo_station": (
        ["AFA", "PMP", "HCH"],
        {
            "A": "ic2:advanced_circuit",
            "F": "ic2:frequency_transmitter",
            "P": "ic2:steel_plate",
            "M": "ic2:advanced_machine",
            "H": "minecraft:hopper",
            "C": "ic2:energy_crystal",
        },
        1,
    ),
    "transport_drone": (
        ["BCB", "EXE", "BPB"],
        {
            "B": "ic2:iron_rotor_blade",
            "C": "ic2:circuit",
            "E": "ic2:electric_motor",
            "X": "minecraft:chest",
            "P": "ic2:carbon_plate",
        },
        1,
    ),
    "heavy_transport_drone": (
        ["BAB", "XDX", "LEL"],
        {
            "B": "ic2:carbon_rotor_blade",
            "A": "ic2:advanced_circuit",
            "X": "minecraft:chest",
            "D": "lc2:transport_drone",
            "L": "ic2:alloy",
            "E": "ic2:electric_motor",
        },
        1,
    ),
    "rf_chip_1": (
        ["WFW", "CRC", "WGW"],
        {
            "W": "ic2:insulated_copper_cable",
            "F": "ic2:frequency_transmitter",
            "C": "ic2:circuit",
            "R": "minecraft:redstone",
            "G": "ic2:copper_plate",
        },
        1,
    ),
    "rf_chip_2": (
        ["WAW", "GIG", "WEW"],
        {
            "W": "ic2:insulated_gold_cable",
            "A": "ic2:advanced_circuit",
            "G": "ic2:gold_plate",
            "I": "lc2:rf_chip_1",
            "E": "minecraft:ender_pearl",
        },
        1,
    ),
    "rf_chip_3": (
        ["WAW", "CIC", "WLW"],
        {
            "W": "ic2:glass_fibre_cable",
            "A": "ic2:advanced_circuit",
            "C": "ic2:carbon_plate",
            "I": "lc2:rf_chip_2",
            "L": "ic2:lapotron_crystal",
        },
        1,
    ),
    "chunk_loading_upgrade": (
        ["ALA", "CKC", "AEA"],
        {
            "A": "ic2:alloy",
            "L": "ic2:insulated_gold_cable",
            "C": "ic2:advanced_circuit",
            "K": "ic2:chunk_loader",
            "E": "minecraft:ender_eye",
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
            if not (destination.name == "lc2-resources" and destination.parent.name == "generated"):
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
        ready.write_text("LC2 generated resources\n" + "\n".join(sorted(names)) + "\n", encoding="utf-8")
        os.replace(ready, marker)


def generate(target: str, destination: Path) -> int:
    forge = target == "forge-1.19.2"
    newest = target in ("neoforge-26.1.2", "neoforge-26.2")

    destination = destination.resolve()
    if destination == Path("/") or len(destination.parts) < 3:
        raise SystemExit("Choose a dedicated resource output directory")
    destination.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix=".lc2-pack-", dir=destination.parent) as tmp_dir:
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
        # 3. Crafting Recipes (Version-Specific Schema)
        # ---------------------------------------------------------------------
        recipe_dir = "recipes/" if forge else "recipe/"

        def format_ingredient(item_id: str):
            if newest:
                # MC 1.21.2+ / 26.x: bare string item/tag identifier
                return item_id
            if item_id.startswith("#"):
                return {"tag": item_id[1:]}
            return {"item": item_id}

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
        # 4. Block Loot Tables & Pickaxe Mineable Tag
        # ---------------------------------------------------------------------
        loot_dir = "loot_tables/blocks/" if forge else "loot_table/blocks/"
        tag_dir = "tags/blocks/" if forge else "tags/block/"

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
            f"data/minecraft/{tag_dir}mineable/pickaxe.json",
            {"replace": False, "values": [mod(b) for b in BLOCK_ITEMS]},
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
                        "description": "LogisticsCraft² resources for Minecraft 1.19.2",
                    }
                },
            )

        count = sum(1 for p in out.rglob("*") if p.is_file() and p.suffix in (".json", ".mcmeta"))
        publish(out, destination)
        print(f"{target}: generated {count} files in {destination}")
        return count


def main():
    parser = argparse.ArgumentParser(description="Generate LC2 version-specific resources and data.")
    parser.add_argument("--target", required=True, choices=TARGETS)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    generate(args.target, args.output)


if __name__ == "__main__":
    main()
