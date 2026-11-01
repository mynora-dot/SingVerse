"""Explicit MPS adapter for pinned Demucs; long convolutions stay on the GPU."""
import argparse
from pathlib import Path


def chunked_convolution(layer, samples, chunk_size=2048):
    """Tile the output grid, preserving receptive fields and overlap contributions."""
    import torch
    from torch import nn
    from torch.nn import functional as F
    if layer.padding_mode != 'zeros' or samples.ndim != 3:
        raise ValueError('Expected batched zero-padded Demucs convolution')
    kernel, stride, padding, dilation = (value[0] for value in
        (layer.kernel_size, layer.stride, layer.padding, layer.dilation))
    if isinstance(layer, nn.Conv1d):
        length = (samples.shape[-1] + 2*padding - dilation*(kernel-1) - 1)//stride + 1
        chunks = []
        for start in range(0, length, chunk_size):
            end = min(start+chunk_size, length)
            left, right = start*stride-padding, (end-1)*stride-padding+dilation*(kernel-1)+1
            part = F.pad(samples[..., max(0, left):min(samples.shape[-1], right)],
                         (max(0, -left), max(0, right-samples.shape[-1])))
            chunks.append(F.conv1d(part, layer.weight, layer.bias, stride, 0, dilation, layer.groups))
        return torch.cat(chunks, -1)
    if not isinstance(layer, nn.ConvTranspose1d):
        raise ValueError('Expected Conv1d or ConvTranspose1d')
    length = (samples.shape[-1]-1)*stride - 2*padding + dilation*(kernel-1) + layer.output_padding[0] + 1
    output = samples.new_zeros(samples.shape[0], layer.out_channels, length)
    for start in range(0, samples.shape[-1], chunk_size):
        part = F.conv_transpose1d(samples[..., start:start+chunk_size], layer.weight, None,
                                  stride, 0, 0, layer.groups, dilation)
        left, right = start*stride-padding, start*stride-padding+part.shape[-1]
        output[..., max(0, left):min(length, right)] += part[..., max(0, -left):part.shape[-1]-max(0, right-length)]
    if layer.bias is not None:
        output += layer.bias[None, :, None]
    return output


def adapt_convolutions(model):
    from torch import nn

    class MpsConvolution(nn.Module):
        def __init__(self, layer):
            super().__init__()
            self.layer = layer

        def forward(self, samples):
            # The fixed MPS runtime rejects very long convolution tensors. Keep
            # each tile small on the same device; never catch errors and use CPU.
            if samples.device.type == 'mps' and samples.shape[-1] > 16000:
                return chunked_convolution(self.layer, samples)
            return self.layer(samples)

    for name, layer in list(model.named_children()):
        if isinstance(layer, (nn.Conv1d, nn.ConvTranspose1d)):
            setattr(model, name, MpsConvolution(layer))
        else:
            adapt_convolutions(layer)


def separate(source, output, model_dir):
    import numpy as np
    import soundfile as sf
    import torch
    from demucs.apply import apply_model
    from demucs.pretrained import get_model
    if not torch.backends.mps.is_available():
        raise RuntimeError('Apple MPS is not available in this native process')
    model = get_model('htdemucs', repo=Path(model_dir)).eval()
    adapt_convolutions(model)
    samples, rate = sf.read(str(source), dtype='float32', always_2d=True)
    if rate != model.samplerate or samples.shape[1] != model.audio_channels or not len(samples) or not np.isfinite(samples).all():
        raise ValueError('Expected finite stereo 44100 Hz input')
    mixture = torch.from_numpy(samples.T.copy())
    reference = mixture.mean(0)
    mean, std = reference.mean(), reference.std() if len(samples) > 1 else torch.tensor(0.)
    with torch.inference_mode():
        # Silence still exercises the actual model and MPS device for qualification.
        normalized = (mixture-mean)/std if std >= 1e-8 else torch.zeros_like(mixture)
        stems = apply_model(model, normalized[None], shifts=0, overlap=.25,
                            device='mps', num_workers=0)[0] * std + mean
    if stems.shape != (4, 2, len(samples)) or not torch.isfinite(stems).all():
        raise RuntimeError('Invalid MPS separation output')
    vocal = model.sources.index('vocals')
    folder = Path(output) / 'htdemucs' / Path(source).stem
    folder.mkdir(parents=True, exist_ok=True)
    for name, stem in [('vocals', stems[vocal]),
                       ('no_vocals', stems[[index for index in range(4) if index != vocal]].sum(0))]:
        sf.write(str(folder / (name+'.wav')), stem.clamp(-1, 1).numpy().T, rate, subtype='PCM_16')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('source')
    args = parser.parse_args()
    separate(args.source, args.output, args.model_dir)


if __name__ == '__main__':
    main()
