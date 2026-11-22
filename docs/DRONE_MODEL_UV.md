# LogisticsCraft² (`lc2`) — Entity Model & UV Specification

This document defines the exact texture dimensions and UV coordinates for both LC2 entities so NeoForge (1.21.1 / 26.1.2 / 26.2) and Forge (1.19.2) render pixel-identically.

---

## 1. Electric Smart Cargo Boat (`lc2:electric_cargo_boat`)

- **Texture**: `assets/lc2/textures/entity/boat/electric_cargo_boat.png`
- **Dimensions**: **128 × 128** RGBA PNG
- **Model**: Standard vanilla Minecraft **`ChestBoatModel`** (`LayerDefinition` texture size `128 × 128` across 1.19.2, 1.21.1, 26.1.2, and 26.2!).
  - Top region (`y = 0..50`): Exact standard `BoatModel` UV boxes (`bottom`, `back`, `front`, `right`, `left`, `left_paddle`, `right_paddle`), styled after IC2's metallic steel hull + electric cobalt-blue & cyan trim + carbon paddles.
  - Bottom region (`y = 59..95`): Exact standard `ChestBoatModel` chest UV boxes:
    - `chest_lock`: `texOffs(0, 59)`, box `(2, 4, 1)` → `x = 0..5, y = 59..63` (polished steel latch with cyan smart-lock LED)
    - `chest_lid`: `texOffs(0, 59)`, box `(12, 5, 12)` → top `x = 12..23, y = 59..70`, bottom `x = 24..35, y = 59..70`, sides `x = 0..47, y = 71..75`
    - `chest_bottom`: `texOffs(0, 76)`, box `(12, 8, 12)` → top `x = 12..23, y = 76..87`, bottom `x = 24..35, y = 76..87`, sides `x = 0..47, y = 88..95`
  - **Important**: Always bind `ChestBoatModel` (128×128), NOT `BoatModel` (128×64), so both the electric hull and mounted oak/steel cargo chest render cleanly with zero UV distortion.

---

## 2. Cargo Drone (`lc2:cargo_drone`) — Custom `CargoDroneModel`

- **Textures**:
  - `STANDARD` variant: `assets/lc2/textures/entity/drone/transport_drone.png` (**64 × 64** RGBA)
  - `HEAVY` variant: `assets/lc2/textures/entity/drone/heavy_transport_drone.png` (**64 × 64** RGBA)
- **LayerDefinition**: `LayerDefinition.create(meshDefinition, 64, 64)`
- **Coordinate System**: Standard Minecraft `EntityModel` space (`-Z` = forward nose, `+Y` = downward, model root pivoted at `PartPose.offset(0.0F, 17.0F, 0.0F)` so landing skids touch ground at `y = 24.0F`).

### Complete Part & UV Table (Zero Overlapping UVs)

All boxes below are children of a `root` part at `PartPose.offset(0.0F, 17.0F, 0.0F)`.

| Part Name | `texOffs(u, v)` | `addBox(x, y, z, w, h, d)` | `PartPose` | UV Footprint `[x0..x1, y0..y1]` | Notes |
|---|---|---|---|---|---|
| `body` | `(0, 0)` | `(-5.0F, -2.0F, -5.0F, 10, 4, 10)` | `ZERO` | `[0..39, 0..13]` | Central steel/armored chassis (`-Z` front chevron) |
| `nose_sensor` | `(40, 0)` | `(-2.0F, -1.0F, -7.0F, 4, 2, 2)` | `ZERO` | `[40..51, 0..3]` | Front optical camera / RF sensor pod |
| `antenna` | `(58, 0)` | `(-0.5F, -6.0F, 3.0F, 1, 4, 1)` | `ZERO` | `[58..61, 0..4]` | Rear top vertical RF whip mast |
| `arm_fl` | `(40, 4)` | `(-1.0F, -0.5F, -7.5F, 2, 1, 6)` | `rotation(0, +0.785398F, 0)` | `[40..55, 4..10]` | Front-left diagonal boom (`+45°` Y) |
| `arm_fr` | `(40, 4)` | `(-1.0F, -0.5F, -7.5F, 2, 1, 6)` | `rotation(0, -0.785398F, 0)` | `[40..55, 4..10]` | Front-right diagonal boom (`-45°` Y) |
| `arm_bl` | `(40, 4)` | `(-1.0F, -0.5F, -7.5F, 2, 1, 6)` | `rotation(0, +2.356194F, 0)` | `[40..55, 4..10]` | Back-left diagonal boom (`+135°` Y) |
| `arm_br` | `(40, 4)` | `(-1.0F, -0.5F, -7.5F, 2, 1, 6)` | `rotation(0, -2.356194F, 0)` | `[40..55, 4..10]` | Back-right diagonal boom (`-135°` Y) |
| `motor_fl` | `(32, 14)` | `(-1.0F, -1.5F, -1.0F, 2, 3, 2)` | `offset(-5.5F, -0.5F, -5.5F)` | `[32..39, 14..18]` | Front-left rotor nacelle |
| `motor_fr` | `(32, 14)` | `(-1.0F, -1.5F, -1.0F, 2, 3, 2)` | `offset(+5.5F, -0.5F, -5.5F)` | `[32..39, 14..18]` | Front-right rotor nacelle |
| `motor_bl` | `(32, 14)` | `(-1.0F, -1.5F, -1.0F, 2, 3, 2)` | `offset(-5.5F, -0.5F, +5.5F)` | `[32..39, 14..18]` | Back-left rotor nacelle |
| `motor_br` | `(32, 14)` | `(-1.0F, -1.5F, -1.0F, 2, 3, 2)` | `offset(+5.5F, -0.5F, +5.5F)` | `[32..39, 14..18]` | Back-right rotor nacelle |
| `rotor_fl` | `(40, 14)` & `(40, 16)` | Blade X `(-4, -0.5F, -0.5F, 8, 1, 1)` + Blade Z `(-0.5F, -0.5F, -4, 1, 1, 8)` | `offset(-5.5F, -2.5F, -5.5F)` | `[40..57, 14..15]` & `[40..57, 16..24]` | Animated: `yRot = +spin` |
| `rotor_fr` | `(40, 14)` & `(40, 16)` | Same crossed blades | `offset(+5.5F, -2.5F, -5.5F)` | Same | Animated: `yRot = -spin` |
| `rotor_bl` | `(40, 14)` & `(40, 16)` | Same crossed blades | `offset(-5.5F, -2.5F, +5.5F)` | Same | Animated: `yRot = -spin` |
| `rotor_br` | `(40, 14)` & `(40, 16)` | Same crossed blades | `offset(+5.5F, -2.5F, +5.5F)` | Same | Animated: `yRot = +spin` |
| `cargo_bay` | `(0, 14)` | `(-4.0F, 2.0F, -4.0F, 8, 5, 8)` | `ZERO` | `[0..31, 14..26]` | Central under-slung cargo crate |
| `skid_left` | `(0, 27)` | `(-5.0F, 6.0F, -5.0F, 1, 1, 10)` | `ZERO` | `[0..21, 27..37]` | Left landing skid |
| `skid_right` | `(0, 27)` | `(4.0F, 6.0F, -5.0F, 1, 1, 10)` | `ZERO` | `[0..21, 27..37]` | Right landing skid |
| `side_pod_left` | `(22, 27)` | `(-7.0F, 2.0F, -4.0F, 3, 4, 8)` | `ZERO` | `[22..43, 27..38]` | **Visible only when `HEAVY` variant** (`side_pod_left.visible = isHeavy`) |
| `side_pod_right` | `(22, 27)` | `(4.0F, 2.0F, -4.0F, 3, 4, 8)` | `ZERO` | `[22..43, 27..38]` | **Visible only when `HEAVY` variant** (`side_pod_right.visible = isHeavy`) |

### Animation Guidance
- In `setupAnim` (or 26.x `RenderState`):
  - Spin rotors continuously while airborne (`spin = ageInTicks * 0.9F`), slow/idle when parked (`ageInTicks * 0.05F` or `0.0F`).
  - Toggle `side_pod_left.visible = isHeavy` and `side_pod_right.visible = isHeavy`.
  - Optional: scale `HEAVY` variant by `1.15F` in renderer pose stack for an even more commanding heavy-lift silhouette!

---

## 3. Blockstate Property Contract

- `lc2:smart_port`:
  - Properties: `facing` (`north`, `east`, `south`, `west`), `active` (`false`, `true`).
  - Uses standard `variants` blockstate with `y` rotation (`north=0`, `east=90`, `south=180`, `west=270`).
- `lc2:cargo_station`:
  - Property: `active` (`false`, `true`).
  - Uses `multipart` blockstate matching `when: {"active": "false"}` / `when: {"active": "true"}` — works identically whether `CargoStationBlock` declares only `active` or also includes `facing`.
