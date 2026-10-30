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
        self.revision = 0
        if self.path and self.path.exists():
            try:
                saved = json.loads(self.path.read_text(encoding='utf-8'))
                if saved['backend'] not in BACKENDS or not isinstance(saved['revision'], int) or saved['revision'] < 0:
                    raise ValueError()
                self.override, self.revision = saved['backend'], saved['revision']
            except (ValueError, KeyError, TypeError) as error:
                raise ValueError('Invalid hardware configuration; preserve the file and repair it') from error
        self.backends.preferred = self.deployment if self.override == 'deployment' else self.override

    def read(self):
        with self.lock:
            return {'backend':self.override, 'effectiveBackend':self.backends.preferred,
                    'deploymentBackend':self.deployment, 'revision':self.revision,
                    'writable':self.path is not None}

    def test(self, backend):
        selected = self.deployment if backend == 'deployment' else backend
        return self.backends.test(selected)

    def save(self, backend, revision):
        if not self.path:
            raise ComputeError(503, 'Configure a transcode cache directory before saving hardware settings')
        # Run expensive verification outside the settings lock; CAS prevents stale writes afterwards.
        if backend not in ('deployment','auto','software'):
            if not self.test(backend)['passed']:
                raise ComputeError(422, 'Hardware encoder test failed; check drivers and device permissions')
        with self.lock:
            if revision != self.revision:
                raise ComputeError(409, 'Hardware settings changed; reload before saving')
            updated = {'backend':backend, 'revision':revision+1}
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
            self.override, self.revision = backend, revision+1
            self.backends.preferred = self.deployment if backend == 'deployment' else backend
            return self.read()
