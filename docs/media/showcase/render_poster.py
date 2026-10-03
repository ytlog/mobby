"""Compose the real phone and tablet screenshots into a README poster.

Run from the repository root after generating phone.png and tablet.png:
    python docs/media/showcase/render_poster.py

Requires Pillow. This adds presentation framing only; it does not redraw the UI.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


HERE = Path(__file__).resolve().parent
SIZE = (1600, 900)


def background() -> Image.Image:
    canvas = Image.new("RGBA", SIZE)
    pixels = canvas.load()
    for y in range(SIZE[1]):
        for x in range(SIZE[0]):
            weight = (x / SIZE[0] + y / SIZE[1]) / 2
            pixels[x, y] = (
                round(231 + 20 * weight),
                round(243 + 10 * weight),
                255,
                255,
            )

    accent = Image.new("RGBA", SIZE)
    draw = ImageDraw.Draw(accent)
    draw.ellipse((40, 10, 900, 800), fill=(83, 159, 244, 70))
    draw.ellipse((840, 230, 1700, 1060), fill=(143, 204, 250, 58))
    canvas = Image.alpha_composite(canvas, accent.filter(ImageFilter.GaussianBlur(115)))

    details = Image.new("RGBA", SIZE)
    draw = ImageDraw.Draw(details)
    for x in range(75, 1550, 48):
        for y in range(74, 850, 48):
            draw.ellipse((x, y, x + 2, y + 2), fill=(86, 132, 185, 22))
    draw.arc((1050, -215, 1750, 485), 40, 250, fill=(91, 155, 224, 34), width=3)
    draw.arc((-230, 420, 475, 1125), 220, 420, fill=(91, 155, 224, 32), width=3)
    canvas = Image.alpha_composite(canvas, details)

    return canvas


def add_device(
    canvas: Image.Image,
    name: str,
    width: int,
    angle: float,
    position: tuple[int, int],
    shadow: tuple[int, int, int, int],
) -> None:
    with Image.open(HERE / name) as source:
        height = round(source.height * width / source.width)
        device = source.convert("RGBA").resize((width, height), Image.Resampling.LANCZOS)
    device = device.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True)

    silhouette = Image.new("RGBA", device.size, shadow[:3] + (0,))
    silhouette.putalpha(device.getchannel("A").point(lambda value: value * shadow[3] // 255))
    x, y = position
    shadow_layer = Image.new("RGBA", SIZE)
    shadow_layer.alpha_composite(silhouette, (x + 25, y + 35))
    canvas.alpha_composite(shadow_layer.filter(ImageFilter.GaussianBlur(75)))
    canvas.alpha_composite(device, position)


def main() -> None:
    canvas = background()
    add_device(canvas, "tablet.png", 1110, 1.2, (75, 86), (28, 55, 91, 20))
    add_device(canvas, "phone.png", 355, -4.0, (1065, 91), (23, 46, 80, 30))
    canvas.save(HERE / "phone-tablet-poster.png", optimize=True)


if __name__ == "__main__":
    main()
