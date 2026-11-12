#!/usr/bin/env python3
"""Confirm the OCI artifact contains exactly the runtime tested by Docker."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile


def check(path, image):
    runtime = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    with tarfile.open(path) as archive:
        members = {member.name.removeprefix('./'): member for member in archive}
        def read(name):
            member = members[name]
            if not member.isfile():
                raise ValueError('OCI input is not a regular file')
            with archive.extractfile(member) as source:
                return source.read()
        def blob(descriptor, decode=False):
            digest = descriptor['digest']
            if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
                raise ValueError('Invalid OCI digest')
            member = members['blobs/sha256/' + digest[7:]]
            if not member.isfile() or member.size != descriptor['size']:
                raise ValueError('OCI blob size or type mismatch')
            value = hashlib.sha256()
            content = []
            with archive.extractfile(member) as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    value.update(chunk)
                    if decode:
                        content.append(chunk)
            if value.hexdigest() != digest[7:]:
                raise ValueError('OCI blob checksum mismatch')
            if decode:
                return json.loads(b''.join(content))
        index = json.loads(read('index.json'))
        if len(index['manifests']) != 1:
            raise ValueError('Expected one platform artifact without extra attestations')
        manifest = blob(index['manifests'][0], decode=True)
        config = blob(manifest['config'], decode=True)
        if runtime['Id'] not in (manifest['config']['digest'], index['manifests'][0]['digest']):
            raise ValueError('OCI runtime differs from the image that passed acceptance')
        if (config['os'], config['architecture']) != (runtime['Os'], runtime['Architecture']):
            raise ValueError('OCI platform differs from tested runtime')
        for layer in manifest['layers']:
            blob(layer)
    print('Verified OCI artifact matches tested image: ' + runtime['Architecture'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    check(args.archive, args.image)
