# LogisticsCraft²: The New Era (`lc2`) — Design & Engineering Specification

Addon mod for **IndustrialCraft²: The New Era (`ic2` v1.0.5+)** supporting four targets:
- **Forge 1.19.2** (Forge 43.5.0, Java 17) — built from `forge-1.19.2/` (Gradle 8.8)
- **NeoForge 1.21.1** (NeoForge 21.1.265, Java 21) — built from root `:neoforge:1.21.1` (Gradle 9.2.1)
- **NeoForge 26.1.2** (NeoForge 26.1.2.128, Java 25) — built from root `:neoforge:26.1.2` (Gradle 9.2.1)
- **NeoForge 26.2** (NeoForge 26.2.0.95, Java 25) — built from root `:neoforge:26.2` (Gradle 9.2.1)

License: **GPL-3.0-only** (code) / **CC BY-SA 4.0** (original authored textures).
Mod ID: `lc2` · Root Package: `com.logisticscraft.lc2` · Version: `1.2.0`

---

## 1. Registry IDs (Canonical across all 4 versions)

### Blocks (3)
| ID | English | 中文 |
|---|---|---|
| `lc2:smart_port` | Smart Port | 智能港口 |
| `lc2:cargo_station` | Cargo Station | 货运基站 |
| `lc2:comm_base_station` | Communication Base Station | 通讯基站 |

### Block Entities (3)
- `lc2:smart_port`
- `lc2:cargo_station`
- `lc2:comm_base_station`

### Entities (2)
- `lc2:electric_cargo_boat` — Electric Smart Cargo Boat (电动智能运输船)
- `lc2:cargo_drone` — Cargo Drone entity supporting two variants (`STANDARD` = Transport Drone, `HEAVY` = Heavy Transport Drone)

### Items (11 total = 3 BlockItems + 8 Items)
| ID | English | 中文 | Stack Size |
|---|---|---|---|
| `lc2:smart_port` | Smart Port | 智能港口 | 64 |
| `lc2:cargo_station` | Cargo Station | 货运基站 | 64 |
| `lc2:comm_base_station` | Communication Base Station | 通讯基站 | 64 |
| `lc2:electric_cargo_boat` | Electric Smart Cargo Boat | 电动智能运输船 | 1 |
| `lc2:transport_drone` | Transport Drone | 运输无人机 | 1 |
| `lc2:heavy_transport_drone` | Heavy Transport Drone | 重型运输无人机 | 1 |
| `lc2:rf_chip_1` | RF Chip I | 射频芯片 I | 1 |
| `lc2:rf_chip_2` | RF Chip II | 射频芯片 II | 1 |
| `lc2:rf_chip_3` | RF Chip III | 射频芯片 III | 1 |
| `lc2:chunk_loading_upgrade` | Chunk Loading Upgrade | 区块加载升级 | 16 |
| `lc2:operation_terminal` | Operation Terminal | 操作终端 | 1 |

### Menus (3)
- `lc2:smart_port` (local read/write or remote read-only mode)
- `lc2:cargo_station` (local read/write or remote read-only mode)
- `lc2:electric_cargo_boat` (54-slot cargo hold when docked/idle)
- *(The Operation Terminal Selection Screen is a client `Screen` driven by `TerminalSyncPayload` / `S2CTerminalSyncPacket` with no container `MenuType`.)*

### Creative Tab (1)
- `lc2:main` — `LogisticsCraft²` / `物流时代2` (includes both empty `0 EU` and fully charged `10,000 EU` `lc2:operation_terminal` entries)

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
  - `ic2:ejector_upgrade` & `ic2:advanced_ejector_upgrade`: pushes cargo out **ONLY from the Storage Area (`27..53`)** to configured side/filter (in addition to default adjacent container output!).
  - `ic2:pulling_upgrade` & `ic2:advanced_pulling_upgrade`: pulls outgoing cargo from configured side/filter **ONLY into the Outbound Area (`0..26`)**!
  - `ic2:redstone_inverter_upgrade`: inverts redstone dispatch trigger (active when unpowered instead of powered!).
  - `lc2:chunk_loading_upgrade`: force-loads a **3×3 chunk area** centered on this block while the block has EU (`>= 2 EU/t`, drains **2 EU/t** standby). Immediately releases chunk ticket if EU runs out, upgrade is removed, or block is broken.
- **2-Zone Inventory Architecture & Adjacent Container Connection** (identical on both Smart Port and Cargo Station):
  - **54 internal cargo slots** (`LogisticsConstants.STATION_TOTAL_CARGO_SLOTS = 54`) divided cleanly into two equal 27-slot (`3×9`) zones:
    - **Outbound Area / 待发货区 (`0..26`, `STATION_OUTBOUND_SLOTS = 27`)**:
      - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **insert ONLY** into `0..26`.
      - IC2 `pulling_upgrade` / `advanced_pulling_upgrade` pulls **ONLY** into `0..26`.
      - Outgoing boats and drones ship/load cargo **ONLY** from `0..26` (plus adjacent pull sources when configured, never pulling back from Storage `27..53`).
    - **Storage Area / 存储区 (`27..53`, `STATION_STORAGE_SLOTS = 27`)**:
      - Received cargo unloaded from arriving boats/drones goes into `27..53` (and adjacent receiving containers).
      - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **extract ONLY** from `27..53`.
      - IC2 `ejector_upgrade` / `advanced_ejector_upgrade` ejects **ONLY** from `27..53`.
    - **Atomic Swap (`⇄`)**:
      - Center Swap Button atomically exchanges every slot pair `i <-> 27 + i` (`i ∈ 0..26`) server-side so players can immediately re-route received cargo back out without manually dragging 27 stacks.
  - Automatically interfaces with adjacent vanilla Chests, Double Chests, Barrels, Shulker Boxes, Hoppers, IC2 Storage Boxes (`iron_storage_box`, `steel_storage_box`, `iridium_storage_box`), and any modded `IItemHandler` / `Container` / `WorldlyContainer`.
  - **Loop-free I/O rule**:
    - Incoming/unloaded cargo from arriving boats/drones is pushed into adjacent receiving containers (respecting `ejector_upgrade` if installed, else any non-port/station adjacent container); overflow stays in the **Storage Area (`27..53`)** and retries pushing periodically, never re-entering **Outbound (`0..26`)** unless explicitly swapped or moved by the player.
    - Outgoing loading (when dispatching a vehicle): loads from the **Outbound Area (`0..26`)** first, then pulls remaining vehicle capacity from adjacent containers (respecting `pulling_upgrade` if installed, else any adjacent container). Never drops or deletes items on overflow!

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

### 2.5 Remote Control System: Communication Base Station & Operation Terminal
- **Communication Base Station (`lc2:comm_base_station`)**:
  - **Model & Blockstate**: No own GUI. Custom 6-element pedestal + mast + crossed-dipole antenna + top beacon 3D JSON model strictly within `1×1×1` (`[0,0,0]..[16,16,16]`) with matching custom `VoxelShape` and `active` blockstate property (`multipart`, switching front telemetry panel and unshaded glowing beacon when online). Implements `com.industrialage.ia2.wrench.WrenchTarget` and `LogisticsPowerBlock` (IC2 cables connect visually via `CableBlockMixin`).
  - **Dual EU + FE Power Input (`1 EU = 4 FE`, `EnergyUnits.FE_PER_EU = 4`)**:
    - Accepts **IC2 EU** on all 6 faces (`WorldPowerPort` / `EuBuffer` / `WorldEnergy`) AND **Forge/NeoForge Energy (FE)** on all 6 faces (`ForgeCapabilities.ENERGY` on Forge 1.19.2; `Capabilities.EnergyStorage.BLOCK` on NeoForge 1.21.1; `Capabilities.Energy.BLOCK` transactional `EnergyHandler` on NeoForge 26.1.2 & 26.2).
    - Input limit: **LV `32 EU/t` (`128 FE/t`)** (`COMM_BASE_INPUT_TIER = 1`, `COMM_BASE_PACKET_LIMIT_EU = 32`).
    - Internal buffer: **`4,000 EU` (`16,000 FE`)** (`COMM_BASE_CAPACITY_EU = 4_000.0`).
    - Standby drain: **`2 EU/t` (`8 FE/t`)** (`COMM_BASE_STANDBY_EU_PER_TICK = 2.0`). Remains online (`active = true`) while stored energy covers standby drain; switches offline (`active = false`) when depleted.
  - **6-Face Adjacency Scanning**:
    - Non-chunk-loading scan of all 6 face-adjacent positions, linking up to **6** adjacent `lc2:smart_port` and/or `lc2:cargo_station` blocks.
  - **Non-Terminal Inspection**:
    - Right-clicking a Communication Base Station without holding an Operation Terminal sends a localized actionbar summary showing online/offline state, stored EU/FE (`%s / 4000 EU (%s / 16000 FE)`), and adjacent linked station names.
- **Operation Terminal (`lc2:operation_terminal`)**:
  - **Internal Battery & Dual EU + FE Charging**:
    - Stack size `1`, persistent `lc2TerminalId` UUID, `lc2TerminalEu` energy value, and `lc2Bindings` list (stored in NBT on 1.19.2 and `CUSTOM_DATA` on 1.21.1/26.x), cyan durability bar, and live EU/FE + bound-count tooltips.
    - Capacity: **`10,000 EU` (`40,000 FE`)** (`TERMINAL_CAPACITY_EU = 10_000.0`), LV charging up to **`32 EU/t` (`128 FE/t`)** (`TERMINAL_INPUT_TIER = 1`, `TERMINAL_PACKET_LIMIT_EU = 32`).
    - Supports **BOTH** charging ecosystems simultaneously:
      - **IC2 portable charging ecosystem** (`PortableGearMixin` on NeoForge / `ForgeGearMixin` on Forge 1.19.2 hooking `acceptsCharge`, `charge`, `transfer`, `manualTransfer`, `stored`, `capacity`): BatBox, CESU, MFE, MFSU, ChargePads, Charging Batteries, and BatPacks.
      - **FE item chargers**: `ForgeCapabilities.ENERGY` on Forge 1.19.2, `Capabilities.EnergyStorage.ITEM` on NeoForge 1.21.1, and `Capabilities.Energy.ITEM` transactional `EnergyHandler` (`SnapshotJournal<Double>` + `ItemAccess`) on NeoForge 26.1.2 & 26.2.
  - **Remote Dispatch Energy Rule**:
    - Deducts a fixed **`100 EU` (`400 FE`)** (`TERMINAL_DISPATCH_COST_EU = 100.0`) from the Operation Terminal **ONLY on successful remote manual dispatch**.
    - **0 EU** cost for opening the selection list, searching/refreshing, inspecting a remote station, renaming, changing targets, toggling Auto-Return / Energy Priority, swapping Outbound & Storage, or failed dispatch attempts.
    - Creative-mode players bypass the `100 EU` cost. Even at `0 EU`, the terminal still allows full remote viewing and configuration; only remote launch is rejected with `DispatchFailure.TERMINAL_INSUFFICIENT_ENERGY` (`status.lc2.failure.terminal_insufficient_energy`).
- **Binding & Selection GUI**:
  - **Bind / Unbind**: Right-click any Communication Base Station while holding an Operation Terminal to bind it (allowed even if unpowered or not yet adjacent to a station; up to **`256`** bindings per terminal, `TERMINAL_MAX_BINDINGS = 256`). Sneak-right-click a bound Communication Base Station with the terminal, or click the per-row **Unbind (`×`)** button in the Selection GUI, to unbind.
  - **Open Selection Screen**: Right-click air (or any non-comm block) while holding the terminal, OR press the configurable hotkey (**`G`**, `key.lc2.open_terminal` in category `key.categories.lc2`), which locates an Operation Terminal in hands, main inventory, or Curios `charm`/`belt` slots.
  - **Flat Deduplicated Unlimited-Range Same-Dimension List** (`TerminalTargetResolver`):
    - Unlimited range within the same dimension (requires the target Communication Base Station and station chunks to be loaded/entity-ticking; probing uses non-forcing chunk lookup).
    - Expands each same-dimension bound Communication Base Station into its adjacent Smart Ports / Cargo Stations (or a single status entry when unpowered, unattached, unloaded, or missing), deduplicating stations shared across multiple bound base stations (preferring `ONLINE`, then earliest binding order).
    - Deterministic **online-first** sorting (`ONLINE` → `UNPOWERED` → `NO_ADJACENT_STATION` → `CHUNK_UNLOADED` → `WRONG_DIMENSION` → `COMM_MISSING`), live search filter (name, type, coordinates), Refresh button, status LEDs, Connect button, and Unbind button.
- **Remote Read-Only Station GUI & 8-Condition Disconnect Guard**:
  - Connecting to an online station opens its `lc2:smart_port` or `lc2:cargo_station` menu in **Remote Read-Only Mode**.
  - **Anti-Teleport Read-Only Locking (Server + Client)**: All machine-side slots (`0..58` on Smart Port; `0..65` on Cargo Station) enforce `mayPlace=false`, `mayPickup=false`, `allowModification=false`, `canDragTo=false`, and block machine-slot clicks plus `QUICK_MOVE` / `PICKUP_ALL` with full server-to-client re-sync, preventing remote item teleportation.
  - **Full Remote Controls**: Renaming + Save, Auto-Return toggle, Energy Priority toggle, target cycling (`<` / `>`) and manual X/Y/Z coordinate assignment, atomic Outbound/Storage Swap (`⇄`), manual Launch (`100 EU` on success), plus the header Remote Read-Only badge/tooltip and **`"Back"` / `"返回列表"`** (`gui.lc2.back_to_terminal`) button returning straight to the selection list.
  - **Real-Time 8-Condition Disconnect Guard** (`TerminalTargetResolver.validateRemoteSession`, enforced every tick in `stillValid` and on every remote action):
    1. Same dimension (`WRONG_DIMENSION`)
    2. Communication Base Station chunk loaded & entity-ticking (`CHUNK_UNLOADED`)
    3. Target logistics station chunk loaded & entity-ticking (`CHUNK_UNLOADED`)
    4. Communication Base Station still present (`COMM_MISSING`)
    5. Communication Base Station still powered/online (`COMM_UNPOWERED`)
    6. Target logistics station still present (`STATION_MISSING`)
    7. Target logistics station still face-adjacent to the Communication Base Station (`NOT_ADJACENT`)
    8. Bound Operation Terminal still carried in player inventory or Curios (`TERMINAL_MISSING`)
    - Breaking any condition closes the remote screen immediately and shows a one-shot localized actionbar disconnect reason (`status.lc2.remote.disconnected.*`).
- **Optional Soft-Compat Integrations (All 4 Targets — Zero Hard Dependencies)**:
  - **IC2 (`ic2` v1.0.5+)**: Cable visuals (`CableBlockMixin`), IC2 Wrench (`WrenchTarget`), and full portable EU charging (`PortableGearMixin` / `ForgeGearMixin`).
  - **FE (Forge / NeoForge Energy)**: 6-face FE block input on `lc2:comm_base_station` and FE item charging on `lc2:operation_terminal` (`ForgeCapabilities.ENERGY` on 1.19.2; `Capabilities.EnergyStorage` on 1.21.1; `Capabilities.Energy` transactional `EnergyHandler` on 26.x).
  - **The One Probe (TOP)**: Soft IMC provider (`Lc2TopCompat` + `Lc2TopProvider`) reporting Comm Base Station online/offline state, EU/FE energy, and linked stations (plus station telemetry).
  - **Jade**: `@WailaPlugin` (`Lc2JadePlugin`, `config.jade.plugin_lc2.comm_base_station`) with server-synced Comm Base Station status, EU/FE energy, and linked stations.
  - **WTHIT**: `wthit_plugins.json` + `Lc2WthitCommonPlugin` / `Lc2WthitClientPlugin` (`Lc2WthitPlugin` on 1.19.2, `config.waila.plugin_lc2`) with server-synced Comm Base Station status, EU/FE energy, and linked stations.
  - **Curios API**: Item tags `data/curios/tags/items/{charm,belt}.json` (1.19.2) / `data/curios/tags/item/{charm,belt}.json` (1.21.1/26.x) + bytecode-isolated `Lc2CuriosCompat` allowing `lc2:operation_terminal` in `charm` or `belt` slots with full `G` hotkey & remote session support.
  - **JEI & EMI**: `@JeiPlugin` (`Lc2JeiPlugin`) and `@EmiEntrypoint` (`Lc2EmiPlugin`) registering custom info pages (`info.lc2.comm_base_station` and `info.lc2.operation_terminal`).

### 2.6 GUI & Networking Specification (v1.2.0 2-Zone Layout & Operation Terminal — see `docs/GUI_LAYOUT.md`)
Both **Smart Port** and **Cargo Station** use an identical **`392×234`** IC2-styled dual-zone panel baked into a **`512×256`** RGBA PNG atlas (fits `1280×720` at GUI Scale 3 cleanly; dynamic sprites in right margin `u = 396..444`):
- **Shared Top Control Bar (`y = 0..55`)**:
  - Header title + (in Remote Mode only) Remote Read-Only badge/tooltip (`gui.lc2.remote_readonly`) and **`"Back"` / `"返回列表"`** button (`gui.lc2.back_to_terminal`) at `(279, 1, 68, 11)`
  - Station Name `EditBox` + Save `Button` + Auto-Return toggle (`返航: 开/关`) + Energy Priority toggle (`EU优先 / 电池优先`)
  - Target Selector: Prev `<` / Target Readout LCD (`256px` wide showing Name, Distance, Waterway/Bay status) / Next `>` + Manual Launch `Button` (`发车`)
  - Manual X/Y/Z `EditBox` inputs + Set `Button` + Status Readout LCD (`171px` wide showing live status or `DispatchFailure`)
- **Shared 2-Zone Cargo Section (`y = 77..141`)**:
  - **Outbound Area / 待发货区 (`0..26`, 3×9, left, warm amber accent)**
  - **Center Swap Button (`⇄`)** at `(172, 104, 16, 20)` with baked 12×10 swap-arrow icon at UV `(414, 64, 12, 10)` — triggers atomic C2S swap of `i <-> 27 + i` (`0..26`)
  - **Storage Area / 存储区 (`27..53`, 3×9, right, cool cyan accent)**
- **Shared Player Inventory (`y = 144..229`)**: centered beneath both zones (`x = 99`, 27 inventory + 9 hotbar)
- **Shared Right Power & Upgrade Column (`x = 355..388`)**:
  - Live EU gauge (`xxxx / 14400 EU`), EU bolt indicator, Single-Use Battery slot (1), Machine Upgrade slots (4), Chunk-Loader active badge, Vehicle/Port ready badge
- **Machine-Specific Middle Deck Strip (`y = 56..73`)**:
  - **Smart Port** (`59` machine slots + `36` player = `95` total): full-width Harbor Telemetry LCD `(7, 56, 340, 18)` showing docked boat & waterway berth status (`0..26` Outbound, `27..53` Storage, `54` Battery, `55..58` Upgrades).
  - **Cargo Station** (`66` machine slots + `36` player = `102` total): RF Chip slot `(8, 57)` + RF Range Mini-LCD `(26, 56, 42, 18)` + 6 Drone Bay slots `(74..164, 57)` + Flight Deck Status LCD `(186, 56, 161, 18)` (`0..26` Outbound, `27..53` Storage, `54` RF Chip, `55..60` 6 Drone Bays, `61` Battery, `62..65` Upgrades).
- **Operation Terminal Selection Screen (`assets/lc2/textures/gui/operation_terminal.png`)**:
  - **`320×224`** IC2-styled panel on a **`512×256`** RGBA PNG atlas (dynamic sprites in right margin `u = 328..452`): header title + `124×9` EU/FE LCD mini-bar, search `EditBox` + Refresh button, 6-row scrollable station list viewport (`296×166`) with vertical scrollbar (`6×166`), type badges, status LEDs, Connect / Unbind buttons, and footer status LCD (`304×12`).
- **Networking**:
  - NeoForge `Lc2Network` protocol v3 (`StationActionPayload`, `StationSyncPayload`, `TerminalActionPayload`, `TerminalSyncPayload`) and Forge 1.19.2 `Lc2Network` `PROTOCOL_VERSION = "2"` (`C2SStationActionPacket`, `S2CStationSyncPacket`, `C2STerminalActionPacket`, `S2CTerminalSyncPacket`).

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
10. `lc2:comm_base_station`: `C F C / I M I / W B W` (`C=ic2:circuit`, `F=ic2:frequency_transmitter`, `I=ic2:iron_plate`, `M=ic2:machine`, `W=ic2:insulated_copper_cable`, `B=ic2:re_battery`)
11. `lc2:operation_terminal`: `I G I / C F C / W B W` (`I=ic2:iron_plate`, `G=minecraft:glass_pane`, `C=ic2:circuit`, `F=ic2:frequency_transmitter`, `W=ic2:insulated_copper_cable`, `B=ic2:re_battery`)

---

## 4. Repository Layout & Worker Ownership

- `src/common/` — Pure Java 17 core engine (NO Minecraft / NeoForge / Forge imports). Owned by `core-engine`.
- `src/test/` — JUnit 5 unit & simulation tests for `common`. Owned by `core-engine` & `qa-verifier`.
- `assets/lc2/` & `src/datagen/` — Textures, lang files, and version-aware resource/recipe/loot/model generator. Owned by `art-assets`.
- `src/neoforge/` — NeoForge 1.21.1, 26.1.2, 26.2 implementation (`shared`, `1.21.1`, `26.x`, `pre-26.2`, `26.2`, `metadata`). Owned by `neoforge-impl`.
- `forge-1.19.2/` — Standalone Forge 1.19.2 (Gradle 8.8, Java 17) project sharing `../src/common`, `../assets`, `../src/datagen`. Owned by `forge-1192`.
- `src/qa/` — Cross-version live dedicated-server integration tests loading real IC2 v1.0.5 + LC2 v1.2.0. Owned by `qa-verifier`.
- `.ic2-deps/` — Gitignored directory holding the 4 official IC2 v1.0.5 JARs.
