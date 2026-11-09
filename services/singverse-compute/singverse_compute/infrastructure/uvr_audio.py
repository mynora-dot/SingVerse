"""UVR MDXNET 9482 stereo spectral adapter; bounded chunks on CPU DSP."""
import argparse
from pathlib import Path

NFFT = 4096
HOP = 1024
BINS = 2048
FRAMES = 256
CHUNK = HOP * (FRAMES - 1)
TRIM = NFFT // 2
STRIDE = CHUNK - 2 * TRIM
COMPENSATION = 1.0


def spectrum(samples):
    """Match Torch's periodic Hann, centered reflection padding and channel ordering."""
    import numpy as np
    window = np.hanning(NFFT + 1)[:-1].astype(np.float32)
    padded = np.pad(samples, ((0, 0), (TRIM, TRIM)), mode='reflect')
    frames = np.lib.stride_tricks.sliding_window_view(padded, NFFT, axis=-1)[:, ::HOP]
    transformed = np.fft.rfft(frames * window, axis=-1).astype(np.complex64).transpose(0, 2, 1)
    value = np.stack((transformed.real, transformed.imag), axis=1).reshape(1, 4, NFFT//2+1, FRAMES)
    result = np.ascontiguousarray(value[:, :, :BINS])
    result[:, :, :3] = 0
    return result


def waveform(predicted):
    import numpy as np
    window = np.hanning(NFFT + 1)[:-1].astype(np.float32)
    full = np.zeros((2, 2, NFFT//2+1, FRAMES), dtype=np.float32)
    full[:, :, :BINS] = predicted.reshape(2, 2, BINS, FRAMES)
    frames = np.fft.irfft((full[:, 0] + 1j*full[:, 1]).transpose(0, 2, 1), n=NFFT, axis=-1).astype(np.float32)
    signal = np.zeros((2, CHUNK + NFFT), dtype=np.float32)
    normalization = np.zeros(CHUNK + NFFT, dtype=np.float32)
    for index in range(FRAMES):
        start = index * HOP
        signal[:, start:start+NFFT] += frames[:, index] * window
        normalization[start:start+NFFT] += window * window
    return signal[:, TRIM:TRIM+CHUNK] / normalization[TRIM:TRIM+CHUNK]


def separate(source, output, infer, *, float_output=False, backing_only=False, progress=None, metrics=None):
    import numpy as np
    import soundfile as sf
    from contextlib import ExitStack
    with sf.SoundFile(str(source)) as audio:
        if audio.samplerate != 44100 or audio.channels != 2 or audio.frames < 1:
            raise ValueError('Expected nonempty stereo 44100 Hz input')
        folder = Path(output) / 'htdemucs' / Path(source).stem
        folder.mkdir(parents=True, exist_ok=True)
        with ExitStack() as stack:
            subtype = 'FLOAT' if float_output else 'PCM_16'
            vocal = None if backing_only else stack.enter_context(sf.SoundFile(str(folder/'vocals.wav'), 'w', samplerate=44100, channels=2, subtype=subtype))
            backing = stack.enter_context(sf.SoundFile(str(folder/'no_vocals.wav'), 'w', samplerate=44100, channels=2, subtype=subtype))
            peak, forwards = 0., 0
            for start in range(0, audio.frames, STRIDE):
                left, right = max(0, start - TRIM), min(audio.frames, start + STRIDE + TRIM)
                audio.seek(left)
                chunk = audio.read(right-left, dtype='float32', always_2d=True)
                if not np.isfinite(chunk).all():
                    raise ValueError('Nonfinite source samples')
                padded = np.zeros((2, CHUNK), dtype=np.float32)
                offset = left - (start - TRIM)
                padded[:, offset:offset+len(chunk)] = chunk.T
                tensor = spectrum(padded)
                predicted = np.asarray(infer(tensor), dtype=np.float32)
                if predicted.shape != tensor.shape or not np.isfinite(predicted).all():
                    raise RuntimeError('Invalid MDX inference output')
                separated = waveform(predicted)
                count = min(STRIDE, audio.frames-start)
                # This model predicts vocals. Subtraction preserves the source timeline.
                vocals = separated[:, TRIM:TRIM+count].T * COMPENSATION
                original = padded[:, TRIM:TRIM+count].T
                accompaniment = original - vocals
                peak = max(peak, float(np.max(np.abs(accompaniment))))
                if vocal is not None:
                    vocal.write(vocals if float_output else np.clip(vocals, -1, 1))
                backing.write(accompaniment if float_output else np.clip(accompaniment, -1, 1))
                forwards += 1
                if progress:
                    progress({'event': 'AUDIO_INFERENCE_PROGRESS', 'completedFrames': start+count,
                              'frames': audio.frames, 'completedForwards': forwards})
            if metrics is not None:
                metrics.update(frames=audio.frames, peak=peak, completedForwards=forwards)
    return folder


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('source')
    args = parser.parse_args()
    import hashlib
    with open(args.model, 'rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d':
            raise ValueError('Expected pinned UVR MDXNET 9482 model')
    import onnxruntime as ort
    session = ort.InferenceSession(args.model, providers=['CPUExecutionProvider'])
    name = session.get_inputs()[0].name
    separate(args.source, args.output, lambda x: session.run(None, {name: x})[0])


if __name__ == '__main__':
    main()
