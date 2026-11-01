"""Build identity without accessing a container engine or host socket."""
import json
import os
import hashlib
from functools import lru_cache
from pathlib import Path


def build_info():
    path = Path(__file__).with_name('build.json')
    try:
        value = json.loads(path.read_text())
        value['variant'] = os.environ.get('SINGVERSE_COMPUTE_VARIANT', value['variant'])
        return value
    except (OSError, ValueError):
        return {'version': '0.1.0-SNAPSHOT', 'revision': 'native', 'variant': 'native',
                'runtimeLock': native_identity()[0], 'sourceDigest': native_identity()[1]}


@lru_cache(maxsize=1)
def native_identity():
    root = Path(__file__).resolve().parents[2]
    locks = sorted(root.glob('requirements*.lock'))
    sources = sorted((root/'singverse_compute').rglob('*.py'))
    def digest(files):
        value = hashlib.sha256()
        for file in files:
            value.update(str(file.relative_to(root)).encode())
            value.update(file.read_bytes())
        return value.hexdigest()
    return digest(locks), digest(sources)


def includes_backend(backend):
    variant = build_info()['variant']
    return variant == 'native' or backend == 'cpu' and variant != 'base' or backend == variant
