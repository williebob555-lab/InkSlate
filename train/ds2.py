"""DeepScoresV2 (dense) into page images and labels in the reader's classes, for StripExport's
deepscores step to cut into strips. python ds2.py <ds2_dense.tar.gz> <out dir>

Per page a .tsv: class, centre x, centre y, beams (heads; -1 unknown), dots (heads), with -1 as
class for a symbol not taught either way (grace and odd heads)."""
import io
import json
import os
import sys
import tarfile

HEAD = {"noteheadBlackOnLine": 0, "noteheadBlackInSpace": 0, "noteheadHalfOnLine": 1, "noteheadHalfInSpace": 1,
        "noteheadWholeOnLine": 2, "noteheadWholeInSpace": 2}
OTHER = {"restWhole": 3, "restHalf": 4, "restQuarter": 5, "rest8th": 6, "rest16th": 7, "augmentationDot": 8,
         "accidentalSharp": 9, "keySharp": 9, "accidentalFlat": 10, "keyFlat": 10, "accidentalNatural": 11, "keyNatural": 11}
BEAMS = {4: 0, 8: 1, 16: 2, 32: 3, 64: 3, 128: 3}


def comment(c, key):
    for part in c.split(";"):
        if part.startswith(key + ":"):
            try:
                return int(float(part.split(":")[1]))
            except ValueError:
                return None
    return None


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(os.path.join(out, "img"), exist_ok=True)
    labels = {}
    for split in ("train", "test"):
        d = json.load(open(os.path.join(out, split + ".json")))
        for img_id, name in d["images"].items():
            labels[name] = (split, d["anns"].get(img_id, []))
    n = 0
    with tarfile.open(src, "r:gz") as t:
        for m in t:
            base = os.path.basename(m.name)
            if not base.endswith(".png") or base not in labels or "/images/" not in m.name.replace("\\", "/") and "images" not in m.name:
                continue
            data = t.extractfile(m).read()
            open(os.path.join(out, "img", base), "wb").write(data)
            split, anns = labels[base]
            heads = [a for a in anns if a[0] in HEAD]
            dots = [a for a in anns if a[0] == "augmentationDot"]
            rows = []
            for name, (x0, y0, x1, y1), c in anns:
                cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
                if name in HEAD:
                    k = HEAD[name]
                    sp = max(8.0, y1 - y0)
                    dur = comment(c, "duration")
                    beams = 0 if k > 0 else BEAMS.get(dur, -1)
                    nd = len([1 for _, (a0, b0, a1, b1), _ in dots if a0 > x1 - sp * 0.2 and a0 < x1 + sp * 2.2 and abs((b0 + b1) / 2 - cy) < sp * 0.7])
                    rows.append(f"{k}\t{cx:.1f}\t{cy:.1f}\t{beams}\t{min(nd, 2)}")
                elif name in OTHER:
                    rows.append(f"{OTHER[name]}\t{cx:.1f}\t{cy:.1f}")
                elif name.startswith("notehead") or name.startswith("rest"):
                    rows.append(f"-1\t{cx:.1f}\t{cy:.1f}")
            open(os.path.join(out, "img", base[:-4] + ".tsv"), "w").write(split + "\n" + "\n".join(rows))
            n += 1
    print("pages", n)


if __name__ == "__main__":
    main()
