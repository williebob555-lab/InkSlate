"""How often the mark reader is right when it says a mark, at several levels of sureness, on the
held-out songs. python evalmarks.py <marks.tsv>"""
import sys

import numpy as np
import torch

import marks

x, y, test = marks.load(sys.argv[1])
net = marks.train(x[~test], y[~test])
with torch.no_grad():
    out = torch.softmax(net(torch.tensor(x[test])), 1).numpy()
pred = out.argmax(1); conf = out.max(1); yt = y[test]
for k, l in enumerate(marks.LABELS):
    if k == 0:
        continue
    for t in (0.9, 0.97, 0.99):
        said = (pred == k) & (conf >= t)
        if said.sum():
            print(f"{l:9s} at {t}: said {said.sum():5d}, right {(yt[said] == k).mean() * 100:5.1f}%, found {(said & (yt == k)).sum()}/{(yt == k).sum()}", flush=True)
