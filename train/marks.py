"""The mark reader: what a shape printed round the notes is - a dynamic's letter, an articulation,
a curve, a hairpin's side, a word, a figure, or something else. A small network over
MarkReader.features (the shape fitted to 16x16, its size and place). Trained on marks.tsv
(MarkExport), judged on the held-out songs.

python marks.py <marks.tsv> [out.bin] [held-out-only.bin]"""
import struct
import sys

import numpy as np
import torch

N, EXTRA = 16, 5
LABELS = ["other", "dyn_p", "dyn_m", "dyn_f", "dyn_s", "dyn_z", "dyn_r",
          "accent", "staccato", "tenuto", "marcato", "fermata", "curve", "hairpin", "word", "digit"]


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
    def __init__(self, n_in):
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


def train(x, y, epochs=25, seed=0):
    torch.manual_seed(seed)
    net = Net(x.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=2e-3, weight_decay=1e-4)
    xt = torch.tensor(x); yt = torch.tensor(y)
    counts = np.bincount(y, minlength=len(LABELS)).astype(np.float32)
    weight = torch.tensor(np.clip(counts.sum() / (len(LABELS) * np.maximum(counts, 1)), 0.2, 5.0))
    for ep in range(epochs):
        perm = torch.randperm(len(xt))
        for i in range(0, len(xt), 256):
            idx = perm[i:i + 256]
            xb = xt[idx].clone()
            xb[:, :N * N] = (xb[:, :N * N] * (0.8 + 0.4 * torch.rand(len(idx), 1))).clamp(0, 1)
            loss = torch.nn.functional.cross_entropy(net(xb), yt[idx], weight=weight)
            opt.zero_grad(); loss.backward(); opt.step()
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


if __name__ == "__main__":
    x, y, test = load(sys.argv[1])
    print(f"{len(y)} samples, {test.sum()} held out; " + ", ".join(f"{l} {(y == k).sum()}" for k, l in enumerate(LABELS)))
    net = train(x[~test], y[~test])
    with torch.no_grad():
        out = torch.softmax(net(torch.tensor(x[test])), 1)
        pred = out.argmax(1).numpy(); conf = out.max(1).values.numpy()
    yt = y[test]
    print(f"held out: right {(pred == yt).mean() * 100:.1f}%")
    for k, l in enumerate(LABELS):
        sel = yt == k
        said = pred == k
        if sel.sum() or said.sum():
            prec = (yt[said] == k).mean() * 100 if said.sum() else float("nan")
            print(f"  {l:9s} {sel.sum():6d}  found {(pred[sel] == k).mean() * 100 if sel.sum() else 0:5.1f}%  when said, right {prec:5.1f}%")
    for t in (0.8, 0.9):
        sure = (conf >= t) & (pred != 0)
        print(f"  said (not other) at {t}: {sure.sum()}, right {(pred[sure] == yt[sure]).mean() * 100:.1f}%")
    if len(sys.argv) > 3:
        export(net, sys.argv[3]); print("written", sys.argv[3])
    if len(sys.argv) > 2:
        export(train(x, y), sys.argv[2]); print("written", sys.argv[2])
