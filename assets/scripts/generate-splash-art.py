#!/usr/bin/env python3
# usage: python3 assets/scripts/generate-splash-art.py
# circle crops of the concept launcher art for the android 12 splash screen, written next to the sources
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path(__file__).resolve().parents[2] / 'src/res/launcher/concepts/webp'
CANVAS = 432
DIAMETER = 288
SUPERSAMPLE = 4


def circle_art(path: Path) -> Image.Image:
    art = Image.open(path).convert('RGBA')
    if art.size != (CANVAS, CANVAS):
        raise SystemExit(f'{path.name}: expected {CANVAS}x{CANVAS}, got {art.size}')
    offset = (CANVAS - DIAMETER) // 2
    crop = art.crop((offset, offset, offset + DIAMETER, offset + DIAMETER))
    mask = Image.new('L', (DIAMETER * SUPERSAMPLE,) * 2, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, DIAMETER * SUPERSAMPLE - 1, DIAMETER * SUPERSAMPLE - 1), fill=255)
    crop.putalpha(mask.resize((DIAMETER, DIAMETER), Image.LANCZOS))
    return crop


for source in sorted(SRC.glob('icon_bg_concept_*.webp')):
    name = source.stem.removeprefix('icon_bg_concept_')
    circle_art(source).save(SRC / f'inu_splash_art_{name}.webp', lossless=True, method=6)
    print(f'inu_splash_art_{name}.webp')
