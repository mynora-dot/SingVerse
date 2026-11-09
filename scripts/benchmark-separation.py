#!/usr/bin/env python3
"""Repeatable local pipeline benchmark, not Go end-to-end or a song quality claim."""
import argparse
import csv
import json
from pathlib import Path
import platform
import statistics
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'services/singverse-compute'))
from singverse_compute.application.separation_cli import LocalEngine
from singverse_compute.core.config import Settings


def summarize(records):
    groups = {}
    for row in records:
        if row.get('warmup') or row.get('error'):
            continue
        groups.setdefault(row['temperature'], []).append(row)
    result = {}
    for key, rows in groups.items():
        times = sorted(row['timingsMs']['total'] for row in rows)
        # Avoid producing a seemingly reliable P95 from a handful of smoke samples.
        result[key] = {'samples': len(rows), 'medianMs': statistics.median(times),
            'p50Ms': statistics.median(times) if len(rows) >= 30 else None,
            'p95Ms': times[min(len(times)-1, int(.95*len(times)))] if len(rows) >= 30 else None,
            'medianRtf': statistics.median(row['rtf'] for row in rows)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path, help='JSON array: {path, materialClass, optional referenceBacking}; paths are relative to manifest')
    parser.add_argument('--profile', choices=['DSP_FAST', 'AI_CLEAN', 'LEGACY_DEMUCS'], default='DSP_FAST')
    parser.add_argument('--backend')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--warmups', type=int, default=2)
    parser.add_argument('--cold', action='store_true', help='Release model before every measured run; OS file caches are not cleared')
    parser.add_argument('--qualify', action='store_true')
    parser.add_argument('--minimum-seconds', type=int, default=0, help='Repeat the set until the requested stability duration is reached')
    args = parser.parse_args()
    if args.repeats < 1 or args.warmups < 0 or args.minimum_seconds < 0:
        parser.error('Invalid run count or duration')
    dataset = json.loads(args.manifest.read_text())
    if not dataset or not isinstance(dataset, list):
        parser.error('Manifest must contain at least one authorized local input')
    args.output.mkdir(parents=True, exist_ok=False)
    cache = (args.output/'cache').resolve(); cache.mkdir()
    settings = Settings.from_env(); engine = LocalEngine(settings, cache)
    records, began, turn = [], time.monotonic(), 0
    try:
        if args.qualify and args.profile == 'AI_CLEAN': engine.qualify(args.backend)
        identity = {'system': platform.platform(), 'processor': platform.processor(),
            'configuration': {'profile': args.profile, 'requestedBackend': args.backend, 'threads': settings.audio_threads},
            'environment': engine.audio.separation.environment(True), 'accelerator': engine.audio.separation.environment(False),
            'energyJoules': None, 'realSongQualityApproved': False}
        (args.output/'environment.json').write_text(json.dumps(identity, ensure_ascii=False, indent=2))
        while turn < args.warmups+args.repeats or time.monotonic()-began < args.minimum_seconds:
            for item in dataset:
                path = (args.manifest.resolve().parent/item['path']).resolve(strict=True)
                target = args.output/f'{len(records):06}.m4a'
                if args.cold:
                    engine.audio.separation.worker.interrupt(); engine.audio.native_worker.interrupt() if engine.audio.native_worker else None
                row = {'input': path.name, 'materialClass': item.get('materialClass', 'unspecified'),
                       'warmup': turn < args.warmups, 'temperature': 'cold-model' if args.cold else 'resident', 'run': turn}
                try:
                    row.update(engine.process(path, target, args.profile, args.backend))
                    if item.get('referenceBacking'):
                        reference = (args.manifest.resolve().parent/item['referenceBacking']).resolve(strict=True)
                        # Quality tool is an explicit CPU/NumPy process, never part of timed execution.
                        import subprocess
                        result = subprocess.check_output([engine.audio.separation.python(True), str(ROOT/'scripts/score-separation.py'), str(reference), str(target)], text=True)
                        row['quality'] = json.loads(result)
                except Exception as error:
                    row['error'] = str(error)[:512]
                records.append(row)
                with (args.output/'runs.jsonl').open('a') as stream: stream.write(json.dumps(row, ensure_ascii=False)+'\n')
            turn += 1
        with (args.output/'runs.csv').open('w', newline='') as stream:
            out = csv.DictWriter(stream, fieldnames=['input', 'run', 'warmup', 'temperature', 'backend', 'totalMs', 'rtf', 'error'])
            out.writeheader()
            for row in records:
                out.writerow({**{key: row.get(key) for key in ['input','run','warmup','temperature','backend','rtf','error']}, 'totalMs': row.get('timingsMs',{}).get('total')})
        summary = summarize(records)
        (args.output/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        print(json.dumps(summary, ensure_ascii=False))
        if any(row.get('error') for row in records): sys.exit(1)
    finally:
        engine.close()


if __name__ == '__main__': main()
