#!/usr/bin/env python3
"""Package a credential-free CUDA source deployment with normalized permissions."""
import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def contents():
    files = {'docker-compose.yaml': (ROOT/'infra/docker/compose/compose.compute.nvidia.yaml').read_bytes(),
             '.dockerignore': (ROOT/'infra/docker/build/.dockerignore').read_bytes()}
    compute = ROOT/'services/singverse-compute'
    for path in sorted((compute/'singverse_compute').rglob('*')):
        if any(part in ('.DS_Store', '__MACOSX') or part.startswith('._') for part in path.relative_to(ROOT).parts):
            continue
        if path.name != 'build.json' and path.is_file() and not path.is_symlink() and path.suffix in ('.py', '.json', '.txt') and '__pycache__' not in path.parts:
            files[str(path.relative_to(ROOT))] = path.read_bytes()
    for path in sorted(compute.glob('requirements*.lock')):
        files[str(path.relative_to(ROOT))] = path.read_bytes()
    for path in [compute/'Dockerfile', *sorted((compute/'docker').glob('*'))]:
        if path.name in ('.DS_Store', '__MACOSX') or path.name.startswith('._'):
            continue
        if path.is_file() and not path.is_symlink():
            files[str(path.relative_to(ROOT))] = path.read_bytes()
    files['部署说明.md'] = (ROOT/'infra/deploy/nvidia/README.md').read_bytes()
    for relative in ['services/singverse-compute/AUDIO-SEPARATION.md',
                     *('scripts/'+name for name in ('setup-separation-runtime.py', 'setup-uvr-model.py',
                         'convert-separation-model.py', 'prepare-separation-calibration.py',
                         'evaluate-separation-conversion.py', 'benchmark-separation.py', 'score-separation.py'))]:
        files[relative] = (ROOT/relative).read_bytes()
    files['README.operations.md'] = (ROOT/'infra/docker/OPERATIONS.md').read_bytes()
    files['SHA256SUMS'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(files.items())).encode()
    return files


def write_bundle(output, files):
    prefix = 'SingVerse-NVIDIA/'
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as bundle:
        directories = {prefix.rstrip('/')}
        for name in files:
            directories.update(str(p) for p in (Path(prefix)/name).parents if str(p) != '.')
        for name in sorted(directories):
            info = zipfile.ZipInfo(name+'/'); info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            bundle.writestr(info, b'')
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(prefix+name); info.create_system = 3
            info.external_attr = 0o100644 << 16; info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_bundle(args.output, contents())
    print(f'{args.output}: {args.output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
