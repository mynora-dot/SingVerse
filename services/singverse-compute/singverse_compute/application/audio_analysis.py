"""Bounded multi-window role analysis; scores are evidence, not probabilities."""
from array import array
import math
import re
import statistics
import sys
import wave

from ..core.errors import ComputeError

ANALYSIS_VERSION = 'vocal-activity-v2'


def sampling_windows(duration):
    # Disjoint windows in five evenly spaced bins, trying the body of the song first.
    seconds = min(20.0, duration / 5)
    return [(duration * (index + .5) / 5 - seconds / 2, seconds) for index in (1, 3, 2, 0, 4)]


def _level(block):
    values = array('h', block)
    if sys.byteorder != 'little':
        values.byteswap()
    return math.sqrt(sum(value * value for value in values) / len(values)) / 32768 if values else 0.0


def vocal_activity(source, vocals):
    """Median vocal/input ratio over audible half-second frames, excluding silence."""
    with wave.open(str(source), 'rb') as original, wave.open(str(vocals), 'rb') as separated:
        if (original.getsampwidth() != 2 or separated.getsampwidth() != 2
                or original.getframerate() != separated.getframerate()
                or original.getnchannels() != separated.getnchannels()
                or original.getnframes() != separated.getnframes()):
            raise ComputeError(422, 'Unexpected analysis audio format or timeline')
        frames = []
        while block := original.readframes(original.getframerate() // 2):
            voice = separated.readframes(len(block) // (2 * original.getnchannels()))
            frames.append((_level(block), _level(voice)))
    peak = max((level for level, _ in frames), default=0)
    active = [(level, voice) for level, voice in frames if level >= max(.003, peak * .1)]
    ratios = [voice / level for level, voice in active]
    return {'level': statistics.median(ratios) if len(active) >= 4 else 0.0,
            'activeFrames': len(active), 'totalFrames': len(frames),
            'vocalFrames': sum(ratio >= .25 for ratio in ratios)}


def _label(title):
    title = title.lower()
    # Negated vocal labels must not be treated as "vocal" by substring matching.
    if any(word in title for word in ('伴奏', '无人声', '纯音乐', 'instrumental', 'karaoke')) or re.search(r'\b(?:no[ _-]?vocals?|without[ _-]+vocals?|off[ _-]+vocal)\b', title):
        return 'backing'
    if any(word in title for word in ('原唱', '人声')) or re.search(r'\b(?:vocals?|lead)\b', title):
        return 'original'
    return ''


def classify(samples, titles):
    evidence = {'vocalRatio': samples, 'model': 'htdemucs', 'analysisVersion': ANALYSIS_VERSION}
    def unknown(reason):
        return {'state': 'UNKNOWN', 'reason': reason, 'evidence': evidence}
    if len(samples) < 2:
        return unknown('INSUFFICIENT_EVIDENCE')
    if any(not math.isfinite(value) or value < 0 for pair in samples for value in pair):
        return unknown('INVALID_EVIDENCE')
    votes, audible = [], 0
    for first, second in samples:
        # An instrumental/silent interval carries no direction, so sample elsewhere.
        if max(first, second) < .12:
            continue
        audible += 1
        ratio = (first + .001) / (second + .001)
        if ratio >= 6 and first >= .25 and second <= .06:
            votes.append(0)
        elif ratio <= 1 / 6 and second >= .25 and first <= .06:
            votes.append(1)
    evidence.update(votes=votes, informativeWindows=audible)
    if len(set(votes)) > 1:
        return unknown('CONFLICTING_WINDOWS')
    if any(min(first, second) >= .12 for first, second in samples):
        return unknown('BOTH_VOCAL_TRACKS')
    if not audible:
        return unknown('NO_RELIABLE_VOCALS')
    if len(votes) < 2 or len(votes) * 3 < audible * 2:
        return unknown('AMBIGUOUS_VOCALS')
    original = votes[0]
    backing = 1 - original
    if _label(titles[original]) == 'backing' or _label(titles[backing]) == 'original':
        return unknown('CONFLICTING_LABELS')
    return {'state': 'CONFIDENT', 'original': original, 'backing': backing, 'evidence': evidence}
