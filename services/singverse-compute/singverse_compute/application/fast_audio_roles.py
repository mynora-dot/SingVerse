"""Label-first, bounded classification; never implicitly falls back to separation."""
import json
import math
import os
import queue
import subprocess
import sys
import threading
import time
from importlib.metadata import version, PackageNotFoundError

from .audio_analysis import _label
from .audio_catalog import FAST, manifest
from ..core.errors import ComputeError
from ..infrastructure.transcoding.process import stop
from ..infrastructure.transcoding.memory import resident_bytes

VERSION = 'fast-vocal-v2'


def classify_labels(titles):
    labels = [_label(title) for title in titles]
    if sorted(labels) == ['backing', 'original']:
        original = labels.index('original')
        return {'state': 'CONFIDENT', 'original': original, 'backing': 1 - original,
                'evidence': {'analysisVersion': VERSION, 'method': 'LABELS', 'windows': []}}
    return None


def classify_scores(scores, titles):
    evidence = {'analysisVersion': VERSION, 'method': 'PANNS_CNN6', 'model': FAST, 'vocalScores': scores,
                'decisionPolicy': 'paired-relative-v2',
                'thresholds': {'minimumScore': .05, 'minimumGap': .03, 'minimumRatio': 2.5, 'scoreSmoothing': .005, 'minimumVotes': 2}}
    def unknown(reason):
        return {'state': 'UNKNOWN', 'reason': reason, 'evidence': evidence}
    if len(scores) < 2 or any(not math.isfinite(value) or not 0 <= value <= 1 for pair in scores for value in pair):
        return unknown('INSUFFICIENT_EVIDENCE')
    votes = []
    for first, second in scores:
        if min(first, second) >= .55:
            return unknown('BOTH_VOCAL_TRACKS')
        if max(first, second) >= .05 and abs(first - second) >= .03 and (max(first, second) + .005) / (min(first, second) + .005) >= 2.5:
            votes.append(0 if first > second else 1)
    evidence['votes'] = votes
    if len(set(votes)) > 1:
        return unknown('CONFLICTING_WINDOWS')
    if len(votes) < 2:
        return unknown('AMBIGUOUS_VOCALS')
    original = votes[0]
    if _label(titles[original]) == 'backing' or _label(titles[1 - original]) == 'original':
        return unknown('CONFLICTING_LABELS')
    return {'state': 'CONFIDENT', 'original': original, 'backing': 1 - original, 'evidence': evidence}


class FastAudioRoles:
    def __init__(self, audio):
        self.audio = audio
        self.process = None
        self.stamp = None
        self.lock = threading.RLock()

    @property
    def available(self):
        try:
            return (version('torch').split('+')[0] == '2.5.1' and version('numpy') == '1.26.4'
                    and self.audio.models.model_valid(FAST))
        except PackageNotFoundError:
            return False

    def close(self):
        with self.lock:
            if self.process:
                stop(self.process)
                for stream in (self.process.stdin, self.process.stdout):
                    stream.close()
                self.process = None

    def predict(self, paths, cancel):
        with self.lock:
            if not self.available:
                raise ComputeError(503, 'FAST_MODEL_MISSING')
            path = self.audio.models.model_path(FAST) / manifest(FAST)['files'][0]['name']
            stamp = (path.stat().st_mtime_ns, path.stat().st_size)
            if self.process and (self.process.poll() is not None or stamp != self.stamp):
                self.close()
            if not self.process:
                self.process = subprocess.Popen([sys.executable, '-m', 'singverse_compute.infrastructure.fast_audio', '--model', str(path)],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                    start_new_session=os.name != 'nt',
                    env={**os.environ, 'OMP_NUM_THREADS': '2', 'MKL_NUM_THREADS': '2'})
                self.stamp = stamp
            responses = queue.Queue()
            process = self.process
            def read():
                try:
                    responses.put(process.stdout.readline(65536))
                except (OSError, ValueError):
                    responses.put('')
            try:
                process.stdin.write(json.dumps({'paths': [str(path) for path in paths]}) + '\n')
                process.stdin.flush()
                reader = threading.Thread(target=read, daemon=True)
                reader.start()
                started = time.monotonic()
                while True:
                    if cancel.is_set() or time.monotonic() - started > 45:
                        raise ComputeError(409 if cancel.is_set() else 422, 'Fast classifier cancelled or timed out')
                    memory = resident_bytes(process.pid)
                    if memory and memory > min(self.audio.settings.audio_memory_bytes, 1024**3):
                        raise ComputeError(422, 'Fast classifier exceeded memory budget')
                    try:
                        response = responses.get(timeout=.1)
                        break
                    except queue.Empty:
                        continue
                result = json.loads(response)
                scores = result.get('scores', [])
                if len(scores) != len(paths) or any(not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1 for score in scores):
                    raise ComputeError(422, 'Invalid fast classifier output')
                return scores
            except Exception as error:
                self.close()
                if isinstance(error, ComputeError):
                    raise
                raise ComputeError(422, 'Fast classifier execution failed') from error
