"""Teach the reader. python train.py <data dir> <run dir> [epochs] [width]
Songs in held.txt are never trained on; the loss on them is reported each epoch."""
import os
import sys
import time

import torch
import torch.nn.functional as F

from data import Strips, load_labels
from reader import NC, Reader, export


def focal(pred, gt, mask):
    """CenterNet's focal loss on the heat maps: cells at a middle (gt 1) taught up, the rest down - less so near a middle."""
    p = pred.sigmoid().clamp(1e-4, 1 - 1e-4)
    pos = (gt == 1).float() * mask
    neg = (gt < 1).float() * mask
    pl = -torch.log(p) * (1 - p) ** 2 * pos
    nl = -torch.log(1 - p) * p ** 2 * (1 - gt) ** 4 * neg
    n = pos.sum().clamp(min=1)
    return (pl.sum() + nl.sum()) / n


def losses(out, heat, off, beams, dots, mask):
    h = out[:, :NC]
    lh = focal(h, heat, mask)
    centre = (heat[:, :3].amax(1) == 1) | (heat.amax(1) == 1)
    cm = centre.float().unsqueeze(1)
    lo = (F.l1_loss(out[:, NC:NC + 2].sigmoid(), off, reduction="none") * cm).sum() / cm.sum().clamp(min=1)
    bl = out[:, NC + 2:NC + 6].permute(0, 2, 3, 1).reshape(-1, 4)
    dl = out[:, NC + 6:NC + 9].permute(0, 2, 3, 1).reshape(-1, 3)
    lb = F.cross_entropy(bl, beams.reshape(-1), ignore_index=-1) if (beams >= 0).any() else out.sum() * 0
    ld = F.cross_entropy(dl, dots.reshape(-1), ignore_index=-1) if (dots >= 0).any() else out.sum() * 0
    return lh, lo, lb, ld


def main():
    root, run = sys.argv[1], sys.argv[2]
    epochs = int(sys.argv[3]) if len(sys.argv) > 3 else 20
    width = int(sys.argv[4]) if len(sys.argv) > 4 else 32
    os.makedirs(run, exist_ok=True)
    # The machine kept awake while this runs (released when it ends).
    import awake
    awake.hold()
    torch.set_num_threads(int(os.environ.get("THREADS", "2")))
    held = set(open(os.path.join(os.path.dirname(__file__), "held.txt")).read().split())
    items = load_labels(root)
    train = [it for it in items if it["song"] not in held]
    val = [it for it in items if it["song"] in held][:600]
    # Each data folder its share of every epoch (SHARES="dir:0.4,dir:0.35,..."), whatever its size:
    # a big synthetic set does not drown the library's own.
    shares = dict((k, float(v)) for k, v in (x.rsplit(":", 1) for x in os.environ.get("SHARES", "").split(",") if ":" in x))
    if shares:
        counts = {}
        for it in train: counts[it["_root"]] = counts.get(it["_root"], 0) + 1
        weights = [shares.get(it["_root"], 0.0) / counts[it["_root"]] for it in train]
        sampler = torch.utils.data.WeightedRandomSampler(weights, int(os.environ.get("SAMPLES", "16000")), replacement=True)
        tl = torch.utils.data.DataLoader(Strips(root, train, 512, True), batch_size=16, sampler=sampler, num_workers=int(os.environ.get("WORKERS", "6")), drop_last=True, persistent_workers=True)
    else:
        tl = torch.utils.data.DataLoader(Strips(root, train, 512, True), batch_size=16, shuffle=True, num_workers=int(os.environ.get("WORKERS", "6")), drop_last=True, persistent_workers=True)
    vl = torch.utils.data.DataLoader(Strips(root, val, 512, True, seed=7), batch_size=16, shuffle=False, num_workers=0)
    # Intel's graphics (PyTorch's xpu build), an NVIDIA card, or the processor.
    dev = "xpu" if hasattr(torch, "xpu") and torch.xpu.is_available() else "cuda" if torch.cuda.is_available() else "cpu"
    print("training on", dev, flush=True)
    model = Reader(width).to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4, foreach=False)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=epochs * len(tl))
    log = open(os.path.join(run, "log.txt"), "a")
    best = 1e9
    # Carried on where a run stopped (a machine asleep, a session cut short): its weights, optimiser
    # and schedule as they were after its last finished epoch.
    start = 0
    state = os.path.join(run, "state.pt")
    if os.path.exists(state):
        st = torch.load(state, map_location=dev)
        model.load_state_dict(st["model"]); opt.load_state_dict(st["opt"]); sched.load_state_dict(st["sched"])
        start, best = st["epoch"], st["best"]
        print("carrying on from epoch", start, flush=True)
    for ep in range(start, epochs):
        model.train(); t0 = time.time(); tot = [0, 0, 0, 0]; n = 0; skipped = 0
        for batch in tl:
            # Copied out of the loader workers' shared memory first: Intel's driver cannot take it from there.
            img, heat, off, beams, dots, mask = [t.clone().to(dev) for t in batch]
            out = model(img)
            lh, lo, lb, ld = losses(out, heat, off, beams, dots, mask)
            loss = lh + lo + 0.5 * lb + 0.5 * ld
            # A batch gone wrong (a loss not a number) is skipped, and no step is too large.
            if not torch.isfinite(loss):
                skipped += 1; sched.step(); continue
            opt.zero_grad(); loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step(); sched.step()
            for i, v in enumerate((lh, lo, lb, ld)): tot[i] += v.item()
            n += 1
        model.eval(); vt = [0, 0, 0, 0]; vn = 0
        with torch.no_grad():
            for batch in vl:
                img, heat, off, beams, dots, mask = [t.clone().to(dev) for t in batch]
                for i, v in enumerate(losses(model(img), heat, off, beams, dots, mask)): vt[i] += v.item()
                vn += 1
        v = sum(vt) / max(1, vn)
        line = f"epoch {ep + 1}/{epochs} {time.time() - t0:.0f}s train heat {tot[0]/n:.3f} off {tot[1]/n:.3f} beams {tot[2]/n:.3f} dots {tot[3]/n:.3f} | held heat {vt[0]/vn:.3f} off {vt[1]/vn:.3f} beams {vt[2]/vn:.3f} dots {vt[3]/vn:.3f}{f' (skipped {skipped})' if skipped else ''}"
        print(line, flush=True); log.write(line + "\n"); log.flush()
        torch.save(model.state_dict(), os.path.join(run, "last.pt"))
        torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(), "epoch": ep + 1, "best": min(best, v) if v == v else best}, state)
        if v < best and v == v:
            best = v
            torch.save(model.state_dict(), os.path.join(run, "best.pt"))
            export(model.cpu(), os.path.join(run, "reader.bin")); model.to(dev)


if __name__ == "__main__":
    main()
