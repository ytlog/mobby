"""Frame real Android recordings and add a moving close-up, without replacing UI.

Requires Pillow, numpy and FFmpeg. Cuts and crop positions are in showcase-edits.json.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, FPS = 1200, 760, 24
PHONE = (62, 66, 290, 628)
DETAIL = (408, 257, 730, 330)
WHITE, MUTED, ACCENT = "#edf5ff", "#9babbe", "#74bcff"


def rounded(size, radius):
    mask = Image.new("L", size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *size), radius=radius, fill=255)
    return mask


def ease(value):
    value = max(0.0, min(1.0, value))
    return value * value * (3 - 2 * value)


def background():
    y, x = np.mgrid[:HEIGHT, :WIDTH]
    glow = np.exp(-((x - 930) ** 2 / 470**2 + (y - 160) ** 2 / 490**2))
    base = np.zeros((HEIGHT, WIDTH, 3)) + np.array([9, 16, 28])
    base += glow[:, :, None] * np.array([7, 14, 24])
    return Image.fromarray(base.astype("uint8"))


class Source:
    def __init__(self, path, cut, ffmpeg):
        self.still = None
        if path.suffix.lower() in {".png", ".jpg"}:
            self.still = Image.open(path).convert("RGB")
        else:
            speed = cut.get("speed", 1)
            self.decoder = subprocess.Popen([
                ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", str(cut.get("start", 0)),
                "-i", str(path), "-an", "-vf", f"setpts=(PTS-STARTPTS)/{speed},fps={FPS},scale=720:1560",
                "-frames:v", str(round(cut["duration"] * FPS)), "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
            ], stdout=subprocess.PIPE)

    def frame(self, seconds):
        if self.still is not None:
            return self.still
        data = self.decoder.stdout.read(720 * 1560 * 3)
        if len(data) != 720 * 1560 * 3:
            raise ValueError(f"Missing source frame at {seconds:.3f}s")
        return Image.frombytes("RGB", (720, 1560), data)

    def close(self):
        if self.still is None:
            self.decoder.stdout.close()
            if self.decoder.wait() != 0:
                raise RuntimeError("FFmpeg decoding failed")


def render(spec, raw_dir, output, ffmpeg, font_path, bold_path):
    fonts = {s: ImageFont.truetype(str(font_path), s) for s in (14, 16, 18, 21)}
    title_font = ImageFont.truetype(str(bold_path), 42)
    brand_font = ImageFont.truetype(str(bold_path), 24)
    base = background()
    d = ImageDraw.Draw(base)
    d.text((408, 58), "mobby", font=brand_font, fill=WHITE)
    d.text((505, 65), "/  " + spec["category"], font=fonts[16], fill=ACCENT)
    d.multiline_text((408, 114), spec["title"], font=title_font, fill=WHITE, spacing=5)
    d.text((410, 224), "LIVE DETAIL", font=fonts[14], fill=ACCENT)
    d.text((974, 224), "ANDROID 13", font=fonts[14], fill=MUTED)
    d.rounded_rectangle((57, 61, 357, 699), 29, fill="#050a12", outline="#344257", width=1)
    d.rounded_rectangle((405, 254, 1141, 590), 20, fill="#050a12", outline="#344257", width=1)
    d.text((408, 690), "Real device capture  ·  Cuts & close-ups for readability", font=fonts[14], fill=MUTED)
    phone_mask = rounded(PHONE[2:], 24)
    detail_mask = rounded(DETAIL[2:], 16)
    duration = sum(c["duration"] for c in spec["cuts"])
    target = output / (spec["name"] + ".mp4")
    args = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo",
            "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS), "-i", "-",
            "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", str(target)]
    encoder = subprocess.Popen(args, stdin=subprocess.PIPE)
    frame_index = 0
    for cut in spec["cuts"]:
        source = Source(raw_dir / cut["source"], cut, ffmpeg)
        for j in range(round(cut["duration"] * FPS)):
            local = j / FPS
            src = source.frame(cut.get("start", 0) + local * cut.get("speed", 1))
            frame = base.copy()
            frame.paste(src.resize(PHONE[2:], Image.Resampling.LANCZOS), PHONE[:2], phone_mask)
            blend = ease((local - .4) / max(.8, cut["duration"] - 1.2))
            focus = cut["focus"]
            end = cut.get("end_focus", focus)
            cx, cy, zoom = [a + (b - a) * blend for a, b in zip(focus, end)]
            crop_w = src.width / zoom
            crop_h = crop_w * DETAIL[3] / DETAIL[2]
            left = max(0, min(src.width - crop_w, cx * src.width - crop_w / 2))
            top = max(0, min(src.height - crop_h, cy * src.height - crop_h / 2))
            detail = src.crop((left, top, left + crop_w, top + crop_h)).resize(DETAIL[2:], Image.Resampling.LANCZOS)
            frame.paste(detail, DETAIL[:2], detail_mask)
            draw = ImageDraw.Draw(frame)
            draw.ellipse((409, 618, 416, 625), fill=ACCENT)
            draw.text((429, 609), cut["caption"], font=fonts[21], fill=WHITE)
            draw.line((408, 661, 1138, 661), fill="#2a3649", width=2)
            progress = frame_index / max(1, duration * FPS - 1)
            draw.line((408, 661, 408 + round(730 * progress), 661), fill=ACCENT, width=3)
            encoder.stdin.write(frame.tobytes())
            if frame_index == round(spec.get("poster_at", 1) * FPS):
                frame.save(output / (spec["name"] + ".jpg"), quality=92)
            frame_index += 1
        source.close()
    encoder.stdin.close()
    if encoder.wait() != 0:
        raise RuntimeError("FFmpeg encoding failed")
    subprocess.run([ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(target),
                    "-filter_complex", "fps=8,scale=900:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                    "-loop", "0", str(output / (spec["name"] + ".gif"))], check=True)
    print(f"Rendered {spec['name']}: {duration:.1f}s", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("--edits", type=Path, default=Path(__file__).with_name("showcase-edits.json"))
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--font", type=Path, default=Path("/System/Library/Fonts/Supplemental/Arial.ttf"))
    parser.add_argument("--bold", type=Path, default=Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf"))
    parser.add_argument("--only")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for spec in json.loads(args.edits.read_text()):
        if not args.only or spec["name"] == args.only:
            render(spec, args.raw_dir, args.output, args.ffmpeg, args.font, args.bold)
