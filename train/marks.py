"""The mark reader: what a shape printed round the notes is - a dynamic's letter, an articulation,
a curve, a hairpin's side, a word, a figure, or something else. A small network over
MarkReader.features (the shape fitted to 16x16, its size and place). Trained on marks.tsv
(MarkExport), judged on the held-out songs.

python marks.py <marks.tsv>[,<heavy.tsv>,...] [out.bin] [held-out-only.bin] [--epochs N] [--compare old.bin]

The first file is the clean scans (vector pages drawn as mild scans); the others are heavy plate-print
scans (MarkExport with -Dinksheets.bench.heavy=<n>: thick, thin, speckled, dots broken). Each is judged
on its own held-out songs. Feature-level shaking (--shake: thickened, thinned, shifted, specked) is off: it cost tenuto and
marcato on clean scans, and the heavy scans themselves give the variety. "Other" is thinned to
--other (0.35) of its shapes. Threads: 4."""
import struct
import sys

import numpy as np
import torch

torch.set_num_threads(4)

N, EXTRA = 16, 5
LABELS = ["other", "dyn_p", "dyn_m", "dyn_f", "dyn_s", "dyn_z", "dyn_r",
          "accent", "staccato", "tenuto", "marcato", "fermata", "curve", "hairpin", "word", "digit"]
SURE = {"curve": 0.97, "tenuto": 0.97, "accent": 0.97, "marcato": 0.97, "staccato": 0.99,
        "dyn_p": 0.97, "dyn_f": 0.99, "dyn_m": 0.99, "dyn_s": 0.99, "dyn_z": 0.99, "dyn_r": 0.99, "hairpin": 0.99}


def load(path):
    xs, ys, test = [], [], []
    for line in open(path, encoding="utf-8"):
        r = line.rstrip("\n").split("\t")
        if len(r) < 5 or r[0] not in LABELS or len(r[3]) != N * N:
            continue
        f = np.array([int(c, 16) / 15.0 for c in r[3]] + [float(v) for v in r[4].split(",")], dtype=np.float32)
        if len(f) != N * N + EXTRA:
            continue
        xs.append(f); ys.append(LABELS.index(r[0])); test.append(r[1] == "true")
    return np.stack(xs), np.array(ys), np.array(test)


class Net(torch.nn.Module):
    """Two small convolutions over the picture (16x16 -> 8x8 -> 4x4), then with its size and place."""
    def __init__(self, n_in=N * N + EXTRA):
        super().__init__()
        self.c1 = torch.nn.Conv2d(1, 16, 3, padding=1)
        self.c2 = torch.nn.Conv2d(16, 32, 3, padding=1)
        self.a = torch.nn.Linear(32 * 4 * 4 + EXTRA, 64)
        self.b = torch.nn.Linear(64, len(LABELS))

    def forward(self, x):
        img = x[:, :N * N].view(-1, 1, N, N)
        h = torch.nn.functional.max_pool2d(torch.relu(self.c1(img)), 2)
        h = torch.nn.functional.max_pool2d(torch.relu(self.c2(h)), 2)
        h = torch.cat([h.flatten(1), x[:, N * N:]], 1)
        return self.b(torch.relu(self.a(h)))


def load_bin(path):
    """Read a net back from its INKC file (see export)."""
    net = Net()
    with open(path, "rb") as f:
        assert f.read(4) == b"INKC"
        for l, conv in [(net.c1, True), (net.c2, True), (net.a, False), (net.b, False)]:
            n, m = struct.unpack("<ii", f.read(8))
            size = n * m * 9 if conv else n * m
            w = np.frombuffer(f.read(4 * size), dtype=np.float32).reshape(l.weight.shape)
            b = np.frombuffer(f.read(4 * n), dtype=np.float32)
            l.weight.data = torch.tensor(w.copy()); l.bias.data = torch.tensor(b.copy())
    return net


def shake(xb, rng):
    """A batch of shapes each shaken a little the way a print and a scan vary: thickened, thinned,
    shifted a cell, specked, the ink's strength changed, the shape a little bigger or smaller."""
    n = xb.shape[0]
    img = xb[:, :N * N].view(n, 1, N, N)
    # Thickened (a cell's ink spread to its neighbours) or thinned (faint ink dropped, strong kept).
    mode = torch.rand(n, 1, 1, 1)
    spread = torch.nn.functional.max_pool2d(img, 3, 1, 1)
    soft = 0.5 * img + 0.5 * torch.nn.functional.avg_pool2d(img, 3, 1, 1, count_include_pad=False)
    img = torch.where(mode < 0.2, spread, torch.where(mode < 0.4, soft, img))
    thin = ((img - 0.35) / 0.65).clamp(0, 1)
    img = torch.where((mode >= 0.4) & (mode < 0.6), thin, img)
    # Shifted by a cell (the box sits a pixel off).
    sx = torch.randint(-1, 2, (1,)).item(); sy = torch.randint(-1, 2, (1,)).item()
    if torch.rand(1).item() < 0.4 and (sx or sy):
        img = torch.roll(img, (sy, sx), (2, 3))
    # Ink strength.
    img = (img * (0.75 + 0.5 * torch.rand(n, 1, 1, 1))).clamp(0, 1)
    # Specks: a few cells' worth of grain, and holes in strong ink.
    grain = (torch.rand(n, 1, N, N) < 0.012).float() * torch.rand(n, 1, N, N)
    holes = (torch.rand(n, 1, N, N) < 0.02).float()
    img = (img + grain * (torch.rand(n, 1, 1, 1) < 0.5).float()).clamp(0, 1)
    img = img * (1 - holes * (torch.rand(n, 1, 1, 1) < 0.3).float() * 0.8)
    out = xb.clone()
    out[:, :N * N] = img.reshape(n, -1)
    # Its size (in spaces) a little off: a thicker print is a bigger shape, a thin one smaller.
    k = 0.8 + 0.4 * torch.rand(n)
    out[:, N * N] = out[:, N * N] * k
    out[:, N * N + 1] = out[:, N * N + 1] * (0.85 + 0.3 * torch.rand(n))
    out[:, N * N + 2] = out[:, N * N + 2] + 0.03 * torch.randn(n)
    return out


def train(x, y, epochs=25, seed=0, augment=False, w=None, log=None):
    torch.manual_seed(seed)
    net = Net(x.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=2e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, epochs)
    xt = torch.tensor(x); yt = torch.tensor(y)
    counts = np.bincount(y, minlength=len(LABELS)).astype(np.float32)
    weight = torch.tensor(np.clip(counts.sum() / (len(LABELS) * np.maximum(counts, 1)), 0.2, 5.0))
    for ep in range(epochs):
        perm = torch.randperm(len(xt))
        tot = 0.0
        for i in range(0, len(xt), 256):
            idx = perm[i:i + 256]
            xb = xt[idx]
            xb = shake(xb, None) if augment else xb.clone()
            if not augment:
                xb[:, :N * N] = (xb[:, :N * N] * (0.8 + 0.4 * torch.rand(len(idx), 1))).clamp(0, 1)
            loss = torch.nn.functional.cross_entropy(net(xb), yt[idx], weight=weight)
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item() * len(idx)
        sched.step()
        if log:
            log(f"  epoch {ep + 1}/{epochs} loss {tot / len(xt):.4f}")
    return net


def export(net, path):
    """INKC: two 3x3 convolutions (each: out, in, weights, biases), then two dense layers (out, in, weights, biases)."""
    with open(path, "wb") as f:
        f.write(b"INKC")
        for l in [net.c1, net.c2]:
            w = l.weight.detach().numpy().astype(np.float32); b = l.bias.detach().numpy().astype(np.float32)
            f.write(struct.pack("<ii", w.shape[0], w.shape[1])); f.write(w.tobytes()); f.write(b.tobytes())
        for l in [net.a, net.b]:
            w = l.weight.detach().numpy().astype(np.float32); b = l.bias.detach().numpy().astype(np.float32)
            f.write(struct.pack("<ii", w.shape[0], w.shape[1])); f.write(w.tobytes()); f.write(b.tobytes())


def predict(net, x):
    with torch.no_grad():
        out = torch.softmax(net(torch.tensor(x)), 1)
    return out.argmax(1).numpy(), out.max(1).values.numpy()


def report(title, net, x, y):
    """Per label: how many, found (argmax), right when said; and as the app keeps marks (at its sure levels)."""
    pred, conf = predict(net, x)
    print(f"{title}: {len(y)} shapes, right {(pred == y).mean() * 100:.1f}%", flush=True)
    rows = {}
    for k, l in enumerate(LABELS):
        sel = y == k; said = pred == k
        if not (sel.sum() or said.sum()):
            continue
        prec = (y[said] == k).mean() * 100 if said.sum() else float("nan")
        t = SURE.get(l)
        if t is None:
            sure_found = sure_prec = float("nan"); sure_said = 0
        else:
            ss = said & (conf >= t)
            sure_said = int(ss.sum())
            sure_found = (ss & sel).sum() / max(1, sel.sum()) * 100
            sure_prec = (y[ss] == k).mean() * 100 if ss.sum() else float("nan")
        rows[l] = (int(sel.sum()), (pred[sel] == k).mean() * 100 if sel.sum() else 0.0, prec, sure_found, sure_prec, sure_said)
        print(f"  {l:9s} n {sel.sum():6d}  found {rows[l][1]:5.1f}%  right when said {prec:5.1f}%   | at sure: found {sure_found:5.1f}%  right {sure_prec:5.1f}%  (said {sure_said})", flush=True)
    return rows


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    epochs = 25
    compare = None
    if "--epochs" in args:
        i = args.index("--epochs"); epochs = int(args[i + 1]); del args[i:i + 2]
    augment = "--shake" in args
    if augment:
        args.remove("--shake")
    other_keep = 0.35
    if "--other" in args:
        i = args.index("--other"); other_keep = float(args[i + 1]); del args[i:i + 2]
    if "--compare" in args:
        i = args.index("--compare"); compare = args[i + 1]; del args[i:i + 2]
    paths = args[0].split(",")
    sets = [load(p) for p in paths]
    for p, (x, y, test) in zip(paths, sets):
        print(f"{p}: {len(y)} samples, {test.sum()} held out; " + ", ".join(f"{l} {(y == k).sum()}" for k, l in enumerate(LABELS)), flush=True)
    # Training: every set's training songs; "other" thinned (a heavy scan's grain makes a great many).
    rng = np.random.default_rng(0)
    xs, ys = [], []
    keep_other = other_keep
    for x, y, t in sets:
        tr = ~t
        pick = tr & ((y != 0) | (rng.random(len(y)) < keep_other))
        xs.append(x[pick]); ys.append(y[pick])
    xtr = np.concatenate(xs); ytr = np.concatenate(ys)
    print(f"training on {len(ytr)} shapes (other kept {keep_other * 100:.0f}%), {epochs} epochs", flush=True)
    import time
    t0 = time.time()
    net = train(xtr, ytr, epochs=epochs, augment=augment, log=lambda s: print(s, flush=True))
    print(f"trained in {time.time() - t0:.0f}s", flush=True)
    old = load_bin(compare) if compare else None
    for p, (x, y, test) in zip(paths, sets):
        if test.sum():
            if old is not None:
                report(f"BEFORE {p} held out", old, x[test], y[test])
            report(f"AFTER  {p} held out", net, x[test], y[test])
    if len(args) > 2:
        export(net, args[2]); print("written", args[2])
    if len(args) > 1:
        # The shipped net: every song, held out ones too.
        xa = np.concatenate([x for x, y, t in sets]); ya = np.concatenate([y for x, y, t in sets])
        pick = (ya != 0) | (rng.random(len(ya)) < keep_other)
        export(train(xa[pick], ya[pick], epochs=epochs, augment=augment), args[1]); print("written", args[1])
