"""Pinned Intel Demucs v4 IR, using MIT Demucs signal processing on CPU.

No Audacity/GPL plugin code is embedded. The IR contains the neural forward pass;
Demucs 4.0.1 supplies its STFT, inverse STFT and overlap/add implementation.
"""
import argparse
from pathlib import Path


def separate(source, output, model_dir, cache, device):
    import numpy as np
    import openvino as ov
    import soundfile as sf
    import torch
    from demucs.apply import apply_model
    from demucs.htdemucs import HTDemucs

    class Model(torch.nn.Module):
        sources = ['drums', 'bass', 'other', 'vocals']
        samplerate = 44100
        segment = 7.8
        nfft = 4096
        hop_length = 1024
        cac = True
        wiener_iters = 0
        _spec = HTDemucs._spec
        _ispec = HTDemucs._ispec
        _magnitude = HTDemucs._magnitude
        _mask = HTDemucs._mask

        def __init__(self):
            super().__init__()
            core = ov.Core()
            Path(cache).mkdir(parents=True, exist_ok=True)
            # No AUTO/HETERO: a GPU failure must not silently execute on CPU.
            self.compiled = core.compile_model(str(Path(model_dir) / 'htdemucs_fwd.xml'), device,
                                               {'CACHE_DIR': str(cache), 'INFERENCE_PRECISION_HINT': 'f32'})
            actual = self.compiled.get_property('EXECUTION_DEVICES')
            if not actual or any(not str(item).startswith(device.split('.')[0]) for item in actual):
                raise RuntimeError('OpenVINO execution device differs from requested device')
            if {p.get_any_name() for p in self.compiled.inputs} != {'x', 'xt'}:
                raise RuntimeError('Unexpected OpenVINO model inputs')

        def valid_length(self, length):
            if length > 343980:
                raise ValueError('Input exceeds pinned model segment')
            return 343980

        def forward(self, mix):
            z = self._spec(mix)
            x = self._magnitude(z)
            mean, std = x.mean((1, 2, 3), keepdim=True), x.std((1, 2, 3), keepdim=True)
            meant, stdt = mix.mean((1, 2), keepdim=True), mix.std((1, 2), keepdim=True)
            inputs = {'x': ((x - mean) / (1e-5 + std)).contiguous().numpy(),
                      'xt': ((mix - meant) / (1e-5 + stdt)).contiguous().numpy()}
            result = self.compiled(inputs)
            x = torch.from_numpy(np.array(result[self.compiled.output('x_out')], copy=True))
            xt = torch.from_numpy(np.array(result[self.compiled.output('xt_out')], copy=True))
            if tuple(x.shape) != (1, 16, 2048, 336) or tuple(xt.shape) != (1, 8, 343980):
                raise RuntimeError('Unexpected OpenVINO model outputs')
            x = x.reshape(1, 4, 4, 2048, 336) * std[:, None] + mean[:, None]
            x = self._ispec(self._mask(z, x), 343980)
            xt = xt.reshape(1, 4, 2, 343980) * stdt[:, None] + meant[:, None]
            return x + xt

    samples, rate = sf.read(str(source), dtype='float32', always_2d=True)
    if rate != 44100 or samples.shape[1] != 2 or not len(samples) or not np.isfinite(samples).all():
        raise ValueError('Expected finite stereo 44100 Hz input')
    mixture = torch.from_numpy(samples.T.copy())
    reference = mixture.mean(0)
    mean, std = reference.mean(), reference.std() if len(samples) > 1 else torch.tensor(0.)
    model = Model()  # Even silence must qualify the requested model and device.
    with torch.inference_mode():
        if std < 1e-8:
            stems = torch.zeros((4, 2, len(samples)))
        else:
            stems = apply_model(model, ((mixture - mean) / std)[None], shifts=0,
                                overlap=.25, device='cpu', num_workers=0)[0] * std + mean
    if stems.shape != (4, 2, len(samples)) or not torch.isfinite(stems).all():
        raise RuntimeError('Invalid separation output')
    folder = Path(output) / 'htdemucs' / Path(source).stem
    folder.mkdir(parents=True, exist_ok=True)
    for name, stem in [('vocals', stems[3]), ('no_vocals', stems[:3].sum(0))]:
        sf.write(str(folder / (name + '.wav')), stem.clamp(-1, 1).numpy().T, rate, subtype='PCM_16')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--cache', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('source')
    args = parser.parse_args()
    separate(args.source, args.output, args.model_dir, args.cache, args.device)


if __name__ == '__main__':
    main()
