"""Explicit, pinned model installation and environment-bound inference qualification."""
from pathlib import Path
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import wave
import zipfile
from copy import deepcopy
from .audio_catalog import BASELINE, OPENVINO, FAST, MODELS, manifest, model_for_backend, model_digest
from ..core.build_info import build_info, includes_backend
import math
from array import array
from urllib.request import urlopen

from ..core.errors import ComputeError
from .audio_roles import AudioTask, _rms

NAME = '955717e8-8726e21a.th'
DIGEST = '8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4'
URL = 'https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/' + NAME
CONFIG = "models: ['955717e8']\n"
MAX_BYTES = 100 * 1024**2
MODEL_BYTES = 84141911
BACKENDS = ('cpu', 'cuda', 'rocm', 'mps', 'openvino', 'xpu', 'rknn')
# Query in a subprocess: importing a GPU runtime must not crash the API process.
PROBE = '''import json, platform
from importlib.metadata import version
out={'devices':{},'versions':{}}
try:
 import torch
 out['versions']={n:version(n) for n in ('torch','torchaudio','demucs')}
 out['devices']['cpu']={'name':platform.processor() or platform.machine()}
 if torch.cuda.is_available():
  p=torch.cuda.get_device_properties(0)
  out['devices']['rocm' if torch.version.hip else 'cuda']={'name':p.name,'memoryBytes':p.total_memory,'driver':str(torch.version.hip or torch.version.cuda)}
 if hasattr(torch.backends,'mps') and torch.backends.mps.is_available(): out['devices']['mps']={'name':'Apple GPU'}
 if hasattr(torch,'xpu') and torch.xpu.is_available(): out['devices']['xpu']={'name':torch.xpu.get_device_name(0)}
except Exception: out['error']='RUNTIME_MISSING_OR_INCOMPATIBLE'
try:
 import openvino as ov
 out['versions']['openvino']=version('openvino')
 core=ov.Core()
 for device in core.available_devices:
  if device.startswith('GPU'):
   details={'name':str(core.get_property(device,'FULL_DEVICE_NAME')),'id':device}
   for key in ('DRIVER_VERSION','DEVICE_UUID'):
    try: details[key]=str(core.get_property(device,key))
    except Exception: pass
   out['devices']['openvino']=details
   break
except Exception: pass
print(json.dumps(out))'''


def memory_available():
    value = 0
    if platform.system() == 'Darwin':
        try:
            import re
            report = subprocess.run(['vm_stat'], capture_output=True, text=True, timeout=2, check=True).stdout
            page_size = int(re.search(r'page size of (\d+) bytes', report).group(1))
            pages = sum(int(re.search(rf'{name}:\s+(\d+)', report).group(1)) for name in ('Pages free', 'Pages inactive', 'Pages speculative'))
            return page_size * pages
        except (OSError, ValueError, AttributeError, subprocess.SubprocessError):
            return 0
    try:
        lines = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        value = int(lines['MemAvailable'].split()[0]) * 1024
        maximum = Path('/sys/fs/cgroup/memory.max').read_text().strip()
        if maximum != 'max':
            value = min(value, max(0, int(maximum) - int(Path('/sys/fs/cgroup/memory.current').read_text())))
    except (OSError, ValueError, KeyError):
        try:
            value = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
        except (ValueError, OSError, AttributeError):
            pass
    return value


class AudioModels:
    def __init__(self, audio):
        self.audio = audio
        self.repo = audio.repo
        self.root = audio.root / 'models' if audio.root else None
        self.lock = threading.RLock()
        self.cancel = threading.Event()
        self.thread = None
        self.startup_thread = None
        self.startup_stop = threading.Event()
        self.startup_check = {'state': 'IDLE'}
        self.operation = {'state': 'IDLE', 'bytes': 0}
        self.selection = 'auto'
        self.qualified = {}
        self.environment = None
        if self.root:
            self.root.mkdir(parents=True, exist_ok=True)
            saved = self.root / 'settings.json'
            if saved.is_file():
                data = json.loads(saved.read_text())
                self.selection = data.get('selection', 'auto')
                self.qualified = data.get('qualified', {})
            record = self.root / 'operation.json'
            if record.is_file():
                self.operation = json.loads(record.read_text())
                if self.operation.get('state') in ('DOWNLOADING', 'IMPORTING', 'VERIFYING', 'TESTING'):
                    self.operation = {'state': 'FAILED', 'error': 'NODE_RESTARTED', 'bytes': 0}
            # Only remove feature-owned temporary directories.
            if self.repo and self.repo.parent.is_dir():
                for path in self.repo.parent.glob('.singverse-model-*'):
                    if path.is_dir() and not path.is_symlink():
                        shutil.rmtree(path)

    def persist(self):
        with self.lock:
            if self.root:
                for name, value in [('settings', {'selection': self.selection, 'qualified': self.qualified}), ('operation', self.operation)]:
                    temporary = self.root / (name + '.part')
                    temporary.write_text(json.dumps(value))
                    temporary.replace(self.root / (name + '.json'))

    def probe(self, refresh=False):
        with self.lock:
            if self.environment is None or refresh:
                from ..infrastructure.hardware import accelerators
                accelerators.cache_clear()
                from ..infrastructure.transcoding.process import run
                try:
                    self.environment = json.loads(run([sys.executable, '-c', PROBE], timeout=30, limit=65536))
                except Exception:
                    self.environment = {'devices': {}, 'versions': {}, 'error': 'RUNTIME_MISSING_OR_INCOMPATIBLE'}
                hardware = []
                for card in Path('/sys/class/drm').glob('card[0-9]*/device'):
                    try:
                        hardware.append({'vendor': (card/'vendor').read_text().strip(), 'device': (card/'device').read_text().strip()})
                    except OSError:
                        pass
                try:
                    hardware.append({'board': Path('/proc/device-tree/model').read_text().strip('\x00')})
                except OSError:
                    pass
                try:
                    driver = Path('/proc/driver/nvidia/version').read_text().strip()
                except OSError:
                    driver = ''
                processor = platform.processor()
                try:
                    if platform.system() == 'Linux':
                        processor = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.lower().startswith(('model name', 'hardware'))), processor)
                    elif platform.system() == 'Darwin':
                        processor = subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True, timeout=2, check=True).stdout.strip()
                except (OSError, subprocess.SubprocessError):
                    pass
                if 'cpu' in self.environment.get('devices', {}):
                    self.environment['devices']['cpu']['name'] = processor or platform.machine()
                self.environment.update(system=platform.system(), release=platform.release(), architecture=platform.machine(), processor=processor, hardware=hardware, driver=driver, inferenceVersion='2', python=platform.python_version(), build=build_info())
                self.environment['fingerprint'] = hashlib.sha256(json.dumps(self.environment, sort_keys=True).encode()).hexdigest()
            return self.environment

    def writable(self):
        if not self.repo:
            return False
        try:
            parent = self.repo if self.repo.exists() else self.repo.parent
            with tempfile.TemporaryFile(dir=parent):
                pass
            return True
        except OSError:
            return False

    def entries(self):
        environment = self.probe()
        installed = self.audio.runtime_available
        values = []
        for backend in BACKENDS:
            device = environment.get('devices', {}).get(backend)
            item = {'id': backend, 'device': device, 'state': 'RUNTIME_MISSING', 'reason': '未检测到兼容的推理运行环境'}
            if not device:
                vendor = {'cuda': '0x10de', 'rocm': '0x1002', 'xpu': '0x8086', 'openvino': '0x8086'}.get(backend)
                for hardware in environment.get('hardware', []):
                    if vendor and hardware.get('vendor') == vendor:
                        item['device'] = {'name': f"PCI {vendor}:{hardware.get('device', '')}"}
                    if backend == 'rknn' and 'rk3566' in hardware.get('board', '').lower():
                        item['device'] = {'name': hardware['board']}
            if backend in ('rknn', 'xpu'):
                item.update(state='MODEL_UNSUPPORTED', reason='当前htdemucs尚无通过验收的此后端模型；QSV和硬件检测不代表AI推理可用')
            elif not includes_backend(backend):
                item.update(state='IMAGE_MISSING', reason='当前镜像未包含此推理后端，请更换对应镜像并重新部署')
            elif backend == 'openvino':
                present = self.model_valid(OPENVINO)
                runtime = environment.get('versions', {}).get('openvino') == '2025.4.0' and getattr(self.audio, 'dependencies_available', False)
                if device and runtime:
                    item.update(state='UNVERIFIED' if present else 'MODEL_MISSING', reason='请运行模型试跑' if present else '请下载Intel官方OpenVINO转换模型')
                    record = self.qualified.get(backend, {})
                    if present and record.get('environment') == environment['fingerprint'] and record.get('digest') == model_digest(OPENVINO):
                        item.update(state=record['state'], reason=record.get('reason', ''), elapsedMs=record.get('elapsedMs'))
                item.update(modelId=OPENVINO, pipeline='openvino-gpu+cpu-stft')
            elif device:
                item.update(state='MODEL_MISSING' if not installed else 'UNVERIFIED', reason='请下载模型或修复固定版本运行环境' if not installed else '请运行模型试跑')
                record = self.qualified.get(backend, {})
                if installed and record.get('environment') == environment['fingerprint'] and record.get('digest') == DIGEST:
                    item.update(state=record['state'], reason=record.get('reason', ''), elapsedMs=record.get('elapsedMs'))
            if backend == 'mps':
                item['pipeline'] = 'mps+cpu-stft'
                item['reason'] += '；Demucs频谱变换在CPU执行，神经网络使用MPS'
            values.append(item)
        from ..infrastructure.hardware import accelerators
        physical = accelerators()['audio']
        for item in values:
            item['detected'] = item['id'] != 'cpu' and (bool(item.get('device')) or item['id'] in physical)
        return values

    def selected(self):
        available = {item['id'] for item in self.entries() if item['state'] == 'AVAILABLE'}
        hardware = any(item.get('detected') for item in self.entries())
        selection = 'auto' if self.selection == 'cpu' and hardware else self.selection
        fallback = getattr(self.audio.transcoding.backends, 'cpu_fallback', True)
        choices = ('cuda', 'rocm', 'openvino', 'mps') if selection == 'auto' else (selection,)
        if not hardware or fallback:
            choices += ('cpu',)
        return next((item for item in choices if item in available), None)

    def status(self, refresh=False):
        self.probe(refresh)
        free = shutil.disk_usage(self.root).free if self.root else 0
        memory = memory_available()
        return {'environment': self.environment, 'memoryAvailableBytes': memory, 'diskFreeBytes': free,
                'resourceReady': (not memory or memory >= self.audio.settings.audio_memory_bytes) and free >= self.audio.settings.minimum_free_bytes,
                'limits': {'audioProcessBudgetBytes': self.audio.settings.audio_memory_bytes, 'minimumFreeBytes': self.audio.settings.minimum_free_bytes},
                'model': {'id': 'htdemucs', 'version': '955717e8', 'filename': NAME, 'sha256': DIGEST, 'size': MODEL_BYTES, 'maxBytes': MAX_BYTES,
                          'downloadUrl': URL, 'installed': self.weights_valid(), 'runtimeReady': self.audio.runtime_available, 'writable': self.writable()},
                'startupCheck': dict(self.startup_check),
                'cpuFallback': getattr(self.audio.transcoding.backends, 'cpu_fallback', True), 'selection': self.selection, 'selected': self.selected(), 'backends': self.entries(), 'operation': dict(self.operation),
                'fastClassifier': {**self.model_status(FAST), 'ready': bool(getattr(self.audio, 'fast', None) and self.audio.fast.available)},
                'models': [self.model_status(key) for key in MODELS if key != FAST], 'build': build_info(), 'deployment': self.deployment(),
                'instructions': {'openvino': 'make compute-audio-openvino-setup', 'cpu': 'make compute-setup compute-audio-setup',
                                 'cuda': 'make compute-audio-cuda-setup', 'rocm': 'make compute-audio-rocm-setup',
                                 'mps': 'make compute-setup compute-audio-setup'}}

    def model_path(self, model_id):
        manifest(model_id)
        return self.repo if model_id == BASELINE else self.repo.parent / model_id if self.repo else None

    def model_valid(self, model_id):
        if model_id == BASELINE:
            return self.weights_valid()
        path = self.model_path(model_id)
        if path is None:
            return False
        try:
            files = manifest(model_id)['files']
            stamps = tuple((f['name'], f['size'], f['sha256'], (path/f['name']).stat().st_size, (path/f['name']).stat().st_mtime_ns) for f in files)
            cached = getattr(self, '_manifest_cache', {})
            if cached.get(model_id, (None,))[0] != stamps:
                valid = all((path/f['name']).stat().st_size == f['size'] and self.audio.digest(path/f['name']) == f['sha256'] for f in files)
                cached[model_id] = (stamps, valid)
                self._manifest_cache = cached
            return cached[model_id][1]
        except OSError:
            return False

    def model_writable(self, model_id):
        path = self.model_path(model_id)
        if path is None:
            return False
        try:
            with tempfile.TemporaryFile(dir=path if path.exists() else path.parent):
                return True
        except OSError:
            return False

    def model_status(self, model_id):
        value = deepcopy(manifest(model_id))
        value.update(installed=self.model_valid(model_id), writable=self.model_writable(model_id))
        return value

    def deployment(self):
        variant = build_info()['variant']
        profiles = []
        for backend in ('cpu', 'cuda', 'rocm', 'openvino'):
            suffix = f' -f compose.audio-{backend}.yaml'
            profiles.append({'backend': backend, 'image': f'singverse-compute:<版本>-{backend}',
                             'command': f'COMPUTE_AUDIO_RUNTIME={backend} docker compose -f compose.compute.yaml{suffix} up -d --build',
                             'included': includes_backend(backend), 'requiresRedeploy': variant not in ('native', backend)})
        return {'profiles': profiles, 'note': '在实际节点执行部署；镜像架构由Docker选择，GPU后端需显式配置。宿主驱动不由网页安装。'}

    def weights_valid(self):
        try:
            model = self.repo / NAME
            stamp = (model.stat().st_size, model.stat().st_mtime_ns, (self.repo/'htdemucs.yaml').stat().st_mtime_ns)
            if getattr(self, 'weight_stamp', None) != stamp:
                self.weight_valid = stamp[0] == MODEL_BYTES and self.audio.digest(model) == DIGEST and (self.repo/'htdemucs.yaml').read_text() == CONFIG
                self.weight_stamp = stamp
            return self.weight_valid
        except (OSError, TypeError):
            return False

    def choose(self, backend):
        if backend not in ('auto', *BACKENDS):
            raise ComputeError(400, 'Unknown audio inference backend')
        if backend == 'cpu' and any(item.get('detected') for item in self.entries()):
            raise ComputeError(422, '检测到加速硬件，CPU 仅用于自动降级')
        if backend != 'auto' and not any(b['id'] == backend and b['state'] == 'AVAILABLE' for b in self.entries()):
            raise ComputeError(422, 'Backend has not passed model qualification')
        with self.lock:
            self.selection = backend
            self.persist()
        return self.status()

    def begin(self, state, model_id=BASELINE):
        manifest(model_id)
        if not self.root:
            raise ComputeError(503, 'Configure COMPUTE_TRANSCODE_ROOT')
        with self.lock:
            if self.operation['state'] in ('DOWNLOADING', 'IMPORTING', 'VERIFYING', 'TESTING'):
                raise ComputeError(409, 'Model operation already active')
            self.cancel = threading.Event()
            self.operation = {'state': state, 'bytes': 0, 'modelId': model_id}
            self.persist()

    def stage(self):
        if not self.model_writable(self.operation.get('modelId', BASELINE)):
            raise ComputeError(409, 'Model directory is missing or read only; configure a writable model volume')
        if shutil.disk_usage(self.repo.parent).free < manifest(self.operation.get('modelId', BASELINE))['maxBytes'] * 2 + self.audio.settings.minimum_free_bytes:
            raise ComputeError(507, 'Insufficient model disk space')
        return tempfile.TemporaryDirectory(prefix='.singverse-model-', dir=self.repo.parent)

    def install(self, stream, stage):
        digest = hashlib.sha256()
        with (stage / NAME).open('wb') as target:
            while block := stream.read(1024**2):
                if self.cancel.is_set():
                    raise ComputeError(409, 'Model installation cancelled')
                self.operation['bytes'] += len(block)
                if self.operation['bytes'] > MAX_BYTES:
                    raise ComputeError(413, 'Model exceeds manifest size limit')
                target.write(block)
                digest.update(block)
        self.finish_install(stage, digest.hexdigest())

    def finish_install(self, stage, digest):
        with self.lock:
            self.operation['state'] = 'VERIFYING'
            self.persist()
            if digest != DIGEST or (stage / NAME).stat().st_size != MODEL_BYTES:
                raise ComputeError(422, 'Model SHA-256 mismatch')
            if self.cancel.is_set():
                raise ComputeError(409, 'Model installation cancelled')
            (stage / 'htdemucs.yaml').write_text(CONFIG)
            # Valid existing models remain untouched. Corrupt installations require explicit operator repair.
            if self.repo.is_dir() and not any(self.repo.iterdir()):
                stage.replace(self.repo)
            elif self.repo.exists():
                if self.audio.digest(self.repo / NAME) != DIGEST or (self.repo / 'htdemucs.yaml').read_text() != CONFIG:
                    raise ComputeError(409, 'Existing model directory differs; preserve or move it before installation')
            else:
                stage.rename(self.repo)
            self.audio.model_stamp = None
            self.operation['state'] = 'SUCCEEDED'
            self.persist()

    def failed(self, error):
        with self.lock:
            self.operation.update(state='CANCELLED' if self.cancel.is_set() else 'FAILED', error=error.message if isinstance(error, ComputeError) else '模型下载或执行失败，请检查网络、运行环境及设备权限')
            self.persist()

    def copy_manifest_file(self, stream, stage, file):
        digest, size = hashlib.sha256(), 0
        with (stage / file['name']).open('wb') as target:
            while block := stream.read(1024**2):
                if self.cancel.is_set():
                    raise ComputeError(409, 'Model installation cancelled')
                size += len(block)
                if size > file['size']:
                    raise ComputeError(413, 'Model file exceeds manifest size')
                target.write(block)
                digest.update(block)
                self.operation['bytes'] += len(block)
        if size != file['size'] or digest.hexdigest() != file['sha256']:
            raise ComputeError(422, 'Model SHA-256 mismatch')

    def finish_bundle(self, stage, model_id):
        with self.lock:
            self.operation['state'] = 'VERIFYING'
            self.persist()
            if self.cancel.is_set():
                raise ComputeError(409, 'Model installation cancelled')
            destination = self.model_path(model_id)
            if destination.exists() and any(destination.iterdir()):
                if not self.model_valid(model_id):
                    raise ComputeError(409, 'Existing model directory differs; preserve or move it before installation')
            else:
                stage.replace(destination)
            self._manifest_cache = {}
            self.operation.update(state='SUCCEEDED')
            self.persist()

    def import_bundle(self, archive, stage, model_id):
        files = manifest(model_id)['files']
        try:
            with zipfile.ZipFile(archive) as bundle:
                entries = bundle.infolist()
                expected = {file['name']: file for file in files}
                if len(entries) != len(expected) or {item.filename for item in entries} != set(expected):
                    raise ComputeError(422, 'Model bundle must contain exactly the manifest files at its root')
                for item in entries:
                    if item.file_size != expected[item.filename]['size'] or item.flag_bits & 1 or (item.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ComputeError(422, 'Invalid model bundle member')
                    with bundle.open(item) as stream:
                        self.copy_manifest_file(stream, stage, expected[item.filename])
            self.finish_bundle(stage, model_id)
        except (zipfile.BadZipFile, RuntimeError, OSError) as error:
            raise ComputeError(422, 'Invalid model ZIP bundle') from error

    def download(self, model_id=BASELINE):
        specification = manifest(model_id)
        if not (self.writable() if model_id == BASELINE else self.model_writable(model_id)):
            raise ComputeError(409, 'Model directory is missing or read only')
        self.begin('DOWNLOADING', model_id)
        def worker():
            try:
                with self.stage() as temporary:
                    stage = Path(temporary)
                    if model_id == BASELINE:
                        with urlopen(URL, timeout=20) as response:
                            self.install(response, stage)
                    else:
                        for file in specification['files']:
                            with urlopen(file['url'], timeout=20) as response:
                                self.copy_manifest_file(response, stage, file)
                        self.finish_bundle(stage, model_id)
            except Exception as error:
                self.failed(error)
        self.thread = threading.Thread(target=worker, daemon=True, name='audio-model-download')
        self.thread.start()
        return dict(self.operation)

    def start(self):
        """Qualify installed models once, in the background; never download weights."""
        if not self.root or self.startup_thread:
            return
        self.startup_check = {'state': 'CHECKING'}
        def worker():
            try:
                self.probe(True)
                entries = self.entries()
                hardware = any(item.get('detected') for item in entries)
                fallback = getattr(self.audio.transcoding.backends, 'cpu_fallback', True)
                candidates = [item['id'] for item in entries if item['state'] == 'UNVERIFIED'
                              and (item['id'] != 'cpu' or not hardware or fallback)]
                candidates.sort(key=lambda backend: backend == 'cpu')
                for backend in candidates:
                    while not self.startup_stop.is_set():
                        with self.audio.transcoding.lock, self.audio.lock, self.lock:
                            if self.startup_stop.is_set():
                                return
                            busy = (self.audio.active or time.monotonic() < self.audio.transcoding.realtime_reserved_until
                                    or any(t.state in ('ACCEPTED', 'RUNNING') for t in self.audio.transcoding.tasks.values())
                                    or self.operation['state'] in ('DOWNLOADING', 'IMPORTING', 'VERIFYING', 'TESTING'))
                            self.startup_check = {'state': 'WAITING' if busy else 'TESTING', 'backend': backend}
                            if not busy:
                                if any(item['id'] == backend and item['state'] == 'UNVERIFIED' for item in self.entries()):
                                    self.test(backend)
                                    trial = self.thread
                                else:
                                    trial = None
                                break
                        self.startup_stop.wait(2)
                    else:
                        return
                    if trial:
                        trial.join()
                self.startup_check = {'state': 'COMPLETED'}
            except Exception:
                self.startup_check = {'state': 'FAILED', 'reason': '自动检测未完成，请重新检测'}
            finally:
                if self.startup_stop.is_set():
                    self.startup_check = {'state': 'CANCELLED'}
        self.startup_thread = threading.Thread(target=worker, daemon=True, name='audio-startup-check')
        self.startup_thread.start()

    def test(self, backend):
        environment = self.probe(True)
        if backend not in ('cpu', 'cuda', 'rocm', 'mps', 'openvino') or backend not in environment.get('devices', {}):
            raise ComputeError(422, 'No qualified runtime for this backend/model')
        model_id = model_for_backend(backend)
        if not includes_backend(backend) or not self.audio.backend_runtime_available(backend):
            raise ComputeError(422, 'Install the pinned runtime and model first')
        self.begin('TESTING', model_id)
        def worker():
            task = AudioTask(str(uuid.uuid4()))
            task.cancel = self.cancel
            task.backend = backend
            acquired = False
            started = time.monotonic()
            try:
                with self.audio.transcoding.lock, self.audio.lock:
                    if time.monotonic() < self.audio.transcoding.realtime_reserved_until or any(t.state in ('ACCEPTED', 'RUNNING') for t in self.audio.transcoding.tasks.values()):
                        raise ComputeError(429, 'Realtime playback has priority')
                    acquired = self.audio.slot.acquire(blocking=False)
                    if not acquired:
                        raise ComputeError(429, 'Audio worker busy')
                    self.audio.active = True
                    self.audio.tasks[task.id] = task
                self.check_resources(backend)
                with tempfile.TemporaryDirectory(dir=self.root) as temporary:
                    path = Path(temporary) / 'test.wav'
                    with wave.open(str(path), 'wb') as out:
                        out.setparams((2, 2, 44100, 0, 'NONE', 'not compressed'))
                        out.writeframes(array('h', (int(4000 * math.sin(2*math.pi*440*(i//2)/44100)) for i in range(44100*8*2))).tobytes())
                    vocal, backing = self.audio._separate(path, Path(temporary) / 'out', task)
                    for stem in (vocal, backing):
                        with wave.open(str(stem), 'rb') as value:
                            if abs(value.getnframes()/value.getframerate()-8) > .025 or value.getnchannels() != 2:
                                raise ComputeError(422, 'Qualification output timeline mismatch')
                        if not math.isfinite(_rms(stem)):
                            raise ComputeError(422, 'Qualification output is not finite')
                    if _rms(vocal) + _rms(backing) < .001:
                        raise ComputeError(422, 'Qualification produced silent stems')
                if self.cancel.is_set():
                    raise ComputeError(409, 'Qualification cancelled')
                self.qualified[backend] = {'state': 'AVAILABLE', 'environment': environment['fingerprint'], 'digest': model_digest(model_id), 'elapsedMs': round((time.monotonic()-started)*1000)}
                self.operation.update(state='SUCCEEDED', backend=backend)
            except Exception as error:
                if not self.cancel.is_set() and not (isinstance(error, ComputeError) and error.status == 429):
                    self.qualified[backend] = {'state': 'FAILED', 'environment': environment['fingerprint'], 'digest': model_digest(model_id), 'reason': error.message if isinstance(error, ComputeError) else '模型试跑失败'}
                self.failed(error)
            finally:
                task.state = 'CANCELLED' if self.cancel.is_set() else 'SUCCEEDED'
                if acquired:
                    with self.audio.lock:
                        self.audio.tasks.pop(task.id, None)
                        self.audio.active = False
                        self.audio.slot.release()
                self.persist()
        self.thread = threading.Thread(target=worker, daemon=True, name='audio-model-test')
        self.thread.start()
        return dict(self.operation)

    def check_resources(self, backend):
        available = memory_available()
        if available and available < self.audio.settings.audio_memory_bytes:
            raise ComputeError(422, 'Available RAM is below the configured audio process budget')
        device = self.probe().get('devices', {}).get(backend, {})
        if backend in ('cuda', 'rocm') and device.get('memoryBytes', 0) < 2*1024**3:
            raise ComputeError(422, 'GPU memory below 2 GiB')
        if backend in ('cuda', 'rocm'):
            from ..infrastructure.transcoding.process import run
            try:
                free = int(run([sys.executable, '-c', 'import torch; print(torch.cuda.mem_get_info()[0])'], timeout=30, limit=4096))
            except Exception as error:
                raise ComputeError(422, 'GPU free memory cannot be determined') from error
            if free < 2*1024**3:
                raise ComputeError(422, 'GPU free memory below 2 GiB')

    def stop(self):
        with self.lock:
            self.startup_stop.set()
            self.cancel.set()
        return dict(self.operation)

    def close(self):
        self.stop()
        if self.thread:
            self.thread.join(timeout=5)
        if self.startup_thread:
            self.startup_thread.join(timeout=5)
