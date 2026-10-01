"""The trained music reader: a small fully-convolutional network over a straightened staff strip
(see sheets-core Strips.kt: a space is 10 px, the top line at row 52, 144 rows).

Out, at a quarter of the strip's size (a cell is 4 px, 0.4 of a space), per cell:
  heat[12]   how likely each symbol's middle is here (heads, rests, note dots, accidentals)
  off[2]     where in the cell the middle is (x, y), 0..1
  beams[4]   for a head: how many beams or flags its stem carries (0-3)
  dots[3]    for a head: how many dots follow it (0-2)

Small enough for a phone: about 2,500 multiply-adds per strip pixel, run in plain Kotlin
(sheets-core Net.kt) from the weights export() writes.
"""
import struct
import torch
import torch.nn as nn
import torch.nn.functional as F

CLASSES = ["head_black", "head_half", "head_whole", "rest_1", "rest_2", "rest_4", "rest_8", "rest_16",
           "dot", "sharp", "flat", "natural"]
NC = len(CLASSES)
OUT = NC + 2 + 4 + 3
H = 144
STRIDE = 4


def conv(cin, cout, stride=1, dil=1):
    return nn.Sequential(nn.Conv2d(cin, cout, 3, stride, padding=dil, dilation=dil), nn.BatchNorm2d(cout), nn.ReLU(inplace=True))


class Reader(nn.Module):
    def __init__(self, w=32):
        super().__init__()
        self.c1 = conv(1, 16, 2)            # 1/2
        self.c2 = conv(16, w, 2)            # 1/4
        self.c3 = conv(w, w)
        self.c4 = conv(w, w)
        self.d1 = conv(w, 48, 2)            # 1/8
        self.d2 = conv(48, 48)
        self.d3 = conv(48, 48)
        self.e1 = conv(48, 64, 2)           # 1/16
        self.e2 = conv(64, 64)
        self.e3 = conv(64, 64, dil=2)
        self.u2 = nn.Conv2d(64, 48, 1)
        self.u1 = nn.Conv2d(48, w, 1)
        self.h1 = conv(w, w)
        self.out = nn.Conv2d(w, OUT, 1)
        with torch.no_grad():
            self.out.bias[:NC].fill_(-4.0)   # most cells hold nothing

    def forward(self, x):
        a = self.c4(self.c3(self.c2(self.c1(x))))
        b = self.d3(self.d2(self.d1(a)))
        c = self.e3(self.e2(self.e1(b)))
        b = b + F.interpolate(self.u2(c), size=b.shape[-2:], mode="nearest")
        a = a + F.interpolate(self.u1(b), size=a.shape[-2:], mode="nearest")
        return self.out(self.h1(a))


def fused(seq):
    """A conv + batch norm pair as one conv's weight and bias."""
    cv, bn = seq[0], seq[1]
    s = bn.weight / torch.sqrt(bn.running_var + bn.eps)
    w = cv.weight * s[:, None, None, None]
    b = (cv.bias - bn.running_mean) * s + bn.bias
    return w.detach(), b.detach(), cv.stride[0], cv.dilation[0]


def export(model, path):
    """The weights as the Kotlin reader loads them: per layer, its shape, stride, dilation, then floats."""
    model.eval()
    layers = []
    for name in ["c1", "c2", "c3", "c4", "d1", "d2", "d3", "e1", "e2", "e3"]:
        layers.append((name,) + fused(getattr(model, name)))
    for name in ["u2", "u1"]:
        m = getattr(model, name)
        layers.append((name, m.weight.detach(), m.bias.detach(), 1, 1))
    layers.append(("h1",) + fused(model.h1))
    layers.append(("out", model.out.weight.detach(), model.out.bias.detach(), 1, 1))
    with open(path, "wb") as f:
        f.write(b"INKR")
        f.write(struct.pack("<i", len(layers)))
        for name, w, b, stride, dil in layers:
            cout, cin, kh, kw = w.shape
            nb = name.encode()
            f.write(struct.pack("<i", len(nb))); f.write(nb)
            f.write(struct.pack("<iiiiii", cout, cin, kh, kw, stride, dil))
            f.write(w.contiguous().numpy().astype("<f4").tobytes())
            f.write(b.contiguous().numpy().astype("<f4").tobytes())
