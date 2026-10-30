#!/usr/bin/env python3
"""Optional official DV verification samples; never part of production images or normal offline tests."""
import hashlib
import json
from pathlib import Path
import tempfile
from urllib.parse import quote
from urllib.request import urlopen

root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'services/singverse-compute/tools/verification-media.lock.json').read_text())
directory=root/'.tools/transcode-fixtures';directory.mkdir(parents=True,exist_ok=True)
for sample in manifest['samples']:
    destination=directory/sample['filename']
    if destination.is_file():
        with destination.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
        if digest==sample['sha256']:
            print(sample['filename']+': checksum verified')
            continue
        raise SystemExit('Existing sample differs; preserve or move it before downloading')
    with tempfile.TemporaryDirectory(dir=directory) as folder:
        output=Path(folder)/'download';digest=hashlib.sha256();total=0
        with urlopen(manifest['source']+quote(sample['path']),timeout=45) as source,output.open('wb') as target:
            while chunk:=source.read(1048576):
                total+=len(chunk)
                if total>150*1024*1024:raise SystemExit('Sample exceeds download budget')
                digest.update(chunk);target.write(chunk)
        if digest.hexdigest()!=sample['sha256']:raise SystemExit('Verification sample checksum mismatch')
        output.replace(destination)
    print(sample['filename']+': downloaded and verified')
