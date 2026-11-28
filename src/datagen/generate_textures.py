#!/usr/bin/env python3
"""Reproducible pixel-art texture generator for Transitera (trera).

Generates all original CC BY-SA 4.0 textures under assets/trera/textures/:
  - 16 block textures (16x16): Smart Port (5), Cargo Station (5), Comm Base Station (6)
  - 8 item textures (16x16): Boat, Drones (2), RF Chips (3), Chunk Loader, Operation Terminal
  - 3 entity textures (128x128 ChestBoat UV + two 64x64 CargoDrone UVs)
  - 3 GUI textures (512x256 atlases: 392x234 2-zone machine panels + 320x224 Operation Terminal)

Optionally writes a visual contact sheet via --preview <path>, a 2x GUI-only preview via --gui-preview <path>,
or a focused v1.2.0 asset sheet via --v120-preview <path>.
"""

import argparse
from pathlib import Path
from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
TEX_ROOT = REPO_ROOT / "assets" / "trera" / "textures"


def rgba(hex_color: str, alpha: int = 255) -> tuple[int, int, int, int]:
    h = hex_color.lstrip("#")
    if len(h) == 8:
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16))
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


def render_grid(grid: list[str], palette: dict[str, tuple[int, int, int, int]]) -> Image.Image:
    h = len(grid)
    w = len(grid[0])
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(grid):
        assert len(row) == w, f"Row {y} width {len(row)} != {w}"
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = palette[ch]
    return im


def fill_rect(px, x0: int, y0: int, x1: int, y1: int, col):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = col


def hline(px, x0: int, x1: int, y: int, col):
    for x in range(x0, x1 + 1):
        px[x, y] = col


def vline(px, x: int, y0: int, y1: int, col):
    for y in range(y0, y1 + 1):
        px[x, y] = col


def draw_bevel_box(px, x0: int, y0: int, x1: int, y1: int, fill, tl, br, border=None):
    if border is not None:
        fill_rect(px, x0, y0, x1, y1, border)
        x0, y0, x1, y1 = x0 + 1, y0 + 1, x1 - 1, y1 - 1
    fill_rect(px, x0, y0, x1, y1, fill)
    hline(px, x0, x1, y0, tl)
    vline(px, x0, y0, y1, tl)
    hline(px, x0, x1, y1, br)
    vline(px, x1, y0, y1, br)
    px[x1, y0] = fill
    px[x0, y1] = fill


def draw_ic2_casing(dark: bool = False) -> Image.Image:
    """Create a crisp 16x16 IC2-inspired industrial steel machine block face."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = im.load()
    if dark:
        c_out = rgba("#141920")
        c_hi = rgba("#92A4B9")
        c_mid = rgba("#5F7086")
        c_base = rgba("#475567")
        c_sh = rgba("#2C3644")
        c_deep = rgba("#1E2530")
        c_spec = rgba("#B8C8DC")
    else:
        c_out = rgba("#151A22")
        c_hi = rgba("#A8B9CE")
        c_mid = rgba("#78899F")
        c_base = rgba("#5D6E83")
        c_sh = rgba("#3A4656")
        c_deep = rgba("#242C37")
        c_spec = rgba("#CDDCEF")

    # Outer dark border
    fill_rect(px, 0, 0, 15, 15, c_out)
    # Beveled steel rim
    fill_rect(px, 1, 1, 14, 14, c_mid)
    hline(px, 1, 14, 1, c_hi)
    vline(px, 1, 1, 14, c_hi)
    hline(px, 1, 14, 14, c_sh)
    vline(px, 14, 1, 14, c_sh)
    px[1, 1] = c_spec
    px[14, 1] = c_mid
    px[1, 14] = c_mid
    px[14, 14] = c_deep

    # Inner recessed panel
    fill_rect(px, 2, 2, 13, 13, c_base)
    hline(px, 2, 13, 2, c_sh)
    vline(px, 2, 2, 13, c_sh)
    hline(px, 2, 13, 13, c_hi)
    vline(px, 13, 2, 13, c_hi)
    # Subtle brushed steel noise
    for y in range(3, 13):
        for x in range(3, 13):
            if (x * 7 + y * 13) % 11 == 0:
                px[x, y] = c_mid
            elif (x * 5 + y * 3) % 13 == 0:
                px[x, y] = c_sh

    # Corner bolts
    for bx, by in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[bx, by] = c_deep
    px[1, 2] = c_spec
    px[2, 1] = c_spec
    return im


# =============================================================================
# 1. BLOCK TEXTURES (16x16)
# =============================================================================

def make_smart_port_bottom() -> Image.Image:
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#1C232D")
    c_sl = rgba("#303B4A")
    c_lt = rgba("#8899AE")
    c_bl = rgba("#26528A")
    # Recessed dark baseplate
    fill_rect(px, 3, 3, 12, 12, c_sl)
    hline(px, 3, 12, 3, c_dk)
    vline(px, 3, 3, 12, c_dk)
    # Industrial cooling/drain slats
    for y in (5, 7, 9, 11):
        hline(px, 5, 10, y, c_dk)
        hline(px, 5, 10, y - 1 if y > 5 else 4, c_sl)
    # Corner rivets & maritime bolt ring
    for rx, ry in ((4, 4), (11, 4), (4, 11), (11, 11)):
        px[rx, ry] = c_lt
    px[7, 7] = c_bl
    px[8, 7] = c_bl
    px[7, 8] = c_bl
    px[8, 8] = c_bl
    return im


def make_smart_port_top() -> Image.Image:
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#1A222C")
    c_sl = rgba("#2B3544")
    c_hi = rgba("#9FB2C8")
    c_blue_d = rgba("#1B4782")
    c_blue = rgba("#2D6EC4")
    c_cyan = rgba("#4CD8F5")
    c_yel = rgba("#EBB926")

    # Maritime dock edge strip along NORTH (top y=2..3)
    hline(px, 3, 12, 2, c_blue_d)
    hline(px, 3, 12, 3, c_blue)
    px[7, 3] = c_cyan
    px[8, 3] = c_cyan

    # Recessed central directional dock plate
    fill_rect(px, 4, 5, 11, 12, c_sl)
    hline(px, 4, 11, 5, c_dk)
    vline(px, 4, 5, 12, c_dk)
    hline(px, 4, 11, 12, c_hi)
    vline(px, 11, 5, 12, c_hi)

    # Crisp north-pointing dock chevron arrow inside center plate
    arrow = [(7, 6), (8, 6), (6, 7), (7, 7), (8, 7), (9, 7), (5, 8), (6, 8), (9, 8), (10, 8),
             (7, 9), (8, 9), (7, 10), (8, 10), (7, 11), (8, 11)]
    for x, y in arrow:
        px[x, y] = c_blue
    for x, y in ((7, 6), (8, 6), (7, 7), (8, 7)):
        px[x, y] = c_cyan

    # Signal beacon at top-right corner
    px[12, 4] = c_dk
    px[13, 4] = c_yel
    px[12, 12] = c_dk
    px[3, 12] = c_dk
    return im


def make_smart_port_side() -> Image.Image:
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#161C24")
    c_in = rgba("#26303D")
    c_bar = rgba("#4D5D70")
    c_hi = rgba("#9DB0C4")
    c_blue = rgba("#2C6DC2")
    c_yel = rgba("#E8B723")
    c_blk = rgba("#1A2029")

    # Container intake/output hatch (upper center y=3..10)
    fill_rect(px, 3, 3, 12, 10, c_dk)
    fill_rect(px, 4, 4, 11, 9, c_in)
    # Blue maritime trim bar at top of hatch
    hline(px, 4, 11, 4, c_blue)
    # Metallic shutter slats
    for y in (6, 8):
        hline(px, 5, 10, y, c_bar)
        hline(px, 5, 10, y + 1, c_dk)
    # Hatch center handle
    px[7, 7] = c_hi
    px[8, 7] = c_hi

    # Yellow/black industrial safety hazard stripe (y=12..13)
    for x in range(2, 14):
        px[x, 12] = c_yel if ((x) % 4 < 2) else c_blk
        px[x, 13] = c_yel if ((x + 1) % 4 < 2) else c_blk
    hline(px, 2, 13, 11, c_dk)
    return im


def make_smart_port_front(active: bool) -> Image.Image:
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#131922")
    c_bay_d = rgba("#162B47")
    c_bay_m = rgba("#224673")
    c_bay_l = rgba("#30629C")
    c_bumper = rgba("#333F4F")
    c_bump_hi = rgba("#7E90A6")

    # Top status lamps (x=3..4 and x=11..12, y=2)
    if active:
        lamp_c = rgba("#38EB67")
        lamp_hi = rgba("#C2FFD2")
        glow = rgba("#2DE8FF")
        glow_hi = rgba("#CCFCFF")
        glow_mid = rgba("#1799D6")
    else:
        lamp_c = rgba("#2B3C4D")
        lamp_hi = rgba("#42576B")
        glow = rgba("#3D6287")
        glow_hi = rgba("#5780A8")
        glow_mid = rgba("#264466")

    # Lamp housings
    px[2, 2] = c_dk
    px[3, 2] = lamp_hi
    px[4, 2] = lamp_c
    px[11, 2] = lamp_c
    px[12, 2] = lamp_hi
    px[13, 2] = c_dk

    # Maritime dock bay recessed shutter (x=3..12, y=4..12)
    fill_rect(px, 2, 3, 13, 13, c_dk)
    for y in range(4, 13):
        slat = c_bay_l if (y % 2 == 0) else c_bay_m
        hline(px, 3, 12, y, slat)
        px[3, y] = c_bay_d
        px[12, y] = c_bay_d

    # Illuminated vertical dock-guide side strips when active
    if active:
        for y in (5, 7, 9, 11):
            px[3, y] = glow_mid
            px[12, y] = glow_mid

    # Center anchor / downward dock chevron emblem
    for x, y in ((7, 5), (8, 5), (7, 6), (8, 6), (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7),
                 (6, 8), (7, 8), (8, 8), (9, 8), (7, 9), (8, 9), (5, 10), (10, 10), (6, 11), (7, 11), (8, 11), (9, 11)):
        px[x, y] = glow
    for x, y in ((7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8)):
        px[x, y] = glow_hi

    # Heavy rubber/steel dock bumper along bottom (y=13..14)
    hline(px, 2, 13, 13, c_bumper)
    for x in (4, 7, 8, 11):
        px[x, 13] = c_bump_hi
    return im


def make_cargo_station_bottom() -> Image.Image:
    im = draw_ic2_casing(dark=True)
    px = im.load()
    c_dk = rgba("#131820")
    c_sl = rgba("#242D39")
    c_lt = rgba("#7E90A6")
    c_cu = rgba("#B56D34")

    fill_rect(px, 3, 3, 12, 12, c_sl)
    hline(px, 3, 12, 3, c_dk)
    vline(px, 3, 3, 12, c_dk)
    # Central RF grounding ring
    for x in range(6, 10):
        px[x, 5] = c_lt
        px[x, 10] = c_lt
    for y in range(6, 10):
        px[5, y] = c_lt
        px[10, y] = c_lt
    fill_rect(px, 6, 6, 9, 9, c_dk)
    px[7, 7] = c_cu
    px[8, 8] = c_cu
    px[8, 7] = c_sl
    px[7, 8] = c_sl
    return im


def make_cargo_station_top(active: bool) -> Image.Image:
    im = draw_ic2_casing(dark=True)
    px = im.load()
    c_dk = rgba("#141A22")
    c_deck = rgba("#1E2631")
    c_line = rgba("#303C4C")
    c_pad = rgba("#263140")
    c_amber = rgba("#E5A924")
    if active:
        c_mk = rgba("#27E6FF")
        c_core = rgba("#B8FFFF")
        c_grn = rgba("#39E86A")
    else:
        c_mk = rgba("#3E586B")
        c_core = rgba("#547287")
        c_grn = rgba("#364D42")

    # Dark helipad landing deck inside steel rim (x=2..13, y=2..13)
    fill_rect(px, 2, 2, 13, 13, c_deck)
    # Crisp bay divider grid (3 columns x 2 rows = 6 miniature drone pads!)
    # Cols: x=2..5, x=6..9, x=10..13; Rows: y=2..7, y=8..13
    vline(px, 6, 2, 13, c_line)
    vline(px, 10, 2, 13, c_line)
    hline(px, 2, 13, 7, c_line)
    hline(px, 2, 13, 8, c_dk)

    # 6 miniature helipads (each 3x4 with corner guidance ticks & center landing beacon)
    pad_origins = [
        (2, 2), (6, 2), (10, 2),
        (2, 9), (6, 9), (10, 9),
    ]
    for ox, oy in pad_origins:
        # 4x5 region with 3x4 pad centered
        fill_rect(px, ox + 1, oy + 1, ox + 3, oy + 3, c_pad)
        # Corner markers
        px[ox + 1, oy + 1] = c_mk
        px[ox + 3, oy + 1] = c_mk
        px[ox + 1, oy + 3] = c_grn
        px[ox + 3, oy + 3] = c_grn
        # Center beacon
        px[ox + 2, oy + 2] = c_core

    # Corner helipad amber safety ticks on outer steel frame
    for x, y in ((1, 2), (2, 1), (14, 2), (13, 1), (1, 13), (2, 14), (14, 13), (13, 14)):
        px[x, y] = c_amber
    return im


def make_cargo_station_side(active: bool) -> Image.Image:
    im = draw_ic2_casing(dark=True)
    px = im.load()
    c_dk = rgba("#12171F")
    c_in = rgba("#1E2632")
    c_hi = rgba("#92A5BB")
    c_amb = rgba("#D98526")
    c_amb_d = rgba("#8F4F14")

    # Upper RF transceiver grille (x=3..10, y=3..6)
    fill_rect(px, 3, 3, 10, 6, c_dk)
    for x in range(4, 10):
        px[x, 4] = rgba("#2DE6FF") if (active and x % 2 == 0) else rgba("#3A4B5E")
        px[x, 5] = rgba("#1A7E9E") if (active and x % 2 == 1) else rgba("#26313E")
    if active:
        px[6, 4] = rgba("#C4FFFF")
        px[7, 4] = rgba("#C4FFFF")

    # Right vertical EU / status LED bar (x=12..13, y=3..12)
    fill_rect(px, 11, 3, 13, 12, c_dk)
    if active:
        led_colors = [
            rgba("#3CE862"), rgba("#3CE862"),
            rgba("#2DE6FF"), rgba("#2DE6FF"),
            rgba("#2DE6FF"), rgba("#FFB833"),
            rgba("#FF8C24"), rgba("#FF5224"),
        ]
    else:
        led_colors = [rgba("#25303D")] * 8
    for idx, col in enumerate(led_colors):
        px[12, 4 + idx] = col

    # Lower-left cargo loading port hatch (x=3..9, y=8..12)
    fill_rect(px, 3, 8, 9, 12, c_dk)
    fill_rect(px, 4, 9, 8, 11, c_in)
    hline(px, 4, 8, 8, c_amb)
    hline(px, 4, 8, 12, c_amb_d)
    px[6, 10] = c_hi
    px[5, 10] = rgba("#3CE862") if active else rgba("#33443C")
    px[7, 10] = rgba("#3CE862") if active else rgba("#33443C")
    return im


def make_comm_base_station_base() -> Image.Image:
    """16x16 steel industrial base casing side/bottom with ventilation slits & rivets."""
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#151B24")
    c_sl = rgba("#2C3747")
    c_rib = rgba("#7E91A8")
    c_hi = rgba("#B6C7DB")
    c_cu = rgba("#C27A3A")

    # Inner recessed panel frame
    fill_rect(px, 3, 3, 12, 12, c_sl)
    hline(px, 3, 12, 3, c_dk)
    vline(px, 3, 3, 12, c_dk)
    hline(px, 3, 12, 12, c_rib)
    vline(px, 12, 3, 12, c_rib)

    # Upper & lower horizontal ventilation louvres
    for y in (4, 6, 10, 12):
        hline(px, 5, 10, y, c_dk)
        if y in (4, 10):
            hline(px, 5, 10, y + 1, c_rib)

    # Central reinforced riveted steel belt across y=7..8 with copper RF grounding bolts
    fill_rect(px, 3, 7, 12, 8, rgba("#536478"))
    hline(px, 3, 12, 7, c_rib)
    hline(px, 3, 12, 8, rgba("#364252"))
    px[7, 7] = c_cu
    px[8, 7] = rgba("#E6A85C")
    px[7, 8] = rgba("#824C1C")
    px[8, 8] = c_cu

    # Four heavy mounting rivets
    for rx, ry in ((3, 3), (12, 3), (3, 12), (12, 12)):
        px[rx, ry] = c_dk
    for rx, ry in ((4, 4), (11, 4), (4, 11), (11, 11)):
        px[rx, ry] = c_hi
    return im


def make_comm_base_station_top() -> Image.Image:
    """16x16 industrial top deck plate with central antenna mount socket & cyan/amber circuit accents.

    Outer ring (0..3, 12..15) renders on the pedestal top [0,9,0]..[16,9,16];
    inner socket (4..11, 4..11) renders 1:1 on the upper collar top [4,11,4]..[12,11,12].
    """
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_dk = rgba("#141A22")
    c_sl = rgba("#26303E")
    c_mid = rgba("#54657A")
    c_hi = rgba("#A8B9CE")
    c_spec = rgba("#D0DFEE")
    c_cyn = rgba("#3CF2B8")
    c_cyn_d = rgba("#1A7F78")
    c_amb = rgba("#F0A829")
    c_amb_d = rgba("#9E6414")

    # Outer deck diagonal circuit traces feeding from corners into central collar
    # Top-left & bottom-right: cyan-green RF traces
    for x, y in ((2, 3), (3, 3), (3, 2), (12, 12), (12, 13), (13, 12)):
        px[x, y] = c_cyn_d
    for x, y in ((3, 3), (12, 12)):
        px[x, y] = c_cyn
    # Top-right & bottom-left: amber power bus traces
    for x, y in ((12, 2), (12, 3), (13, 3), (2, 12), (3, 12), (3, 13)):
        px[x, y] = c_amb_d
    for x, y in ((12, 3), (3, 12)):
        px[x, y] = c_amb
    # Mid-edge conduit ports touching collar sides
    for x, y in ((7, 2), (8, 2), (7, 13), (8, 13), (2, 7), (2, 8), (13, 7), (13, 8)):
        px[x, y] = c_dk
    for x, y in ((7, 3), (8, 3), (7, 12), (8, 12)):
        px[x, y] = c_cyn
    for x, y in ((3, 7), (3, 8), (12, 7), (12, 8)):
        px[x, y] = c_amb

    # Central antenna mount socket (4..11, 4..11) — 1:1 on collar top face
    fill_rect(px, 4, 4, 11, 11, c_dk)
    fill_rect(px, 5, 5, 10, 10, c_mid)
    hline(px, 5, 10, 5, c_hi)
    vline(px, 5, 5, 10, c_hi)
    hline(px, 5, 10, 10, rgba("#333F4F"))
    vline(px, 10, 5, 10, rgba("#333F4F"))
    px[5, 5] = c_spec

    # Inner dark coaxial socket ring (6..9, 6..9) + central steel mast seat (7..8, 7..8)
    fill_rect(px, 6, 6, 9, 9, c_sl)
    for cx, cy in ((6, 6), (9, 6), (6, 9), (9, 9)):
        px[cx, cy] = c_cyn_d
    px[6, 6] = c_cyn
    px[9, 9] = c_cyn
    px[9, 6] = c_amb
    px[6, 9] = c_amb
    fill_rect(px, 7, 7, 8, 8, c_dk)
    px[7, 7] = c_hi
    px[8, 8] = rgba("#4E5F73")
    return im


def make_comm_base_station_front(active: bool) -> Image.Image:
    """16x16 faceplate for Communication Base Station (inactive dark-red vs active cyan-green).

    Designed with exact 1:1 3D voxel UV zones:
      - y=5..6 (x=4..11): upper transceiver collar sides [4,9,4]..[12,11,12]
      - y=7..15 (x=0..15): heavy base pedestal horizontal faces [0,0,0]..[16,9,16]
    """
    im = draw_ic2_casing(dark=False)
    px = im.load()
    c_out = rgba("#151A22")
    c_hi = rgba("#A8B9CE")
    c_spec = rgba("#CDDCEF")
    c_mid = rgba("#78899F")
    c_base = rgba("#5D6E83")
    c_sh = rgba("#3A4656")
    c_deep = rgba("#1E2530")

    if active:
        led_hi = rgba("#C8FFF4")
        led_main = rgba("#3CF2B8")
        led_cyan = rgba("#4CE8FF")
        led_low = rgba("#1DA892")
        led_low2 = rgba("#1E9CC2")
        sig_a = rgba("#3CF2B8")
        sig_b = rgba("#4CE8FF")
        sig_c = rgba("#C4FFF6")
        col_led = rgba("#4CE8FF")
    else:
        led_hi = rgba("#A83440")
        led_main = rgba("#82212B")
        led_cyan = rgba("#6E1B24")
        led_low = rgba("#4D131A")
        led_low2 = rgba("#3E0F15")
        sig_a = rgba("#70242D")
        sig_b = rgba("#4C1C22")
        sig_c = rgba("#8F313C")
        col_led = rgba("#82212B")

    # Upper ventilation louver bay (y=2..4, x=3..12) for cohesive 16x16 tile
    fill_rect(px, 3, 2, 12, 4, c_deep)
    hline(px, 4, 11, 3, c_sh)
    for x in (4, 6, 8, 10):
        px[x, 3] = c_mid

    # Upper transceiver collar strip (y=5..6; x=4..11 sampled 1:1 by 3D collar)
    fill_rect(px, 2, 5, 13, 6, c_deep)
    hline(px, 4, 11, 5, c_hi)
    hline(px, 4, 11, 6, c_base)
    px[4, 5] = c_deep
    px[11, 5] = c_deep
    px[4, 6] = c_sh
    px[11, 6] = c_sh
    # Twin collar indicator micro-LEDs
    px[6, 5] = col_led
    px[9, 5] = col_led
    px[7, 6] = c_mid
    px[8, 6] = c_mid

    # Main base pedestal 16x9 faceplate (y=7..15, x=0..15)
    fill_rect(px, 0, 7, 15, 15, c_out)
    fill_rect(px, 1, 7, 14, 14, c_base)
    # Crisp pedestal top/left highlight bevel & right/bottom shadow bevel
    hline(px, 1, 14, 7, c_hi)
    vline(px, 1, 7, 14, c_hi)
    hline(px, 1, 14, 14, c_sh)
    vline(px, 14, 7, 14, c_sh)
    px[1, 7] = c_spec
    px[2, 7] = c_spec
    px[14, 7] = c_mid
    px[1, 14] = c_mid
    px[14, 14] = c_deep

    # LED status bar housing (x=2..13, y=8..9)
    fill_rect(px, 2, 8, 13, 9, c_out)
    for x in range(3, 13):
        if x in (7, 8):
            px[x, 8] = led_hi
            px[x, 9] = led_main if active else led_low
        elif x % 2 == 0:
            px[x, 8] = led_cyan
            px[x, 9] = led_low2
        else:
            px[x, 8] = led_main
            px[x, 9] = led_low

    # Recessed RF terminal port + signal monitor (x=2..13, y=10..13)
    fill_rect(px, 2, 10, 13, 13, c_out)
    fill_rect(px, 3, 10, 12, 12, rgba("#18212C"))
    hline(px, 3, 12, 10, rgba("#10161E"))
    # Left coaxial RF plug socket (x=4..5, y=11..12)
    px[4, 11] = rgba("#D49442")
    px[5, 11] = rgba("#965E1E")
    px[4, 12] = rgba("#6B4214")
    px[5, 12] = rgba("#C28234")
    # Vertical separator
    vline(px, 6, 10, 12, c_out)
    # Right miniature RF signal strength screen (x=7..11, y=11..12)
    px[7, 12] = sig_a
    px[8, 12] = sig_a
    px[9, 11] = sig_b
    px[9, 12] = sig_a
    px[10, 11] = sig_c
    px[10, 12] = sig_b
    px[11, 11] = sig_b
    px[11, 12] = sig_b

    # Bottom faceplate steel trim strip & corner bolts (y=13)
    hline(px, 3, 12, 13, c_mid)
    px[2, 13] = c_deep
    px[13, 13] = c_deep
    px[3, 13] = c_hi
    px[12, 13] = c_sh
    return im


def make_comm_base_station_antenna(active: bool) -> Image.Image:
    """16x16 metallic antenna mast/dish texture with unpowered dark-red or glowing cyan-green beacon tip.

    UV layout for 3D Comm Base Station elements:
      - y=0..5  (center x=6..9): Signal beacon cap [6.5,14.5,6.5]..[9.5,16,9.5]
      - y=6..10 (x=4..11 / end x=2..3): Crossed RF transceiver dish/fin arms
      - y=11..15 (x=7..8): Central vertical antenna mast [7,11,7]..[9,15,9]
    """
    im = Image.new("RGBA", (16, 16), rgba("#242D38"))
    px = im.load()

    c_out = rgba("#141922")
    c_dk = rgba("#26303D")
    c_sh = rgba("#3D4B5C")
    c_md = rgba("#6B7E94")
    c_lt = rgba("#A3B5C9")
    c_hi = rgba("#D4E2F0")

    if active:
        b_core = rgba("#DBFFF6")
        b_hi = rgba("#4CE8FF")
        b_mid = rgba("#3CF2B8")
        b_edge = rgba("#199C88")
        b_base = rgba("#13595C")
        fin_node = rgba("#4CE8FF")
        fin_node2 = rgba("#3CF2B8")
        mast_glow = rgba("#3CF2B8")
    else:
        b_core = rgba("#9E3540")
        b_hi = rgba("#7D222C")
        b_mid = rgba("#5E1820")
        b_edge = rgba("#3D1016")
        b_base = rgba("#290D12")
        fin_node = rgba("#7A252E")
        fin_node2 = rgba("#521920")
        mast_glow = rgba("#4A5869")

    # --- Top Band (y=0..5): Beacon emitter crystal + gunmetal cap ---
    fill_rect(px, 0, 0, 15, 5, c_dk)
    hline(px, 0, 15, 0, c_out)
    hline(px, 0, 15, 5, c_out)
    # Full-width glow strip for side UVs + concentrated core at x=6..9, y=1..4 for top UV
    fill_rect(px, 1, 1, 14, 4, b_edge)
    fill_rect(px, 4, 1, 11, 3, b_mid)
    fill_rect(px, 6, 1, 9, 4, b_edge)
    fill_rect(px, 6, 1, 9, 3, b_mid)
    hline(px, 6, 9, 2, b_hi)
    px[7, 2] = b_core
    px[8, 2] = b_core
    px[7, 3] = b_hi
    px[8, 3] = b_mid
    hline(px, 1, 14, 4, b_base)
    # Cap corner steel brackets
    for x in (0, 5, 10, 15):
        vline(px, x, 1, 4, c_sh)
    px[5, 1] = c_lt
    px[10, 1] = c_lt

    # --- Middle Band (y=6..10): Crossed RF transceiver dish/fin bars ---
    fill_rect(px, 0, 6, 15, 10, c_out)
    # End caps (x=0..3, 12..15)
    fill_rect(px, 1, 7, 3, 9, c_sh)
    fill_rect(px, 12, 7, 14, 9, c_sh)
    hline(px, 1, 3, 7, c_lt)
    hline(px, 12, 14, 7, c_lt)
    px[2, 7] = fin_node
    px[13, 7] = fin_node
    # Main horizontal dipole arms (x=4..11, y=7..9)
    hline(px, 4, 11, 7, c_lt)
    hline(px, 4, 11, 8, c_md)
    hline(px, 4, 11, 9, c_sh)
    # Center hub highlight & tuning coils
    px[7, 7] = c_hi
    px[8, 7] = c_hi
    px[7, 8] = c_lt
    px[8, 8] = c_lt
    for nx in (5, 10):
        px[nx, 7] = fin_node
        px[nx, 8] = fin_node2

    # --- Bottom Band (y=11..15): Vertical machined antenna mast ---
    fill_rect(px, 0, 11, 15, 15, c_dk)
    # Flanking strut grooves for cohesive 16x16 look
    for x in (2, 3, 12, 13):
        vline(px, x, 11, 15, c_sh)
    vline(px, 6, 11, 15, c_out)
    vline(px, 9, 11, 15, c_out)
    # Central mast column (x=7..8, y=11..15 -> exact 2x4 UV at [7,11,9,15])
    vline(px, 7, 11, 15, c_lt)
    vline(px, 8, 11, 15, c_md)
    # Machined horizontal mast collar rib at y=13
    px[7, 13] = c_sh
    px[8, 13] = c_dk
    # Vertical RF power conduit tracer on mast
    px[7, 11] = c_hi
    px[8, 11] = mast_glow
    px[8, 12] = mast_glow
    px[8, 14] = mast_glow
    hline(px, 0, 15, 15, c_out)
    return im


# =============================================================================
# 2. ITEM TEXTURES (16x16)
# =============================================================================

def make_item_electric_cargo_boat() -> Image.Image:
    # Combines IC2's electric boat silhouette & silver-steel + cobalt/cyan palette
    # with an unmistakable vanilla oak/iron-latched cargo chest mounted inside!
    grid = [
        "................",
        "......oooo......",
        ".....oWLLWo.....",
        "....oWLMMLWo.yo.",
        "...ooWKKKKWooYo.",
        "..oSSoMSSMoSSSo.",
        ".oSLLoWLLWoHLSSo",
        "oSLCCooooooCLLSo",
        "oSLLLLSSSSLCCSSo",
        ".oSSBBBBBBBBSSo.",
        "..oSBBBBBBBBSo..",
        "..oSSMBBBBMSSoo.",
        "...ooSSSSSSooPco",
        ".....oooooo..oPo",
        "..............oo",
        "................",
    ]
    pal = {
        "o": rgba("#1A2530"),  # dark outline
        "S": rgba("#8598AB"),  # steel mid
        "L": rgba("#C8D6E2"),  # bright aluminum hull
        "H": rgba("#EBF2F7"),  # white specular
        "C": rgba("#4DE4F7"),  # electric cyan core/trim
        "B": rgba("#2864B8"),  # IC2 electric cobalt blue stripe
        "M": rgba("#495B6E"),  # shaded metal
        "y": rgba("#F7C844"),  # amber beacon highlight
        "Y": rgba("#D98A1E"),  # amber beacon
        "W": rgba("#C9923E"),  # oak chest wood
        "K": rgba("#6B4414"),  # dark oak chest rim/seam
        "P": rgba("#3C4D5E"),  # carbon paddle
        "c": rgba("#38D9F0"),  # paddle electric trim
    }
    # Refine chest lid & bright iron latch pixels cleanly
    im = render_grid(grid, pal)
    px = im.load()
    # Make chest unmistakably warm oak with dark oak trim & gleaming iron latch!
    chest_oak_hi = rgba("#DEAA54")
    chest_oak = rgba("#BD8534")
    chest_oak_dk = rgba("#8C5B1A")
    chest_rim = rgba("#54330C")
    latch_hi = rgba("#EDF4FA")
    latch_mid = rgba("#9BAAB8")
    # Chest sits cleanly at x=5..10, y=1..6
    for x in range(5, 11):
        px[x, 1] = chest_rim
        px[x, 6] = chest_rim
    for y in range(2, 6):
        px[5, y] = chest_rim
        px[10, y] = chest_rim
    for x in range(6, 10):
        px[x, 2] = chest_oak_hi
        px[x, 3] = chest_oak
        px[x, 4] = chest_rim  # lid seam
        px[x, 5] = chest_oak_dk
    # Crisp 2px iron latch right across lid seam
    px[7, 3] = latch_hi
    px[8, 3] = latch_mid
    px[7, 4] = latch_mid
    px[8, 4] = latch_hi
    return im


def make_item_transport_drone() -> Image.Image:
    # Crisp quad-rotor light industrial transport drone
    grid = [
        "................",
        ".ooo........ooo.",
        "oRCro......orCRo",
        "oRRRo..oo..oRRRo",
        ".oooRoHLLHoRooo.",
        "....oHLCCHHo....",
        "....oLLCCLLo....",
        "....oMMLLMMo....",
        "....oMDWWDMo....",
        "...oRoWWWWoRo...",
        "..oRRoWKKWoRRo..",
        ".oRCroWDDWorCRo.",
        ".oRRRo.oo.oRRRo.",
        "..ooo......ooo..",
        "................",
        "................",
    ]
    pal = {
        "o": rgba("#151C24"),
        "R": rgba("#3B4756"),  # carbon rotor blade
        "r": rgba("#687A8F"),  # rotor sheen
        "C": rgba("#3CEEFF"),  # glowing cyan LED / rotor tips
        "H": rgba("#E2ECF5"),  # bright IC2 light-steel
        "L": rgba("#AEC0D2"),  # light steel body
        "M": rgba("#6C7E93"),  # shaded steel
        "D": rgba("#323E4D"),  # dark chassis
        "W": rgba("#C48C3B"),  # compact cargo crate wood
        "K": rgba("#7D5118"),  # cargo crate trim
    }
    return render_grid(grid, pal)


def make_item_heavy_transport_drone() -> Image.Image:
    # Bulky reinforced armored heavy-lift drone with twin amber cargo pods & hazard stripes
    grid = [
        ".oooo......oooo.",
        "oRYRRo....oRRYRo",
        "oRRRRo.oo.oRRRRo",
        "oRRoRoACCAoRoRRo",
        ".oooDDYYYYDDooo.",
        "...oDDKYYKDDoo..",
        "..ooHMDDDDMHoo..",
        "..oPPHMMMMHPPo..",
        "..oPPHDDDDHPPo..",
        "..oPPoWWWWoPPo..",
        "..oPPoWLLWoPPo..",
        ".oRRo.oWwo.oRRo.",
        "oRRRRo.oo.oRRRRo",
        "oRYRRo....oRRYRo",
        ".oooo......oooo.",
        "................",
    ]
    pal = {
        "o": rgba("#12171F"),
        "R": rgba("#2B3440"),  # heavy carbon rotor
        "Y": rgba("#F2BC27"),  # hazard yellow stripe & rotor tips
        "K": rgba("#1C222B"),  # hazard black stripe
        "A": rgba("#FFB833"),  # amber avionics visor
        "C": rgba("#44EEFF"),  # cyan core sensor
        "H": rgba("#A3B4C7"),  # steel armor highlight
        "M": rgba("#66778B"),  # steel armor mid
        "D": rgba("#3B4757"),  # dark armored frame
        "P": rgba("#EB8A1E"),  # twin orange/amber cargo pods
        "W": rgba("#C47B2B"),  # reinforced central crate
        "w": rgba("#8A4E14"),  # shaded crate
        "L": rgba("#E5EEF7"),  # steel cargo latch
    }
    im = render_grid(grid, pal)
    px = im.load()
    # Add bright highlights on twin amber cargo pods so they pop cleanly
    for y in (7, 8):
        px[3, y] = rgba("#FFC247")
        px[12, y] = rgba("#FFC247")
    for y in (9, 10):
        px[3, y] = rgba("#B85A10")
        px[12, y] = rgba("#B85A10")
    return im


def make_item_rf_chip(tier: int) -> Image.Image:
    # Progressive IC2-style RF transceiver microchips (Tier 1/2/3)
    if tier == 1:
        # Tier I: Classic green PCB + copper pins + 1-arc RF wave
        grid = [
            "................",
            "....p..p..p.....",
            "...ooooooooo....",
            "..oGGGGGgGGGo...",
            ".poGdddddddGop..",
            "..oGd..w..dGo...",
            "..oGd.w.w.dGo...",
            ".poGd..D..dGop..",
            "..oGd..D..dGo...",
            "..oGd.ddd.dGo...",
            ".poGdddddddGop..",
            "..oGGgGGGgGGo...",
            "...ooooooooo....",
            "....p..p..p.....",
            "................",
            "................",
        ]
        pal = {
            "o": rgba("#151C22"),
            "p": rgba("#D98245"),  # copper pins
            "G": rgba("#257A42"),  # green PCB
            "g": rgba("#3BB564"),  # PCB trace highlight
            "d": rgba("#1B232C"),  # dark silicon IC
            "w": rgba("#F2B077"),  # copper-amber 1-wave arc
            "D": rgba("#52F287"),  # emitter dot
        }
        return render_grid(grid, pal)
    elif tier == 2:
        # Tier II: Deep sapphire-blue PCB + gold pins + 2-arc cyan/gold RF wave
        grid = [
            "................",
            "...p..p..p..p...",
            "..ooooooooooo...",
            ".oBBBgBBBgBBBo..",
            "poBdddddddddBop.",
            ".oBd.wwwww.dBo..",
            ".oBdw.....wdBo..",
            "poBd..ccc..dBop.",
            ".oBd.c.D.c.dBo..",
            ".oBd...D...dBo..",
            "poBdddddddddBop.",
            ".oBBgBBBBBgBBo..",
            "..ooooooooooo...",
            "...p..p..p..p...",
            "................",
            "................",
        ]
        pal = {
            "o": rgba("#131822"),
            "p": rgba("#F5C438"),  # gold pins
            "B": rgba("#1E4D94"),  # blue PCB
            "g": rgba("#3E85E6"),  # trace highlight
            "d": rgba("#141B26"),  # dark silicon IC
            "w": rgba("#FFE066"),  # outer gold RF arc
            "c": rgba("#4CEEFF"),  # inner cyan RF arc
            "D": rgba("#E0FFFF"),  # bright emitter core
        }
        return render_grid(grid, pal)
    else:
        # Tier III: Lapotron-violet PCB + gold/cyan pins + 3-arc radiant cyan/gold RF waves
        grid = [
            "................",
            "..p.c.p..p.c.p..",
            ".ooooooooooooo..",
            ".oPPgPPPPPggPo..",
            "poPdddddddddPop.",
            "coP.wwwwwww.Poc.",
            ".oPw.ccccc.wPo..",
            "poP.c.HHH.c.Pop.",
            ".oP..H.D.H..Po..",
            "coP....D....Poc.",
            "poPdddddddddPop.",
            ".oPPggPPPggPPo..",
            ".ooooooooooooo..",
            "..p.c.p..p.c.p..",
            "................",
            "................",
        ]
        pal = {
            "o": rgba("#121220"),
            "p": rgba("#FFD447"),  # gold pins
            "c": rgba("#30ECFF"),  # lapotron cyan pins & mid wave
            "P": rgba("#4A2487"),  # violet PCB
            "g": rgba("#854CE6"),  # violet trace highlight
            "d": rgba("#161329"),  # obsidian IC core
            "w": rgba("#FFD95C"),  # outer gold wave
            "H": rgba("#9EFAFF"),  # inner radiant wave
            "D": rgba("#FFFFFF"),  # pure white lapotron core
        }
        return render_grid(grid, pal)


def make_item_chunk_loading_upgrade() -> Image.Image:
    # Matches IC2 upgrade card silhouette (energy_storage / overclocker / remote_interface)
    # with ender-teal LEDs & crisp luminous 3x3 chunk grid emblem inside!
    grid = [
        "..oooooooooooo..",
        ".oEHHHHHHHHHHEo.",
        ".oHooooooooooHo.",
        ".oHoTTdTTdTToHo.",
        ".oHoTTdTTdTToHo.",
        ".oHodddddddddHo.",
        ".oHoTTdCCdTToHo.",
        ".oHoTTdCCdTToHo.",
        ".oHodddddddddHo.",
        ".oHoTTdTTdTToHo.",
        ".oHoTTdTTdTToHo.",
        ".oHooooooooooHo.",
        ".oMMMMMMMMMMMMo.",
        "..oGGooGGooGGo..",
        "..oYYooYYooYYo..",
        "...oo..oo..oo...",
    ]
    pal = {
        "o": rgba("#141922"),  # IC2 upgrade dark frame
        "H": rgba("#D8E3EE"),  # IC2 brushed silver top/side rail
        "M": rgba("#6E7D8F"),  # IC2 lower metallic bezel
        "E": rgba("#2AF0BE"),  # ender-teal corner LEDs
        "d": rgba("#0E151C"),  # dark interior screen / chunk grid lines
        "T": rgba("#1BC29A"),  # outer 8 loaded chunks (ender-teal)
        "C": rgba("#6BFFDF"),  # center anchor chunk
        "G": rgba("#B87616"),  # IC2 gold connector pin top
        "Y": rgba("#FFE87C"),  # IC2 gold connector pin highlight
    }
    im = render_grid(grid, pal)
    px = im.load()
    # Add crisp bevel shimmer on each 2x2 chunk cell and center core!
    for ox in (4, 7, 10):
        for oy in (3, 6, 9):
            if ox == 7 and oy == 6:
                px[ox, oy] = rgba("#E2FFF8")
                px[ox + 1, oy] = rgba("#88FFE6")
                px[ox, oy + 1] = rgba("#54F5CF")
                px[ox + 1, oy + 1] = rgba("#26D9AE")
            else:
                px[ox, oy] = rgba("#3DEBC0")
                px[ox + 1, oy] = rgba("#1EC29B")
                px[ox, oy + 1] = rgba("#18A684")
                px[ox + 1, oy + 1] = rgba("#107D63")
    # Silver side-rail shading to match IC2 energy_storage.png
    for y in range(2, 12):
        px[2, y] = rgba("#B8C6D6")
        px[13, y] = rgba("#8494A6")
    # Corner LED speculars
    px[2, 1] = rgba("#B8FFF0")
    px[13, 1] = rgba("#2AF0BE")
    return im


def make_item_operation_terminal() -> Image.Image:
    """16x16 rugged industrial handheld Operation Terminal (bezel, RF antenna stub, cyan LCD, keypad)."""
    grid = [
        "............oCo.",
        "............oGo.",
        "..oooooooooooAo.",
        ".oHHHHHHHHHHMMo.",
        ".oHooooooooooMo.",
        ".oHoDGCCCCDDoMo.",
        ".oHoDGCCCDDDoMo.",
        ".oHoDACCCCDDoMo.",
        ".oHoDDDDGGDDoMo.",
        ".oHooooooooooMo.",
        ".oMMoSSSSSSoMMo.",
        ".oMMoSkSGSAoMMo.",
        ".oMMokkkSSSoMMo.",
        ".oMMoSkSSkSoMMo.",
        ".oDDMMMMMMMMDDo.",
        "..oooooooooooo..",
    ]
    pal = {
        "o": rgba("#141922"),  # crisp dark outline
        "C": rgba("#4CE8FF"),  # glowing cyan antenna tip & screen readout
        "G": rgba("#3CF2B8"),  # cyan-green active signal & confirm key
        "A": rgba("#FFB833"),  # amber telemetry dot & launch micro-key
        "H": rgba("#B2C4D8"),  # top/left brushed steel bezel highlight
        "M": rgba("#5E7086"),  # rugged steel casing mid
        "D": rgba("#0D1E29"),  # dark marine-slate LCD background & rubber bumpers
        "S": rgba("#3A4757"),  # recessed keypad bed
        "k": rgba("#8EA2B8"),  # tactile steel micro-keys / D-pad
    }
    im = render_grid(grid, pal)
    px = im.load()
    # Refine top-right antenna stub (x=13, y=0..2 with dark outline at x=12,14)
    px[13, 0] = rgba("#B8FFFF")
    px[13, 1] = rgba("#3CF2B8")
    px[13, 2] = rgba("#4C5D73")
    # Crisp miniature station list readout rows inside LCD (x=4..11, y=5..8)
    # Row 1 (y=5): online green dot + bright cyan station text line
    px[5, 5] = rgba("#3CF285")
    px[6, 5] = rgba("#0D1E29")
    px[7, 5] = rgba("#A8FFF4")
    px[8, 5] = rgba("#4CE8FF")
    px[9, 5] = rgba("#4CE8FF")
    px[10, 5] = rgba("#2AB5CC")
    # Row 2 (y=6): online green dot + shorter teal station line
    px[5, 6] = rgba("#3CF285")
    px[6, 6] = rgba("#0D1E29")
    px[7, 6] = rgba("#3CF2B8")
    px[8, 6] = rgba("#3CF2B8")
    px[9, 6] = rgba("#1E9E8C")
    px[10, 6] = rgba("#0D1E29")
    # Row 3 (y=7): amber warning dot + dim status line
    px[5, 7] = rgba("#FFB833")
    px[6, 7] = rgba("#0D1E29")
    px[7, 7] = rgba("#6FA0B8")
    px[8, 7] = rgba("#4A738A")
    px[9, 7] = rgba("#4A738A")
    px[10, 7] = rgba("#0D1E29")
    # Row 4 (y=8): miniature glowing cyan-green terminal EU bar at bottom-right of LCD
    px[5, 8] = rgba("#183242")
    px[6, 8] = rgba("#183242")
    px[7, 8] = rgba("#183242")
    px[8, 8] = rgba("#3CF2B8")
    px[9, 8] = rgba("#4CE8FF")
    px[10, 8] = rgba("#4CE8FF")
    # Dark armored side grip bumpers at lower corners
    px[2, 14] = rgba("#26303D")
    px[3, 14] = rgba("#26303D")
    px[12, 14] = rgba("#26303D")
    px[13, 14] = rgba("#26303D")
    # Subtle golden/steel bottom charge contacts
    px[7, 14] = rgba("#D99B38")
    px[8, 14] = rgba("#F2C45A")
    return im


# =============================================================================
# 3. ENTITY TEXTURES
# =============================================================================

def make_entity_electric_cargo_boat() -> Image.Image:
    """128x128 RGBA texture matching vanilla Minecraft ChestBoatModel exactly across 1.19.2..26.2."""
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    px = im.load()

    # Palette (inspired by IC2 boat_electric.png steel + cobalt blue + cyan, plus oak cargo chest)
    s_hi = rgba("#D4DFEA")
    s_lt = rgba("#BCC9D6")
    s_md = rgba("#9BAAB9")
    s_dk = rgba("#697A8D")
    s_bd = rgba("#252F3B")
    s_in = rgba("#4E5E70")
    blue_d = rgba("#1C4CA8")
    blue_m = rgba("#2B6AD1")
    cyan = rgba("#5CE8FF")
    amber = rgba("#F2B632")
    pad_d = rgba("#242D38")
    pad_m = rgba("#3B4859")
    pad_l = rgba("#5C6E85")

    def paint_hull_panel(x0, y0, x1, y1, stripe_y=None, rivets_y=None, segment_w=8):
        fill_rect(px, x0, y0, x1, y1, s_lt)
        hline(px, x0, x1, y0, s_bd)
        hline(px, x0, x1, y1, s_bd)
        vline(px, x0, y0, y1, s_bd)
        vline(px, x1, y0, y1, s_bd)
        if y1 - y0 >= 3:
            hline(px, x0 + 1, x1 - 1, y0 + 1, s_hi)
            hline(px, x0 + 1, x1 - 1, y1 - 1, s_md)
        for sx in range(x0 + segment_w, x1, segment_w):
            vline(px, sx, y0 + 1, y1 - 1, s_dk)
            if sx + 1 < x1:
                vline(px, sx + 1, y0 + 1, y1 - 1, s_hi)
        if stripe_y is not None:
            hline(px, x0 + 1, x1 - 1, stripe_y, blue_m)
            for sx in range(x0 + 2, x1 - 1, 4):
                px[sx, stripe_y] = blue_d
            if stripe_y + 1 < y1 - 1:
                hline(px, x0 + 1, x1 - 1, stripe_y + 1, s_md)
                for sx in range(x0 + 2, x1 - 1, segment_w):
                    px[sx, stripe_y + 1] = cyan
                    if sx + 1 < x1 - 1:
                        px[sx + 1, stripe_y + 1] = cyan
        if rivets_y is not None:
            for rx in range(x0 + 3, x1 - 1, segment_w):
                px[rx, rivets_y] = s_bd
                if rx + 1 < x1:
                    px[rx + 1, rivets_y] = s_hi

    # --- 1) BOTTOM (texOffs 0,0; box 28x16x3 -> y=0..2 x=3..58; y=3..18 x=0..61) ---
    fill_rect(px, 3, 0, 58, 2, s_md)
    hline(px, 3, 58, 0, s_bd)
    hline(px, 3, 58, 1, blue_m)
    hline(px, 3, 58, 2, s_lt)
    fill_rect(px, 0, 3, 61, 18, s_lt)
    hline(px, 0, 61, 18, s_bd)
    vline(px, 0, 3, 18, s_bd)
    vline(px, 61, 3, 18, s_bd)
    # Interior deck floor (x=3..30, y=3..18): non-slip ribbed industrial steel deck
    fill_rect(px, 3, 3, 30, 18, s_md)
    for y in range(4, 18):
        if y % 2 == 0:
            hline(px, 4, 29, y, s_lt)
    vline(px, 3, 3, 18, s_bd)
    vline(px, 30, 3, 18, s_bd)
    # Blue electric conduit line down center of deck
    hline(px, 4, 29, 10, blue_d)
    hline(px, 4, 29, 11, blue_m)
    # Subtle yellow-black stern caution strip
    for y in range(5, 16):
        px[5, y] = amber if (y % 2 == 0) else s_bd
    # Hull underside (x=34..61, y=3..18)
    fill_rect(px, 34, 3, 61, 18, s_dk)
    for y in (6, 10, 14):
        hline(px, 35, 60, y, s_in)
    vline(px, 31, 3, 18, blue_d)
    vline(px, 32, 3, 18, blue_m)
    vline(px, 33, 3, 18, s_bd)

    # --- 2) BACK / STERN (texOffs 0,19; box 18x6x2 -> y=19..20 x=2..37; y=21..26 x=0..39) ---
    fill_rect(px, 2, 19, 37, 20, s_bd)
    hline(px, 3, 36, 20, s_md)
    paint_hull_panel(0, 21, 39, 26, stripe_y=23, rivets_y=22, segment_w=6)
    # Stern navigation amber/cyan indicator lights
    px[4, 23] = amber
    px[5, 23] = amber
    px[14, 23] = amber
    px[15, 23] = amber
    px[23, 23] = cyan
    px[24, 23] = cyan

    # --- 3) FRONT / BOW (texOffs 0,27; box 16x6x2 -> y=27..28 x=2..35; y=29..34 x=0..35) ---
    fill_rect(px, 2, 27, 35, 28, s_bd)
    hline(px, 3, 34, 28, s_md)
    paint_hull_panel(0, 29, 35, 34, stripe_y=31, rivets_y=30, segment_w=6)
    # Bow cyan electric headlamp strip
    hline(px, 6, 11, 31, cyan)
    hline(px, 23, 28, 31, cyan)

    # --- 4) RIGHT WALL (texOffs 0,35; box 28x6x2 -> y=35..36 x=2..57; y=37..42 x=0..59) ---
    fill_rect(px, 2, 35, 57, 36, s_bd)
    hline(px, 3, 56, 36, s_md)
    paint_hull_panel(0, 37, 59, 42, stripe_y=39, rivets_y=38, segment_w=7)

    # --- 5) LEFT WALL (texOffs 0,43; box 28x6x2 -> y=43..44 x=2..57; y=45..50 x=0..59) ---
    fill_rect(px, 2, 43, 57, 44, s_bd)
    hline(px, 3, 56, 44, s_md)
    paint_hull_panel(0, 45, 59, 50, stripe_y=47, rivets_y=46, segment_w=7)

    # --- 6) LEFT & RIGHT PADDLES (texOffs 62,0 and 62,20) ---
    for py0 in (0, 20):
        # Blade top/bottom (x=69..70, y=py0..py0+6)
        fill_rect(px, 69, py0, 70, py0 + 6, pad_m)
        # Blade sides (x=62..77, y=py0+7..py0+12)
        fill_rect(px, 62, py0 + 7, 77, py0 + 12, pad_m)
        hline(px, 62, 77, py0 + 7, pad_l)
        hline(px, 62, 77, py0 + 12, pad_d)
        hline(px, 63, 76, py0 + 10, blue_m)
        hline(px, 64, 75, py0 + 11, cyan)
        # Shaft top/bottom (x=80..83, y=py0..py0+17)
        fill_rect(px, 80, py0, 83, py0 + 17, pad_d)
        vline(px, 81, py0, py0 + 17, pad_l)
        vline(px, 82, py0, py0 + 17, pad_m)
        # Shaft sides (x=62..101, y=py0+18..py0+19)
        fill_rect(px, 62, py0 + 18, 101, py0 + 19, pad_d)
        hline(px, 62, 101, py0 + 18, pad_m)

    # --- 7) CHEST (exact ChestBoatModel UV in bottom half y=59..95) ---
    w_rim = rgba("#472C0E")
    w_dk = rgba("#7D5218")
    w_md = rgba("#A8752C")
    w_lt = rgba("#C99543")
    w_hi = rgba("#DEAE5E")
    st_bd = rgba("#26303D")
    st_md = rgba("#6D7F94")
    st_hi = rgba("#CBD8E6")

    def paint_chest_face(x0, y0, x1, y1, steel_corners=True):
        fill_rect(px, x0, y0, x1, y1, w_rim)
        fill_rect(px, x0 + 1, y0 + 1, x1 - 1, y1 - 1, w_md)
        hline(px, x0 + 1, x1 - 1, y0 + 1, w_hi)
        hline(px, x0 + 1, x1 - 1, y1 - 1, w_dk)
        # Wood plank lines
        for y in range(y0 + 2, y1 - 1):
            if (y - y0) % 2 == 0:
                hline(px, x0 + 1, x1 - 1, y, w_lt)
            for x in range(x0 + 2, x1 - 1):
                if (x * 7 + y * 11) % 13 == 0:
                    px[x, y] = w_dk
        if steel_corners:
            for cx, cy in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
                px[cx, cy] = st_bd
            for cx, cy in ((x0 + 1, y0), (x0, y0 + 1), (x1 - 1, y0), (x1, y0 + 1),
                           (x0 + 1, y1), (x0, y1 - 1), (x1 - 1, y1), (x1, y1 - 1)):
                px[cx, cy] = st_md

    # chest_lock (2x4x1 @ 0,59 -> y=59 x=1..4; y=60..63 x=0..5)
    fill_rect(px, 1, 59, 4, 59, st_hi)
    fill_rect(px, 0, 60, 5, 63, st_md)
    hline(px, 0, 5, 60, st_hi)
    hline(px, 0, 5, 63, st_bd)
    px[2, 61] = cyan  # tiny smart-lock LED!
    px[3, 61] = st_hi
    px[2, 62] = st_bd

    # chest_lid (12x5x12 @ 0,59 -> top: 12..23,59..70; bottom: 24..35,59..70; 4 sides: 0..47,71..75)
    paint_chest_face(12, 59, 23, 70, steel_corners=True)
    # Industrial steel tie-down strap across chest lid top
    vline(px, 14, 60, 69, st_md)
    vline(px, 21, 60, 69, st_md)
    px[14, 61] = st_hi
    px[21, 61] = st_hi
    paint_chest_face(24, 59, 35, 70, steel_corners=False)
    for face in range(4):
        fx0 = face * 12
        paint_chest_face(fx0, 71, fx0 + 11, 75, steel_corners=True)
        vline(px, fx0 + 2, 71, 75, st_md)
        vline(px, fx0 + 9, 71, 75, st_md)

    # chest_bottom (12x8x12 @ 0,76 -> top: 12..23,76..87; bottom: 24..35,76..87; 4 sides: 0..47,88..95)
    paint_chest_face(12, 76, 23, 87, steel_corners=False)
    paint_chest_face(24, 76, 35, 87, steel_corners=False)
    for face in range(4):
        fx0 = face * 12
        paint_chest_face(fx0, 88, fx0 + 11, 95, steel_corners=True)
        vline(px, fx0 + 2, 88, 95, st_md)
        vline(px, fx0 + 9, 88, 95, st_md)
        px[fx0 + 2, 89] = st_hi
        px[fx0 + 9, 89] = st_hi

    return im


def _paint_mc_box_uv(px, u: int, v: int, w: int, h: int, d: int,
                     top_col, bot_col, side_col, front_col, border_col,
                     accent_col=None, highlight_col=None):
    """Paint a standard Minecraft CubeListBuilder UV net at (u, v) of size (w, h, d)."""
    # Top face:    [u+d .. u+d+w-1,     v .. v+d-1]
    # Bottom face: [u+d+w .. u+d+2w-1,  v .. v+d-1]
    # Right face:  [u .. u+d-1,         v+d .. v+d+h-1]
    # Front face:  [u+d .. u+d+w-1,     v+d .. v+d+h-1]
    # Left face:   [u+d+w .. u+2d+w-1,  v+d .. v+d+h-1]
    # Back face:   [u+2d+w .. u+2d+2w-1,v+d .. v+d+h-1]
    def face(x0, y0, fw, fh, col):
        if fw <= 0 or fh <= 0:
            return
        x1, y1 = x0 + fw - 1, y0 + fh - 1
        fill_rect(px, x0, y0, x1, y1, col)
        if fw >= 3 and fh >= 3:
            hline(px, x0, x1, y0, border_col)
            hline(px, x0, x1, y1, border_col)
            vline(px, x0, y0, y1, border_col)
            vline(px, x1, y0, y1, border_col)
            if highlight_col is not None and fw >= 4 and fh >= 4:
                hline(px, x0 + 1, x1 - 1, y0 + 1, highlight_col)

    face(u + d, v, w, d, top_col)
    face(u + d + w, v, w, d, bot_col)
    face(u, v + d, d, h, side_col)
    face(u + d, v + d, w, h, front_col)
    face(u + d + w, v + d, d, h, side_col)
    face(u + 2 * d + w, v + d, w, h, side_col)
    if accent_col is not None and w >= 4 and h >= 2:
        # Subtle accent stripe on front face
        hline(px, u + d + 1, u + d + w - 2, v + d + h // 2, accent_col)


def make_entity_drone(heavy: bool) -> Image.Image:
    """64x64 RGBA UV texture matching docs/DRONE_MODEL_UV.md exactly."""
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()

    # Assert exact non-overlapping UV footprints matching docs/DRONE_MODEL_UV.md
    uv_rects = [
        ("body", 0, 0, 40, 14),
        ("nose_sensor", 40, 0, 12, 4),
        ("antenna", 58, 0, 4, 5),
        ("arm", 40, 4, 16, 7),
        ("cargo_bay", 0, 14, 32, 13),
        ("motor_hub", 32, 14, 8, 5),
        ("blade_x", 40, 14, 18, 2),
        ("blade_z", 40, 16, 18, 9),
        ("skid", 0, 27, 22, 11),
        ("side_pod", 22, 27, 22, 12),
    ]
    used = {}
    for name, rx, ry, rw, rh in uv_rects:
        assert rx + rw <= 64 and ry + rh <= 64, f"UV out of bounds: {name}"
        for y in range(ry, ry + rh):
            for x in range(rx, rx + rw):
                assert (x, y) not in used, f"UV overlap at {(x,y)}: {name} vs {used[(x,y)]}"
                used[(x, y)] = name

    if not heavy:
        c_bd = rgba("#19212B")
        c_top = rgba("#BAC9D8")
        c_hi = rgba("#DCE6F0")
        c_side = rgba("#7E91A6")
        c_bot = rgba("#4A596B")
        c_acc = rgba("#33EAFF")
        c_acc_hi = rgba("#CCF9FF")
        c_arm = rgba("#2C3745")
        c_arm_hi = rgba("#586A7F")
        c_blade = rgba("#222A35")
        c_tip = rgba("#3CEEFF")
        c_crate = rgba("#BC8637")
        c_crate_hi = rgba("#D9A552")
        c_crate_bd = rgba("#54340F")
        c_pod = rgba("#5A6C80")
        c_pod_hi = rgba("#889BB0")
    else:
        c_bd = rgba("#131820")
        c_top = rgba("#4E5D6E")
        c_hi = rgba("#8093A8")
        c_side = rgba("#3A4757")
        c_bot = rgba("#252E3A")
        c_acc = rgba("#FFB52E")
        c_acc_hi = rgba("#FFE699")
        c_arm = rgba("#212832")
        c_arm_hi = rgba("#485769")
        c_blade = rgba("#1A2029")
        c_tip = rgba("#F2B824")
        c_crate = rgba("#9E5B1A")
        c_crate_hi = rgba("#D98229")
        c_crate_bd = rgba("#3D220A")
        c_pod = rgba("#E07E1B")
        c_pod_hi = rgba("#FFA93B")

    # 1. body (10, 4, 10) @ (0, 0)
    _paint_mc_box_uv(px, 0, 0, 10, 4, 10, c_top, c_bot, c_side, c_side, c_bd, c_acc, c_hi)
    # Top fuselage detail (x=10..19, y=0..9): crisp forward chevron pointing -Z (upward on top UV)
    for x, y in ((14, 2), (15, 2), (13, 3), (14, 3), (15, 3), (16, 3), (12, 4), (17, 4)):
        px[x, y] = c_acc
    px[14, 3] = c_acc_hi
    px[15, 3] = c_acc_hi
    if heavy:
        # Yellow/black hazard stripes along top rear edge of heavy drone
        for x in range(11, 19):
            px[x, 7] = rgba("#F2BC24") if (x % 2 == 0) else c_bd
            px[x, 8] = rgba("#F2BC24") if (x % 2 == 1) else c_bd

    # 2. nose_sensor (4, 2, 2) @ (40, 0)
    _paint_mc_box_uv(px, 40, 0, 4, 2, 2, c_arm_hi, c_arm, c_arm, c_bd, c_bd)
    # Glowing optical camera lens on front face (x=42..45, y=2..3)
    px[43, 2] = c_acc_hi
    px[44, 2] = c_acc
    px[43, 3] = c_acc
    px[44, 3] = rgba("#127A94") if not heavy else rgba("#B86214")

    # 3. antenna (1, 4, 1) @ (58, 0)
    fill_rect(px, 58, 0, 61, 4, c_hi)
    hline(px, 58, 61, 0, c_acc)
    hline(px, 58, 61, 1, c_acc_hi)
    hline(px, 58, 61, 4, c_bd)

    # 4. arm (2, 1, 6) @ (40, 4)
    _paint_mc_box_uv(px, 40, 4, 2, 1, 6, c_arm_hi, c_arm, c_arm, c_arm, c_bd)
    px[46, 5] = c_acc
    px[47, 5] = c_acc

    # 5. cargo_bay (8, 5, 8) @ (0, 14)
    _paint_mc_box_uv(px, 0, 14, 8, 5, 8, c_crate_hi, c_crate_bd, c_crate, c_crate, c_crate_bd, highlight_col=c_crate_hi)
    # Steel straps & latch across cargo_bay faces (y=22..26)
    for fx in (8, 24):
        vline(px, fx + 1, 22, 26, c_side)
        vline(px, fx + 6, 22, 26, c_side)
        px[fx + 3, 23] = c_hi
        px[fx + 4, 23] = c_hi

    # 6. motor_hub (2, 3, 2) @ (32, 14)
    _paint_mc_box_uv(px, 32, 14, 2, 3, 2, c_hi, c_bd, c_arm, c_arm_hi, c_bd)
    hline(px, 32, 39, 17, c_acc)

    # 7. blade_x (8, 1, 1) @ (40, 14) and blade_z (1, 1, 8) @ (40, 16)
    fill_rect(px, 40, 14, 57, 15, c_blade)
    for tx in (40, 41, 48, 49, 56, 57):
        px[tx, 14] = c_tip
        px[tx, 15] = c_tip
    # blade_z: top/bot at x=48..49, y=16..23; sides at x=40..57, y=24
    fill_rect(px, 48, 16, 49, 23, c_blade)
    fill_rect(px, 40, 24, 57, 24, c_blade)
    for tx in (48, 49):
        px[tx, 16] = c_tip
        px[tx, 23] = c_tip
    for tx in (40, 47, 48, 49, 56, 57):
        px[tx, 24] = c_tip

    # 8. landing_skid (1, 1, 10) @ (0, 27)
    fill_rect(px, 0, 27, 21, 37, c_arm)
    hline(px, 0, 21, 37, c_bd)
    hline(px, 10, 11, 27, c_arm_hi)

    # 9. side_pod (3, 4, 8) @ (22, 27) — visible on HEAVY variant!
    _paint_mc_box_uv(px, 22, 27, 3, 4, 8, c_pod_hi, c_crate_bd, c_pod, c_pod, c_bd, c_acc_hi, c_pod_hi)
    # Caution stripe on heavy pod sides (x=22..29 and x=33..40, y=35..38)
    for sx in range(23, 29):
        px[sx, 36] = rgba("#F2BC24") if (sx % 2 == 0) else c_bd
    for sx in range(34, 40):
        px[sx, 36] = rgba("#F2BC24") if (sx % 2 == 0) else c_bd

    return im


# =============================================================================
# 4. GUI TEXTURES (512x256 atlas, 392x234 2-zone panel matching docs/GUI_LAYOUT.md)
# =============================================================================

def _draw_slot(px, item_x: int, item_y: int, accent=None, bg=None, dk=None, lt=None, mid=None):
    """Draw an authentic IC2 18x18 recessed inventory slot around (item_x, item_y)."""
    x0, y0, x1, y1 = item_x - 1, item_y - 1, item_x + 16, item_y + 16
    c_bg = bg if bg is not None else rgba("#8B9098")
    c_dk = dk if dk is not None else rgba("#373B42")
    c_lt = lt if lt is not None else rgba("#EEF1F5")
    c_mid = mid if mid is not None else rgba("#B8BDC6")
    fill_rect(px, x0, y0, x1, y1, c_bg)
    hline(px, x0, x1 - 1, y0, c_dk)
    vline(px, x0, y0, y1 - 1, c_dk)
    hline(px, x0 + 1, x1, y1, c_lt)
    vline(px, x1, y0 + 1, y1, c_lt)
    px[x1, y0] = c_mid
    px[x0, y1] = c_mid
    if accent is not None:
        # Subtle colored inner top highlight for special slots (e.g. RF chip)
        hline(px, x0 + 1, x1 - 1, y0 + 1, accent)


def _draw_lcd(px, x0: int, y0: int, w: int, h: int):
    x1, y1 = x0 + w - 1, y0 + h - 1
    fill_rect(px, x0, y0, x1, y1, rgba("#151C24"))
    hline(px, x0, x1 - 1, y0, rgba("#0B0F14"))
    vline(px, x0, y0, y1 - 1, rgba("#0B0F14"))
    hline(px, x0 + 1, x1, y1, rgba("#DCE2EA"))
    vline(px, x1, y0 + 1, y1, rgba("#DCE2EA"))
    # Subtle scanline
    for y in range(y0 + 2, y1 - 1, 2):
        hline(px, x0 + 1, x1 - 1, y, rgba("#1B242F"))


def _draw_watermark(px, item_x: int, item_y: int, pattern: list[str], col_hi, col_lo):
    for dy, row in enumerate(pattern):
        for dx, ch in enumerate(row):
            if ch == "#":
                px[item_x + dx, item_y + dy] = col_lo
            elif ch == "+":
                px[item_x + dx, item_y + dy] = col_hi


def _draw_eu_bolt(px, x0: int, y0: int, lit: bool):
    # 10x14 crisp IC2 lightning bolt
    bolt = [
        "....oo....",
        "...oBBoo..",
        "..oBHHBo..",
        "..oBHBo...",
        ".oBBHHBoo.",
        ".oBHHHBBoo",
        "..oooBHBo.",
        "....oBBBo.",
        "...oBHHBo.",
        "...oBHBo..",
        "..oBBBo...",
        "..oBBo....",
        "...oo.....",
        "..........",
    ]
    if lit:
        pal = {"o": rgba("#591808"), "B": rgba("#FF7B1C"), "H": rgba("#FFE854")}
    else:
        pal = {"o": rgba("#2B313B"), "B": rgba("#555D6B"), "H": rgba("#6E7787")}
    for dy, row in enumerate(bolt):
        for dx, ch in enumerate(row):
            if ch != ".":
                px[x0 + dx, y0 + dy] = pal[ch]


def _draw_swap_arrows_sprite(px, u0: int, v0: int):
    """Draw a crisp 12x10 transparent-BG swap-arrows sprite (amber -> top, cyan <- bottom)."""
    grid = [
        ".......oo...",
        ".ooooooAAo..",
        "oAAAAAAHHHo.",
        ".ooooooAAo..",
        ".......oo...",
        "...oo.......",
        "..oCCoooooo.",
        ".oKKKCCCCCCo",
        "..oCCoooooo.",
        "...oo.......",
    ]
    pal = {
        "o": rgba("#181E27"),
        "A": rgba("#F29B2E"),  # warm amber outbound arrow
        "H": rgba("#FFE47A"),  # amber tip highlight
        "C": rgba("#27C3E6"),  # cool cyan storage arrow
        "K": rgba("#A3F8FF"),  # cyan tip highlight
    }
    assert len(grid) == 10 and all(len(r) == 12 for r in grid)
    for dy, row in enumerate(grid):
        for dx, ch in enumerate(row):
            if ch != ".":
                px[u0 + dx, v0 + dy] = pal[ch]


def _verify_gui_layout_non_overlapping(is_cargo_station: bool):
    """Verify mathematically that every widget, LCD, slot, badge, and atlas sprite has ZERO overlaps."""
    panel_rects = [
        ("title_label", 8, 4, 150, 8),
        # Top Control Bar Row 1
        ("name_edit", 8, 13, 150, 12),
        ("save_btn", 162, 13, 44, 12),
        ("auto_return_btn", 210, 13, 66, 12),
        ("priority_btn", 279, 13, 68, 12),
        # Top Control Bar Row 2
        ("prev_btn", 8, 27, 14, 12),
        ("target_lcd", 23, 27, 256, 12),
        ("next_btn", 280, 27, 14, 12),
        ("launch_btn", 297, 27, 50, 12),
        # Top Control Bar Row 3
        ("x_edit", 8, 41, 44, 12),
        ("y_edit", 54, 41, 36, 12),
        ("z_edit", 92, 41, 44, 12),
        ("set_btn", 138, 41, 36, 12),
        ("status_lcd", 176, 41, 171, 12),
        # 2-Zone Cargo Section
        ("outbound_label", 8, 77, 160, 8),
        ("outbound_bar", 7, 85, 162, 1),
        ("outbound_grid", 7, 86, 162, 54),
        ("swap_btn", 172, 104, 16, 20),
        ("storage_label", 190, 77, 160, 8),
        ("storage_bar", 189, 85, 162, 1),
        ("storage_grid", 189, 86, 162, 54),
        # Player Inventory
        ("inv_label", 99, 144, 160, 8),
        ("player_inv", 98, 152, 162, 54),
        ("player_hotbar", 98, 210, 162, 18),
        # Right Column
        ("divider", 355, 13, 2, 215),
        ("eu_gauge", 362, 13, 18, 42),
        ("eu_bolt", 366, 57, 10, 14),
        ("battery_slot", 362, 75, 18, 18),
        ("upgrade_slots", 362, 101, 18, 72),
        ("chunk_badge", 362, 179, 18, 18),
        ("ready_badge", 362, 199, 18, 18),
    ]
    if not is_cargo_station:
        panel_rects.append(("harbor_lcd", 7, 56, 340, 18))
    else:
        panel_rects.extend([
            ("rf_chip_slot", 7, 56, 18, 18),
            ("rf_range_lcd", 26, 56, 42, 18),
            ("drone_bays", 73, 56, 108, 18),
            ("flight_deck_lcd", 186, 56, 161, 18),
        ])

    for name, x, y, w, h in panel_rects:
        assert x >= 3 and y >= 3 and x + w <= 389 and y + h <= 231, (
            f"Panel element {name} ({x},{y},{w},{h}) exceeds safe panel interior (3..388, 3..230)"
        )
    for i, (n1, x1, y1, w1, h1) in enumerate(panel_rects):
        for n2, x2, y2, w2, h2 in panel_rects[i + 1:]:
            overlap_x = x1 < x2 + w2 and x2 < x1 + w1
            overlap_y = y1 < y2 + h2 and y2 < y1 + h1
            assert not (overlap_x and overlap_y), (
                f"GUI layout overlap between {n1} ({x1},{y1},{w1},{h1}) and {n2} ({x2},{y2},{w2},{h2})"
            )

    atlas_rects = [
        ("main_panel", 0, 0, 392, 234),
        ("eu_fill", 396, 0, 16, 40),
        ("eu_bolt_lit", 414, 0, 10, 14),
        ("badge_active", 396, 44, 16, 16),
        ("badge_warn", 414, 44, 16, 16),
        ("badge_chunk", 396, 64, 16, 16),
        ("swap_arrows", 414, 64, 12, 10),
    ]
    for name, x, y, w, h in atlas_rects:
        assert x >= 0 and y >= 0 and x + w <= 512 and y + h <= 256, (
            f"Atlas sprite {name} ({x},{y},{w},{h}) exceeds 512x256"
        )
    for i, (n1, x1, y1, w1, h1) in enumerate(atlas_rects):
        for n2, x2, y2, w2, h2 in atlas_rects[i + 1:]:
            overlap_x = x1 < x2 + w2 and x2 < x1 + w1
            overlap_y = y1 < y2 + h2 and y2 < y1 + h1
            assert not (overlap_x and overlap_y), (
                f"Atlas sprite overlap between {n1} and {n2}"
            )


def _draw_base_gui(is_cargo_station: bool) -> Image.Image:
    _verify_gui_layout_non_overlapping(is_cargo_station)

    im = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    px = im.load()

    # Authentic IC2 beveled grey panel (392x234 in top-left of 512x256 atlas)
    PW, PH = 392, 234
    c_out = rgba("#1E232B")
    c_hi = rgba("#F2F4F7")
    c_bg = rgba("#C6C6C6")
    c_bg2 = rgba("#C0C2C6")  # ultra-subtle horizontal brush
    c_sh = rgba("#555B66")
    c_seam = rgba("#9BA0A8")
    c_seam_hi = rgba("#E4E7EC")

    # Outer frame
    fill_rect(px, 0, 0, PW - 1, PH - 1, c_out)
    # Rounded pixel corners (cut 1px transparent outside border)
    for cx, cy in ((0, 0), (PW - 1, 0), (0, PH - 1), (PW - 1, PH - 1)):
        px[cx, cy] = (0, 0, 0, 0)
    # Bevel border (2px highlight top/left, 2px shadow bottom/right)
    fill_rect(px, 1, 1, PW - 2, PH - 2, c_bg)
    hline(px, 1, PW - 3, 1, c_hi)
    hline(px, 1, PW - 4, 2, c_hi)
    vline(px, 1, 1, PH - 3, c_hi)
    vline(px, 2, 1, PH - 4, c_hi)
    hline(px, 2, PW - 2, PH - 2, c_sh)
    hline(px, 3, PW - 2, PH - 3, c_sh)
    vline(px, PW - 2, 2, PH - 2, c_sh)
    vline(px, PW - 3, 3, PH - 2, c_sh)
    # Inner subtle industrial horizontal grain
    for y in range(4, PH - 3, 2):
        hline(px, 3, PW - 4, y, c_bg2)
    # IC2 corner rivets
    for rx, ry in ((4, 4), (PW - 6, 4), (4, PH - 6), (PW - 6, PH - 6)):
        px[rx, ry] = rgba("#707680")
        px[rx + 1, ry + 1] = c_hi

    # Vertical bevel divider separating main area (x=7..350) from right power/upgrade column (x=356..388)
    vline(px, 355, 13, 227, c_seam)
    vline(px, 356, 13, 227, c_seam_hi)

    # Subtle horizontal section seams separating Top Control Bar / Middle Deck / 2-Zone Cargo
    hline(px, 7, 350, 54, c_seam)
    hline(px, 7, 350, 75, c_seam)

    # -------------------------------------------------------------------------
    # Top Control Bar LCDs (exact coordinates from docs/GUI_LAYOUT.md)
    # -------------------------------------------------------------------------
    # Row 2: Target Readout LCD (23, 27, 256, 12)
    _draw_lcd(px, 23, 27, 256, 12)
    # Row 3: Status Readout LCD (176, 41, 171, 12)
    _draw_lcd(px, 176, 41, 171, 12)

    # -------------------------------------------------------------------------
    # Middle Deck Strip (y = 56..73)
    # -------------------------------------------------------------------------
    drone_wm = [
        "................",
        "..###......###..",
        "..#+#......#+#..",
        "..###..##..###..",
        "....#.####.#....",
        ".....######.....",
        ".....##++##.....",
        ".....######.....",
        "....#.####.#....",
        "..###..##..###..",
        "..#+#......#+#..",
        "..###......###..",
        "................",
        "................",
        "................",
        "................",
    ]
    if not is_cargo_station:
        # Smart Port: Clean dark harbor telemetry LCD strip (7, 56, 340, 18)
        _draw_lcd(px, 7, 56, 340, 18)
        # Subtle 2px maritime cobalt/cyan status accent strip inside left edge (x=8..9; text starts x=12)
        vline(px, 8, 57, 72, rgba("#1E4F82"))
        vline(px, 9, 57, 72, rgba("#2E78BF"))
        for ay in (61, 62, 67, 68):
            px[9, ay] = rgba("#4CD8F5")
    else:
        # Cargo Station:
        # RF Chip Slot (8, 57) -> frame 7..24, 56..73
        _draw_slot(px, 8, 57, accent=rgba("#4A7E94"))
        rf_wm = [
            "................",
            "....#......#....",
            "...#..####..#...",
            "..#..#....#..#..",
            "..#.#..##..#.#..",
            "..#..#.++.#..#..",
            "...#...++...#...",
            ".......##.......",
            ".......##.......",
            "......####......",
            ".....######.....",
            "....########....",
            "................",
            "................",
            "................",
            "................",
        ]
        _draw_watermark(px, 8, 57, rf_wm, rgba("#7B8894"), rgba("#5F6973"))

        # RF Range Mini-LCD (26, 56, 42, 18)
        _draw_lcd(px, 26, 56, 42, 18)

        # 6 Drone Bay Slots (74 + bay * 18, 57) -> frames 73..180, 56..73
        for bay in range(6):
            bx = 74 + bay * 18
            _draw_slot(px, bx, 57)
            _draw_watermark(px, bx, 57, drone_wm, rgba("#7C838C"), rgba("#636A73"))

        # Flight Deck Status LCD (186, 56, 161, 18)
        _draw_lcd(px, 186, 56, 161, 18)

    # -------------------------------------------------------------------------
    # 2-Zone Cargo Section (y = 77..141, IDENTICAL on Smart Port & Cargo Station)
    # -------------------------------------------------------------------------
    # Left Zone: Outbound (0..26, 3x9 @ x = 8 + col*18, y = 87 + row*18)
    # Baked warm amber indicator bar at (7, 85, 162, 1)
    hline(px, 7, 168, 85, rgba("#D9821E"))
    hline(px, 8, 167, 85, rgba("#E69228"))
    px[7, 85] = rgba("#A85B10")
    px[168, 85] = rgba("#A85B10")
    for row in range(3):
        for col in range(9):
            _draw_slot(
                px,
                8 + col * 18,
                87 + row * 18,
                bg=rgba("#8E8D89"),
                dk=rgba("#443D35"),
                lt=rgba("#F0EBE4"),
                mid=rgba("#BCB7B0"),
            )
    # Subtle warm amber-tinted inner recess border around Outbound 3x9 perimeter (7..168, 86..139)
    hline(px, 7, 167, 86, rgba("#66441E"))
    vline(px, 7, 86, 138, rgba("#66441E"))
    hline(px, 8, 168, 139, rgba("#F3E1CA"))
    vline(px, 168, 87, 139, rgba("#F3E1CA"))
    # Subtle bottom amber accent hairline at (7, 140, 162, 1)
    hline(px, 7, 168, 140, rgba("#BD8C54"))

    # Center Channel (x = 169..188): Swap Button sits at (172, 104, 16, 20)
    # Subtle engraved industrial directional guide chevrons above (y=93..97) & below (y=129..133)
    # Centered on x=176..183 (midpoint 179.5 matching swap button center 179.5)
    c_arr_amb = rgba("#B86E1D")
    c_arr_amb_hi = rgba("#DFC8A8")
    for ax in range(176, 182):
        px[ax, 95] = c_arr_amb
        px[ax, 96] = c_arr_amb_hi
    for ax, ay in ((180, 93), (181, 94), (182, 95), (181, 96), (180, 97)):
        px[ax, ay] = c_arr_amb

    c_arr_cyn = rgba("#1E7F96")
    c_arr_cyn_hi = rgba("#B7DCE6")
    for ax in range(178, 184):
        px[ax, 131] = c_arr_cyn
        px[ax, 132] = c_arr_cyn_hi
    for ax, ay in ((179, 129), (178, 130), (177, 131), (178, 132), (179, 133)):
        px[ax, ay] = c_arr_cyn

    # Right Zone: Storage (27..53, 3x9 @ x = 190 + col*18, y = 87 + row*18)
    # Baked cool cyan indicator bar at (189, 85, 162, 1)
    hline(px, 189, 350, 85, rgba("#1A92AD"))
    hline(px, 190, 349, 85, rgba("#25ACC9"))
    px[189, 85] = rgba("#116478")
    px[350, 85] = rgba("#116478")
    for row in range(3):
        for col in range(9):
            _draw_slot(
                px,
                190 + col * 18,
                87 + row * 18,
                bg=rgba("#889198"),
                dk=rgba("#33414A"),
                lt=rgba("#E7F1F5"),
                mid=rgba("#B2BFC6"),
            )
    # Subtle cool cyan-tinted inner recess border around Storage 3x9 perimeter (189..350, 86..139)
    hline(px, 189, 349, 86, rgba("#1D4756"))
    vline(px, 189, 86, 138, rgba("#1D4756"))
    hline(px, 190, 350, 139, rgba("#CEEAF2"))
    vline(px, 350, 87, 139, rgba("#CEEAF2"))
    # Subtle bottom cyan accent hairline at (189, 140, 162, 1)
    hline(px, 189, 350, 140, rgba("#5B94A4"))

    # -------------------------------------------------------------------------
    # Player Inventory (centered beneath both zones: x = 99 + col*18)
    # -------------------------------------------------------------------------
    for row in range(3):
        for col in range(9):
            _draw_slot(px, 99 + col * 18, 153 + row * 18)
    for col in range(9):
        _draw_slot(px, 99 + col * 18, 211)

    # -------------------------------------------------------------------------
    # Right Power & Upgrade Column (x = 356..388, IDENTICAL on both machines)
    # -------------------------------------------------------------------------
    # EU Gauge Frame (362, 13, 18, 42) -> fill (363, 14, 16, 40)
    _draw_lcd(px, 362, 13, 18, 42)
    # Subtle EU scale tick marks inside dark gauge (every 4px = 10%)
    for ty in range(18, 53, 4):
        hline(px, 364, 367, ty, rgba("#2C3B4D"))
        hline(px, 374, 377, ty, rgba("#2C3B4D"))
    # Unlit EU bolt at (366, 57, 10, 14)
    _draw_eu_bolt(px, 366, 57, lit=False)

    # Single-Use Battery Slot (363, 76) -> frame 362..379, 75..92
    _draw_slot(px, 363, 76)
    bat_wm = [
        "................",
        "......####......",
        ".....######.....",
        "....########....",
        "....#++++++#....",
        "....#+####+#....",
        "....#++##++#....",
        "....#++++++#....",
        "....#+####+#....",
        "....#++++++#....",
        "....#+####+#....",
        "....#++++++#....",
        "....########....",
        "................",
        "................",
        "................",
    ]
    _draw_watermark(px, 363, 76, bat_wm, rgba("#7B8088"), rgba("#636870"))

    # 4 Machine Upgrade Slots (363, 102/120/138/156)
    upg_wm = [
        "................",
        "...##########...",
        "..##++++++++##..",
        "..#+########+#..",
        "..#+#......#+#..",
        "..#+#..++..#+#..",
        "..#+#.++++.#+#..",
        "..#+#..++..#+#..",
        "..#+#......#+#..",
        "..#+########+#..",
        "..############..",
        "...##..##..##...",
        "...##..##..##...",
        "................",
        "................",
        "................",
    ]
    for uy in (102, 120, 138, 156):
        _draw_slot(px, 363, uy)
        _draw_watermark(px, 363, uy, upg_wm, rgba("#7D828A"), rgba("#676C74"))

    # Bottom-right recessed status badge wells:
    # Chunk-Loader Badge well (362, 179, 18, 18) -> active badge (363, 180, 16, 16)
    # Vehicle/Port Ready Badge well (362, 199, 18, 18) -> active/warn badge (363, 200, 16, 16)
    _draw_lcd(px, 362, 179, 18, 18)
    _draw_lcd(px, 362, 199, 18, 18)
    # Subtle dim chunk-grid watermark in (363, 180)
    for gx in (366, 370, 374):
        for gy in (183, 187, 191):
            fill_rect(px, gx, gy, gx + 1, gy + 1, rgba("#24333C"))

    if not is_cargo_station:
        anchor_wm = [
            "................",
            ".......##.......",
            "......#..#......",
            ".......##.......",
            "....########....",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            "...#...##...#...",
            "...##..##..##...",
            "....###++###....",
            "......####......",
            "................",
            "................",
            "................",
            "................",
        ]
        _draw_watermark(px, 363, 200, anchor_wm, rgba("#2E485C"), rgba("#213240"))
    else:
        _draw_watermark(px, 363, 200, drone_wm, rgba("#2E485C"), rgba("#213240"))

    # =========================================================================
    # DYNAMIC OVERLAY SPRITES in unused right margin (u = 396..444)
    # =========================================================================

    # 1) Lit EU Energy Column Fill: UV (396, 0, 16, 40)
    for dy in range(40):
        frac = 1.0 - (dy / 39.0)
        if frac > 0.72:
            c_l, c_m, c_r = rgba("#FFF066"), rgba("#FFCC29"), rgba("#D98C14")
        elif frac > 0.38:
            c_l, c_m, c_r = rgba("#FFBA38"), rgba("#FF841A"), rgba("#C94A0E")
        else:
            c_l, c_m, c_r = rgba("#FF7838"), rgba("#E83B17"), rgba("#9E1E0B")
        if dy % 4 == 3:
            hline(px, 396, 411, dy, rgba("#5E1C0A"))
        else:
            hline(px, 396, 411, dy, c_m)
            px[396, dy] = c_r
            px[397, dy] = c_l
            px[398, dy] = c_l
            px[411, dy] = c_r

    # 2) Lit EU Lightning Bolt: UV (414, 0, 10, 14)
    _draw_eu_bolt(px, 414, 0, lit=True)

    # 3) Active Vehicle / Port Ready Badge: UV (396, 44, 16, 16)
    fill_rect(px, 396, 44, 411, 59, rgba("#132633"))
    if not is_cargo_station:
        _draw_watermark(px, 396, 44, [
            "................",
            ".......++.......",
            "......+..+......",
            ".......++.......",
            "....++++++++....",
            ".......++.......",
            ".......++.......",
            ".......++.......",
            "...+...++...+...",
            "...++..++..++...",
            "....+++##+++....",
            "......####......",
            "................",
            "................",
            "................",
            "................",
        ], rgba("#3CEEFF"), rgba("#BDFBFF"))
    else:
        _draw_watermark(px, 396, 44, drone_wm, rgba("#C2FDFF"), rgba("#30E4FF"))

    # 4) Warning / Idle Badge: UV (414, 44, 16, 16)
    fill_rect(px, 414, 44, 429, 59, rgba("#2B1F10"))
    _draw_watermark(px, 414, 44, [
        "................",
        ".......##.......",
        "......####......",
        "......#++#......",
        ".....##++##.....",
        ".....##++##.....",
        "....###++###....",
        "....###++###....",
        "...####++####...",
        "...##########...",
        "..#####++#####..",
        "..#####++#####..",
        ".##############.",
        "................",
        "................",
        "................",
    ], rgba("#FFF4B8"), rgba("#F0A422"))

    # 5) Chunk-Loader Active Badge: UV (396, 64, 16, 16)
    fill_rect(px, 396, 64, 411, 79, rgba("#0F2420"))
    for gx in (399, 403, 407):
        for gy in (67, 71, 75):
            col = rgba("#A8FFEB") if (gx == 403 and gy == 71) else rgba("#24D9AC")
            fill_rect(px, gx, gy, gx + 1, gy + 1, col)

    # 6) Swap Arrows Icon: UV (414, 64, 12, 10) — transparent BG for button overlay
    _draw_swap_arrows_sprite(px, 414, 64)

    return im


def _verify_terminal_gui_layout_non_overlapping():
    """Verify mathematically that every Operation Terminal GUI panel region and atlas sprite has ZERO overlaps."""
    panel_rects = [
        ("title_label", 8, 5, 174, 8),
        ("eu_lcd", 186, 4, 126, 11),
        ("header_seam", 7, 17, 306, 2),
        ("search_box", 8, 20, 236, 14),
        ("refresh_btn", 248, 20, 64, 14),
        ("toolbar_seam", 7, 36, 306, 1),
        ("list_viewport", 8, 38, 296, 166),
        ("scrollbar_track", 306, 38, 6, 166),
        ("footer_lcd", 8, 207, 304, 12),
    ]
    for name, x, y, w, h in panel_rects:
        assert x >= 3 and y >= 3 and x + w <= 317 and y + h <= 221, (
            f"Terminal panel element {name} ({x},{y},{w},{h}) exceeds safe interior (3..316, 3..220)"
        )
    for i, (n1, x1, y1, w1, h1) in enumerate(panel_rects):
        for n2, x2, y2, w2, h2 in panel_rects[i + 1:]:
            overlap_x = x1 < x2 + w2 and x2 < x1 + w1
            overlap_y = y1 < y2 + h2 and y2 < y1 + h1
            assert not (overlap_x and overlap_y), (
                f"Terminal GUI overlap between {n1} ({x1},{y1},{w1},{h1}) and {n2} ({x2},{y2},{w2},{h2})"
            )

    # Verify 6 visible rows fit strictly inside list_viewport interior (9..302, 39..202)
    for row_idx in range(6):
        rx, ry, rw, rh = 10, 40 + row_idx * 27, 292, 25
        assert rx >= 9 and rx + rw <= 303 and ry >= 39 and ry + rh <= 203, (
            f"Terminal row {row_idx} ({rx},{ry},{rw},{rh}) clips list_viewport interior"
        )

    atlas_rects = [
        ("main_panel", 0, 0, 320, 224),
        ("badge_smart_port", 328, 0, 14, 14),
        ("badge_cargo_station", 344, 0, 14, 14),
        ("badge_comm_base", 360, 0, 14, 14),
        ("led_online", 328, 18, 8, 8),
        ("led_unpowered", 338, 18, 8, 8),
        ("led_offline", 348, 18, 8, 8),
        ("led_error", 358, 18, 8, 8),
        ("eu_fill", 328, 30, 124, 9),
        ("unbind_icon", 328, 44, 10, 10),
        ("unbind_icon_hover", 328, 56, 10, 10),
        ("scrollbar_thumb", 344, 44, 6, 24),
        ("connect_icon", 358, 44, 10, 10),
        ("readonly_icon", 370, 44, 10, 10),
    ]
    for name, x, y, w, h in atlas_rects:
        assert x >= 0 and y >= 0 and x + w <= 512 and y + h <= 256, (
            f"Terminal atlas sprite {name} ({x},{y},{w},{h}) exceeds 512x256"
        )
    for i, (n1, x1, y1, w1, h1) in enumerate(atlas_rects):
        for n2, x2, y2, w2, h2 in atlas_rects[i + 1:]:
            overlap_x = x1 < x2 + w2 and x2 < x1 + w1
            overlap_y = y1 < y2 + h2 and y2 < y1 + h1
            assert not (overlap_x and overlap_y), (
                f"Terminal atlas overlap between {n1} and {n2}"
            )


def _draw_status_led_8x8(px, u0: int, v0: int, c_hi, c_mid, c_dk, c_rim):
    """Draw a crisp 8x8 industrial indicator LED orb in a dark steel bezel."""
    grid = [
        "..oooo..",
        ".oRddRo.",
        "oRdHHMRo",
        "oRdHMMRo",
        "oRdMMMRo",
        "oRddddRo",
        ".oRRRRo.",
        "..oooo..",
    ]
    pal = {
        "o": rgba("#141922"),
        "R": c_rim,
        "d": c_dk,
        "M": c_mid,
        "H": c_hi,
    }
    for dy, row in enumerate(grid):
        for dx, ch in enumerate(row):
            if ch != ".":
                px[u0 + dx, v0 + dy] = pal[ch]


def _draw_badge_14x14(px, u0: int, v0: int, bg, border_tl, border_br, grid: list[str], pal: dict):
    """Draw a crisp 14x14 recessed industrial badge icon with 1px beveled frame."""
    fill_rect(px, u0, v0, u0 + 13, v0 + 13, bg)
    hline(px, u0, u0 + 12, v0, border_tl)
    vline(px, u0, v0, v0 + 12, border_tl)
    hline(px, u0 + 1, u0 + 13, v0 + 13, border_br)
    vline(px, u0 + 13, v0 + 1, v0 + 13, border_br)
    px[u0 + 13, v0] = bg
    px[u0, v0 + 13] = bg
    assert len(grid) == 12 and all(len(r) == 12 for r in grid)
    for dy, row in enumerate(grid):
        for dx, ch in enumerate(row):
            if ch != ".":
                px[u0 + 1 + dx, v0 + 1 + dy] = pal[ch]


def make_gui_operation_terminal() -> Image.Image:
    """512x256 RGBA atlas for Operation Terminal selection screen (320x224 panel + right-margin sprites)."""
    _verify_terminal_gui_layout_non_overlapping()

    im = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    px = im.load()

    # Exact IC2 beveled steel panel matching smart_port.png & cargo_station.png
    PW, PH = 320, 224
    c_out = rgba("#1E232B")
    c_hi = rgba("#F2F4F7")
    c_bg = rgba("#C6C6C6")
    c_bg2 = rgba("#C0C2C6")
    c_sh = rgba("#555B66")
    c_seam = rgba("#9BA0A8")
    c_seam_hi = rgba("#E4E7EC")

    fill_rect(px, 0, 0, PW - 1, PH - 1, c_out)
    for cx, cy in ((0, 0), (PW - 1, 0), (0, PH - 1), (PW - 1, PH - 1)):
        px[cx, cy] = (0, 0, 0, 0)
    fill_rect(px, 1, 1, PW - 2, PH - 2, c_bg)
    hline(px, 1, PW - 3, 1, c_hi)
    hline(px, 1, PW - 4, 2, c_hi)
    vline(px, 1, 1, PH - 3, c_hi)
    vline(px, 2, 1, PH - 4, c_hi)
    hline(px, 2, PW - 2, PH - 2, c_sh)
    hline(px, 3, PW - 2, PH - 3, c_sh)
    vline(px, PW - 2, 2, PH - 2, c_sh)
    vline(px, PW - 3, 3, PH - 2, c_sh)
    for y in range(4, PH - 3, 2):
        hline(px, 3, PW - 4, y, c_bg2)
    # Corner rivets (clear of header/footer LCDs)
    for rx, ry in ((4, 4), (PW - 6, 4), (4, PH - 6), (PW - 6, PH - 6)):
        px[rx, ry] = rgba("#707680")
        px[rx + 1, ry + 1] = c_hi

    # 1) Top Title Header Strip (y=0..16):
    # Right-side Terminal EU/FE LCD mini-bar frame at (186, 4, 126, 11) -> inner fill (187, 5, 124, 9)
    _draw_lcd(px, 186, 4, 126, 11)
    # Subtle 10% vertical scale ticks inside dark EU mini-bar
    for tick_i in range(1, 10):
        tx = 187 + round(tick_i * 124 / 10)
        vline(px, tx, 6, 12, rgba("#253545"))
    # Bevel seam below header (y=17..18)
    hline(px, 7, 312, 17, c_seam)
    hline(px, 7, 312, 18, c_seam_hi)

    # 2) Control Toolbar (y=19..36):
    # Search EditBox recessed dark well at (8, 20, 236, 14)
    _draw_lcd(px, 8, 20, 236, 14)
    # Subtle teal focus accent strip on left interior edge of search bar
    vline(px, 9, 21, 32, rgba("#1D6F7D"))
    # Refresh button area (248, 20, 64, 14) stays clean steel panel surface for vanilla Button
    # Horizontal seam below toolbar (y=36)
    hline(px, 7, 312, 36, c_seam)

    # 3) Dark Recessed Scrollable Station List Viewport (8, 38, 296, 166)
    vx0, vy0, vx1, vy1 = 8, 38, 8 + 296 - 1, 38 + 166 - 1
    fill_rect(px, vx0, vy0, vx1, vy1, rgba("#11171F"))
    hline(px, vx0, vx1 - 1, vy0, rgba("#090D12"))
    vline(px, vx0, vy0, vy1 - 1, rgba("#090D12"))
    hline(px, vx0 + 1, vx1, vy1, rgba("#DCE2EA"))
    vline(px, vx1, vy0 + 1, vy1, rgba("#DCE2EA"))
    px[vx1, vy0] = c_seam
    px[vx0, vy1] = c_seam
    # Ultra-subtle industrial horizontal scanline grain inside list viewport
    for y in range(vy0 + 2, vy1 - 1, 2):
        hline(px, vx0 + 1, vx1 - 1, y, rgba("#151D27"))

    # 4) Vertical Scrollbar Track (306, 38, 6, 166)
    sx0, sy0, sx1, sy1 = 306, 38, 306 + 6 - 1, 38 + 166 - 1
    fill_rect(px, sx0, sy0, sx1, sy1, rgba("#151C24"))
    hline(px, sx0, sx1 - 1, sy0, rgba("#373B42"))
    vline(px, sx0, sy0, sy1 - 1, rgba("#373B42"))
    hline(px, sx0 + 1, sx1, sy1, rgba("#EEF1F5"))
    vline(px, sx1, sy0 + 1, sy1, rgba("#EEF1F5"))
    vline(px, sx0 + 2, sy0 + 2, sy1 - 2, rgba("#0D1218"))
    vline(px, sx0 + 3, sy0 + 2, sy1 - 2, rgba("#0D1218"))

    # 5) Bottom Footer Status LCD (8, 207, 304, 12)
    _draw_lcd(px, 8, 207, 304, 12)
    vline(px, 9, 208, 217, rgba("#1F8B8A"))
    px[9, 210] = rgba("#3CF2B8")
    px[9, 211] = rgba("#4CE8FF")

    # =========================================================================
    # RIGHT-MARGIN DYNAMIC SPRITES (u = 328..452)
    # =========================================================================

    # 1) Smart Port Badge Icon (14x14) at UV (328, 0, 14, 14) — maritime blue anchor
    _draw_badge_14x14(
        px, 328, 0,
        bg=rgba("#132336"),
        border_tl=rgba("#0A121C"),
        border_br=rgba("#2D5D96"),
        grid=[
            ".....HH.....",
            "....H..H....",
            ".....CC.....",
            "..CCCCCCCC..",
            ".....CC.....",
            ".....CC.....",
            ".....CC.....",
            ".B...CC...B.",
            ".BB..CC..BB.",
            "..BBBHHBBB..",
            "....BBBB....",
            "............",
        ],
        pal={
            "H": rgba("#C2FAFF"),
            "C": rgba("#4CE8FF"),
            "B": rgba("#2B74CC"),
        },
    )

    # 2) Cargo Station Badge Icon (14x14) at UV (344, 0, 14, 14) — amber quad-rotor drone
    _draw_badge_14x14(
        px, 344, 0,
        bg=rgba("#261D12"),
        border_tl=rgba("#140E08"),
        border_br=rgba("#9E681E"),
        grid=[
            ".AA......AA.",
            ".AH......HA.",
            "..A..AA..A..",
            "...A.HH.A...",
            "....AHHA....",
            "...AAHHAA...",
            "...AAHHAA...",
            "....AAAA....",
            "...A.OO.A...",
            "..A..OO..A..",
            ".AO......OA.",
            ".AA......AA.",
        ],
        pal={
            "H": rgba("#FFF0A6"),
            "A": rgba("#F2A429"),
            "O": rgba("#C46E18"),
        },
    )

    # 3) Comm Base Station Badge Icon (14x14) at UV (360, 0, 14, 14) — cyan RF antenna
    _draw_badge_14x14(
        px, 360, 0,
        bg=rgba("#102428"),
        border_tl=rgba("#091418"),
        border_br=rgba("#218A87"),
        grid=[
            "..C......C..",
            ".C..C..C..C.",
            ".C.C.HH.C.C.",
            ".C..CHHC..C.",
            "..C..GG..C..",
            ".....GG.....",
            "....SGGS....",
            ".....GG.....",
            "....SSSS....",
            "...SSGGSS...",
            "..SSSSSSSS..",
            "............",
        ],
        pal={
            "H": rgba("#D4FFF5"),
            "C": rgba("#4CE8FF"),
            "G": rgba("#3CF2B8"),
            "S": rgba("#8EA2B8"),
        },
    )

    # 4) Status LEDs (8x8) at v=18:
    # Online green (328, 18, 8, 8)
    _draw_status_led_8x8(px, 328, 18, rgba("#D4FFE4"), rgba("#3CF285"), rgba("#168F4D"), rgba("#2E443B"))
    # Unpowered amber (338, 18, 8, 8)
    _draw_status_led_8x8(px, 338, 18, rgba("#FFF2B2"), rgba("#F5A623"), rgba("#9E5E0E"), rgba("#4A3B24"))
    # Offline / unloaded gray-blue (348, 18, 8, 8)
    _draw_status_led_8x8(px, 348, 18, rgba("#B4C6D8"), rgba("#667D96"), rgba("#3B4B5E"), rgba("#2B3542"))
    # Missing / error red (358, 18, 8, 8)
    _draw_status_led_8x8(px, 358, 18, rgba("#FFC2C6"), rgba("#EB3B46"), rgba("#8F1820"), rgba("#47262A"))

    # 5) Terminal EU Gauge Fill (124x9) at UV (328, 30, 124, 9) — cyan-green segmented bar
    for dx in range(124):
        frac = dx / 123.0
        # Smooth cyan-green to radiant cyan energy gradient
        r = int(42 + frac * 34)
        g = int(235 - frac * 10)
        b = int(172 + frac * 83)
        c_top = (min(255, r + 90), min(255, g + 20), min(255, b + 40), 255)
        c_mid = (r, g, b, 255)
        c_low = (max(0, r - 24), max(0, g - 65), max(0, b - 40), 255)
        c_bot = (max(0, r - 34), max(0, g - 110), max(0, b - 70), 255)
        is_seg = (dx % 6 == 5) and dx < 123
        for dy in range(9):
            if is_seg:
                px[328 + dx, 30 + dy] = rgba("#11474A")
            elif dy == 0:
                px[328 + dx, 30 + dy] = c_top
            elif dy in (1, 2):
                px[328 + dx, 30 + dy] = c_top if dy == 1 else c_mid
            elif dy in (3, 4, 5, 6):
                px[328 + dx, 30 + dy] = c_mid
            elif dy == 7:
                px[328 + dx, 30 + dy] = c_low
            else:
                px[328 + dx, 30 + dy] = c_bot

    # 6) Unbind Trash / X Icon (10x10) at UV (328, 44, 10, 10) + Hover Variant at (328, 56, 10, 10)
    x_grid = [
        "..........",
        ".oo....oo.",
        ".oRo..oRo.",
        "..oRooRo..",
        "...oRRo...",
        "...oRRo...",
        "..oRooRo..",
        ".oRo..oRo.",
        ".oo....oo.",
        "..........",
    ]
    for v_off, col_r, col_o in (
        (44, rgba("#C9545C"), rgba("#2E181B")),
        (56, rgba("#FF6B74"), rgba("#4A1419")),
    ):
        for dy, row in enumerate(x_grid):
            for dx, ch in enumerate(row):
                if ch == "R":
                    px[328 + dx, v_off + dy] = col_r
                elif ch == "o":
                    px[328 + dx, v_off + dy] = col_o

    # 7) Scrollbar Thumb (6x24) at UV (344, 44, 6, 24)
    tx0, ty0, tx1, ty1 = 344, 44, 349, 67
    fill_rect(px, tx0, ty0, tx1, ty1, rgba("#8F9AA8"))
    hline(px, tx0, tx1 - 1, ty0, rgba("#E4EAF2"))
    vline(px, tx0, ty0, ty1 - 1, rgba("#D5DDE8"))
    hline(px, tx0 + 1, tx1, ty1, rgba("#48515E"))
    vline(px, tx1, ty0 + 1, ty1, rgba("#48515E"))
    px[tx1, ty0] = rgba("#8F9AA8")
    px[tx0, ty1] = rgba("#8F9AA8")
    # 3 recessed horizontal grip ridges at vertical center
    for gy in (ty0 + 9, ty0 + 12, ty0 + 15):
        hline(px, tx0 + 1, tx1 - 1, gy, rgba("#4A5461"))
        hline(px, tx0 + 1, tx1 - 1, gy + 1, rgba("#CBD4E0"))

    # 8) Optional Bonus Crisp 10x10 Icons (non-overlapping right-margin slots):
    # Connect right-arrow icon at (358, 44, 10, 10)
    conn_grid = [
        "....oo....",
        "....oCo...",
        ".ooooCHo..",
        "oCCCCCHHo.",
        "oCCCCCHHHo",
        "oCCCCCHHo.",
        ".ooooCHo..",
        "....oCo...",
        "....oo....",
        "..........",
    ]
    for dy, row in enumerate(conn_grid):
        for dx, ch in enumerate(row):
            if ch == "o":
                px[358 + dx, 44 + dy] = rgba("#0D262B")
            elif ch == "C":
                px[358 + dx, 44 + dy] = rgba("#3CF2B8")
            elif ch == "H":
                px[358 + dx, 44 + dy] = rgba("#A8FFF4")

    # Remote Read-Only eye badge icon at (370, 44, 10, 10)
    eye_grid = [
        "..........",
        "..oooooo..",
        ".oAAAAAAo.",
        "oAAoCCoAAo",
        "oAoCHHCoAo",
        "oAAoCCoAAo",
        ".oAAAAAAo.",
        "..oooooo..",
        "..........",
        "..........",
    ]
    for dy, row in enumerate(eye_grid):
        for dx, ch in enumerate(row):
            if ch == "o":
                px[370 + dx, 44 + dy] = rgba("#1C170E")
            elif ch == "A":
                px[370 + dx, 44 + dy] = rgba("#F2B233")
            elif ch == "C":
                px[370 + dx, 44 + dy] = rgba("#3CE8FF")
            elif ch == "H":
                px[370 + dx, 44 + dy] = rgba("#E0FFFF")

    return im


# =============================================================================
# 5. VISUAL PREVIEW CONTACT SHEET (4x nearest-neighbor)
# =============================================================================

def generate_preview(out_path: Path, generated: dict[str, Image.Image]):
    scale = 4
    bg = rgba("#1A1F28")
    card_bg = rgba("#262D3B")
    border = rgba("#4A576C")
    text_col = rgba("#E4ECF5")
    hdr_col = rgba("#5CE8FF")

    blocks = [
        "block/smart_port_front", "block/smart_port_front_active",
        "block/smart_port_side", "block/smart_port_top", "block/smart_port_bottom",
        "block/cargo_station_top", "block/cargo_station_top_active",
        "block/cargo_station_side", "block/cargo_station_side_active", "block/cargo_station_bottom",
        "block/comm_base_station_base", "block/comm_base_station_top",
        "block/comm_base_station_front", "block/comm_base_station_front_active",
        "block/comm_base_station_antenna", "block/comm_base_station_antenna_active",
    ]
    items = [
        "item/electric_cargo_boat", "item/transport_drone", "item/heavy_transport_drone",
        "item/rf_chip_1", "item/rf_chip_2", "item/rf_chip_3", "item/chunk_loading_upgrade",
        "item/operation_terminal",
    ]
    entities = [
        "entity/boat/electric_cargo_boat",
        "entity/drone/transport_drone",
        "entity/drone/heavy_transport_drone",
    ]
    guis = ["gui/smart_port", "gui/cargo_station", "gui/operation_terminal"]

    # Layout canvas width = 1180 px, stacked 2x 512x256 GUIs cleanly
    W = 1180
    H = 2780
    sheet = Image.new("RGBA", (W, H), bg)
    draw = ImageDraw.Draw(sheet)

    draw.text((20, 14), "LogisticsCraft2 (lc2) - Complete Original Asset Preview (Nearest-Neighbor)", fill=hdr_col)

    # Section 1: Blocks (4 rows of up to 5)
    y = 42
    draw.text((20, y), "BLOCKS (16x16 @ 4x = 64x64)", fill=hdr_col)
    y += 18
    for idx, key in enumerate(blocks):
        col = idx % 5
        row = idx // 5
        cx = 20 + col * 228
        cy = y + row * 104
        draw.rectangle([cx, cy, cx + 216, cy + 94], fill=card_bg, outline=border)
        tile = generated[key].resize((16 * scale, 16 * scale), Image.NEAREST)
        sheet.alpha_composite(tile, (cx + 12, cy + 15))
        label = key.split("/", 1)[1]
        parts = label.split("_")
        line1 = "_".join(parts[:3]) if parts[0] == "comm" else "_".join(parts[:2])
        line2 = "_".join(parts[3:]) if parts[0] == "comm" else "_".join(parts[2:])
        draw.text((cx + 86, cy + 28), line1, fill=text_col)
        draw.text((cx + 86, cy + 44), line2, fill=rgba("#9FB2C8"))

    # Section 2: Items (1 row of 8)
    y += 428
    draw.text((20, y), "ITEMS (16x16 @ 4x = 64x64)", fill=hdr_col)
    y += 18
    for idx, key in enumerate(items):
        cx = 20 + idx * 143
        cy = y
        draw.rectangle([cx, cy, cx + 135, cy + 106], fill=card_bg, outline=border)
        tile = generated[key].resize((16 * scale, 16 * scale), Image.NEAREST)
        sheet.alpha_composite(tile, (cx + 36, cy + 10))
        label = key.split("/", 1)[1]
        draw.text((cx + 6, cy + 82), label[:20], fill=text_col)

    # Section 3: Entity UV Maps (Boat 128x128 @ 3x = 384x384; Drones 64x64 @ 4x = 256x256)
    y += 124
    draw.text((20, y), "ENTITY UV MAPS (Boat: 128x128 ChestBoatModel @ 3x | Drones: 64x64 CargoDroneModel @ 4x)", fill=hdr_col)
    y += 18
    # Boat
    bx, by = 20, y
    draw.rectangle([bx, by, bx + 408, by + 420], fill=card_bg, outline=border)
    boat_img = generated[entities[0]].resize((128 * 3, 128 * 3), Image.NEAREST)
    sheet.alpha_composite(boat_img, (bx + 12, by + 26))
    draw.text((bx + 12, by + 8), "entity/boat/electric_cargo_boat.png (128x128)", fill=text_col)
    # Standard Drone
    dx1 = bx + 424
    draw.rectangle([dx1, by, dx1 + 348, by + 420], fill=card_bg, outline=border)
    d1_img = generated[entities[1]].resize((64 * 4, 64 * 4), Image.NEAREST)
    sheet.alpha_composite(d1_img, (dx1 + 46, by + 82))
    draw.text((dx1 + 12, by + 8), "entity/drone/transport_drone.png (64x64 @ 4x)", fill=text_col)
    # Heavy Drone
    dx2 = dx1 + 364
    draw.rectangle([dx2, by, dx2 + 348, by + 420], fill=card_bg, outline=border)
    d2_img = generated[entities[2]].resize((64 * 4, 64 * 4), Image.NEAREST)
    sheet.alpha_composite(d2_img, (dx2 + 46, by + 82))
    draw.text((dx2 + 12, by + 8), "entity/drone/heavy_transport_drone.png (64x64 @ 4x)", fill=text_col)

    # Section 4: Machine & Terminal GUIs (512x256 @ 2x = 1024x512 stacked cleanly)
    y += 438
    draw.text((20, y), "GUIs (512x256 atlases @ 2x)", fill=hdr_col)
    y += 18
    for idx, key in enumerate(guis):
        gx = 20
        gy = y + idx * 564
        draw.rectangle([gx, gy, gx + 1140, gy + 548], fill=card_bg, outline=border)
        g_img = generated[key].resize((512 * 2, 256 * 2), Image.NEAREST)
        sheet.alpha_composite(g_img, (gx + 58, gy + 26))
        panel_dim = "320x224 terminal panel" if key == "gui/operation_terminal" else "392x234 2-zone panel"
        draw.text((gx + 16, gy + 8), f"{key}.png (512x256 atlas -> {panel_dim})", fill=text_col)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path)
    print(f"Saved preview contact sheet: {out_path} ({sheet.size[0]}x{sheet.size[1]})")


def generate_gui_preview(out_path: Path, generated: dict[str, Image.Image]):
    """Render a clean 2x nearest-neighbor preview PNG of all three 512x256 LC2 GUIs."""
    bg = rgba("#151A22")
    card_bg = rgba("#222936")
    border = rgba("#47556B")
    text_col = rgba("#E8F0F8")
    sub_col = rgba("#9AB0C7")
    hdr_col = rgba("#5CE8FF")

    W = 1088
    H = 1736
    sheet = Image.new("RGBA", (W, H), bg)
    draw = ImageDraw.Draw(sheet)

    draw.text(
        (24, 14),
        "LogisticsCraft2 (v1.2.0) - All GUI Atlases (512x256 @ 2x Nearest-Neighbor)",
        fill=hdr_col,
    )

    entries = [
        (
            "gui/smart_port",
            "Smart Port (assets/lc2/textures/gui/smart_port.png) - 512x256 -> 392x234 | 0..26 Outbound (Amber) | Swap | 27..53 Storage (Cyan)",
            "Dynamic Sprites (u=396..444):\n- EU Fill (396,0 16x40)\n- EU Bolt (414,0 10x14)\n- Ready Badge (396,44 16x16)\n- Warn Badge (414,44 16x16)\n- Chunk Badge (396,64 16x16)\n- Swap Arrows (414,64 12x10)",
            792,
        ),
        (
            "gui/cargo_station",
            "Cargo Station (assets/lc2/textures/gui/cargo_station.png) - 512x256 -> 392x234 | RF + 6 Drone Bays | 0..26 Outbound | Swap | 27..53 Storage",
            "Dynamic Sprites (u=396..444):\n- EU Fill (396,0 16x40)\n- EU Bolt (414,0 10x14)\n- Ready Badge (396,44 16x16)\n- Warn Badge (414,44 16x16)\n- Chunk Badge (396,64 16x16)\n- Swap Arrows (414,64 12x10)",
            792,
        ),
        (
            "gui/operation_terminal",
            "Operation Terminal (assets/lc2/textures/gui/operation_terminal.png) - 512x256 -> 320x224 | 6-Row Scrollable List + EU Mini-Bar",
            "Dynamic Sprites (u=328..452):\n- Badges 14x14: Port(328,0) Cargo(344,0) Comm(360,0)\n- LEDs 8x8 @ v=18: Online/Warn/Off/Err\n- EU Fill 124x9: (328,30)\n- Unbind 10x10: (328,44)/(328,56)\n- Scroll Thumb 6x24: (344,44)\n- Connect/Eye 10x10: (358,44)/(370,44)",
            656,
        ),
    ]
    for idx, (key, caption, legend, legend_dx) in enumerate(entries):
        cx = 16
        cy = 40 + idx * 562
        draw.rectangle([cx, cy, cx + 1055, cy + 548], fill=card_bg, outline=border)
        draw.text((cx + 16, cy + 9), caption, fill=text_col)
        ax, ay = cx + 16, cy + 28
        draw.rectangle([ax - 1, ay - 1, ax + 1024, ay + 512], fill=rgba("#181E28"), outline=rgba("#384457"))
        g_img = generated[key].resize((512 * 2, 256 * 2), Image.NEAREST)
        sheet.alpha_composite(g_img, (ax, ay))
        draw.text((ax + legend_dx, ay + 180), legend, fill=sub_col)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path)
    print(f"Saved 2x GUI preview: {out_path} ({sheet.size[0]}x{sheet.size[1]})")


def generate_v120_preview(out_path: Path, generated: dict[str, Image.Image]):
    """Render a crisp v1.2.0 contact sheet showing all new Comm Base Station, Operation Terminal & GUI assets."""
    bg = rgba("#141922")
    card_bg = rgba("#1E2532")
    border = rgba("#3F4E63")
    text_col = rgba("#E8F0F8")
    sub_col = rgba("#98AEC6")
    hdr_col = rgba("#4CE8FF")
    acc_col = rgba("#3CF2B8")

    W = 1088
    H = 928
    sheet = Image.new("RGBA", (W, H), bg)
    draw = ImageDraw.Draw(sheet)

    draw.text(
        (20, 14),
        "LogisticsCraft2 v1.2.0 - Comm Base Station & Operation Terminal Original Assets (CC BY-SA 4.0)",
        fill=hdr_col,
    )

    # Row 1: 6 Comm Base Station block textures + 1 Operation Terminal item texture @ 6x (96x96)
    y = 42
    draw.text((20, y), "BLOCK & ITEM TEXTURES (16x16 @ 6x Nearest-Neighbor = 96x96)", fill=acc_col)
    y += 20
    tiles = [
        ("block/comm_base_station_base", "comm_base", "base"),
        ("block/comm_base_station_top", "comm_base", "top"),
        ("block/comm_base_station_front", "comm_base", "front (off)"),
        ("block/comm_base_station_front_active", "comm_base", "front (on)"),
        ("block/comm_base_station_antenna", "comm_base", "antenna (off)"),
        ("block/comm_base_station_antenna_active", "comm_base", "antenna (on)"),
        ("item/operation_terminal", "operation", "terminal (item)"),
    ]
    for idx, (key, l1, l2) in enumerate(tiles):
        cx = 20 + idx * 150
        cy = y
        draw.rectangle([cx, cy, cx + 140, cy + 148], fill=card_bg, outline=border)
        # Checker/dark tile backdrop so transparent pixels pop clearly
        draw.rectangle([cx + 21, cy + 11, cx + 118, cy + 108], fill=rgba("#12171F"), outline=rgba("#344052"))
        tile = generated[key].resize((96, 96), Image.NEAREST)
        sheet.alpha_composite(tile, (cx + 22, cy + 12))
        draw.text((cx + 12, cy + 114), l1, fill=text_col)
        draw.text((cx + 12, cy + 128), l2, fill=sub_col)

    # Row 2: Zoomed 3x Sprite Inspection Strip from gui/operation_terminal.png
    y += 166
    draw.text((20, y), "OPERATION TERMINAL DYNAMIC SPRITES (3x Zoom from Right Margin u=328..452)", fill=acc_col)
    y += 20
    draw.rectangle([20, y, 1068, y + 114], fill=card_bg, outline=border)
    gui_im = generated["gui/operation_terminal"]
    sprite_specs = [
        ("Port", 328, 0, 14, 14),
        ("Cargo", 344, 0, 14, 14),
        ("Comm", 360, 0, 14, 14),
        ("On", 328, 18, 8, 8),
        ("LowPwr", 338, 18, 8, 8),
        ("Off", 348, 18, 8, 8),
        ("Err", 358, 18, 8, 8),
        ("Unbind", 328, 44, 10, 10),
        ("UnbHov", 328, 56, 10, 10),
        ("Thumb", 344, 44, 6, 24),
        ("Conn", 358, 44, 10, 10),
        ("ReadOnly", 370, 44, 10, 10),
    ]
    sx = 34
    for label, u, v, sw, sh in sprite_specs:
        crop = gui_im.crop((u, v, u + sw, v + sh)).resize((sw * 3, sh * 3), Image.NEAREST)
        cy_off = y + 14 + max(0, (72 - sh * 3) // 2)
        draw.rectangle([sx - 3, cy_off - 3, sx + sw * 3 + 2, cy_off + sh * 3 + 2], fill=rgba("#12171F"), outline=rgba("#344052"))
        sheet.alpha_composite(crop, (sx, cy_off))
        draw.text((sx - 2, y + 94), label, fill=sub_col)
        sx += max(sw * 3 + 20, 58)
    # EU Fill 124x9 @ 2x on the right side of the strip
    eu_crop = gui_im.crop((328, 30, 328 + 124, 30 + 9)).resize((248, 18), Image.NEAREST)
    ex, ey = 796, y + 36
    draw.rectangle([ex - 3, ey - 3, ex + 250, ey + 20], fill=rgba("#12171F"), outline=rgba("#344052"))
    sheet.alpha_composite(eu_crop, (ex, ey))
    draw.text((ex, y + 66), "EU Fill 124x9 @ 2x (328,30)", fill=sub_col)

    # Row 3: Operation Terminal GUI Atlas @ 2x (1024x512)
    y += 128
    draw.text((20, y), "OPERATION TERMINAL GUI ATLAS (assets/lc2/textures/gui/operation_terminal.png | 512x256 @ 2x)", fill=acc_col)
    y += 20
    cx, cy = 16, y
    draw.rectangle([cx, cy, cx + 1055, cy + 536], fill=card_bg, outline=border)
    ax, ay = cx + 16, cy + 12
    draw.rectangle([ax - 1, ay - 1, ax + 1024, ay + 512], fill=rgba("#181E28"), outline=rgba("#384457"))
    g2 = gui_im.resize((512 * 2, 256 * 2), Image.NEAREST)
    sheet.alpha_composite(g2, (ax, ay))
    draw.text(
        (ax + 656, ay + 160),
        "Panel: 320x224 at (0,0)\n"
        "Header EU LCD: (186,4,126,11)\n"
        "Search Well: (8,20,236,14)\n"
        "Refresh Area: (248,20,64,14)\n"
        "List Viewport: (8,38,296,166)\n"
        "  6 rows: x=10, y=40+i*27\n"
        "  row inner: 292x25\n"
        "Scrollbar Track: (306,38,6,166)\n"
        "Footer LCD: (8,207,304,12)",
        fill=sub_col,
    )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path)
    print(f"Saved v1.2.0 contact sheet: {out_path} ({sheet.size[0]}x{sheet.size[1]})")


def main():
    parser = argparse.ArgumentParser(description="Generate all LC2 pixel-art textures.")
    parser.add_argument("--output", type=Path, default=TEX_ROOT, help="Output textures directory")
    parser.add_argument("--preview", type=Path, default=None, help="Optional zoomed full contact sheet PNG")
    parser.add_argument("--gui-preview", type=Path, default=None, help="Optional 2x GUI-only preview PNG")
    parser.add_argument("--v120-preview", type=Path, default=None, help="Optional v1.2.0 assets contact sheet PNG")
    parser.add_argument("--gui-only", action="store_true", help="Regenerate only the GUI textures")
    args = parser.parse_args()

    out_root = args.output.resolve()
    generators = {
        "block/smart_port_front": lambda: make_smart_port_front(False),
        "block/smart_port_front_active": lambda: make_smart_port_front(True),
        "block/smart_port_side": make_smart_port_side,
        "block/smart_port_top": make_smart_port_top,
        "block/smart_port_bottom": make_smart_port_bottom,
        "block/cargo_station_top": lambda: make_cargo_station_top(False),
        "block/cargo_station_top_active": lambda: make_cargo_station_top(True),
        "block/cargo_station_side": lambda: make_cargo_station_side(False),
        "block/cargo_station_side_active": lambda: make_cargo_station_side(True),
        "block/cargo_station_bottom": make_cargo_station_bottom,
        "block/comm_base_station_base": make_comm_base_station_base,
        "block/comm_base_station_top": make_comm_base_station_top,
        "block/comm_base_station_front": lambda: make_comm_base_station_front(False),
        "block/comm_base_station_front_active": lambda: make_comm_base_station_front(True),
        "block/comm_base_station_antenna": lambda: make_comm_base_station_antenna(False),
        "block/comm_base_station_antenna_active": lambda: make_comm_base_station_antenna(True),
        "item/electric_cargo_boat": make_item_electric_cargo_boat,
        "item/transport_drone": make_item_transport_drone,
        "item/heavy_transport_drone": make_item_heavy_transport_drone,
        "item/rf_chip_1": lambda: make_item_rf_chip(1),
        "item/rf_chip_2": lambda: make_item_rf_chip(2),
        "item/rf_chip_3": lambda: make_item_rf_chip(3),
        "item/chunk_loading_upgrade": make_item_chunk_loading_upgrade,
        "item/operation_terminal": make_item_operation_terminal,
        "entity/boat/electric_cargo_boat": make_entity_electric_cargo_boat,
        "entity/drone/transport_drone": lambda: make_entity_drone(False),
        "entity/drone/heavy_transport_drone": lambda: make_entity_drone(True),
        "gui/smart_port": lambda: _draw_base_gui(False),
        "gui/cargo_station": lambda: _draw_base_gui(True),
        "gui/operation_terminal": make_gui_operation_terminal,
    }

    generated = {}
    for rel, fn in generators.items():
        if args.gui_only and not rel.startswith("gui/"):
            continue
        im = fn()
        dest = out_root / (rel + ".png")
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.save(dest)
        generated[rel] = im
        print(f"Wrote {dest.relative_to(REPO_ROOT) if REPO_ROOT in dest.parents else dest} ({im.size[0]}x{im.size[1]})")

    if args.preview is not None:
        if args.gui_only:
            raise SystemExit("--preview cannot be combined with --gui-only; use --gui-preview instead")
        generate_preview(args.preview.resolve(), generated)
    if args.gui_preview is not None:
        generate_gui_preview(args.gui_preview.resolve(), generated)
    if args.v120_preview is not None:
        if args.gui_only:
            raise SystemExit("--v120-preview cannot be combined with --gui-only")
        generate_v120_preview(args.v120_preview.resolve(), generated)


if __name__ == "__main__":
    main()
