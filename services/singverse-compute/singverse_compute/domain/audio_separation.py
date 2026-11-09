"""Versioned accompaniment recipes; no runtime imports or request-owned model paths."""
import hashlib
import json
import os
from pathlib import Path
import re

LEGACY = 'LEGACY_DEMUCS'
DSP = 'DSP_FAST'
CLEAN = 'AI_CLEAN'
PROFILES = (LEGACY, DSP, CLEAN)
MDX = 'uvr-mdxnet-9482'
MDX_FILE = 'UVR_MDXNET_9482.onnx'
MDX_SHA256 = '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d'
MDX_BYTES = 29704738
PAN = 'pan=stereo|c0=0.5*c0-0.5*c1|c1=0.5*c0-0.5*c1'
BACKENDS = ('cpu', 'cuda', 'tensorrt', 'openvino', 'openvino-cpu', 'migraphx', 'coreml', 'directml', 'rknn', 'mlx')
PROVIDERS = {'cpu': 'CPUExecutionProvider', 'cuda': 'CUDAExecutionProvider',
             'tensorrt': 'TensorrtExecutionProvider', 'migraphx': 'MIGraphXExecutionProvider',
             'coreml': 'CoreMLExecutionProvider', 'directml': 'DmlExecutionProvider'}


def recipe(profile=LEGACY, model_id=None, output_format='aac', preview=False, source_audio_id=None):
    if profile not in PROFILES or output_format not in ('aac', 'flac') or not isinstance(preview, bool):
        raise ValueError('Invalid separation profile, output format or preview')
    expected = MDX if profile == CLEAN else 'center-cancel-v1' if profile == DSP else 'htdemucs'
    if model_id not in (None, '', expected):
        raise ValueError('Model is not supported by this separation profile')
    if source_audio_id is not None and not re.fullmatch(r'stream:[0-9]{1,3}(?::(?:LEFT|RIGHT))?', source_audio_id):
        raise ValueError('Invalid confirmed source audio identifier')
    value = {'separationProfile': profile, 'modelId': expected, 'outputFormat': output_format,
             'preview': preview, 'sourceAudioId': source_audio_id, 'recipeVersion': 1,
             'precision': 'fp32' if profile == CLEAN else 'float32', 'sampleRate': 44100}
    if profile == CLEAN:
        value.update(modelDigest=MDX_SHA256, nfft=4096, hop=1024, frames=256, trim=2048, compensation=1.0)
    elif profile == DSP:
        value.update(filter=PAN, channelLayout='dual-mono', minimumSideRatioDb=-40)
    value['recipeFingerprint'] = hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return value


def effective_cores():
    """Respect affinity and cgroup quotas without changing affinity or host policy."""
    count = os.cpu_count() or 1
    try:
        count = min(count, len(os.sched_getaffinity(0)))
    except (AttributeError, OSError):
        pass
    try:
        quota, period = Path('/sys/fs/cgroup/cpu.max').read_text().split()
        if quota != 'max':
            count = min(count, max(1, int(quota) // int(period)))
    except (OSError, ValueError):
        try:
            quota = int(Path('/sys/fs/cgroup/cpu/cpu.cfs_quota_us').read_text())
            period = int(Path('/sys/fs/cgroup/cpu/cpu.cfs_period_us').read_text())
            if quota > 0:
                count = min(count, max(1, quota // period))
        except (OSError, ValueError):
            pass
    return max(1, count)


def threads(requested=0):
    if requested:
        return max(1, min(requested, 8, effective_cores()))
    limit = max(1, min(8, effective_cores() - 1))
    try:
        from ..infrastructure.hardware import device_tree
        if 'rk3566' in device_tree('compatible').lower():
            limit = min(limit, 2)
    except OSError:
        pass
    return limit
