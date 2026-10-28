"""Validate sustain loops, balance recorded layers, and bundle the compact offline pack.

First run build_recorded_defaults.py. This copies only samples referenced by the presets,
including source licenses and pinned provenance; no network is used in this step.
"""
import argparse
import shutil
import subprocess
import sys
import wave
import json
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='build/recorded-defaults')
    parser.add_argument('--output', default='sheets-core/src/main/resources/instruments/defaults')
    args = parser.parse_args()
    root = Path(args.source).resolve()
    target = Path(args.output).resolve()
    scripts = Path(__file__).resolve().parent
    subprocess.run([sys.executable, str(scripts/'loop_recorded_sustains.py'), str(root), '--apply'], check=True)
    subprocess.run([sys.executable, str(scripts/'normalize_recorded_defaults.py'), str(root)], check=True)
    files = [p for p in root.iterdir() if p.is_file()]
    loop_ends = {}
    for preset in root.glob('*/instrument.dspreset'):
        files.append(preset)
        for sample in ET.parse(preset).getroot().iter('sample'):
            path = (preset.parent/sample.attrib['path']).resolve()
            assert path.is_relative_to(root) and path.is_file(), str(path)
            files.append(path)
            if sample.get('loopEnabled') == 'true':
                loop_ends[path] = int(sample.attrib['loopEnd']) + 8
    for source in set(files):
        dest = target/source.relative_to(root)
        dest.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() in loop_ends:
            # The sampler wraps at loopEnd; later PCM is unreachable, including
            # during release. Retain interpolation guard frames and every byte
            # of the attack/loop. Original downloaded/converted files stay intact.
            with wave.open(str(source)) as original:
                frames = min(original.getnframes(), loop_ends[source.resolve()])
                pcm = original.readframes(frames)
                params = original.getparams()
            with wave.open(str(dest), 'wb') as packed:
                packed.setparams(params)
                packed.writeframes(pcm)
            with wave.open(str(dest)) as packed:
                assert packed.readframes(packed.getnframes()) == pcm
        else:
            shutil.copy2(source, dest)
    provenance = json.loads((target/'provenance.json').read_text())
    for item in provenance:
        item['converted_sha256'] = item['output_sha256']
        item['converted_bytes'] = item['bytes']
        packed = target/item['output']
        item['output_sha256'] = hashlib.sha256(packed.read_bytes()).hexdigest()
        item['bytes'] = packed.stat().st_size
    (target/'provenance.json').write_text(json.dumps(provenance, indent=2))
    with (target/'README.txt').open('a') as notes:
        notes.write('Bundling removes only PCM beyond enabled sustain loops (plus interpolation guard frames); retained PCM is byte-identical. Original converted checksums remain in provenance alongside final packaged checksums.\n')
    size = sum((target/p.relative_to(root)).stat().st_size for p in set(files))
    print(f'Bundled {len(set(files))} files, {size/1048576:.2f} MiB, in {target}')


if __name__ == '__main__':
    main()
