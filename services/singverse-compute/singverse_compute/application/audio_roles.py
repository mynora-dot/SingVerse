"""Offline, opt-in vocal analysis. Inputs remain inside the existing media gateway."""
from dataclasses import dataclass, field, asdict
from pathlib import Path
import os
import time
import importlib.util
import math
import shutil
import sys
import tempfile
import threading
import json
import hashlib
import uuid
from importlib.metadata import version, PackageNotFoundError
import wave

from ..core.errors import ComputeError
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


def classify(samples: list[tuple[float, float]], titles: tuple[str, str]) -> dict:
    """Require two consistent, strongly separated windows; tags never decide alone."""
    if len(samples) < 2 or any(min(a, b) < 0 for a, b in samples):
        return {'state': 'UNKNOWN', 'reason': 'INSUFFICIENT_EVIDENCE'}
    ratios = []
    for first, second in samples:
        if max(first, second) < 0.006:
            return {'state': 'UNKNOWN', 'reason': 'NO_RELIABLE_VOCALS'}
        ratios.append((first + 0.001) / (second + 0.001))
    if all(r >= 6 for r in ratios) and all(a >= .25 and b <= .06 for a, b in samples):
        original, backing = 0, 1
    elif all(r <= 1/6 for r in ratios) and all(b >= .25 and a <= .06 for a, b in samples):
        original, backing = 1, 0
    else:
        return {'state': 'UNKNOWN', 'reason': 'AMBIGUOUS_VOCALS'}
    # Explicit opposite labels are evidence of a different rendition or incorrect sampling.
    original_title = titles[original].lower()
    backing_title = titles[backing].lower()
    if any(word in original_title for word in ('伴奏', 'instrumental', 'karaoke')) or any(word in backing_title for word in ('原唱', 'vocal', 'lead')):
        return {'state': 'UNKNOWN', 'reason': 'CONFLICTING_LABELS'}
    return {'state': 'CONFIDENT', 'original': original, 'backing': backing,
            'evidence': {'vocalRatio': samples, 'model': 'htdemucs'}}


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
        self.stopping = False
        self.active = False
        self.thread = None
        self.model_stamp = None
        self.model_valid = False
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
        if not self.runtime_available and not manager.model_valid('htdemucs-openvino'):
            return False
        return manager.selected() is not None

    @property
    def dependencies_available(self):
        try:
            return self.transcoding.available and version('demucs') == '4.0.1' and version('torch').split('+')[0] == '2.5.1' and version('torchaudio').split('+')[0] == '2.5.1'
        except PackageNotFoundError:
            return False

    def backend_runtime_available(self, backend):
        if backend != 'openvino':
            return self.runtime_available
        try:
            return self.dependencies_available and version('openvino') == '2025.4.0' and self.models.model_valid('htdemucs-openvino')
        except PackageNotFoundError:
            return False

    @property
    def runtime_available(self):
        try:
            if version("demucs") != "4.0.1":
                return False
        except PackageNotFoundError:
            return False
        if not (self.transcoding.available and self.repo and importlib.util.find_spec('demucs')):
            return False
        try:
            model = self.repo / '955717e8-8726e21a.th'
            config = self.repo / 'htdemucs.yaml'
            stamp = (model.stat().st_size, model.stat().st_mtime_ns, config.stat().st_mtime_ns)
            if stamp != self.model_stamp:
                self.model_valid = (config.read_text() == "models: ['955717e8']\n" and self.digest(model) ==
                    '8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4')
                self.model_stamp = stamp
            return self.model_valid and version('torch').split('+')[0] == '2.5.1' and version('torchaudio').split('+')[0] == '2.5.1'
        except (OSError, PackageNotFoundError):
            return False

    def _run(self, command, task):
        if any(t.target.purpose == 'REALTIME' and t.state in ('RUNNING', 'ACCEPTED') for t in list(self.transcoding.tasks.values())):
            task.cancel.set()
        diagnosis = []
        def observe(line):
            if 'Output channels > 65536 not supported' in line or 'not currently implemented for the MPS' in line:
                diagnosis.append('所选推理后端不支持模型所需算子，请选择通过试跑的CPU；不会隐式切换后端')
            elif 'out of memory' in line.lower():
                diagnosis.append('模型执行内存或显存不足，请释放资源后重试')
        try:
            return run(command, timeout=7200, limit=65536, cancel=task.cancel,
                memory_limit=self.settings.audio_memory_bytes, error_line=observe,
                env={**os.environ, 'OMP_NUM_THREADS': str(self.settings.audio_threads), 'MKL_NUM_THREADS': str(self.settings.audio_threads), 'PYTORCH_ENABLE_MPS_FALLBACK': '0'})
        except ComputeError as error:
            if diagnosis and not task.cancel.is_set():
                raise ComputeError(422, diagnosis[-1]) from error
            raise

    def _separate(self, wav: Path, output: Path, task: AudioTask):
        backend = getattr(task, 'backend', 'cpu')
        if backend == 'openvino':
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
        if backend == 'mps':
            self._run([sys.executable, '-m', 'singverse_compute.infrastructure.mps_audio',
                       '--model-dir', str(self.repo), '--output', str(output), str(wav)], task)
        else:
            device = 'cuda' if backend == 'rocm' else backend
            self._run([sys.executable, '-m', 'demucs', '--repo', str(self.repo), '-n', 'htdemucs',
                       '--two-stems', 'vocals', '--clip-mode', 'clamp', '--shifts', '0', '-d', device, '-o', str(output), str(wav)], task)
        folder = output / 'htdemucs' / wav.stem
        vocals, backing = folder / 'vocals.wav', folder / 'no_vocals.wav'
        if not vocals.is_file() or not backing.is_file():
            raise ComputeError(422, 'Model did not produce both stems')
        return vocals, backing

    def _extract(self, source, network, stream, start, seconds, output, task):
        self._run([self.settings.ffmpeg, '-nostdin', '-v', 'error', '-protocol_whitelist',
                   'file,http,tcp' if network else 'file', '-ss', str(start), '-i', source,
                   '-map', f'0:{stream}', '-t', str(seconds), '-vn', '-ac', '2', '-ar', '44100',
                   '-af', 'aresample=async=1:first_pts=0,apad', '-c:a', 'pcm_s16le', '-y', str(output)], task)

    def submit(self, identifier, path, reference, mode, backend=None):
        if str(uuid.UUID(identifier)) != identifier or mode not in ('PROBE', 'ANALYZE', 'SEPARATE'):
            raise ComputeError(400, 'Invalid audio task')
        signature = hashlib.sha256(json.dumps([path, reference, mode, backend]).encode()).hexdigest()
        with self.lock:
            if identifier in self.tasks:
                if getattr(self.tasks[identifier], 'signature', signature) != signature:
                    raise ComputeError(409, 'Task input changed')
                return self.tasks[identifier].view()
            # A running model temporarily consumes RAM. Report queue capacity
            # before checking resources so the next batch member stays queued.
            if self.active:
                raise ComputeError(429, 'Audio model worker busy')
        if self.stopping or not self.transcoding.available or (mode != 'PROBE' and not (self.backend_runtime_available(backend) if backend else self.available)):
            raise ComputeError(503, 'Audio model is not installed and configured')
        if self.root is None or shutil.disk_usage(self.root).free < self.settings.minimum_free_bytes:
            raise ComputeError(507, 'Insufficient audio working space')
        selected = backend or (self.models.selected() if hasattr(self, 'models') else 'cpu')
        # GPU/runtime subprocess queries must not hold the realtime scheduling lock.
        if mode != 'PROBE' and hasattr(self, 'models'):
            if not any(item['id'] == selected and item['state'] == 'AVAILABLE' for item in self.models.entries()):
                raise ComputeError(422, 'Selected inference backend has not passed qualification')
            try:
                self.models.check_resources(selected)
            except ComputeError as error:
                if error.status == 422:
                    raise ComputeError(429, error.message) from error
                raise
        with self.transcoding.lock, self.lock:
            self.collect()
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
            task.backend = selected
            if mode != 'PROBE':
                task.result.update(self.execution(selected))
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
                from ..infrastructure.transcoding.input import remote_input
                context = self.transcoding.input(path, None) if path else remote_input(
                    self.settings.java_origin, reference, self.transcoding.auth._token(), 'audio-input')
                with context as value:
                    source, network = value if path else (value, True)
                    media = self.transcoding.probe.read(source, network)
                    audio = [s for s in media.streams if s.kind == 'audio']
                    if mode == 'PROBE':
                        task.result = {'state': 'UNKNOWN', 'reason': 'PROBED', 'streams': [asdict(s) for s in audio], 'duration': media.duration}
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    expected = 2 if mode == 'ANALYZE' else 1
                    if mode == 'ANALYZE' and len(audio) != 2:
                        task.result = {'state': 'UNKNOWN', 'reason': 'NOT_DUAL_AUDIO', 'streams': [asdict(s) for s in audio], 'duration': media.duration}
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    if len(audio) != expected or media.duration <= 0:
                        raise ComputeError(422, 'Unexpected audio stream count or duration')
                    if mode == 'ANALYZE' and media.duration < 60:
                        task.result = {'state': 'UNKNOWN', 'reason': 'INSUFFICIENT_DURATION', 'streams': [asdict(s) for s in audio], 'duration': media.duration}
                        task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                        return
                    estimate = int(media.duration * 44100 * 2 * 2 * 8)
                    if estimate + sum(p.stat().st_size for p in self.root.glob("*.m4a")) > self.settings.cache_bytes or shutil.disk_usage(self.root).free < estimate + self.settings.minimum_free_bytes:
                        raise ComputeError(507, 'Audio task exceeds disk budget')
                    with tempfile.TemporaryDirectory(prefix='work-'+identifier+'-', dir=self.root) as temp:
                        work = Path(temp)
                        if mode == 'ANALYZE':
                            evidence = []
                            for position in (max(0, media.duration * .25 - 10), max(0, media.duration * .7 - 10)):
                                levels = []
                                for stream in audio:
                                    wav = work / f'input-{len(evidence)}-{stream.index}.wav'
                                    self._extract(source, network, stream.index, position, 20, wav, task)
                                    vocal, _ = self._separate(wav, work / f'out-{len(evidence)}-{stream.index}', task)
                                    levels.append(_rms(vocal) / max(_rms(wav), .001))
                                evidence.append(tuple(levels))
                            result = classify(evidence, (audio[0].title, audio[1].title))
                            if result['state'] == 'CONFIDENT':
                                result['original'] = audio[result['original']].id
                                result['backing'] = audio[result['backing']].id
                            result['streams'] = [asdict(s) for s in audio]
                            result['duration'] = media.duration
                            task.result = result
                        else:
                            wav = work / 'source.wav'
                            self._extract(source, network, audio[0].index, 0, media.duration, wav, task)
                            _, backing = self._separate(wav, work / 'out', task)
                            target = self.root / f'{identifier}.m4a'
                            temporary = self.root / f'{identifier}.part'
                            self._run([self.settings.ffmpeg, '-nostdin', '-v', 'error', '-i', str(backing),
                                       '-c:a', 'aac', '-b:a', '192k', '-f', 'ipod', '-y', str(temporary)], task)
                            if not temporary.is_file() or temporary.stat().st_size == 0:
                                raise ComputeError(422, 'Empty accompaniment output')
                            output_media = self.transcoding.probe.read(str(temporary))
                            if abs(output_media.duration - media.duration) > .25 or not any(s.kind == 'audio' for s in output_media.streams):
                                raise ComputeError(422, 'Accompaniment timeline mismatch')
                            timeline = json.loads(self._run([self.settings.ffprobe, '-v', 'error', '-show_entries',
                                'format=start_time', '-of', 'json', str(temporary)], task))
                            if abs(float(timeline.get('format', {}).get('start_time', '0'))) > .025:
                                raise ComputeError(422, 'Accompaniment starts outside original timeline')
                            if task.cancel.is_set():
                                raise ComputeError(409, 'Audio task cancelled')
                            temporary.replace(target)
                            task.result = {'state': 'READY', 'duration': media.duration, 'audioId': audio[0].id,
                                           'sha256': self.digest(target), 'size': target.stat().st_size, 'artifact': f'{identifier}.m4a', 'streams': [asdict(s) for s in audio]}
                task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
            except Exception as error:
                task.error = task.error or ('AUDIO_MEMORY_LIMIT' if isinstance(error, ComputeError) and 'memory budget' in error.message else 'AUDIO_EXECUTION_FAILED')
                task.state = 'CANCELLED' if task.cancel.is_set() else 'FAILED'
                for suffix in ('.part', '.m4a'):
                    (self.root / f'{identifier}{suffix}').unlink(missing_ok=True)
            finally:
                if mode != 'PROBE':
                    task.result.update(self.execution(task.backend))
                    if task.backend == 'mps':
                        task.result['pipeline'] = 'mps+cpu-stft'
                if task.state != 'SUCCEEDED':
                    for suffix in ('.part', '.m4a'):
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
            value.update(environmentFingerprint=environment['fingerprint'], device=environment.get('devices', {}).get(backend, {}), runtimes=environment.get('versions', {}))
        if backend == 'openvino':
            value['pipeline'] = 'openvino-gpu+cpu-stft'
        return value

    def preempt(self):
        for task in list(self.tasks.values()):
            if task.state in ('QUEUED', 'RUNNING'):
                task.error = 'REALTIME_PREEMPTED'
                task.cancel.set()

    def collect(self):
        # Java acknowledges successful transfers; orphaned output expires after one day.
        for record in self.root.glob('*.m4a'):
            if time.time() - record.stat().st_mtime > 86400:
                record.unlink(missing_ok=True)
        for identifier, task in list(self.tasks.items()):
            record = self.root / f'{identifier}.json'
            if task.state in ('SUCCEEDED', 'FAILED', 'CANCELLED') and record.exists() and time.time() - record.stat().st_mtime > 7*86400:
                self.release(identifier)
                record.unlink(missing_ok=True)
                self.tasks.pop(identifier, None)

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
        for suffix in ('.m4a', '.part'):
            (self.root / f'{task.id}{suffix}').unlink(missing_ok=True)
        return task.view()

    def save(self, task):
        with self.lock:
            self._save(task)

    def _save(self, task):
        temporary = self.root / f'{task.id}.json.part'
        temporary.write_text(json.dumps({**task.view(), 'signature': getattr(task, 'signature', '')}), encoding='utf-8')
        temporary.replace(self.root / f'{task.id}.json')

    def close(self):
        self.stopping = True
        for task in self.tasks.values():
            if task.state in ('QUEUED', 'RUNNING'):
                task.cancel.set()

        if self.thread:
            self.thread.join(timeout=5)

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
        path = self.root / f'{identifier}.m4a'
        if not path.is_file():
            raise ComputeError(404, 'Audio artifact is missing')
        return path
