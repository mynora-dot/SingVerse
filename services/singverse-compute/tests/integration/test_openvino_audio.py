"""Explicit official IR pipeline test; CPU execution here is not Intel GPU qualification."""
import os
from pathlib import Path
import tempfile
import unittest


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_OPENVINO_MODEL_REPO'), 'Explicit fixed OpenVINO model required')
class OpenVinoAudioTest(unittest.TestCase):
    def test_actual_model_preserves_timeline_and_silence(self):
        import numpy as np
        import soundfile as sf
        from singverse_compute.infrastructure.openvino_audio import separate
        model = Path(os.environ['SINGVERSE_TEST_OPENVINO_MODEL_REPO'])
        device = os.environ.get('SINGVERSE_TEST_OPENVINO_DEVICE', 'CPU')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for seconds, silent in [(8, False), (.1, True)]:
                frames = int(44100 * seconds)
                signal = np.sin(np.arange(frames) * 2*np.pi*440/44100) * (.0 if silent else .1)
                source = root/'input.wav'
                sf.write(source, np.column_stack((signal, signal)), 44100, subtype='PCM_16')
                before = source.read_bytes()
                separate(source, root/'out', model, root/'cache', device)
                energy = 0
                for name in ('vocals', 'no_vocals'):
                    value, rate = sf.read(root/'out/htdemucs/input'/f'{name}.wav')
                    self.assertEqual(value.shape, (frames, 2))
                    self.assertEqual(rate, 44100)
                    self.assertTrue(np.isfinite(value).all())
                    energy += float(np.square(value).sum())
                self.assertEqual(source.read_bytes(), before)
                self.assertEqual(energy == 0, silent)
