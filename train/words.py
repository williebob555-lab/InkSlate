"""The word reader: slowing ("rit."), back in time ("a tempo"), pressing on ("accel.") - or any
other word. Trained on words drawn in print fonts and roughened (WordSynth) and the library's own
printed words cut from its parts drawn as scans (WordExport), judged on the held-out songs' words:
above all, how often another word is taken for a tempo word (that would change the music wrongly).

python words.py <data.tsv>... [out.bin]"""
import struct
import sys

import numpy as np
import torch

W, H = 48, 16
LABELS = ["other", "rit", "atempo", "accel"]


def load(*paths):
    xs, ys, kinds, names = [], [], [], []
    for path in paths:
        for line in open(path, encoding="utf-8"):
            r = line.rstrip("\n").split("\t")
            if len(r) < 5 or r[0] not in LABELS or len(r[3]) != W * H:
                continue
            xs.append(np.array([int(c, 16) / 15.0 for c in r[3]] + [float(r[4])], dtype=np.float32))
            ys.append(LABELS.index(r[0])); kinds.append(r[2]); names.append(r[1])
    return np.stack(xs), np.array(ys), np.array(kinds), np.array(names)


class Net(torch.nn.Module):
    def __init__(self, n_in, h1=96, h2=48):
        super().__init__()
        self.a = torch.nn.Linear(n_in, h1); self.b = torch.nn.Linear(h1, h2); self.c = torch.nn.Linear(h2, len(LABELS))

    def forward(self, x):
        return self.c(torch.relu(self.b(torch.relu(self.a(x)))))


def train(x, y, epochs=60, seed=0):
    torch.manual_seed(seed)
    net = Net(x.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=2e-3, weight_decay=3e-4)
    xt = torch.tensor(x); yt = torch.tensor(y)
    counts = np.bincount(y, minlength=len(LABELS)).astype(np.float32)
    weight = torch.tensor(np.clip(counts.sum() / (len(LABELS) * np.maximum(counts, 1)), 0.3, 3.0))
    for ep in range(epochs):
        perm = torch.randperm(len(xt))
        for i in range(0, len(xt), 128):
            idx = perm[i:i + 128]
            xb = xt[idx].clone()
            px = xb[:, :W * H].view(-1, H, W)
            px = torch.roll(px, shifts=(np.random.randint(-1, 2), np.random.randint(-2, 3)), dims=(1, 2))
            xb[:, :W * H] = (px.reshape(-1, W * H) * (0.8 + 0.4 * torch.rand(len(idx), 1))).clamp(0, 1)
            loss = torch.nn.functional.cross_entropy(net(xb), yt[idx], weight=weight)
            opt.zero_grad(); loss.backward(); opt.step()
    return net


def export(net, path):
    with open(path, "wb") as f:
        f.write(b"INKS"); layers = [net.a, net.b, net.c]
        f.write(struct.pack("<i", len(layers)))
        for l in layers:
            w = l.weight.detach().numpy().astype(np.float32); b = l.bias.detach().numpy().astype(np.float32)
            f.write(struct.pack("<ii", w.shape[0], w.shape[1])); f.write(w.tobytes()); f.write(b.tobytes())


if __name__ == "__main__":
    data = [a for a in sys.argv[1:] if a.endswith(".tsv")]
    outs = [a for a in sys.argv[1:] if a.endswith(".bin")]
    x, y, kinds, names = load(*data)
    # Judged on: the held-out songs' words (are other words taken for tempo words?), and every real
    # tempo word in the library (found, never having seen one but drawn?).
    test = (kinds == "held") | ((kinds == "real") & (y != 0))
    print(f"{len(y)} words: synthetic {(kinds == 'synth').sum()}, real {(kinds == 'real').sum()}, held-out real {test.sum()}; "
          + ", ".join(f"{l} {(y == k).sum()}" for k, l in enumerate(LABELS)))
    net = train(x[~test], y[~test])
    with torch.no_grad():
        out = torch.softmax(net(torch.tensor(x[test])), 1).numpy()
    pred = out.argmax(1); conf = out.max(1); yt = y[test]
    for t in (0.9, 0.95, 0.99):
        tempo = (pred != 0) & (conf >= t)
        wrong = tempo & (pred != yt)
        print(f"at {t}: held-out real words {len(yt)}; taken for tempo words {tempo.sum()}, wrongly {wrong.sum()}: {list(names[test][wrong])[:12]}; "
              f"real tempo words found {(tempo & (pred == yt) & (yt != 0)).sum()}/{(yt != 0).sum()}")
    if outs:
        export(train(x, y), outs[0]); print("written", outs[0])
