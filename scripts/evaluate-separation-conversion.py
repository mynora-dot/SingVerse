#!/usr/bin/env python3
"""Compare a converted MDX candidate against fixed FP32, without promoting it."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time
import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'services/singverse-compute'))
from singverse_compute.domain.audio_separation import MDX_SHA256, threads
from singverse_compute.infrastructure.uvr_audio import separate


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def relative_error(reference, candidate):
    energy = error = 0.
    with sf.SoundFile(reference) as left, sf.SoundFile(candidate) as right:
        if (len(left), left.samplerate, left.channels) != (len(right), right.samplerate, right.channels):
            raise ValueError('Candidate timeline differs from FP32')
        for block in left.blocks(blocksize=65536, dtype='float64', always_2d=True):
            actual = right.read(len(block), dtype='float64', always_2d=True)
            if not np.isfinite(actual).all():
                raise ValueError('Candidate output is not finite')
            energy += float(np.square(block).sum())
            error += float(np.square(block-actual).sum())
    return (error/max(energy, 1e-30))**.5


def ort_forward(path, count):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads, options.inter_op_num_threads = count, 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
    name = session.get_inputs()[0].name
    return lambda value: session.run(None, {name: value})[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--conversion', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--warmups', type=int, default=2)
    parser.add_argument('--threads', type=int, default=0)
    args = parser.parse_args()
    if args.repeats < 1 or args.warmups < 0:
        parser.error('Invalid repetition count')
    if digest(args.baseline) != MDX_SHA256:
        raise ValueError('Expected pinned MDX FP32 baseline')
    evidence = json.loads((args.conversion/'conversion.json').read_text())
    if evidence['sourceSha256'] != MDX_SHA256 or evidence['target'] not in ('int8', 'openvino-fp16'):
        raise ValueError('Expected an unmodified MDX INT8 or OpenVINO conversion')
    for entry in evidence['files']:
        path = (args.conversion/entry['path']).resolve(strict=True)
        if not path.is_relative_to(args.conversion.resolve()) or digest(path) != entry['sha256']:
            raise ValueError('Converted candidate changed')
    args.output.mkdir(parents=True, exist_ok=False)
    count = threads(args.threads)
    started = time.monotonic()
    forwards = {'fp32': ort_forward(args.baseline, count)}
    load = {'fp32': (time.monotonic()-started)*1000}
    started = time.monotonic()
    if evidence['target'] == 'int8':
        forwards['candidate'] = ort_forward(args.conversion/'mdx-int8.onnx', count)
        precision = 'int8-QDQ-with-fp32-operators'
    else:
        import openvino as ov
        compiled = ov.Core().compile_model(str(args.conversion/'mdx.xml'), 'CPU', {'INFERENCE_NUM_THREADS': count, 'PERFORMANCE_HINT': 'LATENCY'})
        request = compiled.create_infer_request()
        forwards['candidate'] = lambda value: request.infer({compiled.input(0): value})[compiled.output(0)].copy()
        precision = 'fp16-compressed-weights; CPU execution precision selected by device'
    load['candidate'] = (time.monotonic()-started)*1000
    records = []
    dataset = json.loads(args.manifest.read_text())
    if not isinstance(dataset, list) or not dataset:
        raise ValueError('Use a nonempty independent validation set')
    for index, entry in enumerate(dataset):
        source = (args.manifest.resolve().parent/entry['path']).resolve(strict=True)
        folder = args.output/f'{index:04}'; folder.mkdir()
        wav = folder/'input.wav'
        started = time.monotonic()
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(source), '-map', '0:a:0',
                        '-vn', '-ac', '2', '-ar', '44100', '-c:a', 'pcm_f32le', str(wav)], check=True)
        decode_ms = (time.monotonic()-started)*1000
        duration = sf.info(wav).duration
        measured = {name: [] for name in forwards}
        outputs = {}
        for turn in range(args.warmups+args.repeats):
            # Alternate order to reduce systematic thermal/order bias.
            order = list(forwards) if turn % 2 == 0 else list(reversed(forwards))
            for name in order:
                target = folder/f'{name}-{turn}'
                started = time.monotonic()
                output = separate(wav, target, forwards[name], float_output=True, backing_only=True)/'no_vocals.wav'
                elapsed = (time.monotonic()-started)*1000
                if turn >= args.warmups:
                    measured[name].append(elapsed)
                previous = outputs.get(name)
                if previous:
                    previous.unlink(); previous.parent.rmdir()
                outputs[name] = output
        record = {'input': source.name, 'inputSha256': digest(source), 'materialClass': entry.get('materialClass', 'unspecified'),
                  'duration': duration, 'decodeMs': decode_ms, 'spectralInferenceWriteMs': measured,
                  'relativeErrorToFp32': relative_error(outputs['fp32'], outputs['candidate']),
                  'medianRtf': {name: statistics.median(values)/1000/duration for name, values in measured.items()}}
        if entry.get('referenceBacking'):
            reference = (args.manifest.resolve().parent/entry['referenceBacking']).resolve(strict=True)
            record['quality'] = {name: json.loads(subprocess.check_output([sys.executable, str(ROOT/'scripts/score-separation.py'), str(reference), str(output)], text=True))
                                 for name, output in outputs.items()}
        records.append(record)
        with (args.output/'runs.jsonl').open('a') as stream:
            stream.write(json.dumps(record, ensure_ascii=False)+'\n')
        wav.unlink()
    report = {'measurement': 'measured-local-CPU-conversion-comparison', 'productionApproved': False,
              'candidatePrecision': precision, 'threads': count, 'modelLoadMs': load, 'conversion': evidence,
              'note': 'Decode measured once. Timed stages include shared spectral DSP, inference and float WAV write; excludes encoding, Java and transport. Real-song blind listening and deployment qualification remain required.',
              'samples': len(records), 'records': records}
    (args.output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'samples': len(records), 'report': str(args.output/'report.json'), 'productionApproved': False}))


if __name__ == '__main__':
    main()
