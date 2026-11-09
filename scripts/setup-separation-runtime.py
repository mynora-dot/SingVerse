#!/usr/bin/env python3
"""Install an isolated audio worker; never alters the service or global Python."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT/'services/singverse-compute'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=SERVICE/'.venv-audio-cpu')
    parser.add_argument('--lock', type=Path, default=SERVICE/'requirements-separation-cpu.lock')
    parser.add_argument('--role', choices=['cpu', 'accelerator'], default='cpu')
    parser.add_argument('--legacy', action='store_true', help='Also install pinned CPU-only Demucs for advanced-profile fallback')
    args = parser.parse_args()
    if args.legacy and args.role != 'cpu':
        parser.error('--legacy is only supported in the independent CPU environment')
    target = args.directory.resolve()
    if target == Path(sys.prefix).resolve():
        raise ValueError('Choose a separate worker environment')
    if sys.version_info[:2] != (3, 12):
        raise ValueError('Pinned worker dependencies require Python 3.12')
    if not (target/'pyvenv.cfg').is_file():
        if target.exists() and any(target.iterdir()):
            raise ValueError('Refusing to reuse a non-venv directory')
        venv.EnvBuilder(with_pip=True).create(target)
    python = target/('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    subprocess.run([str(python), '-m', 'pip', 'install', '-r', str(args.lock.resolve())], check=True)
    if args.legacy:
        subprocess.run([str(python), '-m', 'pip', 'install', '-r', str(SERVICE/'requirements-audio.lock')], check=True)
    subprocess.run([str(python), '-m', 'pip', 'check'], check=True)
    print(('COMPUTE_AUDIO_CPU_PYTHON=' if args.role == 'cpu' else 'COMPUTE_AUDIO_ACCELERATOR_PYTHON=') + str(python))


if __name__ == '__main__':
    main()
