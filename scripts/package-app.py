#!/usr/bin/env python3
"""Package business source, frontend and SQLite migrations without local data."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'SingVerse-Business/'


def contents():
    files = {
        'docker-compose.yaml': (ROOT/'infra/deploy/app/docker-compose.yaml').read_bytes(),
        '部署说明.md': (ROOT/'infra/deploy/app/README.md').read_bytes(),
        'README.offline.md': (ROOT/'infra/deploy/app/README.offline.md').read_bytes(),
        'README.operations.md': (ROOT/'infra/docker/OPERATIONS.md').read_bytes(),
        '.dockerignore': (ROOT/'infra/docker/build/.dockerignore').read_bytes(),
    }
    trees = (
        'services/singverse-frontend/src/', 'services/singverse-frontend/admin/',
        'services/singverse-frontend/party/', 'services/singverse-frontend/public/',
        'services/singverse-frontend/tests/', 'services/singverse-java-backend/src/',
        'services/singverse-java-backend/.mvn/', 'database/migrations/',
    )
    exact = {
        'services/singverse-frontend/'+name for name in
        ('package.json', 'package-lock.json', 'tsconfig.json', 'vite.config.ts')
    } | {
        'services/singverse-java-backend/'+name for name in
        ('Dockerfile', 'mvnw', 'pom.xml', 'nginx/nginx.conf', 'nginx/proxy-scheme.conf')
    } | {
        'scripts/setup-dbmate.sh', 'scripts/check-database-baseline.py', 'database/README.md',
        'database/migrations/sqlite/202611070001_initial_schema.sql',
        'database/migrations/mysql/202611070001_initial_schema.sql',
        'contracts/openapi/auth-test-vectors.json',
        'contracts/openapi/singverse.openapi.json',
    }
    sources = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT,
    ).decode().split('\0')
    for name in sources:
        if any(part in ('.DS_Store', '__MACOSX') or part.startswith('._') for part in Path(name).parts):
            continue
        if name and (name in exact or name.startswith(trees)):
            path = ROOT/name
            if not path.exists() and not path.is_symlink():
                continue  # Removed migrations and source files must not enter a fresh deployment.
            if not path.is_file() or path.is_symlink():
                raise ValueError(f'Expected regular source file: {name}')
            files[name] = path.read_bytes()
    missing = exact - files.keys()
    if missing:
        raise ValueError(f'Missing build inputs: {sorted(missing)}')
    files['SHA256SUMS'] = ''.join(
        f'{hashlib.sha256(data).hexdigest()}  {name}\n'
        for name, data in sorted(files.items())
    ).encode()
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    files = contents()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED) as bundle:
        directories = {PREFIX.rstrip('/'), PREFIX+'media'}
        for name in files:
            directories.update(str(p) for p in (Path(PREFIX)/name).parents if str(p) != '.')
        for name in sorted(directories):
            info = zipfile.ZipInfo(name+'/')
            info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            bundle.writestr(info, b'')
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(PREFIX+name)
            info.create_system = 3
            mode = 0o100755 if name.endswith('/mvnw') or name == 'scripts/setup-dbmate.sh' else 0o100644
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)
    print(f'{args.output}: {args.output.stat().st_size} bytes, {len(files)} files')


if __name__ == '__main__':
    main()
