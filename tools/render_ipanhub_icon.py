#!/usr/bin/env python3
"""Render 512×512 ipanHub launcher icon — matches ic_launcher_foreground geometry."""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 512
BG = "#1A1A1A"
YELLOW = "#FFC107"
SCALE = SIZE / 108.0
CX, CY = 54.0, 54.0
R_OUT = 30.0
R_IN = 16.5
STROKE_OUT = 6.5
STROKE_IN = 2.2


def s(x: float, y: float) -> tuple[float, float]:
    return x * SCALE, y * SCALE


def hex_pts(r: float) -> list[tuple[float, float]]:
    return [
        s(CX + r * math.cos(math.radians(-90 + i * 60)), CY + r * math.sin(math.radians(-90 + i * 60)))
        for i in range(6)
    ]


def stroke_segment(draw: ImageDraw.ImageDraw, a, b, width: float) -> None:
    draw.line([a, b], fill=YELLOW, width=max(1, int(width)), joint="curve")


def main() -> None:
    img = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(img)
    ow = STROKE_OUT * SCALE
    iw = STROKE_IN * SCALE

    outer = hex_pts(R_OUT)
    # Gap at vertex 2 (bottom-right): stop edge 1→2 at y=61.5; start edge 2→3 at (73.5, 72.75)
    gap_end = s(79.98, 61.5)
    gap_start = s(73.5, 72.75)
    stroke_segment(draw, outer[5], outer[0], ow)
    stroke_segment(draw, outer[0], outer[1], ow)
    stroke_segment(draw, outer[1], gap_end, ow)
    stroke_segment(draw, gap_start, outer[3], ow)
    stroke_segment(draw, outer[3], outer[4], ow)
    stroke_segment(draw, outer[4], outer[5], ow)

    inner = hex_pts(R_IN)
    for i in range(6):
        stroke_segment(draw, inner[i], inner[(i + 1) % 6], iw)

    dot_r = 2.2 * SCALE
    for x, y in inner:
        draw.ellipse([x - dot_r, y - dot_r, x + dot_r, y + dot_r], fill=YELLOW)

    tri = [s(49, 48), s(49, 60), s(63, 54)]
    draw.polygon(tri, fill=YELLOW)

    # Beamed eighth-note pair
    def oval(cx: float, cy: float, rx: float, ry: float) -> None:
        x, y = s(cx, cy)
        draw.ellipse([x - rx * SCALE, y - ry * SCALE, x + rx * SCALE, y + ry * SCALE], fill=YELLOW)

    oval(71.2, 67.2, 2.3, 1.7)
    oval(75.3, 70.2, 2.1, 1.5)
    sw = max(2, int(2.4 * SCALE))
    draw.line([s(72.2, 65.8), s(72.2, 56.8)], fill=YELLOW, width=sw)
    draw.line([s(76.1, 68.8), s(76.1, 56.8)], fill=YELLOW, width=sw)
    bx0, by0 = s(70.8, 56.8)
    bx1, by1 = s(78.0, 59.6)
    draw.rounded_rectangle([bx0, by0, bx1, by1], radius=int(0.6 * SCALE), fill=YELLOW)

    out = Path("/opt/cursor/artifacts/ipanhub-icon.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, "PNG")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()