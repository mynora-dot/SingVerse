#!/usr/bin/env python3
"""Export clean Linux AMD64 business images and a no-pull Compose upload ZIP."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ('singverse-java-backend:offline-amd64', 'singverse-frontend:offline-amd64')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def offline_compose():
    compose = (ROOT/'infra/deploy/app/docker-compose.yaml').read_text()
    compose, count = re.subn(r'    build:\n(?:      .*\n)+', '', compose)
    if count != 2 or compose.count(':remote\n') != 2:
        raise ValueError('Unexpected source Compose build/image structure')
    return compose.replace(':remote\n', ':offline-amd64\n    platform: linux/amd64\n    pull_policy: never\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--frontend-only', action='store_true', help='Export only the frontend update, preserving the deployed Compose')
    args = parser.parse_args()
    images = IMAGES[1:] if args.frontend_only else IMAGES
    for name in images:
        platform = subprocess.check_output(
            ['docker', 'image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', name],
            text=True,
        ).strip()
        if platform != 'linux/amd64':
            raise ValueError(f'{name}: expected linux/amd64, got {platform}')
    compose = offline_compose()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='singverse-offline-') as directory:
        staging = Path(directory)
        subprocess.run(['docker', 'image', 'save', '-o', str(staging/'images.tar'), *images], check=True)
        if not args.frontend_only:
            (staging/'docker-compose.yaml').write_text(compose)
        readme = 'README.frontend-update.md' if args.frontend_only else 'README.offline.md'
        (staging/'部署说明.md').write_bytes((ROOT/'infra/deploy/app'/readme).read_bytes())
        (staging/'README.operations.md').write_bytes((ROOT/'infra/docker/OPERATIONS.md').read_bytes())
        (staging/'SHA256SUMS').write_text(''.join(
            f'{digest(path)}  {path.name}\n' for path in sorted(staging.iterdir()) if path.is_file()
        ))
        with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED) as archive:
            prefix = 'SingVerse-Frontend-Update/' if args.frontend_only else 'SingVerse-Business/'
            for directory_name in ((prefix,) if args.frontend_only else (prefix, prefix+'media/')):
                info = zipfile.ZipInfo(directory_name)
                info.create_system = 3
                info.external_attr = (0o40755 << 16) | 0x10
                archive.writestr(info, b'')
            for path in sorted(staging.iterdir()):
                info = zipfile.ZipInfo(prefix+path.name)
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with path.open('rb') as source, archive.open(info, 'w', force_zip64=True) as target:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        target.write(chunk)
    print(f'{args.output}: {args.output.stat().st_size} bytes; SHA256 {digest(args.output)}')


if __name__ == '__main__':
    main()
