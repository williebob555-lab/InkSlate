"""Build a small, reproducible CC0 real-recording prototype (requires numpy).

No audio devices are opened. Original files are hash-checked against pinned Git blobs.
Output is mono 24 kHz PCM16, with a windowed-sinc low-pass BEFORE rate conversion.
No fabricated accent/legato sample groups: these sources provide sustain and staccato.
"""
import argparse, concurrent.futures, hashlib, io, json, math, re, urllib.request, urllib.parse, wave
from pathlib import Path
import numpy as np

SOURCES = {
    "VSCO-2-CE": "440300901dfe9275fd84e0b7763af1f8443ae62e",
    "VCSL": "c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e",
}
# All names in these two sources use C3=60 (rather than scientific C4=60).
# Verified against VSCO's official TrumpetSus.sfz; VCSL checked by pitch analysis.
CONFIG = {
    "trumpet": ("VSCO-2-CE", "Brass/Trumpet/sus/", "Brass/Trumpet/stac/", 60, 77),
    "trumpet-muted": ("VSCO-2-CE", "Brass/Trumpet/straightM-sus/", "Brass/Trumpet/straightM-stac/", 60, 77),
    "trombone": ("VSCO-2-CE", "Brass/Tenor Trombone/sus/", "Brass/Tenor Trombone/stac/", 46, 65),
    "clarinet": ("VSCO-2-CE", "Woodwinds/Clarinet/susLong/", "Woodwinds/Clarinet/stac/", 58, 79),
    "tenor-sax": ("VCSL", "Aerophones/Reed Aerophones/Tenor Saxophone/Non-Vibrato/", "Aerophones/Reed Aerophones/Tenor Saxophone/Staccato/", 48, 72),
}
NOTES = dict(C=0,D=2,E=4,F=5,G=7,A=9,B=11)

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "InkSheets-CC0-prototype"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()

def info(entry):
    name = Path(entry["path"]).name
    m = re.search(r"_([A-G])(#?)(-?\d)_v(?:l)?(\d+)(?:_|\.)", name)
    if not m:
        return None
    root = (int(m[3])+2)*12+NOTES[m[1]]+bool(m[2])
    rr = re.search(r"_rr(\d+)", name)
    return root, int(m[4]), int(rr[1]) if rr else 1

def decode(data):
    # scipy is deliberately not required. PCM24/16/32 original WAVs are supported.
    with wave.open(io.BytesIO(data)) as w:
        channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(frames)
    if width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1,3).astype(np.int32)
        pcm = b[:,0] | b[:,1]<<8 | b[:,2]<<16
        pcm = ((pcm ^ 0x800000)-0x800000).astype(np.float64)/8388608
    elif width in (2,4):
        pcm = np.frombuffer(raw, "<i%d" % width).astype(np.float64)/(2**(8*width-1))
    else:
        raise ValueError("Unsupported source PCM depth %s" % width)
    return pcm.reshape(-1,channels).mean(axis=1),rate

def compact(data):
    pcm, rate = decode(data)
    # Preserve the entire recording/tail. Removing channels and bit depth saves space;
    # truncation/loop fabrication would change the sound and is not done here.
    target = 24000
    taps = 191
    n = np.arange(taps)-(taps-1)/2
    cutoff = min(target*.45,rate*.45)/rate
    kernel = 2*cutoff*np.sinc(2*cutoff*n)*np.kaiser(taps,8.6)
    kernel /= kernel.sum()
    filtered = np.convolve(pcm,kernel,"same")
    converted = np.interp(np.arange(int(len(pcm)*target/rate))*rate/target,np.arange(len(pcm)),filtered)
    # Source level is preserved; SFZ group gain controls playback balance.
    clipped = int(np.count_nonzero(np.abs(converted)>1))
    if clipped:
        raise ValueError("Source conversion clips: %d" % clipped)
    out = io.BytesIO()
    with wave.open(out,"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(target)
        w.writeframes(np.round(converted*32767).astype("<i2").tobytes())
    return out.getvalue()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",default="build/recorded-defaults")
    parser.add_argument("--cache",default="build/recorded-source-cache")
    parser.add_argument("--instruments",default=",".join(CONFIG))
    args=parser.parse_args();output=Path(args.output);cache=Path(args.cache)
    output.mkdir(parents=True,exist_ok=True);cache.mkdir(parents=True,exist_ok=True)
    trees={}
    for repo,commit in SOURCES.items():
        file=cache/(repo+"-tree.json")
        if not file.exists():
            file.write_bytes(fetch(f"https://api.github.com/repos/sgossner/{repo}/git/trees/{commit}?recursive=1"))
        tree=json.loads(file.read_text())
        assert tree["sha"]==commit and not tree["truncated"]
        trees[repo]=tree["tree"]
    jobs=[];manifest={};provenance=[]
    for instrument in args.instruments.split(","):
        repo,sus,stac,lo,hi=CONFIG[instrument]; dest=output/instrument;dest.mkdir(exist_ok=True)
        groups=[]
        for kind,prefix in (("default",sus),("staccato",stac)):
            entries=[e for e in trees[repo] if e["type"]=="blob" and e["path"].startswith(prefix) and e["path"].endswith(".wav") and info(e)]
            roots=sorted({info(e)[0] for e in entries if lo<=info(e)[0]<=hi})
            # Source spacings are normally 3-4 semitones. Preserve every source root.
            group=[]
            for root in roots:
                candidates=[e for e in entries if info(e)[0]==root]
                velocities=sorted({info(e)[1] for e in candidates})
                for layer,vel in enumerate(sorted(set((velocities[0],velocities[-1])))):
                    # The soft D5 clarinet take has 200 ms of pre-tone breath. The
                    # middle take starts promptly; musical level is balanced later.
                    if instrument=="clarinet" and root==74 and layer==0 and 2 in velocities:
                        vel=2
                    e=min((e for e in candidates if info(e)[1]==vel),key=lambda e:info(e)[2])
                    filename=f"{kind}-{root}-v{vel}.wav";jobs.append((repo,e,dest/filename))
                    index=roots.index(root)
                    low=lo if index==0 else (roots[index-1]+root)//2+1
                    high=hi if index==len(roots)-1 else (root+roots[index+1])//2
                    boundary=95 if instrument=="trumpet" else 74
                    vl,vh=(0,127) if len(velocities)==1 else ((0,boundary) if layer==0 else (boundary+1,127))
                    group.append(f'<sample path="{filename}" rootNote="{root}" loNote="{low}" hiNote="{high}" loVel="{vl}" hiVel="{vh}" />')
            # Only actual named source articulations get groups.
            groups.append('<group name="%s" tags="%s" attack="0.004" release="0.08">\n%s\n</group>'%(kind,kind,"\n".join(group)))
        preset='<DecentSampler><groups>\n'+"\n".join(groups)+'\n</groups></DecentSampler>\n'
        (dest/"instrument.dspreset").write_text(preset)
        manifest[instrument]=f"{instrument}/instrument.dspreset"
    def process(job):
        repo,e,dest=job;blob=cache/(e["sha"]+".wav")
        if not blob.exists():
            url=f'https://raw.githubusercontent.com/sgossner/{repo}/{SOURCES[repo]}/'+urllib.parse.quote(e["path"])
            blob.write_bytes(fetch(url))
        data=blob.read_bytes()
        assert hashlib.sha1(b"blob "+str(len(data)).encode()+b"\0"+data).hexdigest()==e["sha"], e["path"]
        dest.write_bytes(compact(data))
        return dict(repository=repo,commit=SOURCES[repo],path=e["path"],git_blob=e["sha"],
                    source_sha256=hashlib.sha256(data).hexdigest(),output=dest.relative_to(output).as_posix(),
                    output_sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),bytes=dest.stat().st_size)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for item in pool.map(process,jobs):
            provenance.append(item)
    for repo,commit in SOURCES.items():
        (output/(repo+"-LICENSE.txt")).write_bytes(fetch(f"https://raw.githubusercontent.com/sgossner/{repo}/{commit}/LICENSE"))
    (output/"manifest.properties").write_text("\n".join(f"{k}={v}" for k,v in manifest.items())+"\n")
    (output/"provenance.json").write_text(json.dumps(provenance,indent=2))
    (output/"README.txt").write_text("CC0 recordings: Sam Gossner, Simon Dalzell, Versilian Studios and VCSL contributors.\nSources: https://github.com/sgossner/VSCO-2-CE and https://github.com/sgossner/VCSL\nMono 24kHz PCM16 prototype, sustain/staccato, two available velocity layers, one round robin.\nNo recorded legato transitions or accent samples. Euphonium, alto sax and baritone sax are NOT present.\nPitch convention of original filenames: C3=60. See provenance.json for pinned commits/checksums.\n")
    print(f"Built {len(provenance)} real samples; {sum(p['bytes'] for p in provenance)/1048576:.2f} MiB PCM in {output}")

if __name__=="__main__":
    main()
