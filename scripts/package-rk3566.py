#!/usr/bin/env python3
"""Create an allowlisted, credential-free ARM64 compute source deployment ZIP."""
import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SHA256 = '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d'


def write_bundle(output, files):
    """Set explicit POSIX permissions; ZipFile defaults to owner-only files."""
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
        directories = {'singverse-rk3566', 'singverse-rk3566/test-output'}
        for name in files:
            parent = Path('singverse-rk3566') / name
            directories.update(str(p) for p in parent.parents if str(p) != '.')
        for name in sorted(directories):
            info = zipfile.ZipInfo(name + '/')
            info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            bundle.writestr(info, b'')
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo('singverse-rk3566/' + name)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    with args.model.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != SHA256:
            raise ValueError('Expected pinned UVR_MDXNET_9482.onnx')
    files = {}
    for name in ['docker-compose.yaml', 'compose.rknn.yaml', '.env.example', 'README.md']:
        files[name] = (ROOT/'infra/deploy/rk3566'/name).read_bytes()
    compute = ROOT/'services/singverse-compute'
    for path in sorted((compute/'singverse_compute').rglob('*.py')):
        files[str(path.relative_to(ROOT))] = path.read_bytes()
    for path in sorted(compute.glob('requirements*.lock')):
        files[str(path.relative_to(ROOT))] = path.read_bytes()
    for relative in ['services/singverse-compute/Dockerfile', 'scripts/convert-rknn-audio.py']:
        files[relative] = (ROOT/relative).read_bytes()
    files['.dockerignore'] = (ROOT/'infra/docker/build/.dockerignore').read_bytes()
    files['models/uvr-mdxnet-9482/UVR_MDXNET_9482.onnx'] = args.model.read_bytes()
    files['SHA256SUMS'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(files.items())).encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_bundle(args.output, files)
    print(f'{args.output}: {args.output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
