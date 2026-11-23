#!/usr/bin/env python3
"""Rasterize car skip icons for visual verification (matches vector pathData)."""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("/opt/cursor/artifacts")
SIZE = 256
SCALE = SIZE / 24.0
BG = "#1A1A1A"
WHITE = "#FFFFFF"


def bar_left(draw: ImageDraw.ImageDraw, x: float, y0: float, y1: float, w: float) -> None:
    draw.rectangle(
        [x * SCALE, y0 * SCALE, (x + w) * SCALE, y1 * SCALE],
        fill=WHITE,
    )


def bar_right(draw: ImageDraw.ImageDraw, x: float, y0: float, y1: float, w: float) -> None:
    draw.rectangle(
        [x * SCALE, y0 * SCALE, (x + w) * SCALE, y1 * SCALE],
        fill=WHITE,
    )


def export_previous(path: Path) -> None:
    img = Image.new("RGB", (SIZE, SIZE), BG)
    d = ImageDraw.Draw(img)
    bar_left(d, 6, 5, 19, 2.5)
    d.polygon(
        [(16 * SCALE, 19 * SCALE), (8 * SCALE, 12 * SCALE), (16 * SCALE, 5 * SCALE)],
        fill=WHITE,
    )
    img.save(path)
    print(path)


def export_next(path: Path) -> None:
    img = Image.new("RGB", (SIZE, SIZE), BG)
    d = ImageDraw.Draw(img)
    d.polygon(
        [(8 * SCALE, 19 * SCALE), (8 * SCALE, 5 * SCALE), (16 * SCALE, 12 * SCALE)],
        fill=WHITE,
    )
    bar_right(d, 18, 5, 19, 2.5)
    img.save(path)
    print(path)


def export_strip(path: Path) -> None:
    w, h = 420, 140
    img = Image.new("RGB", (w, h), "#2A2A2A")

    def tile(draw_fn, center_x: int) -> None:
        s = 48
        sub = Image.new("RGB", (s, s), BG)
        sd = ImageDraw.Draw(sub)
        draw_fn(sd, s)
        img.paste(sub, (center_x - s // 2, h // 2 - s // 2))

    tile(draw_prev, 70)
    tile(draw_pause, 210)
    tile(draw_next, 350)
    img.save(path)
    print(path)


def draw_prev(sd: ImageDraw.ImageDraw, s: int) -> None:
    k = s / 24.0
    sd.rectangle([6 * k, 5 * k, 8.5 * k, 19 * k], fill=WHITE)
    sd.polygon([(16 * k, 19 * k), (8 * k, 12 * k), (16 * k, 5 * k)], fill=WHITE)


def draw_next(sd: ImageDraw.ImageDraw, s: int) -> None:
    k = s / 24.0
    sd.polygon([(8 * k, 19 * k), (8 * k, 5 * k), (16 * k, 12 * k)], fill=WHITE)
    sd.rectangle([18 * k, 5 * k, 20.5 * k, 19 * k], fill=WHITE)


def draw_pause(sd: ImageDraw.ImageDraw, s: int) -> None:
    k = s / 24.0
    sd.rectangle([7 * k, 5 * k, 10 * k, 19 * k], fill=WHITE)
    sd.rectangle([14 * k, 5 * k, 17 * k, 19 * k], fill=WHITE)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    export_previous(OUT / "car-skip-previous.png")
    export_next(OUT / "car-skip-next.png")
    export_strip(OUT / "car-action-strip-icons.png")