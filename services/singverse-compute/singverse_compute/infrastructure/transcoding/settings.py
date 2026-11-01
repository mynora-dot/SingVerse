"""Node-local execution configuration; never stores business data or credentials."""
import json
from pathlib import Path
import threading
from ...core.errors import ComputeError

BACKENDS = ('deployment','auto','software','qsv','vaapi','nvenc','amf','videotoolbox','rkmpp')

class HardwareSettings:
    def __init__(self, root, backends):
        self.path = root / 'hardware-settings.json' if root else None
        self.backends = backends
        self.lock = threading.RLock()
        self.deployment = backends.preferred
        self.override = 'deployment'
        self.cpu_fallback = True
        self.revision = 0
        if self.path and self.path.exists():
            try:
                saved = json.loads(self.path.read_text(encoding='utf-8'))
                if saved['backend'] not in BACKENDS or not isinstance(saved['revision'], int) or saved['revision'] < 0:
                    raise ValueError()
                self.override, self.revision = saved['backend'], saved['revision']
                self.cpu_fallback = saved.get('cpuFallback', True)
                if not isinstance(self.cpu_fallback, bool):
                    raise ValueError()
            except (ValueError, KeyError, TypeError) as error:
                raise ValueError('Invalid hardware configuration; preserve the file and repair it') from error
        self.backends.cpu_fallback = self.cpu_fallback
        self.backends.preferred = self.deployment if self.override == 'deployment' else self.override

    def read(self):
        with self.lock:
            detected = self.backends.detected()
            effective = 'auto' if detected and self.backends.preferred == 'software' else self.backends.preferred
            return {'backend':self.override, 'effectiveBackend':effective,
                    'deploymentBackend':self.deployment, 'revision':self.revision,
                    'cpuFallback':self.cpu_fallback, 'detectedBackends':detected,
                    'writable':self.path is not None}

    def test(self, backend):
        selected = self.deployment if backend == 'deployment' else backend
        return self.backends.test(selected)

    def save(self, backend, revision, cpu_fallback=None):
        if not self.path:
            raise ComputeError(503, 'Configure a transcode cache directory before saving hardware settings')
        if backend == 'software' and self.backends.detected():
            raise ComputeError(422, '检测到加速硬件，请使用自动选择；CPU 仅用于自动降级')
        # Run expensive verification outside the settings lock; CAS prevents stale writes afterwards.
        if backend not in ('deployment','auto','software'):
            if not self.test(backend)['passed']:
                raise ComputeError(422, 'Hardware encoder test failed; check drivers and device permissions')
        with self.lock:
            if revision != self.revision:
                raise ComputeError(409, 'Hardware settings changed; reload before saving')
            updated = {'backend':backend, 'revision':revision+1, 'cpuFallback': self.cpu_fallback if cpu_fallback is None else cpu_fallback}
            temp = self.path.with_suffix('.tmp')
            try:
                with temp.open('w', encoding='utf-8') as output:
                    json.dump(updated, output)
                    output.flush()
                    import os
                    os.fsync(output.fileno())
                temp.replace(self.path)
            except OSError as error:
                raise ComputeError(503, 'Hardware configuration could not be saved') from error
            self.backends.invalidate()
            self.cpu_fallback = updated['cpuFallback']
            self.backends.cpu_fallback = self.cpu_fallback
            self.override, self.revision = backend, revision+1
            self.backends.preferred = self.deployment if backend == 'deployment' else backend
            return self.read()
