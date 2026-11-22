# LogisticsCraft² (`lc2` v1.1.0) — 2-Zone GUI Pixel Layout Specification

Both machine screens (`lc2:smart_port` and `lc2:cargo_station`) use an identical **`512 × 256`** RGBA PNG atlas with the main beveled IC2-style panel occupying `(0, 0)` to `(391, 233)` (**`imageWidth = 392`**, **`imageHeight = 234`** — fits cleanly inside `1280 × 720` at GUI Scale 3: `1176 × 702`). Dynamic overlay sprites live in the right margin (`u = 396..444`).

> **IMPORTANT (Atlas Dimensions):** Because the GUI texture atlas is **`512 × 256`** (not `256 × 256`), every `GuiGraphics.blit(...)` / `GuiComponent.blit(...)` call MUST explicitly pass **`textureWidth = 512, textureHeight = 256`** across all four targets (NeoForge 1.21.1, 26.1.2, 26.2, and Forge 1.19.2).

All slot `(x, y)` coordinates below are **Minecraft `Slot(container, index, x, y)` item top-left coordinates** (the 18×18 recessed slot frame baked into the PNG sits at `(x - 1, y - 1)` through `(x + 16, y + 16)`).

---

## 1. 2-Zone Inventory Semantics (Identical on Smart Port & Cargo Station)

Both stations provide **54 internal cargo slots** (`LogisticsConstants.STATION_TOTAL_CARGO_SLOTS = 54`) split cleanly into two equal 27-slot (`3 × 9`) zones:

1. **Outbound Area / 待发货区 (`0..26`, `STATION_OUTBOUND_SLOTS = 27`, left side, warm amber accent)**
   - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **insert ONLY** into `0..26`.
   - IC2 `pulling_upgrade` / `advanced_pulling_upgrade` **pulls ONLY** into `0..26`.
   - Outgoing Electric Smart Cargo Boats and Transport / Heavy Drones **load ONLY** from `0..26`.
2. **Storage Area / 存储区 (`27..53`, `STATION_STORAGE_SLOTS = 27`, right side, cool cyan accent)**
   - Incoming cargo unloaded from arriving boats and drones goes into `27..53` (and adjacent receiving containers).
   - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **extract ONLY** from `27..53`.
   - IC2 `ejector_upgrade` / `advanced_ejector_upgrade` **ejects ONLY** from `27..53`.
3. **Center Swap Button (`⇄`) at `(172, 104, 16, 20)`**
   - Atomically swaps every slot pair `i <-> 27 + i` for `i ∈ 0..26` server-side, marks the station dirty, and reports `status.lc2.swapped`.
   - Renders the crisp baked 12×10 dual-color swap arrows sprite from UV `(414, 64, 12, 10)` centered on the button at `(leftPos + 174, topPos + 109)` (zero Unicode glyph box issues across 1.19.2..26.2). Uses `gui.lc2.swap` (`"<->"`) as ASCII fallback, `gui.lc2.swap.title` as narration title, and `tooltip.lc2.swap` on hover.

---

## 2. Shared Regions (Identical across `smart_port.png` & `cargo_station.png`)

### Top Control Bar (`y = 0..55`, spanning `x = 8..350`)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Main Panel | `(0, 0, 392, 234)` | `imageWidth = 392`, `imageHeight = 234` |
| Title Label | `titleLabelX = 8`, `titleLabelY = 4` | Rendered in dark charcoal `#2C3440` (or `#404040`); updates live on rename |
| Row 1: Station Name `EditBox` | `(8, 13, 150, 12)` | Custom station name text input (`gui.lc2.station_name`) |
| Row 1: Save `Button` | `(162, 13, 44, 12)` | `gui.lc2.save` |
| Row 1: Auto-Return `Button` | `(210, 13, 66, 12)` | `gui.lc2.auto_return.on` / `gui.lc2.auto_return.off` |
| Row 1: Energy Priority `Button` | `(279, 13, 68, 12)` | `gui.lc2.priority.eu_first` / `gui.lc2.priority.battery_first` |
| Row 2: Prev Target `Button` (`<`) | `(8, 27, 14, 12)` | Cycles same-dimension target stations backwards |
| Row 2: Target Readout LCD | `(23, 27, 256, 12)` | Baked dark LCD; draw target name + distance/status text at `(26, 29)`, `maxWidth = 250` |
| Row 2: Next Target `Button` (`>`) | `(280, 27, 14, 12)` | Cycles same-dimension target stations forwards |
| Row 2: Launch `Button` | `(297, 27, 50, 12)` | `gui.lc2.dispatch` |
| Row 3: Manual `X` `EditBox` | `(8, 41, 44, 12)` | Target X coordinate |
| Row 3: Manual `Y` `EditBox` | `(54, 41, 36, 12)` | Target Y coordinate |
| Row 3: Manual `Z` `EditBox` | `(92, 41, 44, 12)` | Target Z coordinate |
| Row 3: Set Coords `Button` | `(138, 41, 36, 12)` | `gui.lc2.set_target` |
| Row 3: Status Readout LCD | `(176, 41, 171, 12)` | Baked dark LCD; draw live status / `DispatchFailure` text at `(179, 43)`, `maxWidth = 165` |

### 2-Zone Cargo Section (`y = 77..141`, IDENTICAL on both machines)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Outbound Header Label | `(8, 77)` | Draw `gui.lc2.zone.outbound` in warm dark-amber `#7A4200` (or `#404040`) |
| Outbound Accent Bar | `(7, 85, 162, 1)` | Baked warm amber bar directly atop Outbound 3×9 recess (`7..168, 86..139`) |
| **Outbound Slots `0..26` (3×9)** | `x = 8 + col * 18`, `y = 87 + row * 18` | `col ∈ 0..8` (`8..152`), `row ∈ 0..2` (`87, 105, 123`); subtle amber-tinted inner recess border |
| **Center Swap `Button` (`⇄`)** | `(172, 104, 16, 20)` | Centered in channel `x = 170..188`; blit 12×10 sprite at `(174, 109)` from UV `(414, 64, 12, 10)` |
| Storage Header Label | `(190, 77)` | Draw `gui.lc2.zone.storage` in cool dark-teal `#004D66` (or `#404040`) |
| Storage Accent Bar | `(189, 85, 162, 1)` | Baked cool cyan bar directly atop Storage 3×9 recess (`189..350, 86..139`) |
| **Storage Slots `27..53` (3×9)** | `x = 190 + col * 18`, `y = 87 + row * 18` | `col ∈ 0..8` (`190..334`), `row ∈ 0..2` (`87, 105, 123`); subtle cyan-tinted inner recess border |

### Player Inventory (`y = 144..229`, centered beneath both zones)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Inventory Label | `inventoryLabelX = 99`, `inventoryLabelY = 144` | Standard player inventory label |
| Player Inventory (27 slots, 3×9) | `x = 99 + col * 18`, `y = 153 + row * 18` | `col ∈ 0..8` (`99..243`), `row ∈ 0..2` (`153, 171, 189`) |
| Player Hotbar (9 slots, 1×9) | `x = 99 + col * 18`, `y = 211` | `col ∈ 0..8` (`99..243`) |

### Shared Right Power & Upgrade Column (`x = 355..388`)

| Element | Screen Rect `(x, y, w, h)` | Dynamic Sprite UV `(u, v, w, h)` | Notes |
|---|---|---|---|
| Vertical Bevel Divider | `x = 355` (`y = 13..227`) | Baked into background | 2px bevel seam (`355` shadow, `356` highlight) |
| EU Gauge Frame | `(362, 13, 18, 42)` | Baked into background | Dark recessed gauge with 10% scale ticks; tooltip `gui.lc2.eu_stored` |
| EU Gauge Fill (bottom-up) | `(363, 14, 16, 40)` | `(396, 0, 16, 40)` | Let `h = round(40 * eu / maxEu)`; blit `(363, 14 + 40 - h)` from `(396, 40 - h)` size `(16, h)` |
| EU Bolt Indicator | `(366, 57, 10, 14)` | `(414, 0, 10, 14)` | Unlit bolt baked; blit lit sprite when `eu > 0` |
| Single-Use Battery Slot (1) | Slot `(363, 76)` (frame `362..379, 75..92`) | Battery watermark baked | Accepts only `ic2:single_use_battery` (max 64) |
| Machine Upgrade Slots (4) | Slots `(363, 102)`, `(363, 120)`, `(363, 138)`, `(363, 156)` | Upgrade watermark baked | Accepts supported IC2 upgrades + `lc2:chunk_loading_upgrade` |
| Chunk-Loader Active Badge | `(363, 180, 16, 16)` (well `362..379, 179..196`) | `(396, 64, 16, 16)` | Blit when `chunk_loading_upgrade` is actively keeping 3×3 chunks loaded |
| Vehicle / Port Ready Badge | `(363, 200, 16, 16)` (well `362..379, 199..216`) | Active `(396, 44, 16, 16)` / Warn `(414, 44, 16, 16)` | Blit cyan anchor/drone when ready, amber warning when blocked/idle |

### Right-Margin Dynamic Sprite Atlas Summary (`u = 396..444`, `512 × 256`)

| Sprite | UV `(u, v, w, h)` | Usage |
|---|---|---|
| Lit EU Gauge Fill | `(396, 0, 16, 40)` | Bottom-up segmented red→orange→gold energy fill at `(363, 14)` |
| Lit EU Lightning Bolt | `(414, 0, 10, 14)` | Power indicator at `(366, 57)` |
| Active Vehicle / Port Badge | `(396, 44, 16, 16)` | Ready cyan anchor (`smart_port.png`) or drone (`cargo_station.png`) at `(363, 200)` |
| Warning / Idle Badge | `(414, 44, 16, 16)` | Amber warning triangle at `(363, 200)` |
| Chunk-Loader Active Badge | `(396, 64, 16, 16)` | Luminous ender-teal 3×3 chunk grid at `(363, 180)` |
| Swap Arrows Icon (`⇄`) | `(414, 64, 12, 10)` | Transparent-BG amber `→` / cyan `←` icon blitted on Swap Button at `(174, 109)` |

---

## 3. Smart Port Specifics (`assets/lc2/textures/gui/smart_port.png`)

- **Middle Deck Harbor Telemetry LCD Strip**:
  - Baked dark LCD at `(7, 56, 340, 18)` with subtle 2px maritime cobalt/cyan left accent (`x = 8..9`).
  - Render docked boat status & waterway berth info at `(12, 61)` using `gui.lc2.berth_docked` / `gui.lc2.berth_empty` and `gui.lc2.water_ok` / `gui.lc2.water_blocked`.
- **Canonical Menu Slot Index Contract** (`59` machine slots + `36` player slots = **`95` total**):
  - `0..26`: **Outbound Area** (`3×9` at `x = 8 + col * 18`, `y = 87 + row * 18`)
  - `27..53`: **Storage Area** (`3×9` at `x = 190 + col * 18`, `y = 87 + row * 18`)
  - `54`: Single-Use Battery Slot `(363, 76)`
  - `55..58`: 4 Machine Upgrade Slots `(363, 102)`, `(363, 120)`, `(363, 138)`, `(363, 156)`
  - `59..85`: Player Inventory (`3×9` at `x = 99 + col * 18`, `y = 153 + row * 18`)
  - `86..94`: Player Hotbar (`1×9` at `x = 99 + col * 18`, `y = 211`)

---

## 4. Cargo Station Specifics (`assets/lc2/textures/gui/cargo_station.png`)

- **Middle Deck Strip (`y = 56..73`)**:
  - **RF Chip Slot (1 slot)**: Slot `(8, 57)` (frame `7..24, 56..73`) — recessed with cyan top highlight & antenna watermark. Accepts `lc2:rf_chip_1`, `lc2:rf_chip_2`, `lc2:rf_chip_3`.
  - **RF Range Mini-LCD**: Baked dark LCD at `(26, 56, 42, 18)` — render current RF range string (`"256m"`, `"512m"`, `"1024m"`, or `gui.lc2.rf_none`) centered around `(29, 61)`.
  - **Drone Bay Slots (6 slots, 1×6)**: `x = 74 + bay * 18`, `y = 57` (`bay ∈ 0..5`: `74, 92, 110, 128, 146, 164`; frames `73..180, 56..73`) — each slot has a subtle drone-pad watermark. Accepts `lc2:transport_drone` and `lc2:heavy_transport_drone` (max stack 1).
  - **Flight Deck Status LCD**: Baked dark LCD at `(186, 56, 161, 18)` — render ready drone bay count (`gui.lc2.flight_deck_ready`) & RF range status (`gui.lc2.in_range` / `gui.lc2.out_of_range`) at `(190, 61)`.
- **Canonical Menu Slot Index Contract** (`66` machine slots + `36` player slots = **`102` total**):
  - `0..26`: **Outbound Area** (`3×9` at `x = 8 + col * 18`, `y = 87 + row * 18`)
  - `27..53`: **Storage Area** (`3×9` at `x = 190 + col * 18`, `y = 87 + row * 18`)
  - `54`: RF Chip Slot `(8, 57)`
  - `55..60`: 6 Drone Bay Slots `0..5` (`74, 92, 110, 128, 146, 164`, `y = 57`)
  - `61`: Single-Use Battery Slot `(363, 76)`
  - `62..65`: 4 Machine Upgrade Slots `(363, 102)`, `(363, 120)`, `(363, 138)`, `(363, 156)`
  - `66..92`: Player Inventory (`3×9` at `x = 99 + col * 18`, `y = 153 + row * 18`)
  - `93..101`: Player Hotbar (`1×9` at `x = 99 + col * 18`, `y = 211`)

---

## 5. Electric Smart Cargo Boat Menu (`lc2:electric_cargo_boat`)

The Electric Smart Cargo Boat has a **54-slot** cargo hold (`6 rows × 9 cols`), matching vanilla Minecraft's standard `ChestMenu.sixRows` (`minecraft:textures/gui/container/generic_54.png`, `176×222`). No custom GUI PNG is required for the boat hold.
