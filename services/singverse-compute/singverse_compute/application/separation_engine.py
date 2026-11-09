"""Profile-aware execution, runtime qualification and independent CPU recovery."""
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
import uuid
import wave
from array import array

from ..core.errors import ComputeError
from ..domain.audio_separation import BACKENDS, CLEAN, DSP, LEGACY, MDX, MDX_FILE, MDX_SHA256, recipe, threads
from ..infrastructure.audio_worker import AudioWorker
from ..infrastructure.transcoding.process import run


class SeparationEngine:
    def __init__(self, audio):
        self.audio, self.settings = audio, audio.settings
        self.root = audio.root / 'separation' if audio.root else None
        self.environments, self.qualified, self.cooldown = {}, {}, {}
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.thread = None
        self.operation = {'state': 'IDLE'}
        if self.root:
            self.root.mkdir(parents=True, exist_ok=True)
            try:
                self.qualified = json.loads((self.root/'qualified.json').read_text())
            except (OSError, ValueError):
                pass
        self.worker = AudioWorker(self.command, env={**audio._environment(),
            'PYTHONPATH': str(Path(__file__).resolve().parents[2]),
            'OPENBLAS_NUM_THREADS': '1', 'OMP_NUM_THREADS': str(threads(self.settings.audio_threads))},
            memory_limit=self.settings.audio_memory_bytes,
            idle_seconds=max(1, self.settings.audio_worker_idle_seconds))

    def python(self, cpu):
        return str((self.settings.audio_cpu_python if cpu else self.settings.audio_accelerator_python) or sys.executable)

    def environment(self, cpu, refresh=False):
        key = (self.python(cpu), bool(cpu))
        with self.lock:
            if key not in self.environments or refresh:
                try:
                    result = json.loads(run([key[0], '-m', 'singverse_compute.infrastructure.separation_worker', '--probe', *(['--cpu-only'] if cpu else [])],
                        timeout=30, limit=65536, memory_limit=min(self.settings.audio_memory_bytes, 1024**3), env=self.worker.env))
                except Exception:
                    result = {'fingerprint': 'unavailable', 'backends': [], 'standaloneCpu': False, 'versions': {}}
                self.environments[key] = result
            return self.environments[key]

    def model_ready(self):
        return self.audio.models.model_valid(MDX)

    def key(self, backend):
        from ..core.build_info import build_info
        environment = self.environment(backend in ('cpu', 'openvino-cpu'))
        digest = MDX_SHA256
        if backend == 'rknn':
            digest = self.audio.models.inference_digest('uvr-mdxnet-9482-rknn')
        return backend, hashlib.sha256(json.dumps([backend, environment['fingerprint'], digest,
            recipe(CLEAN)['recipeFingerprint'], build_info().get('sourceDigest'),
            'backend-precision-v1', 'spectral-numpy-v1'], sort_keys=True).encode()).hexdigest()

    def command(self, key):
        backend, identity = key
        command = [self.python(backend in ('cpu', 'openvino-cpu')), '-m',
                   'singverse_compute.infrastructure.separation_worker', '--backend', backend,
                   '--model', str(self.audio.models.model_path(MDX)/MDX_FILE),
                   '--cache', str(self.root/'compiled'/identity), '--threads', str(threads(self.settings.audio_threads))]
        if backend == 'rknn':
            command.extend(['--rknn-model', str(self.audio.models.model_path('uvr-mdxnet-9482-rknn'))])
        return command

    def entries(self, *, only_cpu=False):
        present = self.model_ready()
        cpu = self.environment(True)
        accelerated = {} if only_cpu else self.environment(False)
        entries = []
        for backend in BACKENDS:
            if only_cpu and backend not in ('cpu', 'openvino-cpu'):
                continue
            environment = cpu if backend in ('cpu', 'openvino-cpu') else accelerated
            detected = backend in environment.get('backends', [])
            state, reason = 'RUNTIME_MISSING', '未安装此推理运行环境'
            if detected:
                state, reason = ('UNVERIFIED', '请试跑此模型和后端') if present else ('MODEL_MISSING', '请下载固定的 MDX 9482 ONNX 模型')
                if backend == 'cpu' and not environment.get('standaloneCpu'):
                    state, reason = 'RUNTIME_MISSING', '请配置独立的 CPU ORT 环境，不能以 GPU ORT 包替代'
                if backend == 'rknn' and not self.audio.models.model_valid('uvr-mdxnet-9482-rknn'):
                    state, reason = 'MODEL_MISSING', '请先部署通过转换校验的 RKNN 模型'
                if state == 'UNVERIFIED':
                    key = self.key(backend)[1]
                    record = self.qualified.get(key, {})
                    if record:
                        state, reason = record['state'], record.get('reason', '')
                    if self.cooldown.get(key, 0) > time.monotonic():
                        state, reason = 'FAILED', '后端执行失败，暂时熔断；可重新试跑'
            entries.append({'id': backend, 'state': state, 'reason': reason, 'detected': detected,
                            'modelId': MDX, 'standaloneCpu': environment.get('standaloneCpu', False)})
        return entries

    def selected(self, requested=None, *, require_cpu=True):
        available = {item['id'] for item in self.entries(only_cpu=requested in ('cpu', 'openvino-cpu')) if item['state'] == 'AVAILABLE'}
        if require_cpu and 'cpu' not in available:
            return None
        choices = (requested,) if requested and requested != 'auto' else ('tensorrt', 'cuda', 'migraphx', 'openvino', 'mlx', 'coreml', 'directml', 'rknn', 'cpu')
        return next((item for item in choices if item in available), None)

    def status(self, refresh=False):
        if refresh:
            self.environment(True, True)
            if self.python(False) != self.python(True):
                self.environment(False, True)
        backends = self.entries()
        selected = self.selected()
        dsp = bool(self.root and self.audio.transcoding.available)
        return {'version': 1, 'profiles': [
            {'id': DSP, 'ready': dsp, 'modelId': 'center-cancel-v1', 'backend': 'cpu',
             'reason': '中心抵消；可能损伤鼓和贝斯并留下混响，不适合单声道'},
            {'id': CLEAN, 'ready': selected is not None, 'modelId': MDX, 'backend': selected,
             'reason': '' if selected else '需要安装 MDX 模型并先通过独立 CPU 试跑'},
            {'id': LEGACY, 'ready': self.audio.models.selected() is not None, 'modelId': 'htdemucs'}],
            'backends': backends, 'selected': selected, 'operation': dict(self.operation),
            'cpuEnvironment': self.environment(True), 'acceleratorEnvironment': self.environment(False),
            'outputFormats': ['aac', 'flac'], 'previewSeconds': 12}

    def check_resources(self, backend):
        from .audio_models import memory_available
        available = memory_available()
        required = min(self.settings.audio_memory_bytes, (1536 if backend == 'cpu' else 768)*1024**2)
        if available and available + self.worker.resident() < required:
            raise ComputeError(429, 'Available RAM is below the separation recipe working budget')

    def admit(self, profile, backend=None, source_audio_id=None):
        if source_audio_id is not None or profile == DSP:
            return 'cpu'
        selected = self.selected(backend)
        if selected is None and backend not in (None, '', 'auto', 'cpu') and self.audio.transcoding.backends.cpu_fallback:
            selected = self.selected('cpu')
        if selected is None:
            raise ComputeError(422, 'Install MDX and qualify its independent CPU runtime and selected backend first')
        self.check_resources(selected)
        return selected

    def infer(self, source, output, task):
        backend = task.backend
        key = self.key(backend)
        try:
            return self.worker.request(key, {'op': 'separate', 'source': str(source), 'output': str(output)},
                cancel=task.cancel, event=lambda value: self.audio.inference_event(task, value))
        except ComputeError as error:
            # Input/IO errors must not be disguised as hardware failures. Worker death,
            # timeout and host/device OOM are explicit recoverable accelerator failures.
            recoverable = any(value in error.message.lower() for value in (
                'hardware accelerator', 'worker exited', 'worker connection', 'worker unavailable',
                'execution or output budget', 'memory budget', 'provider', 'initializ'))
            if backend == 'cpu' or not recoverable or task.cancel.is_set() or not task.cpu_fallback:
                raise
            self.cooldown[key[1]] = time.monotonic()+600
            if not self.selected('cpu'):
                raise ComputeError(503, 'Independent CPU fallback is unavailable') from error
            self.check_resources('cpu')
            shutil.rmtree(output, ignore_errors=True)
            task.backend = 'cpu'
            task.fallback_reason = 'ACCELERATOR_EXECUTION_FAILED'
            task.result['fallbackReason'] = task.fallback_reason
            return self.worker.request(self.key('cpu'), {'op': 'separate', 'source': str(source), 'output': str(output)},
                cancel=task.cancel, event=lambda value: self.audio.inference_event(task, value))

    def process(self, source, network, audio, duration, work, target, task):
        from ..core.build_info import build_info
        from ..infrastructure.dsp_audio import process
        task.result['build'] = build_info()
        specification = task.recipe
        reused = specification['sourceAudioId'] is not None
        identifier = specification['sourceAudioId'] or (audio[0].id if len(audio) == 1 else '')
        parts = identifier.split(':')
        stream = next((item for item in audio if item.id == ':'.join(parts[:2])), None)
        if stream is None or (not reused and len(audio) != 1):
            raise ComputeError(422, 'Select a confirmed source audio track or a single-track song')
        channel = parts[2] if len(parts) == 3 else None
        if (channel or specification['separationProfile'] == DSP and not reused) and stream.channels != 2:
            raise ComputeError(422, 'DSP_NOT_SUITABLE：中心抵消和声道提取需要真正的双声道音轨')
        seconds = min(12, duration) if specification['preview'] else duration
        start = max(0, (duration-seconds)*.35) if specification['preview'] else 0
        options = dict(ffmpeg=self.settings.ffmpeg, stream=stream.index, channel=channel, cancel=task.cancel,
                       memory_limit=min(self.settings.audio_memory_bytes, 512*1024**2),
                       output_format=specification['outputFormat'], start=start, seconds=seconds, network=network)
        if reused or specification['separationProfile'] == DSP:
            with self.audio.stage(task, 'filterEncode'):
                copied = False
                if reused and not channel and not specification['preview'] and stream.codec == ('flac' if specification['outputFormat'] == 'flac' else 'aac'):
                    try:
                        run([self.settings.ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-protocol_whitelist',
                             'file,http,tcp' if network else 'file', '-i', str(source), '-map', f'0:{stream.index}',
                             '-vn', '-c:a', 'copy', '-y', '-f', 'flac' if specification['outputFormat'] == 'flac' else 'ipod', str(target)],
                            timeout=7200, limit=32768, cancel=task.cancel, memory_limit=options['memory_limit'])
                        output = self.audio.transcoding.probe.read_audio(str(target), cancel=task.cancel)
                        if abs(output.duration-seconds) > .25 or abs(output.start_time) > .025:
                            raise ComputeError(422, 'Source copy needs timeline normalization')
                        copied = True
                    except ComputeError:
                        target.unlink(missing_ok=True)
                        if task.cancel.is_set():
                            raise
                signal = {'streamCopy': True} if copied else process(source, target, center_cancel=not reused, **options)
            task.result.update(backend='cpu', model='source-audio' if reused else 'center-cancel-v1',
                               modelVersion='1', signal=signal, sourceReused=reused)
        else:
            wav = work/'source.wav'
            self.audio._extract(source, network, stream.index, start, seconds, wav, task)
            with self.audio.stage(task, 'separate'):
                evidence = self.infer(wav, work/'out', task)
            backing = evidence.pop('backing')
            task.result.update(backend=task.backend, model=MDX, modelVersion=MDX_SHA256[:12],
                               modelDigest=MDX_SHA256, inference=evidence, precision=evidence['precision'])
            gain = min(1., .99/evidence['peak']) if evidence['peak'] else 1.
            with self.audio.stage(task, 'encode'):
                task.result['signal'] = process(backing, target, ffmpeg=self.settings.ffmpeg,
                    center_cancel=False, cancel=task.cancel, output_format=specification['outputFormat'], peak_gain=gain,
                    memory_limit=min(self.settings.audio_memory_bytes, 512*1024**2))
            task.result['outputGain'] = gain
        return stream, seconds, start

    def qualify(self, backend):
        if backend not in BACKENDS or not self.model_ready():
            raise ComputeError(422, 'Install the fixed model and supported runtime first')
        environment = self.environment(backend in ('cpu', 'openvino-cpu'), True)
        if backend not in environment.get('backends', []) or backend == 'cpu' and not environment.get('standaloneCpu'):
            raise ComputeError(422, 'Independent CPU runtime or accelerator is unavailable')
        if backend != 'cpu' and self.selected('cpu', require_cpu=False) != 'cpu':
            raise ComputeError(422, 'Qualify independent CPU fallback before an accelerator')
        from .audio_roles import AudioTask
        task = AudioTask(str(uuid.uuid4()))
        task.backend, task.cpu_fallback = backend, False
        acquired = False
        key = self.key(backend)
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
            with tempfile.TemporaryDirectory(prefix='trial-', dir=self.root) as directory:
                path = Path(directory)/'input.wav'
                with wave.open(str(path), 'wb') as stream:
                    stream.setparams((2, 2, 44100, 0, 'NONE', 'not compressed'))
                    stream.writeframes(array('h', (int(4000*math.sin(2*math.pi*(440 if i%2 else 880)*(i//2)/44100)) for i in range(44100*8*2))).tobytes())
                result = self.infer(path, Path(directory)/'out', task)
                if result['frames'] != 44100*8 or not math.isfinite(result['peak']):
                    raise ComputeError(422, 'Qualification output is not finite or aligned')
                comparison = None
                if backend != 'cpu':
                    reference = self.worker.request(self.key('cpu'), {'op': 'separate', 'source': str(path),
                        'output': str(Path(directory)/'reference'), 'compare': result['backing']}, cancel=task.cancel)
                    comparison = reference['relativeError']
                    if not math.isfinite(comparison) or comparison > .02:
                        raise ComputeError(422, 'Accelerator differs from CPU reference by more than the 2% numerical qualification limit')
            self.qualified[key[1]] = {'state': 'AVAILABLE', 'backend': backend,
                'elapsedMs': round((time.monotonic()-started)*1000), 'placement': result['placement'], 'relativeError': comparison}
            self.cooldown.pop(key[1], None)
        except Exception as error:
            if not isinstance(error, ComputeError) or error.status != 429:
                self.qualified[key[1]] = {'state': 'FAILED', 'backend': backend, 'reason': str(error)[:256]}
            raise
        finally:
            if acquired:
                with self.audio.lock:
                    self.audio.tasks.pop(task.id, None)
                    self.audio.active = False
                    self.audio.slot.release()
            with self.lock:
                if self.root:
                    temporary = self.root/'qualified.part'
                    temporary.write_text(json.dumps(self.qualified))
                    temporary.replace(self.root/'qualified.json')

    def test(self, backend, *, wait_for_slot=False):
        if self.thread and self.thread.is_alive():
            raise ComputeError(429, 'Separation qualification is already running')
        self.operation = {'state': 'TESTING', 'backend': backend}
        def work():
            try:
                while not self.closed.is_set():
                    try:
                        self.qualify(backend)
                        break
                    except ComputeError as error:
                        if not wait_for_slot or error.status != 429:
                            raise
                        self.operation = {'state': 'WAITING', 'backend': backend}
                        self.closed.wait(2)
                if self.closed.is_set():
                    return
                self.operation = {'state': 'SUCCEEDED', 'backend': backend}
            except Exception as error:
                self.operation = {'state': 'FAILED', 'backend': backend,
                                  'error': error.message if isinstance(error, ComputeError) else str(error)[:256]}
        self.thread = threading.Thread(target=work, daemon=True, name='separation-qualification')
        self.thread.start()
        return dict(self.operation)

    def start(self):
        if self.root and self.model_ready() and self.selected('cpu', require_cpu=False) != 'cpu':
            self.test('cpu', wait_for_slot=True)

    def close(self):
        self.closed.set()
        self.worker.close()
        if self.thread:
            self.thread.join(timeout=5)
