#!/usr/bin/env python3
"""Generate only synthetic public test signals; keep the pack OUTSIDE the checkout.

Requires Python 3 and ffmpeg with libopus + libmp3lame. Reproducible with the same
ffmpeg build (its version is recorded); no downloaded audio, recordings or keys.
"""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

REQUIRED = ('tone.opus', 'tone.wav', 'tone.mp3', 'reject-garbage.opus')
EXPECTED_SAMPLES = 21072  # round(1.317 * 16000); input is exactly 63216 at 48 kHz


def read_pcm(path):
    data = path.read_bytes()
    assert len(data) % 4 == 0, 'partial float reference'
    pcm = array.array('f')
    pcm.frombytes(data)
    if sys.byteorder != 'little': pcm.byteswap()
    return pcm


def validate_pcm(pcm, reference, expected_samples):
    """Same tolerances as the device runner; silence/truncation/phase inversion fail.

    Normalized RMSE permits Kaiser-vs-SWR filter differences, not missing signal.
    No alignment search: codec delay/end trimming are part of the contract.
    """
    assert abs(len(pcm) - expected_samples) <= 1, 'unexpected sample count'
    assert len(reference) == expected_samples, 'invalid reference sample count'
    assert all(math.isfinite(x) for x in pcm), 'nonfinite PCM'
    n = min(len(pcm), len(reference))
    ref_rms = math.sqrt(sum(x*x for x in reference[:n]) / n)
    rms = math.sqrt(sum(x*x for x in pcm[:n]) / n)
    assert ref_rms > 0.05, 'reference must contain a meaningful signal'
    ratio = rms / ref_rms
    nrmse = math.sqrt(sum((a-b)**2 for a, b in zip(pcm, reference)) / n) / ref_rms
    assert 0.8 <= ratio <= 1.2, f'RMS ratio {ratio}'
    assert nrmse <= 0.12, f'normalized RMSE {nrmse}'
    return {'rms': rms, 'reference_rms': ref_rms, 'rms_ratio': ratio, 'normalized_rmse': nrmse}


def validate_pack(root):
    manifest = json.loads((root / 'manifest.json').read_text())
    assert manifest['schema'] == 1 and manifest['sample_rate'] == 16000
    assert set(manifest['fixtures']) == set(REQUIRED), 'missing mandatory fixture manifest entries'
    for name in REQUIRED:
        entry = manifest['fixtures'][name]
        assert (root / name).is_file(), f'missing mandatory fixture: {name}'
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == entry['sha256']
        if entry.get('reject'): continue
        assert entry['expected_samples'] == EXPECTED_SAMPLES
        assert entry['reference'] == name + '.ref.f32'
        reference_path = root / entry['reference']
        assert hashlib.sha256(reference_path.read_bytes()).hexdigest() == entry['reference_sha256']
        ref = read_pcm(reference_path)
        validate_pcm(ref, ref, EXPECTED_SAMPLES)
    return manifest


def generate(root):
    root = Path(root).resolve()
    checkout = Path(__file__).resolve().parents[2]
    if root == checkout or checkout in root.parents:
        raise ValueError('Generate fixtures outside the git checkout, never commit audio/PCM')
    root.mkdir(parents=True, exist_ok=True)
    # The envelope and two frequencies reveal silence, shifts and partial decode;
    # avoid random input and high frequencies sensitive to different resamplers.
    with tempfile.TemporaryDirectory(dir=root) as temp:
        source = Path(temp) / 'source.f32'
        with source.open('wb') as out:
            for i in range(63216):
                t = i / 48000
                fade = min(1.0, i / 960, (63215-i) / 960)
                envelope = 0.7 + 0.3 * math.cos(2 * math.pi * 3 * t)
                value = fade * envelope * (0.24 * math.sin(2*math.pi*233*t) + 0.11 * math.sin(2*math.pi*701*t))
                out.write(struct.pack('<f', value))
        for name, codec in [('tone.opus', ['-c:a', 'libopus', '-b:a', '48k']),
                            ('tone.wav', ['-c:a', 'pcm_s16le']),
                            ('tone.mp3', ['-c:a', 'libmp3lame', '-b:a', '128k'])]:
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'f32le', '-ar', '48000', '-ac', '1',
                            '-i', str(source), '-map_metadata', '-1', '-fflags', '+bitexact',
                            '-flags:a', '+bitexact', *codec, str(root / name)], check=True)
    (root / 'reject-garbage.opus').write_bytes(b'not an Ogg Opus stream\n' * 32)
    manifest = {'schema': 1, 'sample_rate': 16000,
                'ffmpeg': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
                'signal': '1.317 s, mono 48 kHz, enveloped 233 + 701 Hz tones, 20 ms fades', 'fixtures': {}}
    for name in REQUIRED:
        entry: dict = {'sha256': hashlib.sha256((root / name).read_bytes()).hexdigest()}
        manifest['fixtures'][name] = entry
        if name.startswith('reject-'):
            entry['reject'] = True
            continue
        ref_name = name + '.ref.f32'
        # Match the native libopus backend, NOT ffmpeg's separate built-in Opus decoder.
        input_codec = ['-c:a', 'libopus'] if name.endswith('.opus') else []
        subprocess.run(['ffmpeg', '-v', 'error', '-y', *input_codec, '-i', str(root / name),
                        '-f', 'f32le', '-ac', '1', '-ar', '16000', str(root / ref_name)], check=True)
        ref = read_pcm(root / ref_name)
        assert len(ref) == EXPECTED_SAMPLES, f'{name}: ffmpeg changed gapless sample count'
        entry.update(expected_samples=EXPECTED_SAMPLES, reference=ref_name,
                     reference_sha256=hashlib.sha256((root / ref_name).read_bytes()).hexdigest(),
                     reference_rms=math.sqrt(sum(x*x for x in ref)/len(ref)),
                     route='native-opus' if name.endswith('.opus') else 'android')
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    validate_pack(root)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='directory outside the git checkout')
    args = parser.parse_args()
    result = generate(args.output)
    print(f'Validated {len(result["fixtures"])} mandatory synthetic fixtures in {args.output.resolve()}')
