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


def separate(source, output, infer):
    import numpy as np
    import soundfile as sf
    import torch
    window = torch.hann_window(NFFT)
    with sf.SoundFile(str(source)) as audio:
        if audio.samplerate != 44100 or audio.channels != 2 or audio.frames < 1:
            raise ValueError('Expected nonempty stereo 44100 Hz input')
        folder = Path(output) / 'htdemucs' / Path(source).stem
        folder.mkdir(parents=True, exist_ok=True)
        with sf.SoundFile(str(folder/'vocals.wav'), 'w', samplerate=44100, channels=2, subtype='PCM_16') as vocal, sf.SoundFile(
                str(folder/'no_vocals.wav'), 'w', samplerate=44100, channels=2, subtype='PCM_16') as backing:
            for start in range(0, audio.frames, STRIDE):
                left, right = max(0, start - TRIM), min(audio.frames, start + STRIDE + TRIM)
                audio.seek(left)
                chunk = audio.read(right-left, dtype='float32', always_2d=True)
                if not np.isfinite(chunk).all():
                    raise ValueError('Nonfinite source samples')
                padded = np.zeros((2, CHUNK), dtype=np.float32)
                offset = left - (start - TRIM)
                padded[:, offset:offset+len(chunk)] = chunk.T
                with torch.inference_mode():
                    spec = torch.stft(torch.from_numpy(padded), NFFT, HOP, window=window, return_complex=True)
                    tensor = torch.view_as_real(spec).permute(0, 3, 1, 2).reshape(1, 4, NFFT//2+1, FRAMES)[:, :, :BINS].contiguous().numpy()
                    tensor[:, :, :3] = 0
                    predicted = np.asarray(infer(tensor), dtype=np.float32)
                    if predicted.shape != tensor.shape or not np.isfinite(predicted).all():
                        raise RuntimeError('Invalid MDX inference output')
                    full = torch.zeros((1, 4, NFFT//2+1, FRAMES))
                    full[:, :, :BINS] = torch.from_numpy(predicted.copy())
                    complex_spec = torch.view_as_complex(full.reshape(2, 2, NFFT//2+1, FRAMES).permute(0, 2, 3, 1).contiguous())
                    waveform = torch.istft(complex_spec, NFFT, HOP, window=window, length=CHUNK).numpy()
                count = min(STRIDE, audio.frames-start)
                # This model predicts vocals. Subtraction preserves the source timeline.
                vocals = waveform[:, TRIM:TRIM+count].T * COMPENSATION
                original = padded[:, TRIM:TRIM+count].T
                vocal.write(np.clip(vocals, -1, 1))
                backing.write(np.clip(original-vocals, -1, 1))
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
