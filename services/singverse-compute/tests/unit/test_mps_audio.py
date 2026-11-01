"""Numerical guards for tiling: stride, padding, groups, dilation and overlap."""
import importlib.util
import unittest

from singverse_compute.infrastructure.mps_audio import chunked_convolution


@unittest.skipUnless(importlib.util.find_spec('torch'), 'Optional fixed audio runtime required')
class MpsConvolutionTest(unittest.TestCase):
    def test_tiled_convolutions_match_original_at_boundaries(self):
        import torch
        from torch import nn
        torch.manual_seed(17)
        for kind in (nn.Conv1d, nn.ConvTranspose1d):
            for stride, padding, dilation, groups, bias in ((4, 2, 1, 1, True), (1, 3, 2, 2, False), (2, 1, 1, 1, True)):
                with self.subTest(kind=kind.__name__, stride=stride, padding=padding, dilation=dilation, groups=groups, bias=bias):
                    options = {'output_padding': stride-1} if kind == nn.ConvTranspose1d else {}
                    layer = kind(4, 8, 8, stride=stride, padding=padding,
                                 dilation=dilation, groups=groups, bias=bias, **options).eval()
                    samples = torch.randn(2, 4, 137)
                    with torch.inference_mode():
                        expected, actual = layer(samples), chunked_convolution(layer, samples, chunk_size=17)
                    torch.testing.assert_close(actual, expected, rtol=2e-5, atol=2e-6)

    def test_model_length_convolution_matches_original(self):
        import torch
        layer = torch.nn.Conv1d(2, 48, 8, stride=4, padding=2).eval()
        samples = torch.randn(1, 2, 343980)
        with torch.inference_mode():
            torch.testing.assert_close(chunked_convolution(layer, samples), layer(samples), rtol=2e-5, atol=2e-6)

    def test_explicit_mps_command_does_not_enable_fallback(self):
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import Mock
        from singverse_compute.application.audio_roles import AudioRoles
        audio = SimpleNamespace(repo=Path('/fixed/model'), _run=Mock())
        with __import__('tempfile').TemporaryDirectory() as temporary:
            output = Path(temporary)
            folder = output/'htdemucs'/'input'
            folder.mkdir(parents=True)
            for name in ('vocals.wav', 'no_vocals.wav'):
                (folder/name).touch()
            AudioRoles._separate(audio, Path('input.wav'), output, SimpleNamespace(backend='mps'))
        command = audio._run.call_args.args[0]
        self.assertIn('singverse_compute.infrastructure.mps_audio', command)
        self.assertNotIn('cpu', command)
