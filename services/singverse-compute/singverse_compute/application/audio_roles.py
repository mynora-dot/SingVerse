"""Offline, opt-in vocal analysis. Inputs remain inside the existing media gateway."""
from dataclasses import dataclass, field, asdict
from contextlib import contextmanager
from pathlib import Path
import os
import time
import importlib.util
import math
import wave
import shutil
import sys
import tempfile
import threading
import json
import hashlib
import uuid
from importlib.metadata import version, PackageNotFoundError

from .audio_analysis import classify, sampling_windows, vocal_activity
from .fast_audio_roles import classify_labels, classify_scores, VERSION as FAST_VERSION
from ..core.errors import ComputeError
from ..core.audio_runtime import audio_runtime_profile
from ..infrastructure.transcoding.process import run


def _rms(path: Path) -> float:
    with wave.open(str(path), 'rb') as audio:
        if audio.getsampwidth() != 2:
            raise ComputeError(422, 'Unexpected analysis audio format')
        from array import array
        total = 0
        samples = 0
        while block := audio.readframes(32768):
            count = len(block) // 2
            values = array("h", block)
            if sys.byteorder != "little":
                values.byteswap()
            total += sum(value * value for value in values)
            samples += count
        return math.sqrt(total / samples) / 32768 if samples else 0.0


@dataclass
class AudioTask:
    id: str
    state: str = 'QUEUED'
    result: dict = field(default_factory=dict)
    error: str = ''
    cancel: threading.Event = field(default_factory=threading.Event)

    def view(self):
        return {'id': self.id, 'state': self.state, 'result': self.result, 'error': self.error}


class AudioRoles:
    def __init__(self, transcoding):
        self.transcoding = transcoding
        self.settings = transcoding.settings
        self.repo = self.settings.audio_model_repo
        self.root = self.settings.transcode_root / 'audio-roles' if self.settings.transcode_root else None
        self.tasks: dict[str, AudioTask] = {}
        self.lock = threading.RLock()
        self.collection_lock = threading.Lock()
        self.stopping = False
        self.active = False
        self.thread = None
        self.model_stamp = None
        self.model_valid = False
        self.native_worker = None
        if (audio_runtime_profile() in ('rocm-native', 'legacy') or self.settings.audio_cpu_python) and self.settings.audio_worker_idle_seconds:
            from ..infrastructure.audio_worker import AudioWorker
            self.native_worker = AudioWorker(lambda key: [self.cpu_python() if key[0] == 'cpu' else sys.executable, '-m',
                'singverse_compute.infrastructure.rocm_audio', '--worker', '--model-dir', str(self.repo),
                '--output', str(self.root), '--backend', key[0]], env=self._environment(),
                memory_limit=self.settings.audio_memory_bytes, idle_seconds=self.settings.audio_worker_idle_seconds)
        transcoding.audio_roles = self
        self.slot = threading.BoundedSemaphore(1)
        if self.root:
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            # Only this feature's interrupted workspace is removed; media roots remain read only.
            for partial in self.root.glob('*.part'):
                partial.unlink(missing_ok=True)
            for work in self.root.glob('work-*'):
                if work.is_dir() and not work.is_symlink():
                    shutil.rmtree(work)
            for record in self.root.glob('*.json'):
                try:
                    data = json.loads(record.read_text())
                    if str(uuid.UUID(data['id'])) != record.stem:
                        continue
                    task = AudioTask(data['id'], data['state'], data.get('result', {}), data.get('error', ''))
                    task.signature = data.get('signature', '')
                    if task.state not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                        task.state, task.error = 'FAILED', 'NODE_RESTARTED'
                    self.tasks[task.id] = task
                    if task.error == 'NODE_RESTARTED':
                        self._save(task)
                except (ValueError, KeyError, OSError):
                    continue

    @property
    def available(self):
        manager = getattr(self, 'models', None)
        if manager is None:
            return self.runtime_available
        # Status/pairing must not initiate or queue behind a GPU/runtime probe.
        # The background startup checker or explicit model page establishes it;
        # task submission still validates the selected model and backend itself.
        if manager.environment is None or not manager.lock.acquire(blocking=False):
            return False
        try:
            if not self.runtime_available and not any(manager.model_valid(model) for model in ('htdemucs-openvino', 'uvr-mdxnet-9482-rknn')):
                return False
            return manager.selected() is not None
        finally:
            manager.lock.release()

    @property
    def dependencies_available(self):
        return self.transcoding.available and audio_runtime_profile() is not None

    def backend_runtime_available(self, backend):
        if backend == 'cpu' and getattr(self.settings, 'audio_cpu_python', None):
            return self.runtime_available and self.cpu_runtime().get('valid', False)
        if backend == 'rknn':
            try:
                return self.dependencies_available and version('rknn-toolkit-lite2') == '2.3.2' and self.models.model_valid('uvr-mdxnet-9482-rknn')
            except PackageNotFoundError:
                return False
        if backend != 'openvino':
            return self.runtime_available
        try:
            return self.dependencies_available and version('openvino') == '2025.4.0' and self.models.model_valid('htdemucs-openvino')
        except PackageNotFoundError:
            return False

    @property
    def runtime_available(self):
        independent = bool(getattr(self.settings, 'audio_cpu_python', None) and self.cpu_runtime().get('valid'))
        try:
            if version("demucs") != "4.0.1" and not independent:
                return False
        except PackageNotFoundError:
            if not independent:
                return False
        if not (self.transcoding.available and self.repo and (independent or importlib.util.find_spec('demucs'))):
            return False
        try:
            model = self.repo / '955717e8-8726e21a.th'
            config = self.repo / 'htdemucs.yaml'
            stamp = tuple((p.stat().st_dev, p.stat().st_ino, p.stat().st_size, p.stat().st_mtime_ns,
                           p.stat().st_ctime_ns) for p in (model, config))
            if stamp != self.model_stamp:
                self.model_valid = (config.read_text() == "models: ['955717e8']\n" and self.digest(model) ==
                    '8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4')
                self.model_stamp = stamp
            return self.model_valid and (independent or audio_runtime_profile() is not None)
        except (OSError, PackageNotFoundError):
            return False

    def cpu_python(self):
        return str(getattr(self.settings, 'audio_cpu_python', None) or sys.executable)

    def cpu_runtime(self, refresh=False):
        """Legacy CPU is optional alongside ORT, but never borrowed from a CUDA/HIP wheel."""
        if not hasattr(self, '_legacy_cpu_environment') or refresh:
            try:
                self._legacy_cpu_environment = json.loads(run([self.cpu_python(), '-c',
                    "import json,torch; from importlib.metadata import version; "
                    "print(json.dumps({'valid':not torch.version.cuda and not torch.version.hip and "
                    "version('demucs')=='4.0.1' and version('torch').split('+')[0]=='2.5.1' and "
                    "version('torchaudio').split('+')[0]=='2.5.1', "
                    "'versions':{n:version(n) for n in ('torch','torchaudio','demucs')}}))"],
                    timeout=30, limit=4096, memory_limit=self.settings.audio_memory_bytes, env=self._environment()))
            except Exception:
                self._legacy_cpu_environment = {'valid': False, 'versions': {}}
        return self._legacy_cpu_environment

    def _environment(self):
        from ..domain.audio_separation import threads
        count = str(threads(self.settings.audio_threads))
        return {**os.environ, 'OMP_NUM_THREADS': count,
                'MKL_NUM_THREADS': count, 'OPENBLAS_NUM_THREADS': count, 'PYTORCH_ENABLE_MPS_FALLBACK': '0',
                'PYTHONPATH': str(Path(__file__).resolve().parents[2])}

    def native_key(self, backend):
        model = self.repo / '955717e8-8726e21a.th'
        info = model.lstat()
        import stat
        if not stat.S_ISREG(info.st_mode):
            raise ComputeError(422, 'Expected a regular official model file')
        return backend, info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns

    @contextmanager
    def stage(self, task, name):
        started = time.monotonic()
        with self.lock:
            task.result['stage'] = name
            if task.id in self.tasks:
                self._save(task)
        try:
            yield
        finally:
            with self.lock:
                timings = task.result.setdefault('timingsMs', {})
                timings[name] = round(timings.get(name, 0) + (time.monotonic()-started)*1000, 3)

    def inference_event(self, task, value):
        if value.get('event') != 'AUDIO_INFERENCE_PROGRESS':
            return
        with self.lock:
            task.result['progress'] = {key: value[key] for key in ('completedFrames', 'frames', 'completedForwards') if key in value}
            if task.id in self.tasks and time.monotonic() - getattr(task, 'progress_saved', 0) >= 1:
                task.progress_saved = time.monotonic()
                self._save(task)

    def _run(self, command, task):
        if any(t.target.purpose == 'REALTIME' and t.state in ('RUNNING', 'ACCEPTED') for t in list(self.transcoding.tasks.values())):
            task.cancel.set()
        diagnosis = []
        hardware_error, blocked = [], []
        from ..infrastructure.hardware import accelerator_failure, non_accelerator_failure
        def observe(line):
            if non_accelerator_failure(line): blocked.append(True)
            if accelerator_failure(line): hardware_error.append(line)
            if 'Output channels > 65536 not supported' in line or 'not currently implemented for the MPS' in line:
                diagnosis.append('所选推理后端不支持模型所需算子，请检查硬件及自动降级设置')
            elif 'out of memory' in line.lower():
                diagnosis.append('模型执行内存或显存不足，请释放资源后重试')
        def output(line):
            try:
                value = json.loads(line)
                if value.get('event') == 'AUDIO_INFERENCE_COMPLETE':
                    task.result['inference'] = {key: item for key, item in value.items() if key != 'event'}
            except (ValueError, AttributeError):
                pass
        try:
            return run(command, timeout=7200, limit=65536, cancel=task.cancel,
                memory_limit=self.settings.audio_memory_bytes, error_line=observe, line=output, env=AudioRoles._environment(self))
        except ComputeError as error:
            if hardware_error and not blocked and not non_accelerator_failure(error.message) and not task.cancel.is_set():
                raise ComputeError(422, 'Hardware accelerator execution failed：所选推理后端不支持模型所需算子或设备运行失败') from error
            if diagnosis and not task.cancel.is_set():
                raise ComputeError(422, diagnosis[-1]) from error
            raise

    def _separate(self, wav: Path, output: Path, task: AudioTask):
        from ..infrastructure.hardware import accelerator_failure
        try:
            return AudioRoles._separate_once(self, wav, output, task)
        except ComputeError as error:
            if (getattr(task, 'backend', 'cpu') == 'cpu' or not getattr(task, 'cpu_fallback', False)
                    or task.result.get('fallbackReason') or task.cancel.is_set() or not accelerator_failure(error.message)):
                raise
            if task.backend == 'rknn':
                raise ComputeError(503, 'RKNN MDX requires the AI_CLEAN recipe for same-model ONNX CPU recovery') from error
            if not self.backend_runtime_available('cpu') or not any(item['id'] == 'cpu' and item['state'] == 'AVAILABLE' for item in self.models.entries()):
                raise ComputeError(503, 'CPU 降级不可用，请安装 CPU 模型并完成试跑') from error
            self.models.check_resources('cpu')
            shutil.rmtree(output, ignore_errors=True)
            task.backend = 'cpu'
            task.fallback_reason = 'ACCELERATOR_EXECUTION_FAILED'
            task.result['fallbackReason'] = task.fallback_reason
            return AudioRoles._separate_once(self, wav, output, task)

    def _separate_once(self, wav: Path, output: Path, task: AudioTask):
        backend = getattr(task, 'backend', 'cpu')
        if backend == 'cpu' and getattr(self.settings, 'audio_cpu_python', None) and not getattr(self, 'native_worker', None):
            self._run([self.cpu_python(), '-m', 'singverse_compute.infrastructure.rocm_audio',
                       '--model-dir', str(self.repo), '--output', str(output), '--backend', 'cpu',
                       *(['--backing-only'] if getattr(task, 'mode', '') == 'SEPARATE' else []), str(wav)], task)
        elif backend == 'rknn':
            self._run([sys.executable, '-m', 'singverse_compute.infrastructure.rknn_audio',
                       '--model-dir', str(self.models.model_path('uvr-mdxnet-9482-rknn')),
                       '--output', str(output), str(wav)], task)
        elif backend == 'openvino':
            from .audio_catalog import OPENVINO, model_digest
            from ..core.build_info import build_info
            environment = self.models.probe()
            identity = hashlib.sha256(json.dumps([model_digest(OPENVINO), environment['fingerprint'], build_info()], sort_keys=True).encode()).hexdigest()
            cache = self.models.root / 'compiled' / identity
            device = environment['devices']['openvino']['id']
            self._run([sys.executable, '-m', 'singverse_compute.infrastructure.openvino_audio',
                       '--model-dir', str(self.models.model_path(OPENVINO)), '--cache', str(cache),
                       '--device', device, '--output', str(output), str(wav)], task)
            folder = output / 'htdemucs' / wav.stem
            if not all((folder/name).is_file() for name in ('vocals.wav', 'no_vocals.wav')):
                raise ComputeError(422, 'Model did not produce both stems')
            return folder/'vocals.wav', folder/'no_vocals.wav'
        elif backend == 'mps' and not getattr(self, 'native_worker', None):
            self._run([sys.executable, '-m', 'singverse_compute.infrastructure.mps_audio',
                       '--model-dir', str(self.repo), '--output', str(output), str(wav)], task)
        elif backend in ('rocm', 'cpu', 'cuda', 'mps') and (audio_runtime_profile() == 'rocm-native' or getattr(self, 'native_worker', None)):
            backing_only = getattr(task, 'mode', '') == 'SEPARATE'
            native = getattr(self, 'native_worker', None)
            if native:
                task.result['inference'] = native.request(self.native_key(backend),
                    {'op': 'separate', 'source': str(wav), 'output': str(output), 'backingOnly': backing_only},
                    cancel=task.cancel, event=lambda value: self.inference_event(task, value))
            else:
                self._run([sys.executable, '-m', 'singverse_compute.infrastructure.rocm_audio',
                           '--model-dir', str(self.repo), '--output', str(output), '--backend', backend,
                           *(['--backing-only'] if backing_only else []), str(wav)], task)
        else:
            device = 'cuda' if backend == 'rocm' else backend
            self._run([sys.executable, '-m', 'demucs', '--repo', str(self.repo), '-n', 'htdemucs',
                       '--two-stems', 'vocals', '--clip-mode', 'clamp', '--shifts', '0', '-d', device, '-o', str(output), str(wav)], task)
        folder = output / 'htdemucs' / wav.stem
        vocals, backing = folder / 'vocals.wav', folder / 'no_vocals.wav'
        if not backing.is_file() or (getattr(task, 'mode', '') != 'SEPARATE' and not vocals.is_file()):
            raise ComputeError(422, 'Model did not produce both stems')
        return vocals, backing

    def _extract(self, source, network, stream, start, seconds, output, task):
        with self.stage(task, 'extract'):
            floating = getattr(task, 'recipe', {}).get('separationProfile', 'LEGACY_DEMUCS') != 'LEGACY_DEMUCS'
            self._run([self.settings.ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-protocol_whitelist',
                       'file,http,tcp' if network else 'file', '-ss', str(start), '-i', source,
                       '-map', f'0:{stream}', '-t', str(seconds), '-vn', '-ac', '2', '-ar', '44100',
                       '-af', 'aresample=async=1:first_pts=0,apad', '-c:a', 'pcm_f32le' if floating else 'pcm_s16le', '-y', str(output)], task)

    def _fast_analyze(self, source, network, audio, duration, task):
        def unknown(reason):
            return {'state': 'UNKNOWN', 'reason': reason,
                    'evidence': {'analysisVersion': FAST_VERSION, 'method': 'LABELS', 'windows': []}}
        if len(audio) != 2:
            return unknown('NOT_DUAL_AUDIO')
        titles = (audio[0].title, audio[1].title)
        labeled = classify_labels(titles)
        if labeled:
            labeled['backend'] = 'metadata'
            return labeled
        if duration < 6:
            return unknown('INSUFFICIENT_DURATION')
        if not getattr(self, 'fast', None) or not self.fast.available:
            return unknown('FAST_MODEL_MISSING')
        seconds = min(6.0, duration / 3)
        windows, pairs = [], []
        # Three initial reads; only sparse evidence receives three additional excerpts.
        # No full-song decoding or implicit stem separation, even when still uncertain.
        with tempfile.TemporaryDirectory(prefix='work-' + task.id + '-', dir=self.root) as temporary:
            for fractions in ((.25, .5, .75), (.1, .4, .9)):
                paths = []
                for fraction in fractions:
                    window = {'start': max(0, duration * fraction - seconds / 2), 'seconds': seconds}
                    index = len(windows)
                    windows.append(window)
                    command = [self.settings.ffmpeg, '-nostdin', '-v', 'error', '-protocol_whitelist',
                               'file,http,tcp' if network else 'file', '-ss', str(window['start']), '-i', source]
                    # Read both streams together, including WebDAV.
                    for stream in audio:
                        path = Path(temporary) / f'fast-{index}-{stream.index}.wav'
                        command.extend(['-map', f'0:{stream.index}', '-t', str(seconds), '-vn', '-ac', '1', '-ar', '32000',
                                        '-af', 'aresample=async=1:first_pts=0,apad', '-c:a', 'pcm_s16le', '-y', str(path)])
                        paths.append(path)
                    self._run(command, task)
                scores = self.fast.predict(paths, task.cancel)
                pairs.extend(zip(scores[::2], scores[1::2]))
                result = classify_scores(pairs, titles)
                # Short media cannot provide independent extra six-second excerpts.
                if result['state'] == 'CONFIDENT' or result.get('reason') != 'AMBIGUOUS_VOCALS' or duration < 60:
                    break
        result['evidence']['windows'] = windows
        from .audio_catalog import FAST, manifest
        file = manifest(FAST)['files'][0]
        result.update(backend='cpu', model=FAST, modelVersion=manifest(FAST)['version'], modelDigest=file['sha256'])
        return result

    def submit(self, identifier, reference, mode, backend=None, callback_origin=None,
               separation_profile=None, model_id=None, output_format='aac', preview=False, source_audio_id=None):
        submitted = time.monotonic()
        if str(uuid.UUID(identifier)) != identifier or mode not in ('PROBE', 'FAST', 'ANALYZE', 'SEPARATE'):
            raise ComputeError(400, 'Invalid audio task')
        from ..domain.audio_separation import LEGACY, recipe
        enhanced = mode == 'SEPARATE' and (separation_profile not in (None, LEGACY) or source_audio_id is not None)
        specification = None
        extra = separation_profile is not None or model_id is not None or output_format != 'aac' or preview or source_audio_id is not None
        if extra:
            if mode != 'SEPARATE':
                raise ComputeError(400, 'Separation options apply only to SEPARATE tasks')
            try:
                specification = recipe(separation_profile or LEGACY, model_id, output_format, preview, source_audio_id)
            except ValueError as error:
                raise ComputeError(400, str(error)) from error
        signature_input = [reference, callback_origin, mode, backend]
        if extra:
            signature_input.append(specification)
        signature = hashlib.sha256(json.dumps(signature_input).encode()).hexdigest()
        with self.lock:
            if identifier in self.tasks:
                if getattr(self.tasks[identifier], 'signature', signature) != signature:
                    raise ComputeError(409, 'Task input changed')
                return self.tasks[identifier].view()
            # A running model temporarily consumes RAM. Report queue capacity
            # before checking resources so the next batch member stays queued.
            if self.active:
                raise ComputeError(429, 'Audio model worker busy')
        selected = (self.separation.admit(separation_profile, backend, source_audio_id) if enhanced else
                    None if mode in ('PROBE', 'FAST') else backend or (self.models.selected() if hasattr(self, 'models') else 'cpu'))
        if not enhanced and mode in ('ANALYZE', 'SEPARATE') and hasattr(self, 'models'):
            available = {item['id'] for item in self.models.entries() if item['state'] == 'AVAILABLE'}
            if selected not in available and getattr(self.transcoding.backends, 'cpu_fallback', True) and 'cpu' in available:
                selected = 'cpu'
        if self.stopping or not self.transcoding.available or (not enhanced and mode in ('ANALYZE', 'SEPARATE') and not ((self.backend_runtime_available(selected) if selected else False) if hasattr(self, 'models') else self.available)):
            raise ComputeError(503, 'Audio model is not installed and configured')
        if self.root is None or shutil.disk_usage(self.root).free < self.settings.minimum_free_bytes:
            raise ComputeError(507, 'Insufficient audio working space')
        # GPU/runtime subprocess queries must not hold the realtime scheduling lock.
        if not enhanced and mode in ('ANALYZE', 'SEPARATE') and hasattr(self, 'models'):
            if not any(item['id'] == selected and item['state'] == 'AVAILABLE' for item in self.models.entries()):
                raise ComputeError(422, 'Selected inference backend has not passed qualification')
            try:
                self.models.check_resources(selected)
            except ComputeError as error:
                if error.status == 422:
                    raise ComputeError(429, error.message) from error
                raise
        # Retention scans must not delay the realtime admission lock.
        self.collect()
        with self.transcoding.lock, self.lock:
            if identifier in self.tasks:
                if getattr(self.tasks[identifier], 'signature', signature) != signature:
                    raise ComputeError(409, 'Task input changed')
                return self.tasks[identifier].view()
            if time.monotonic() < self.transcoding.realtime_reserved_until or any(t.state in ('ACCEPTED','RUNNING') for t in list(self.transcoding.tasks.values())):
                raise ComputeError(429, 'Realtime playback has priority')
            if not self.slot.acquire(blocking=False):
                raise ComputeError(429, 'Audio model worker busy')
            self.active = True
            task = AudioTask(identifier)
            task.mode = mode
            task.recipe = specification or (recipe() if mode == 'SEPARATE' else {})
            task.result['timingsMs'] = {'admission': round((time.monotonic()-submitted)*1000, 3)}
            task.backend = selected
            task.cpu_fallback = getattr(self.transcoding.backends, 'cpu_fallback', True)
            if enhanced and specification['separationProfile'] == 'AI_CLEAN' and backend not in (None, '', 'auto', 'cpu') and selected == 'cpu':
                task.fallback_reason = 'ACCELERATOR_UNAVAILABLE'
                task.result['fallbackReason'] = task.fallback_reason
            if not enhanced and backend != 'cpu' and mode in ('ANALYZE', 'SEPARATE') and selected == 'cpu' and hasattr(self, 'models') and any(item.get('detected') for item in self.models.entries()):
                task.fallback_reason = 'ACCELERATOR_NOT_READY'
                task.result['fallbackReason'] = task.fallback_reason
            if mode in ('ANALYZE', 'SEPARATE') and not enhanced:
                task.result.update(self.execution(selected))
            if mode == 'SEPARATE':
                task.result.update(task.recipe)
            task.signature = signature
            self.tasks[identifier] = task
            try:
                self.save(task)
            except OSError as error:
                self.tasks.pop(identifier, None)
                self.active = False
                self.slot.release()
                raise ComputeError(507, 'Audio task state cannot be persisted') from error
        def preempt():
            while not task.cancel.wait(.25):
                if task.state in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                    return
                if any(t.target.purpose == 'REALTIME' and t.state in ('RUNNING', 'ACCEPTED') for t in list(self.transcoding.tasks.values())):
                    task.error = 'REALTIME_PREEMPTED'
                    task.cancel.set()
                    return
        def worker():
            threading.Thread(target=preempt, daemon=True).start()
            try:
                task.state = 'RUNNING'
                self.save(task)
                with self.transcoding.input(reference, callback_origin, 'audio-input') as (source, network):
                    with self.stage(task, 'probe'):
                        media = self.transcoding.probe.read_audio(source, network, cancel=task.cancel)
                    audio = [s for s in media.streams if s.kind == 'audio']
                    if mode == 'PROBE':
                        task.result.update(state='UNKNOWN', reason='PROBED', streams=[asdict(s) for s in audio], duration=media.duration)
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    if mode == 'FAST':
                        result = self._fast_analyze(source, network, audio, media.duration, task)
                        if result['state'] == 'CONFIDENT':
                            result['original'] = audio[result['original']].id
                            result['backing'] = audio[result['backing']].id
                        result.update(streams=[asdict(stream) for stream in audio], duration=media.duration)
                        task.result.update(result)
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    expected = 2 if mode == 'ANALYZE' else 1
                    if mode == 'ANALYZE' and len(audio) != 2:
                        task.result.update(state='UNKNOWN', reason='NOT_DUAL_AUDIO', streams=[asdict(s) for s in audio], duration=media.duration)
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    if (len(audio) != expected and not (enhanced and source_audio_id is not None)) or media.duration <= 0:
                        raise ComputeError(422, 'Unexpected audio stream count or duration')
                    if mode == 'ANALYZE' and media.duration < 60:
                        task.result.update(state='UNKNOWN', reason='INSUFFICIENT_DURATION', streams=[asdict(s) for s in audio], duration=media.duration)
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    estimate = int(media.duration * 44100 * 2 * 2 * 8)
                    if enhanced and (separation_profile == 'DSP_FAST' or source_audio_id):
                        estimate = int((min(12, media.duration) if preview else media.duration) * (352800 if output_format == 'flac' else 32000) + 65536)
                    if estimate + sum(p.stat().st_size for suffix in ('*.m4a', '*.flac') for p in self.root.glob(suffix)) > self.settings.cache_bytes or shutil.disk_usage(self.root).free < estimate + self.settings.minimum_free_bytes:
                        raise ComputeError(507, 'Audio task exceeds disk budget')
                    with tempfile.TemporaryDirectory(prefix='work-'+identifier+'-', dir=self.root) as temp:
                        work = Path(temp)
                        if mode == 'ANALYZE':
                            evidence, windows = [], []
                            for position, seconds in sampling_windows(media.duration):
                                levels, activity = [], []
                                for stream in audio:
                                    wav = work / f'input-{len(evidence)}-{stream.index}.wav'
                                    self._extract(source, network, stream.index, position, seconds, wav, task)
                                    with self.stage(task, 'separate'):
                                        vocal, _ = self._separate(wav, work / f'out-{len(evidence)}-{stream.index}', task)
                                    measured = vocal_activity(wav, vocal)
                                    levels.append(measured['level'])
                                    activity.append(measured)
                                evidence.append(tuple(levels))
                                windows.append({'start': position, 'seconds': seconds, 'tracks': activity})
                                result = classify(evidence, (audio[0].title, audio[1].title))
                                # Easy cases retain the two-window cost; uncertain cases get up to five.
                                if result['state'] == 'CONFIDENT' or result.get('reason') in ('CONFLICTING_WINDOWS', 'CONFLICTING_LABELS', 'BOTH_VOCAL_TRACKS'):
                                    break
                            result['evidence']['windows'] = windows
                            if result['state'] == 'CONFIDENT':
                                result['original'] = audio[result['original']].id
                                result['backing'] = audio[result['backing']].id
                            result['streams'] = [asdict(s) for s in audio]
                            result['duration'] = media.duration
                            task.result.update(result)
                        else:
                            extension = 'flac' if output_format == 'flac' else 'm4a'
                            target = self.root / f'{identifier}.{extension}'
                            temporary = self.root / f'{identifier}.part'
                            if enhanced:
                                selected_stream, expected_duration, offset = self.separation.process(
                                    source, network, audio, media.duration, work, temporary, task)
                            else:
                                expected_duration = min(12, media.duration) if preview else media.duration
                                offset = max(0, (media.duration-expected_duration)*.35) if preview else 0
                                selected_stream = audio[0]
                                wav = work / 'source.wav'
                                self._extract(source, network, audio[0].index, offset, expected_duration, wav, task)
                                with self.stage(task, 'separate'):
                                    _, backing = self._separate(wav, work / 'out', task)
                                codec = ['-c:a', 'flac', '-compression_level', '0', '-f', 'flac'] if output_format == 'flac' else ['-c:a', 'aac', '-b:a', '192k', '-f', 'ipod']
                                with self.stage(task, 'encode'):
                                    self._run([self.settings.ffmpeg, '-nostdin', '-v', 'error', '-i', str(backing),
                                               *codec, '-y', str(temporary)], task)
                            if not temporary.is_file() or temporary.stat().st_size == 0:
                                raise ComputeError(422, 'Empty accompaniment output')
                            with self.stage(task, 'validate'):
                                output_media = self.transcoding.probe.read_audio(str(temporary), cancel=task.cancel)
                            if abs(output_media.duration - expected_duration) > .25 or not any(s.kind == 'audio' for s in output_media.streams):
                                raise ComputeError(422, 'Accompaniment timeline mismatch')
                            if abs(output_media.start_time) > .025:
                                raise ComputeError(422, 'Accompaniment starts outside original timeline')
                            if task.cancel.is_set():
                                raise ComputeError(409, 'Audio task cancelled')
                            temporary.replace(target)
                            with self.stage(task, 'hash'):
                                digest = self.digest(target)
                            task.result.update(state='READY', duration=expected_duration, sourceDuration=media.duration,
                                sourceOffsetSeconds=offset, audioId=selected_stream.id,
                                mediaType='audio/flac' if output_format == 'flac' else 'audio/mp4',
                                sha256=digest, size=target.stat().st_size, artifact=f'{identifier}.{extension}', streams=[asdict(s) for s in audio])
                task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
            except Exception as error:
                task.result['diagnostic'] = error.message if isinstance(error, ComputeError) else 'Audio worker failed unexpectedly'
                task.error = task.error or ('AUDIO_MEMORY_LIMIT' if isinstance(error, ComputeError) and 'memory budget' in error.message else 'AUDIO_EXECUTION_FAILED')
                task.state = 'CANCELLED' if task.cancel.is_set() else 'FAILED'
                for suffix in ('.part', '.m4a', '.flac'):
                    (self.root / f'{identifier}{suffix}').unlink(missing_ok=True)
            finally:
                task.result.setdefault('timingsMs', {})['total'] = round((time.monotonic()-submitted)*1000, 3)
                task.result['stage'] = task.state
                if mode in ('ANALYZE', 'SEPARATE') and not enhanced:
                    if getattr(task, 'fallback_reason', ''): task.result['fallbackReason'] = task.fallback_reason
                    task.result.update(self.execution(task.backend))
                    if task.backend == 'mps':
                        task.result['pipeline'] = 'mps+cpu-stft'
                if task.state != 'SUCCEEDED':
                    for suffix in ('.part', '.m4a', '.flac'):
                        (self.root / f'{identifier}{suffix}').unlink(missing_ok=True)
                try:
                    self.save(task)
                finally:
                    self.active = False
                    self.slot.release()
        self.thread = threading.Thread(target=worker, name='audio-role-worker', daemon=True)
        self.thread.start()
        return task.view()

    def execution(self, backend):
        from .audio_catalog import model_for_backend, manifest, model_digest
        from ..core.build_info import build_info
        model_id = model_for_backend(backend)
        value = {'backend': backend, 'model': model_id, 'modelVersion': manifest(model_id)['version'],
                 'modelDigest': model_digest(model_id), 'build': build_info()}
        if hasattr(self, 'models'):
            environment = self.models.probe()
            value.update(modelDigest=self.models.inference_digest(model_id), environmentFingerprint=environment['fingerprint'], device=environment.get('devices', {}).get(backend, {}), runtimes=environment.get('versions', {}))
        if backend == 'openvino':
            value['pipeline'] = 'openvino-gpu+cpu-stft'
        return value

    def preempt(self):
        for task in list(self.tasks.values()):
            if task.state in ('QUEUED', 'RUNNING'):
                task.error = 'REALTIME_PREEMPTED'
                task.cancel.set()
        if self.native_worker:
            self.native_worker.interrupt()
        if hasattr(self, 'separation'):
            self.separation.worker.interrupt()

    def collect(self):
        # Go acknowledges successful transfers; orphaned output expires after one day.
        if not self.collection_lock.acquire(blocking=False):
            return
        try:
            for record in (*self.root.glob('*.m4a'), *self.root.glob('*.flac')):
                try:
                    if time.time() - record.stat().st_mtime > 86400:
                        record.unlink(missing_ok=True)
                except FileNotFoundError:
                    pass
            with self.lock:
                tasks = list(self.tasks.items())
            for identifier, task in tasks:
                record = self.root / f'{identifier}.json'
                try:
                    if task.state in ('SUCCEEDED', 'FAILED', 'CANCELLED') and time.time()-record.stat().st_mtime > 7*86400:
                        self.release(identifier)
                        record.unlink(missing_ok=True)
                        with self.lock:
                            self.tasks.pop(identifier, None)
                except FileNotFoundError:
                    pass
        finally:
            self.collection_lock.release()

    @staticmethod
    def digest(path):
        with path.open('rb') as source:
            return hashlib.file_digest(source, 'sha256').hexdigest()

    def release(self, identifier):
        task = self.tasks.get(identifier)
        if task is None:
            raise ComputeError(404, 'Audio task not found')
        if task.state not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
            raise ComputeError(409, 'Audio task is still active')
        for suffix in ('.m4a', '.flac', '.part'):
            (self.root / f'{task.id}{suffix}').unlink(missing_ok=True)
        self.transcoding.storage_dirty.set()
        return task.view()

    def save(self, task):
        with self.lock:
            self._save(task)

    def _save(self, task):
        temporary = self.root / f'{task.id}.json.part'
        temporary.write_text(json.dumps({**task.view(), 'signature': getattr(task, 'signature', '')}), encoding='utf-8')
        temporary.replace(self.root / f'{task.id}.json')
        self.transcoding.storage_dirty.set()

    def close(self):
        self.stopping = True
        for task in self.tasks.values():
            if task.state in ('QUEUED', 'RUNNING'):
                task.cancel.set()
        if self.native_worker:
            self.native_worker.close()
        if hasattr(self, 'separation'):
            self.separation.close()

        if self.thread:
            self.thread.join(timeout=5)
        if getattr(self, 'fast', None):
            self.fast.close()

    def view(self, identifier):
        task = self.tasks.get(identifier)
        if task is None:
            raise ComputeError(404, 'Audio task not found')
        return task.view()

    def cancel(self, identifier):
        task = self.tasks.get(identifier)
        if task is None:
            raise ComputeError(404, 'Audio task not found')
        task.cancel.set()
        if task.state in ('QUEUED', 'RUNNING'):
            task.state = 'CANCELLED'
            self.save(task)
        return task.view()

    def artifact(self, identifier):
        if self.view(identifier)['state'] != 'SUCCEEDED':
            raise ComputeError(409, 'Audio artifact is not ready')
        extension = 'flac' if self.tasks[identifier].result.get('outputFormat') == 'flac' else 'm4a'
        path = self.root / f'{identifier}.{extension}'
        if not path.is_file():
            raise ComputeError(404, 'Audio artifact is missing')
        return path
