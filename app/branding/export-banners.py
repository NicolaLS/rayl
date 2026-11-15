#!/usr/bin/env python3
"""Export Rayl's social media banners from rails.svg; same tools as export-icons.py.

The banner shows the icon's rails seen from afar, with the wordmark beside them, so it
continues the avatar instead of repeating it. The text uses SF Pro, so run this on macOS.
"""

from copy import deepcopy
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw, ImageFont


BRANDING = Path(__file__).resolve().parent
OUT = BRANDING / "social"
ICON = BRANDING.parent.parent / "distribution/store-listing/icon.png"
SVG = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", "http://www.w3.org/2000/svg")
ET.register_namespace("inkscape", "http://www.inkscape.org/namespaces/inkscape")
SOURCE = ET.parse(BRANDING / "rails.svg").getroot()
SF_PRO = "/System/Library/Fonts/SFNS.ttf"
# 3:1 is the X banner; Nostr clients such as Ditto crop it a little wider.
W, H = 1500, 500
SCALE = 2


def banner_art(transform, rail_opacity):
    svg = deepcopy(SOURCE)
    svg.attrib.update(width=str(W), height=str(H), viewBox=f"0 0 {W} {H}")
    svg.find(f".//{SVG}clipPath[@id='canvas-clip']/{SVG}rect").attrib.update(
        width=str(W), height=str(H)
    )
    svg.find(f".//{SVG}linearGradient[@id='background-gradient']").attrib.update(
        x1="0", y1="0", x2=str(W), y2=str(H)
    )
    svg.find(f".//{SVG}radialGradient[@id='background-ambient']").attrib.update(
        cx="380", cy="60", r="1100"
    )
    art = svg.find(f"{SVG}g[@id='artwork']")
    for rect in art.find(f"{SVG}g[@id='background']").iter(f"{SVG}rect"):
        rect.attrib.update(width=str(W), height=str(H))
    for group_id in ("rails-atmosphere", "rails-symbol", "rails-light-accents"):
        group = art.find(f"{SVG}g[@id='{group_id}']")
        group.set("transform", transform)
        for rect in group.iter(f"{SVG}rect"):
            rect.attrib.update(x="-3000", y="-3000", width="7000", height="7000")
        for path in group.iter(f"{SVG}path"):
            # Lengthen each rail threefold from its tip so it reaches the banner's edge.
            d = path.get("d").split()
            x, y = float(d[1]), float(d[2])
            far = [(x + (float(d[i]) - x) * 3, y + (float(d[i + 1]) - y) * 3) for i in (4, 7)]
            path.set("d", f"M {x:g} {y:g} L {far[0][0]:g} {far[0][1]:g} "
                          f"L {far[1][0]:g} {far[1][1]:g} Z")
    art.find(f"{SVG}g[@id='rails-symbol']").set("opacity", str(rail_opacity))
    data = cairosvg.svg2png(
        bytestring=ET.tostring(svg), output_width=W * SCALE, output_height=H * SCALE
    )
    return Image.open(BytesIO(data)).convert("RGB")


def font(size, weight):
    face = ImageFont.truetype(SF_PRO, size * SCALE)
    face.set_variation_by_axes([100, min(max(size, 17), 96), 400, weight])  # wdth, opsz, GRAD, wght
    return face


def wordmark(image):
    draw = ImageDraw.Draw(image)
    x, y = 1010 * SCALE, 250 * SCALE
    draw.text((x, y), "Rayl", font=font(104, 640), fill=(240, 249, 255), anchor="ls")
    draw.text((x, y + 51 * SCALE), "Pay anything.", font=font(30, 430),
              fill=(150, 186, 226), anchor="ls")
    return image


def profile_preview(banners):
    """Each banner as a Nostr profile shows it: cropped to about 3.46:1, avatar bottom-left."""
    icon = Image.open(ICON).convert("RGB")
    cards = []
    for banner in banners:
        width = 1480
        height = int(width / 3.46)
        banner = banner.resize((width, width // 3), Image.Resampling.LANCZOS)
        top = (banner.height - height) // 2
        card = Image.new("RGB", (width, height + 260), (29, 31, 42))
        card.paste(banner.crop((0, top, width, top + height)), (0, 0))
        size = 290
        mask = Image.new("L", (size, size))
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        ImageDraw.Draw(card).ellipse((44, height - 160, 56 + size, height - 148 + size),
                                     fill=(29, 31, 42))
        card.paste(icon.resize((size, size), Image.Resampling.LANCZOS), (50, height - 154), mask)
        cards.append(card)
    sheet = Image.new("RGB", (cards[0].width, sum(c.height for c in cards) + 40), (10, 10, 14))
    y = 0
    for card in cards:
        sheet.paste(card, (0, y))
        y += card.height + 40
    return sheet


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    banners = {
        # Dim rails that seem to come from behind the avatar; the light point carries it.
        "rayl-banner": wordmark(banner_art(
            "translate(820 190) rotate(-2) scale(0.42) translate(-690 -336)", 0.32)),
        # Brighter and wider rails; louder, and closer to the avatar's own artwork.
        "rayl-banner-bold": wordmark(banner_art(
            "translate(760 175) rotate(4) scale(0.55) translate(-690 -336)", 0.55)),
    }
    for name, image in banners.items():
        image.save(OUT / f"{name}-3000x1000.png", optimize=True)
        image.resize((W, H), Image.Resampling.LANCZOS).save(
            OUT / f"{name}-1500x500.png", optimize=True
        )
    profile_preview(banners.values()).save(OUT / "preview-profile.png", optimize=True)


if __name__ == "__main__":
    main()
