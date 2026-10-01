"""Strips and their labels (from StripExport), made to look like scans at random while training."""
import json
import math
import os
import random

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from reader import H, NC, STRIDE


def load_labels(root):
    out = []
    with open(os.path.join(root, "labels.jsonl"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


class Strips(torch.utils.data.Dataset):
    def __init__(self, root, items, width=512, scan=True, seed=None):
        self.root, self.items, self.width, self.scan = root, items, width, scan
        self.seed = seed
        self.cache = {}

    def __len__(self):
        return len(self.items)

    def image(self, name):
        img = self.cache.get(name)
        if img is None:
            # Kept as bytes (a quarter of floats' room: each loader worker holds its own copy).
            img = np.asarray(Image.open(os.path.join(self.root, "strips", name)), dtype=np.uint8)
            if len(self.cache) < 30000:
                self.cache[name] = img
        return img.astype(np.float32) / 255.0

    def __getitem__(self, i):
        # Training: fresh damage every time (each loader worker's own random stream);
        # checking: the same damage every time, so epochs compare.
        rng = random.Random(self.seed * 100003 + i) if self.seed is not None else random.Random(random.getrandbits(64))
        it = self.items[i]
        img = self.image(it["file"])
        objs = [list(o) for o in it["objs"]]
        # Size and place jitter: the staff tracing is never exact, nor a page's space.
        scale = rng.uniform(0.87, 1.15) if self.scan else 1.0
        dy = rng.uniform(-3, 3) if self.scan else 0.0
        t = torch.from_numpy(img)[None, None]
        if scale != 1.0 or dy != 0.0:
            h, w = img.shape
            t = F.interpolate(t, size=(max(8, int(round(h * scale))), max(16, int(round(w * scale)))), mode="bilinear", align_corners=False)
            # The top line kept at row 52, give or take dy: the picture placed oy rows down.
            oy = int(round(52 + dy - 52 * scale))
            canvas = torch.ones(1, 1, H, t.shape[-1])
            s0, d0 = max(0, -oy), max(0, oy)
            n = min(t.shape[-2] - s0, H - d0)
            if n > 0:
                canvas[..., d0:d0 + n, :] = t[..., s0:s0 + n, :]
            t = canvas
            for o in objs:
                o[1] = o[1] * scale
                # A span in doubt is two columns; everything else a column and a row.
                o[2] = o[2] * scale if o[0] == -3 else o[2] * scale + oy
        img = t[0, 0]
        w = img.shape[1]
        # A window of the strip.
        if w > self.width:
            x0 = rng.randrange(0, w - self.width + 1) if self.scan else 0
            img = img[:, x0:x0 + self.width]
        else:
            x0 = 0
            img = F.pad(img, (0, self.width - w), value=1.0)
        if self.scan:
            img = degrade(img, rng)
        heat, off, beams, dots, mask = targets([(o[0], o[1] - x0, o[2] - x0 if o[0] == -3 else o[2]) + tuple(o[3:]) for o in objs], self.width)
        return img[None], heat, off, beams, dots, mask


def degrade(img, rng):
    """A clean strip made to look scanned: low resolution, blur, ink spread or worn, one-bit
    thresholding, uneven paper, noise, specks, and pencil marks."""
    t = img[None, None].clone()
    h, w = img.shape
    # Pencil marks (the player's own), under everything else.
    if rng.random() < 0.3:
        for _ in range(rng.randint(1, 4)):
            x, y = rng.uniform(0, w), rng.uniform(0, h)
            ang, ln = rng.uniform(0, math.pi), rng.uniform(10, 80)
            shade = rng.uniform(0.2, 0.7); thick = rng.uniform(0.8, 2.5)
            n = int(ln)
            for k in range(n):
                px = int(x + math.cos(ang) * k + math.sin(k / 6.0) * 3); py = int(y + math.sin(ang) * k)
                r = int(thick)
                t[..., max(0, py - r):py + r + 1, max(0, px - r):px + r + 1] = torch.minimum(
                    t[..., max(0, py - r):py + r + 1, max(0, px - r):px + r + 1], torch.tensor(shade))
    # Ink spread (a dark copy) or worn (a light one).
    p = rng.random()
    if p < 0.25:
        spread = -F.max_pool2d(-t, 3, 1, 1)
        t = spread if rng.random() < 0.5 else 0.5 * (t + spread)
    elif p < 0.4:
        t = 0.5 * (t + F.max_pool2d(t, 3, 1, 1))
    # The scan's own resolution, lower than the strip's.
    if rng.random() < 0.7:
        f = rng.uniform(0.35, 0.9)
        small = F.interpolate(t, scale_factor=f, mode="area")
        t = F.interpolate(small, size=(h, w), mode="bilinear", align_corners=False)
    # Blur.
    if rng.random() < 0.5:
        s = rng.uniform(0.4, 1.3)
        k = torch.arange(-3, 4, dtype=torch.float32)
        g = torch.exp(-k * k / (2 * s * s)); g = g / g.sum()
        t = F.conv2d(F.pad(t, (3, 3, 0, 0), mode="replicate"), g.view(1, 1, 1, 7))
        t = F.conv2d(F.pad(t, (0, 0, 3, 3), mode="replicate"), g.view(1, 1, 7, 1))
    # One bit: a scan saved black and white.
    if rng.random() < 0.3:
        th = rng.uniform(0.35, 0.7)
        t = (t > th).float()
        if rng.random() < 0.5:
            t = F.avg_pool2d(F.pad(t, (1, 1, 1, 1), mode="replicate"), 3, 1)
    # Paper and ink shades, and a gradient across.
    ink = rng.uniform(0.0, 0.45); paper = rng.uniform(0.7, 1.0)
    grad = torch.linspace(0, rng.uniform(-0.15, 0.15), w).view(1, 1, 1, w)
    t = ink + (paper - ink) * t + grad
    # Noise and specks.
    if rng.random() < 0.8:
        t = t + torch.randn_like(t) * rng.uniform(0.0, 0.08)
    if rng.random() < 0.4:
        specks = (torch.rand_like(t) < rng.uniform(0.0005, 0.004)).float()
        t = torch.where(specks > 0, torch.full_like(t, ink), t)
    return t.clamp(0, 1)[0, 0]


def targets(objs, width):
    """Heat maps, offsets and head properties at a quarter the strip's size."""
    gh, gw = H // STRIDE, width // STRIDE
    heat = torch.zeros(NC, gh, gw)
    off = torch.zeros(2, gh, gw)
    beams = torch.full((gh, gw), -1, dtype=torch.long)
    dots = torch.full((gh, gw), -1, dtype=torch.long)
    mask = torch.ones(NC, gh, gw)
    ys = torch.arange(gh, dtype=torch.float32).view(gh, 1)
    xs = torch.arange(gw, dtype=torch.float32).view(1, gw)
    for o in objs:
        if int(o[0]) == -3:
            # A span of columns in doubt (a bar the reader was unsure of): not taught either way.
            a, b = max(0, int(o[1] / STRIDE)), min(gw, int(o[2] / STRIDE) + 1)
            if b > a:
                mask[:, :, a:b] = 0
            continue
        c, x, y = int(o[0]), o[1] / STRIDE, o[2] / STRIDE
        if not (0 <= x < gw and 0 <= y < gh):
            continue
        cx, cy = int(x), int(y)
        if c < 0:
            # A symbol the key cannot name: not taught either way, nearby.
            mask[:, max(0, cy - 2):cy + 3, max(0, cx - 2):cx + 3] = 0
            continue
        if len(o) > 5 and o[5] == "ignore":
            # A cue or grace head: not taught either way.
            mask[:, max(0, cy - 2):cy + 3, max(0, cx - 2):cx + 3] = 0
            continue
        sigma = 0.8
        g = torch.exp(-((xs - x + 0.5) ** 2 + (ys - y + 0.5) ** 2) / (2 * sigma * sigma))
        heat[c] = torch.maximum(heat[c], g)
        heat[c, cy, cx] = 1.0
        off[0, cy, cx] = x - cx
        off[1, cy, cx] = y - cy
        if c <= 2 and len(o) > 4:
            beams[cy, cx] = int(o[3]) if int(o[3]) >= 0 else -1
            dots[cy, cx] = int(o[4])
    return heat, off, beams, dots, mask
