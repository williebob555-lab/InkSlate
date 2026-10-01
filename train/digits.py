"""The trained digit reader: a small network over a figure cut to its outline and shrunk to 12x18
(Digits.mask), its height in staff spaces and its width over its height - saying which digit it is,
or that it is none. Trained on digits.tsv (DigitExport), judged on the held-out songs (held.txt),
beside the masks matched now (sheets-core omr/digits.txt).

python digits.py <digits.tsv> [out.bin]"""
import struct
import sys

import numpy as np
import torch

W, H = 12, 18
NONE = 10


def load(path):
    held = {l.strip() for l in open("held.txt") if l.strip()}
    rows = [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8") if l.count("\t") >= 7]
    y = np.array([int(r[0]) for r in rows])
    bits = np.array([[c == "1" for c in r[7]] for r in rows], dtype=np.float32)
    extra = np.array([[float(r[5]) / 3.0, float(r[6]), 1.0 if r[1] == "time" else 0.0] for r in rows], dtype=np.float32)
    test = np.array([r[3] in held for r in rows])
    meta = [(r[1], r[2], r[3]) for r in rows]
    return np.concatenate([bits, extra], 1), y, test, meta


def masks():
    out = []
    for line in open("../sheets-core/src/main/resources/omr/digits.txt"):
        if not line.strip() or line.startswith("#"):
            continue
        p = line.split()
        out.append((int(p[-2]), np.array([c == "1" for c in p[-1]], dtype=np.float32)))
    return out


def by_masks(x):
    """What the app does now: the nearest mask, if 80% alike."""
    known = masks()
    k = np.stack([m for _, m in known]); d = np.array([d for d, _ in known])
    diff = (x[:, None, :W * H] != k[None]).sum(2)
    best = diff.argmin(1)
    alike = 1 - diff[np.arange(len(x)), best] / (W * H)
    return np.where(alike >= 0.8, d[best], NONE)


class Net(torch.nn.Module):
    def __init__(self, n_in, hidden=64):
        super().__init__()
        self.a = torch.nn.Linear(n_in, hidden)
        self.b = torch.nn.Linear(hidden, 11)

    def forward(self, x):
        return self.b(torch.relu(self.a(x)))


def train(x, y, epochs=60, seed=0):
    torch.manual_seed(seed)
    net = Net(x.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=3e-3, weight_decay=1e-4)
    xt = torch.tensor(x); yt = torch.tensor(y)
    # Each digit counted as much as the rest together of its kind would be: "none" is common.
    counts = np.bincount(y, minlength=11).astype(np.float32)
    weight = torch.tensor(counts.sum() / (11 * np.maximum(counts, 1)))
    weight = weight.clamp(0.2, 5.0)
    for ep in range(epochs):
        perm = torch.randperm(len(xt))
        for i in range(0, len(xt), 256):
            idx = perm[i:i + 256]
            xb = xt[idx].clone()
            # A pixel or two flipped, as a scan does.
            flip = (torch.rand(len(idx), W * H) < 0.02).float()
            xb[:, :W * H] = (xb[:, :W * H] - flip).abs()
            loss = torch.nn.functional.cross_entropy(net(xb), yt[idx], weight=weight)
            opt.zero_grad(); loss.backward(); opt.step()
    return net


def report(name, pred, y, meta, sel):
    def acc(m):
        return f"{(pred[m] == y[m]).mean():.4f} ({m.sum()})" if m.any() else "-"
    dig = sel & (y != NONE); none = sel & (y == NONE)
    print(f"{name}: digits right {acc(dig)}, none right {acc(none)}, digits called none {(pred[dig] == NONE).mean():.4f}, "
          f"none called a digit {(pred[none] != NONE).mean():.4f}")
    for kind in ("time", "text"):
        for look in ("clean", "scan", "small"):
            m = dig & np.array([k == kind and l == look for k, l, _ in meta])
            if m.any():
                print(f"   {kind}/{look}: {acc(m)}")


def main():
    x, y, test, meta = load(sys.argv[1])
    print(f"{len(y)} shapes: {np.bincount(y, minlength=11).tolist()}, test {test.sum()}")
    report("masks now (held songs)", by_masks(x), y, meta, test)
    net = train(x[~test], y[~test])
    with torch.no_grad():
        p = torch.softmax(net(torch.tensor(x)), 1).numpy()
    pred = p.argmax(1)
    report("trained (held songs)", pred, y, meta, test)
    # Sure enough to believe: the share of held digits read at each odds, and how right.
    for th in (0.5, 0.8, 0.9, 0.97):
        m = test & (p.max(1) >= th) & (pred != NONE)
        print(f"   odds >= {th}: {m.sum()} read as digits, right {(pred[m] == y[m]).mean():.4f}")
    if len(sys.argv) > 2:
        # Trained on everything, for the app: [inputs, hidden] then the weights, little-endian floats.
        net = train(x, y)
        a_w = net.a.weight.detach().numpy(); a_b = net.a.bias.detach().numpy()
        b_w = net.b.weight.detach().numpy(); b_b = net.b.bias.detach().numpy()
        with open(sys.argv[2], "wb") as f:
            f.write(struct.pack("<ii", a_w.shape[1], a_w.shape[0]))
            for arr in (a_w, a_b, b_w, b_b):
                f.write(arr.astype("<f4").tobytes())
        print("wrote", sys.argv[2])


if __name__ == "__main__":
    main()
