"""Instance-owned execution registry; Java owns business queues and durable task intent."""
from contextlib import contextmanager
from collections import OrderedDict
from dataclasses import asdict, dataclass, field, replace
import hashlib
import json
import os
import stat
import re
import shutil
import threading
import time
from ..core.errors import ComputeError
from ..domain.transcoding import Target
from .transcode_artifacts import ARTIFACT, completed_hls, export_hls, ready_hls

@dataclass
class Execution:
    id: str
    signature: str
    target: Target
    state: str = 'ACCEPTED'
    backend: str = ''
    error: str = ''
    fallback_reason: str = ''
    position: float = 0
    speed: float = 0
    peak_memory_bytes: int = 0
    renewed: float = field(default_factory=time.time)
    finished: float = 0
    mapping: list = field(default_factory=list)
    output: dict = field(default_factory=dict)
    pipeline: dict = field(default_factory=dict)
    released: bool = False
    released_at: float = 0
    exports: int = 0
    deleting: bool = False
    hls_ready: bool = False
    cancel: threading.Event = field(default_factory=threading.Event)

    def view(self):
        return {'id':self.id, 'state':self.state, 'backend':self.backend, 'error':self.error, 'fallbackReason':self.fallback_reason,
                'position':self.position, 'speed':self.speed, 'sampledPeakRssBytes':self.peak_memory_bytes, 'offset':self.target.start,
                'purpose':self.target.purpose, 'effectiveMode':self.target.mode, 'trackMapping':self.mapping,
                'ready':self.state == 'SUCCEEDED' and not self.released, 'output':self.output, 'pipeline':self.pipeline,
                'subtitle':bool(self.target.subtitle_id and not self.target.burn_subtitle),
                'released':self.released}

class Transcoding:
    def __init__(self, settings, auth, probe, encoder, backends, remote):
        self.settings, self.auth = settings, auth
        self.probe, self.encoder, self.backends, self.remote = probe, encoder, backends, remote
        self.root = settings.transcode_root
        self.tasks = {}
        self.lock = threading.RLock()
        self.collection_lock = threading.Lock()
        self.storage_lock = threading.Lock()
        self.storage_dirty = threading.Event()
        self.storage_dirty.set()
        self.usage_bytes = 0
        self.usage_at = 0
        self.closed = threading.Event()
        self.threads = []
        self.realtime_reserved_until = 0
        self.probe_slots = threading.BoundedSemaphore(settings.media_concurrency)
        self.probes = OrderedDict()
        if self.root:
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            for file in self.root.glob('*/execution.json'):
                try:
                    if file.is_symlink() or file.parent.is_symlink():
                        continue
                    record = json.loads(file.read_text())
                    task = Execution(record['id'], record['signature'], Target(**record['target']))
                    if not re.fullmatch(r'[a-f0-9-]{36}', task.id) or file.parent.name != task.id or not file.parent.is_relative_to(self.root):
                        continue
                    task.state = record['state'] if record['state'] in ('SUCCEEDED','FAILED','CANCELLED') else 'FAILED'
                    task.error = record.get('error','') if task.state == record['state'] else 'NODE_RESTARTED'
                    task.fallback_reason = record.get('fallbackReason', '')
                    task.mapping, task.backend = record.get('trackMapping',[]), record.get('backend','')
                    task.output = record.get('output', {})
                    task.pipeline = record.get('pipeline', {})
                    task.finished = record.get('finished',0) or time.time()
                    task.released = record.get('released', False)
                    task.released_at = record.get('releasedAt', task.finished if task.released else 0)
                    self.tasks[task.id] = task
                except (OSError, ValueError, KeyError, TypeError):
                    continue

    @property
    def available(self):
        return bool(self.root and self.auth.available and shutil.which(self.settings.ffmpeg) and shutil.which(self.settings.ffprobe))

    @contextmanager
    def input(self, reference, callback_origin, resource='transcode-input'):
        if not reference or not callback_origin:
            raise ComputeError(503, 'Remote media input is not configured')
        with self.remote(callback_origin, reference, self.auth._token(), resource) as url:
            yield url, True

    def inspect(self, reference, callback_origin, fingerprint=None):
        if not self.available:
            raise ComputeError(503, 'Transcoding unavailable')
        if not self.probe_slots.acquire(blocking=False):
            raise ComputeError(429, 'Media probe busy')
        try:
            with self.input(reference, callback_origin) as (source, network):
                media = self.probe.read(source, network)
            if fingerprint:
                with self.lock:
                    self.probes[(reference, callback_origin, fingerprint)] = (time.monotonic(), media)
                    self.probes.move_to_end((reference, callback_origin, fingerprint))
                    while len(self.probes) > 128:
                        self.probes.popitem(last=False)
            return media
        finally:
            self.probe_slots.release()

    def save(self, task):
        record = {**task.view(), 'signature':task.signature, 'target':asdict(task.target), 'finished':task.finished, 'releasedAt':task.released_at}
        directory = self.root / task.id
        temp = directory / 'execution.tmp'
        temp.write_text(json.dumps(record), encoding='utf-8')
        temp.replace(directory / 'execution.json')

    def usage(self, refresh=False):
        # Disk scans never hold the scheduling lock. Coalesce concurrent callers and
        # reuse accounting until files change or an active encoder needs sampling.
        with self.storage_lock:
            with self.lock:
                active = any(t.state in ('ACCEPTED', 'RUNNING') for t in self.tasks.values())
            if not refresh and not self.storage_dirty.is_set() and (not active or time.monotonic() - self.usage_at < 5):
                return self.usage_bytes
            self.storage_dirty.clear()
            total = 0
            try:
                with os.scandir(self.root) as directories:
                    for directory in directories:
                        if not directory.is_dir(follow_symlinks=False):
                            continue
                        try:
                            with os.scandir(directory.path) as entries:
                                for entry in entries:
                                    try:
                                        info = entry.stat(follow_symlinks=False)
                                        if stat.S_ISREG(info.st_mode):
                                            total += info.st_size
                                    except FileNotFoundError:
                                        pass  # FFmpeg replaces manifests atomically.
                        except FileNotFoundError:
                            pass  # Another task may finish artifact release during accounting.
            except OSError:
                self.storage_dirty.set()
                raise
            self.usage_bytes, self.usage_at = total, time.monotonic()
            return total

    def create(self, identifier, reference, fingerprint, target, callback_origin):
        if not self.available:
            raise ComputeError(503, 'Transcoding unavailable')
        # Input seeking plus stream-copy may expose frames preceding the requested time.
        # Decode nonzero starts accurately so returned original-timeline offsets remain valid for lyrics.
        if target.start > 0 and target.mode != 'TRANSCODE':
            target = replace(target, mode='TRANSCODE')
        signature = hashlib.sha256(json.dumps(['3', reference, callback_origin, fingerprint, asdict(target)], sort_keys=True).encode()).hexdigest()
        with self.lock:
            prior = self.tasks.get(identifier)
            if prior:
                if prior.signature != signature:
                    raise ComputeError(409, 'Execution ID already used for another input')
                return self.view(identifier)
        usage, free = self.collect()
        with self.lock:
            # Another request may have created the same execution during disk IO.
            prior = self.tasks.get(identifier)
            if prior:
                if prior.signature != signature:
                    raise ComputeError(409, 'Execution ID already used for another input')
                return self.view(identifier)
            if target.purpose == 'OFFLINE' and time.monotonic() < self.realtime_reserved_until:
                raise ComputeError(429, 'Capacity reserved for realtime playback')
            active = [t for t in self.tasks.values() if t.state in ('ACCEPTED','RUNNING')]
            if len(active) >= self.settings.transcode_concurrency:
                if target.purpose == 'REALTIME':
                    self.realtime_reserved_until = time.monotonic() + 5
                    for task in active:
                        if task.target.purpose == 'OFFLINE':
                            task.cancel.set()
                raise ComputeError(429, 'Transcode capacity busy; retry after active execution stops')
            audio = getattr(self, 'audio_roles', None)
            if audio and target.purpose == 'REALTIME':
                # Also release an idle warm GPU model before admitting realtime playback.
                audio.preempt()
            if audio and audio.active:
                if target.purpose == 'REALTIME':
                    self.realtime_reserved_until = time.monotonic() + 5
                raise ComputeError(429, 'Offline audio worker stopping; retry playback')
            if usage >= self.settings.cache_bytes or free < self.settings.minimum_free_bytes:
                raise ComputeError(507, 'Transcode storage budget exhausted')
            task = Execution(identifier, signature, target)
            task.cpu_fallback = getattr(self.backends, 'cpu_fallback', True)
            task.preferred_backend = getattr(self.backends, 'preferred', 'auto')
            directory = self.root / identifier
            directory.mkdir(exist_ok=False)
            self.tasks[identifier] = task
            self.save(task)
            self.storage_dirty.set()
            thread = threading.Thread(target=self.execute, args=(task,reference,callback_origin,fingerprint), daemon=True)
            self.threads = [t for t in self.threads if t.is_alive()]
            self.threads.append(thread)
            thread.start()
            return task.view()

    def execute(self, task, reference, callback_origin, fingerprint=None):
        directory = self.root / task.id
        try:
            task.state = 'RUNNING'
            with self.lock:
                prior_probe = self.probes.pop((reference, callback_origin, fingerprint), None)
            with self.input(reference, callback_origin) as (source, network):
                # The snapshot is private to this authorized reference and immutable source
                # fingerprint. FFmpeg still opens the fresh authenticated input itself.
                media = prior_probe[1] if prior_probe and time.monotonic() - prior_probe[0] < 60 else self.probe.read(source, network)
                if media.duration and task.target.start >= media.duration:
                    raise ComputeError(422, 'Start position exceeds media duration')
                selected = [s for s in media.streams if s.kind == 'video'][:1] + [s for s in media.streams if s.kind == 'audio' and (not task.target.audio_id or s.id == task.target.audio_id)]
                task.mapping = [{'sourceId':s.id, 'outputIndex':i, 'kind':s.kind, 'language':s.language, 'title':s.title, 'channels':s.channels} for i,s in enumerate(selected)]
                cpu_fallback = getattr(task, 'cpu_fallback', True)
                backend = self.backends.select(task.target.codec, cpu_fallback=cpu_fallback, preferred=getattr(task, 'preferred_backend', 'auto')) if task.target.mode == 'TRANSCODE' and any(s.kind == 'video' for s in media.streams) else 'software'
                if backend == 'software' and task.target.mode == 'TRANSCODE' and hasattr(self.backends, 'detected') and self.backends.detected():
                    task.fallback_reason = 'ACCELERATOR_NOT_READY'
                def progress(key, value):
                    if key == 'pipeline':
                        task.pipeline = value
                    elif key == 'memory':
                        task.peak_memory_bytes = value
                    elif key == 'out_time_us':
                        if value.lstrip("-").isdigit():
                            task.position = max(0, int(value)/1000000)
                    elif key == 'speed':
                        try:
                            task.speed = float(value.rstrip('x'))
                        except ValueError:
                            pass
                    if task.target.purpose == 'REALTIME' and task.position > 30 and 0 < task.speed < .8:
                        task.error = 'REALTIME_TOO_SLOW'
                        task.cancel.set()
                for attempt in range(2):
                    task.backend = backend
                    try:
                        task.mapping = self.encoder.execute(source, network, media, task.target, directory, task.cancel, progress, backend)
                        break
                    except ComputeError as error:
                        from ..infrastructure.hardware import accelerator_failure
                        # Never mix hardware and software segments already exposed to a player.
                        if attempt or backend == 'software' or not cpu_fallback or not accelerator_failure(error.message) or task.cancel.is_set() or (directory / 'master.m3u8').exists():
                            raise
                        task.fallback_reason = 'ACCELERATOR_EXECUTION_FAILED'
                        backend = 'software'
                if task.target.purpose == 'OFFLINE':
                    output = directory / 'output.mp4'
                    digest = hashlib.sha256()
                    with output.open('rb') as stream:
                        while chunk := stream.read(1048576):
                            digest.update(chunk)
                    task.output = {'sha256':digest.hexdigest(), 'size':output.stat().st_size,
                                   'media':asdict(self.probe.read(output))}
                outcome = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
                if outcome == 'SUCCEEDED' and task.target.purpose == 'REALTIME':
                    completed_hls(directory)
        except ComputeError as error:
            outcome = 'CANCELLED' if task.cancel.is_set() and not task.error else 'FAILED'
            task.error = task.error or error.message
        except Exception:
            outcome, task.error = 'FAILED', 'EXECUTION_FAILED'
        finally:
            with self.lock:
                task.state = 'CANCELLED' if outcome == 'SUCCEEDED' and task.cancel.is_set() else outcome
                task.finished = time.time()
                self.storage_dirty.set()
                self.save(task)
                if task.state in ('FAILED','CANCELLED'):
                    for artifact in directory.iterdir():
                        if artifact.name != 'execution.json' and artifact.is_file() and not artifact.is_symlink():
                            artifact.unlink()

    def get(self, identifier):
        task = self.tasks.get(identifier)
        if task is None:
            raise ComputeError(404, 'Execution not found')
        if task.deleting:
            raise ComputeError(409, 'Execution artifacts are being released')
        return task

    def view(self, identifier):
        with self.lock:
            task = self.get(identifier)
            result = task.view()
            if task.target.purpose == 'REALTIME':
                directory = self.root / identifier
                active = not task.released and not task.cancel.is_set() and task.state not in ('FAILED', 'CANCELLED')
                if active and not task.hls_ready:
                    task.hls_ready = ready_hls(directory)
                result['ready'] = active and task.hls_ready
            return result

    def renew(self, identifier):
        with self.lock:
            task = self.get(identifier)
            task.renewed = time.time()
            return self.view(identifier)

    def cancel(self, identifier):
        with self.lock:
            task = self.get(identifier)
            task.cancel.set()
            if task.state == 'SUCCEEDED':
                task.state, task.finished = 'CANCELLED', time.time()
                self.save(task)
            return task.view()

    def artifact(self, identifier, name):
        if not ARTIFACT.fullmatch(name):
            raise ComputeError(404, 'Artifact not found')
        task = self.get(identifier)
        if task.state in ('FAILED','CANCELLED') or task.released:
            raise ComputeError(410, 'Execution no longer available')
        path = self.root / identifier / name
        if not path.is_file() or path.is_symlink() or path.parent.is_symlink():
            raise ComputeError(404, 'Artifact not ready')
        return path

    def delete_directory(self, identifier):
        directory = self.root / identifier
        if directory.is_symlink():
            directory.unlink()
        else:
            try:
                shutil.rmtree(directory)
            except FileNotFoundError:
                pass
        self.storage_dirty.set()

    def remove(self, identifier):
        with self.lock:
            task = self.get(identifier)
            if task.state in ('ACCEPTED', 'RUNNING'):
                task.cancel.set()
                raise ComputeError(409, 'Cancel execution before deleting artifacts')
            if task.exports or time.time() - task.renewed < self.settings.lease_seconds:
                raise ComputeError(409, 'Artifacts currently leased')
            task.deleting = True
        try:
            self.delete_directory(identifier)
            with self.lock:
                del self.tasks[identifier]
        finally:
            with self.lock:
                task.deleting = False

    def collect(self):
        if not self.root:
            return 0, 0
        usage, free = self.usage(), shutil.disk_usage(self.root).free
        if not self.collection_lock.acquire(blocking=False):
            return usage, free
        try:
            expired = []
            now = time.time()
            with self.lock:
                for identifier, task in list(self.tasks.items()):
                    if task.deleting or task.exports:
                        continue
                    if task.state in ('ACCEPTED', 'RUNNING'):
                        if task.target.purpose == 'REALTIME' and now - task.renewed > self.settings.lease_seconds:
                            task.cancel.set()
                        if usage > self.settings.cache_bytes or free < self.settings.minimum_free_bytes:
                            task.error = 'STORAGE_BUDGET_EXHAUSTED'
                            task.cancel.set()
                    elif (task.target.purpose == 'REALTIME' and now - max(task.finished, task.renewed) > self.settings.retention_seconds
                          or task.target.purpose == 'OFFLINE' and (task.released or task.state in ('FAILED', 'CANCELLED'))
                          and now - (task.released_at or task.finished) > 7 * 86400):
                        task.deleting = True
                        expired.append((identifier, task))
            try:
                for identifier, task in expired:
                    self.delete_directory(identifier)
                    with self.lock:
                        self.tasks.pop(identifier, None)
            finally:
                with self.lock:
                    for _, task in expired:
                        task.deleting = False
            if expired:
                usage, free = self.usage(), shutil.disk_usage(self.root).free
            return usage, free
        finally:
            self.collection_lock.release()

    def export(self, identifier):
        with self.lock:
            task = self.get(identifier)
            if task.released:
                raise ComputeError(410, 'Execution artifacts were already released')
            if task.state != 'SUCCEEDED' or task.target.purpose != 'REALTIME':
                raise ComputeError(409, 'Only completed realtime HLS outputs may be exported')
            task.renewed = time.time()
            task.exports += 1
        try:
            exported = export_hls(self.root / identifier)
            with self.lock:
                if task.state != 'SUCCEEDED' or task.released:
                    raise ComputeError(409, 'Execution changed during export')
                task.renewed = time.time()
            return exported
        except (OSError, UnicodeError) as error:
            raise ComputeError(409, 'Execution artifacts are incomplete or unreadable') from error
        finally:
            with self.lock:
                task.exports -= 1

    def acknowledge(self, identifier):
        with self.lock:
            task = self.get(identifier)
            if task.state != 'SUCCEEDED':
                raise ComputeError(409, 'Only completed outputs may be acknowledged')
            if task.exports:
                raise ComputeError(409, 'Execution export is still active')
            if task.released:
                self.save(task)  # Retry a release whose prior persistence failed.
                return task.view()
            task.deleting = True
        try:
            directory = self.root / identifier
            if directory.is_symlink():
                raise ComputeError(409, 'Execution artifact directory is invalid')
            try:
                for path in directory.iterdir():
                    if path.name != 'execution.json' and (path.is_file() or path.is_symlink()):
                        path.unlink()
            except OSError as error:
                raise ComputeError(500, 'Execution artifacts could not be released') from error
            with self.lock:
                task.released = True
                task.released_at = time.time()
                self.save(task)
                return task.view()
        finally:
            self.storage_dirty.set()
            with self.lock:
                task.deleting = False

    def start(self):
        def maintain():
            while not self.closed.wait(5):
                self.maintain()
        self.maintenance = threading.Thread(target=maintain, daemon=True)
        self.maintenance.start()

    def maintain(self):
        try:
            self.collect()
        except (ComputeError, OSError):
            import logging
            logging.getLogger('singverse_compute.transcoding').warning('Temporary artifact maintenance deferred; check storage access')

    def close(self):
        self.closed.set()
        with self.lock:
            tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel.set()
        for thread in self.threads:
            thread.join(timeout=8)
        if hasattr(self, 'maintenance'):
            self.maintenance.join(timeout=2)
