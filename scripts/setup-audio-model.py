#!/usr/bin/env python3
"""Explicit release-owned model installation and optional offline ZIP creation."""
import argparse
import hashlib
from pathlib import Path
import sys
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'services/singverse-compute'))
from singverse_compute.application.audio_catalog import BASELINE, MODELS, manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', choices=list(MODELS), default=BASELINE)
    parser.add_argument('--bundle', type=Path, help='Create offline ZIP (OpenVINO only), refuses overwrite')
    args = parser.parse_args()
    specification = manifest(args.model)
    output = ROOT / '.tools' / ('demucs-htdemucs' if args.model == BASELINE else args.model)
    def valid():
        try:
            return all((output / f['name']).stat().st_size == f['size'] and
                       digest(output / f['name']) == f['sha256'] for f in specification['files']) and (
                       args.model != BASELINE or (output/'htdemucs.yaml').read_text() == "models: ['955717e8']\n")
        except OSError:
            return False
    if output.exists():
        if not valid():
            raise SystemExit('Existing model differs; preserve or move it before installation')
    else:
        output.parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
            stage = Path(temporary)
            for file in specification['files']:
                total = 0
                with urlopen(file['url'], timeout=60) as source, (stage/file['name']).open('wb') as target:
                    while block := source.read(1048576):
                        total += len(block)
                        if total > file['size']:
                            raise SystemExit('Model exceeds manifest size')
                        target.write(block)
                if total != file['size'] or digest(stage/file['name']) != file['sha256']:
                    raise SystemExit('Model checksum mismatch')
            if args.model == BASELINE:
                (stage/'htdemucs.yaml').write_text("models: ['955717e8']\n")
            stage.rename(output)
    if args.bundle:
        if args.model == BASELINE:
            raise SystemExit('Import the original .th file directly for the baseline model')
        with zipfile.ZipFile(args.bundle, 'x', compression=zipfile.ZIP_STORED) as bundle:
            for file in specification['files']:
                bundle.write(output/file['name'], file['name'])
        print('Offline model ZIP created:', args.bundle)
    print('Pinned model installed:', output)


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


if __name__ == '__main__':
    main()
