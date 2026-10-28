"""Offline QA of the compact recordings. Requires numpy; never opens an audio device."""
import argparse, json, math, re, wave
from pathlib import Path
import numpy as np


def read(path):
    with wave.open(str(path)) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64)/32768, w.getframerate()


def metrics(path):
    pcm, rate = read(path)
    root = int(re.search(r"-(\d+)-v", path.name)[1])
    hz = 440*2**((root-69)/12)
    hop = max(1, rate//200)
    rms = np.array([np.sqrt(np.mean(pcm[i:i+hop]**2)) for i in range(0, len(pcm), hop)])
    peak_rms = float(rms.max())
    audible = np.flatnonzero(rms > max(.0005, peak_rms*.05))
    # Estimate period close to the declared pitch, using a steady part of the note.
    begin = min(int(rate*.4), max(0, len(pcm)//4))
    signal = pcm[begin:min(len(pcm), begin+int(rate*.5))]
    lo, hi = max(2, int(rate/hz*.94)), int(rate/hz*1.06)+1
    scores=[]
    for lag in range(lo, hi+1):
        a,b=signal[:-lag],signal[lag:]
        scores.append(np.dot(a,b)/max(1e-15, math.sqrt(np.dot(a,a)*np.dot(b,b))))
    best=int(np.argmax(scores)); period=float(lo+best)
    if 0<best<len(scores)-1:
        a,b,c=scores[best-1:best+2]
        period += .5*(a-c)/(a-2*b+c)
    steady = pcm[int(rate*.3):min(len(pcm), int(rate*1.0))]
    return dict(file=path.as_posix(), root=root, seconds=len(pcm)/rate,
                onset_ms=round((audible[0]*hop/rate*1000),1) if len(audible) else None,
                audible_end_s=round((audible[-1]+1)*hop/rate,3) if len(audible) else None,
                peak=float(np.max(np.abs(pcm))), clipped=int(np.count_nonzero(np.abs(pcm)>=32766/32768)),
                steady_rms=float(np.sqrt(np.mean(steady**2))) if len(steady) else 0,
                pitch_cents=round(1200*math.log2(rate/period/hz),2), period_correlation=round(max(scores),4))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("root", nargs="?",default="build/recorded-defaults")
    p.add_argument("--report",default="build/recorded-defaults-audit.json")
    args=p.parse_args();results=[metrics(f) for f in sorted(Path(args.root).glob("*/*.wav"))]
    Path(args.report).write_text(json.dumps(results,indent=2))
    for instrument in sorted({Path(m["file"]).parent.name for m in results}):
        ms=[m for m in results if Path(m["file"]).parent.name==instrument]
        sustains=[m for m in ms if "/default-" in m["file"]]
        print(instrument,"samples",len(ms),"clipped",sum(m["clipped"] for m in ms),
              "max onset ms",max(m["onset_ms"] or 0 for m in ms),
              "sustain min audible s",min(m["audible_end_s"] or 0 for m in sustains),
              "max pitch cents",max(abs(m["pitch_cents"]) for m in ms))
    print("Report:",args.report)


if __name__=="__main__":main()
