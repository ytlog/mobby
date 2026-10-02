"""Draw a device around a screenshot or GIF without changing its screen content.

Usage: python docs/media/frame_device.py phone input.gif output.gif
       python docs/media/frame_device.py tablet input.png output.png
Requires Pillow. Keep the unframed recording as the source asset.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageSequence


def frame_size(screen_size: tuple[int, int], device: str) -> tuple[int, int, int, int]:
    width, height = screen_size
    edge = round(min(width, height) * (0.061 if device == "phone" else 0.035))
    top = round(edge * (1.1 if device == "phone" else 0.8))
    bottom = round(edge * (1.4 if device == "phone" else 0.8))
    return width + 2 * edge, height + top + bottom, edge, top


def draw_frame(screen: Image.Image, device: str) -> Image.Image:
    width, height = screen.size
    out_width, out_height, edge, top = frame_size(screen.size, device)
    canvas = Image.new("RGBA", (out_width, out_height))
    draw = ImageDraw.Draw(canvas)
    radius = round(edge * (2 if device == "phone" else 1.5))
    draw.rounded_rectangle((1, 1, out_width - 2, out_height - 2), radius=radius,
                           fill="#181b23", outline="#666d7c", width=2)
    if device == "phone":
        draw.rounded_rectangle((0, round(out_height * 0.16), 2, round(out_height * 0.23)),
                               radius=1, fill="#777d88")
        draw.rounded_rectangle((out_width - 3, round(out_height * 0.24), out_width - 1,
                                round(out_height * 0.34)), radius=1, fill="#777d88")
        dot = round(edge * 0.17)
        cx, cy = out_width // 2, max(7, top // 2)
        draw.ellipse((cx - dot, cy - dot, cx + dot, cy + dot), fill="#455064")

    mask = Image.new("L", (width, height))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1),
                                           radius=round(edge * 0.75), fill=255)
    canvas.paste(screen.convert("RGBA"), (edge, top), mask)
    return canvas


def gif_palette(frame: Image.Image) -> Image.Image:
    # Reserve index 255 for transparent pixels outside the device silhouette.
    result = frame.quantize(colors=255, method=Image.Quantize.FASTOCTREE)
    alpha = frame.getchannel("A")
    result.paste(255, mask=alpha.point(lambda value: 255 if value < 128 else 0))
    palette = result.getpalette()
    palette.extend([0] * (768 - len(palette)))
    result.putpalette(palette)
    return result


def render(source: Path, target: Path, device: str) -> None:
    with Image.open(source) as image:
        target.parent.mkdir(parents=True, exist_ok=True)
        if image.format == "GIF":
            frames = []
            durations = []
            for frame in ImageSequence.Iterator(image):
                frames.append(gif_palette(draw_frame(frame.convert("RGBA"), device)))
                durations.append(frame.info.get("duration", image.info.get("duration", 100)))
            frames[0].save(target, save_all=True, append_images=frames[1:],
                           duration=durations, loop=image.info.get("loop", 0),
                           disposal=1, transparency=255, optimize=True)
        else:
            draw_frame(image.convert("RGBA"), device).save(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("device", choices=("phone", "tablet"))
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    render(args.source, args.target, args.device)
