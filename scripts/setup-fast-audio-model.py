#!/usr/bin/env python3
"""Install only the pinned PANNs classifier; never downloads music or separation weights."""
import argparse
import hashlib
from pathlib import Path
import tempfile
from urllib.request import urlopen

from singverse_compute.application.audio_catalog import FAST, manifest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parents[1] / '.tools' / FAST)
args = parser.parse_args()
file = manifest(FAST)['files'][0]
args.directory.mkdir(parents=True, exist_ok=True)
target = args.directory / file['name']
if target.exists():
    if target.stat().st_size != file['size'] or hashlib.sha256(target.read_bytes()).hexdigest() != file['sha256']:
        raise SystemExit('Existing classifier differs; preserve or move it before installation')
else:
    with tempfile.TemporaryDirectory(dir=args.directory) as temporary:
        stage = Path(temporary) / file['name']
        digest, size = hashlib.sha256(), 0
        with urlopen(file['url'], timeout=30) as response, stage.open('wb') as out:
            while block := response.read(1024**2):
                size += len(block)
                if size > file['size']:
                    raise SystemExit('Classifier exceeds manifest size')
                out.write(block); digest.update(block)
        if size != file['size'] or digest.hexdigest() != file['sha256']:
            raise SystemExit('Classifier checksum mismatch')
        stage.replace(target)
print('PANNs Cnn6 classifier installed and verified')
