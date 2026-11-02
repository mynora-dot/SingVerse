#!/usr/bin/env python3
"""Explicit download of the pinned UVR baseline; never run during audio tasks."""
import argparse
import hashlib
import os
import tempfile
from pathlib import Path
from urllib.request import urlopen

SHA256 = '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d'
SIZE = 29704738
URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR_MDXNET_9482.onnx'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('.tools/uvr-mdxnet-9482'))
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    target = args.directory/'UVR_MDXNET_9482.onnx'
    if target.exists():
        with target.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != SHA256:
                raise ValueError('Existing model differs; preserve or move it first')
        return
    path = None
    try:
        with tempfile.NamedTemporaryFile(dir=args.directory, delete=False) as output:
            path = Path(output.name)
            with urlopen(URL, timeout=60) as response:
                total = 0
                while block := response.read(1024*1024):
                    total += len(block)
                    if total > SIZE:
                        raise ValueError('Model exceeds pinned size')
                    output.write(block)
        with path.open('rb') as stream:
            if total != SIZE or hashlib.file_digest(stream, 'sha256').hexdigest() != SHA256:
                raise ValueError('Model checksum mismatch')
        # Do not overwrite a model installed concurrently.
        os.link(path, target)
    finally:
        if path:
            path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
