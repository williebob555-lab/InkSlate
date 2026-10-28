"""Find phase-matched long sustain loops without rewriting recordings (requires numpy).

Writes reviewable metadata; --apply adds validated offsets/loops to generated dspresets.
Staccato recordings are never looped. The original recordings/checksums remain intact.
"""
import argparse, json, math, wave
from pathlib import Path
import xml.etree.ElementTree as ET
import numpy as np
from audit_recorded_defaults import read


def prepare(path):
    pcm,rate=read(path)
    hop=max(1,rate//500)
    rms=np.array([np.sqrt(np.mean(pcm[i:i+hop]**2)) for i in range(0,len(pcm),hop)])
    active=np.flatnonzero(rms>max(.0001,float(rms.max())*.005))
    if not len(active):raise ValueError("silent recording "+str(path))
    offset=max(0,int(active[0]*hop-.008*rate))
    result=dict(offset=offset, original_onset_ms=round(active[0]*hop/rate*1000,1))
    if not path.name.startswith("default-"):return result
    end=min(len(pcm),int((active[-1]*hop)*.72))
    xf=int(rate*.08)
    sums=np.concatenate(([0.],np.cumsum(pcm**2)))
    fftlen=1<<(len(pcm)+xf-1).bit_length()
    spectrum=np.fft.rfft(pcm,fftlen)
    best=None
    # Long loops preserve the recording's own breath/modulation rather than
    # replacing it with a tiny repeated waveform cycle.
    for seconds in (.8,1.0,1.2,1.4,1.6,2.0,2.4,2.8,3.2):
        start=offset+int(seconds*rate)
        ends=np.arange(start+int(1.2*rate),min(end,start+int(3.5*rate)))
        if not len(ends):continue
        ref=pcm[start-xf:start]; refenergy=float(np.dot(ref,ref))
        dots=np.fft.irfft(spectrum*np.fft.rfft(ref[::-1],fftlen),fftlen)[ends-1]
        energies=sums[ends]-sums[ends-xf]
        correlation=dots/np.sqrt(np.maximum(1e-20,energies*refenergy))
        ratios=np.sqrt(np.maximum(1e-20,energies/refenergy))
        scores=correlation-.35*np.abs(np.log(ratios))
        scores[(ratios<=.8)|(ratios>=1.25)]=-np.inf
        if not np.isfinite(scores).any():continue
        index=int(np.argmax(scores))
        candidate=(float(scores[index]),start,int(ends[index]),float(correlation[index]),float(ratios[index]))
        if best is None or candidate[0]>best[0]:best=candidate
    if best is None:raise ValueError("recording too short for a long loop "+str(path))
    score,start,end,corr,ratio=best
    if corr<.75 or not .8<ratio<1.25:
        raise ValueError(f"loop requires review {path}: correlation={corr:.3f} level={ratio:.3f}")
    # Reproduce SampledVoice's pre-loop crossfade and compare the seam's step
    # with the native waveform's steepest nearby change.
    left=pcm[end-xf:end];right=pcm[start-xf:start];mix=np.arange(xf)/xf
    blend=left*(1-mix)+right*mix
    blend_db=10*math.log10(float(np.mean(blend**2))/float((np.mean(left**2)+np.mean(right**2))*.5))
    if abs(blend_db)>1.5:raise ValueError("loop crossfade changes level excessively "+str(path))
    seam=abs(float(pcm[start]-blend[-1]))
    native=float(np.max(np.abs(np.diff(np.concatenate((pcm[start-xf:start+xf],pcm[end-xf:end]))))))
    if seam>max(.0005,native*1.1):raise ValueError("loop seam exceeds native waveform step "+str(path))
    result.update(loop_start=start,loop_end=end,crossfade=xf,loop_seconds=round((end-start)/rate,3),
                  correlation=round(corr,5),level_ratio=round(ratio,5),blend_rms_db=round(blend_db,4),seam_step=seam,native_max_step=native)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root",nargs="?",default="build/recorded-defaults");parser.add_argument("--apply",action="store_true")
    args=parser.parse_args();root=Path(args.root)
    results={str(path.relative_to(root)).replace("\\","/"):prepare(path) for path in sorted(root.glob("*/*.wav"))}
    (root/"sustain-loops.json").write_text(json.dumps(results,indent=2))
    if args.apply:
        for preset in sorted(root.glob("*/instrument.dspreset")):
            doc=ET.parse(preset)
            for sample in doc.findall(".//sample"):
                key=(preset.parent/sample.attrib["path"]).relative_to(root).as_posix();m=results[key]
                sample.set("start",str(m["offset"]))
                if "loop_start" in m:
                    for attr,value in dict(loopEnabled="true",loopStart=m["loop_start"],loopEnd=m["loop_end"],loopCrossfade=m["crossfade"]).items():sample.set(attr,str(value))
            ET.indent(doc);doc.write(preset,encoding="unicode")
    loops=[m for m in results.values() if "loop_start" in m]
    print("Validated",len(loops),"sustain loops; minimum correlation",min(m["correlation"] for m in loops),
          "; min length",min(m["loop_seconds"] for m in loops),"s; files remain unchanged")
    print("Applied" if args.apply else "Metadata only; pass --apply to update generated presets")


if __name__=="__main__":main()
