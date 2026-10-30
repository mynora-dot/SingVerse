"""Instance-owned execution registry; Java owns business queues and durable task intent."""
from contextlib import nullcontext
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
import hashlib
import json
import shutil
import threading
import time
from ..core.errors import ComputeError
from ..domain.transcoding import Target

@dataclass
class Execution:
    id: str
    signature: str
    target: Target
    state: str = 'ACCEPTED'
    backend: str = ''
    error: str = ''
    position: float = 0
    speed: float = 0
    peak_memory_bytes: int = 0
    renewed: float = field(default_factory=time.time)
    finished: float = 0
    mapping: list = field(default_factory=list)
    output: dict = field(default_factory=dict)
    pipeline: dict = field(default_factory=dict)
    cancel: threading.Event = field(default_factory=threading.Event)

    def view(self):
        return {'id':self.id, 'state':self.state, 'backend':self.backend, 'error':self.error,
                'position':self.position, 'speed':self.speed, 'sampledPeakRssBytes':self.peak_memory_bytes, 'offset':self.target.start,
                'purpose':self.target.purpose, 'effectiveMode':self.target.mode, 'trackMapping':self.mapping,
                'ready':self.state == 'SUCCEEDED', 'output':self.output, 'pipeline':self.pipeline,
                'subtitle':bool(self.target.subtitle_id and not self.target.burn_subtitle)}

class Transcoding:
    def __init__(self, settings, paths, auth, probe, encoder, backends, remote):
        self.settings, self.paths, self.auth = settings, paths, auth
        self.probe, self.encoder, self.backends, self.remote = probe, encoder, backends, remote
        self.root = settings.transcode_root
        self.tasks = {}
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.threads = []
        self.realtime_reserved_until = 0
        self.probe_slots = threading.BoundedSemaphore(settings.media_concurrency)
        if self.root:
            if any(self.root.is_relative_to(r) or r.is_relative_to(self.root) for r in settings.media_roots):
                raise ValueError('Transcode directory must be separate from media roots')
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            for file in self.root.glob('*/execution.json'):
                try:
                    record = json.loads(file.read_text())
                    task = Execution(record['id'], record['signature'], Target(**record['target']))
                    if file.parent.name != task.id or not file.parent.is_relative_to(self.root):
                        continue
                    task.state = record['state'] if record['state'] in ('SUCCEEDED','FAILED','CANCELLED') else 'FAILED'
                    task.error = record.get('error','') if task.state == record['state'] else 'NODE_RESTARTED'
                    task.mapping, task.backend = record.get('trackMapping',[]), record.get('backend','')
                    task.output = record.get('output', {})
                    task.pipeline = record.get('pipeline', {})
                    task.finished = record.get('finished',0) or time.time()
                    self.tasks[task.id] = task
                except (OSError, ValueError, KeyError, TypeError):
                    continue

    @property
    def available(self):
        return bool(self.root and self.auth.available and shutil.which(self.settings.ffmpeg) and shutil.which(self.settings.ffprobe))

    def input(self, path, reference):
        if path:
            return nullcontext((str(self.paths.resolve(path)), False))
        if not reference or not self.settings.java_origin:
            raise ComputeError(503, 'Remote media input is not configured')
        from contextlib import contextmanager
        @contextmanager
        def gateway():
            with self.remote(self.settings.java_origin, reference, self.auth._token()) as url:
                yield url, True
        return gateway()

    def inspect(self, path, reference):
        if not self.available:
            raise ComputeError(503, 'Transcoding unavailable')
        if not self.probe_slots.acquire(blocking=False):
            raise ComputeError(429, 'Media probe busy')
        try:
            with self.input(path, reference) as (source, network):
                return self.probe.read(source, network)
        finally:
            self.probe_slots.release()

    def save(self, task):
        record = {**task.view(), 'signature':task.signature, 'target':asdict(task.target), 'finished':task.finished}
        directory = self.root / task.id
        temp = directory / 'execution.tmp'
        temp.write_text(json.dumps(record), encoding='utf-8')
        temp.replace(directory / 'execution.json')

    def usage(self):
        return sum(p.stat().st_size for p in self.root.glob('*/*') if p.is_file() and not p.is_symlink())

    def create(self, identifier, path, reference, fingerprint, target):
        if not self.available:
            raise ComputeError(503, 'Transcoding unavailable')
        # Input seeking plus stream-copy may expose frames preceding the requested time.
        # Decode nonzero starts accurately so returned original-timeline offsets remain valid for lyrics.
        if target.start > 0 and target.mode != 'TRANSCODE':
            target = replace(target, mode='TRANSCODE')
        signature = hashlib.sha256(json.dumps(['2', path, reference, fingerprint, asdict(target)], sort_keys=True).encode()).hexdigest()
        with self.lock:
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
            self.collect()
            if self.usage() >= self.settings.cache_bytes or shutil.disk_usage(self.root).free < self.settings.minimum_free_bytes:
                raise ComputeError(507, 'Transcode storage budget exhausted')
            task = Execution(identifier, signature, target)
            directory = self.root / identifier
            directory.mkdir(exist_ok=False)
            self.tasks[identifier] = task
            self.save(task)
            thread = threading.Thread(target=self.execute, args=(task,path,reference), daemon=True)
            self.threads = [t for t in self.threads if t.is_alive()]
            self.threads.append(thread)
            thread.start()
            return task.view()

    def execute(self, task, path, reference):
        directory = self.root / task.id
        try:
            task.state = 'RUNNING'
            with self.input(path, reference) as (source, network):
                before = Path(source).stat() if not network else None
                media = self.probe.read(source, network)
                if media.duration and task.target.start >= media.duration:
                    raise ComputeError(422, 'Start position exceeds media duration')
                selected = [s for s in media.streams if s.kind == 'video'][:1] + [s for s in media.streams if s.kind == 'audio' and (not task.target.audio_id or s.id == task.target.audio_id)]
                task.mapping = [{'sourceId':s.id, 'outputIndex':i, 'kind':s.kind, 'language':s.language, 'title':s.title, 'channels':s.channels} for i,s in enumerate(selected)]
                backend = self.backends.select(task.target.codec) if task.target.mode == 'TRANSCODE' and any(s.kind == 'video' for s in media.streams) else 'software'
                def progress(key, value):
                    if key == 'pipeline':
                        task.pipeline = value
                    elif key == 'memory':
                        task.peak_memory_bytes = value
                    elif key == 'out_time_us':
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
                    except ComputeError:
                        # Never mix hardware and software segments already exposed to a player.
                        if attempt or backend == 'software' or task.cancel.is_set() or (directory / 'master.m3u8').exists():
                            raise
                        backend = 'software'
                if before and (before.st_size, before.st_mtime_ns) != (Path(source).stat().st_size, Path(source).stat().st_mtime_ns):
                    raise ComputeError(409, 'Source changed during execution')
                if task.target.purpose == 'OFFLINE':
                    output = directory / 'output.mp4'
                    digest = hashlib.sha256()
                    with output.open('rb') as stream:
                        while chunk := stream.read(1048576):
                            digest.update(chunk)
                    task.output = {'sha256':digest.hexdigest(), 'size':output.stat().st_size,
                                   'media':asdict(self.probe.read(output))}
                task.state = 'CANCELLED' if task.cancel.is_set() else 'SUCCEEDED'
        except ComputeError as error:
            task.state = 'CANCELLED' if task.cancel.is_set() and not task.error else 'FAILED'
            task.error = task.error or error.message
        except Exception:
            task.state, task.error = 'FAILED', 'EXECUTION_FAILED'
        finally:
            with self.lock:
                task.finished = time.time()
                self.save(task)
                if task.state in ('FAILED','CANCELLED'):
                    for artifact in directory.iterdir():
                        if artifact.name != 'execution.json' and artifact.is_file() and not artifact.is_symlink():
                            artifact.unlink()

    def get(self, identifier):
        task = self.tasks.get(identifier)
        if task is None:
            raise ComputeError(404, 'Execution not found')
        return task

    def view(self, identifier):
        with self.lock:
            task = self.get(identifier)
            result = task.view()
            if task.target.purpose == 'REALTIME':
                manifest = self.root / identifier / 'master.m3u8'
                result['ready'] = task.state not in ('FAILED', 'CANCELLED') and manifest.is_file() and any((self.root / identifier).glob('stream_*_000000.*'))
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
        import re
        if not re.fullmatch(r'(master\.m3u8|stream_[a-zA-Z0-9]+\.m3u8|stream_[a-zA-Z0-9]+_[0-9]+\.(ts|m4s)|init_[a-zA-Z0-9]+\.mp4|output\.mp4|subtitle\.vtt)', name):
            raise ComputeError(404, 'Artifact not found')
        task = self.get(identifier)
        if task.state in ('FAILED','CANCELLED'):
            raise ComputeError(410, 'Execution no longer available')
        path = self.root / identifier / name
        if not path.is_file() or path.is_symlink():
            raise ComputeError(404, 'Artifact not ready')
        return path

    def remove(self, identifier):
        with self.lock:
            task = self.get(identifier)
            if task.state in ('ACCEPTED','RUNNING'):
                task.cancel.set()
                raise ComputeError(409, 'Cancel execution before deleting artifacts')
            if time.time() - task.renewed < self.settings.lease_seconds:
                raise ComputeError(409, 'Artifacts currently leased')
            shutil.rmtree(self.root / identifier)
            del self.tasks[identifier]

    def collect(self):
        if not self.root:
            return
        with self.lock:
            usage = self.usage()
            free = shutil.disk_usage(self.root).free
            for identifier, task in list(self.tasks.items()):
                now = time.time()
                if task.state in ('ACCEPTED','RUNNING'):
                    if task.target.purpose == 'REALTIME' and now-task.renewed > self.settings.lease_seconds:
                        task.cancel.set()
                    if usage > self.settings.cache_bytes or free < self.settings.minimum_free_bytes:
                        task.error = 'STORAGE_BUDGET_EXHAUSTED'
                        task.cancel.set()
                elif task.target.purpose == 'REALTIME' and now-max(task.finished, task.renewed) > self.settings.retention_seconds:
                    shutil.rmtree(self.root / identifier, ignore_errors=True)
                    del self.tasks[identifier]

    def acknowledge(self, identifier):
        with self.lock:
            task = self.get(identifier)
            if task.target.purpose != 'OFFLINE' or task.state != 'SUCCEEDED':
                raise ComputeError(409, 'Only completed offline outputs may be acknowledged')
            for path in (self.root / identifier).iterdir():
                if path.name != 'execution.json' and path.is_file() and not path.is_symlink():
                    path.unlink()
            return task.view()

    def start(self):
        def maintain():
            while not self.closed.wait(5):
                self.collect()
        self.maintenance = threading.Thread(target=maintain, daemon=True)
        self.maintenance.start()

    def close(self):
        self.closed.set()
        for task in self.tasks.values():
            task.cancel.set()
        for thread in self.threads:
            thread.join(timeout=8)
        if hasattr(self, 'maintenance'):
            self.maintenance.join(timeout=2)
