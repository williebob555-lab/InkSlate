"""The symbol reader: what a rest or an accidental the trained reader found really is - which rest,
which accidental, or nothing (a letter, a dynamic, a mark it took for one). A small network over
the picture round it (20x40, SymbolReader.crop), its height on the staff, and what the reader took
it for. Trained on symbols.tsv (SymbolExport), judged on the held-out songs.

python symbols.py <symbols.tsv> [out.bin]"""
import struct
import sys

import numpy as np
import torch

W, H = 20, 40
LABELS = ["other", "sharp", "flat", "natural", "block", "rest4", "rest8", "rest16"]
NETKINDS = LABELS  # what the reader took it for ("none": missed by it)


def load(path):
    xs, ys, test, was = [], [], [], []
    for line in open(path, encoding="utf-8"):
        r = line.rstrip("\n").split("\t")
        if len(r) < 7 or len(r[6]) != W * H:
            continue
        px = np.array([int(c, 16) / 15.0 for c in r[6]], dtype=np.float32)
        onehot = np.zeros(len(LABELS), dtype=np.float32)
        if r[1] in LABELS:
            onehot[LABELS.index(r[1])] = 1
        xs.append(np.concatenate([px, np.array([float(r[3]) / 8.0], dtype=np.float32), onehot]).astype(np.float32))
        ys.append(LABELS.index(r[0]))
        test.append(r[4] == "true")
        was.append(r[1])
    return np.stack(xs), np.array(ys), np.array(test), np.array(was)


class Net(torch.nn.Module):
    def __init__(self, n_in, h1=128, h2=64):
        super().__init__()
        self.a = torch.nn.Linear(n_in, h1)
        self.b = torch.nn.Linear(h1, h2)
        self.c = torch.nn.Linear(h2, len(LABELS))

    def forward(self, x):
        return self.c(torch.relu(self.b(torch.relu(self.a(x)))))


def shifted(xb):
    """The picture moved a cell or so any way, as the reader's place for a symbol wanders."""
    px = xb[:, :W * H].view(-1, H, W)
    dx = np.random.randint(-1, 2); dy = np.random.randint(-2, 3)
    px = torch.roll(px, shifts=(dy, dx), dims=(1, 2))
    out = xb.clone()
    out[:, :W * H] = px.reshape(-1, W * H)
    return out


def train(x, y, epochs=40, seed=0):
    torch.manual_seed(seed); np.random.seed(seed)
    net = Net(x.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=2e-3, weight_decay=1e-4)
    xt = torch.tensor(x); yt = torch.tensor(y)
    counts = np.bincount(y, minlength=len(LABELS)).astype(np.float32)
    weight = torch.tensor(np.clip(counts.sum() / (len(LABELS) * np.maximum(counts, 1)), 0.3, 4.0))
    for ep in range(epochs):
        perm = torch.randperm(len(xt))
        for i in range(0, len(xt), 256):
            idx = perm[i:i + 256]
            xb = shifted(xt[idx])
            # Paper a little darker or lighter, ink a little fainter.
            xb[:, :W * H] = (xb[:, :W * H] * (0.8 + 0.4 * torch.rand(len(idx), 1)) + 0.05 * torch.rand(len(idx), 1)).clamp(0, 1)
            loss = torch.nn.functional.cross_entropy(net(xb), yt[idx], weight=weight)
            opt.zero_grad(); loss.backward(); opt.step()
    return net


def report(name, pred, y, was):
    ok = pred == y
    print(f"{name}: right {ok.mean() * 100:.1f}% of {len(y)}")
    for k, lab in enumerate(LABELS):
        sel = y == k
        if sel.sum():
            print(f"  {lab:8s} {sel.sum():6d}  right {ok[sel].mean() * 100:5.1f}%")
    # Of what the reader found: how often its own kind was right, and this one's.
    found = was != "none"
    reader = np.array([LABELS.index(w) if w in LABELS else -1 for w in was])
    print(f"  of the reader's {found.sum()} finds: reader right {(reader[found] == y[found]).mean() * 100:.1f}%, this right {(pred[found] == y[found]).mean() * 100:.1f}%")
    fake = found & (y == 0)
    if fake.sum():
        print(f"  finds that are nothing ({fake.sum()}): turned down {(pred[fake] == 0).mean() * 100:.1f}%")
    real = found & (y != 0)
    print(f"  real finds ({real.sum()}): wrongly turned down {(pred[real] == 0).mean() * 100:.2f}%")


def export(net, path):
    layers = [net.a, net.b, net.c]
    with open(path, "wb") as f:
        f.write(b"INKS")
        f.write(struct.pack("<i", len(layers)))
        for l in layers:
            w = l.weight.detach().numpy().astype(np.float32)
            b = l.bias.detach().numpy().astype(np.float32)
            f.write(struct.pack("<ii", w.shape[0], w.shape[1]))
            f.write(w.tobytes()); f.write(b.tobytes())


if __name__ == "__main__":
    x, y, test, was = load(sys.argv[1])
    print(f"{len(y)} samples, {test.sum()} held out; by label: " + ", ".join(f"{l} {(y == k).sum()}" for k, l in enumerate(LABELS)))
    net = train(x[~test], y[~test])
    with torch.no_grad():
        pred = net(torch.tensor(x[test])).argmax(1).numpy()
        conf = torch.softmax(net(torch.tensor(x[test])), 1).max(1).values.numpy()
    report("held out", pred, y[test], was[test])
    reader = np.array([LABELS.index(w) if w in LABELS else -1 for w in was[test]])
    found = reader >= 0
    for t in (0.8, 0.9, 0.95):
        sure = conf >= t
        over = found & sure & (pred != reader)
        print(f"  sure at {t}: {sure.mean() * 100:.1f}% of them, right {(pred[sure] == y[test][sure]).mean() * 100:.2f}%; "
              f"overrules the reader {over.sum()} times: right {(pred[over] == y[test][over]).sum()}, wrong where the reader was right {(reader[over] == y[test][over]).sum()}")
    if len(sys.argv) > 3:
        # The held-out songs never seen: for measuring in the app's reading (ReadingBenchmark).
        export(net, sys.argv[3])
        print("written", sys.argv[3])
    if len(sys.argv) > 2:
        # The one shipped is trained on everything.
        full = train(x, y)
        export(full, sys.argv[2])
        print("written", sys.argv[2])
