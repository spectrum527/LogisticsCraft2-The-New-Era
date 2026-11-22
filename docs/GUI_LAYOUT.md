# LogisticsCraft² (`lc2`) — GUI Pixel Layout Specification

Both machine screens use a **256×256** RGBA PNG atlas with the main beveled IC2-style panel occupying `(0, 0)` to `(211, 221)` (**`imageWidth = 212`**, **`imageHeight = 222`**). Dynamic overlay sprites live in the right margin (`u = 216..255`).

All slot `(x, y)` coordinates below are **Minecraft `Slot(container, index, x, y)` item top-left coordinates** (the 18×18 recessed slot frame baked into the PNG sits at `(x - 1, y - 1)` through `(x + 16, y + 16)`).

---

## 1. Shared Regions (Identical across `smart_port.png` & `cargo_station.png`)

| Element | Coordinates `(x, y, w, h)` | Notes |
|---|---|---|
| Main Panel | `(0, 0, 212, 222)` | `imageWidth = 212`, `imageHeight = 222` |
| Title Label | `titleLabelX = 8`, `titleLabelY = 5` | Rendered in dark charcoal `#2C3440` (or `#404040`) |
| Station Name `EditBox` | `(8, 15, 112, 12)` | Custom station name text input |
| Save Name `Button` | `(122, 15, 48, 12)` | `gui.lc2.save` |
| Prev Target `Button` (`<`) | `(8, 28, 14, 12)` | Cycles same-dimension target stations backwards |
| Target Readout LCD | `(23, 28, 132, 12)` | Dark LCD panel baked into PNG; render target name + distance/status text at `(26, 30)` |
| Next Target `Button` (`>`) | `(156, 28, 14, 12)` | Cycles same-dimension target stations forwards |
| Manual `X` `EditBox` | `(8, 41, 38, 12)` | Target X coordinate |
| Manual `Y` `EditBox` | `(48, 41, 32, 12)` | Target Y coordinate |
| Manual `Z` `EditBox` | `(82, 41, 38, 12)` | Target Z coordinate |
| Apply Coords `Button` | `(122, 41, 48, 12)` | `gui.lc2.set_target` |
| Auto-Return Toggle `Button` | `(8, 54, 54, 12)` | `gui.lc2.auto_return.on` / `gui.lc2.auto_return.off` |
| Energy Priority Toggle `Button` | `(64, 54, 56, 12)` | `gui.lc2.priority.eu_first` / `gui.lc2.priority.battery_first` |
| Manual Dispatch `Button` | `(122, 54, 48, 12)` | `gui.lc2.dispatch` |
| Status Readout LCD | `(7, 67, 164, 10)` | Dark LCD strip baked into PNG; render live status / `DispatchFailure` message at `(10, 68)` |
| Inventory Label | `inventoryLabelX = 8`, `inventoryLabelY = 133` | Standard player inventory label |
| Player Inventory (27 slots, 3×9) | `x = 8 + col * 18`, `y = 142 + row * 18` | `col ∈ 0..8`, `row ∈ 0..2` |
| Player Hotbar (9 slots, 1×9) | `x = 8 + col * 18`, `y = 199` | `col ∈ 0..8` |

### Shared Right Power & Upgrade Column (`x = 176..206`)

| Element | Screen Rect `(x, y, w, h)` | Dynamic Sprite UV `(u, v, w, h)` | Notes |
|---|---|---|---|
| EU Gauge Frame | `(185, 16, 18, 42)` | Baked into background | Dark recessed gauge with 10 tick marks; tooltip shows `eu / maxEu EU` |
| EU Gauge Fill (bottom-up) | `(186, 17, 16, 40)` | `(216, 0, 16, 40)` | Let `h = round(40 * eu / maxEu)`; blit `(186, 17 + 40 - h)` from `(216, 40 - h)` size `(16, h)` |
| EU Bolt Indicator | `(189, 60, 10, 14)` | `(234, 0, 10, 14)` | Unlit bolt baked; blit lit sprite when `eu > 0` (or scaled bottom-up) |
| Single-Use Battery Slot (1) | Slot `(186, 79)` (frame `185..202, 78..95`) | Watermark baked | Accepts only `ic2:single_use_battery` (up to 64) |
| Machine Upgrade Slots (4) | Slot `(186, 106)`, `(186, 124)`, `(186, 142)`, `(186, 160)` | Upgrade watermark baked | Accepts supported IC2 upgrades + `lc2:chunk_loading_upgrade` |
| Chunk-Loader Active Badge | `(186, 180, 16, 16)` | `(216, 64, 16, 16)` | Optional: blit when `chunk_loading_upgrade` is actively keeping 3×3 chunks loaded |
| Vehicle / Port Status Badge | `(186, 199, 16, 16)` | Active `(216, 44, 16, 16)` / Warn `(234, 44, 16, 16)` | Optional: blit docked/ready cyan icon or amber warning icon |

---

## 2. Smart Port Specifics (`assets/lc2/textures/gui/smart_port.png`)

- **Cargo Buffer (27 slots, 3×9)**:
  - `x = 8 + col * 18` (`col ∈ 0..8`: `8, 26, 44, 62, 80, 98, 116, 134, 152`)
  - `y = 79 + row * 18` (`row ∈ 0..2`: `79, 97, 115`)
- **Recommended Menu Slot Index Order** (32 machine slots + 36 player slots = 68 total):
  - `0..26`: Cargo Buffer (3×9)
  - `27`: Single-Use Battery Slot `(186, 79)`
  - `28..31`: Machine Upgrade Slots `(186, 106..160)`
  - `32..58`: Player Inventory (3×9)
  - `59..67`: Player Hotbar (1×9)

---

## 3. Cargo Station Specifics (`assets/lc2/textures/gui/cargo_station.png`)

- **RF Chip Slot (1 slot)**:
  - Slot `(8, 79)` (frame `7..24, 78..95`) — recessed with cyan/gold trim & antenna watermark. Accepts `lc2:rf_chip_1`, `lc2:rf_chip_2`, `lc2:rf_chip_3`.
- **RF Range Mini-LCD**:
  - Baked dark LCD box at `(26, 78, 34, 18)` (flush with adjacent slot frames) — render current RF range string (`"256m"`, `"512m"`, `"1024m"`, or `"---"`) centered around `(29, 83)`.
- **Drone Bay Slots (6 slots, 1×6)**:
  - `x = 62 + bay * 18` (`bay ∈ 0..5`: `62, 80, 98, 116, 134, 152`)
  - `y = 79` (frames `61..168, 78..95`) — each slot has a subtle miniature helipad/drone watermark. Accepts `lc2:transport_drone` and `lc2:heavy_transport_drone` (max stack 1).
- **Cargo Buffer (18 slots, 2×9)**:
  - `x = 8 + col * 18` (`col ∈ 0..8`: `8, 26, 44, 62, 80, 98, 116, 134, 152`)
  - `y = 97 + row * 18` (`row ∈ 0..1`: `97, 115`)
- **Recommended Menu Slot Index Order** (30 machine slots + 36 player slots = 66 total):
  - `0..17`: Cargo Buffer (2×9)
  - `18`: RF Chip Slot `(8, 79)`
  - `19..24`: Drone Bay Slots 0..5 `(62..152, 79)`
  - `25`: Single-Use Battery Slot `(186, 79)`
  - `26..29`: Machine Upgrade Slots `(186, 106..160)`
  - `30..56`: Player Inventory (3×9)
  - `57..65`: Player Hotbar (1×9)

---

## 4. Electric Smart Cargo Boat Menu (`lc2:electric_cargo_boat`)

The Electric Smart Cargo Boat has a **54-slot** cargo hold (`6 rows × 9 cols`), matching vanilla Minecraft's standard `ChestMenu.sixRows` (`minecraft:textures/gui/container/generic_54.png`, `176×222`). No custom GUI PNG is required for the boat hold.
