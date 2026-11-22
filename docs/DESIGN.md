# LogisticsCraft²: The New Era (`lc2`) — Design & Engineering Specification

Addon mod for **IndustrialCraft²: The New Era (`ic2` v1.0.5+)** supporting four targets:
- **Forge 1.19.2** (Forge 43.5.0, Java 17) — built from `forge-1.19.2/` (Gradle 8.8)
- **NeoForge 1.21.1** (NeoForge 21.1.265, Java 21) — built from root `:neoforge:1.21.1` (Gradle 9.2.1)
- **NeoForge 26.1.2** (NeoForge 26.1.2.128, Java 25) — built from root `:neoforge:26.1.2` (Gradle 9.2.1)
- **NeoForge 26.2** (NeoForge 26.2.0.95, Java 25) — built from root `:neoforge:26.2` (Gradle 9.2.1)

License: **GPL-3.0-only** (code) / **CC BY-SA 4.0** (original authored textures).
Mod ID: `lc2` · Root Package: `com.logisticscraft.lc2` · Version: `1.0.0`

---

## 1. Registry IDs (Canonical across all 4 versions)

### Blocks (2)
| ID | English | 中文 |
|---|---|---|
| `lc2:smart_port` | Smart Port | 智能港口 |
| `lc2:cargo_station` | Cargo Station | 货运基站 |

### Block Entities (2)
- `lc2:smart_port`
- `lc2:cargo_station`

### Entities (2)
- `lc2:electric_cargo_boat` — Electric Smart Cargo Boat (电动智能运输船)
- `lc2:cargo_drone` — Cargo Drone entity supporting two variants (`STANDARD` = Transport Drone, `HEAVY` = Heavy Transport Drone)

### Items (9 total = 2 BlockItems + 7 Items)
| ID | English | 中文 | Stack Size |
|---|---|---|---|
| `lc2:smart_port` | Smart Port | 智能港口 | 64 |
| `lc2:cargo_station` | Cargo Station | 货运基站 | 64 |
| `lc2:electric_cargo_boat` | Electric Smart Cargo Boat | 电动智能运输船 | 1 |
| `lc2:transport_drone` | Transport Drone | 运输无人机 | 1 |
| `lc2:heavy_transport_drone` | Heavy Transport Drone | 重型运输无人机 | 1 |
| `lc2:rf_chip_1` | RF Chip I | 射频芯片 I | 1 |
| `lc2:rf_chip_2` | RF Chip II | 射频芯片 II | 1 |
| `lc2:rf_chip_3` | RF Chip III | 射频芯片 III | 1 |
| `lc2:chunk_loading_upgrade` | Chunk Loading Upgrade | 区块加载升级 | 16 |

### Menus (3)
- `lc2:smart_port`
- `lc2:cargo_station`
- `lc2:electric_cargo_boat` (54-slot cargo hold when docked/idle)

### Creative Tab (1)
- `lc2:main` — `LogisticsCraft²` / `物流时代2`

---

## 2. Gameplay Mechanics & Exact Numbers

### 2.1 Shared Power, Upgrades, & Container Rules (Smart Port & Cargo Station)
- **EU Network Integration**:
  - Both block entities implement `com.industrialage.ia2.power.WorldPowerPort` and `com.industrialage.ia2.power.EuBuffer` and register via `WorldEnergy.connect(this)` / `WorldEnergy.disconnect(this)`.
  - Both blocks implement `com.industrialage.ia2.wrench.WrenchTarget` and marker interface `LogisticsPowerBlock`.
  - A Mixin (`lc2.mixins.json`) hooks IC2's `CableBlock` (`updateConnection` on NeoForge, `connects` on Forge 1.19.2) so IC2 cables visually connect arms to `LogisticsPowerBlock`.
- **Electrical Rating**:
  - Base internal EU capacity: **14,400 EU**.
  - Base input tier: **LV (32 EU/t)** (`packetLimit() = 32` at tier 1).
  - Sink on all 6 faces (`Ports(ALL_FACES, Set.of(), false)`).
- **Single-Use Battery Slot (1 slot)**:
  - Accepts **only** `ic2:single_use_battery` (up to 64).
  - Both Smart Port and Cargo Station have this slot.
- **Energy Source Priority Toggle (GUI button)**:
  - `EU_FIRST` (优先EU, default) vs `BATTERY_FIRST` (优先一次性电池).
  - Checks preferred source first; if insufficient for the full required dispatch cost, automatically falls back to the other source. Never performs a partial hybrid debit.
- **Machine Upgrade Slots (4 slots)** — accepts ALL applicable IC2 standard upgrades + `lc2:chunk_loading_upgrade`:
  - `ic2:transformer_upgrade`: +1 voltage tier per upgrade (LV 32 → MV 128 → HV 512 → EV 2048 → IV 8192 EU/t).
  - `ic2:energy_storage_upgrade`: +10,000 EU max capacity per upgrade.
  - `ic2:overclocker_upgrade`: boosts container transfer throughput (`base + 8 * count` items/tick up to 64) and speeds up dispatch cycle & modest cruise speed boost (up to +40% capped).
  - `ic2:ejector_upgrade` & `ic2:advanced_ejector_upgrade`: pushes unloaded cargo out to configured side/filter (in addition to default adjacent container output!).
  - `ic2:pulling_upgrade` & `ic2:advanced_pulling_upgrade`: pulls outgoing cargo from configured side/filter into buffer!
  - `ic2:redstone_inverter_upgrade`: inverts redstone dispatch trigger (active when unpowered instead of powered!).
  - `lc2:chunk_loading_upgrade`: force-loads a **3×3 chunk area** centered on this block while the block has EU (`>= 2 EU/t`, drains **2 EU/t** standby). Immediately releases chunk ticket if EU runs out, upgrade is removed, or block is broken.
- **Adjacent Container Connection**:
  - Automatically interfaces with adjacent vanilla Chests, Double Chests, Barrels, Shulker Boxes, Hoppers, IC2 Storage Boxes (`iron_storage_box`, `steel_storage_box`, `iridium_storage_box`), and any modded `IItemHandler` / `Container` / `WorldlyContainer`.
  - **Loop-free I/O rule**:
    - Incoming/unloaded cargo from arriving boats/drones is pushed **immediately** into adjacent containers (respecting `ejector_upgrade` if installed, else any non-port/station adjacent container); overflow stays in the block's internal buffer (and retries pushing periodically without ever being re-loaded as outgoing until room opens or player moves it).
    - Outgoing loading (when dispatching a vehicle): loads from the block's outgoing cargo buffer first, then pulls remaining vehicle capacity from adjacent containers (respecting `pulling_upgrade` if installed, else any adjacent container). Never drops or deletes items on overflow!

### 2.2 Waterway System: Smart Port & Electric Smart Cargo Boat
- **Placement**: `lc2:smart_port` has horizontal facing (`HORIZONTAL_FACING`). Should be placed adjacent to water (front facing water; auto-detects water dock cell in front first, falling back to any horizontal adjacent water cell).
- **Docking & Boarding**:
  - Electric Smart Cargo Boat (`lc2:electric_cargo_boat`) has **54 cargo slots** (exact Double Chest size).
  - Players can place the boat item onto water adjacent to a Smart Port (or right-click a Smart Port with the boat item to spawn & dock it in the port's water berth).
  - While docked/idle, players can **right-click to sit** in the boat, or **sneak-right-click** to inspect/open its 54-slot cargo hold.
  - **CRITICAL RIDING RULE**:
    - While the boat is **actively running autonomously**, players **CANNOT board** it (`interact` blocked with status message).
    - If a player **sits in the boat BEFORE launch** and then the Smart Port triggers launch (via redstone), the player **remains seated and rides along**! (Normal dismount remains available.)
- **Dispatch Conditions**:
  1. Target Smart Port set (via GUI station list or manual X/Y/Z coordinates) and exists in same dimension.
  2. Docked Electric Smart Cargo Boat available at origin port berth (not currently occupied in transit).
  3. **Water connectivity verified**: continuous navigable water path between origin port berth and target port berth. (A* search over water blocks; supports 1-block canals and open water with bank-clearance preference; caches waypoint path in `LogisticsSavedData`; probes/temporarily checks intermediate chunks only when computing a new or invalidated route).
  4. **Redstone trigger active** (high signal by default; inverted if `ic2:redstone_inverter_upgrade` installed).
  5. **Energy available**:
     - One-way (`autoReturn == false`): **3,000 EU** OR **2× `ic2:single_use_battery`**.
     - Round-trip (`autoReturn == true`): **6,000 EU** OR **4× `ic2:single_use_battery`** (doubled, paid at departure so return trip is prepaid!).
- **Arrival & Unloading**:
  - Upon reaching target Smart Port berth, boat unloads its 54-slot cargo into target port's adjacent containers + buffer.
  - If `autoReturn == true` (prepaid), after unloading completes (and no overflow blocks unloading, or after short dwell), boat automatically departs back to origin port and docks!
  - If `autoReturn == false`, boat stays docked at target port ready for next dispatch or player use.
- **Waterway Collision Avoidance**:
  - Right-hand (starboard) lane offset on wide waterways (`>= 3` wide).
  - Level-wide `TrafficController` tracks every active boat (physical + virtual), enforces safe headway spacing, yields lower-priority boat in narrow 1–2 wide channels / intersections to prevent overlap or deadlock, and reserves target berth slots!

### 2.3 Airway System: Cargo Station & Transport / Heavy Drones
- **Drone Bays**: `lc2:cargo_station` has **6 drone bay slots** (`0..5`), each mapped to a dedicated physical landing pad offset on the station top.
- **Drone Variants**:
  | Item ID | Variant | Cargo Slots | Base Cruise Speed | Energy Multiplier |
  |---|---|---|---|---|
  | `lc2:transport_drone` | `STANDARD` | **12 slots** | Fast (**0.80** blocks/tick = 16 b/s) | **1×** |
  | `lc2:heavy_transport_drone` | `HEAVY` | **36 slots** | Slower (**0.50** blocks/tick = 10 b/s) | **2×** |
- **RF Chip Requirement (1 dedicated slot)**:
  - Station **cannot operate/dispatch** without an RF chip installed.
  - Max Manhattan distance (`|dx| + |dy| + |dz|`):
    - `lc2:rf_chip_1`: **256** blocks
    - `lc2:rf_chip_2`: **512** blocks
    - `lc2:rf_chip_3`: **1024** blocks
- **Energy Cost (by actual Manhattan distance `d <= chip.maxRange`)**:
  | Distance Band | Standard Drone | Heavy Drone (2×) |
  |---|---|---|
  | `d <= 256` | **800 EU** or **1** single-use battery | **1,600 EU** or **2** single-use batteries |
  | `256 < d <= 512` | **2,000 EU** or **2** single-use batteries | **4,000 EU** or **4** single-use batteries |
  | `512 < d <= 1024` | **7,000 EU** or **5** single-use batteries | **14,000 EU** or **10** single-use batteries |
- **Dispatch Conditions**:
  1. Valid RF Chip installed and Manhattan distance `d <= chip.maxRange`.
  2. Target Cargo Station set (same dimension) and has **at least 1 free drone bay** (strictly checking `parked + inboundReserved < 6`, reserving the bay atomically on launch!).
  3. At least 1 ready drone parked in origin station's 6 bays.
  4. Redstone trigger active (respecting `redstone_inverter_upgrade`) or manual dispatch trigger.
  5. Origin station has required energy (EU or single-use batteries according to priority toggle).
- **Auto-Return (Target-Side Return Payment)**:
  - Outbound trip is paid by Origin Station on launch.
  - On arrival at Target Station, drone lands in its reserved bay and unloads cargo into target adjacent containers + buffer.
  - If `autoReturn == true`, drone enters `WAITING_RETURN_ENERGY` state at Target Station and automatically takes off back to Origin Station once:
    - Target Station has sufficient EU or single-use batteries for the return flight (same distance/variant cost, using Target's priority toggle), AND
    - Origin Station has a free drone bay (atomically reserved on return takeoff).
  - If Target Station lacks energy or Origin is full, drone waits safely in its docked bay at Target Station and retries periodically!
- **Airspace Route & 3D Collision Avoidance**:
  - **VTOL flight profile**: Ascend vertically from bay offset to assigned cruise altitude → fly along horizontal cruise corridor → descend vertically onto reserved target bay offset.
  - **Altitude Stratification**: Cruise Y = `max(originY, targetY) + 16 + directionBand + laneLayer`, where `directionBand` separates opposing headings (`+0` vs `+4`) and `laneLayer` separates concurrent routes (`0..5 * 3`), plus clearance over known terrain.
  - **Dynamic 3D Separation**: `TrafficController` checks all active drones every tick; if two drones come within `3.5` blocks, lower-priority drone temporarily climbs `+3` blocks or holds speed until clear.

### 2.4 Unloaded-Chunk Virtual Transit (Create-Train Style)
- Managed by a single per-dimension `LogisticsSavedData` + `VirtualTransitEngine` (in `src/common`).
- Every active boat/drone trip is a `TransitRecord` (UUID, type, origin, target, reservedBay, autoReturn, returnPaid, waypoints, progressIndex, segmentProgress, currentPos, yaw, speed, cargo items, state, passengerFlag).
- Every server tick (`LevelTickEvent.Post`):
  1. `VirtualTransitEngine` advances all active transits along their cached waypoint paths with full collision avoidance.
  2. **Loaded chunk**: If the vehicle's current chunk is loaded (entity-ticking), ensure its physical entity (`ElectricCargoBoatEntity` / `CargoDroneEntity`) is materialized and synchronized smoothly to the transit position/velocity/yaw (and for a boat with a seated player, keep the entity alive and synced!).
  3. **Unloaded chunk**: If the vehicle moves into an unloaded chunk (and has no seated player), cleanly dematerialize the physical entity (without dropping cargo or losing state!) while the `TransitRecord` continues advancing seamlessly tick-by-tick through unloaded chunks with zero chunk-loading overhead!
  4. **Re-entering loaded chunk**: Automatically spawns/materializes the physical entity at the exact current coordinate with its full cargo and continues smoothly!
  5. **Arrival in unloaded target chunk**: Completes transit into a pending-arrival queue for that target station/port in `LogisticsSavedData`; if the target is loaded (e.g. via player or `lc2:chunk_loading_upgrade`), immediately executes unloading & auto-return; if unloaded, completes immediately when target chunk loads!
- Full NBT persistence: server restart mid-flight resumes every boat and drone at its exact coordinates with zero item loss!

### 2.5 GUI & Networking Specification
- **Smart Port GUI** (`176×222` or clean IC2-styled `212×222` panel):
  - Station Name text input + Save/Apply
  - Target Selector: scrollable/prev-next list of same-dimension Smart Ports (showing Name, Distance, Water Status) + manual X/Y/Z input boxes
  - Auto-Return toggle (`自动返回: 开/关`)
  - Energy Priority toggle (`供电: 优先EU / 优先电池`)
  - Manual Dispatch button (`立即发车` — convenience alongside redstone trigger)
  - Live EU bar (`xxxx / 14400 EU`), Status message line (exact feedback: 就绪 / 航行中 / 水域未连通 / 能源不足 / 无停靠船只 / 无目标港口)
  - Dedicated Single-Use Battery slot (1), Machine Upgrade slots (4), Cargo Buffer slots (27), Player inventory (27+9)
- **Cargo Station GUI** (`212×222` panel):
  - Station Name text input
  - Target Selector: scrollable/prev-next list of same-dimension Cargo Stations (showing Name, Manhattan Distance, Free Bays `x/6`, In-Range indicator) + manual X/Y/Z input boxes
  - Auto-Return toggle (`自动返回: 开/关`)
  - Energy Priority toggle (`供电: 优先EU / 优先电池`)
  - Manual Dispatch button
  - Live EU bar (`xxxx / 14400 EU`), RF Chip slot (1), Single-Use Battery slot (1), 6 Drone Bay slots (showing standard/heavy icon & status), Machine Upgrade slots (4), Cargo Buffer slots (18), Player inventory (27+9)

---

## 3. Crafting Recipes (All IC2 IDs Verified against IC2 v1.0.5)

1. `lc2:smart_port`: `C M C / I H I / C R C` (`C=ic2:circuit`, `M=ic2:machine`, `I=ic2:iron_plate`, `H=minecraft:hopper`, `R=ic2:re_battery`)
2. `lc2:electric_cargo_boat`: `X C X / P B P / P E P` (`X=minecraft:chest`, `C=ic2:circuit`, `P=ic2:iron_plate`, `B=ic2:electric_boat`, `E=ic2:electric_motor`)
3. `lc2:cargo_station`: `A F A / P M P / H C H` (`A=ic2:advanced_circuit`, `F=ic2:frequency_transmitter`, `P=ic2:steel_plate`, `M=ic2:advanced_machine`, `H=minecraft:hopper`, `C=ic2:energy_crystal`)
4. `lc2:transport_drone`: `B C B / E X E / B P B` (`B=ic2:iron_rotor_blade`, `C=ic2:circuit`, `E=ic2:electric_motor`, `X=minecraft:chest`, `P=ic2:carbon_plate`)
5. `lc2:heavy_transport_drone`: `B A B / X D X / L E L` (`B=ic2:carbon_rotor_blade`, `A=ic2:advanced_circuit`, `X=minecraft:chest`, `D=lc2:transport_drone`, `L=ic2:alloy`, `E=ic2:electric_motor`)
6. `lc2:rf_chip_1`: `W F W / C R C / W G W` (`W=ic2:insulated_copper_cable`, `F=ic2:frequency_transmitter`, `C=ic2:circuit`, `R=minecraft:redstone`, `G=ic2:copper_plate`)
7. `lc2:rf_chip_2`: `W A W / G I G / W E W` (`W=ic2:insulated_gold_cable`, `A=ic2:advanced_circuit`, `G=ic2:gold_plate`, `I=lc2:rf_chip_1`, `E=minecraft:ender_pearl`)
8. `lc2:rf_chip_3`: `W A W / C I C / W L W` (`W=ic2:glass_fibre_cable`, `A=ic2:advanced_circuit`, `C=ic2:carbon_plate`, `I=lc2:rf_chip_2`, `L=ic2:lapotron_crystal`)
9. `lc2:chunk_loading_upgrade`: `A L A / C K C / A E A` (`A=ic2:alloy`, `L=ic2:insulated_gold_cable`, `C=ic2:advanced_circuit`, `K=ic2:chunk_loader`, `E=minecraft:ender_eye`)

---

## 4. Repository Layout & Worker Ownership

- `src/common/` — Pure Java 17 core engine (NO Minecraft / NeoForge / Forge imports). Owned by `core-engine`.
- `src/test/` — JUnit 5 unit & simulation tests for `common`. Owned by `core-engine` & `qa-verifier`.
- `assets/lc2/` & `src/datagen/` — Textures, lang files, and version-aware resource/recipe/loot/model generator. Owned by `art-assets`.
- `src/neoforge/` — NeoForge 1.21.1, 26.1.2, 26.2 implementation (`shared`, `1.21.1`, `26.x`, `pre-26.2`, `26.2`, `metadata`). Owned by `neoforge-impl`.
- `forge-1.19.2/` — Standalone Forge 1.19.2 (Gradle 8.8, Java 17) project sharing `../src/common`, `../assets`, `../src/datagen`. Owned by `forge-1192`.
- `src/qa/` — Cross-version live dedicated-server integration tests loading real IC2 v1.0.5 + LC2 v1.0.0. Owned by `qa-verifier`.
- `.ic2-deps/` — Gitignored directory holding the 4 official IC2 v1.0.5 JARs.
