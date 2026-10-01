"""How sure to be of a bar: a small logistic model over what the reader knew of it (the reading
benchmark's -Dinksheets.bench.features dump), judged song by song - fitted on all held-out songs
but one, tried on that one, for each in turn - and the threshold that keeps 99% of bars called
sure right. python sure.py <features.tsv> [more.tsv...]

Columns: right, rules-sure, adds-up, notes, rests, min head p, mean head p, min value p, mean value
p, min dots p, maybes, doubts, repeat sign, tuplets, chords, dotted, part, bar."""
import sys

import numpy as np

NAMES = ["rules_sure", "adds", "notes", "rests", "min_head", "mean_head", "min_value", "mean_value", "min_dots",
         "maybes", "doubts", "repeat", "tuplets", "chords", "dotted"]


def load(paths):
    rows = []
    for p in paths:
        for line in open(p, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) < 18:
                continue
            rows.append(f)
    y = np.array([int(r[0]) for r in rows], dtype=np.float64)
    # With look agreement (19 columns): how many of four other looks read the bar the same.
    looks = len(rows[0]) >= 19
    x = np.array([[float(v) for v in r[1:17 if looks else 16]] for r in rows], dtype=np.float64)
    song = [r[17 if looks else 16].split(" - ")[0].split("-")[0].strip().lower() for r in rows]
    return x, y, song


def design(x):
    """The features as the model takes them: probabilities as log-odds, counts as logs."""
    eps = 1e-4
    lo = lambda p: np.log(np.clip(p, eps, 1 - eps) / (1 - np.clip(p, eps, 1 - eps)))
    cols = [x[:, 0], x[:, 1], np.log1p(x[:, 2]), np.log1p(x[:, 3]),
            lo(x[:, 4]), lo(x[:, 5]), lo(x[:, 6]), lo(x[:, 7]), lo(x[:, 8]),
            np.log1p(x[:, 9]), np.log1p(x[:, 10]), x[:, 11], np.log1p(x[:, 12]), np.log1p(x[:, 13]), np.log1p(x[:, 14])]
    if x.shape[1] > 15:
        # Agreeing looks, and all four agreeing.
        cols += [x[:, 15] / 4.0, (x[:, 15] == 4).astype(np.float64)]
    return np.stack(cols, 1)


def fit(a, y, l2=1.0, steps=3000, lr=0.1):
    mu, sd = a.mean(0), a.std(0) + 1e-9
    z = (a - mu) / sd
    w = np.zeros(z.shape[1]); b = 0.0
    for _ in range(steps):
        p = 1 / (1 + np.exp(-(z @ w + b)))
        g = p - y
        w -= lr * (z.T @ g / len(y) + l2 * w / len(y))
        b -= lr * g.mean()
    return w, b, mu, sd


def predict(model, a):
    w, b, mu, sd = model
    return 1 / (1 + np.exp(-(((a - mu) / sd) @ w + b)))


def main():
    x, y, song = load(sys.argv[1:])
    a = design(x)
    songs = sorted(set(song))
    print(f"{len(y)} bars, {len(songs)} songs, right {y.mean():.3f}")
    base = x[:, 0] == 1
    print(f"now: sure {base.mean():.3f}, trust {y[base].mean():.4f}")
    if x.shape[1] > 15:
        for k in range(5):
            sel = x[:, 15] == k
            if sel.any(): print(f"  {k} looks agree: {sel.sum()} bars, right {y[sel].mean():.4f}; of those now sure, right {y[sel & base].mean() if (sel & base).any() else 0:.4f}")
    # Each song's bars scored by a model that never saw that song.
    scores = np.zeros(len(y))
    for sg in songs:
        test = np.array([s == sg for s in song])
        m = fit(a[~test], y[~test])
        scores[test] = predict(m, a[test])
    for target in (0.99, 0.985, 0.98):
        # The lowest threshold whose sure bars (scored unseen) are [target] right.
        order = np.argsort(-scores)
        right = np.cumsum(y[order]); n = np.arange(1, len(y) + 1)
        ok = np.where(right / n >= target)[0]
        k = ok.max() + 1 if len(ok) else 0
        th = scores[order][k - 1] if k else 1.0
        print(f"trust {target}: threshold {th:.4f} sure {k / len(y):.3f} (of which right {right[k - 1] / k if k else 0:.4f})")
    # Per song, at the 99% threshold.
    m_all = fit(a, y)
    print("weights (standardised):", dict(zip(NAMES, np.round(m_all[0], 3))), "bias", round(m_all[1], 3))
    np.save("sure-model.npy", np.concatenate([m_all[0], [m_all[1]], m_all[2], m_all[3]]))


if __name__ == "__main__":
    main()
