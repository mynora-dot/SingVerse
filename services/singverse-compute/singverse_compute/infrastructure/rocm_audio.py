"""Native gfx1103 Demucs adapter with explicit device and verified, offline model loading."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import time
import sys
from contextlib import ExitStack

from ..application.audio_catalog import BASELINE, manifest
from ..core.audio_runtime import audio_runtime_profile


def load_official_model(model_dir):
    import torch
    from demucs.states import load_model
    from demucs.htdemucs import HTDemucs
    entry = manifest(BASELINE)['files'][0]
    path = Path(model_dir) / entry['name']
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size != entry['size']:
            raise ValueError('Expected the fixed official Demucs weight file')
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024**2), b''):
            digest.update(block)
        if digest.hexdigest() != entry['sha256']:
            raise ValueError('Official Demucs weight SHA256 mismatch')
        stream.seek(0)
        # Torch 2.6+ defaults to weights_only=True, while this official Demucs
        # package contains its constructor. Trust only this exact hashed stream;
        # never patch torch.load globally or allow arbitrary request-supplied weights.
        model = load_model(torch.load(stream, map_location='cpu', weights_only=False), strict=True)
    if (not isinstance(model, HTDemucs) or model.samplerate != 44100
            or model.audio_channels != 2 or list(model.sources) != ['drums', 'bass', 'other', 'vocals']):
        raise ValueError('Unexpected official Demucs audio/model contract')
    return model.eval().requires_grad_(False)


def _device(backend):
    profile = audio_runtime_profile()
    if profile not in ('rocm-native', 'legacy'):
        raise RuntimeError('The exact pinned audio runtime is required')
    if backend not in ('rocm', 'cpu', 'cuda', 'mps'):
        raise ValueError('Expected an explicit inference backend')
    import torch
    if profile == 'legacy':
        if backend == 'mps':
            if not torch.backends.mps.is_available():
                raise RuntimeError('Apple MPS is unavailable')
            # Tensor devices resolve the default MPS index to 0. Keep the
            # explicit device identical for parameter and forward placement checks.
            return torch.device('mps:0')
        if backend in ('cuda', 'rocm'):
            if not torch.cuda.is_available() or (backend == 'rocm') != bool(torch.version.hip):
                raise RuntimeError('Requested GPU runtime is unavailable or mismatched')
            return torch.device('cuda:0')
        return torch.device('cpu')
    if os.environ.get('HSA_OVERRIDE_GFX_VERSION'):
        raise RuntimeError('Forged GPU architecture overrides are forbidden')
    if not torch.version.hip or torch.version.cuda:
        raise RuntimeError('Expected an unambiguous HIP runtime')
    device = torch.device('cuda:0' if backend == 'rocm' else 'cpu')
    if backend == 'rocm':
        if not torch.cuda.is_available():
            raise RuntimeError('ROCm GPU is unavailable; CPU inference was not requested')
        properties = torch.cuda.get_device_properties(device)
        architecture = properties.gcnArchName.split(':')[0]
        if architecture != 'gfx1103' or architecture not in [value.split(':')[0] for value in torch.cuda.get_arch_list()]:
            raise RuntimeError('This native ROCm image requires a real gfx1103 GPU and matching device libraries')
    return device


def _normalization(audio):
    """Two-pass global normalization, bounded RAM, unbiased std as in the original adapter."""
    import numpy as np
    count, mean, variance = 0, 0., 0.
    for samples in audio.blocks(blocksize=65536, dtype='float32', always_2d=True):
        if not np.isfinite(samples).all():
            raise ValueError('Expected finite stereo 44100 Hz input')
        reference = samples.mean(axis=1).astype('float64')
        size, average = len(reference), float(reference.mean())
        delta = average - mean
        total = count + size
        variance += float(((reference - average) ** 2).sum()) + delta * delta * count * size / total
        mean += delta * size / total
        count = total
    audio.seek(0)
    return mean, (variance / (count - 1)) ** .5 if count > 1 else 0.


class NativeRuntime:
    def __init__(self, model_dir, backend):
        self.device = _device(backend)
        self.backend, self.model_dir, self.model = backend, model_dir, None

    def resources(self):
        import torch
        return {'freeMemoryBytes': torch.cuda.mem_get_info(self.device)[0]} if self.backend in ('rocm', 'cuda') else {}

    def separate(self, source, output, *, backing_only=False, progress=None):
        import numpy as np
        import soundfile as sf
        import torch
        from demucs.apply import TensorChunk, apply_model
        started = time.monotonic()
        reused = self.model is not None
        if self.model is None:
            self.model = load_official_model(self.model_dir)
            if self.backend == 'mps':
                from .mps_audio import adapt_convolutions
                adapt_convolutions(self.model)
            self.model = self.model.to(self.device)
        model, device = self.model, self.device
        if any(parameter.device != device for parameter in model.parameters()):
            raise RuntimeError('Model parameters left the explicitly selected device')
        timings = {'modelLoad': round((time.monotonic() - started) * 1000, 3)}
        evidence = {'backend': self.backend, 'device': str(device), 'cpuInferenceFallback': False,
                    'modelReused': reused, 'startedForwards': 0, 'completedForwards': 0}
        if self.backend in ('rocm', 'cuda'):
            torch.cuda.reset_peak_memory_stats(device)

        def before(module, inputs):
            tensors = [value for value in inputs if isinstance(value, torch.Tensor)]
            if not tensors or any(value.device != device for value in tensors):
                raise RuntimeError('Model forward input left the explicitly selected device')
            evidence['startedForwards'] += 1

        def after(module, inputs, result):
            if not isinstance(result, torch.Tensor) or result.device != device:
                raise RuntimeError('Model forward output left the explicitly selected device')
            evidence['completedForwards'] += 1

        folder = Path(output) / BASELINE / Path(source).stem
        folder.mkdir(parents=True, exist_ok=True)
        hooks = [model.register_forward_pre_hook(before), model.register_forward_hook(after)]
        try:
            with ExitStack() as stack, torch.inference_mode():
                audio = stack.enter_context(sf.SoundFile(str(source)))
                frames, rate = len(audio), audio.samplerate
                if rate != model.samplerate or audio.channels != 2 or not frames:
                    raise ValueError('Expected finite stereo 44100 Hz input')
                phase = time.monotonic()
                mean, scale = _normalization(audio)
                timings['normalization'] = round((time.monotonic() - phase) * 1000, 3)
                writers = {'no_vocals': stack.enter_context(sf.SoundFile(str(folder/'no_vocals.wav'),
                    'w', samplerate=rate, channels=2, format='WAV', subtype='PCM_16'))}
                if not backing_only:
                    writers['vocals'] = stack.enter_context(sf.SoundFile(str(folder/'vocals.wav'),
                        'w', samplerate=rate, channels=2, format='WAV', subtype='PCM_16'))
                segment = float(model.segment)
                length = int(rate * segment)
                stride = int(.75 * length)
                # Match Demucs 4.0.1's triangular overlap-add weights and padding.
                weight = torch.cat([torch.arange(1, length//2 + 1, device=device),
                                    torch.arange(length - length//2, 0, -1, device=device)])
                weight = weight / weight.max()
                buffer = torch.zeros(4, 2, length)
                sums = torch.zeros(length)
                inference, writing = 0., 0.
                for offset in range(0, frames, stride):
                    phase = time.monotonic()
                    size = min(length, frames - offset)
                    # The last short chunk needs real preceding context, not new zero padding.
                    start = max(0, offset - (length-size)//2)
                    end = min(frames, offset - (length-size)//2 + length)
                    audio.seek(start)
                    samples = audio.read(end-start, dtype='float32', always_2d=True)
                    mixture = torch.from_numpy(samples.T.copy())
                    mixture = (mixture-mean)/scale if scale >= 1e-8 else torch.zeros_like(mixture)
                    chunk = TensorChunk(mixture[None], offset-start, size)
                    stems = apply_model(model, chunk, device=device, shifts=0, split=False,
                                        overlap=.25, segment=segment, num_workers=0)[0]
                    if stems.shape != (4, 2, size) or not torch.isfinite(stems).all():
                        raise RuntimeError('Non-finite or misaligned model output')
                    buffer[..., :size] += (weight[:size] * stems).cpu()
                    sums[:size] += weight[:size].cpu()
                    inference += time.monotonic() - phase
                    phase = time.monotonic()
                    ready = min(stride, frames-offset)
                    if not torch.all(sums[:ready] > 0):
                        raise RuntimeError('Incomplete overlap weights')
                    current = buffer[..., :ready] / sums[:ready]
                    current.mul_(scale).add_(mean)
                    if not torch.isfinite(current).all():
                        raise RuntimeError('Non-finite complete model output')
                    backing = current[:3].sum(0)
                    writers['no_vocals'].write(backing.clamp(-1, 1).T.contiguous().numpy())
                    if 'vocals' in writers:
                        writers['vocals'].write(current[3].clamp(-1, 1).T.contiguous().numpy())
                    remaining = length-ready
                    if remaining:
                        buffer[..., :remaining] = buffer[..., ready:].clone()
                        sums[:remaining] = sums[ready:].clone()
                    buffer[..., remaining:] = 0
                    sums[remaining:] = 0
                    writing += time.monotonic() - phase
                    if progress:
                        progress({'event': 'AUDIO_INFERENCE_PROGRESS', 'completedFrames': offset+ready,
                                  'frames': frames, 'completedForwards': evidence['completedForwards']})
                if self.backend in ('rocm', 'cuda'):
                    torch.cuda.synchronize(device)
                timings.update(inference=round(inference*1000, 3), stemWrite=round(writing*1000, 3))
                evidence.update(frames=frames, bufferFrames=length, timingsMs=timings,
                                elapsedSeconds=round(time.monotonic()-started, 3))
                if self.backend in ('rocm', 'cuda'):
                    evidence.update(peakGpuAllocatedBytes=torch.cuda.max_memory_allocated(device),
                                    peakGpuReservedBytes=torch.cuda.max_memory_reserved(device))
                if evidence['startedForwards'] != evidence['completedForwards'] or not evidence['completedForwards']:
                    raise RuntimeError('Incomplete model forwards')
                return evidence
        finally:
            for hook in hooks:
                hook.remove()
            # Keep the model, release per-song GPU workspaces between jobs.
            if self.backend in ('rocm', 'cuda'):
                torch.cuda.empty_cache()


def separate(source, output, model_dir, backend, *, backing_only=False):
    evidence = NativeRuntime(model_dir, backend).separate(source, output, backing_only=backing_only)
    print(json.dumps({'event': 'AUDIO_INFERENCE_COMPLETE', **evidence}), flush=True)
    return evidence


def worker(model_dir, backend):
    runtime = NativeRuntime(model_dir, backend)
    for line in sys.stdin:
        request = json.loads(line)
        identifier = request['id']
        def send(value):
            print(json.dumps({'id': identifier, **value}), flush=True)
        try:
            if request['op'] == 'resources':
                result = runtime.resources()
            elif request['op'] == 'separate':
                result = runtime.separate(request['source'], request['output'],
                    backing_only=request.get('backingOnly', False), progress=send)
            else:
                raise ValueError('Unknown audio operation')
            send({'ok': True, 'result': result})
        except Exception as error:
            send({'ok': False, 'error': str(error)[:512]})
            # Never retain potentially poisoned GPU state after an inference error.
            return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--backend', choices=('rocm', 'cpu', 'cuda', 'mps'), required=True)
    parser.add_argument('--backing-only', action='store_true')
    parser.add_argument('--worker', action='store_true')
    parser.add_argument('source', nargs='?')
    args = parser.parse_args()
    if args.worker:
        worker(args.model_dir, args.backend)
    else:
        if not args.source:
            parser.error('source is required outside worker mode')
        separate(args.source, args.output, args.model_dir, args.backend, backing_only=args.backing_only)


if __name__ == '__main__':
    main()
