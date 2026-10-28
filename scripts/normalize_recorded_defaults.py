"""Balance compact recorded layers without altering their WAVs or their tone.

The playback engine supplies musical dynamics. Raw microphone level differences between
velocity takes must not add a second, abrupt volume jump at the layer boundary.
Run after build_recorded_defaults.py; loop metadata is preserved. Requires numpy.
"""
import argparse
import json
import math
import wave
from pathlib import Path
import xml.etree.ElementTree as ET
import numpy as np


def body_level(path, short):
    with wave.open(str(path)) as source:
        assert source.getnchannels() == 1 and source.getsampwidth() == 2
        rate = source.getframerate()
        audio = np.frombuffer(source.readframes(source.getnframes()), '<i2').astype(float) / 32768
    size = max(1, int(rate * .03))
    rms = np.array([math.sqrt(np.mean(audio[i:i+size] ** 2)) for i in range(0, len(audio)-size, size)])
    assert len(rms) and rms.max() > .0001, str(path)
    # Sustained bodies use the settled recording; short articulations use the active
    # attack/body, excluding their silence and decayed tail.
    if short:
        active = rms[rms > rms.max() * .35]
    else:
        onset = int(np.flatnonzero(rms > rms.max() * .15)[0])
        active = rms[onset+5:min(len(rms), onset+25)]
    return float(np.sqrt(np.mean(active ** 2)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', default='build/recorded-defaults')
    args = parser.parse_args()
    root = Path(args.root)
    report = []
    for preset in sorted(root.glob('*/instrument.dspreset')):
        tree = ET.parse(preset)
        for group in tree.getroot().iter('group'):
            short = 'staccato' in group.get('tags', '')
            for sample in group.findall('sample'):
                path = preset.parent / sample.attrib['path']
                measured = body_level(path, short)
                gain = 20 * math.log10(.12 / measured)
                sample.set('volume', f'{gain:.5f}dB')
                report.append(dict(sample=path.relative_to(root).as_posix(), body_rms=measured, gain_db=gain))
        ET.indent(tree)
        tree.write(preset, encoding='unicode')
    (root / 'level-balance.json').write_text(json.dumps(report, indent=2))
    print(f'Balanced {len(report)} recorded zones; original WAV bytes unchanged.')


if __name__ == '__main__':
    main()
