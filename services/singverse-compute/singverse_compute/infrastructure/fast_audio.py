"""PANNs Cnn6 inference adapter. No remote code or checkpoint pickle execution.

Architecture: qiuqiangkong/audioset_tagging_cnn, MIT,
revision d2f4b8c18eab44737fcc0de1248ae21eb43f6aa4, pytorch/models.py Cnn6.
Weights: author release Zenodo 3987831, CC-BY-4.0 (see release manifest).
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import wave


def load_model(path):
    import torch
    from torch import nn
    from torch.nn import functional as F
    from ..application.audio_catalog import FAST, manifest
    file = manifest(FAST)['files'][0]
    if path.stat().st_size != file['size'] or hashlib.sha256(path.read_bytes()).hexdigest() != file['sha256']:
        raise ValueError('Classifier checksum mismatch')
    state = torch.load(path, map_location='cpu', weights_only=True)['model']
    real = state.pop('spectrogram_extractor.stft.conv_real.weight')
    imag = state.pop('spectrogram_extractor.stft.conv_imag.weight')
    mel = state.pop('logmel_extractor.melW')
    if real.shape != (513, 1, 1024) or imag.shape != real.shape or mel.shape != (513, 64):
        raise ValueError('Unexpected classifier features')

    class Block(nn.Module):
        def __init__(self, incoming, outgoing):
            super().__init__()
            self.conv1 = nn.Conv2d(incoming, outgoing, 5, padding=2, bias=False)
            self.bn1 = nn.BatchNorm2d(outgoing)

        def forward(self, value):
            return F.avg_pool2d(F.relu_(self.bn1(self.conv1(value))), 2)

    class Cnn6(nn.Module):
        def __init__(self):
            super().__init__()
            self.bn0 = nn.BatchNorm2d(64)
            for index, (incoming, outgoing) in enumerate(((1, 64), (64, 128), (128, 256), (256, 512)), 1):
                setattr(self, 'conv_block' + str(index), Block(incoming, outgoing))
            self.fc1 = nn.Linear(512, 512)
            self.fc_audioset = nn.Linear(512, 527)

        def forward(self, samples):
            spectrum = torch.stft(samples, 1024, 320, window=torch.hann_window(1024),
                                  center=True, pad_mode='reflect', return_complex=True).abs().square()
            features = 10 * torch.log10(torch.matmul(spectrum.transpose(1, 2), mel).clamp_min(1e-10))
            value = features[:, None, :, :]
            value = self.bn0(value.transpose(1, 3)).transpose(1, 3)
            for index in range(1, 5):
                value = getattr(self, 'conv_block' + str(index))(value)
            value = value.mean(dim=3)
            value = value.max(dim=2).values + value.mean(dim=2)
            return torch.sigmoid(self.fc_audioset(F.relu_(self.fc1(value))))

    model = Cnn6()
    model.load_state_dict(state, strict=True)
    return model.eval()


def predict(model, paths):
    import numpy as np
    import torch
    values, silent = [], []
    for path in paths:
        with wave.open(str(path), 'rb') as audio:
            if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or audio.getframerate() != 32000 or not 32000 <= audio.getnframes() <= 32000 * 6:
                raise ValueError('Unexpected classifier audio format or length')
            value = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768
        silent.append(float(np.sqrt(np.mean(value ** 2))) < .002)
        values.append(value)
    if not values or len(values) > 6 or len({len(value) for value in values}) != 1:
        raise ValueError('Unexpected classifier batch')
    # AudioSet indices fixed by author's metadata/class_labels_indices.csv at the revision above.
    voice = (0, 1, 2, 3, 27, 32, 33, 34, 35, 37, 254)
    scores = []
    with torch.inference_mode():
        # One paired excerpt at a time bounds convolution workspace and cached RSS,
        # including repeated six-second requests in the long-lived process.
        for start in range(0, len(values), 2):
            output = model(torch.from_numpy(np.stack(values[start:start + 2])))
            scores.extend(output[:, list(voice)].max(dim=1).values.tolist())
    return [0.0 if quiet else score for score, quiet in zip(scores, silent)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=Path, required=True)
    args = parser.parse_args()
    import torch
    torch.set_num_threads(2)
    model = load_model(args.model)
    for line in sys.stdin:
        try:
            request = json.loads(line)
            result = {'scores': predict(model, request['paths'])}
        except Exception:
            result = {'error': 'FAST_CLASSIFIER_FAILED'}
        print(json.dumps(result, allow_nan=False), flush=True)


if __name__ == '__main__':
    main()
