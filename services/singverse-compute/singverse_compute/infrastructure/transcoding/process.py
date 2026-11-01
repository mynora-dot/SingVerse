"""Bounded subprocesses, cancellation and process-tree cleanup on supported OSes."""
import os
import signal
import subprocess
import threading
import time
from ...core.errors import ComputeError
from .memory import resident_bytes


def stop(process):
    if process.poll() is not None:
        return
    try:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        else:
            os.killpg(process.pid, signal.SIGKILL)
    except (OSError, subprocess.TimeoutExpired):
        process.kill()
    process.wait(timeout=5)


def run(command, *, timeout=15, limit=1048576, cancel=None, line=None, cwd=None, memory_limit=1024**3, metrics=None, env=None, error_line=None):
    flags = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, cwd=cwd, env=env, **flags)
    except OSError as error:
        raise ComputeError(503, 'Media tool unavailable') from error
    output, errors = bytearray(), bytearray()
    overflow = threading.Event()
    def drain(pipe, target, callback):
        try:
            while chunk := pipe.readline(4096):
                if len(target) + len(chunk) > limit:
                    overflow.set()
                    break
                target.extend(chunk)
                if callback:
                    callback(chunk.decode('utf-8', 'replace').strip())
        finally:
            pipe.close()
    readers = [threading.Thread(target=drain, args=(process.stdout, output, line), daemon=True),
               threading.Thread(target=drain, args=(process.stderr, errors, error_line), daemon=True)]
    for thread in readers:
        thread.start()
    started = time.monotonic()
    last_sample = started-1
    peak = 0
    try:
        while process.poll() is None:
            if time.monotonic()-last_sample >= .25 and (memory_limit or metrics):
                observed=resident_bytes(process.pid)
                last_sample=time.monotonic()
                if observed is not None:
                    peak=max(peak,observed)
                    if metrics:metrics(peak)
                    if memory_limit and observed > memory_limit:
                        stop(process)
                        raise ComputeError(422, 'Media process exceeded memory budget')
            if overflow.is_set() or time.monotonic() - started > timeout or cancel and cancel.is_set():
                stop(process)
                raise ComputeError(409 if cancel and cancel.is_set() else 422, 'Media execution cancelled or exceeded budget')
            time.sleep(.025)
        for thread in readers:
            thread.join(timeout=2)
        if overflow.is_set() or process.returncode:
            raise ComputeError(422, 'Media execution failed')
        return bytes(output)
    finally:
        stop(process)
        for thread in readers:
            thread.join(timeout=2)
