#!/usr/bin/env python3
"""Publish checked OCI artifacts without rebuilding; refuse different immutable tags."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
VARIANTS = {'app': ['amd64', 'arm64'], 'base': ['amd64', 'arm64'],
            'cpu': ['amd64', 'arm64'], 'uvr': ['amd64', 'arm64'],
            'cuda': ['amd64'], 'rocm': ['amd64'], 'rocm-native': ['amd64'],
            'openvino': ['amd64'], 'rknn': ['arm64']}


def config(env=os.environ):
    version = (ROOT / 'VERSION').read_text().strip().removesuffix('-SNAPSHOT')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('VERSION must be a semantic release version, optionally ending in -SNAPSHOT')
    if env.get('RELEASE_TAG') != 'v' + version:
        raise ValueError('GitHub Release tag must equal v + VERSION without -SNAPSHOT')
    ghcr = env.get('GHCR_NAMESPACE') or env.get('GITHUB_REPOSITORY_OWNER', '').lower()
    hub, acr, host = (env.get(key, '') for key in ('DOCKERHUB_NAMESPACE', 'ACR_NAMESPACE', 'ACR_REGISTRY'))
    for value in (ghcr, hub, acr):
        if not re.fullmatch(r'[a-z0-9]+(?:[._-][a-z0-9]+)*', value):
            raise ValueError('Registry namespaces must be configured as lowercase names')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*(?::[0-9]+)?', host):
        raise ValueError('ACR_REGISTRY must be a registry host, without scheme or namespace')
    for key in ('GHCR_TOKEN', 'DOCKERHUB_USERNAME', 'DOCKERHUB_TOKEN', 'ACR_USERNAME', 'ACR_PASSWORD'):
        if not env.get(key):
            raise ValueError('Missing publishing secret: ' + key)
    return version, ['ghcr.io/' + ghcr, 'docker.io/' + hub, host + '/' + acr]


def run(*args, input=None):
    return subprocess.check_output(args, input=input, text=True).strip()


def remote_digest(reference):
    result = subprocess.run(['skopeo', 'inspect', '--raw', 'docker://' + reference],
                            capture_output=True, text=True)
    if result.returncode == 0:
        return 'sha256:' + hashlib.sha256(result.stdout.encode()).hexdigest()
    # Only an explicitly missing manifest is safe to create. Auth/network errors fail.
    if not any(code in result.stderr.lower() for code in ('manifest unknown', 'manifest_unknown', 'name unknown', 'name_unknown')):
        raise RuntimeError('Cannot inspect remote image; refusing to assume it is absent')
    return None


def ensure(reference, digest, publish):
    existing = remote_digest(reference)
    if existing is not None:
        if existing != digest:
            raise ValueError('Immutable image already exists with different content: ' + reference)
        return
    publish()
    if remote_digest(reference) != digest:
        raise ValueError('Published image digest mismatch: ' + reference)


def manifest(entries):
    return json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json',
                       'manifests': entries}, separators=(',', ':')).encode()


def layout(directory, variant, raw, destination):
    """Combine checked OCI blobs without changing image or index bytes."""
    blobs = destination / 'blobs/sha256'
    blobs.mkdir(parents=True)
    for arch in VARIANTS[variant]:
        with tarfile.open(directory / f'{variant}-{arch}' / 'image.tar') as archive:
            for member in archive:
                name = member.name.removeprefix('./')
                if not re.fullmatch(r'blobs/sha256/[a-f0-9]{64}', name):
                    continue
                if not member.isfile():
                    raise ValueError('OCI blob must be a regular file')
                target = destination / name
                if not target.exists():
                    with archive.extractfile(member) as source, target.open('wb') as output:
                        shutil.copyfileobj(source, output)
                    with target.open('rb') as source:
                        digest = hashlib.sha256()
                        for chunk in iter(lambda: source.read(1024 * 1024), b''):
                            digest.update(chunk)
                        if digest.hexdigest() != target.name:
                            raise ValueError('OCI blob checksum mismatch')
    digest = hashlib.sha256(raw).hexdigest()
    (blobs / digest).write_bytes(raw)
    descriptor = {'mediaType': 'application/vnd.oci.image.index.v1+json',
                  'digest': 'sha256:' + digest, 'size': len(raw),
                  'annotations': {'org.opencontainers.image.ref.name': 'release'}}
    (destination / 'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}')
    (destination / 'index.json').write_text(json.dumps({'schemaVersion': 2, 'manifests': [descriptor]}))


def publish(directory):
    version, prefixes = config()
    records = {}
    # Publish architecture images first. Each artifact was built and tested once.
    for variant, arches in VARIANTS.items():
        image = 'singverse' if variant == 'app' else 'singverse-compute'
        suffix = '' if variant == 'app' else '-' + variant
        entries = []
        for arch in arches:
            archive = directory / f'{variant}-{arch}' / 'image.tar'
            raw = subprocess.check_output(['skopeo', 'inspect', '--raw', 'oci-archive:' + str(archive)])
            digest = 'sha256:' + hashlib.sha256(raw).hexdigest()
            descriptor = {'mediaType': json.loads(raw)['mediaType'], 'digest': digest, 'size': len(raw),
                          'platform': {'os': 'linux', 'architecture': arch}}
            entries.append(descriptor)
            for prefix in prefixes:
                ref = f'{prefix}/{image}:{version}{suffix}-{arch}'
                ensure(ref, digest, lambda ref=ref, archive=archive: run('skopeo', 'copy', '--all', '--preserve-digests',
                       'oci-archive:' + str(archive), 'docker://' + ref))
        records[(image, suffix)] = (variant, manifest(entries))
    # Copy deterministic OCI indexes, keeping bytes and digests identical at all destinations.
    for (image, suffix), (variant, raw) in records.items():
        digest = 'sha256:' + hashlib.sha256(raw).hexdigest()
        with tempfile.TemporaryDirectory(prefix='singverse-release-') as temporary:
            destination = Path(temporary) / 'layout'
            layout(directory, variant, raw, destination)
            for prefix in prefixes:
                ref = f'{prefix}/{image}:{version}{suffix}'
                ensure(ref, digest, lambda ref=ref: run('skopeo', 'copy', '--all', '--preserve-digests',
                       'oci:' + str(destination) + ':release', 'docker://' + ref))
    # No latest tag is changed until every immutable version passed validation.
    for (image, suffix), (_, raw) in records.items():
        for prefix in prefixes:
            run('skopeo', 'copy', '--all', '--preserve-digests',
                f'docker://{prefix}/{image}:{version}{suffix}', f'docker://{prefix}/{image}:latest{suffix}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate', 'publish'))
    parser.add_argument('--artifacts', type=Path, default=Path('artifacts'))
    args = parser.parse_args()
    version, _ = config()
    if args.command == 'publish':
        publish(args.artifacts)
    print('Validated release ' + version)


if __name__ == '__main__':
    main()
