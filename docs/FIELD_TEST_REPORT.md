# Transitera (`trera`) — Natural-World Client+Server & MHERA Production-Line Field Test Report (`v1.0.1`)

## 1. Verified Natural World Seed & Terrain

- **Level Type**: `minecraft:normal`
- **Seed**: `20261121`
- **Biome & Sea Level**: `minecraft:sparse_jungle` coastline/bay (`waterY = 62`)
- **Natural Waterway**:
  - **NeoForge (1.21.1 / 26.1.2 / 26.2)**: Curved 84m natural river/bay connecting Shore A berth `(4, 62, 120)` → Shore B berth `(-32, 62, 50)` (5–6 A* waypoints traversing 118–204 natural kelp/seagrass blocks), +12-block hillside overlooking the bay for aerial Cargo Station testing, and natural underground ore veins beneath Shore A.
  - **Forge 1.19.2**: Curved 139.5m natural bay connecting Shore A berth `(-41, 62, 24)` → Shore B berth `(88, 62, -29)` (7 A* waypoints traversing 50 natural kelp/seagrass blocks), with 1,626+ natural ores beneath Shore A (`mhera:advanced_miner`) and 239+ natural ores beneath the classic `mhera:miner`.

---

## 2. End-to-End Automated MHERA Mining → Logistics → Refining Production Line

Both NeoForge (`1.21.1`, `26.1.2`, `26.2`) and Forge `1.19.2` were tested end-to-end loading the exact packaged `ic2-*-1.0.5.jar` and `lc2-*-1.0.1.jar`:

1. **Outpost A (Natural Shore A + Hillside)**:
   - Real MHERA coal-fueled `mhera:generator` + `mhera:batbox` + `mhera:insulated_copper_cable` (visually connected via `CableBlockMixin` and delivering real EU).
   - Real **`mhera:advanced_miner`** (equipped with `mhera:advanced_scanner`) and **`mhera:miner`** (equipped with `mhera:diamond_drill`, `mhera:advanced_scanner`, `mhera:mining_pipe`) actively digging real natural underground ores from seed `20261121`.
   - Mined ores auto-ejected into Chest/Storage Box, driving vanilla **Redstone Comparators** (using our new `hasAnalogOutputSignal` / `getAnalogOutputSignal` support) to trigger both **Smart Port (`trera:smart_port`)** and **Cargo Station (`trera:cargo_station`)** automatically, with `trera:chunk_loading_upgrade` active.
2. **Real Natural-World Transit**:
   - **Electric Smart Cargo Boat** navigated the real natural curved bay/kelp waterway with auto-return.
   - **Standard & Heavy Transport Drones** climbed VTOL above jungle canopies/hills and flew to Base B with Base-B-paid auto-return.
3. **Main Refining Base B (Natural Shore B)**:
   - Incoming Smart Port & Cargo Station unloaded mined raw ores into Base B Receiving Chest → **`mhera:macerator`** (`pulling_upgrade` + `ejector_upgrade` + `overclocker_upgrade`) → **`mhera:electric_furnace`** (`ejector_upgrade`) → finished **`minecraft:copper_ingot`** landed automatically in the final **`mhera:steel_storage_box`**!

### Natural-World Production Line & Live Client↔Server Matrix

| Target | Java | Flat QA (`write` + cold `read`) | Natural-World Mining Line (`runFieldTest`) | Live Graphical Client (`1280×720`) ↔ Dedicated Server (TCP) | Errors in Logs |
|---|---|---|---|---|---|
| **Forge 1.19.2** | 17 | **375 + 12 = 387 PASS** | **56/56 PASS** (271 natural ores mined → 65 refined ingots) | **48/48 PASS** (22/22 textures, GUIs, 140.3m player boat ride, 6 screenshots) | **0** |
| **NeoForge 1.21.1** | 21 | **132 + 7 = 139 PASS** | **39/39 PASS** (27 natural ores mined → 7 refined ingots) | **PASS** (22/22 sprites, GUIs, 75.4m player boat ride, 6 screenshots) | **0** |
| **NeoForge 26.1.2** | 25 | **132 + 7 = 139 PASS** | **39/39 PASS** (30 natural ores mined → 5 refined ingots) | **PASS** (22/22 sprites, GUIs, 78.0m player boat ride, 6 screenshots) | **0** |
| **NeoForge 26.2** | 25 | **132 + 7 = 139 PASS** | **39/39 PASS** (30 natural ores mined → 5 refined ingots) | **PASS** (22/22 sprites, GUIs, 78.7m player boat ride, 6 screenshots) | **0** |
| **Core Engine** | 17/21 | **43/43 JUnit 5 PASS** | — | — | **0** |

---

## 3. Real-World Bugs Caught & Fixed in `v1.0.1`

1. **Waterlogged Obstacle & Submerged Water Surface Guards (`LevelWaterGrid`)**:
   - NeoForge `isNavigableWater` now enforces `state.getCollisionShape(chunk, cursor).isEmpty()`, rejecting waterlogged fences/slabs/signs/trapdoors, and `isPassableHeadroom` queries `chunk` directly without triggering extra `ServerLevel` lookups on probed chunks.
   - Forge 1.19.2 `isPassableHeadroom` now rejects `FluidTags.WATER` so submerged ocean-floor blocks cannot be mistaken for open water surfaces.
2. **Vanilla Redstone Comparator Support (`SmartPortBlock` & `CargoStationBlock`)**:
   - Added `hasAnalogOutputSignal` (`true`) and `getAnalogOutputSignal` (`0..15` over cargo buffer slots) on all four targets so vanilla Redstone Comparators natively automate mining/factory dispatch loops.
3. **Oversized Container Extraction Remainder Guard (`AbstractLogisticsStationBlockEntity`)**:
   - Prevented item loss in `loadOutgoingCargo` / `addToCargoList` when Transfer API / modded containers aggregate stacks exceeding vehicle slot headroom (caught live on NeoForge 26.x preventing 8-item loss).
4. **Live Client GUI & Rendering Polish (Caught via Real `1280×720` Screenshots)**:
   - Fixed Forge 1.19.2 `CargoStationRenderer` sampling block light from inside the opaque station block (causing pitch-black roof drone silhouettes) → now samples `LevelRenderer.getLightColor(level, pos.above())` matching NeoForge.
   - Fixed compact `"Return: ON"` / `"Return: OFF"` toggle button text clipping and target LCD `[OK]` / `[6/6]` readout truncation on both Forge and NeoForge.
   - Fixed live top header title staying stale after renaming a station in-GUI.
5. **Multi-Boat Berth Priority for Seated Players (`SmartPortBlockEntity.findDockedBoat`)**:
   - Prioritizes launching a docked boat with a seated passenger before tie-breaking by berth distance on all four targets (caught live when two boats were docked at the same harbor).
6. **Mixin Config Metadata (`trera.mixins.json`)**:
   - Added `"minVersion": "0.8"` across Forge & NeoForge to eliminate Mixin startup warnings.
