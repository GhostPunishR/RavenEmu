#!/usr/bin/env python3
"""Rend les previews de skin à partir des VectorDrawable réellement embarqués."""

from __future__ import annotations

import html
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAWABLES = ROOT / "input/src/main/res/drawable"
OUTPUT = ROOT / "docs/skins/previews"
ANDROID = "{http://schemas.android.com/apk/res/android}"
CANVAS_WIDTH = 1000
CANVAS_HEIGHT = 2160


GB_CONTROLS = {
    "DPAD": (60, 1281, 370, 1591),
    "BUTTON_A": (720, 1265, 890, 1435),
    "BUTTON_B": (565, 1427, 735, 1597),
    "SELECT": (325, 1782, 485, 1933),
    "START": (515, 1782, 675, 1933),
    "MENU": (415, 961, 585, 1177),
}

GBA_CONTROLS = {
    "DPAD": (60, 1249, 370, 1559),
    "BUTTON_A": (720, 1233, 890, 1403),
    "BUTTON_B": (565, 1405, 735, 1575),
    "SELECT": (325, 1782, 485, 1933),
    "START": (515, 1782, 675, 1933),
    "MENU": (415, 810, 585, 1026),
    "BUTTON_L": (55, 842, 295, 994),
    "BUTTON_R": (705, 842, 945, 994),
}

FACE = {
    "DPAD": "raven_control_dpad",
    "BUTTON_A": "raven_control_button_a",
    "BUTTON_B": "raven_control_button_b",
    "SELECT": "raven_control_select",
    "START": "raven_control_start",
    "MENU": "raven_control_menu",
    "BUTTON_L": "raven_control_button_l",
    "BUTTON_R": "raven_control_button_r",
}

LABEL = {
    "BUTTON_A": "A",
    "BUTTON_B": "B",
    "SELECT": "SELECT",
    "START": "START",
    "MENU": "MENU",
    "BUTTON_L": "L",
    "BUTTON_R": "R",
}


def color(value: str | None) -> tuple[str, str | None]:
    if not value or value == "@android:color/transparent":
        return "none", None
    if value.startswith("#") and len(value) == 9:
        alpha = int(value[1:3], 16) / 255
        return f"#{value[3:]}", f"{alpha:.4f}"
    return value, None


def vector_paths(name: str) -> tuple[float, float, str]:
    root = ET.parse(DRAWABLES / f"{name}.xml").getroot()
    viewport_width = float(root.attrib[f"{ANDROID}viewportWidth"])
    viewport_height = float(root.attrib[f"{ANDROID}viewportHeight"])
    rendered: list[str] = []
    for path in root.findall("path"):
        attributes: list[str] = [
            f'd="{html.escape(path.attrib[f"{ANDROID}pathData"], quote=True)}"'
        ]
        fill, fill_opacity = color(path.attrib.get(f"{ANDROID}fillColor"))
        attributes.append(f'fill="{fill}"')
        if fill_opacity:
            attributes.append(f'fill-opacity="{fill_opacity}"')
        stroke, stroke_opacity = color(path.attrib.get(f"{ANDROID}strokeColor"))
        if stroke != "none":
            attributes.append(f'stroke="{stroke}"')
            attributes.append(
                f'stroke-width="{path.attrib.get(f"{ANDROID}strokeWidth", "1")}"'
            )
            if stroke_opacity:
                attributes.append(f'stroke-opacity="{stroke_opacity}"')
        line_cap = path.attrib.get(f"{ANDROID}strokeLineCap")
        if line_cap:
            attributes.append(f'stroke-linecap="{line_cap}"')
        line_join = path.attrib.get(f"{ANDROID}strokeLineJoin")
        if line_join:
            attributes.append(f'stroke-linejoin="{line_join}"')
        fill_type = path.attrib.get(f"{ANDROID}fillType")
        if fill_type == "evenOdd":
            attributes.append('fill-rule="evenodd"')
        rendered.append(f"<path {' '.join(attributes)}/>")
    return viewport_width, viewport_height, "".join(rendered)


def vector_group(name: str, rect: tuple[int, int, int, int]) -> str:
    viewport_width, viewport_height, paths = vector_paths(name)
    left, top, right, bottom = rect
    scale_x = (right - left) / viewport_width
    scale_y = (bottom - top) / viewport_height
    return (
        f'<g transform="translate({left} {top}) scale({scale_x:.7f} {scale_y:.7f})">'
        f"{paths}</g>"
    )


def label(control_id: str, rect: tuple[int, int, int, int]) -> str:
    value = LABEL.get(control_id)
    if not value:
        return ""
    left, top, right, bottom = rect
    center_x = (left + right) / 2
    if control_id in {"BUTTON_A", "BUTTON_B"}:
        y = (top + bottom) / 2 + (bottom - top) * 0.15
        size = (right - left) * 0.40
    elif control_id in {"BUTTON_L", "BUTTON_R"}:
        y = (top + bottom) / 2 + (bottom - top) * 0.13
        size = (bottom - top) * 0.42
    else:
        y = bottom - 7
        size = (right - left) * 0.14
    return (
        f'<text x="{center_x:.1f}" y="{y:.1f}" text-anchor="middle" '
        f'font-family="DejaVu Sans" font-size="{size:.1f}" font-weight="700" '
        'letter-spacing="3" fill="#AA68FF" stroke="#240A3D" stroke-width="2" '
        f'paint-order="stroke">{value}</text>'
    )


def overlay_names(control_id: str) -> tuple[str, str]:
    if control_id == "DPAD":
        return "raven_control_glow_dpad", "raven_control_pressed_dpad"
    if control_id in {"BUTTON_A", "BUTTON_B"}:
        return "raven_control_glow_round", "raven_control_pressed_round"
    if control_id == "MENU":
        return "raven_control_glow_menu", "raven_control_pressed_menu"
    return "raven_control_glow_pill", "raven_control_pressed_pill"


def control(
    control_id: str,
    rect: tuple[int, int, int, int],
    pressed: bool = False,
    dpad_direction: str | None = None,
) -> str:
    left, top, right, bottom = rect
    center_x = (left + right) / 2
    center_y = (top + bottom) / 2
    transform = ""
    if pressed or dpad_direction:
        transform = (
            f"translate({center_x:.1f} {center_y + 7:.1f}) scale(.94) "
            f"translate({-center_x:.1f} {-center_y:.1f})"
        )
    parts: list[str] = []
    glow, pressed_overlay = overlay_names(control_id)
    if pressed or dpad_direction:
        parts.append(f'<g opacity=".92">{vector_group(glow, rect)}</g>')
    parts.append(vector_group(FACE[control_id], rect))
    if pressed or dpad_direction:
        parts.append(f'<g opacity=".78">{vector_group(pressed_overlay, rect)}</g>')
    if control_id == "DPAD" and dpad_direction:
        rotation = {"UP": 0, "RIGHT": 90, "DOWN": 180, "LEFT": 270}[dpad_direction]
        direction = vector_group("raven_control_dpad_direction", rect)
        parts.append(
            f'<g transform="rotate({rotation} {center_x:.1f} {center_y:.1f})">'
            f"{direction}</g>"
        )
    parts.append(label(control_id, rect))
    content = "".join(parts)
    return f'<g transform="{transform}">{content}</g>' if transform else content


def synthetic_game(screen: tuple[int, int, int, int], gba: bool) -> str:
    left, top, right, bottom = screen
    width = right - left
    height = bottom - top
    sky = "#17213D" if gba else "#1D2A32"
    ground = "#251438" if gba else "#26352D"
    accent = "#9E54ED" if gba else "#9AAE72"
    pieces = [
        f'<rect x="{left}" y="{top}" width="{width}" height="{height}" fill="{sky}"/>',
        f'<rect x="{left}" y="{top + height * .66:.1f}" width="{width}" '
        f'height="{height * .34:.1f}" fill="{ground}"/>',
        f'<path d="M{left},{top + height * .68:.1f} '
        f'L{left + width * .20:.1f},{top + height * .40:.1f} '
        f'L{left + width * .38:.1f},{top + height * .68:.1f} '
        f'L{left + width * .62:.1f},{top + height * .30:.1f} '
        f'L{right},{top + height * .68:.1f} Z" fill="#11152A"/>',
        f'<circle cx="{left + width * .78:.1f}" cy="{top + height * .22:.1f}" '
        f'r="{min(width, height) * .08:.1f}" fill="{accent}" opacity=".88"/>',
    ]
    for index in range(9):
        x = left + width * (0.08 + index * 0.105)
        y = top + height * (0.12 + (index % 3) * 0.08)
        pieces.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{max(3, width * .008):.1f}" '
            f'height="{max(3, width * .008):.1f}" fill="#E7DEFF" opacity=".75"/>'
        )
    # Silhouette de corbeau originale et synthétique au premier plan.
    pieces.append(
        f'<path d="M{left + width * .42:.1f},{top + height * .70:.1f} '
        f'C{left + width * .49:.1f},{top + height * .56:.1f} '
        f'{left + width * .62:.1f},{top + height * .55:.1f} '
        f'{left + width * .66:.1f},{top + height * .65:.1f} '
        f'L{left + width * .76:.1f},{top + height * .68:.1f} '
        f'L{left + width * .65:.1f},{top + height * .73:.1f} '
        f'C{left + width * .58:.1f},{top + height * .82:.1f} '
        f'{left + width * .47:.1f},{top + height * .82:.1f} '
        f'{left + width * .42:.1f},{top + height * .70:.1f} Z" fill="#07070D"/>'
    )
    return "".join(pieces)


def skin_svg(
    gba: bool,
    with_game: bool,
    pressed: str | None = None,
    dpad_direction: str | None = None,
) -> str:
    background = "raven_skin_gba_background" if gba else "raven_skin_gb_background"
    screen = (70, 119, 930, 692) if gba else (70, 119, 930, 893)
    controls = GBA_CONTROLS if gba else GB_CONTROLS
    layers: list[str] = [
        '<rect width="1000" height="2160" fill="#030305"/>',
    ]
    if with_game:
        layers.append(synthetic_game(screen, gba))
    else:
        layers.append(
            f'<rect x="{screen[0]}" y="{screen[1]}" width="{screen[2] - screen[0]}" '
            f'height="{screen[3] - screen[1]}" fill="#010102"/>'
        )
    layers.append(vector_group(background, (0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)))
    for control_id, rect in controls.items():
        layers.append(
            control(
                control_id,
                rect,
                pressed=control_id == pressed,
                dpad_direction=dpad_direction if control_id == "DPAD" else None,
            )
        )
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="2160" '
        'viewBox="0 0 1000 2160">'
        '<defs><filter id="softGlow"><feGaussianBlur stdDeviation="8"/></filter></defs>'
        f"{''.join(layers)}</svg>"
    )


def render() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    previews = {
        "raven-gb-empty.svg": skin_svg(gba=False, with_game=False),
        "raven-gb-game.svg": skin_svg(gba=False, with_game=True),
        "raven-gba-empty.svg": skin_svg(gba=True, with_game=False),
        "raven-gba-game.svg": skin_svg(gba=True, with_game=True),
        "raven-gb-a-pressed.svg": skin_svg(
            gba=False,
            with_game=True,
            pressed="BUTTON_A",
        ),
        "raven-gb-dpad-up-pressed.svg": skin_svg(
            gba=False,
            with_game=True,
            dpad_direction="UP",
        ),
        "raven-gba-l-pressed.svg": skin_svg(
            gba=True,
            with_game=True,
            pressed="BUTTON_L",
        ),
    }
    for filename, svg in previews.items():
        (OUTPUT / filename).write_text(svg, encoding="utf-8")


if __name__ == "__main__":
    render()
