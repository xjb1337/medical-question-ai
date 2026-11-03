#!/usr/bin/env python3
"""Regenerate Medical Question AI launcher icons and the review sheet."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = Path("/workspace/images/2026-11-03-mqa-icon-1.png")
DEFAULT_PREVIEW = Path("/workspace/images/2026-11-03-mqa-icon-preview.png")
RES = ROOT / "app" / "src" / "main" / "res"

DENSITY_SCALES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}

ADAPTIVE_DP = 108
LEGACY_DP = 48
FOREGROUND_WIDTH_FRACTION = 0.60
SAFE_ZONE_DP = 66.0
BACKGROUND_POLYNOMIAL_DEGREE = 4
ALPHA_FLOOR = 0.015

ADAPTIVE_XML = """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <background android:drawable=\"@mipmap/ic_launcher_background\" />
    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />
    <monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\" />
</adaptive-icon>
"""


def polynomial_terms(degree: int) -> list[tuple[int, int]]:
    return [
        (x_power, y_power)
        for x_power in range(degree + 1)
        for y_power in range(degree + 1 - x_power)
    ]


def design_matrix(
    x_normalized: np.ndarray,
    y_normalized: np.ndarray,
    terms: list[tuple[int, int]],
) -> np.ndarray:
    columns = [
        np.power(x_normalized, x_power) * np.power(y_normalized, y_power)
        for x_power, y_power in terms
    ]
    return np.stack(columns, axis=-1)


class GradientModel:
    def __init__(
        self,
        width: int,
        height: int,
        terms: list[tuple[int, int]],
        coefficients: np.ndarray,
    ) -> None:
        self.width = width
        self.height = height
        self.terms = terms
        self.coefficients = coefficients

    def predict(self, x: np.ndarray, y: np.ndarray) -> np.ndarray:
        x_normalized = x.astype(np.float32) / max(self.width - 1, 1) * 2.0 - 1.0
        y_normalized = y.astype(np.float32) / max(self.height - 1, 1) * 2.0 - 1.0
        features = design_matrix(x_normalized, y_normalized, self.terms)
        return np.clip(features @ self.coefficients, 0.0, 255.0)


def load_source(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32)


def fit_gradient(source: np.ndarray) -> GradientModel:
    height, width, _ = source.shape
    terms = polynomial_terms(BACKGROUND_POLYNOMIAL_DEGREE)
    step = 6

    y, x = np.mgrid[0:height:step, 0:width:step]
    sample = source[y, x]
    background_pixels = sample.min(axis=2) < 70

    x_normalized = x[background_pixels].astype(np.float64) / (width - 1) * 2.0 - 1.0
    y_normalized = y[background_pixels].astype(np.float64) / (height - 1) * 2.0 - 1.0
    features = design_matrix(x_normalized, y_normalized, terms)
    target = sample[background_pixels].astype(np.float64)
    coefficients = np.linalg.lstsq(features, target, rcond=None)[0]

    residual = features @ coefficients - target
    rmse = float(np.sqrt(np.mean(residual * residual)))
    print(f"Gradient fit: degree {BACKGROUND_POLYNOMIAL_DEGREE}, RMSE {rmse:.2f}/255")
    return GradientModel(width, height, terms, coefficients)


def crop_white_silhouette(source: np.ndarray) -> tuple[int, int, int, int]:
    colored = source.min(axis=2) < 70
    rows = np.flatnonzero(colored.any(axis=1))
    columns = np.flatnonzero(colored.any(axis=0))
    if not len(rows) or not len(columns):
        raise ValueError("Could not find the colored icon silhouette")
    return int(columns[0]), int(rows[0]), int(columns[-1]) + 1, int(rows[-1]) + 1


def extract_glyph(source: np.ndarray, model: GradientModel) -> Image.Image:
    height, width, _ = source.shape
    yy, xx = np.mgrid[0:height, 0:width]
    central = (
        (xx > width * 0.12)
        & (xx < width * 0.88)
        & (yy > height * 0.12)
        & (yy < height * 0.88)
    )
    glyph_core = (source.min(axis=2) > 140) & central
    core_y, core_x = np.nonzero(glyph_core)
    if not len(core_x):
        raise ValueError("Could not locate the white glyph")

    padding = 18
    x0 = max(int(core_x.min()) - padding, 0)
    x1 = min(int(core_x.max()) + padding + 1, width)
    y0 = max(int(core_y.min()) - padding, 0)
    y1 = min(int(core_y.max()) + padding + 1, height)

    crop = source[y0:y1, x0:x1]
    crop_y, crop_x = np.mgrid[y0:y1, x0:x1]
    background = model.predict(crop_x, crop_y)
    white = np.full_like(background, 255.0)
    direction = white - background
    denominator = np.maximum(np.sum(direction * direction, axis=2), 1.0)
    alpha = np.sum((crop - background) * direction, axis=2) / denominator
    alpha = np.clip(alpha, 0.0, 1.0)
    alpha = np.clip((alpha - ALPHA_FLOOR) / (1.0 - ALPHA_FLOOR), 0.0, 1.0)
    alpha[alpha < ALPHA_FLOOR] = 0.0

    support = Image.fromarray(np.uint8(alpha > 0.025) * 255, mode="L")
    support = support.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))
    alpha *= np.asarray(support, dtype=np.float32) > 0

    glyph_y, glyph_x = np.nonzero(alpha > 0.04)
    if not len(glyph_x):
        raise ValueError("Extracted glyph is empty")
    glyph = np.round(alpha[glyph_y.min() : glyph_y.max() + 1, glyph_x.min() : glyph_x.max() + 1] * 255.0)
    glyph_image = Image.fromarray(glyph.astype(np.uint8), mode="L")
    print(f"Glyph crop: {glyph_image.width} x {glyph_image.height} px")
    return glyph_image


def render_background(model: GradientModel, size: int) -> Image.Image:
    coordinates = np.arange(size, dtype=np.float32)
    y, x = np.meshgrid(coordinates, coordinates)
    rgb = model.predict(x, y)
    rng = np.random.default_rng(20261103)
    rgb += rng.uniform(-0.35, 0.35, size=rgb.shape).astype(np.float32)
    return Image.fromarray(np.clip(np.round(rgb), 0, 255).astype(np.uint8), mode="RGB")


def glyph_alpha_at_size(glyph: Image.Image, canvas_size: int) -> Image.Image:
    target_width = FOREGROUND_WIDTH_FRACTION * canvas_size
    scale = target_width / glyph.width
    target_height = glyph.height * scale

    safe_extent = SAFE_ZONE_DP / ADAPTIVE_DP * canvas_size
    if max(target_width, target_height) > safe_extent:
        correction = safe_extent / max(target_width, target_height)
        target_width *= correction
        target_height *= correction

    width = max(1, round(target_width))
    height = max(1, round(target_height))
    resized = glyph.resize((width, height), Image.Resampling.LANCZOS)
    alpha = Image.new("L", (canvas_size, canvas_size), 0)
    left = (canvas_size - width) // 2
    top = (canvas_size - height) // 2
    alpha.paste(resized, (left, top))
    return alpha


def white_layer(alpha: Image.Image) -> Image.Image:
    blank = Image.new("L", alpha.size, 255)
    return Image.merge("RGBA", (blank, blank, blank, alpha))


def circle_mask(size: int) -> Image.Image:
    supersample = 4
    mask = Image.new("L", (size * supersample, size * supersample), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size * supersample - 1, size * supersample - 1), fill=255)
    return mask.resize((size, size), Image.Resampling.LANCZOS)


def squircle_mask(size: int, exponent: float = 4.0) -> Image.Image:
    supersample = 4
    high_size = size * supersample
    coordinates = (np.arange(high_size, dtype=np.float32) + 0.5) / high_size * 2.0 - 1.0
    y, x = np.meshgrid(coordinates, coordinates)
    inside = np.power(np.abs(x), exponent) + np.power(np.abs(y), exponent) <= 1.0
    mask = Image.fromarray(np.uint8(inside) * 255, mode="L")
    return mask.resize((size, size), Image.Resampling.LANCZOS)


def apply_mask(image: Image.Image, mask: Image.Image) -> Image.Image:
    masked = image.copy()
    masked.putalpha(mask)
    return masked


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def write_adaptive_xml() -> None:
    directory = RES / "mipmap-anydpi-v26"
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (directory / name).write_text(ADAPTIVE_XML, encoding="utf-8")


def generate_adaptive_assets(
    glyph: Image.Image,
    background_master: Image.Image,
) -> dict[str, dict[str, Image.Image]]:
    generated: dict[str, dict[str, Image.Image]] = {}
    for density, scale in DENSITY_SCALES.items():
        canvas_size = round(ADAPTIVE_DP * scale)
        background = background_master.resize((canvas_size, canvas_size), Image.Resampling.LANCZOS)
        alpha = glyph_alpha_at_size(glyph, canvas_size)
        foreground = white_layer(alpha)
        monochrome = white_layer(alpha)

        directory = RES / f"mipmap-{density}"
        save_png(background, directory / "ic_launcher_background.png")
        save_png(foreground, directory / "ic_launcher_foreground.png")
        save_png(monochrome, directory / "ic_launcher_monochrome.png")
        generated[density] = {
            "background": background,
            "foreground": foreground,
            "monochrome": monochrome,
        }
    return generated


def generate_legacy_assets(
    glyph: Image.Image,
    background_master: Image.Image,
) -> dict[str, dict[str, Image.Image]]:
    master_size = round(LEGACY_DP * DENSITY_SCALES["xxxhdpi"])
    background = background_master.resize((master_size, master_size), Image.Resampling.LANCZOS).convert("RGBA")
    foreground = white_layer(glyph_alpha_at_size(glyph, master_size))
    square_master = Image.alpha_composite(background, foreground)
    round_master = apply_mask(square_master, circle_mask(master_size))

    generated: dict[str, dict[str, Image.Image]] = {}
    for density, scale in DENSITY_SCALES.items():
        size = round(LEGACY_DP * scale)
        square = square_master.resize((size, size), Image.Resampling.LANCZOS)
        round_icon = round_master.resize((size, size), Image.Resampling.LANCZOS)
        directory = RES / f"mipmap-{density}"
        save_png(square, directory / "ic_launcher.png")
        save_png(round_icon, directory / "ic_launcher_round.png")
        generated[density] = {"square": square, "round": round_icon}
    return generated


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    names = ["DejaVuSans-Bold.ttf", "DejaVuSans.ttf"] if bold else ["DejaVuSans.ttf"]
    search_roots = [Path("/usr/share/fonts"), Path("/usr/local/share/fonts")]
    for root in search_roots:
        if not root.exists():
            continue
        for name in names:
            matches = list(root.rglob(name))
            if matches:
                return ImageFont.truetype(str(matches[0]), size=size)
    return ImageFont.load_default(size=size)


def add_shadow(
    canvas: Image.Image,
    icon: Image.Image,
    position: tuple[int, int],
    opacity: int,
    blur: int,
) -> None:
    pad = blur * 3
    alpha = icon.getchannel("A").point(lambda value: value * opacity // 255)
    shadow = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    shadow.putalpha(alpha)
    padded_shadow = Image.new("RGBA", (icon.width + pad * 2, icon.height + pad * 2), (0, 0, 0, 0))
    padded_shadow.alpha_composite(shadow, (pad, pad))
    padded_shadow = padded_shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(padded_shadow, (position[0] - pad, position[1] + blur - pad))


def draw_centered(
    draw: ImageDraw.ImageDraw,
    center: tuple[int, int],
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    fill: tuple[int, int, int, int],
) -> None:
    draw.text(center, text, font=font, fill=fill, anchor="mm")


def preview_icon(background: Image.Image, foreground: Image.Image, mask: Image.Image) -> Image.Image:
    composite = Image.alpha_composite(background.convert("RGBA"), foreground.convert("RGBA"))
    return apply_mask(composite, mask)


def preview_monochrome(monochrome: Image.Image, tint: tuple[int, int, int], tile: tuple[int, int, int]) -> Image.Image:
    size = monochrome.width
    alpha = monochrome.getchannel("A")
    tile_layer = Image.new("RGBA", (size, size), (*tile, 255))
    tile_layer.putalpha(squircle_mask(size))
    tint_layer = Image.new("RGBA", (size, size), (*tint, 255))
    tint_layer.putalpha(alpha)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.alpha_composite(tile_layer)
    result.alpha_composite(tint_layer)
    return result


def render_preview(
    output_path: Path,
    adaptive_assets: dict[str, dict[str, Image.Image]],
    legacy_assets: dict[str, dict[str, Image.Image]],
) -> None:
    width, height = 2520, 1320
    margin = 40
    gap = 40
    panel_width = (width - margin * 2 - gap) // 2
    panel_height = height - margin * 2
    panel_origin_y = margin

    canvas = Image.new("RGBA", (width, height), (214, 224, 232, 255))
    draw = ImageDraw.Draw(canvas)

    panels = [
        {
            "x": margin,
            "fill": (248, 250, 252, 255),
            "title": (15, 23, 42, 255),
            "body": (71, 85, 105, 255),
            "muted": (100, 116, 139, 255),
            "rule": (203, 213, 225, 255),
            "label": "LIGHT SURFACE",
            "accent": (15, 118, 110, 255),
            "shadow": 55,
            "mono_tint": (15, 118, 110),
            "mono_tile": (217, 245, 239),
        },
        {
            "x": margin + panel_width + gap,
            "fill": (11, 18, 32, 255),
            "title": (248, 250, 252, 255),
            "body": (203, 213, 225, 255),
            "muted": (148, 163, 184, 255),
            "rule": (51, 65, 85, 255),
            "label": "DARK SURFACE",
            "accent": (94, 234, 212, 255),
            "shadow": 125,
            "mono_tint": (94, 234, 212),
            "mono_tile": (19, 78, 74),
        },
    ]

    for panel in panels:
        x = panel["x"]
        draw.rounded_rectangle(
            (x, panel_origin_y, x + panel_width, panel_origin_y + panel_height),
            radius=32,
            fill=panel["fill"],
        )
        draw.text((x + 64, panel_origin_y + 45), "Medical Question AI", font=load_font(34, True), fill=panel["title"])
        draw.text(
            (x + 64, panel_origin_y + 92),
            "Launcher icon preview · adaptive 108dp + legacy 48dp",
            font=load_font(19),
            fill=panel["muted"],
        )
        draw.text(
            (x + panel_width - 64, panel_origin_y + 57),
            panel["label"],
            font=load_font(15, True),
            fill=panel["accent"],
            anchor="ra",
        )
        draw.line(
            (x + 64, panel_origin_y + 135, x + panel_width - 64, panel_origin_y + 135),
            fill=panel["rule"],
            width=1,
        )

        source = adaptive_assets["xxxhdpi"]
        large_size = 280
        large_y = panel_origin_y + 175
        centers = [x + 260, x + 600, x + 940]
        circle = preview_icon(source["background"], source["foreground"], circle_mask(source["background"].width))
        squircle = preview_icon(source["background"], source["foreground"], squircle_mask(source["background"].width))
        monochrome = preview_monochrome(
            source["monochrome"],
            panel["mono_tint"],
            panel["mono_tile"],
        )

        for icon, center_x, label, detail in (
            (circle, centers[0], "Adaptive · circle", "full-bleed background"),
            (squircle, centers[1], "Adaptive · squircle", "66dp glyph safe area"),
            (monochrome, centers[2], "Monochrome · tinted", "same glyph alpha"),
        ):
            resized = icon.resize((large_size, large_size), Image.Resampling.LANCZOS)
            icon_x = center_x - large_size // 2
            add_shadow(canvas, resized, (icon_x, large_y), panel["shadow"], 14)
            canvas.alpha_composite(resized, (icon_x, large_y))
            draw_centered(draw, (center_x, large_y + large_size + 28), label, load_font(21, True), panel["title"])
            draw_centered(draw, (center_x, large_y + large_size + 55), detail, load_font(15), panel["muted"])

        legacy_y = panel_origin_y + 620
        draw.line(
            (x + 64, legacy_y - 34, x + panel_width - 64, legacy_y - 34),
            fill=panel["rule"],
            width=1,
        )
        draw.text(
            (x + 64, legacy_y - 8),
            "Legacy assets",
            font=load_font(23, True),
            fill=panel["title"],
        )
        draw.text(
            (x + panel_width - 64, legacy_y - 2),
            "square + round · actual generated pixels",
            font=load_font(16),
            fill=panel["muted"],
            anchor="ra",
        )

        column_centers = [x + 166 + index * 216 for index in range(5)]
        pair_top = legacy_y + 55
        pair_height = 400
        for index, density in enumerate(DENSITY_SCALES):
            square = legacy_assets[density]["square"]
            round_icon = legacy_assets[density]["round"]
            center_x = column_centers[index]
            pair_height_total = square.height + round_icon.height + 14
            pair_origin_y = pair_top + (pair_height - pair_height_total) // 2
            for row, icon in enumerate((square, round_icon)):
                icon_x = center_x - icon.width // 2
                icon_y = pair_origin_y + row * (icon.height + 14)
                add_shadow(canvas, icon, (icon_x, icon_y), panel["shadow"] // 2, 5)
                canvas.alpha_composite(icon, (icon_x, icon_y))
            draw_centered(
                draw,
                (center_x, pair_top + pair_height + 22),
                f"{density} · {round(LEGACY_DP * DENSITY_SCALES[density])} px",
                load_font(16, True),
                panel["body"],
            )

        draw.text(
            (x + 64, panel_origin_y + panel_height - 52),
            "Foreground width 60% · the source white canvas and corner mask are not part of the assets.",
            font=load_font(15),
            fill=panel["muted"],
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(output_path, format="PNG", optimize=True)
    print(f"Preview: {output_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--preview", type=Path, default=DEFAULT_PREVIEW)
    parser.add_argument("--skip-preview", action="store_true")
    args = parser.parse_args()

    source = load_source(args.source)
    model = fit_gradient(source)
    crop = crop_white_silhouette(source)
    print(f"Source: {source.shape[1]} x {source.shape[0]} px; colored crop {crop}")
    glyph = extract_glyph(source, model)
    background_master = render_background(model, round(ADAPTIVE_DP * DENSITY_SCALES["xxxhdpi"]))

    adaptive_assets = generate_adaptive_assets(glyph, background_master)
    legacy_assets = generate_legacy_assets(glyph, background_master)
    write_adaptive_xml()

    if not args.skip_preview:
        render_preview(args.preview, adaptive_assets, legacy_assets)

    print(f"Adaptive and legacy icons written under {RES}")


if __name__ == "__main__":
    main()
