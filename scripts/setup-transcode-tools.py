#!/usr/bin/env python3
"""Install a checksum-pinned official portable FFmpeg into this repository only."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile
import sys

if sys.version_info < (3, 12):
    raise SystemExit("Python 3.12+ required; use services/singverse-compute/.venv/bin/python")

root = Path(__file__).resolve().parents[1]
lock = json.loads((root/'services/singverse-compute/tools/jellyfin-ffmpeg.lock.json').read_text())
key = platform.system() + '-' + platform.machine()
if key not in lock['assets']:
    raise SystemExit('No pinned FFmpeg asset for this platform; configure COMPUTE_FFMPEG/COMPUTE_FFPROBE explicitly')
name, digest = lock['assets'][key]
output = root/'.tools/jellyfin-ffmpeg'
if (output/'installed.sha256').is_file() and (output/'installed.sha256').read_text() == digest:
    print('Pinned Jellyfin media tools already installed in .tools/jellyfin-ffmpeg')
    raise SystemExit(0)
if output.exists():
    raise SystemExit('Existing media tools differ; preserve or move .tools/jellyfin-ffmpeg before installing another version')
output.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
    temp = Path(temporary)
    archive = temp/name
    checksum = hashlib.sha256()
    with urlopen(lock['repository']+'/releases/download/v'+lock['version']+'/'+name, timeout=60) as source, archive.open('wb') as target:
        total=0
        while chunk := source.read(1048576):
            total += len(chunk)
            if total > 512*1024*1024:
                raise SystemExit('Media tool archive exceeds size budget')
            checksum.update(chunk)
            target.write(chunk)
    if checksum.hexdigest() != digest:
        raise SystemExit('Media tool checksum mismatch')
    extracted = temp/'extract'; extracted.mkdir()
    if name.endswith('.zip'):
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                path = Path(member.filename)
                if path.is_absolute() or '..' in path.parts or ':' in member.filename:
                    raise SystemExit('Invalid archive entry')
            source.extractall(extracted)
    else:
        with tarfile.open(archive) as source:
            source.extractall(extracted, filter='data')
    extension = '.exe' if os.name == 'nt' else ''
    ffmpeg = list(extracted.rglob('ffmpeg'+extension))
    ffprobe = list(extracted.rglob('ffprobe'+extension))
    if len(ffmpeg) != 1 or len(ffprobe) != 1 or ffmpeg[0].parent != ffprobe[0].parent:
        raise SystemExit('Unexpected portable media tool layout')
    # Preserve the complete portable bundle, including sibling libraries and license files.
    children=list(extracted.iterdir())
    bundle=children[0] if len(children)==1 and children[0].is_dir() else extracted
    relative=ffmpeg[0].parent.relative_to(bundle)
    if str(relative) not in ('.','bin'):
        raise SystemExit('Unexpected portable binary directory')
    shutil.move(str(bundle), output)
    (output/'installed.sha256').write_text(digest)
print('Installed pinned Jellyfin media tools in .tools/jellyfin-ffmpeg')
