#!/usr/bin/env python3
"""Render 512×512 ipanHub launcher icon (concept #3) to PNG."""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 512
BG = "#1A1A1A"
YELLOW = "#FFC107"
SCALE = SIZE / 108.0


def scale(x: float, y: float) -> tuple[float, float]:
    return x * SCALE, y * SCALE


def hex_vertices(cx: float, cy: float, r: float) -> list[tuple[float, float]]:
    return [
        scale(cx + r * math.cos(math.radians(-90 + i * 60)), cy + r * math.sin(math.radians(-90 + i * 60)))
        for i in range(6)
    ]


def main() -> None:
    img = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(img)
    w = max(2, int(3.8 * SCALE))

    outer = hex_vertices(54, 54, 36)
  # Broken stroke at bottom-right (indices 1→2) for music note
    for i in range(6):
        a, b = outer[i], outer[(i + 1) % 6]
        if i == 1:
            bx, by = b
            draw.line([a, (bx - 8 * SCALE, by - 6 * SCALE)], fill=YELLOW, width=w)
        elif i == 2:
            ax, ay = a
            draw.line([(ax + 4 * SCALE, ay + 4 * SCALE), b], fill=YELLOW, width=w)
        else:
            draw.line([a, b], fill=YELLOW, width=w)

    inner = hex_vertices(54, 54, 16)
    wi = max(1, int(2 * SCALE))
    for i in range(6):
        draw.line([inner[i], inner[(i + 1) % 6]], fill=YELLOW, width=wi)

    dot_r = max(2, int(2.8 * SCALE))
    for x, y in inner:
        draw.ellipse([x - dot_r, y - dot_r, x + dot_r, y + dot_r], fill=YELLOW)

    tri = [scale(47, 47), scale(47, 61), scale(62, 54)]
    draw.polygon(tri, fill=YELLOW)

    # Eighth-note pair (BR)
    hx, hy = scale(76, 74)
    hr = max(3, int(3.2 * SCALE))
    draw.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=YELLOW)
    sx, sy = scale(78.5, 74)
    tx, ty = scale(78.5, 58)
    draw.line([(sx, sy), (tx, ty)], fill=YELLOW, width=max(2, int(2.2 * SCALE)))

    hx2, hy2 = scale(84, 76)
    hr2 = max(2, int(2.8 * SCALE))
    draw.ellipse([hx2 - hr2, hy2 - hr2, hx2 + hr2, hy2 + hr2], fill=YELLOW)
    sx2, sy2 = scale(85.5, 76)
    tx2, ty2 = scale(85.5, 62)
    draw.line([(sx2, sy2), (tx2, ty2)], fill=YELLOW, width=max(2, int(2 * SCALE)))
    draw.line([scale(78.5, 58), scale(88, 52)], fill=YELLOW, width=max(2, int(2 * SCALE)))
    draw.line([scale(85.5, 62), scale(93, 57)], fill=YELLOW, width=max(2, int(1.8 * SCALE)))

    out = Path("/opt/cursor/artifacts/ipanhub-icon.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, "PNG")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()