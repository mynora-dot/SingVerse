#!/usr/bin/env python3
"""Export clean Linux AMD64 business images and a no-pull Compose upload ZIP."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ('singverse-go-backend:offline-amd64', 'singverse-frontend:offline-amd64')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def offline_compose(backend_image=IMAGES[0], frontend_image=IMAGES[1]):
    compose = (ROOT/'infra/deploy/app/docker-compose.yaml').read_text()
    compose, count = re.subn(r'    build:\n(?:      .*\n)+', '', compose)
    if count != 2 or compose.count(':remote\n') != 2:
        raise ValueError('Unexpected source Compose build/image structure')
    for service, image in (('singverse-go-backend', backend_image), ('singverse-frontend', frontend_image)):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./:@+-]{0,511}', image):
            raise ValueError('Invalid image reference')
        compose = compose.replace('    image: ' + service + ':remote\n',
                                  '    image: ' + image + '\n    platform: linux/amd64\n    pull_policy: never\n')
    return compose


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--backend-image', default=IMAGES[0], help='explicit already-built Go Linux AMD64 image')
    parser.add_argument('--frontend-image', default=IMAGES[1], help='explicit matching Go-proxy Nginx Linux AMD64 image')
    parser.add_argument('--frontend-only', action='store_true', help='Export only the frontend update, preserving the deployed Compose')
    args = parser.parse_args()
    references = [args.frontend_image] if args.frontend_only else [args.backend_image, args.frontend_image]
    identities = []
    for reference in references:
        if '@' in reference or reference.startswith('sha256:') or ':' not in reference.rsplit('/', 1)[-1]:
            raise ValueError('Offline exports require an explicit repository:tag reference for portable docker load')
        value = json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference], text=True))[0]
        if value.get('Os') != 'linux' or value.get('Architecture') != 'amd64':
            raise ValueError(f'{reference}: expected linux/amd64')
        identities.append({'reference': reference, 'id': value['Id'], 'os': value['Os'], 'architecture': value['Architecture']})
    compose = offline_compose(references[0], references[-1]) if not args.frontend_only else ''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='singverse-offline-') as directory:
        staging = Path(directory)
        # Tags are part of Docker's portable save format. OCI index IDs differ from
        # classic-engine config IDs, so saving only IDs would break older NAS engines.
        subprocess.run(['docker', 'image', 'save', '-o', str(staging/'images.tar'), *references], check=True)
        for identity in identities:
            current = json.loads(subprocess.check_output(
                ['docker', 'image', 'inspect', identity['reference']], text=True))[0]
            if current.get('Id') != identity['id']:
                raise ValueError('An image tag changed while exporting; no deployment ZIP was written')
        if not args.frontend_only:
            (staging/'docker-compose.yaml').write_text(compose)
        (staging/'images.json').write_text(json.dumps({'images': identities}, indent=2) + '\n')
        (staging/'VERSION').write_bytes((ROOT/'VERSION').read_bytes())
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
