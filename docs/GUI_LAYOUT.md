# Transitera (`trera` v1.2.0) — 2-Zone & Operation Terminal GUI Pixel Layout Specification

Both machine screens (`trera:smart_port` and `trera:cargo_station`) use an identical **`512 × 256`** RGBA PNG atlas with the main beveled MHERA-style panel occupying `(0, 0)` to `(391, 233)` (**`imageWidth = 392`**, **`imageHeight = 234`** — fits cleanly inside `1280 × 720` at GUI Scale 3: `1176 × 702`). Dynamic overlay sprites live in the right margin (`u = 396..444`). The **Operation Terminal Selection Screen** (`assets/trera/textures/gui/operation_terminal.png`, Section 6) also uses a **`512 × 256`** RGBA PNG atlas with a **`320 × 224`** main panel and right-margin sprites (`u = 328..452`).

> **IMPORTANT (Atlas Dimensions):** Because all custom GUI texture atlases are **`512 × 256`** (not `256 × 256`), every `GuiGraphics.blit(...)` / `GuiComponent.blit(...)` call MUST explicitly pass **`textureWidth = 512, textureHeight = 256`** across all four targets (NeoForge 1.21.1, 26.1.2, 26.2, and Forge 1.19.2).

All slot `(x, y)` coordinates below are **Minecraft `Slot(container, index, x, y)` item top-left coordinates** (the 18×18 recessed slot frame baked into the PNG sits at `(x - 1, y - 1)` through `(x + 16, y + 16)`).

---

## 1. 2-Zone Inventory Semantics (Identical on Smart Port & Cargo Station)

Both stations provide **54 internal cargo slots** (`LogisticsConstants.STATION_TOTAL_CARGO_SLOTS = 54`) split cleanly into two equal 27-slot (`3 × 9`) zones:

1. **Outbound Area / 待发货区 (`0..26`, `STATION_OUTBOUND_SLOTS = 27`, left side, warm amber accent)**
   - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **insert ONLY** into `0..26`.
   - MHERA `pulling_upgrade` / `advanced_pulling_upgrade` **pulls ONLY** into `0..26`.
   - Outgoing Electric Smart Cargo Boats and Transport / Heavy Drones **load ONLY** from `0..26`.
2. **Storage Area / 存储区 (`27..53`, `STATION_STORAGE_SLOTS = 27`, right side, cool cyan accent)**
   - Incoming cargo unloaded from arriving boats and drones goes into `27..53` (and adjacent receiving containers).
   - External pipes, hoppers, and adjacent automation (`IItemHandler` / `WorldlyContainer`) **extract ONLY** from `27..53`.
   - MHERA `ejector_upgrade` / `advanced_ejector_upgrade` **ejects ONLY** from `27..53`.
3. **Center Swap Button (`⇄`) at `(172, 104, 16, 20)`**
   - Atomically swaps every slot pair `i <-> 27 + i` for `i ∈ 0..26` server-side, marks the station dirty, and reports `status.trera.swapped`.
   - Renders the crisp baked 12×10 dual-color swap arrows sprite from UV `(414, 64, 12, 10)` centered on the button at `(leftPos + 174, topPos + 109)` (zero Unicode glyph box issues across 1.19.2..26.2). Uses `gui.trera.swap` (`"<->"`) as ASCII fallback, `gui.trera.swap.title` as narration title, and `tooltip.trera.swap` on hover.

---

## 2. Shared Regions (Identical across `smart_port.png` & `cargo_station.png`)

### Top Control Bar (`y = 0..55`, spanning `x = 8..350`)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Main Panel | `(0, 0, 392, 234)` | `imageWidth = 392`, `imageHeight = 234` |
| Title Label | `titleLabelX = 8`, `titleLabelY = 4` | Rendered in dark charcoal `#2C3440` (or `#404040`); updates live on rename |
| Row 1: Station Name `EditBox` | `(8, 13, 150, 12)` | Custom station name text input (`gui.trera.station_name`) |
| Row 1: Save `Button` | `(162, 13, 44, 12)` | `gui.trera.save` |
| Row 1: Auto-Return `Button` | `(210, 13, 66, 12)` | `gui.trera.auto_return.on` / `gui.trera.auto_return.off` |
| Row 1: Energy Priority `Button` | `(279, 13, 68, 12)` | `gui.trera.priority.eu_first` / `gui.trera.priority.battery_first` |
| Row 2: Prev Target `Button` (`<`) | `(8, 27, 14, 12)` | Cycles same-dimension target stations backwards |
| Row 2: Target Readout LCD | `(23, 27, 256, 12)` | Baked dark LCD; draw target name + distance/status text at `(26, 29)`, `maxWidth = 250` |
| Row 2: Next Target `Button` (`>`) | `(280, 27, 14, 12)` | Cycles same-dimension target stations forwards |
| Row 2: Launch `Button` | `(297, 27, 50, 12)` | `gui.trera.dispatch` |
| Row 3: Manual `X` `EditBox` | `(8, 41, 44, 12)` | Target X coordinate |
| Row 3: Manual `Y` `EditBox` | `(54, 41, 36, 12)` | Target Y coordinate |
| Row 3: Manual `Z` `EditBox` | `(92, 41, 44, 12)` | Target Z coordinate |
| Row 3: Set Coords `Button` | `(138, 41, 36, 12)` | `gui.trera.set_target` |
| Row 3: Status Readout LCD | `(176, 41, 171, 12)` | Baked dark LCD; draw live status / `DispatchFailure` text at `(179, 43)`, `maxWidth = 165` |

### Remote Mode Header Additions (only when opened via Operation Terminal)

When a Smart Port or Cargo Station is opened remotely (`menu.isRemoteMode() == true`), all machine-side slots (`0..58` on Smart Port; `0..65` on Cargo Station) are strictly read-only on both server and client, while all Top Control Bar controls and the Center Swap Button (`⇄`) remain active. Two header elements appear in `y = 1..12`:

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Remote Read-Only Indicator Pill | Dynamic right of title, strictly `x + w <= 275`, `y = 1, h = 11` (NeoForge) / `y = 2, h = 10` (Forge 1.19.2) | Displays `gui.trera.remote_readonly` in cyan on a dark pill (`0xFF142D3E` fill / `0xFF38A8CC` outline + `10×10` eye icon from `operation_terminal.png` UV `(370, 44, 10, 10)` on NeoForge; `0xFF1A2D40` fill / `0xFF38D9FF` outline on Forge 1.19.2). Hover displays `gui.trera.remote_readonly.tooltip`. |
| `"Back"` / `"返回列表"` `Button` | `(279, 1, 68, 11)` | `gui.trera.back_to_terminal`; aligned directly above Energy Priority `(279, 13, 68, 12)` and added as Button 8 (preserving local button indices `0..7`). Clicking sends `RETURN_TO_LIST` to reopen the Operation Terminal Selection Screen. |

### 2-Zone Cargo Section (`y = 77..141`, IDENTICAL on both machines)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Outbound Header Label | `(8, 77)` | Draw `gui.trera.zone.outbound` in warm dark-amber `#7A4200` (or `#404040`) |
| Outbound Accent Bar | `(7, 85, 162, 1)` | Baked warm amber bar directly atop Outbound 3×9 recess (`7..168, 86..139`) |
| **Outbound Slots `0..26` (3×9)** | `x = 8 + col * 18`, `y = 87 + row * 18` | `col ∈ 0..8` (`8..152`), `row ∈ 0..2` (`87, 105, 123`); subtle amber-tinted inner recess border |
| **Center Swap `Button` (`⇄`)** | `(172, 104, 16, 20)` | Centered in channel `x = 170..188`; blit 12×10 sprite at `(174, 109)` from UV `(414, 64, 12, 10)` |
| Storage Header Label | `(190, 77)` | Draw `gui.trera.zone.storage` in cool dark-teal `#004D66` (or `#404040`) |
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
| EU Gauge Frame | `(362, 13, 18, 42)` | Baked into background | Dark recessed gauge with 10% scale ticks; tooltip `gui.trera.eu_stored` |
| EU Gauge Fill (bottom-up) | `(363, 14, 16, 40)` | `(396, 0, 16, 40)` | Let `h = round(40 * eu / maxEu)`; blit `(363, 14 + 40 - h)` from `(396, 40 - h)` size `(16, h)` |
| EU Bolt Indicator | `(366, 57, 10, 14)` | `(414, 0, 10, 14)` | Unlit bolt baked; blit lit sprite when `eu > 0` |
| Single-Use Battery Slot (1) | Slot `(363, 76)` (frame `362..379, 75..92`) | Battery watermark baked | Accepts only `mhera:single_use_battery` (max 64) |
| Machine Upgrade Slots (4) | Slots `(363, 102)`, `(363, 120)`, `(363, 138)`, `(363, 156)` | Upgrade watermark baked | Accepts supported MHERA upgrades + `trera:chunk_loading_upgrade` |
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

## 3. Smart Port Specifics (`assets/trera/textures/gui/smart_port.png`)

- **Middle Deck Harbor Telemetry LCD Strip**:
  - Baked dark LCD at `(7, 56, 340, 18)` with subtle 2px maritime cobalt/cyan left accent (`x = 8..9`).
  - Render docked boat status & waterway berth info at `(12, 61)` using `gui.trera.berth_docked` / `gui.trera.berth_empty` and `gui.trera.water_ok` / `gui.trera.water_blocked`.
- **Canonical Menu Slot Index Contract** (`59` machine slots + `36` player slots = **`95` total**):
  - `0..26`: **Outbound Area** (`3×9` at `x = 8 + col * 18`, `y = 87 + row * 18`)
  - `27..53`: **Storage Area** (`3×9` at `x = 190 + col * 18`, `y = 87 + row * 18`)
  - `54`: Single-Use Battery Slot `(363, 76)`
  - `55..58`: 4 Machine Upgrade Slots `(363, 102)`, `(363, 120)`, `(363, 138)`, `(363, 156)`
  - `59..85`: Player Inventory (`3×9` at `x = 99 + col * 18`, `y = 153 + row * 18`)
  - `86..94`: Player Hotbar (`1×9` at `x = 99 + col * 18`, `y = 211`)

---

## 4. Cargo Station Specifics (`assets/trera/textures/gui/cargo_station.png`)

- **Middle Deck Strip (`y = 56..73`)**:
  - **RF Chip Slot (1 slot)**: Slot `(8, 57)` (frame `7..24, 56..73`) — recessed with cyan top highlight & antenna watermark. Accepts `trera:rf_chip_1`, `trera:rf_chip_2`, `trera:rf_chip_3`.
  - **RF Range Mini-LCD**: Baked dark LCD at `(26, 56, 42, 18)` — render current RF range string (`"256m"`, `"512m"`, `"1024m"`, or `gui.trera.rf_none`) centered around `(29, 61)`.
  - **Drone Bay Slots (6 slots, 1×6)**: `x = 74 + bay * 18`, `y = 57` (`bay ∈ 0..5`: `74, 92, 110, 128, 146, 164`; frames `73..180, 56..73`) — each slot has a subtle drone-pad watermark. Accepts `trera:transport_drone` and `trera:heavy_transport_drone` (max stack 1).
  - **Flight Deck Status LCD**: Baked dark LCD at `(186, 56, 161, 18)` — render ready drone bay count (`gui.trera.flight_deck_ready`) & RF range status (`gui.trera.in_range` / `gui.trera.out_of_range`) at `(190, 61)`.
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

## 5. Electric Smart Cargo Boat Menu (`trera:electric_cargo_boat`)

The Electric Smart Cargo Boat has a **54-slot** cargo hold (`6 rows × 9 cols`), matching vanilla Minecraft's standard `ChestMenu.sixRows` (`minecraft:textures/gui/container/generic_54.png`, `176×222`). No custom GUI PNG is required for the boat hold.

---

## 6. Operation Terminal Selection Screen (`assets/trera/textures/gui/operation_terminal.png`)

The Operation Terminal Selection Screen is a client-only `Screen` (no container slots; synced via `TerminalSyncPayload` / `S2CTerminalSyncPacket`) using a **`512 × 256`** RGBA PNG atlas with the main beveled MHERA-style panel occupying `(0, 0)` to `(319, 223)` (**`imageWidth = 320`**, **`imageHeight = 224`**). Dynamic sprites live in the right margin (`u = 328..452`). Every `blit` call MUST explicitly pass **`textureWidth = 512, textureHeight = 256`**.

### Panel Regions (`320 × 224`)

| Region / Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Main Panel | `(0, 0, 320, 224)` | `imageWidth = 320`, `imageHeight = 224` |
| Header Title (`y = 0..16`) | `(8, 5)`, `maxWidth = 174` | `gui.trera.terminal.title` in dark charcoal `#2C3440` (or `#404040`) |
| Header Terminal EU/FE Mini-Bar Frame | `(186, 4, 126, 11)` | Baked dark recessed LCD frame; hover tooltip `gui.trera.terminal.energy` (`EU` + `FE`) |
| Header Terminal EU/FE Gauge Fill | `(187, 5, 124, 9)` | Left-to-right fill `fillW = round(124 * eu / maxEu)` blitted from UV `(328, 30, fillW, 9)`; centered `gui.trera.terminal.energy_short` text at `y = 6`, `maxWidth = 120`, `0xFFEAFBFF` |
| Toolbar Search `EditBox` (`y = 19..36`) | `(8, 20, 236, 14)` | Dark recessed well; placeholder `gui.trera.terminal.search_placeholder`; live-filters by name, type, or coordinates |
| Toolbar Refresh `Button` | `(248, 20, 64, 14)` | `gui.trera.terminal.refresh`; sends `REFRESH` to re-probe bound stations |
| Scrollable Station List Viewport | `(8, 38, 296, 166)` | Interior `(9..302, 39..202)`; displays `gui.trera.terminal.empty` or `gui.trera.terminal.no_matches` centered (`maxLineW = 280`, `0xFF8EA4BA`) when no rows match |
| Visible Row Cards (`6` rows, `i = 0..5`) | `(10, 40 + i * 27, 292, 25)` | `ROW_X = 10`, `ROW_Y_START = 40`, `ROW_STEP = 27`, `ROW_W = 292`, `ROW_H = 25` (2px vertical gap) |
| Vertical Scrollbar Track | `(306, 38, 6, 166)` | Supports mouse-wheel scrolling over `(8..314, 38..204)` and click/drag on track |
| Vertical Scrollbar Thumb | `(306, thumbY, 6, 24)` | `thumbY` travels `38..180` (`142px` travel); blitted from UV `(344, 44, 6, 24)` |
| Footer Status LCD | `(8, 207, 304, 12)` | Baked dark LCD; draw `gui.trera.terminal.footer` at `(12, 209)`, `maxWidth = 296`, cyan `0xFF4CE8FF` |

### Row Card Styling & Sub-Layout (Relative to Row Top-Left `(rx, ry)`)

- **Row Card Fill & Border ARGB**:
  - **Online (`status.isConnectable()`)**: normal `0xFF1A2532`, hover `0xFF233648`, 1px outline `0xFF2E4960`
  - **Offline / Disabled**: normal `0xFF171D26`, hover `0xFF1F2733`, 1px outline `0xFF2B3442`

| Sub-Element | Relative Rect `(x, y, w, h)` | Notes |
|---|---|---|
| Station Type Badge (`14 × 14`) | `(rx + 4, ry + 5, 14, 14)` | Smart Port `(328, 0)`, Cargo Station `(344, 0)`, or Comm Base Station `(360, 0)` |
| Status LED (`8 × 8`) | `(rx + 22, ry + 8, 8, 8)` | Online green `(328, 18)`, Unpowered amber `(338, 18)`, Offline/Unloaded/No-Station/Other-Dim gray-blue `(348, 18)`, Missing/Error red `(358, 18)` |
| Line 1 (Title + Distance) | `(rx + 34, ry + 3)`, `maxWidth = 184` | Station name (or fallback unnamed title) + `[<dist>m]`; `0xFFEAF6FF` online / `0xFFAAB8C8` offline |
| Line 2 (Coordinates `·` Status) | `(rx + 34, ry + 14)`, `maxWidth = 184` | `(x, y, z) · <status>`; green `0xFF66FF99` online, amber `0xFFFFC845` unpowered, red `0xFFFF6E6E` missing, muted `0xFF8EA4BA` otherwise |
| Connect `Button` | `(rx + 222, ry + 5, 48, 15)` | `gui.trera.terminal.connect`; active only when `status.isConnectable()`, sends `CONNECT` |
| Unbind `Button` | `(rx + 274, ry + 5, 14, 15)` | `gui.trera.terminal.unbind`; hover tooltip `gui.trera.terminal.unbind_tooltip`, sends `UNBIND`; `10 × 10` X icon centered at `(rx + 276, ry + 7)` from UV `(328, 44)` / hover `(328, 56)` |

### Right-Margin Dynamic Sprite Atlas Summary (`u = 328..452`, `512 × 256`)

| Sprite | UV `(u, v, w, h)` | Usage |
|---|---|---|
| Smart Port Badge | `(328, 0, 14, 14)` | Row type icon at `(rx + 4, ry + 5)` for Smart Ports |
| Cargo Station Badge | `(344, 0, 14, 14)` | Row type icon at `(rx + 4, ry + 5)` for Cargo Stations |
| Comm Base Station Badge | `(360, 0, 14, 14)` | Row type icon at `(rx + 4, ry + 5)` for unattached / status-only base stations |
| Status LED — Online (Green) | `(328, 18, 8, 8)` | Row LED at `(rx + 22, ry + 8)` when `CommStationStatus.ONLINE` |
| Status LED — Unpowered (Amber) | `(338, 18, 8, 8)` | Row LED at `(rx + 22, ry + 8)` when `CommStationStatus.UNPOWERED` |
| Status LED — Offline / Unloaded (Gray-Blue) | `(348, 18, 8, 8)` | Row LED at `(rx + 22, ry + 8)` for `NO_ADJACENT_STATION`, `CHUNK_UNLOADED`, `WRONG_DIMENSION` |
| Status LED — Missing / Error (Red) | `(358, 18, 8, 8)` | Row LED at `(rx + 22, ry + 8)` when `CommStationStatus.COMM_MISSING` |
| Terminal EU/FE Gauge Fill | `(328, 30, 124, 9)` | Left-to-right header energy fill at `(187, 5)` |
| Unbind `×` Icon (Normal) | `(328, 44, 10, 10)` | Centered on Unbind button at `(rx + 276, ry + 7)` |
| Unbind `×` Icon (Hover) | `(328, 56, 10, 10)` | Centered on Unbind button when hovered |
| Scrollbar Thumb | `(344, 44, 6, 24)` | Vertical scrollbar thumb at `(306, thumbY)` (`thumbY ∈ 38..180`) |
| Connect Arrow Icon (Optional Bonus) | `(358, 44, 10, 10)` | Optional 10×10 connect arrow glyph |
| Remote Read-Only Eye Icon | `(370, 44, 10, 10)` | Optional/shared 10×10 read-only eye icon used in the Remote Mode header pill |
