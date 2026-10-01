"""Pictures of strips as the network is taught them: degraded (or not), each label's middle marked
in its class's colour. python show.py <data dir> <out.png> [n] [clean]"""
import sys

import numpy as np
from PIL import Image, ImageDraw

from data import Strips, load_labels
from reader import STRIDE

COLOURS = [(255, 0, 0), (0, 160, 0), (0, 0, 255), (160, 0, 160), (255, 128, 0), (0, 160, 160), (128, 64, 0), (255, 0, 128),
           (0, 0, 0), (200, 200, 0), (90, 90, 255), (0, 255, 0)]


def main():
    root, out = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 6
    clean = len(sys.argv) > 4
    items = load_labels(root)
    ds = Strips(root, items, width=768, scan=not clean, seed=1)
    rows = []
    for i in range(n):
        img, heat, off, beams, dots, mask = ds[(i * 7) % len(items)]
        a = (img[0].numpy() * 255).astype(np.uint8)
        pic = Image.fromarray(a).convert("RGB").resize((a.shape[1] * 2, a.shape[0] * 2), Image.NEAREST)
        d = ImageDraw.Draw(pic)
        for gy, gx in (mask[0] == 0).nonzero().tolist():
            d.rectangle([gx * STRIDE * 2, gy * STRIDE * 2, gx * STRIDE * 2 + 7, gy * STRIDE * 2 + 7], outline=(150, 150, 150))
        peaks = (heat == 1.0).nonzero().tolist()
        for c, gy, gx in peaks:
            x = (gx + off[0, gy, gx].item()) * STRIDE * 2
            y = (gy + off[1, gy, gx].item()) * STRIDE * 2
            d.ellipse([x - 5, y - 5, x + 5, y + 5], outline=COLOURS[c], width=2)
            if c <= 2 and beams[gy, gx] >= 0:
                d.text((x - 3, y + 8), f"{beams[gy, gx].item()}{'.' * max(0, dots[gy, gx].item())}", fill=COLOURS[c])
        rows.append(pic)
    w = max(r.width for r in rows)
    sheet = Image.new("RGB", (w, sum(r.height for r in rows)), "white")
    y = 0
    for r in rows:
        sheet.paste(r, (0, y)); y += r.height
    sheet.save(out)


if __name__ == "__main__":
    main()
