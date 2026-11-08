"""One bounded, killable inference subprocess per application (never fork a GPU runtime)."""
from collections import deque
import json
import logging
import os
import queue
import signal
import subprocess
import threading
import time
import uuid

from ..core.errors import ComputeError
from .transcoding.memory import resident_bytes
from .transcoding.process import stop

log = logging.getLogger(__name__)


class AudioWorker:
    def __init__(self, command, *, env, memory_limit, idle_seconds=60):
        self.command, self.env = command, env
        self.memory_limit, self.idle_seconds = memory_limit, idle_seconds
        self.lock = threading.Lock()
        self.operation = threading.Lock()
        self.handle = None
        self.closed = threading.Event()
        self.last_used = 0
        self.maintenance = threading.Thread(target=self._maintain, daemon=True, name='audio-worker-idle')
        self.maintenance.start()

    def _start(self, key):
        with self.lock:
            if self.closed.is_set():
                raise ComputeError(503, 'Audio worker is stopping')
            if self.handle and (self.handle['key'] != key or self.handle['process'].poll() is not None):
                self._interrupt_locked()
            if self.handle is None:
                try:
                    process = subprocess.Popen(self.command(key), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, env=self.env, **({'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP}
                                                               if os.name == 'nt' else {'start_new_session': True}))
                except OSError as error:
                    raise ComputeError(503, 'Audio worker unavailable') from error
                handle = {'process': process, 'key': key, 'messages': queue.Queue(maxsize=64),
                          'errors': deque(maxlen=8), 'overflow': threading.Event()}
                self.handle = handle
                def output():
                    try:
                        while line := process.stdout.readline(65537):
                            if len(line) > 65536:
                                handle['overflow'].set()
                                break
                            try:
                                value = json.loads(line)
                            except (ValueError, UnicodeError):
                                continue
                            try:
                                handle['messages'].put(value, timeout=.5)
                            except queue.Full:
                                handle['overflow'].set()
                                break
                    finally:
                        process.stdout.close()
                def errors():
                    try:
                        while line := process.stderr.readline(4096):
                            handle['errors'].append(line.decode('utf-8', 'replace')[-1024:])
                    finally:
                        process.stderr.close()
                handle['readers'] = [threading.Thread(target=output, daemon=True, name='audio-worker-output'),
                                     threading.Thread(target=errors, daemon=True, name='audio-worker-errors')]
                for reader in handle['readers']:
                    reader.start()
            return self.handle

    def request(self, key, payload, *, cancel=None, event=None, timeout=7200, idle_timeout=120):
        if not self.operation.acquire(blocking=False):
            raise ComputeError(429, 'Audio worker busy')
        handle = None
        started = time.monotonic()
        activity = started
        try:
            if cancel and cancel.is_set():
                raise ComputeError(409, 'Audio task cancelled')
            handle = self._start(key)
            process, identifier = handle['process'], uuid.uuid4().hex
            try:
                process.stdin.write((json.dumps({**payload, 'id': identifier}) + '\n').encode())
                process.stdin.flush()
            except (OSError, ValueError) as error:
                raise ComputeError(422, 'Audio worker connection failed') from error
            sampled, peak = 0, 0
            while True:
                now = time.monotonic()
                if cancel and cancel.is_set():
                    raise ComputeError(409, 'Audio task cancelled')
                if now - started > timeout or now - activity > idle_timeout or handle['overflow'].is_set():
                    raise ComputeError(422, 'Audio worker exceeded execution or output budget')
                if now - sampled >= .25:
                    observed = resident_bytes(process.pid)
                    peak = max(peak, observed or 0)
                    sampled = now
                    if self.memory_limit and peak > self.memory_limit:
                        raise ComputeError(422, 'Audio process exceeded memory budget')
                try:
                    message = handle['messages'].get(timeout=.025)
                except queue.Empty:
                    if process.poll() is not None:
                        # A process can exit just before its pipe readers enqueue the last response.
                        for reader in handle['readers']:
                            reader.join(timeout=.5)
                        try:
                            message = handle['messages'].get_nowait()
                        except queue.Empty:
                            from .hardware import accelerator_failure, non_accelerator_failure
                            detail = ''.join(handle['errors'])
                            log.warning('Audio worker exited with code %s: %s', process.returncode, detail[-4096:])
                            if accelerator_failure(detail) and not non_accelerator_failure(detail):
                                raise ComputeError(422, 'Hardware accelerator execution failed')
                            raise ComputeError(422, 'Audio worker exited unexpectedly')
                    else:
                        continue
                if not isinstance(message, dict) or message.get('id') != identifier:
                    raise ComputeError(422, 'Invalid audio worker response')
                activity=now
                if message.get('event'):
                    if event:
                        event(message)
                    continue
                if not message.get('ok'):
                    from .hardware import accelerator_failure, non_accelerator_failure
                    detail = message.get('error', '')
                    if accelerator_failure(detail) and not non_accelerator_failure(detail):
                        raise ComputeError(422, 'Hardware accelerator execution failed')
                    raise ComputeError(422, 'Audio inference failed: ' + str(detail)[:512])
                return {**message.get('result', {}), 'sampledPeakRssBytes': peak}
        except BaseException:
            if handle:
                self.interrupt(handle)
            raise
        finally:
            self.last_used = time.monotonic()
            self.operation.release()

    def resident(self):
        with self.lock:
            process = self.handle['process'] if self.handle else None
        return resident_bytes(process.pid) or 0 if process and process.poll() is None else 0

    def _interrupt_locked(self):
        handle, self.handle = self.handle, None
        if not handle:
            return
        process = handle['process']
        # Called by the playback admission path: send a signal without waiting for GPU/pipe cleanup.
        try:
            if process.poll() is None:
                if os.name == 'nt':
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            pass
        def reap():
            try:
                stop(process)
            finally:
                process.stdin.close()
        threading.Thread(target=reap, daemon=True, name='audio-worker-reap').start()

    def interrupt(self, expected=None):
        with self.lock:
            if expected is None or self.handle is expected:
                self._interrupt_locked()

    def _maintain(self):
        while not self.closed.wait(.5):
            if self.operation.acquire(blocking=False):
                try:
                    if time.monotonic() - self.last_used >= self.idle_seconds:
                        self.interrupt()
                finally:
                    self.operation.release()

    def close(self):
        self.closed.set()
        self.interrupt()
        self.maintenance.join(timeout=2)
