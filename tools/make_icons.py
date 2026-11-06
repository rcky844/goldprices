#!/usr/bin/env python3
"""Generates the launcher and notification icons for Gold Monitor."""

import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
S = 512


def vertical_gradient(size, top, bottom):
    image = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / float(size - 1)
        image.putpixel((0, y), tuple(
            int(round(top[i] + (bottom[i] - top[i]) * t)) for i in range(3)))
    return image.resize((size, size))


def horizontal_gradient(width, height, left, right):
    image = Image.new("RGB", (width, 1))
    for x in range(width):
        t = x / float(width - 1)
        image.putpixel((x, 0), tuple(
            int(round(left[i] + (right[i] - left[i]) * t)) for i in range(3)))
    return image.resize((width, height))


def bar(draw, top_y, bottom_y, back_top_y, front_left, front_right,
        back_left, back_right, top_color, front_image=None):
    """Draws a 3D gold ingot: top parallelogram plus trapezoid front."""
    top = [(back_left, back_top_y), (back_right, back_top_y),
           (front_right, top_y), (front_left, top_y)]
    front = [(front_left, top_y), (front_right, top_y),
             (front_right + (front_right - front_left) * 0.09, bottom_y),
             (front_left - (front_right - front_left) * 0.09, bottom_y)]
    draw.polygon(front, fill=(196, 142, 18))
    if front_image is not None:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).polygon(front, fill=255)
        front_image.putalpha(mask)
        return front_image
    draw.polygon(top, fill=top_color)
    return None


def main():
    canvas = vertical_gradient(S, (27, 36, 53), (10, 15, 24)).convert("RGBA")

    # Soft warm glow behind the metal.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((70, 180, 442, 440), fill=(255, 205, 90, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(38))
    canvas = Image.alpha_composite(canvas, glow)

    draw = ImageDraw.Draw(canvas)

    # Contact shadow.
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse((62, 384, 450, 442), fill=(0, 0, 0, 130))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    canvas = Image.alpha_composite(canvas, shadow)
    draw = ImageDraw.Draw(canvas)

    # Back bar.
    top_face = [(188, 214), (338, 214), (366, 252), (160, 252)]
    draw.polygon(top_face, fill=(255, 234, 156))
    draw.polygon([(160, 252), (366, 252), (392, 316), (134, 316)],
                 fill=(170, 120, 12))
    band = horizontal_gradient(S, S, (248, 209, 105), (205, 150, 24))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).polygon([(160, 252), (366, 252), (392, 316), (134, 316)],
                                 fill=255)
    band = band.convert("RGBA")
    band.putalpha(mask)
    canvas = Image.alpha_composite(canvas, band)
    draw = ImageDraw.Draw(canvas)
    draw.line([(160, 252), (366, 252)], fill=(255, 240, 180), width=3)

    # Front bar.
    draw.polygon([(150, 316), (362, 316), (396, 382), (116, 382)],
                 fill=(255, 236, 160))
    draw.polygon([(116, 382), (396, 382), (420, 424), (92, 424)],
                 fill=(160, 112, 10))
    band = horizontal_gradient(S, S, (247, 208, 96), (206, 148, 20))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).polygon([(116, 382), (396, 382), (420, 424), (92, 424)],
                                 fill=255)
    band = band.convert("RGBA")
    band.putalpha(mask)
    canvas = Image.alpha_composite(canvas, band)
    draw = ImageDraw.Draw(canvas)
    draw.line([(116, 382), (396, 382)], fill=(255, 244, 196), width=4)

    # Small specular highlight.
    draw.line([(170, 404), (330, 404)], fill=(255, 238, 170), width=3)

    # Round the icon corners.
    rounded = Image.new("L", (S, S), 0)
    ImageDraw.Draw(rounded).rounded_rectangle((0, 0, S - 1, S - 1), radius=112,
                                              fill=255)
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    icon.paste(canvas, (0, 0), rounded)

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144}
    for density, size in densities.items():
        directory = os.path.join(ROOT, "res", "drawable-" + density)
        os.makedirs(directory, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(
            os.path.join(directory, "ic_launcher.png"))
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "icon-preview.png"))

    # Notification icon: flat white ingot on transparent, aligned to the
    # Material 24dp grid (Android tints it automatically where supported).
    small = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    d = ImageDraw.Draw(small)
    d.polygon([(7, 8), (17, 8), (19, 12), (5, 12)], fill=(255, 255, 255, 255))
    d.polygon([(5, 12), (19, 12), (21, 18), (3, 18)], fill=(255, 255, 255, 255))
    for density, size in {"mdpi": 24, "hdpi": 36, "xhdpi": 48,
                          "xxhdpi": 72}.items():
        directory = os.path.join(ROOT, "res", "drawable-" + density)
        small.resize((size, size), Image.LANCZOS).save(
            os.path.join(directory, "ic_stat_gold.png"))


if __name__ == "__main__":
    main()
