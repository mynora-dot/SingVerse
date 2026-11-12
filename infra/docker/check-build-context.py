#!/usr/bin/env python3
"""Audit Docker's actual root-context filtering without printing source contents."""
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def audit(dockerfile, name):
    with tempfile.TemporaryDirectory(prefix='singverse-context-audit-') as directory:
        output = Path(directory) / 'context.tar'
        try:
            result = subprocess.run(['docker', 'buildx', 'build', '--progress=plain', '--file', str(dockerfile),
                                     '--output', 'type=tar,dest=' + str(output), str(ROOT)], text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        except subprocess.TimeoutExpired as error:
            details = error.stderr.decode(errors='replace') if isinstance(error.stderr, bytes) else error.stderr or ''
            raise RuntimeError('Docker scratch context audit timed out: ' + details[-3000:]) from error
        if result.returncode:
            raise RuntimeError('Docker scratch context audit failed: ' + result.stderr[-3000:])
        with tarfile.open(output) as archive:
            members = archive.getmembers()
            if any(member.issym() or member.islnk() for member in members):
                raise ValueError('actual Docker context contains a symbolic or hard link')
            files = [member for member in members if member.isfile()]
        relatives = {PurePosixPath(member.name).relative_to('context') for member in files}
        banned_parts = {'.git', '.tools', 'node_modules', '.venv', '__pycache__', 'data', 'secrets', 'bin', 'dist'}
        for relative in relatives:
            if banned_parts.intersection(relative.parts) or 'singverse-java-backend' in relative.parts:
                raise ValueError('actual Docker context includes a forbidden runtime/build directory')
            if (relative.name.startswith('.env') and relative.name != '.env.example') or relative.suffix in {
                    '.db', '.sqlite', '.sqlite3', '.key', '.pem', '.log', '.p12', '.pfx', '.tmp'}:
                raise ValueError('actual Docker context includes a private runtime file')
        for relative in ('VERSION', 'services/singverse-go-backend/go.mod',
                         'services/singverse-go-backend/cmd/singverse/main.go',
                         'services/singverse-frontend/package-lock.json',
                         'database/migrations/sqlite/202611070001_initial_schema.sql'):
            if PurePosixPath(relative) not in relatives:
                raise ValueError('actual Docker context omits a required build input')
        size = sum(member.size for member in files)
        if size > 64 * 1024 * 1024:
            raise ValueError('allowlisted Docker source context exceeds 64 MiB')
        if name == 'go-specific' and any('singverse-compute' in path.parts for path in relatives):
            raise ValueError('Go-specific Docker context contains compute sources')
        print(json.dumps({'result': 'passed', 'rules': name, 'files': len(files), 'bytes': size,
                          'checks': ['actual-scratch-copy', 'root-ignore-fallback',
                                     'no-private-runtime-data', 'no-generated-artifacts', 'required-build-inputs']}),
              flush=True)


def main():
    link = ROOT / '.dockerignore'
    expected = ROOT / 'infra/docker/build/.dockerignore'
    if not link.is_file() or link.is_symlink() or link.read_bytes() != expected.read_bytes():
        raise ValueError('root .dockerignore must match the maintained infra rules byte for byte')
    with tempfile.TemporaryDirectory(prefix='singverse-root-context-') as directory:
        dockerfile = Path(directory) / 'context-audit.Dockerfile'
        dockerfile.write_text('FROM scratch\nCOPY . /context/\n')
        audit(dockerfile, 'root-fallback')
    build = ROOT / 'infra/docker/build'
    with tempfile.NamedTemporaryFile(prefix='context-audit-', suffix='.Dockerfile', dir=build) as stream:
        dockerfile = Path(stream.name)
        stream.write(b'FROM scratch\nCOPY . /context/\n')
        stream.flush()
        ignore = dockerfile.with_name(dockerfile.name + '.dockerignore')
        ignore.write_bytes((build / 'app.Dockerfile.dockerignore').read_bytes())
        try:
            audit(dockerfile, 'go-specific')
        finally:
            ignore.unlink()


if __name__ == '__main__':
    main()
